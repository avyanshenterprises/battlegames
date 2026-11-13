package com.avyansh.battleroyale;

/** Directional gunfire indicators (HUD arcs). Fixed pool, no allocation. Only shots within 200 m create one. */
public final class GunfireIndicators {
	public static final int MAX = 8;
	public static final float LIFE = 2.6f;
	public final float[] worldBearing = new float[MAX];   // degrees, world space (so the arc follows the camera when you turn)
	public final float[] age = new float[MAX];
	public final float[] distance = new float[MAX];
	public final String[] source = new String[MAX];
	private final boolean[] used = new boolean[MAX];
	private final PositionalAudio.Result tmp = new PositionalAudio.Result();

	/** @return true if an indicator was created or refreshed */
	public boolean onShot(String shooterId, float lx, float ly, float lz, float sx, float sy, float sz) {
		float dx = sx - lx, dz = sz - lz, dy = sy - ly;
		float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (d > PositionalAudio.INDICATOR_RANGE) return false;
		float b = (float) Math.toDegrees(Math.atan2(dx, -dz));
		int slot = -1, oldest = 0;
		for (int i = 0; i < MAX; i++) {
			if (used[i] && shooterId != null && shooterId.equals(source[i])) { slot = i; break; }   // same shooter: refresh
			if (!used[i]) { if (slot < 0) slot = i; }
			else if (age[i] > age[oldest]) oldest = i;
		}
		if (slot < 0) slot = oldest;
		used[slot] = true; source[slot] = shooterId; worldBearing[slot] = b; age[slot] = 0f; distance[slot] = d;
		return true;
	}

	public void update(float dt) { for (int i = 0; i < MAX; i++) if (used[i]) { age[i] += dt; if (age[i] > LIFE) { used[i] = false; source[i] = null; } } }
	public boolean active(int i) { return used[i]; }
	public float alpha(int i) { float a = 1f - age[i] / LIFE; return a < 0f ? 0f : a; }
	/** bearing relative to where the camera is looking: 0 = ahead, +90 = right */
	public float relative(int i, float camYawDeg) { return AimAssist.wrap(worldBearing[i] - camYawDeg); }
}
