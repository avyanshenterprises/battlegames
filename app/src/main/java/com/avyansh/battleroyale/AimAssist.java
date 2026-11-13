package com.avyansh.battleroyale;

/**
 * Configurable aim assist for touch controls: <b>friction</b> (look sensitivity drops while the crosshair is near an enemy)
 * and a gentle <b>pull</b> (a capped angular speed towards the nearest enemy inside a cone).
 * It never produces an auto-aim lock: the pull is limited in speed, fades out as the crosshair approaches, stops at
 * {@link #residualDeg} from the target centre, and is mostly suppressed while the player drives the camera.
 * Pure Java, no allocation per frame.
 */
public final class AimAssist {
	public boolean enabled = true;
	/** 0..1 overall strength (0 = off). */
	public float strength = 0.5f;
	public float maxRange = 80f;
	/** acquisition cone half-angle in degrees (hip) */
	public float coneDeg = 7f;
	/** extra cone when aiming down sights */
	public float adsConeBonus = 3f;
	/** hard cap on the pull speed in degrees/second at strength 1 */
	public float maxPullDegPerSec = 45f;
	/** look-sensitivity multiplier at the target centre (friction); 1 = none */
	public float frictionMin = 0.55f;
	/** assist never closes the last N degrees: aim is still the player's job */
	public float residualDeg = 0.7f;
	/** pull is scaled by this while the player is actively moving the camera */
	public float userOverride = 0.25f;

	private int lockId = -1;

	/**
	 * @param pos    n * 3 floats: aim points of visible, hostile targets (x, y, z)
	 * @param ids    stable id per target (sticky target selection)
	 * @param userLook magnitude of this frame's player look input (degrees)
	 * @param out    out[0]=dYaw, out[1]=dPitch (degrees to add this frame), out[2]=look-sensitivity multiplier
	 * @return index of the assisted target or -1
	 */
	public int update(float dt, float cx, float cy, float cz, float yawDeg, float pitchDeg, int n, float[] pos, int[] ids,
	                  boolean aiming, float userLook, float[] out) {
		out[0] = 0f; out[1] = 0f; out[2] = 1f;
		if (!enabled || strength <= 0f || n <= 0) { lockId = -1; return -1; }
		float cone = coneDeg + (aiming ? adsConeBonus : 0f);
		int best = -1; float bestErr = 1e9f, bestDy = 0, bestDp = 0;
		for (int i = 0; i < n; i++) {
			float dx = pos[i * 3] - cx, dy = pos[i * 3 + 1] - cy, dz = pos[i * 3 + 2] - cz;
			float h = (float) Math.sqrt(dx * dx + dz * dz);
			float dist = (float) Math.sqrt(h * h + dy * dy);
			if (dist > maxRange || dist < 0.5f) continue;
			float ty = (float) Math.toDegrees(Math.atan2(dx, -dz));
			float tp = (float) Math.toDegrees(Math.atan2(dy, h));
			float ey = wrap(ty - yawDeg), ep = tp - pitchDeg;
			float err = (float) Math.sqrt(ey * ey + ep * ep);
			// the cone is angular, but far targets are small: shrink it with distance so we do not drag aim to specks
			float c = cone * (dist < 15f ? 1f : Math.max(0.5f, 15f / dist + 0.35f));
			if (err > c) continue;
			float score = err - (ids != null && ids[i] == lockId ? 1.5f : 0f);   // sticky
			if (score < bestErr) { bestErr = score; best = i; bestDy = ey; bestDp = ep; }
		}
		if (best < 0) { lockId = -1; return -1; }
		lockId = ids != null ? ids[best] : best;
		float err = (float) Math.sqrt(bestDy * bestDy + bestDp * bestDp);
		float c = cone;
		float closeness = 1f - Math.min(1f, err / c);                 // 0 at cone edge, 1 at the centre
		out[2] = 1f - (1f - frictionMin) * strength * closeness;      // friction
		if (err <= residualDeg) return best;
		float fade = Math.min(1f, (err - residualDeg) / (c * 0.5f));  // fades to 0 as we approach the residual
		float user = userLook > 0.05f ? userOverride : 1f;
		float pull = maxPullDegPerSec * strength * fade * user * dt;
		pull = Math.min(pull, err - residualDeg);                     // never overshoot the residual ring
		out[0] = bestDy / err * pull;
		out[1] = bestDp / err * pull;
		return best;
	}

	public static float wrap(float a) { a = ((a + 180f) % 360f + 360f) % 360f - 180f; return a; }
}
