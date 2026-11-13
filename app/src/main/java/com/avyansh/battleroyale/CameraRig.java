package com.avyansh.battleroyale;

/**
 * Third-person camera helper: sensitivity (separate for hip / ADS / each scope magnification) and obstacle-aware
 * orbit placement (camera never ends up inside terrain or walls). Pure Java; the world is reached through {@link World}.
 */
public final class CameraRig {
	public interface World {
		float groundAt(float x, float z);
		/** true when the segment crosses solid geometry (buildings, props). May be a cheap approximation. */
		boolean segmentBlocked(float x0, float y0, float z0, float x1, float y1, float z1);
	}

	// ---- sensitivity (degrees per touch unit) ----
	public float baseSensitivity = 0.14f;      // matches GameSettings.LOOK_SENSITIVITY
	public float hipMul = 1.0f;
	public float adsMul = 0.75f;               // aiming without a magnifying scope
	public float scope2xMul = 0.55f;
	public float scope4xMul = 0.38f;
	public float scope6xMul = 0.26f;

	/** @param zoom current scope magnification (1 = none) */
	public float sensitivity(float zoom, boolean aiming) {
		float m;
		if (zoom <= 1.05f) m = aiming ? adsMul : hipMul;
		else if (zoom <= 2f) m = lerp(adsMul, scope2xMul, (zoom - 1f));
		else if (zoom <= 4f) m = lerp(scope2xMul, scope4xMul, (zoom - 2f) / 2f);
		else m = lerp(scope4xMul, scope6xMul, Math.min(1f, (zoom - 4f) / 2f));
		return baseSensitivity * m;
	}

	// ---- third-person orbit ----
	public float margin = 0.28f;
	public float minDistance = 0.9f;
	public float shrinkSpeed = 40f;            // metres/s the camera is pushed in
	public float growSpeed = 6f;               // metres/s the camera eases back out
	public float groundClearance = 0.35f;
	private float curDist = -1f;

	public void reset() { curDist = -1f; }
	public float currentDistance() { return curDist; }

	/**
	 * @param pivot  point the camera orbits (head / shoulder), in world space
	 * @param wanted desired distance behind the pivot
	 * @param shoulder lateral offset (right positive) applied to the camera
	 * @param out    out[0..2] = camera position
	 */
	public void orbit(World w, float dt, float px, float py, float pz, float yawDeg, float pitchDeg, float wanted, float shoulder, float[] out) {
		float yr = (float) Math.toRadians(yawDeg), pr = (float) Math.toRadians(pitchDeg);
		float cp = (float) Math.cos(pr), sp = (float) Math.sin(pr);
		float fx = (float) Math.sin(yr) * cp, fy = sp, fz = -(float) Math.cos(yr) * cp;
		float rx = (float) Math.cos(yr), rz = (float) Math.sin(yr);
		float safe = clearDistance(w, px, py, pz, -fx, -fy, -fz, rx * shoulder, rz * shoulder, wanted);
		if (curDist < 0f) curDist = safe;
		if (safe < curDist) curDist = Math.max(safe, curDist - shrinkSpeed * dt);   // fast in ...
		else curDist = Math.min(safe, curDist + growSpeed * dt);                    // ... slow out
		if (safe < curDist) curDist = safe;                                         // but never inside a wall
		float d = Math.max(Math.min(minDistance, wanted), curDist);
		float cx = px + (-fx) * d + rx * shoulder, cy = py + (-fy) * d, cz = pz + (-fz) * d + rz * shoulder;
		float g = w.groundAt(cx, cz) + groundClearance;
		if (cy < g) cy = g;
		out[0] = cx; out[1] = cy; out[2] = cz;
	}

	/** largest distance <= wanted along (-forward) such that the camera stays out of ground and solids. */
	float clearDistance(World w, float px, float py, float pz, float bx, float by, float bz, float lx, float lz, float wanted) {
		final int steps = 12;
		float last = 0f;
		for (int i = 1; i <= steps; i++) {
			float d = wanted * i / steps;
			float x = px + bx * d + lx, y = py + by * d, z = pz + bz * d + lz;
			if (y < w.groundAt(x, z) + groundClearance * 0.6f) return Math.max(0f, last - margin);
			if (w.segmentBlocked(px, py, pz, x, y, z)) return Math.max(0f, last - margin);
			last = d;
		}
		return wanted;
	}

	private static float lerp(float a, float b, float t) { return a + (b - a) * Math.max(0f, Math.min(1f, t)); }
}
