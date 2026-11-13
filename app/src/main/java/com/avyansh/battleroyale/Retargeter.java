package com.avyansh.battleroyale;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Retargets an animation made for ANY humanoid rig (Mixamo, Blender, rig-native...) onto the
 * character rig. Works in WORLD space, so different bone axes / T-pose vs A-pose do not matter:
 *  - hips, spine, neck, head, shoulders : world rotation delta from the source rest pose
 *  - upper arm / forearm / thigh / shin : the bone is pointed where the source bone points (+ twist)
 *  - hands, feet, toes                  : keep the source's extra rotation relative to their parent
 * The result is BAKED once at load time into normal rotation tracks (no per-frame cost).
 * Pure Java, no Android classes.
 */
public final class Retargeter {
	private Retargeter() {}

	private static final String[] DIR_BONES = { "lul", "lll", "rul", "rll", "la", "lf", "ra", "rf" };
	private static final String[] DIR_CHILD = { "lll", "lfoot", "rll", "rfoot", "lf", "lh", "rf", "rh" };
	private static final String[] REL_BONES = { "lfoot", "rfoot", "ltoe", "rtoe", "lh", "rh" };
	private static final String[] REL_PARENT = { "lll", "rll", "lfoot", "rfoot", "lf", "rf" };
	private static final int FPS = 30;

	static String key(String n) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; n != null && i < n.length(); i++) {
			char c = Character.toLowerCase(n.charAt(i));
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
		}
		String k = sb.toString();
		return k.startsWith("mixamorig") ? k.substring(9) : k;
	}

	/** One rig reduced to what retargeting needs. */
	static final class Rig {
		int n;
		int[] parent;
		String[] names;
		double[] lt, lr, ls;           // local T (3), R quaternion (4), S (3) per node
		double[] wRot, wPos;           // world rotation (9, row-major, normalised) and position (3)
		int[] order;
		HashMap<String, Integer> canon = new HashMap<String, Integer>();

		Rig(GltfModel m) {
			n = m.nodes.length;
			parent = new int[n]; names = new String[n];
			lt = new double[n * 3]; lr = new double[n * 4]; ls = new double[n * 3];
			java.util.Arrays.fill(parent, -1);
			int roots;
			for (int i = 0; i < n; i++) {
				names[i] = m.nodes[i].name;
				int[] ch = m.nodes[i].children;
				if (ch != null) for (int c : ch) if (c >= 0 && c < n) parent[c] = i;
				float[] a = m.nodes[i].localMatrix;
				lt[i * 3] = a[12]; lt[i * 3 + 1] = a[13]; lt[i * 3 + 2] = a[14];
				double sx = len(a[0], a[1], a[2]), sy = len(a[4], a[5], a[6]), sz = len(a[8], a[9], a[10]);
				if (sx < 1e-9) sx = 1; if (sy < 1e-9) sy = 1; if (sz < 1e-9) sz = 1;
				ls[i * 3] = sx; ls[i * 3 + 1] = sy; ls[i * 3 + 2] = sz;
				double[] r = { a[0] / sx, a[4] / sy, a[8] / sz, a[1] / sx, a[5] / sy, a[9] / sz, a[2] / sx, a[6] / sy, a[10] / sz };
				double[] q = mat2q(r);
				System.arraycopy(q, 0, lr, i * 4, 4);
			}
			roots = 0;
			for (int i = 0; i < n; i++) if (parent[i] < 0) roots++;
			if (roots > n / 2) fixFlat();
			buildOrder();
			mapBones();
			world(lt, lr);
		}

		/** Some exporters drop the hierarchy: rebuild it from the standard humanoid names. */
		void fixFlat() {
			String[][] p = { { "spine", "hips" }, { "spine1", "spine" }, { "spine2", "spine1" }, { "neck", "spine2" }, { "head", "neck" },
				{ "leftshoulder", "spine2" }, { "leftarm", "leftshoulder" }, { "leftforearm", "leftarm" }, { "lefthand", "leftforearm" },
				{ "rightshoulder", "spine2" }, { "rightarm", "rightshoulder" }, { "rightforearm", "rightarm" }, { "righthand", "rightforearm" },
				{ "leftupleg", "hips" }, { "leftleg", "leftupleg" }, { "leftfoot", "leftleg" }, { "lefttoebase", "leftfoot" },
				{ "rightupleg", "hips" }, { "rightleg", "rightupleg" }, { "rightfoot", "rightleg" }, { "righttoebase", "rightfoot" } };
			HashMap<String, Integer> idx = new HashMap<String, Integer>();
			for (int i = 0; i < n; i++) idx.put(key(names[i]), i);
			java.util.Arrays.fill(parent, -1);
			for (String[] e : p) {
				Integer a = idx.get(e[0]), b = idx.get(e[1]);
				if (a != null && b != null) parent[a] = b;
			}
		}

		void buildOrder() {
			order = new int[n];
			boolean[] done = new boolean[n];
			int k = 0;
			boolean prog = true;
			while (k < n && prog) {
				prog = false;
				for (int i = 0; i < n; i++) {
					if (done[i]) continue;
					if (parent[i] < 0 || done[parent[i]]) { order[k++] = i; done[i] = true; prog = true; }
				}
			}
			for (int i = 0; i < n; i++) if (!done[i]) { order[k++] = i; parent[i] = -1; }
		}

		void world(double[] T, double[] R) {
			wRot = new double[n * 9]; wPos = new double[n * 3];
			double[] sc = new double[n];            // accumulated uniform scale
			for (int q = 0; q < n; q++) {
				int i = order[q], p = parent[i];
				double[] lm = q2m(R, i * 4);
				if (p < 0) {
					System.arraycopy(lm, 0, wRot, i * 9, 9);
					wPos[i * 3] = T[i * 3]; wPos[i * 3 + 1] = T[i * 3 + 1]; wPos[i * 3 + 2] = T[i * 3 + 2];
					sc[i] = ls[i * 3];
				} else {
					double[] pr = sub(wRot, p * 9);
					double[] w = mul(pr, lm);
					System.arraycopy(w, 0, wRot, i * 9, 9);
					double s = sc[p];
					wPos[i * 3]     = wPos[p * 3]     + s * (pr[0] * T[i * 3] + pr[1] * T[i * 3 + 1] + pr[2] * T[i * 3 + 2]);
					wPos[i * 3 + 1] = wPos[p * 3 + 1] + s * (pr[3] * T[i * 3] + pr[4] * T[i * 3 + 1] + pr[5] * T[i * 3 + 2]);
					wPos[i * 3 + 2] = wPos[p * 3 + 2] + s * (pr[6] * T[i * 3] + pr[7] * T[i * 3 + 1] + pr[8] * T[i * 3 + 2]);
					sc[i] = s * ls[i * 3];
				}
			}
			scale = sc;
		}
		double[] scale;

		void mapBones() {
			String[][] simple = {
				{ "hips", "hips" }, { "pelvis", "hips" }, { "neck", "neck" }, { "head", "head" },
				{ "leftshoulder", "ls" }, { "leftclavicle", "ls" }, { "rightshoulder", "rs" }, { "rightclavicle", "rs" },
				{ "leftarm", "la" }, { "leftupperarm", "la" }, { "rightarm", "ra" }, { "rightupperarm", "ra" },
				{ "leftforearm", "lf" }, { "leftlowerarm", "lf" }, { "rightforearm", "rf" }, { "rightlowerarm", "rf" },
				{ "lefthand", "lh" }, { "righthand", "rh" },
				{ "leftupleg", "lul" }, { "leftupperleg", "lul" }, { "leftthigh", "lul" }, { "rightupleg", "rul" }, { "rightupperleg", "rul" }, { "rightthigh", "rul" },
				{ "leftleg", "lll" }, { "leftlowerleg", "lll" }, { "leftcalf", "lll" }, { "leftshin", "lll" },
				{ "rightleg", "rll" }, { "rightlowerleg", "rll" }, { "rightcalf", "rll" }, { "rightshin", "rll" },
				{ "leftfoot", "lfoot" }, { "rightfoot", "rfoot" }, { "lefttoebase", "ltoe" }, { "lefttoe", "ltoe" },
				{ "righttoebase", "rtoe" }, { "righttoe", "rtoe" } };
			HashMap<String, String> sm = new HashMap<String, String>();
			for (String[] e : simple) sm.put(e[0], e[1]);
			ArrayList<Integer> spine = new ArrayList<Integer>();
			for (int i = 0; i < n; i++) {
				String k = key(names[i]);
				String c = sm.get(k);
				if (c != null) { if (!canon.containsKey(c)) canon.put(c, i); }
				else if (k.startsWith("spine") || k.equals("chest") || k.equals("upperchest")) spine.add(i);
			}
			// spine bones are ordered by their height in the rest pose (works for Spine/Spine1/Spine2 AND Spine02/01/Spine)
			double[] restY = new double[n];
			double[] T = lt, R = lr;
			world(T, R);
			for (int i = 0; i < n; i++) restY[i] = wPos[i * 3 + 1];
			final double[] ry = restY;
			java.util.Collections.sort(spine, new java.util.Comparator<Integer>() {
				public int compare(Integer a, Integer b) { return Double.compare(ry[a], ry[b]); }
			});
			String[] sn = { "s0", "s1", "s2" };
			for (int i = 0; i < spine.size() && i < 3; i++) canon.put(sn[i], spine.get(i));
		}
	}

	// ------------------------------------------------------------------ public
	/** Returns a baked clip on the target rig, or null when too few bones match. */
	public static AnimationClip retarget(GltfModel srcModel, AnimationClip sc, GltfModel tgtModel, String name) {
		try {
			Rig s = new Rig(srcModel), t = new Rig(tgtModel);
			ArrayList<String> keys = new ArrayList<String>();
			for (String k : t.canon.keySet()) if (s.canon.containsKey(k)) keys.add(k);
			if (keys.size() < 8 || !keys.contains("hips")) return null;

			final double[] sRestRot = s.wRot.clone(), sRestPos = s.wPos.clone();
			final double[] tRestRot = t.wRot.clone(), tRestPos = t.wPos.clone();
			double[] tRestScale = t.scale.clone();

			// tracks of the source clip by node
			Track[] trR = new Track[s.n], trT = new Track[s.n];
			float dur = sc.duration;
			for (AnimationClip.Track tr : sc.tracks) {
				if (tr.nodeIndex < 0 || tr.nodeIndex >= s.n || tr.times == null || tr.times.length == 0) continue;
				if ("rotation".equals(tr.path)) trR[tr.nodeIndex] = new Track(tr);
				else if ("translation".equals(tr.path)) trT[tr.nodeIndex] = new Track(tr);
				if (tr.times[tr.times.length - 1] > dur) dur = tr.times[tr.times.length - 1];
			}
			if (dur < 0.05f) return null;
			int frames = Math.max(2, (int) Math.ceil(dur * FPS) + 1);
			float[] times = new float[frames];
			for (int f = 0; f < frames; f++) times[f] = Math.min(dur, f / (float) FPS);

			int hipsT = t.canon.get("hips"), hipsS = s.canon.get("hips");
			double ratio = tRestPos[hipsT * 3 + 1] / Math.max(1e-6, sRestPos[hipsS * 3 + 1]);

			HashMap<Integer, float[]> outR = new HashMap<Integer, float[]>();
			for (String k : keys) outR.put(t.canon.get(k), new float[frames * 4]);
			float[] outHips = new float[frames * 3];

			double[] sR = new double[s.n * 4], sT = new double[s.n * 3];
			double[] tR = new double[t.n * 4];
			HashMap<Integer, String> tNode = new HashMap<Integer, String>();
			for (String k : keys) tNode.put(t.canon.get(k), k);
			double[] prevQ = new double[t.n * 4];

			for (int f = 0; f < frames; f++) {
				float time = times[f];
				System.arraycopy(s.lr, 0, sR, 0, sR.length);
				System.arraycopy(s.lt, 0, sT, 0, sT.length);
				for (int i = 0; i < s.n; i++) {
					if (trR[i] != null) trR[i].sampleQuat(time, sR, i * 4);
					if (trT[i] != null && i == hipsS) trT[i].sampleVec(time, sT, i * 3);
				}
				s.world(sT, sR);
				HashMap<String, double[]> dS = new HashMap<String, double[]>();
				HashMap<String, double[]> sRot = new HashMap<String, double[]>();
				for (String k : keys) {
					int si = s.canon.get(k);
					double[] cur = sub(s.wRot, si * 9), rest = sub(sRestRot, si * 9);
					sRot.put(k, cur);
					dS.put(k, mul(cur, tr(rest)));
				}
				HashMap<String, double[]> newW = new HashMap<String, double[]>();
				double[] Rw = new double[t.n * 9];
				System.arraycopy(t.lr, 0, tR, 0, tR.length);
				for (int q = 0; q < t.n; q++) {
					int i = t.order[q], p = t.parent[i];
					double[] Pw = p < 0 ? ident() : sub(Rw, p * 9);
					String k = tNode.get(i);
					double[] Wn;
					if (k == null) {
						Wn = mul(Pw, q2m(t.lr, i * 4));
					} else {
						double[] restT = sub(tRestRot, i * 9);
						int dirIdx = idx(DIR_BONES, k), relIdx = idx(REL_BONES, k);
						if (dirIdx >= 0 && keys.contains(DIR_CHILD[dirIdx])) {
							String c = DIR_CHILD[dirIdx];
							double[] ds0 = dir(sRestPos, s.canon.get(k), s.canon.get(c));
							double[] dsT = dir(s.wPos, s.canon.get(k), s.canon.get(c));
							double[] dt0 = dir(tRestPos, t.canon.get(k), t.canon.get(c));
							double[] q4 = m2q(dS.get(k));
							double ang = 2.0 * Math.atan2(q4[0] * ds0[0] + q4[1] * ds0[1] + q4[2] * ds0[2], q4[3]);
							Wn = mul(arc(dt0, dsT), mul(axisRot(dt0, ang), restT));
						} else if (relIdx >= 0 && keys.contains(REL_PARENT[relIdx])) {
							String pk = REL_PARENT[relIdx];
							double[] pRestT = sub(tRestRot, t.canon.get(pk) * 9);
							double[] corr = mul(newW.get(pk), tr(pRestT));
							double[] extra = mul(dS.get(k), tr(dS.get(pk)));
							Wn = mul(extra, mul(corr, restT));
						} else {
							Wn = mul(dS.get(k), restT);
						}
						newW.put(k, Wn);
					}
					System.arraycopy(Wn, 0, Rw, i * 9, 9);
					if (k != null) {
						double[] loc = m2q(mul(tr(Pw), Wn));
						int o = i * 4;
						if (f > 0 && loc[0] * prevQ[o] + loc[1] * prevQ[o + 1] + loc[2] * prevQ[o + 2] + loc[3] * prevQ[o + 3] < 0) {
							loc[0] = -loc[0]; loc[1] = -loc[1]; loc[2] = -loc[2]; loc[3] = -loc[3];
						}
						System.arraycopy(loc, 0, prevQ, o, 4);
						float[] arr = outR.get(i);
						for (int c = 0; c < 4; c++) arr[f * 4 + c] = (float) loc[c];
					}
				}
				// hips translation: world delta of the source hips, scaled to the target's size
				double dx = (s.wPos[hipsS * 3] - sRestPos[hipsS * 3]) * ratio;
				double dy = (s.wPos[hipsS * 3 + 1] - sRestPos[hipsS * 3 + 1]) * ratio;
				double dz = (s.wPos[hipsS * 3 + 2] - sRestPos[hipsS * 3 + 2]) * ratio;
				double wx = tRestPos[hipsT * 3] + dx, wy = tRestPos[hipsT * 3 + 1] + dy, wz = tRestPos[hipsT * 3 + 2] + dz;
				int hp = t.parent[hipsT];
				double lx, ly, lz;
				if (hp < 0) { lx = wx; ly = wy; lz = wz; }
				else {
					double sc2 = tRestScale[hp];
					double[] pr = sub(tRestRot, hp * 9);
					double rx = wx - tRestPos[hp * 3], ry = wy - tRestPos[hp * 3 + 1], rz = wz - tRestPos[hp * 3 + 2];
					lx = (pr[0] * rx + pr[3] * ry + pr[6] * rz) / sc2;
					ly = (pr[1] * rx + pr[4] * ry + pr[7] * rz) / sc2;
					lz = (pr[2] * rx + pr[5] * ry + pr[8] * rz) / sc2;
				}
				outHips[f * 3] = (float) lx; outHips[f * 3 + 1] = (float) ly; outHips[f * 3 + 2] = (float) lz;
			}

			AnimationClip nc = new AnimationClip();
			nc.name = name;
			nc.duration = times[frames - 1];
			for (Map.Entry<Integer, float[]> e : outR.entrySet()) {
				AnimationClip.Track nt = new AnimationClip.Track();
				nt.nodeIndex = e.getKey(); nt.path = "rotation"; nt.interpolation = "LINEAR";
				nt.times = times; nt.values = e.getValue(); nt.components = 4;
				nc.tracks.add(nt);
			}
			AnimationClip.Track ht = new AnimationClip.Track();
			ht.nodeIndex = hipsT; ht.path = "translation"; ht.interpolation = "LINEAR";
			ht.times = times; ht.values = outHips; ht.components = 3;
			nc.tracks.add(ht);
			return nc;
		} catch (Throwable ex) {
			android.util.Log.e("Retargeter", "retarget failed: " + ex, ex);
			return null;
		}
	}

	// ------------------------------------------------------------------ source track sampling
	private static final class Track {
		final float[] ts, v; final boolean step;
		Track(AnimationClip.Track t) { ts = t.times; v = t.values; step = "STEP".equals(t.interpolation); }
		int find(float time) {
			int lo = 0, hi = ts.length - 1;
			while (lo + 1 < hi) { int mid = (lo + hi) >>> 1; if (ts[mid] <= time) lo = mid; else hi = mid; }
			return lo;
		}
		void sampleVec(float time, double[] out, int o) {
			int n = ts.length;
			if (n * 3 > v.length) return;
			if (n == 1 || time <= ts[0]) { out[o] = v[0]; out[o + 1] = v[1]; out[o + 2] = v[2]; return; }
			if (time >= ts[n - 1]) { int b = (n - 1) * 3; out[o] = v[b]; out[o + 1] = v[b + 1]; out[o + 2] = v[b + 2]; return; }
			int lo = find(time), a = lo * 3, b = a + 3;
			double u = step ? 0 : (time - ts[lo]) / Math.max(1e-7, ts[lo + 1] - ts[lo]);
			for (int c = 0; c < 3; c++) out[o + c] = v[a + c] + (v[b + c] - v[a + c]) * u;
		}
		void sampleQuat(float time, double[] out, int o) {
			int n = ts.length;
			if (n * 4 > v.length) return;
			if (n == 1 || time <= ts[0]) { for (int c = 0; c < 4; c++) out[o + c] = v[c]; return; }
			if (time >= ts[n - 1]) { for (int c = 0; c < 4; c++) out[o + c] = v[(n - 1) * 4 + c]; return; }
			int lo = find(time), a = lo * 4, b = a + 4;
			double u = step ? 0 : (time - ts[lo]) / Math.max(1e-7, ts[lo + 1] - ts[lo]);
			double dot = v[a] * v[b] + v[a + 1] * v[b + 1] + v[a + 2] * v[b + 2] + v[a + 3] * v[b + 3];
			double sg = dot < 0 ? -1 : 1;
			double l = 0;
			for (int c = 0; c < 4; c++) { out[o + c] = v[a + c] * (1 - u) + v[b + c] * sg * u; l += out[o + c] * out[o + c]; }
			l = Math.sqrt(l);
			if (l > 1e-9) for (int c = 0; c < 4; c++) out[o + c] /= l;
		}
	}

	// ------------------------------------------------------------------ math (row-major 3x3 in double[9])
	private static double len(double x, double y, double z) { return Math.sqrt(x * x + y * y + z * z); }
	private static int idx(String[] a, String k) { for (int i = 0; i < a.length; i++) if (a[i].equals(k)) return i; return -1; }
	private static double[] ident() { return new double[] { 1, 0, 0, 0, 1, 0, 0, 0, 1 }; }
	private static double[] sub(double[] a, int o) { double[] r = new double[9]; System.arraycopy(a, o, r, 0, 9); return r; }
	private static double[] tr(double[] m) { return new double[] { m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8] }; }
	private static double[] mul(double[] a, double[] b) {
		double[] r = new double[9];
		for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
			r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
		return r;
	}
	private static double[] dir(double[] pos, int a, int b) {
		double x = pos[b * 3] - pos[a * 3], y = pos[b * 3 + 1] - pos[a * 3 + 1], z = pos[b * 3 + 2] - pos[a * 3 + 2];
		double l = Math.sqrt(x * x + y * y + z * z);
		if (l < 1e-12) return new double[] { 0, -1, 0 };
		return new double[] { x / l, y / l, z / l };
	}
	private static double[] q2m(double[] q, int o) {
		double x = q[o], y = q[o + 1], z = q[o + 2], w = q[o + 3];
		double l = Math.sqrt(x * x + y * y + z * z + w * w);
		if (l < 1e-12) return ident();
		x /= l; y /= l; z /= l; w /= l;
		return new double[] { 1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
			2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
			2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y) };
	}
	private static double[] mat2q(double[] m) { return m2q(m); }
	private static double[] m2q(double[] m) {
		double t = m[0] + m[4] + m[8], x, y, z, w;
		if (t > 0) { double s = Math.sqrt(t + 1) * 2; w = 0.25 * s; x = (m[7] - m[5]) / s; y = (m[2] - m[6]) / s; z = (m[3] - m[1]) / s; }
		else if (m[0] > m[4] && m[0] > m[8]) { double s = Math.sqrt(1 + m[0] - m[4] - m[8]) * 2; w = (m[7] - m[5]) / s; x = 0.25 * s; y = (m[1] + m[3]) / s; z = (m[2] + m[6]) / s; }
		else if (m[4] > m[8]) { double s = Math.sqrt(1 + m[4] - m[0] - m[8]) * 2; w = (m[2] - m[6]) / s; x = (m[1] + m[3]) / s; y = 0.25 * s; z = (m[5] + m[7]) / s; }
		else { double s = Math.sqrt(1 + m[8] - m[0] - m[4]) * 2; w = (m[3] - m[1]) / s; x = (m[2] + m[6]) / s; y = (m[5] + m[7]) / s; z = 0.25 * s; }
		double l = Math.sqrt(x * x + y * y + z * z + w * w);
		return new double[] { x / l, y / l, z / l, w / l };
	}
	private static double[] axisRot(double[] a, double ang) {
		double s = Math.sin(ang / 2), c = Math.cos(ang / 2);
		return q2m(new double[] { a[0] * s, a[1] * s, a[2] * s, c }, 0);
	}
	/** shortest-arc rotation taking unit vector a to unit vector b */
	private static double[] arc(double[] a, double[] b) {
		double d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
		if (d > 0.99999) return ident();
		if (d < -0.99999) {
			double[] ax = { a[1] * 0 - a[2] * 0, a[2] * 1 - a[0] * 0, a[0] * 0 - a[1] * 1 };   // a x (1,0,0) .. fallback below
			double l = len(ax[0], ax[1], ax[2]);
			if (l < 1e-3) ax = new double[] { 0, 0, 1 }; else ax = new double[] { ax[0] / l, ax[1] / l, ax[2] / l };
			return q2m(new double[] { ax[0], ax[1], ax[2], 0 }, 0);
		}
		double cx = a[1] * b[2] - a[2] * b[1], cy = a[2] * b[0] - a[0] * b[2], cz = a[0] * b[1] - a[1] * b[0];
		return q2m(new double[] { cx, cy, cz, 1 + d }, 0);
	}
}
