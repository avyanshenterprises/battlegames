package com.avyansh.battleroyale;

/**
 * Client-side prediction bookkeeping. The client moves locally at once (prediction), sends numbered inputs and
 * remembers the predicted position of each. When the server reports which input it last accepted (snapshot `ack`) together
 * with its own position, we compare with what we predicted for that input: a small error is ignored (and smoothed),
 * a large error (or an explicit `correct` message) means the server rejected movement and we snap + replay later inputs.
 */
public final class InputReconciler {
	private static final int N = 128;
	private final int[] seqs = new int[N];
	private final float[] px = new float[N], py = new float[N], pz = new float[N];
	private int next = 1, count;
	public float snapThreshold = 1.5f;
	/** residual error (client - server) being smoothed away, world units. Read by the camera for a soft correction. */
	public float errX, errY, errZ;

	public int nextSeq() { return next++; }

	/** record predicted position for an input that was just sent */
	public void record(int seq, float x, float y, float z) {
		int i = seq % N;
		seqs[i] = seq; px[i] = x; py[i] = y; pz[i] = z;
		if (count < N) count++;
	}

	/** @return true when the local player should be moved to (sx,sy,sz); out receives the position to use */
	public boolean onServerState(int ack, float sx, float sy, float sz, float[] out) {
		if (ack <= 0) return false;
		int i = ack % N;
		if (seqs[i] != ack) return false;
		float dx = px[i] - sx, dy = py[i] - sy, dz = pz[i] - sz;
		float d = (float) Math.sqrt(dx * dx + dz * dz);
		if (d > snapThreshold || Math.abs(dy) > 3f) {
			// replay: the newest predicted position, shifted by the same error
			int newest = next - 1;
			int j = newest % N;
			if (seqs[j] == newest) { out[0] = px[j] - dx; out[1] = py[j] - dy; out[2] = pz[j] - dz; }
			else { out[0] = sx; out[1] = sy; out[2] = sz; }
			errX = errY = errZ = 0f;
			return true;
		}
		errX = dx; errY = dy; errZ = dz;
		return false;
	}

	/** server `correct`: hard reset to the authoritative position, forget prediction history. */
	public void onCorrect(float sx, float sy, float sz, float[] out) {
		out[0] = sx; out[1] = sy; out[2] = sz;
		for (int i = 0; i < N; i++) seqs[i] = 0;
		count = 0; errX = errY = errZ = 0f;
	}

	/** call every frame to bleed off the small residual error */
	public void smooth(float dt) { float k = Math.min(1f, dt * 10f); errX -= errX * k; errY -= errY * k; errZ -= errZ * k; }
}
