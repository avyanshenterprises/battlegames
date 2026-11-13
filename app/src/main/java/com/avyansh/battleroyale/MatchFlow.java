package com.avyansh.battleroyale;

public class MatchFlow {
	public MatchPhase phase = MatchPhase.AIRPLANE;
	public final Airplane plane = new Airplane();

	public float phaseTime = 0f;

	public float chuteRotation = 0f;
	public boolean chuteOpen = false;

	public boolean jumpRequested = false;

	private static final float FREEFALL_GRAVITY   = 26f;
	private static final float FREEFALL_MAX_SPEED = 55f;
	private static final float FREEFALL_STEER     = 22f;
	private static final float PARACHUTE_GRAVITY  = 6.5f;
	private static final float PARACHUTE_MAXSPEED = 8f;
	private static final float PARACHUTE_STEER    = 9f;
	private static final float CHUTE_OPEN_HEIGHT  = 180f;

	private static final float AUTO_JUMP_AFTER    = 45f;
	private static final float AUTO_JUMP_PROGRESS = 0.92f;

	public void reset() {
		phase = MatchPhase.AIRPLANE;
		phaseTime = 0f;
		plane.respawn();
		chuteRotation = 0f;
		chuteOpen = false;
		jumpRequested = false;
	}

	public void requestJump() {
		if (phase == MatchPhase.AIRPLANE) jumpRequested = true;
	}

	private void doJump(Player p, GameEngine eng) {
		phase = MatchPhase.FREEFALL;
		phaseTime = 0f;
		chuteOpen = false;

		float[] origin = plane.jumpOrigin();
		float[] vel    = plane.jumpVelocity();

		p.x = origin[0];
		p.y = origin[1];
		p.z = origin[2];
		p.vy = -10f;
		p.horizontalVX = vel[0];
		p.horizontalVZ = vel[2];
		p.onGround = false;
		p.yaw = (float) Math.atan2(plane.dirX, -plane.dirZ);
		p.alive = true;
		p.crouching = false;
		p.swimming = false;
		p.inAir = true;
		p.airTime = 0f;
		if (eng.sound != null) eng.sound.play("jump");
		ServerNet.sendJump();   // online: the server places us at its plane position (no-op offline)
	}

	public void update(float dt, GameEngine eng) {
		phaseTime += dt;

		switch (phase) {
            case LOBBY:
            break;
			case AIRPLANE:
			updateAirplane(dt, eng);
			break;
			case FREEFALL:
			updateFreefall(dt, eng);
			break;
			case PARACHUTE:
			updateParachute(dt, eng);
			break;
			case PLAYING:
			if (!eng.player.alive) phase = MatchPhase.DEAD;
			else if (ServerNet.inServerMatch()) {
				// online: the server decides the winner (match_end); nothing to compute locally
			} else if (Net.active && Net.inMatch) {
				// Team match: won when every enemy-team player is gone (and every local filler bot, if any).
				boolean hasFoes = Net.expectedEnemies() > 0 || eng.bots.botCount > 0;
				if (hasFoes && Net.aliveEnemies() == 0 && eng.bots.aliveCount() == 0) phase = MatchPhase.WIN;
			} else if (eng.bots.aliveCount() == 0) phase = MatchPhase.WIN;
			break;
			case DEAD:
			case WIN:
			break;
		}
	}

	private void updateAirplane(float dt, GameEngine eng) {
		plane.update(dt);

		if (jumpRequested) {
			jumpRequested = false;
			doJump(eng.player, eng);
			return;
		}

		if (phaseTime > AUTO_JUMP_AFTER || plane.progress() > AUTO_JUMP_PROGRESS || plane.finished) {
			jumpRequested = true;
		}
	}

	private void updateFreefall(float dt, GameEngine eng) {
		Player p = eng.player;
		GameCamera cam = eng.camera;

		float yawRad = (float) Math.toRadians(cam.yaw);
		float fx = (float) Math.sin(yawRad);
		float fz = -(float) Math.cos(yawRad);
		float rx = (float) Math.cos(yawRad);
		float rz =  (float) Math.sin(yawRad);

		float mf = eng.inputMoveF();
		float ms = eng.inputMoveS();

		float steerX = (fx * mf + rx * ms) * FREEFALL_STEER;
		float steerZ = (fz * mf + rz * ms) * FREEFALL_STEER;

		p.horizontalVX += (steerX - p.horizontalVX) * Math.min(1f, dt * 4f);
		p.horizontalVZ += (steerZ - p.horizontalVZ) * Math.min(1f, dt * 4f);

		p.vy -= FREEFALL_GRAVITY * dt;
		if (p.vy < -FREEFALL_MAX_SPEED) p.vy = -FREEFALL_MAX_SPEED;

		p.x += p.horizontalVX * dt;
		p.z += p.horizontalVZ * dt;
		if (p.horizontalVX * p.horizontalVX + p.horizontalVZ * p.horizontalVZ > 0.25f) {
			float face = (float) Math.atan2(p.horizontalVX, -p.horizontalVZ);
			p.yaw = Player.turnToward(p.yaw, face, 3.5f * dt);
		}
		p.y += p.vy * dt;

		float ground = eng.world.ground(p.x, p.z);
		if (p.y <= ground) {
			landPlayer(p, ground, eng);
			return;
		}

		float groundAbove = p.y - ground;
		if (groundAbove < CHUTE_OPEN_HEIGHT) {
			phase = MatchPhase.PARACHUTE;
			chuteOpen = true;
			phaseTime = 0f;
			p.vy = Math.max(p.vy, -PARACHUTE_MAXSPEED);
			if (eng.sound != null) eng.sound.play("chute");
		}
	}

	private void updateParachute(float dt, GameEngine eng) {
		Player p = eng.player;
		GameCamera cam = eng.camera;

		chuteRotation += dt * 0.6f;

		float yawRad = (float) Math.toRadians(cam.yaw);
		float fx = (float) Math.sin(yawRad);
		float fz = -(float) Math.cos(yawRad);
		float rx = (float) Math.cos(yawRad);
		float rz =  (float) Math.sin(yawRad);

		float mf = eng.inputMoveF();
		float ms = eng.inputMoveS();

		float steerX = (fx * mf + rx * ms) * PARACHUTE_STEER;
		float steerZ = (fz * mf + rz * ms) * PARACHUTE_STEER;

		p.horizontalVX += (steerX - p.horizontalVX) * Math.min(1f, dt * 6f);
		p.horizontalVZ += (steerZ - p.horizontalVZ) * Math.min(1f, dt * 6f);

		p.vy -= PARACHUTE_GRAVITY * dt;
		if (p.vy < -PARACHUTE_MAXSPEED) p.vy = -PARACHUTE_MAXSPEED;

		p.x += p.horizontalVX * dt;
		p.z += p.horizontalVZ * dt;
		if (p.horizontalVX * p.horizontalVX + p.horizontalVZ * p.horizontalVZ > 0.25f) {
			float face = (float) Math.atan2(p.horizontalVX, -p.horizontalVZ);
			p.yaw = Player.turnToward(p.yaw, face, 3.5f * dt);
		}
		p.y += p.vy * dt;

		float ground = eng.world.ground(p.x, p.z);
		if (p.y <= ground + 0.05f) {
			landPlayer(p, ground, eng);
		}
	}

	private void landPlayer(Player p, float ground, GameEngine eng) {
		float impactSpeed = -p.vy;
		p.y = ground;
		p.vy = 0f;
		p.horizontalVX = 0f;
		p.horizontalVZ = 0f;
		p.onGround = true;
		p.inAir = false;
		p.airTime = 0f;
		p.swimming = false;
		phase = MatchPhase.PLAYING;
		chuteOpen = false;
		phaseTime = 0f;
		if (eng.sound != null) eng.sound.play("land");
		if (eng.cameraShake != null) eng.cameraShake.land(Math.min(1f, impactSpeed / 20f));
	}
}
