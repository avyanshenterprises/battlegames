package com.avyansh.battleroyale;

public class GameEngine {
	public final GameCamera camera = new GameCamera();
	public final GameWorld world = new GameWorld();
	public final Player player = new Player();
	public final MatchFlow match = new MatchFlow();
	public final BotManager bots = new BotManager();
	public final WeaponManager weapons = new WeaponManager();
	public final ZoneController zone = new ZoneController();
	/** Name tags computed by the renderer every frame (read by GameActivity). */
	public volatile NameTag[] nameTags = new NameTag[0];
	/** Shared by every player of a match (online: derived from the GAME ID) so zone, bot names and bot spawns match. */
	public volatile long matchSeed = 0L;
	public final LootManager loot = new LootManager();
	public final SoundManager sound = new SoundManager();
	public final Effects effects = new Effects();
	public final CameraShake cameraShake = new CameraShake();
	public final PlayerHealing healing = new PlayerHealing();
	
	// ---- camera / aim systems (settings screen writes these through applyPrefs) ----
	public final CameraRig camRig = new CameraRig();
	public final AimAssist aimAssist = new AimAssist();
	private final float[] aaPos = new float[3 * 12];
	private final int[] aaIds = new int[12];
	private final float[] aaOut = new float[3];
	private float aaFriction = 1f, userLookDeg;
	private PositionalAudio.Occluder audioOcc;
	private final CameraRig.World rigWorld = new CameraRig.World() {
		@Override public float groundAt(float x, float z) { return Math.max(world.generator.heightAt(x, z), GameSettings.WATER_LEVEL); }
		@Override public boolean segmentBlocked(float x0, float y0, float z0, float x1, float y1, float z1) {
			float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
			float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (len < 0.01f) return false;
			return CollisionSystem.rayTerrain(world.generator, x0, y0, z0, dx / len, dy / len, dz / len, len, 1.5f) >= 0f;
		}
	};
	/** Occlusion probe for the 3D gunfire audio (terrain and buildings muffle distant shots). */
	public PositionalAudio.Occluder audioOccluder() {
		if (audioOcc == null) audioOcc = new PositionalAudio.TerrainOccluder(rigWorld);
		return audioOcc;
	}
	/** Reads the settings screen values: aim assist, hip/ADS/scope sensitivity. */
	public void applyPrefs(android.content.SharedPreferences sp) {
		aimAssist.enabled = sp.getBoolean("aa_on", true);
		aimAssist.strength = sp.getFloat("aa_strength", 0.5f);
		camRig.hipMul = sp.getFloat("sens_hip", 1.0f);
		camRig.adsMul = sp.getFloat("sens_ads", 0.75f);
		camRig.scope2xMul = sp.getFloat("sens_2x", 0.55f);
		camRig.scope4xMul = sp.getFloat("sens_4x", 0.38f);
		camRig.scope6xMul = sp.getFloat("sens_6x", 0.26f);
	}
	
	public volatile boolean fireHeld, aiming, crouchOn, runOn;
	public volatile boolean jumpReq, reloadReq, switchReq, dropWeaponReq, meleeReq;
	public volatile boolean respawnReq;
	/** Emotes: names found in the player GLB (set by the renderer), and a pending request from the HUD. */
	public volatile String[] emoteNames = new String[0];
	public volatile int emoteRequest = -1;
	public volatile boolean firstPerson = GameSettings.DEFAULT_FIRST_PERSON;
	
	private float inMoveF, inMoveS;
	private float inLookDx, inLookDy;
	
	public float timeOfDay = 0.5f;
	public float dayFactor, nightFactor;
	public final float[] sunDir = new float[3];
	public final float[] lightDir = new float[3];
	public final float[] lightColor = new float[3];
	public final float[] skySunColor = new float[3];
	public final float[] ambient = new float[3];
	public final float[] horizon = new float[3];
	public final float[] zenith = new float[3];
	public final float[] fog = new float[3];
	public final float[] waterColor = new float[3];
	private final float[] tmp = new float[3];
	
	private static final float[] DAY_H = { 0.72f, 0.84f, 0.96f };
	private static final float[] DAY_Z = { 0.16f, 0.40f, 0.82f };
	private static final float[] NIGHT_H = { 0.035f, 0.055f, 0.12f };
	private static final float[] NIGHT_Z = { 0.004f, 0.010f, 0.035f };
	private static final float[] TWI_H = { 1.0f, 0.45f, 0.20f };
	private static final float[] SUN_DAY = { 1.0f, 0.96f, 0.86f };
	private static final float[] SUN_TWI = { 1.0f, 0.55f, 0.30f };
	private static final float[] MOON = { 0.22f, 0.30f, 0.50f };
	private static final float[] AMB_DAY = { 0.40f, 0.44f, 0.52f };
	private static final float[] AMB_NIGHT = { 0.10f, 0.12f, 0.20f };
	private static final float[] WATER_DAY = { 0.05f, 0.27f, 0.40f };
	private static final float[] WATER_NIGHT = { 0.01f, 0.03f, 0.07f };
	private static final float[] MOON_DIR = { -0.35f, 0.85f, 0.30f };
	
	private volatile GameRenderer renderer;
	public GameRenderer getRenderer() { return renderer; }
	public void setRenderer(GameRenderer r) { this.renderer = r; }
	
	private boolean matchInitialized = false;
	public volatile boolean lobby = true;
	/** Lobby squad: names of the teammates standing next to the hero (set by LobbyActivity). */
	public volatile String[] lobbyMates = new String[0];
	/** Screen position (0..1) of the name tags above the hero (index 0) and teammates, written by the renderer. */
	public final float[] lobbyTagX = new float[4], lobbyTagY = new float[4];
	private boolean wasSwimming = false;
	// GameEngine.java - Add field
	public volatile int totalParticipants = 50;
	public volatile int realPlayerCount = 1;
	public volatile int botCount = 49;
	
	// Add method
	public void setMatchInfo(int real, int bots) {
		this.realPlayerCount = real;
		this.botCount = bots;
		this.totalParticipants = real + bots;
	}
	public GameEngine() {
		float px = 90f, pz = 60f;
		float h = Math.max(world.ground(px, pz), 0f);
		player.x = px;
		player.y = h;
		player.z = pz;
		player.yaw = (float) Math.PI;      // lobby: hero faces the camera (front view)
		player.onGround = true;
		player.alive = true;
		camera.pos[0] = px;
		camera.pos[1] = h + 1.55f;
		camera.pos[2] = pz + 4.6f;
		camera.yaw = 0f;
		camera.pitch = -3f;
		match.phase = MatchPhase.LOBBY;
		updateAtmosphere();
		camera.update();
	}
	
	/** Starts a real match only after the lobby START button is pressed. */
	public synchronized void startMatch() {
		if (!lobby && matchInitialized) return;
		lobby = false;
		match.reset();
		match.plane.respawn();
		if (ServerNet.inServerMatch()) ServerNet.alignPlane(match.plane);
		long seed = matchSeed != 0L ? matchSeed : System.nanoTime();
		bots.spawnAll(world, seed);
		zone.reset(seed, TerrainGenerator.PLAY_RADIUS);
		if (ServerNet.inServerMatch()) loot.loadServerLoot(ServerNet.startLoot()); else loot.spawnAll(world.generator);
		weapons.resetForMatch();
		player.respawn(match.plane.x, match.plane.z, world);
		camera.pos[0] = match.plane.x - match.plane.dirX * 65f;
		camera.pos[1] = match.plane.y + 55f;
		camera.pos[2] = match.plane.z - match.plane.dirZ * 65f;
		camera.yaw = (float) Math.toDegrees(Math.atan2(match.plane.dirX, -match.plane.dirZ));
		camera.pitch = -48f;
		matchInitialized = true;
		if (ServerNet.inServerMatch()) ServerNet.attach(this);
	}
	
	public float inputMoveF() { return inMoveF; }
	public float inputMoveS() { return inMoveS; }
	
	public synchronized void setMove(float forward, float strafe) {
		inMoveF = forward;
		inMoveS = strafe;
	}
	
	public synchronized void addLook(float dx, float dy) {
		inLookDx += dx;
		inLookDy += dy;
	}
	
	private void initMatchIfNeeded() {
		if (matchInitialized) return;
		match.reset();
		match.plane.respawn();
		bots.spawnAll(world);
		loot.spawnAll(world.generator);
		
		camera.pos[0] = match.plane.x - match.plane.dirX * 65f;
		camera.pos[1] = match.plane.y + 55f;
		camera.pos[2] = match.plane.z - match.plane.dirZ * 65f;
		camera.yaw = (float) Math.toDegrees(Math.atan2(match.plane.dirX, -match.plane.dirZ));
		camera.pitch = -48f;
		matchInitialized = true;
	}
	
	// ---------------- scope (Free Fire style) ----------------
	/** 0 = normal third person, 1 = fully in the scope (eye-level, zoomed, scope frame drawn). */
	public float scopeBlend = 0f;
	private final float[] tppPos = new float[3];
	private boolean tppInit = false;
	private float tppSmoothY = 0f;
	private float tppCamT = 0f;
	
	private boolean scopeWanted() {
		return aiming && match.phase == MatchPhase.PLAYING && player.alive && !healing.isHealing();
	}
	
	public float scopeZoom() {
		Weapon w = weapons.current();
		return w == null ? GameSettings.SCOPE_ZOOM_SMALL : w.zoom;
	}
	
	public boolean sniperScope() { return weapons.current() != null && weapons.current().category == Weapon.CAT_SNIPER; }
	
	/** Eased 0..1 amount for the scope frame overlay (only appears once the zoom is mostly done). */
	public float scopeFrameAlpha() {
		float t = (scopeBlend - 0.55f) / 0.45f;
		if (t <= 0f) return 0f;
		if (t >= 1f) return 1f;
		return t * t * (3f - 2f * t);
	}
	
	private void updateFov() {
		float base = GameSettings.FOV_DEG;
		float target = base;
		if (scopeBlend > 0.001f) {
			float z = Math.max(1f, scopeZoom());
			float zoomed = (float) Math.toDegrees(2.0 * Math.atan(Math.tan(Math.toRadians(base * 0.5)) / z));
			float e = scopeBlend * scopeBlend * (3f - 2f * scopeBlend);
			target = base + (zoomed - base) * e;
		}
		camera.setFov(target);
	}
	
	/** Toggle FPP/TPP. */
	public void toggleView() {
		firstPerson = !firstPerson;
	}
	
	public void update(float dt) {
		if (lobby) {
			camera.update();
			return;
		}
		initMatchIfNeeded();
		
		float ldx, ldy;
		synchronized (this) {
			ldx = inLookDx;
			ldy = inLookDy;
			inLookDx = 0f;
			inLookDy = 0f;
		}
		// sensitivity follows the zoom so a 4.5x scope does not feel 4.5x twitchier
		camRig.baseSensitivity = GameSettings.LOOK_SENSITIVITY;
		float sens = camRig.sensitivity(camera.getScopeZoom(), aiming) * aaFriction;
		userLookDeg = (Math.abs(ldx) + Math.abs(ldy)) * sens;
		camera.yaw += ldx * sens;
		float pMin = (match.phase == MatchPhase.PLAYING || match.phase == MatchPhase.DEAD || match.phase == MatchPhase.WIN)
		&& !firstPerson ? GameSettings.TPP_PITCH_MIN : -80f;
		float pMax = (match.phase == MatchPhase.PLAYING || match.phase == MatchPhase.DEAD || match.phase == MatchPhase.WIN)
		&& !firstPerson ? GameSettings.TPP_PITCH_MAX : 80f;
		if (scopeBlend > 0.5f) { pMin = -80f; pMax = 80f; }
		camera.pitch = GameMath.clamp(camera.pitch - ldy * sens, pMin, pMax);
		if (camera.yaw > 360f) camera.yaw -= 360f;
		if (camera.yaw < -360f) camera.yaw += 360f;
		
		match.update(dt, this);
		
		switch (match.phase) {
			case AIRPLANE:
			updateCameraInPlane(dt);
			break;
			case FREEFALL:
			case PARACHUTE:
			updateCameraFalling(dt);
			break;
			case PLAYING:
			updatePlaying(dt);
			break;
			case DEAD:
			case WIN:
			updateCameraPlaying(dt);
			if (respawnReq) {
				respawnReq = false;
				respawnPlayer();
			}
			break;
		}
		
		updateFov();
		camera.update();
		
		if (Net.active && Net.inMatch) { Net.tick(dt, this); Net.applyIncoming(this); }
		else if (Net.killFeedTime > 0f) Net.killFeedTime -= dt;   // offline kill feed fades out too
		if (!lobby && matchInitialized) zone.update(dt, this);
		weapons.update(dt);
		boolean canFire = (match.phase == MatchPhase.PLAYING) && player.alive && !ServerNet.iAmKnocked
		&& !healing.isHealing() && weapons.hasWeapon();
		if (fireHeld && canFire) {
			if (weapons.tryFire(true)) {
				firePlayer(dt);
				healing.onFired();
			}
		} else {
			weapons.tryFire(false);
		}
		if (meleeReq) { meleeReq = false; CombatSystem.melee(this); }
		if (reloadReq) { weapons.reload(); sound.play("reload"); reloadReq = false; ServerNet.sendReload(); }
		if (switchReq) {
			weapons.switchNext(); switchReq = false;
			if (weapons.current() != null) ServerNet.sendSwitch(weapons.current().type);
		}
		if (dropWeaponReq) { loot.dropCurrent(this); dropWeaponReq = false; }
		
		player.reloading = weapons.isReloading();
		healing.update(dt, player);
		
		if (match.phase == MatchPhase.PLAYING || match.phase == MatchPhase.DEAD) {
			bots.update(dt, this);
		}
		
		if (match.phase == MatchPhase.PLAYING && player.alive) {
			loot.update(this);
		}
		
		if (!GameSettings.FREEZE_TIME) {
			timeOfDay += dt / GameSettings.DAY_LENGTH_SECONDS;
			if (timeOfDay >= 1f) timeOfDay -= 1f;
		}
		updateAtmosphere();
		effects.update(dt);
		cameraShake.update(dt);
	}
	
	private void firePlayer(float dt) {
		Weapon w = weapons.current();
		if(w==null) return;
		cameraShake.fire(GameSettings.SHAKE_FIRE * (w.category == Weapon.CAT_SNIPER ? 1.6f : w.category == Weapon.CAT_SHOTGUN ? 1.2f : 1f));
		
		// Recoil kicks the pitch up
		camera.pitch += (float) Math.toDegrees(weapons.takeRecoil());
		camera.pitch = GameMath.clamp(camera.pitch, -80f, 80f);
		
		CombatSystem.firePlayer(this);
		sound.playPitched("fire_" + w.fireSound,
		w.firePitch * (0.95f + (float) Math.random() * 0.1f));
		
		// Muzzle flash
		if (effects != null && camera != null) {
			if (!firstPerson && scopeBlend < 0.5f) {
				// third person: flash comes out of the gun in the character's hands, aimed at the crosshair
				float yr = player.yaw;
				float mx = player.x + (float) Math.sin(yr) * 0.85f + (float) Math.cos(yr) * 0.22f;
				float my = player.y + (player.crouching ? 0.95f : 1.30f);
				float mz = player.z - (float) Math.cos(yr) * 0.85f + (float) Math.sin(yr) * 0.22f;
				float ax = camera.pos[0] + camera.fx * 80f - mx;
				float ay = camera.pos[1] + camera.fy * 80f - my;
				float az = camera.pos[2] + camera.fz * 80f - mz;
				float al = (float) Math.sqrt(ax * ax + ay * ay + az * az);
				if (al > 1e-4f) { ax /= al; ay /= al; az /= al; }
				effects.muzzle(mx, my, mz, ax, ay, az);
			} else {
				float mx = camera.pos[0] + camera.fx * 0.9f;
				float my = camera.pos[1] + camera.fy * 0.9f - 0.12f;
				float mz = camera.pos[2] + camera.fz * 0.9f;
				effects.muzzle(mx, my, mz, camera.fx, camera.fy, camera.fz);
			}
		}
	}
	
	/** Free orbit camera around the airplane (and the player standing on it): look anywhere, never lose it. */
	private void updateCameraInPlane(float dt) {
		Airplane p = match.plane;
		float dist = 55f;
		float yr = (float) Math.toRadians(camera.yaw), pr = (float) Math.toRadians(camera.pitch);
		float cp = (float) Math.cos(pr);
		float fx = (float) Math.sin(yr) * cp, fy = (float) Math.sin(pr), fz = -(float) Math.cos(yr) * cp;
		float fxp = p.x, fyp = p.y + ModelCache.PLANE_TOP + 1f, fzp = p.z;
		camera.pos[0] = fxp - fx * dist;
		camera.pos[1] = fyp - fy * dist;
		camera.pos[2] = fzp - fz * dist;
	}
	
	/** Orbit camera around the falling / parachuting player, so he stays in view whichever way you look. */
	private void updateCameraFalling(float dt) {
		Player pl = player;
		float dist = 9f;
		float yr = (float) Math.toRadians(camera.yaw), pr = (float) Math.toRadians(camera.pitch);
		float cp = (float) Math.cos(pr);
		float fx = (float) Math.sin(yr) * cp, fy = (float) Math.sin(pr), fz = -(float) Math.cos(yr) * cp;
		float tx = pl.x, ty = pl.y + 1.1f, tz = pl.z;
		camera.pos[0] = tx - fx * dist;
		camera.pos[1] = ty - fy * dist;
		camera.pos[2] = tz - fz * dist;
		
		float minAbove = Math.max(world.generator.heightAt(camera.pos[0], camera.pos[2]),
		GameSettings.WATER_LEVEL);
		if (camera.pos[1] < minAbove + 1.5f) camera.pos[1] = minAbove + 1.5f;
	}
	
	private void updatePlaying(float dt) {
		final boolean control = player.alive;
		
		boolean run = runOn && !ServerNet.iAmKnocked;
		boolean crouch = crouchOn;
		final float mvF = ServerNet.iAmKnocked ? inMoveF * 0.2f : inMoveF, mvS = ServerNet.iAmKnocked ? inMoveS * 0.2f : inMoveS;
		boolean wasOnGround = player.onGround;
		
		// Capture pre-frame swim state for splash
		boolean wasSwim = player.swimming;
		
		player.update(dt, world, camera.yaw, mvF, mvS,
		jumpReq && control, crouch, run, aiming,
		!firstPerson || aiming || fireHeld || scopeBlend > 0.2f);
		
		// Swim entry/exit splash
		if (player.swimming && !wasSwim) {
			if (effects != null) effects.waterSplash(player.x, GameSettings.WATER_LEVEL, player.z, 1.0f);
			sound.playPitchedVolume("land", 1.3f, 0.6f);
		}
		if (!player.swimming && wasSwim) {
			if (effects != null) effects.waterSplash(player.x, GameSettings.WATER_LEVEL, player.z, 0.6f);
		}
		
		// Landing: fall damage + landing sound + dust
		if (wasOnGround == false && player.onGround && player.lastLandSpeed > 0f) {
			float landSpd = player.lastLandSpeed;
			player.lastLandSpeed = 0f;
			if (landSpd > GameSettings.FALL_SAFE_SPEED) {
				if (GameSettings.FALL_DAMAGE_ON && !ServerNet.inServerMatch()) {
					float dmg = (landSpd - GameSettings.FALL_SAFE_SPEED) * GameSettings.FALL_DAMAGE_PER_UNIT;
					if (dmg > 0f) {
						player.damage(dmg);
						healing.onDamaged(dmg);
						cameraShake.hit(Math.min(1.5f, dmg / 20f));
					}
				}
				cameraShake.land(Math.min(1f, landSpd / 22f));
				if (effects != null) effects.groundDust(player.x, player.y, player.z,
				Math.min(1.5f, landSpd / 15f));
				sound.playPitchedVolume("land", 1.0f + landSpd * 0.01f, 0.8f);
			}
		}
		
		jumpReq = false;
		
		// Aim assist: friction + gentle pull toward the nearest hostile in the crosshair cone. It never snaps onto a target
		// (the last degrees are always left to the player) and fades while the player is steering the camera.
		aaFriction = 1f;
		if (aimAssist.enabled && player.alive && (aiming || fireHeld)) {
			int n = collectAimTargets();
			if (n > 0) {
				aimAssist.update(dt, camera.pos[0], camera.pos[1], camera.pos[2], camera.yaw, camera.pitch, n, aaPos, aaIds, aiming, userLookDeg, aaOut);
				camera.yaw += aaOut[0];
				camera.pitch = GameMath.clamp(camera.pitch + aaOut[1], -80f, 80f);
				aaFriction = aaOut[2];
			} else aimAssist.update(dt, 0, 0, 0, 0, 0, 0, aaPos, aaIds, aiming, 0f, aaOut);
		}
		
		// Feed aim pitch into player for anim pose
		player.aimPitchRad = (float) Math.toRadians(camera.pitch);
		
		updateCameraPlaying(dt);
		
		if (player.y < -40f && !ServerNet.inServerMatch()) respawnPlayer();
	}
	
	
	/** Fills aaPos/aaIds with hostile, alive targets within assist range that have a clear line from the camera. Returns the count. */
	private int collectAimTargets() {
		int n = 0;
		final float r2 = aimAssist.maxRange * aimAssist.maxRange;
		for (int i = 0; i < bots.bots.size() && n < 12; i++) {
			Bot b = bots.bots.get(i);
			if (!b.alive) continue;
			float dx = b.x - player.x, dz = b.z - player.z;
			if (dx * dx + dz * dz > r2) continue;
			if (!aimClear(b.x, b.y + 1.2f, b.z)) continue;
			aaPos[n * 3] = b.x; aaPos[n * 3 + 1] = b.y + 1.2f; aaPos[n * 3 + 2] = b.z; aaIds[n] = 1000 + i; n++;
		}
		if (Net.active && Net.inMatch) synchronized (Net.remotes) {
			for (int i = 0; i < Net.remotes.size() && n < 12; i++) {
				NetPlayer o = Net.remotes.get(i);
				if (!o.alive || o.knocked || Net.isFriend(o)) continue;
				float dx = o.x - player.x, dz = o.z - player.z;
				if (dx * dx + dz * dz > r2) continue;
				if (!aimClear(o.x, o.y + 1.2f, o.z)) continue;
				aaPos[n * 3] = o.x; aaPos[n * 3 + 1] = o.y + 1.2f; aaPos[n * 3 + 2] = o.z; aaIds[n] = o.id.hashCode(); n++;
			}
		}
		return n;
	}
	
	private boolean aimClear(float tx, float ty, float tz) {
		float dx = tx - camera.pos[0], dy = ty - camera.pos[1], dz = tz - camera.pos[2];
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 0.5f) return true;
		return CollisionSystem.rayTerrain(world.generator, camera.pos[0], camera.pos[1], camera.pos[2], dx / len, dy / len, dz / len, len - 0.5f, 1.5f) < 0f;
	}
	
	private void updateCameraPlaying(float dt) {
		Player pl = player;
		float pitchRad = (float) Math.toRadians(camera.pitch);
		float yawRad   = (float) Math.toRadians(camera.yaw);
		float cp = (float) Math.cos(pitchRad), sp = (float) Math.sin(pitchRad);
		float sy = (float) Math.sin(yawRad),   cy = (float) Math.cos(yawRad);
		// camera forward vector
		float fX = sy * cp, fY = sp, fZ = -cy * cp;
		
		// ---- scope blend: 0 = third person, 1 = eye-level scope ----
		float want = scopeWanted() ? 1f : 0f;
		if (!pl.alive) { want = 0f; scopeBlend = 0f; }
		scopeBlend += (want - scopeBlend) * (1f - (float) Math.exp(-GameSettings.SCOPE_BLEND_SPEED * dt));
		if (Math.abs(want - scopeBlend) < 0.002f) scopeBlend = want;
		float ease = scopeBlend * scopeBlend * (3f - 2f * scopeBlend);
		
		float eyeH = pl.crouching ? GameSettings.FPP_EYE_HEIGHT_CROUCH : GameSettings.FPP_EYE_HEIGHT;
		if (pl.swimming) eyeH = 0.35f;
		float eyeX = pl.x + sy * 0.12f;
		float eyeY = pl.y + eyeH;
		float eyeZ = pl.z - cy * 0.12f;
		
		// ---- first person (view toggle) ----
		if (firstPerson && pl.alive) {
			camera.pos[0] = pl.x;
			camera.pos[1] = pl.y + eyeH;
			camera.pos[2] = pl.z;
			tppInit = false;
			return;
		}
		
		// ---- Free Fire third person: orbit a head-height pivot, shifted to the right shoulder ----
		float rightX = cy, rightZ = sy;
		float pivotH = pl.crouching ? GameSettings.TPP_HEIGHT_CROUCH : GameSettings.TPP_HEIGHT;
		if (pl.swimming) pivotH = 0.9f;
		
		// smooth only the vertical component so stair steps / landings do not jolt the camera
		if (!tppInit) { tppSmoothY = pl.y; tppCamT = GameSettings.TPP_DISTANCE; }
		float kY = 1f - (float) Math.exp(-14f * dt);
		tppSmoothY += (pl.y - tppSmoothY) * kY;
		if (Math.abs(pl.y - tppSmoothY) > 2.5f) tppSmoothY = pl.y;
		
		float headX = pl.x, headY = tppSmoothY + pivotH, headZ = pl.z;
		float pivX = headX + rightX * GameSettings.TPP_SHOULDER;
		float pivY = headY;
		float pivZ = headZ + rightZ * GameSettings.TPP_SHOULDER;
		
		float wantX = pivX - fX * GameSettings.TPP_DISTANCE;
		float wantY = pivY - fY * GameSettings.TPP_DISTANCE;
		float wantZ = pivZ - fZ * GameSettings.TPP_DISTANCE;
		
		// direction + length from the head to where we want the camera
		float dX = wantX - headX, dY = wantY - headY, dZ = wantZ - headZ;
		float dl = (float) Math.sqrt(dX * dX + dY * dY + dZ * dZ);
		if (dl < 0.01f) dl = 0.01f;
		dX /= dl; dY /= dl; dZ /= dl;
		
		// camera collision: terrain, walls, roofs and floors between the head and the camera
		float allowed = dl;
		float t = CollisionSystem.rayTerrain(world.generator, headX, headY, headZ, dX, dY, dZ, dl, 0.35f);
		if (t >= 0f) allowed = Math.max(GameSettings.TPP_MIN_DISTANCE * 0.5f, t - 0.35f);
		
		if (!tppInit) tppCamT = allowed;
		if (allowed < tppCamT) tppCamT = allowed;                         // pulled in: instantly
		else tppCamT = Math.min(allowed, tppCamT + 9f * dt);              // pushed out: ease
		tppInit = true;
		
		float cx = headX + dX * tppCamT;
		float cyy = headY + dY * tppCamT;
		float cz = headZ + dZ * tppCamT;
		
		float g = Math.max(world.generator.heightAt(cx, cz), GameSettings.WATER_LEVEL);
		if (cyy < g + 0.6f) cyy = g + 0.6f;
		
		tppPos[0] = cx; tppPos[1] = cyy; tppPos[2] = cz;
		
		// slide from the third-person position to the scope eye position
		camera.pos[0] = tppPos[0] + (eyeX - tppPos[0]) * ease;
		camera.pos[1] = tppPos[1] + (eyeY - tppPos[1]) * ease;
		camera.pos[2] = tppPos[2] + (eyeZ - tppPos[2]) * ease;
	}
	
	private void respawnPlayer() {
		TerrainGenerator.Site s = world.generator.sites[0];
		player.respawn(s.x, s.z, world);
		zone.reset(matchSeed != 0L ? matchSeed : System.nanoTime(), TerrainGenerator.PLAY_RADIUS);
		weapons.resetForMatch();
		healing.cancel();
		match.phase = MatchPhase.PLAYING;
	}
	
	private void updateAtmosphere() {
		float a = (float) (2.0 * Math.PI * (timeOfDay - 0.25f));
		float sinA = (float) Math.sin(a), cosA = (float) Math.cos(a);
		float l = (float) Math.sqrt(cosA * cosA + sinA * sinA + 0.1225f);
		sunDir[0] = cosA / l;
		sunDir[1] = sinA / l;
		sunDir[2] = 0.35f / l;
		
		dayFactor = GameMath.smooth(-0.12f, 0.22f, sinA);
		nightFactor = 1f - GameMath.smooth(-0.25f, 0.08f, sinA);
		float tw = GameMath.clamp(1f - Math.abs(sinA) / 0.28f, 0f, 1f);
		
		GameMath.mix3(horizon, NIGHT_H, DAY_H, dayFactor);
		GameMath.mix3(horizon, horizon, TWI_H, tw * 0.65f);
		GameMath.mix3(zenith, NIGHT_Z, DAY_Z, dayFactor);
		fog[0] = horizon[0]; fog[1] = horizon[1]; fog[2] = horizon[2];
		
		GameMath.mix3(tmp, SUN_DAY, SUN_TWI, tw);
		skySunColor[0] = tmp[0] * dayFactor;
		skySunColor[1] = tmp[1] * dayFactor;
		skySunColor[2] = tmp[2] * dayFactor;
		
		GameMath.mix3(lightColor, MOON, tmp, dayFactor);
		float lx = GameMath.lerp(MOON_DIR[0], sunDir[0], dayFactor);
		float ly = GameMath.lerp(MOON_DIR[1], sunDir[1], dayFactor);
		float lz = GameMath.lerp(MOON_DIR[2], sunDir[2], dayFactor);
		float ll = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
		lightDir[0] = lx / ll;
		lightDir[1] = ly / ll;
		lightDir[2] = lz / ll;
		
		GameMath.mix3(ambient, AMB_NIGHT, AMB_DAY, dayFactor);
		ambient[0] += tw * 0.06f;
		ambient[1] += tw * 0.03f;
		GameMath.mix3(waterColor, WATER_NIGHT, WATER_DAY, dayFactor);
	}
}
