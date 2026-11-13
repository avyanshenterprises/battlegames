package com.avyansh.battleroyale;

public class Player {
	public static final float RADIUS = 0.4f;
	public float horizontalVX, horizontalVZ;
	public float x, y, z, yaw, vy;
	public boolean onGround = true, crouching, alive = true, swimming;
	public float health = 100f, maxHealth = 100f;
	public float armor = 0f, maxArmor = 100f;
	public float animTime, speedNow, deathT;
	public int kills;
	/** Healing items carried, indexed by Loot.BANDAGE..Loot.ENERGY_DRINK. */
	public final int[] heal = new int[5];
	public int vestLevel = 0;
	public int helmetLevel = 0, backpackLevel = 0;

	public int healCount() { int n = 0; for (int v : heal) n += v; return n; }
	/** How many healing items the backpack can hold. */
	public int healCapacity() { return 4 + 3 * backpackLevel; }
	public float helmetDurability = 0f;
	
	public boolean inAir = false;
	public float airTime = 0f;
	public float lastLandSpeed = 0f;
	public float aimPitchRad = 0f;
	public boolean reloading = false;
	/** movement direction relative to facing (rad, +right), and melee swing / cooldown timers (s) */
	public float moveRel, meleeT, meleeCd;
	private float waterExitT = 0f;   // short smooth lift when climbing out of the water
	
	private static final float GRAVITY = 22f;
	private static final float JUMP_VELOCITY = 7.2f;
	private static final float GROUND_SNAP_UP = 0.60f;   // up a stair tread
	private static final float GROUND_SNAP_DOWN = 1.10f; // down a stair tread (was 0.65 — too tight)
	private static final float STEP_HEIGHT = 0.75f;
	private static final float SWIM_LEVEL_OFFSET = 1.2f;
	private static final float GROUND_TOLERANCE = 1.20f;
	
	public void respawn(float sx, float sz, GameWorld world) {
		x = sx;
		z = sz;
		horizontalVX = horizontalVZ = 0f;
		yaw = 0f;
		y = world.groundForPlayer(sx, sz, world.ground(sx, sz) + 2f, GROUND_TOLERANCE);
		vy = 0f;
		onGround = true;
		health = maxHealth;
		armor = 0f; vestLevel = 0;
        helmetLevel = 0; backpackLevel = 0; helmetDurability = 0f;
		crouching = false;
		speedNow = 0f;
		alive = true;
		deathT = 0f;
		swimming = false;
		waterExitT = 0f;
		inAir = false;
		airTime = 0f;
		lastLandSpeed = 0f;
		java.util.Arrays.fill(heal, 0);
		heal[Loot.BANDAGE] = 2;
	}
	
	public void damage(float d) {
		if (!alive || d <= 0f) return;
        float remaining = d;
        if (helmetLevel > 0 && helmetDurability > 0f) {
            float protection = helmetLevel == 1 ? 0.08f : helmetLevel == 2 ? 0.14f : 0.20f;
            float helmetAbsorb = Math.min(helmetDurability, d * protection);
            helmetDurability -= helmetAbsorb; remaining -= helmetAbsorb;
            if (helmetDurability <= 0f) { helmetDurability = 0f; helmetLevel = 0; }
        }
		float vestFrac = vestLevel == 1 ? 0.30f : vestLevel == 2 ? 0.40f : vestLevel >= 3 ? 0.55f : 0.65f;
		float absorbed = Math.min(armor, remaining * vestFrac);
		armor -= absorbed;
		if (armor <= 0f) { armor = 0f; vestLevel = 0; }
		health -= (remaining - absorbed);
		if (health <= 0f) { health = 0f; alive = false; deathT = 0f; }
	}
	
	/** Zone damage ignores vest and helmet (like the PUBG blue zone). */
	public void zoneDamage(float d) {
		if (!alive || d <= 0f) return;
		health -= d;
		if (health <= 0f) { health = 0f; alive = false; deathT = 0f; }
	}

	public static float turnToward(float cur, float target, float maxStep) {
		float d = target - cur;
		while (d > Math.PI) d -= 2f * (float) Math.PI;
		while (d < -Math.PI) d += 2f * (float) Math.PI;
		if (d > maxStep) d = maxStep;
		if (d < -maxStep) d = -maxStep;
		return cur + d;
	}
	
	private boolean canStand(GameWorld world, float px, float pz, float moveDist) {
		if (!TerrainGenerator.inPlayArea(px, pz, 4f)) return false;
		float surface = world.groundForPlayer(px, pz, y, GROUND_TOLERANCE);
		float maxRise = Math.max(STEP_HEIGHT, moveDist * 1.6f);
		if (swimming) maxRise = 2.5f;   // let a swimmer climb up onto the bank
		if (surface - y > maxRise) return false;
		// probe with feet slightly higher so we can step onto the doorway slab without being blocked
		float probeY = Math.max(y, surface);
		return !world.blockedForPlayer(px, pz, probeY, RADIUS);
	}
	
	private void unstick(GameWorld world) {
		if (!world.blockedForPlayer(x, z, y, RADIUS)) return;
		for (float r = 0.5f; r <= 8f; r += 0.5f) {
			for (int i = 0; i < 16; i++) {
				float a = i * 0.3926991f;
				float nx = x + (float) Math.cos(a) * r, nz = z + (float) Math.sin(a) * r;
				if (!world.blockedForPlayer(nx, nz, y, RADIUS + 0.1f)) { x = nx; z = nz; return; }
			}
		}
	}
	
	public void update(float dt, GameWorld world, float camYaw,
	float moveF, float moveS, boolean jump, boolean wantCrouch,
	boolean wantRun, boolean aiming, boolean faceCam) {
		
		crouching = wantCrouch && !swimming;
		
		float len = (float) Math.sqrt(moveF * moveF + moveS * moveS);
		if (len > 1f) { moveF /= len; moveS /= len; len = 1f; }
		
		float yawRad = (float) Math.toRadians(camYaw);
		float fx = (float) Math.sin(yawRad), fz = -(float) Math.cos(yawRad);
		float rx = (float) Math.cos(yawRad), rz = (float) Math.sin(yawRad);
		float dx = fx * moveF + rx * moveS;
		float dz = fz * moveF + rz * moveS;
		
		float speed = 5.2f;
		if (wantRun && !aiming) speed = 8.0f;
		if (crouching) speed = 2.8f;
		if (aiming) speed *= 0.65f;
		
		unstick(world);
		
		float terrainH = world.generator.heightAt(x, z);
		float waterDepth = GameSettings.WATER_LEVEL - terrainH;
		if (!swimming) {
			if (waterDepth > GameSettings.SWIM_ENTER_DEPTH && waterExitT <= 0f
			&& y < GameSettings.WATER_LEVEL + 0.35f) {
				swimming = true; onGround = false; vy = 0f; waterExitT = 0f;
			}
		} else {
			// BUG FIX: a swimmer floats BELOW the water level (WATER_LEVEL - SWIM_SURFACE_OFFSET), so the
			// old "y > WATER_LEVEL - 0.15" test could never be true and the player stayed in swim mode
			// forever. Leave the water as soon as the bottom is shallow enough to stand on, or when
			// the bank right in front of us is above the water (climb out).
			boolean shallow = waterDepth < GameSettings.SWIM_EXIT_DEPTH;
			boolean bankAhead = false;
			if (len > 0.01f) {
				float ax = x + dx * 0.9f, az = z + dz * 0.9f;
				float aheadH = world.generator.heightAt(ax, az);
				bankAhead = aheadH > GameSettings.WATER_LEVEL - 0.25f
				&& TerrainGenerator.inPlayArea(ax, az, 4f)
				&& !world.blockedForPlayer(ax, az, Math.max(y, aheadH), RADIUS);
			}
			if (shallow || bankAhead) {
				swimming = false;
				onGround = true;
				vy = 0f;
				inAir = false;
				airTime = 0f;
				waterExitT = 0.30f;
			}
		}
		
		float moveSpeed = speed;
		if (swimming) {
			moveSpeed = GameSettings.SWIM_SPEED * (aiming ? 0.6f : 1f);
			jump = false; crouching = false;
		}
		
		if (len > 0.01f) {
			float nx = x + dx * moveSpeed * dt;
			if (canStand(world, nx, z, Math.abs(nx - x))) x = nx;
			float nz = z + dz * moveSpeed * dt;
			if (canStand(world, x, nz, Math.abs(nz - z))) z = nz;
		}
		
		speedNow = len * moveSpeed;
		if (speedNow > 0.1f) animTime += dt * speedNow * 1.6f;
		else animTime += dt * (swimming ? 2.5f : 0f);
		
		// Stable third-person character orientation
		if (faceCam) {
			// Smoothly face the camera's forward direction.
			yaw = turnToward(yaw, yawRad, 12f * dt);
		} else if (len > 0.1f) {
			// FPP / normal movement orientation.
			float moveYaw = (float) Math.atan2(dx, -dz);
			yaw = turnToward(yaw, moveYaw, 10f * dt);
		}
		
		if (len > 0.1f) {
			float rel = (float) Math.atan2(dx, -dz) - yaw;
			while (rel > Math.PI) rel -= 2f * (float) Math.PI;
			while (rel < -Math.PI) rel += 2f * (float) Math.PI;
			moveRel = rel;
		} else moveRel = 0f;
		if (meleeT > 0f) meleeT -= dt;
		if (meleeCd > 0f) meleeCd -= dt;
		
		float surface = world.groundForPlayer(x, z, y, GROUND_TOLERANCE);
		
		if (swimming) {
			float targetY = GameSettings.WATER_LEVEL - GameSettings.SWIM_SURFACE_OFFSET
			+ (float) Math.sin(animTime * 1.4f) * 0.08f;
			if (jump && y < GameSettings.WATER_LEVEL - 0.15f)
			targetY = GameSettings.WATER_LEVEL - 0.2f;
			float k = Math.min(1f, dt * 6f);
			y += (targetY - y) * k;
			vy = 0f; onGround = false; inAir = false; airTime = 0f;
		} else {
			if (jump && onGround && !crouching) {
				vy = JUMP_VELOCITY; onGround = false; inAir = true; airTime = 0f;
			}
			
			if (onGround) {
				float diff = y - surface;
				if (waterExitT > 0f) {
					// climbing out: glide up onto the bank instead of popping
					waterExitT -= dt;
					y += (surface - y) * Math.min(1f, dt * 14f);
					vy = 0f;
				} else if (diff > GROUND_SNAP_DOWN) {
					onGround = false; vy = 0f; inAir = true; airTime = 0f;
				} else if (diff < -GROUND_SNAP_UP) {
					y = surface; vy = 0f;
				} else {
					y = surface; vy = 0f;
				}
			}
			
			if (!onGround) {
				if (waterExitT > 0f) waterExitT -= dt;
				vy -= GRAVITY * dt;
				y += vy * dt;
				airTime += dt;
				if (y <= surface) {
					lastLandSpeed = -vy;
					y = surface; vy = 0f; onGround = true; inAir = false; airTime = 0f;
				}
			}
		}
		
		if (y < -30f) {
			y = surface + 2f; vy = 0f; onGround = false; swimming = false;
		}
	}
}
