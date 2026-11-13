package com.avyansh.battleroyale;

/**
 * Per-entity snapshot buffer for smooth remote movement (entity interpolation).
 * Rendering happens ~100 ms in the past so there are normally two snapshots to blend; when the buffer runs dry the
 * entity is extrapolated for at most 150 ms and then held. Fixed arrays: no allocation after construction.
 */
public final class SnapshotInterpolator {
	private static final int N = 24;
	private final double[] t = new double[N];
	private final float[] x = new float[N], y = new float[N], z = new float[N], yaw = new float[N];
	private int head, count;
	public static final float MAX_EXTRAPOLATE_MS = 150f;
	public static final float TELEPORT_DIST = 40f;

	public void clear() { head = 0; count = 0; }
	public int size() { return count; }

	/** @param ts server timestamp (ms) of the snapshot */
	public void push(double ts, float px, float py, float pz, float pyaw) {
		if (count > 0 && ts <= t[(head + count - 1) % N]) return;   // out-of-order / duplicate
		if (count == N) { head = (head + 1) % N; count--; }
		int i = (head + count) % N;
		t[i] = ts; x[i] = px; y[i] = py; z[i] = pz; yaw[i] = pyaw;
		count++;
	}

	/** out = {x,y,z,yaw}. Returns false when nothing is known yet. */
	public boolean sample(double renderTs, float[] out) {
		if (count == 0) return false;
		int last = (head + count - 1) % N;
		if (renderTs >= t[last]) {
			float over = (float) Math.min(MAX_EXTRAPOLATE_MS, renderTs - t[last]);
			out[0] = x[last]; out[1] = y[last]; out[2] = z[last]; out[3] = yaw[last];
			if (count >= 2 && over > 0f) {
				int p = (head + count - 2) % N;
				float dt = (float) (t[last] - t[p]);
				if (dt > 1f) {
					float vx = (x[last] - x[p]) / dt, vz = (z[last] - z[p]) / dt;
					if (Math.abs(x[last] - x[p]) < TELEPORT_DIST && Math.abs(z[last] - z[p]) < TELEPORT_DIST) { out[0] += vx * over; out[2] += vz * over; }
				}
			}
			return true;
		}
		int first = head;
		if (renderTs <= t[first]) { out[0] = x[first]; out[1] = y[first]; out[2] = z[first]; out[3] = yaw[first]; return true; }
		for (int k = count - 1; k > 0; k--) {
			int b = (head + k) % N, a = (head + k - 1) % N;
			if (t[a] <= renderTs) {
				float f = (float) ((renderTs - t[a]) / Math.max(1.0, t[b] - t[a]));
				if (Math.abs(x[b] - x[a]) > TELEPORT_DIST || Math.abs(z[b] - z[a]) > TELEPORT_DIST) f = f < 0.5f ? 0f : 1f;   // respawn/teleport: no sliding across the map
				out[0] = x[a] + (x[b] - x[a]) * f; out[1] = y[a] + (y[b] - y[a]) * f; out[2] = z[a] + (z[b] - z[a]) * f;
				out[3] = lerpAngle(yaw[a], yaw[b], f);
				return true;
			}
		}
		return false;
	}

	public static float lerpAngle(float a, float b, float f) {
		float d = ((b - a) % 360f + 540f) % 360f - 180f;
		return a + d * f;
	}
}
