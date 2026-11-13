package com.avyansh.battleroyale;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;


public final class TreeModel {
	private static final String TAG = "TreeModel";

	public static boolean ready = false;
	public static float[] verts = null;
	public static int vertCount = 0;
	public static final int MAX_VARIANTS = 6;
	public static float[][] variants = new float[MAX_VARIANTS][];
	public static int[] variantCounts = new int[MAX_VARIANTS];
	public static int variantN = 0;

	private static final String[] NAMES = { "tree.obj", "tree1.obj", "pine.obj", "trees.obj", "tree_low.obj" };
	private static final String[] GLB_NAMES = { "tree.glb", "tree1.glb", "pine.glb", "trees.glb", "tree_low.glb", "tree.gltf" };
	private static final String[] DIRS = { "models", "", "model", "models/obj" };
	public static int triCount() { return vertCount / 3; }

	private static final class FL {
		float[] a = new float[4096];
		int n = 0;
		void add(float v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; }
	}

	private static final String[] TREE_KEYS = { "tree", "pine", "palm", "oak", "birch", "spruce", "fir", "cedar", "bush", "maple" };
	public static void load(Context ctx) {
		ready = false;
		verts = null;
		vertCount = 0;
		variantN = 0;
		try {
			AssetManager am = ctx.getAssets();
			ArrayList<String> files = listTreeFiles(am);
			for (String path : files) {
				if (variantN >= MAX_VARIANTS) break;
				String l = path.toLowerCase(Locale.ROOT);
				try {
					if (l.endsWith(".glb") || l.endsWith(".gltf")) {
						GltfModel gm = GltfLoader.loadPath(ctx, path, false);
						if (gm != null && gm.ready) fromGlb(gm);
					} else {
						parse(am, path);
					}
				} catch (Throwable t) {
					Log.e(TAG, "tree model failed (" + path + "): " + t, t);
				}
			}
			if (variantN > 0) {
				verts = variants[0];
				vertCount = variantCounts[0];
				ready = true;
				Log.i(TAG, variantN + " tree model(s) loaded, tris of first = " + triCount());
			} else {
				Log.i(TAG, "no tree model found - using procedural trees (pine, oak, birch, autumn)");
			}
		} catch (Throwable t) {
			Log.e(TAG, "tree load failed: " + t, t);
			ready = false;
		}
	}

	/** Every .glb/.gltf/.obj in the model folders whose name says it is a tree. */
	private static ArrayList<String> listTreeFiles(AssetManager am) {
		ArrayList<String> out = new ArrayList<>();
		for (String d : DIRS) {
			String[] list;
			try { list = am.list(d); } catch (IOException e) { continue; }
			if (list == null) continue;
			Arrays.sort(list);
			for (String have : list) {
				String l = have.toLowerCase(Locale.ROOT);
				if (!(l.endsWith(".glb") || l.endsWith(".gltf") || l.endsWith(".obj"))) continue;
				if (l.contains("street")) continue;
				boolean tree = false;
				for (String k : TREE_KEYS) if (l.contains(k)) { tree = true; break; }
				if (!tree) continue;
				String path = d.isEmpty() ? have : d + "/" + have;
				if (!out.contains(path)) out.add(path);
			}
		}
		return out;
	}

	/** Store one finished tree model; heavy ones are simplified first so they never cause lag. */
	private static void addVariant(float[] v, int vc) {
		if (variantN >= MAX_VARIANTS) return;
		int target = Math.max(60, GameSettings.TREE_MAX_MODEL_TRIS);
		if (vc / 3 > target) {
			float[] d = decimate(v, vc, target);
			if (d != null) { v = d; vc = d.length / 11; }
		}
		variants[variantN] = v;
		variantCounts[variantN] = vc;
		variantN++;
	}

	private static float[] decimate(float[] v, int vc, int targetTris) {
		float cell = 1f / 28f;
		float[] best = null;
		for (int iter = 0; iter < 14; iter++) {
			HashMap<Long, Integer> ids = new HashMap<>();
			int cap = 1024;
			float[] acc = new float[cap * 9];
			int[] cnt = new int[cap];
			int[] map = new int[vc];
			int n = 0;
			for (int i = 0; i < vc; i++) {
				int o = i * 11;
				long ix = (long) Math.floor(v[o] / cell) + 4096, iy = (long) Math.floor(v[o + 1] / cell) + 4096, iz = (long) Math.floor(v[o + 2] / cell) + 4096;
				long key = (ix * 8192L + iy) * 8192L + iz;
				Integer id = ids.get(key);
				if (id == null) {
					id = n++;
					ids.put(key, id);
					if (n > cap) { cap *= 2; acc = Arrays.copyOf(acc, cap * 9); cnt = Arrays.copyOf(cnt, cap); }
				}
				map[i] = id;
				int a = id * 9;
				acc[a] += v[o]; acc[a + 1] += v[o + 1]; acc[a + 2] += v[o + 2];
				acc[a + 3] += v[o + 3]; acc[a + 4] += v[o + 4]; acc[a + 5] += v[o + 5];
				acc[a + 6] += v[o + 6]; acc[a + 7] += v[o + 7]; acc[a + 8] += v[o + 8];
				cnt[id]++;
			}
			FL out = new FL();
			int tris = 0;
			for (int t = 0; t + 2 < vc; t += 3) {
				int a = map[t], b = map[t + 1], c = map[t + 2];
				if (a == b || b == c || a == c) continue;
				int[] tri = { a, b, c };
				for (int k = 0; k < 3; k++) {
					int id = tri[k], q = id * 9;
					float inv = 1f / cnt[id];
					float nx = acc[q + 3] * inv, ny = acc[q + 4] * inv, nz = acc[q + 5] * inv;
					float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
					if (nl > 1e-6f) { nx /= nl; ny /= nl; nz /= nl; } else { nx = 0f; ny = 1f; nz = 0f; }
					out.add(acc[q] * inv); out.add(acc[q + 1] * inv); out.add(acc[q + 2] * inv);
					out.add(nx); out.add(ny); out.add(nz);
					out.add(acc[q + 6] * inv); out.add(acc[q + 7] * inv); out.add(acc[q + 8] * inv);
					out.add(0f); out.add(0f);
				}
				tris++;
			}
			if (tris >= 4) best = Arrays.copyOf(out.a, out.n);
			if (tris <= targetTris) break;
			cell *= 1.25f;
		}
		Log.i(TAG, "simplified tree " + (vc / 3) + " -> " + (best == null ? vc / 3 : best.length / 33) + " tris");
		return best;
	}

	private static String find(AssetManager am, String[] names) {
		for (String d : DIRS) {
			String[] list;
			try { list = am.list(d); } catch (IOException e) { continue; }
			if (list == null) continue;
			for (String want : names) {
				for (String have : list) {
					if (have.equalsIgnoreCase(want)) return d.isEmpty() ? have : d + "/" + have;
				}
			}
			// any other GLB whose name says "tree"/"pine"/"palm"
			if (names == GLB_NAMES) {
				for (String have : list) {
					String l = have.toLowerCase(Locale.ROOT);
					if (!(l.endsWith(".glb") || l.endsWith(".gltf")) || l.contains("street")) continue;
					if (l.contains("tree") || l.contains("pine") || l.contains("palm")) {
						return d.isEmpty() ? have : d + "/" + have;
					}
				}
			}
		}
		return null;
	}

	private static String dirOf(String p) {
		int s = p.lastIndexOf('/');
		return s >= 0 ? p.substring(0, s) : "";
	}

	private static String lastToken(String s) {
		s = s.trim();
		String[] t = s.split("\\s+");
		s = t[t.length - 1];
		int sl = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
		return sl >= 0 ? s.substring(sl + 1) : s;
	}

	private static String findIn(AssetManager am, String dir, String name) {
		String[][] tries = { { dir }, { "models" }, { "" } };
		for (String[] tr : tries) {
			try {
				String[] list = am.list(tr[0]);
				if (list == null) continue;
				for (String have : list) {
					if (have.equalsIgnoreCase(name)) return tr[0].isEmpty() ? have : tr[0] + "/" + have;
				}
			} catch (IOException ignored) { }
		}
		return null;
	}

	private static final class Mat {
		float[] kd = { 0.35f, 0.55f, 0.25f };
		String tex;
		Bitmap bmp;
		float[] avg;
	}

	private static Bitmap loadBitmap(AssetManager am, String path) {
		try {
			BitmapFactory.Options o = new BitmapFactory.Options();
			o.inJustDecodeBounds = true;
			InputStream in = am.open(path);
			try { BitmapFactory.decodeStream(in, null, o); } finally { in.close(); }
			int ss = 1;
			while (o.outWidth / ss > 256 || o.outHeight / ss > 256) ss *= 2;
			BitmapFactory.Options o2 = new BitmapFactory.Options();
			o2.inSampleSize = ss;
			in = am.open(path);
			try { return BitmapFactory.decodeStream(in, null, o2); } finally { in.close(); }
		} catch (Throwable t) {
			return null;
		}
	}

	private static void averageColor(Mat m) {
		if (m.bmp == null) return;
		long r = 0, g = 0, b = 0, c = 0;
		int w = m.bmp.getWidth(), h = m.bmp.getHeight();
		for (int y = 0; y < h; y += 2) {
			for (int x = 0; x < w; x += 2) {
				int p = m.bmp.getPixel(x, y);
				if (((p >>> 24) & 255) < 128) continue;
				r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; c++;
			}
		}
		if (c > 0) m.avg = new float[] { r / (255f * c), g / (255f * c), b / (255f * c) };
	}

	private static void sample(Mat m, float u, float v, float[] out) {
		if (m.bmp == null) { out[0] = m.kd[0]; out[1] = m.kd[1]; out[2] = m.kd[2]; return; }
		u = u - (float) Math.floor(u);
		v = v - (float) Math.floor(v);
		int w = m.bmp.getWidth(), h = m.bmp.getHeight();
		int px = Math.min(w - 1, Math.max(0, (int) (u * w)));
		int py = Math.min(h - 1, Math.max(0, (int) ((1f - v) * h)));
		int p = m.bmp.getPixel(px, py);
		if (((p >>> 24) & 255) < 128 && m.avg != null) {
			out[0] = m.avg[0]; out[1] = m.avg[1]; out[2] = m.avg[2];
		} else {
			out[0] = ((p >> 16) & 255) / 255f;
			out[1] = ((p >> 8) & 255) / 255f;
			out[2] = (p & 255) / 255f;
		}
	}

	private static void parseMtl(AssetManager am, String path, HashMap<String, Mat> mats) {
		try {
			InputStream in = am.open(path);
			BufferedReader br = new BufferedReader(new InputStreamReader(in));
			String line;
			Mat cur = null;
			String dir = dirOf(path);
			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.charAt(0) == '#') continue;
				String[] sp = line.split("\\s+", 2);
				if (sp.length < 2) continue;
				String key = sp[0].toLowerCase(Locale.ROOT);
				if (key.equals("newmtl")) {
					cur = new Mat();
					mats.put(sp[1].trim(), cur);
				} else if (cur == null) {
					continue;
				} else if (key.equals("kd")) {
					String[] c = sp[1].trim().split("\\s+");
					if (c.length >= 3) {
						try { cur.kd = new float[] { Float.parseFloat(c[0]), Float.parseFloat(c[1]), Float.parseFloat(c[2]) }; }
						catch (RuntimeException ignored) { }
					}
				} else if (key.equals("map_kd")) {
					cur.tex = lastToken(sp[1]);
					String tp = findIn(am, dir, cur.tex);
					if (tp != null) { cur.bmp = loadBitmap(am, tp); averageColor(cur); }
				}
			}
			br.close();
		} catch (Throwable ignored) { }
	}

	private static void parse(AssetManager am, String objPath) throws IOException {
		String dir = dirOf(objPath);
		HashMap<String, Mat> mats = new HashMap<>();

		// materials: mtllib lines + <name>.mtl
		{
			InputStream in = am.open(objPath);
			BufferedReader br = new BufferedReader(new InputStreamReader(in), 1 << 16);
			String line;
			int guard = 0;
			while ((line = br.readLine()) != null && guard++ < 500) {
				line = line.trim();
				if (line.regionMatches(true, 0, "mtllib", 0, 6) && line.length() > 7) {
					String mp = findIn(am, dir, lastToken(line.substring(6)));
					if (mp != null) parseMtl(am, mp, mats);
				}
			}
			br.close();
			String base = objPath.substring(objPath.lastIndexOf('/') + 1).replaceAll("(?i)\\.obj$", "") + ".mtl";
			String mp = findIn(am, dir, base);
			if (mp != null && mats.isEmpty()) parseMtl(am, mp, mats);
		}

		FL pos = new FL(), nrm = new FL(), uv = new FL();
		FL out = new FL();               // pos3 nrm3 col3 uv2 per vertex
		boolean[] noNormal;
		ArrayList<Integer> flatTris = new ArrayList<>();   // vertex index (start of tri) needing flat normal
		Mat curMat = new Mat();
		float[] tmpCol = new float[3];

		InputStream in = am.open(objPath);
		BufferedReader br = new BufferedReader(new InputStreamReader(in), 1 << 16);
		String line;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (line.isEmpty() || line.charAt(0) == '#') continue;
			String[] p = line.split("\\s+");
			String k = p[0];
			try {
				if (k.equals("v")) {
					pos.add(Float.parseFloat(p[1])); pos.add(Float.parseFloat(p[2])); pos.add(Float.parseFloat(p[3]));
				} else if (k.equals("vn")) {
					nrm.add(Float.parseFloat(p[1])); nrm.add(Float.parseFloat(p[2])); nrm.add(Float.parseFloat(p[3]));
				} else if (k.equals("vt")) {
					uv.add(Float.parseFloat(p[1])); uv.add(p.length > 2 ? Float.parseFloat(p[2]) : 0f);
				} else if (k.equals("usemtl")) {
					Mat m = p.length > 1 ? mats.get(line.substring(6).trim()) : null;
					curMat = m != null ? m : new Mat();
				} else if (k.equals("f") && p.length >= 4) {
					int nv = p.length - 1;
					int[] vi = new int[nv], ti = new int[nv], ni = new int[nv];
					for (int i = 0; i < nv; i++) {
						String[] f = p[i + 1].split("/", -1);
						vi[i] = idx(f[0], pos.n / 3);
						ti[i] = (f.length > 1 && !f[1].isEmpty()) ? idx(f[1], uv.n / 2) : -1;
						ni[i] = (f.length > 2 && !f[2].isEmpty()) ? idx(f[2], nrm.n / 3) : -1;
					}
					for (int i = 1; i + 1 < nv; i++) {
						int[] corner = { 0, i, i + 1 };
						boolean needFlat = false;
						int startVert = out.n / 11;
						for (int c = 0; c < 3; c++) {
							int q = corner[c];
							out.add(pos.a[vi[q] * 3]); out.add(pos.a[vi[q] * 3 + 1]); out.add(pos.a[vi[q] * 3 + 2]);
							if (ni[q] >= 0 && ni[q] * 3 + 2 < nrm.n) {
								out.add(nrm.a[ni[q] * 3]); out.add(nrm.a[ni[q] * 3 + 1]); out.add(nrm.a[ni[q] * 3 + 2]);
							} else {
								out.add(0f); out.add(1f); out.add(0f);
								needFlat = true;
							}
							float u = 0f, v = 0f;
							if (ti[q] >= 0 && ti[q] * 2 + 1 < uv.n) { u = uv.a[ti[q] * 2]; v = uv.a[ti[q] * 2 + 1]; }
							sample(curMat, u, v, tmpCol);
							out.add(tmpCol[0]); out.add(tmpCol[1]); out.add(tmpCol[2]);
							out.add(0f); out.add(0f);
						}
						if (needFlat) flatTris.add(startVert);
					}
				}
			} catch (RuntimeException e) {
				// skip a bad line
			}
		}
		br.close();

		finish(out, flatTris);
	}

	/** Normalise a baked vertex list (pos3 nrm3 col3 uv2) to unit height and store it. */
	private static void finish(FL out, ArrayList<Integer> flatTris) {
		int vc = out.n / 11;
		if (vc < 3) return;
		float[] a = out.a;

		// bounds
		float minX = 1e9f, minY = 1e9f, minZ = 1e9f, maxX = -1e9f, maxY = -1e9f, maxZ = -1e9f;
		for (int i = 0; i < vc; i++) {
			float x = a[i * 11], y = a[i * 11 + 1], z = a[i * 11 + 2];
			if (x < minX) minX = x; if (x > maxX) maxX = x;
			if (y < minY) minY = y; if (y > maxY) maxY = y;
			if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
		}
		// Z-up export (Blender default without Y-up): rotate so the tall axis becomes Y
		float hY = maxY - minY, dZ = maxZ - minZ, wX = maxX - minX;
		if (dZ > hY * 1.5f && dZ > wX) {
			for (int i = 0; i < vc; i++) {
				int o = i * 11;
				float y = a[o + 1], z = a[o + 2];
				a[o + 1] = z; a[o + 2] = -y;
				float ny = a[o + 4], nz = a[o + 5];
				a[o + 4] = nz; a[o + 5] = -ny;
			}
			minX = 1e9f; minY = 1e9f; minZ = 1e9f; maxX = -1e9f; maxY = -1e9f; maxZ = -1e9f;
			for (int i = 0; i < vc; i++) {
				float x = a[i * 11], y = a[i * 11 + 1], z = a[i * 11 + 2];
				if (x < minX) minX = x; if (x > maxX) maxX = x;
				if (y < minY) minY = y; if (y > maxY) maxY = y;
				if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
			}
			hY = maxY - minY;
		}
		if (hY < 1e-5f) return;

		float cxm = (minX + maxX) * 0.5f, czm = (minZ + maxZ) * 0.5f;
		float inv = 1f / hY;
		for (int i = 0; i < vc; i++) {
			int o = i * 11;
			a[o] = (a[o] - cxm) * inv;
			a[o + 1] = (a[o + 1] - minY) * inv;
			a[o + 2] = (a[o + 2] - czm) * inv;
			// world shader darkens by its detail texture, so lift colours a little
			a[o + 6] = Math.min(1f, a[o + 6] * 1.3f);
			a[o + 7] = Math.min(1f, a[o + 7] * 1.3f);
			a[o + 8] = Math.min(1f, a[o + 8] * 1.3f);
		}
		// flat normals where the OBJ had none
		for (int t = 0; t < flatTris.size(); t++) {
			int s = flatTris.get(t) * 11;
			float ux = a[s + 11] - a[s], uy = a[s + 12] - a[s + 1], uz = a[s + 13] - a[s + 2];
			float wx = a[s + 22] - a[s], wy = a[s + 23] - a[s + 1], wz = a[s + 24] - a[s + 2];
			float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
			float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (l < 1e-9f) continue;
			nx /= l; ny /= l; nz /= l;
			for (int c = 0; c < 3; c++) {
				a[s + c * 11 + 3] = nx; a[s + c * 11 + 4] = ny; a[s + c * 11 + 5] = nz;
			}
		}
		addVariant(Arrays.copyOf(a, vc * 11), vc);
	}

	/** Convert any GLB (static or skinned) into the baked tree vertex format. */
	private static void fromGlb(GltfModel gm) {
		FL out = new FL();
		ArrayList<Integer> flatTris = new ArrayList<>();
		float[] tmp = new float[3];
		for (GltfModel.Mesh mesh : gm.meshes) {
			for (GltfModel.Primitive p : mesh.primitives) {
				if (p.pos == null || p.idx == null) continue;
				float[] avg = null;
				if (p.texBitmap != null) avg = bitmapAverage(p.texBitmap);
				for (int t = 0; t + 2 < p.idx.length; t += 3) {
					for (int c = 0; c < 3; c++) {
						int v = p.idx[t + c];
						out.add(p.pos[v * 3]); out.add(p.pos[v * 3 + 1]); out.add(p.pos[v * 3 + 2]);
						out.add(p.nrm[v * 3]); out.add(p.nrm[v * 3 + 1]); out.add(p.nrm[v * 3 + 2]);
						float r = p.baseColor[0], g = p.baseColor[1], b = p.baseColor[2];
						if (p.col != null) { r *= p.col[v * 4]; g *= p.col[v * 4 + 1]; b *= p.col[v * 4 + 2]; }
						if (p.texBitmap != null && p.uv != null) {
							sampleBitmap(p.texBitmap, avg, p.uv[v * 2], p.uv[v * 2 + 1], tmp);
							r *= tmp[0]; g *= tmp[1]; b *= tmp[2];
						}
						out.add(r); out.add(g); out.add(b);
						out.add(0f); out.add(0f);
					}
				}
			}
		}
		if (out.n / 11 >= 3) finish(out, flatTris);
	}

	private static float[] bitmapAverage(Bitmap bmp) {
		long r = 0, g = 0, b = 0, c = 0;
		int w = bmp.getWidth(), h = bmp.getHeight();
		for (int y = 0; y < h; y += 2) {
			for (int x = 0; x < w; x += 2) {
				int p = bmp.getPixel(x, y);
				if (((p >>> 24) & 255) < 128) continue;
				r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; c++;
			}
		}
		if (c == 0) return new float[] { 0.35f, 0.55f, 0.25f };
		return new float[] { r / (255f * c), g / (255f * c), b / (255f * c) };
	}

	private static void sampleBitmap(Bitmap bmp, float[] avg, float u, float v, float[] out) {
		u = u - (float) Math.floor(u);
		v = v - (float) Math.floor(v);
		int w = bmp.getWidth(), h = bmp.getHeight();
		int px = Math.min(w - 1, Math.max(0, (int) (u * w)));
		int py = Math.min(h - 1, Math.max(0, (int) (v * h)));   // glTF uv origin is top-left
		int p = bmp.getPixel(px, py);
		if (((p >>> 24) & 255) < 128 && avg != null) {
			out[0] = avg[0]; out[1] = avg[1]; out[2] = avg[2];
		} else {
			out[0] = ((p >> 16) & 255) / 255f;
			out[1] = ((p >> 8) & 255) / 255f;
			out[2] = (p & 255) / 255f;
		}
	}

	private static int idx(String s, int count) {
		int i = Integer.parseInt(s);
		return i > 0 ? i - 1 : count + i;
	}
}
