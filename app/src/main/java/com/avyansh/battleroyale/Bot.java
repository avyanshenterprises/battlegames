package com.avyansh.battleroyale;

public class Bot {
	public float x, y, z, yaw, vy;
	public boolean onGround = true, alive = true;
	public float health = 100f;
	public float animTime, speedNow, deathT;
	public final Weapon weapon = Weapon.create(Weapon.AR);
	/** Display name (shown above the bot and in the kill feed). */
	public String name = "Bot";
	
	private float fireCooldown = 0f;
	private float wanderAngle;
	private float thinkTimer = 0f;
	private float burstTimer = 0f;
	private int burstCount = 0;
	private Object currentTarget = null;
	private float targetLockTime = 0f;
	
	private static final float ZONE_RUN_SPEED = 3.6f;
	private static final int MAX_BURST = 3;
	private static final float BURST_COOLDOWN = 2.2f;
	private static final float MAX_DAMAGE_PER_HIT = 12f;
	
	private static final float GRAVITY = 22f;
	private static final float GROUND_SNAP_UP = 0.35f;
	private static final float GROUND_SNAP_DOWN = 0.55f;
	
	public void spawn(float sx, float sz, GameWorld world) {
		x = sx; z = sz;
		y = world.ground(sx, sz);
		health = 100f; alive = true; onGround = true;
		vy = 0f;
		deathT = 0f;
		fireCooldown = 1.5f + (float)Math.random() * 2f;
		wanderAngle = (float)(Math.random() * Math.PI * 2);
		burstTimer = 0f;
		burstCount = 0;
		currentTarget = null;
		targetLockTime = 0f;
	}
	
	private boolean hasLineOfSight(GameEngine eng, float tx, float tz, float dist) {
		float dx = tx - x, dz = tz - z;
		float len = Math.max(dist, 0.01f);
		float nx = dx / len, nz = dz / len;
		float terr = CollisionSystem.rayTerrain(
		eng.world.generator,
		x, y + 1.4f, z,
		nx, 0f, nz,
		dist, 1.0f);
		return (terr < 0f) || (terr > dist);
	}
	
	private Object pickTarget(GameEngine eng) {
		Player p = eng.player;
		float bestDist = 60f;
		Object best = null;
		
		if (p.alive) {
			float dx = p.x - x, dz = p.z - z;
			float d = (float)Math.sqrt(dx*dx + dz*dz);
			if (d < bestDist && hasLineOfSight(eng, p.x, p.z, d)) {
				bestDist = d;
				best = p;
			}
		}
		
		for (int i = 0; i < eng.bots.bots.size(); i++) {
			Bot other = eng.bots.bots.get(i);
			if (other == this || !other.alive) continue;
			float dx = other.x - x, dz = other.z - z;
			float d = (float)Math.sqrt(dx*dx + dz*dz);
			if (d < bestDist && hasLineOfSight(eng, other.x, other.z, d)) {
				bestDist = d;
				best = other;
			}
		}
		return best;
	}
	
	public void update(float dt, GameEngine eng) {
		if (!alive) { deathT += dt; return; }
		
		targetLockTime -= dt;
		if (targetLockTime <= 0f) {
			targetLockTime = 0.4f + (float)Math.random() * 0.3f;
			currentTarget = pickTarget(eng);
		}
		
		float targetX = 0f, targetZ = 0f, targetY = 0f;
		boolean hasTarget = false;
		float dist = 999f;
		
		if (currentTarget instanceof Player) {
			Player p = (Player)currentTarget;
			if (p.alive) {
				targetX = p.x; targetY = p.y; targetZ = p.z;
				float dx = targetX - x, dz = targetZ - z;
				dist = (float)Math.sqrt(dx*dx + dz*dz);
				hasTarget = dist < 65f;
			}
		} else if (currentTarget instanceof Bot) {
			Bot b = (Bot)currentTarget;
			if (b.alive) {
				targetX = b.x; targetY = b.y; targetZ = b.z;
				float dx = targetX - x, dz = targetZ - z;
				dist = (float)Math.sqrt(dx*dx + dz*dz);
				hasTarget = dist < 50f;
			}
		}
		
		float moveSpeed = 0f;
		
		if (hasTarget) {
			float dx = targetX - x, dz = targetZ - z;
			float targetYaw = (float)Math.atan2(dx, -dz);
			yaw = Player.turnToward(yaw, targetYaw, 6f * dt);
			
			fireCooldown -= dt;
			if (burstTimer > 0f) burstTimer -= dt;
			
			if (fireCooldown <= 0f && dist < 45f) {
				if (burstTimer <= 0f) {
					burstTimer = BURST_COOLDOWN * (0.8f + (float)Math.random() * 0.6f);
					burstCount = 0;
				}
				if (burstCount < MAX_BURST) {
					fireCooldown = weapon.interval * (2.2f + (float)Math.random() * 1.5f);
					shootAt(targetX, targetY, targetZ, dist, eng);
					burstCount++;
				} else {
					fireCooldown = 0.3f;
				}
			}
			
			thinkTimer -= dt;
			if (thinkTimer <= 0f) {
				thinkTimer = 1.2f + (float)Math.random() * 1.5f;
				wanderAngle = targetYaw + ((float)Math.random() - 0.5f) * 2.5f;
			}
			float wx = (float)Math.sin(wanderAngle), wz = -(float)Math.cos(wanderAngle);
			float spd = 2.4f;
			float nx = x + wx * spd * dt, nz = z + wz * spd * dt;
			if (eng.world.canWalk(x, z, nx, nz, y, 0.4f)) { 
				x = nx; z = nz; 
				moveSpeed = spd;
			}
		} else {
			thinkTimer -= dt;
			if (thinkTimer <= 0f) {
				thinkTimer = 2f + (float)Math.random() * 3f;
				wanderAngle = (float)(Math.random() * Math.PI * 2);
			}
			float wx = (float)Math.sin(wanderAngle), wz = -(float)Math.cos(wanderAngle);
			float nx = x + wx * 1.8f * dt, nz = z + wz * 1.8f * dt;
			if (eng.world.canWalk(x, z, nx, nz, y, 0.4f)) { 
				x = nx; z = nz; 
				moveSpeed = 1.8f;
			}
			yaw = Player.turnToward(yaw, wanderAngle, 4f * dt);
		}
		
		// head for the safe zone when it is closing in (or we are already outside of it)
		if (eng.zone.shouldRelocate(x, z)) {
			float zx = eng.zone.targetX() - x, zz = eng.zone.targetZ() - z;
			float zl = (float) Math.sqrt(zx * zx + zz * zz);
			if (zl > 2f) {
				float nx = x + zx / zl * ZONE_RUN_SPEED * dt, nz = z + zz / zl * ZONE_RUN_SPEED * dt;
				if (eng.world.canWalk(x, z, nx, nz, y, 0.4f)) { x = nx; z = nz; moveSpeed = ZONE_RUN_SPEED; }
				else wanderAngle += 1.3f;
				if (!hasTarget) yaw = Player.turnToward(yaw, (float) Math.atan2(zx, -zz), 5f * dt);
			}
		}

		// ================================================================
		// ★ FIXED GROUND PHYSICS
		// ================================================================
		float ground = eng.world.ground(x, z);
		
		if (onGround) {
			float diff = y - ground;
			if (diff > GROUND_SNAP_DOWN) {
				onGround = false;
				vy = 0f;
			} else if (diff < -GROUND_SNAP_UP) {
				y = ground;
				vy = 0f;
			} else {
				y = ground;
				vy = 0f;
			}
		}
		
		if (!onGround) {
			vy -= GRAVITY * dt;
			y += vy * dt;
			if (y <= ground) {
				y = ground;
				vy = 0f;
				onGround = true;
			}
		}
		
		// Safety
		if (y < -30f) {
			y = ground + 2f;
			vy = 0f;
			onGround = false;
		}
		
		speedNow = moveSpeed;
		animTime += dt * speedNow * 1.4f;
	}
	
	private void shootAt(float tx, float ty, float tz, float dist, GameEngine eng) {
		float missChance = GameMath.clamp(dist / 45f, 0.40f, 0.85f);
		if ((float)Math.random() < missChance) {
			if (currentTarget instanceof Player) {
				eng.camera.yaw += ((float)Math.random() - 0.5f) * 1.2f;
			}
			// Sound based on distance
			if (eng.sound != null) {
				float maxDist = 90f;
				float volumeScale = Math.max(0f, 1f - (dist / maxDist));
				if (volumeScale > 0.05f) {
					eng.sound.playPitchedVolume("fire_ar", 1.3f + (float)Math.random() * 0.2f, volumeScale);
				}
			}
			return;
		}
		
		float baseDmg = weapon.damage * 0.30f;
		float falloff = 1f - dist * 0.008f;
		if (falloff < 0.5f) falloff = 0.5f;
		float dmg = baseDmg * falloff;
		if (dmg < 3f) dmg = 3f;
		if (dmg > MAX_DAMAGE_PER_HIT) dmg = MAX_DAMAGE_PER_HIT;
		
		if (currentTarget instanceof Player) {
			Player p = (Player)currentTarget;
			boolean wasAlive = p.alive;
			p.damage(dmg);
			if (wasAlive && !p.alive) Net.announce(name + " eliminated you");
			if (eng.sound != null) eng.sound.play("hurt");
			GameRenderer r = eng.getRenderer();
			if (r != null) r.flashDamage();
		} else if (currentTarget instanceof Bot) {
			Bot b = (Bot)currentTarget;
			b.damage(dmg);
			if (eng.sound != null) eng.sound.playPitched("hit", 1.1f);
		}
		
		if (eng.sound != null) {
			float maxDist = 90f;
			float volumeScale = Math.max(0f, 1f - (dist / maxDist));
			if (volumeScale > 0.05f) {
				eng.sound.playPitchedVolume("fire_ar", 1.2f + (float)Math.random() * 0.3f, volumeScale);
			}
		}
	}
	
	public void damage(float dmg) {
		if (!alive) return;
		if (dmg <= 0f) return;
		health -= dmg;
		if (health <= 0f) { health = 0f; alive = false; deathT = 0f; }
	}
}
