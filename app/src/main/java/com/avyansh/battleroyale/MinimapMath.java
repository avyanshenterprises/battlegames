package com.avyansh.battleroyale;

/** World <-> minimap transform (player-centred, optionally rotating with the camera) and edge clamping for off-map markers. */
public final class MinimapMath {
	public float centerX, centerZ;
	/** metres from the centre to the edge of the minimap at zoom 1 */
	public float viewRadius = 150f;
	public float zoom = 1f;
	public boolean rotate = true;
	public float yawDeg;
	/** minimap radius in pixels */
	public float sizePx = 100f;

	public void setZoom(float z) { zoom = Math.max(0.5f, Math.min(6f, z)); }
	public float metersPerPixel() { return viewRadius / zoom / sizePx; }

	/** out = pixel offset from the minimap centre (x right, y down). Returns true if inside the circle. */
	public boolean toMap(float wx, float wz, float[] out) {
		float dx = wx - centerX, dz = wz - centerZ;
		if (rotate) {
			float r = (float) Math.toRadians(yawDeg);
			float c = (float) Math.cos(r), s = (float) Math.sin(r);
			// forward (sin yaw, -cos yaw) maps to "up" on screen
			float right = dx * c + dz * s;
			float fwd = dx * s - dz * c;
			dx = right; dz = -fwd;
		}
		float k = sizePx / (viewRadius / zoom);
		out[0] = dx * k; out[1] = dz * k;
		return out[0] * out[0] + out[1] * out[1] <= sizePx * sizePx;
	}

	/** Clamp to the circle edge (for squad pings / far markers). */
	public void clampToEdge(float[] p, float inset) {
		float r = sizePx - inset, d = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1]);
		if (d > r && d > 0f) { p[0] *= r / d; p[1] *= r / d; }
	}

	/** Zone circle in minimap pixels: out = {cx, cy, radiusPx}. */
	public void zoneCircle(float zx, float zz, float zr, float[] out) {
		toMap(zx, zz, out);
		out[2] = zr * sizePx / (viewRadius / zoom);
	}

	/** inverse of {@link #toMap}: pixel offset -> world position (used by the full-screen map to place pings). */
	public void toWorld(float px, float py, float[] out) {
		float k = (viewRadius / zoom) / sizePx;
		float dx = px * k, dz = py * k;
		if (rotate) {
			float r = (float) Math.toRadians(yawDeg);
			float c = (float) Math.cos(r), s = (float) Math.sin(r);
			float right = dx, fwd = -dz;
			float wx = right * c + fwd * s, wz = right * s - fwd * c;
			dx = wx; dz = wz;
		}
		out[0] = centerX + dx; out[1] = centerZ + dz;
	}
}
