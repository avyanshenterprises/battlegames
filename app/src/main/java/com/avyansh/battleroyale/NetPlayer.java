package com.avyansh.battleroyale;

/** Another real player in the room (replaces a bot in online matches). */
public final class NetPlayer {
	public String id = "", name = "Player";
	// last received state
	public float tx, ty, tz, tyaw, speed, pitch;
	public boolean alive = true, onGround = true, crouch, swim, aiming, firing;
	public int weapon = Weapon.AR, hp = 100;
	public long lastSeen;
	/** Team number assigned by the host when the match started (-1 = unknown). */
	public int team = -1;
	// smoothed (drawn) state, same field names as Bot so the renderer can treat it alike
	public float x, y, z, yaw, speedNow, moveRel;
	public boolean inited;
	public float deathT;
	/** Online (dedicated server) matches: true for AI bots. Always honest, never guessed on the client. */
	public boolean isBot;
	public boolean knocked;
	/** Server snapshot interpolation (null for legacy Firebase players). */
	public SnapshotInterpolator interp;
	private final float[] samp = new float[4];

	public void step(float dt) {
		if (interp != null) {
			// render ~100 ms in the past so there are always two snapshots to interpolate between
			if (interp.sample(ServerNet.renderTs(), samp)) {
				float ox = x, oz = z;
				x = samp[0]; y = samp[1]; z = samp[2]; yaw = (float) Math.toRadians(samp[3]); inited = true;
				float d = (float) Math.sqrt((x - ox) * (x - ox) + (z - oz) * (z - oz));
				speedNow += ((dt > 0f ? d / dt : 0f) - speedNow) * Math.min(1f, dt * 10f);
			}
			if (!alive) deathT += dt; else deathT = 0f;
			return;
		}
		if (!inited) { x = tx; y = ty; z = tz; yaw = tyaw; inited = true; }
		float ox0 = x, oz0 = z;
		float k = 1f - (float) Math.exp(-12f * dt);
		float dx = tx - x, dz = tz - z;
		if (dx * dx + dz * dz > 30f * 30f) { x = tx; z = tz; y = ty; }
		else { x += dx * k; z += dz * k; y += (ty - y) * k; }
		yaw = Player.turnToward(yaw, tyaw, 14f * dt);
		float mvx = x - ox0, mvz = z - oz0;
		if (mvx * mvx + mvz * mvz > 1e-6f) {
			float rel = (float) Math.atan2(mvx, -mvz) - yaw;
			while (rel > Math.PI) rel -= 2f * (float) Math.PI;
			while (rel < -Math.PI) rel += 2f * (float) Math.PI;
			moveRel = rel;
		} else moveRel = 0f;
		speedNow += (speed - speedNow) * Math.min(1f, dt * 10f);
		if (!alive) deathT += dt; else deathT = 0f;
	}
}
