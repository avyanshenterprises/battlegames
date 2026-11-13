package com.avyansh.battleroyale;

/**
* Evaluates glTF animation channels on top of the authored bind pose, then builds the node
* hierarchy and the skin (joint) matrices.
*
* PERFORMANCE: this runs for the player and every visible bot each frame, so it allocates
* NOTHING per frame (no new arrays, no android.opengl.Matrix helper temporaries) - the old
* version created thousands of small arrays per frame which caused GC stutter.
*
* Also contains a small two-bone IK solver used to put a character's hands on its rifle.
*/
public final class AnimationMixer {
	private final GltfModel model;
	private final int n;
	
	private final float[] bindT, bindR, bindS;
	private final float[] localT, localR, localS;
	private final float[] aT, aR, aS;               // blend scratch
	
	private final int[] order;                      // parents always before children
	private final int[] parent;
	private final float[] nodeWorld;                // n * 16
	private final float[] local = new float[16];
	public float[] jointMatrices;
	
	// IK scratch
	private final float[] q1 = new float[4], q2 = new float[4], q3 = new float[4];
	
	public AnimationMixer(GltfModel model) {
		if (model == null || model.nodes == null) throw new IllegalArgumentException("GLTF model has no nodes");
		this.model = model;
		n = model.nodes.length;
		bindT = new float[n * 3]; bindR = new float[n * 4]; bindS = new float[n * 3];
		localT = new float[n * 3]; localR = new float[n * 4]; localS = new float[n * 3];
		aT = new float[n * 3]; aR = new float[n * 4]; aS = new float[n * 3];
		nodeWorld = new float[n * 16];
		order = new int[n];
		parent = new int[n];
		jointMatrices = new float[Math.max(0, model.jointCount()) * 16];
		decomposeBindPose();
		buildOrder();
		resetToBindPose();
		for (int i = 0; i < model.jointCount(); i++) identity(jointMatrices, i * 16);
	}
	
	public float[] jointMatrices() { return jointMatrices; }
	public float[] getJointMatrices() { return jointMatrices; }
	public float[] nodeWorldMatrices() { return nodeWorld; }
	
	// ------------------------------------------------------------------
	// setup (once)
	// ------------------------------------------------------------------
	private void decomposeBindPose() {
		float[] rot = new float[16];
		for (int i = 0; i < n; i++) {
			float[] m = model.nodes[i].localMatrix;
			int t = i * 3, r = i * 4;
			bindT[t] = m[12]; bindT[t + 1] = m[13]; bindT[t + 2] = m[14];
			float sx = len3(m[0], m[1], m[2]), sy = len3(m[4], m[5], m[6]), sz = len3(m[8], m[9], m[10]);
			if (sx < 1e-7f) sx = 1f;
			if (sy < 1e-7f) sy = 1f;
			if (sz < 1e-7f) sz = 1f;
			bindS[t] = sx; bindS[t + 1] = sy; bindS[t + 2] = sz;
			identity(rot, 0);
			rot[0] = m[0] / sx; rot[1] = m[1] / sx; rot[2] = m[2] / sx;
			rot[4] = m[4] / sy; rot[5] = m[5] / sy; rot[6] = m[6] / sy;
			rot[8] = m[8] / sz; rot[9] = m[9] / sz; rot[10] = m[10] / sz;
			matrixToQuat(rot, bindR, r);
		}
	}
	
	private void buildOrder() {
		boolean[] isChild = new boolean[n];
		boolean[] done = new boolean[n];
		java.util.Arrays.fill(parent, -1);
		for (int i = 0; i < n; i++) {
			int[] ch = model.nodes[i].children;
			if (ch != null) for (int c : ch) if (c >= 0 && c < n) { isChild[c] = true; parent[c] = i; }
		}
		int k = 0;
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < n; i++) {
				if (done[i] || (pass == 0 && isChild[i])) continue;
				// iterative DFS
				int[] stack = new int[n + 1];
				int sp = 0;
				stack[sp++] = i;
				done[i] = true;
				if (pass == 1) parent[i] = -1;
				while (sp > 0) {
					int cur = stack[--sp];
					order[k++] = cur;
					int[] ch = model.nodes[cur].children;
					if (ch != null) for (int c : ch) {
						if (c >= 0 && c < n && !done[c]) { done[c] = true; stack[sp++] = c; }
					}
				}
			}
		}
		// anything missed (should not happen)
		for (int i = 0; i < n; i++) if (!done[i]) { order[k++] = i; parent[i] = -1; }
	}
	
	private void resetToBindPose() {
		System.arraycopy(bindT, 0, localT, 0, bindT.length);
		System.arraycopy(bindR, 0, localR, 0, bindR.length);
		System.arraycopy(bindS, 0, localS, 0, bindS.length);
	}
	
	// ------------------------------------------------------------------
	// evaluation (per frame, allocation free)
	// ------------------------------------------------------------------
	public void evaluate(AnimationClip clip, float time) {
		resetToBindPose();
		applyClip(clip, time);
	}
	
	private void applyClip(AnimationClip clip, float time) {
		if (clip == null || clip.tracks == null) return;
		for (int i = 0, c = clip.tracks.size(); i < c; i++) {
			AnimationClip.Track tr = clip.tracks.get(i);
			if (tr == null || tr.times == null || tr.values == null) continue;
			if (tr.kind == 0) {
				if ("translation".equals(tr.path)) tr.kind = 1;
				else if ("rotation".equals(tr.path)) tr.kind = 2;
				else if ("scale".equals(tr.path)) tr.kind = 3;
				else tr.kind = -1;
				tr.step = "STEP".equals(tr.interpolation);
			}
			if (tr.kind < 0 || tr.nodeIndex < 0 || tr.nodeIndex >= n) continue;
			if (tr.kind == 2) sampleRot(tr, time, localR, tr.nodeIndex * 4);
			else sampleVec(tr, time, tr.kind == 1 ? localT : localS, tr.nodeIndex * 3);
		}
	}
	
	public void evaluateBlend(AnimationClip a, float ta, AnimationClip b, float tb, float weight) {
		float w = weight < 0f ? 0f : (weight > 1f ? 1f : weight);
		evaluate(a, ta);
		System.arraycopy(localT, 0, aT, 0, localT.length);
		System.arraycopy(localR, 0, aR, 0, localR.length);
		System.arraycopy(localS, 0, aS, 0, localS.length);
		evaluate(b, tb);
		float iw = 1f - w;
		for (int i = 0; i < n * 3; i++) {
			localT[i] = aT[i] * iw + localT[i] * w;
			localS[i] = aS[i] * iw + localS[i] * w;
		}
		for (int i = 0; i < n; i++) slerp(aR, i * 4, localR, i * 4, w, localR, i * 4);
	}
	

	// ------------------------------------------------------------------
	// layering / procedural twist (strafing, reload while running)
	// ------------------------------------------------------------------
	/** Marks 'root' and every node below it in 'out' (length >= node count). */
	public void maskUnder(int root, boolean[] out) {
		for (int i = 0; i < n && i < out.length; i++) out[i] = root >= 0 && isUnder(i, root);
	}

	public int nodeCount() { return n; }

	/**
	 * Call right after evaluate/evaluateBlend: replaces the masked nodes (upper body) with the pose of 'over'
	 * at time 'to', by 'weight'. The legs keep the base animation (e.g. running) while the arms play e.g. a reload.
	 */
	public void overlay(AnimationClip over, float to, boolean[] mask, float weight) {
		if (over == null || mask == null || weight <= 0.001f) return;
		float w = weight > 1f ? 1f : weight;
		System.arraycopy(localT, 0, aT, 0, localT.length);
		System.arraycopy(localR, 0, aR, 0, localR.length);
		System.arraycopy(localS, 0, aS, 0, localS.length);
		evaluate(over, to);                      // localX now holds the overlay pose
		for (int i = 0; i < n; i++) {
			boolean m = i < mask.length && mask[i];
			if (!m) {                            // not in the mask: restore the base pose
				for (int k = 0; k < 3; k++) { localT[i * 3 + k] = aT[i * 3 + k]; localS[i * 3 + k] = aS[i * 3 + k]; }
				for (int k = 0; k < 4; k++) localR[i * 4 + k] = aR[i * 4 + k];
			} else {
				for (int k = 0; k < 3; k++) {
					localT[i * 3 + k] = aT[i * 3 + k] * (1f - w) + localT[i * 3 + k] * w;
					localS[i * 3 + k] = aS[i * 3 + k] * (1f - w) + localS[i * 3 + k] * w;
				}
				slerp(aR, i * 4, localR, i * 4, w, localR, i * 4);
			}
		}
	}

	/**
	 * Rotates 'node' (and so everything below it) about the model's vertical axis by angleRad, in model space.
	 * World matrices must be current (call buildWorlds() first); they are rebuilt below the node.
	 */
	public void twistAboutY(int node, float angleRad, float weight) {
		if (node < 0 || node >= n || Math.abs(angleRad * weight) < 1e-4f) return;
		float half = angleRad * weight * 0.5f;
		float[] D = q2; D[0] = 0f; D[1] = (float) Math.sin(half); D[2] = 0f; D[3] = (float) Math.cos(half);
		int p = parent[node];
		float[] pr = q1;
		if (p < 0) { pr[0] = 0f; pr[1] = 0f; pr[2] = 0f; pr[3] = 1f; }
		else worldRot(p, pr);
		float[] tmp = q3;
		qmul(D, pr, tmp);                        // D * P
		float[] pinv = D;
		pinv[0] = -pr[0]; pinv[1] = -pr[1]; pinv[2] = -pr[2]; pinv[3] = pr[3];
		float[] res = pr;
		qmul(pinv, tmp, res);                    // inv(P) * D * P
		int r = node * 4;
		tmp[0] = localR[r]; tmp[1] = localR[r + 1]; tmp[2] = localR[r + 2]; tmp[3] = localR[r + 3];
		qmul(res, tmp, pinv);
		float l = (float) Math.sqrt(pinv[0] * pinv[0] + pinv[1] * pinv[1] + pinv[2] * pinv[2] + pinv[3] * pinv[3]);
		if (l < 1e-8f) return;
		localR[r] = pinv[0] / l; localR[r + 1] = pinv[1] / l; localR[r + 2] = pinv[2] / l; localR[r + 3] = pinv[3] / l;
		buildSubtree(node);
	}

	// ------------------------------------------------------------------
	// hierarchy
	// ------------------------------------------------------------------
	/** Recomputes every node's world matrix from the current local TRS. */
	public void buildWorlds() {
		for (int k = 0; k < n; k++) {
			int i = order[k];
			int t = i * 3, r = i * 4;
			float x = localR[r], y = localR[r + 1], z = localR[r + 2], w = localR[r + 3];
			float xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
			float sx = localS[t], sy = localS[t + 1], sz = localS[t + 2];
			local[0] = (1f - 2f * (yy + zz)) * sx; local[1] = 2f * (xy + wz) * sx; local[2] = 2f * (xz - wy) * sx; local[3] = 0f;
			local[4] = 2f * (xy - wz) * sy; local[5] = (1f - 2f * (xx + zz)) * sy; local[6] = 2f * (yz + wx) * sy; local[7] = 0f;
			local[8] = 2f * (xz + wy) * sz; local[9] = 2f * (yz - wx) * sz; local[10] = (1f - 2f * (xx + yy)) * sz; local[11] = 0f;
			local[12] = localT[t]; local[13] = localT[t + 1]; local[14] = localT[t + 2]; local[15] = 1f;
			int p = parent[i];
			if (p < 0) System.arraycopy(local, 0, nodeWorld, i * 16, 16);
			else mul(nodeWorld, p * 16, local, 0, nodeWorld, i * 16);
		}
	}
	
	public void buildJointMatrices() {
		buildWorlds();
		if (model.skins == null || model.skins.length == 0) return;
		GltfModel.Skin skin = model.skins[0];
		if (skin.joints == null || skin.inverseBind == null) return;
		int count = Math.min(skin.joints.length, jointMatrices.length / 16);
		for (int j = 0; j < count; j++) {
			int node = skin.joints[j];
			if (node < 0 || node >= n) { identity(jointMatrices, j * 16); continue; }
			mul(nodeWorld, node * 16, skin.inverseBind, j * 16, jointMatrices, j * 16);
		}
	}
	
	// ------------------------------------------------------------------
	// two-bone IK (analytic): moves the 'hand' node onto (tx,ty,tz) in MODEL space by rotating the
	// upper and lower arm. Call buildWorlds() first and buildJointMatrices() afterwards.
	// (hx,hy,hz) is the pole vector: the direction the elbow should point towards.
	// ------------------------------------------------------------------
	// AnimationMixer.java - Replace solveTwoBone with better pole handling
	public void solveTwoBone(int upper, int lower, int hand,
	float tx, float ty, float tz, float hx, float hy, float hz, float weight) {
		if (upper < 0 || lower < 0 || hand < 0 || upper >= n || lower >= n || hand >= n) return;
		float[] W = nodeWorld;
		float sx = W[upper * 16 + 12], sy = W[upper * 16 + 13], sz = W[upper * 16 + 14];
		float ex = W[lower * 16 + 12], ey = W[lower * 16 + 13], ez = W[lower * 16 + 14];
		float wx = W[hand * 16 + 12],  wy = W[hand * 16 + 13],  wz = W[hand * 16 + 14];
		float l1 = dist(sx, sy, sz, ex, ey, ez);
		float l2 = dist(ex, ey, ez, wx, wy, wz);
		if (l1 < 1e-5f || l2 < 1e-5f) return;
		
		float dx = tx - sx, dy = ty - sy, dz = tz - sz;
		float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (d < 1e-5f) return;
		
		float maxD = (l1 + l2) * 0.999f;
		float minD = Math.abs(l1 - l2) * 1.001f + 1e-4f;
		float dc = d > maxD ? maxD : (d < minD ? minD : d);
		float ux = dx / d, uy = dy / d, uz = dz / d;
		
		float a = (l1 * l1 - l2 * l2 + dc * dc) / (2f * dc);
		float hh = l1 * l1 - a * a;
		float h = hh > 0f ? (float) Math.sqrt(hh) : 0f;
		
		float dot = hx * ux + hy * uy + hz * uz;
		float px = hx - ux * dot, py = hy - uy * dot, pz = hz - uz * dot;
		float pl = (float) Math.sqrt(px * px + py * py + pz * pz);
		
		// ═══ FIX: rtX/rtZ ki jagah safe fallback derive karo ═══
		if (pl < 0.01f) {
			// Use "down" as base pole, orthogonalized against the shoulder->target axis.
			// This keeps elbows pointing naturally downward without needing character axes.
			px = 0f; py = -1f; pz = 0f;
			dot = px * ux + py * uy + pz * uz;
			px -= ux * dot; py -= uy * dot; pz -= uz * dot;
			pl = (float) Math.sqrt(px * px + py * py + pz * pz);
			
			// If still degenerate (arm pointing straight down), pick any perpendicular axis
			if (pl < 0.01f) {
				// Cross product of (0,-1,0) with the axis gives a side vector
				px = -uz; py = 0f; pz = ux;
				pl = (float) Math.sqrt(px * px + pz * pz);
				if (pl < 1e-4f) { px = 1f; py = 0f; pz = 0f; pl = 1f; }
			}
		}
		
		px /= pl; py /= pl; pz /= pl;
		float nex = sx + ux * a + px * h;
		float ney = sy + uy * a + py * h;
		float nez = sz + uz * a + pz * h;
		float ntx = sx + ux * dc;
		float nty = sy + uy * dc;
		float ntz = sz + uz * dc;
		
		rotateNodeToward(upper, ex - sx, ey - sy, ez - sz,
		nex - sx, ney - sy, nez - sz, weight);
		buildSubtree(upper);
		
		float ex2 = W[lower * 16 + 12], ey2 = W[lower * 16 + 13], ez2 = W[lower * 16 + 14];
		float wx2 = W[hand * 16 + 12],  wy2 = W[hand * 16 + 13],  wz2 = W[hand * 16 + 14];
		rotateNodeToward(lower, wx2 - ex2, wy2 - ey2, wz2 - ez2,
		ntx - ex2, nty - ey2, ntz - ez2, weight);
		buildSubtree(lower);
	}
	
	/** Rotates 'node' (in world space, by the shortest arc) so direction 'from' turns into 'to'. */
	private void rotateNodeToward(int node, float fx, float fy, float fz, float tx, float ty, float tz, float weight) {
		float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz), tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
		if (fl < 1e-6f || tl < 1e-6f) return;
		fx /= fl; fy /= fl; fz /= fl; tx /= tl; ty /= tl; tz /= tl;
		float d = fx * tx + fy * ty + fz * tz;
		float cx = fy * tz - fz * ty, cy = fz * tx - fx * tz, cz = fx * ty - fy * tx;
		// delta quaternion (world space)
		float qx, qy, qz, qw;
		if (d < -0.99999f) {   // opposite: any perpendicular axis
			float ax = Math.abs(fx) < 0.9f ? 1f : 0f, ay = ax == 1f ? 0f : 1f;
			float ox = fy * 0f - fz * ay, oy = fz * ax - fx * 0f, oz = fx * ay - fy * ax;
			float ol = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
			qx = ox / ol; qy = oy / ol; qz = oz / ol; qw = 0f;
		} else {
			qw = 1f + d; qx = cx; qy = cy; qz = cz;
			float ql = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
			qx /= ql; qy /= ql; qz /= ql; qw /= ql;
		}
		if (weight < 1f) {      // blend with identity
			q1[0] = 0f; q1[1] = 0f; q1[2] = 0f; q1[3] = 1f;
			q2[0] = qx; q2[1] = qy; q2[2] = qz; q2[3] = qw;
			slerp(q1, 0, q2, 0, weight, q3, 0);
			qx = q3[0]; qy = q3[1]; qz = q3[2]; qw = q3[3];
		}
		// world rotation of the parent
		int p = parent[node];
		float[] pr = q1;
		if (p < 0) { pr[0] = 0f; pr[1] = 0f; pr[2] = 0f; pr[3] = 1f; }
		else worldRot(p, pr);
		// local' = inv(P) * D * P * local
		float[] D = q2; D[0] = qx; D[1] = qy; D[2] = qz; D[3] = qw;
		float[] tmp = q3;
		qmul(D, pr, tmp);                       // D * P
		float[] pinv = D;                        // reuse
		pinv[0] = -pr[0]; pinv[1] = -pr[1]; pinv[2] = -pr[2]; pinv[3] = pr[3];
		float[] res = pr;                        // reuse
		qmul(pinv, tmp, res);                    // inv(P) * D * P
		int r = node * 4;
		float lx = localR[r], ly = localR[r + 1], lz = localR[r + 2], lw = localR[r + 3];
		tmp[0] = lx; tmp[1] = ly; tmp[2] = lz; tmp[3] = lw;
		qmul(res, tmp, pinv);
		float l = (float) Math.sqrt(pinv[0] * pinv[0] + pinv[1] * pinv[1] + pinv[2] * pinv[2] + pinv[3] * pinv[3]);
		if (l < 1e-8f) return;
		localR[r] = pinv[0] / l; localR[r + 1] = pinv[1] / l; localR[r + 2] = pinv[2] / l; localR[r + 3] = pinv[3] / l;
	}
	
	/** World-space rotation of a node as a quaternion (from its world matrix, scale removed). */
	private void worldRot(int node, float[] out) {
		int o = node * 16;
		float[] W = nodeWorld;
		float sx = len3(W[o], W[o + 1], W[o + 2]), sy = len3(W[o + 4], W[o + 5], W[o + 6]), sz = len3(W[o + 8], W[o + 9], W[o + 10]);
		if (sx < 1e-7f) sx = 1f;
		if (sy < 1e-7f) sy = 1f;
		if (sz < 1e-7f) sz = 1f;
		float m00 = W[o] / sx, m10 = W[o + 1] / sx, m20 = W[o + 2] / sx;
		float m01 = W[o + 4] / sy, m11 = W[o + 5] / sy, m21 = W[o + 6] / sy;
		float m02 = W[o + 8] / sz, m12 = W[o + 9] / sz, m22 = W[o + 10] / sz;
		float trace = m00 + m11 + m22;
		if (trace > 0f) {
			float s = (float) Math.sqrt(trace + 1f) * 2f;
			out[3] = 0.25f * s; out[0] = (m21 - m12) / s; out[1] = (m02 - m20) / s; out[2] = (m10 - m01) / s;
		} else if (m00 > m11 && m00 > m22) {
			float s = (float) Math.sqrt(1f + m00 - m11 - m22) * 2f;
			out[3] = (m21 - m12) / s; out[0] = 0.25f * s; out[1] = (m01 + m10) / s; out[2] = (m02 + m20) / s;
		} else if (m11 > m22) {
			float s = (float) Math.sqrt(1f + m11 - m00 - m22) * 2f;
			out[3] = (m02 - m20) / s; out[0] = (m01 + m10) / s; out[1] = 0.25f * s; out[2] = (m12 + m21) / s;
		} else {
			float s = (float) Math.sqrt(1f + m22 - m00 - m11) * 2f;
			out[3] = (m10 - m01) / s; out[0] = (m02 + m20) / s; out[1] = (m12 + m21) / s; out[2] = 0.25f * s;
		}
		normalizeQuat(out, 0);
	}
	
	/** Rebuilds world matrices for 'root' and everything below it. */
	private void buildSubtree(int root) {
		for (int k = 0; k < n; k++) {
			int i = order[k];
			if (!isUnder(i, root)) continue;
			int t = i * 3, r = i * 4;
			float x = localR[r], y = localR[r + 1], z = localR[r + 2], w = localR[r + 3];
			float xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
			float sx = localS[t], sy = localS[t + 1], sz = localS[t + 2];
			local[0] = (1f - 2f * (yy + zz)) * sx; local[1] = 2f * (xy + wz) * sx; local[2] = 2f * (xz - wy) * sx; local[3] = 0f;
			local[4] = 2f * (xy - wz) * sy; local[5] = (1f - 2f * (xx + zz)) * sy; local[6] = 2f * (yz + wx) * sy; local[7] = 0f;
			local[8] = 2f * (xz + wy) * sz; local[9] = 2f * (yz - wx) * sz; local[10] = (1f - 2f * (xx + yy)) * sz; local[11] = 0f;
			local[12] = localT[t]; local[13] = localT[t + 1]; local[14] = localT[t + 2]; local[15] = 1f;
			int p = parent[i];
			if (p < 0) System.arraycopy(local, 0, nodeWorld, i * 16, 16);
			else mul(nodeWorld, p * 16, local, 0, nodeWorld, i * 16);
		}
	}
	
	private boolean isUnder(int node, int root) {
		int guard = 0;
		while (node >= 0 && guard++ < 256) {
			if (node == root) return true;
			node = parent[node];
		}
		return false;
	}
	
	// ------------------------------------------------------------------
	// sampling
	// ------------------------------------------------------------------
	private static int findKey(float[] times, float time) {
		int lo = 0, hi = times.length - 1;
		while (lo + 1 < hi) { int mid = (lo + hi) >>> 1; if (times[mid] <= time) lo = mid; else hi = mid; }
		return lo;
	}
	
	private static void sampleVec(AnimationClip.Track tr, float time, float[] out, int o) {
		float[] ts = tr.times, v = tr.values;
		int nk = ts.length;
		if (nk * 3 > v.length) return;
		if (nk == 1 || time <= ts[0]) { out[o] = v[0]; out[o + 1] = v[1]; out[o + 2] = v[2]; return; }
		if (time >= ts[nk - 1]) { int b = (nk - 1) * 3; out[o] = v[b]; out[o + 1] = v[b + 1]; out[o + 2] = v[b + 2]; return; }
		int lo = findKey(ts, time), hi = lo + 1;
		int a = lo * 3, b = hi * 3;
		if (tr.step) { out[o] = v[a]; out[o + 1] = v[a + 1]; out[o + 2] = v[a + 2]; return; }
		float span = ts[hi] - ts[lo];
		float u = span > 1e-7f ? (time - ts[lo]) / span : 0f;
		out[o]     = v[a]     + (v[b]     - v[a])     * u;
		out[o + 1] = v[a + 1] + (v[b + 1] - v[a + 1]) * u;
		out[o + 2] = v[a + 2] + (v[b + 2] - v[a + 2]) * u;
	}
	
	private static void sampleRot(AnimationClip.Track tr, float time, float[] out, int o) {
		float[] ts = tr.times, v = tr.values;
		int nk = ts.length;
		if (nk * 4 > v.length) return;
		if (nk == 1 || time <= ts[0]) { copy4(v, 0, out, o); return; }
		if (time >= ts[nk - 1]) { copy4(v, (nk - 1) * 4, out, o); return; }
		int lo = findKey(ts, time), hi = lo + 1;
		if (tr.step) { copy4(v, lo * 4, out, o); return; }
		float span = ts[hi] - ts[lo];
		float u = span > 1e-7f ? (time - ts[lo]) / span : 0f;
		slerp(v, lo * 4, v, hi * 4, u, out, o);
	}
	
	private static void copy4(float[] a, int ao, float[] b, int bo) {
		b[bo] = a[ao]; b[bo + 1] = a[ao + 1]; b[bo + 2] = a[ao + 2]; b[bo + 3] = a[ao + 3];
	}
	
	// ------------------------------------------------------------------
	// math
	// ------------------------------------------------------------------
	/** out = a * b (column-major 4x4). 'out' must not overlap a or b. */
	private static void mul(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
		for (int c = 0; c < 4; c++) {
			float b0 = b[bo + c * 4], b1 = b[bo + c * 4 + 1], b2 = b[bo + c * 4 + 2], b3 = b[bo + c * 4 + 3];
			out[oo + c * 4]     = a[ao]     * b0 + a[ao + 4] * b1 + a[ao + 8]  * b2 + a[ao + 12] * b3;
			out[oo + c * 4 + 1] = a[ao + 1] * b0 + a[ao + 5] * b1 + a[ao + 9]  * b2 + a[ao + 13] * b3;
			out[oo + c * 4 + 2] = a[ao + 2] * b0 + a[ao + 6] * b1 + a[ao + 10] * b2 + a[ao + 14] * b3;
			out[oo + c * 4 + 3] = a[ao + 3] * b0 + a[ao + 7] * b1 + a[ao + 11] * b2 + a[ao + 15] * b3;
		}
	}
	
	private static void identity(float[] m, int o) {
		for (int i = 0; i < 16; i++) m[o + i] = 0f;
		m[o] = 1f; m[o + 5] = 1f; m[o + 10] = 1f; m[o + 15] = 1f;
	}
	
	/** out = a * b (quaternions x,y,z,w). out must not alias a or b. */
	private static void qmul(float[] a, float[] b, float[] out) {
		out[0] = a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1];
		out[1] = a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0];
		out[2] = a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3];
		out[3] = a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2];
	}
	
	private static void slerp(float[] a, int ao, float[] b, int bo, float t, float[] out, int oo) {
		float ax = a[ao], ay = a[ao + 1], az = a[ao + 2], aw = a[ao + 3];
		float bx = b[bo], by = b[bo + 1], bz = b[bo + 2], bw = b[bo + 3];
		float dot = ax * bx + ay * by + az * bz + aw * bw;
		float sign = 1f;
		if (dot < 0f) { dot = -dot; sign = -1f; }
		float k0, k1;
		if (dot > 0.9995f) { k0 = 1f - t; k1 = t * sign; }
		else {
			if (dot > 1f) dot = 1f;
			float theta = (float) Math.acos(dot), sin = (float) Math.sin(theta);
			k0 = (float) Math.sin((1f - t) * theta) / sin;
			k1 = (float) Math.sin(t * theta) / sin * sign;
		}
		out[oo] = ax * k0 + bx * k1; out[oo + 1] = ay * k0 + by * k1;
		out[oo + 2] = az * k0 + bz * k1; out[oo + 3] = aw * k0 + bw * k1;
		normalizeQuat(out, oo);
	}
	
	private static void normalizeQuat(float[] q, int o) {
		float l = (float) Math.sqrt(q[o] * q[o] + q[o + 1] * q[o + 1] + q[o + 2] * q[o + 2] + q[o + 3] * q[o + 3]);
		if (l > 1e-8f) { q[o] /= l; q[o + 1] /= l; q[o + 2] /= l; q[o + 3] /= l; }
		else { q[o] = 0f; q[o + 1] = 0f; q[o + 2] = 0f; q[o + 3] = 1f; }
	}
	
	private static void matrixToQuat(float[] m, float[] q, int o) {
		float trace = m[0] + m[5] + m[10];
		if (trace > 0f) {
			float s = (float) Math.sqrt(trace + 1f) * 2f;
			q[o + 3] = 0.25f * s; q[o] = (m[6] - m[9]) / s; q[o + 1] = (m[8] - m[2]) / s; q[o + 2] = (m[1] - m[4]) / s;
		} else if (m[0] > m[5] && m[0] > m[10]) {
			float s = (float) Math.sqrt(1f + m[0] - m[5] - m[10]) * 2f;
			q[o + 3] = (m[6] - m[9]) / s; q[o] = 0.25f * s; q[o + 1] = (m[4] + m[1]) / s; q[o + 2] = (m[8] + m[2]) / s;
		} else if (m[5] > m[10]) {
			float s = (float) Math.sqrt(1f + m[5] - m[0] - m[10]) * 2f;
			q[o + 3] = (m[8] - m[2]) / s; q[o] = (m[4] + m[1]) / s; q[o + 1] = 0.25f * s; q[o + 2] = (m[9] + m[6]) / s;
		} else {
			float s = (float) Math.sqrt(1f + m[10] - m[0] - m[5]) * 2f;
			q[o + 3] = (m[1] - m[4]) / s; q[o] = (m[8] + m[2]) / s; q[o + 1] = (m[9] + m[6]) / s; q[o + 2] = 0.25f * s;
		}
		normalizeQuat(q, o);
	}
	
	private static float len3(float x, float y, float z) { return (float) Math.sqrt(x * x + y * y + z * z); }
	private static float dist(float ax, float ay, float az, float bx, float by, float bz) {
		float x = ax - bx, y = ay - by, z = az - bz;
		return (float) Math.sqrt(x * x + y * y + z * z);
	}
}
