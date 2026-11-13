package com.avyansh.battleroyale;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ObjLoader {

	private static final String TAG = "ObjLoader";
	public static final int FLOATS_PER_VERTEX = 11;
	private static final int MAX_TEX = 2048;
	private static final float[] DEFAULT_COLOR = { 0.8f, 0.8f, 0.8f };

	public static final class Mesh {
		public final int vbo;
		public final int vertexCount;
		public final int textureId;
		public Mesh(int vbo, int vertexCount, int textureId) {
			this.vbo = vbo;
			this.vertexCount = vertexCount;
			this.textureId = textureId;
		}
	}

	public static final class Model {
		public final ArrayList<Mesh> meshes = new ArrayList<>();
		public float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
		public float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

		// --- Rigging fields (populated by ObjLoader after parse) ---
		public boolean rigOk = false;
		public float rigHipY = 0.95f;
		public float rigShoulderY = 1.6f;
		public float rigCenterLat = 0f;
		public float rigArmStart = 0.36f;
		public float rigLatAxis = 0f; // 0=X, 1=Z
		public float rigCenterFwd = 0f;
		public float rigHipHalf = 0.15f;

		public float height() { return maxY - minY; }
		public float width()  { return maxX - minX; }
		public float depth()  { return maxZ - minZ; }
		public boolean isEmpty() { return meshes.isEmpty(); }

		public void release() {
			int[] ids = new int[meshes.size()];
			for (int i = 0; i < meshes.size(); i++) ids[i] = meshes.get(i).vbo;
			if (ids.length > 0) GLES20.glDeleteBuffers(ids.length, ids, 0);
			meshes.clear();
		}
	}

	private static final class FloatList {
		float[] a = new float[8192];
		int n = 0;
		void add(float v) {
			if (n == a.length) a = Arrays.copyOf(a, n * 2);
			a[n++] = v;
		}
	}

	private static final String[] MODEL_DIRS = { "models", "", "model", "models/obj" };
	private static final HashMap<String, String[]> dirCache = new HashMap<>();

	public static Model load(Context ctx, String objName, String mtlName) {
		return load(ctx, objName, mtlName, false);
	}

	public static Model load(Context ctx, String objName, String mtlName, boolean groundToFeet) {
		try {
			AssetManager am = ctx.getAssets();
			dirCache.clear();

			String objPath = findAsset(am, objName, MODEL_DIRS);
			if (objPath == null) {
				Log.w(TAG, "NOT FOUND: " + objName);
				return null;
			}
			String objDir = dirOf(objPath);

			ArrayList<String> mtlNames = new ArrayList<>();
			readMtllibs(am, objPath, mtlNames);
			if (mtlName != null) mtlNames.add(mtlName);
			mtlNames.add(objName.replaceAll("(?i)\\.obj$", "") + ".mtl");

			HashMap<String, String> matTex = new HashMap<>();
			HashMap<String, float[]> matCol = new HashMap<>();
			for (String n : mtlNames) {
				String p = findAsset(am, n, new String[] { objDir, "models", "" });
				if (p == null) continue;
				InputStream in = am.open(p);
				try { parseMtl(in, matTex, matCol); } finally { in.close(); }
			}

			InputStream in = am.open(objPath);
			try {
				Model m = parseObj(ctx, am, in, objDir, matTex, matCol, groundToFeet, objPath);
				return m;
			} finally { in.close(); }
		} catch (Throwable t) {
			Log.e(TAG, "OBJ load failed for " + objName + ": " + t, t);
			return null;
		}
	}

	private static String[] list(AssetManager am, String dir) {
		String[] l = dirCache.get(dir);
		if (l == null) {
			try { l = am.list(dir); } catch (IOException e) { l = null; }
			if (l == null) l = new String[0];
			dirCache.put(dir, l);
		}
		return l;
	}

	private static String join(String dir, String name) {
		return (dir == null || dir.isEmpty()) ? name : dir + "/" + name;
	}

	private static String dirOf(String path) {
		int s = path.lastIndexOf('/');
		return s >= 0 ? path.substring(0, s) : "";
	}

	private static String findAsset(AssetManager am, String name, String[] dirs) {
		if (name == null || name.isEmpty()) return null;
		for (String d : dirs) {
			for (String s : list(am, d)) {
				if (s.equalsIgnoreCase(name)) return join(d, s);
			}
		}
		return null;
	}

	private static void readMtllibs(AssetManager am, String objPath, ArrayList<String> out) {
		try {
			InputStream in = am.open(objPath);
			BufferedReader br = new BufferedReader(new InputStreamReader(in));
			try {
				String line;
				int lines = 0;
				while ((line = br.readLine()) != null && lines++ < 400) {
					line = line.trim();
					if (line.regionMatches(true, 0, "mtllib", 0, 6) && line.length() > 7) {
						String n = lastPathToken(line.substring(6).trim());
						if (!n.isEmpty()) out.add(n);
					}
				}
			} finally { br.close(); }
		} catch (Throwable ignored) { }
	}

	private static String lastPathToken(String s) {
		s = s.trim();
		if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') s = s.substring(1, s.length() - 1);
		else {
			String[] t = s.split("\\s+");
			if (t.length > 1 && !t[t.length - 1].isEmpty()) {
				s = t[t.length - 1];
			}
		}
		int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
		return slash >= 0 ? s.substring(slash + 1) : s;
	}

	private static void parseMtl(InputStream in, HashMap<String, String> tex, HashMap<String, float[]> col)
	throws IOException {
		BufferedReader br = new BufferedReader(new InputStreamReader(in));
		String line, cur = null;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (line.isEmpty() || line.charAt(0) == '#') continue;
			String[] sp = line.split("\\s+", 2);
			if (sp.length < 2) continue;
			String key = sp[0].toLowerCase(Locale.ROOT);
			String rest = sp[1].trim();
			if (key.equals("newmtl")) {
				cur = rest;
			} else if (cur == null) {
				continue;
			} else if (key.equals("map_kd")) {
				tex.put(cur, lastPathToken(rest));
			} else if (key.equals("map_ka") || key.equals("map_d")) {
				if (!tex.containsKey(cur) && key.equals("map_ka")) tex.put(cur, lastPathToken(rest));
			} else if (key.equals("kd")) {
				String[] c = rest.split("\\s+");
				if (c.length >= 3) {
					try {
						col.put(cur, new float[] { Float.parseFloat(c[0]), Float.parseFloat(c[1]), Float.parseFloat(c[2]) });
					} catch (RuntimeException ignored) { }
				}
			}
		}
		br.close();
	}

	private static Model parseObj(Context ctx, AssetManager am, InputStream in, String objDir,
	HashMap<String, String> matTex, HashMap<String, float[]> matCol,
	boolean groundToFeet, String objPath) throws IOException {

		BufferedReader br = new BufferedReader(new InputStreamReader(in), 1 << 16);

		FloatList pos = new FloatList(), nrm = new FloatList(), uv = new FloatList();
		LinkedHashMap<String, FloatList> groups = new LinkedHashMap<>();
		String curMat = "";
		FloatList cur = new FloatList();
		groups.put(curMat, cur);
		float[] color = colorFor(matCol, curMat);
		int badFaces = 0, faces = 0;

		String line;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (line.isEmpty() || line.charAt(0) == '#') continue;
			char c0 = line.charAt(0);
			try {
				if (c0 == 'v') {
					String[] p = line.split("\\s+");
					if (p[0].equals("v")) {
						pos.add(Float.parseFloat(p[1])); pos.add(Float.parseFloat(p[2])); pos.add(Float.parseFloat(p[3]));
					} else if (p[0].equals("vn")) {
						nrm.add(Float.parseFloat(p[1])); nrm.add(Float.parseFloat(p[2])); nrm.add(Float.parseFloat(p[3]));
					} else if (p[0].equals("vt")) {
						uv.add(Float.parseFloat(p[1])); uv.add(p.length > 2 ? Float.parseFloat(p[2]) : 0f);
					}
				} else if (c0 == 'u' && line.startsWith("usemtl")) {
					curMat = line.length() > 7 ? line.substring(7).trim() : "";
					cur = groups.get(curMat);
					if (cur == null) { cur = new FloatList(); groups.put(curMat, cur); }
					color = colorFor(matCol, curMat);
				} else if (c0 == 'f' && line.length() > 2 && Character.isWhitespace(line.charAt(1))) {
					faces++;
					if (!addFace(line, pos, nrm, uv, cur, color)) badFaces++;
				}
			} catch (RuntimeException e) {
				if (c0 == 'f') badFaces++;
			}
		}
		br.close();

		Model model = new Model();
		int total = 0;
		for (FloatList g : groups.values()) {
			for (int i = 0; i + 2 < g.n; i += FLOATS_PER_VERTEX) {
				float x = g.a[i], y = g.a[i + 1], z = g.a[i + 2];
				if (x < model.minX) model.minX = x; if (x > model.maxX) model.maxX = x;
				if (y < model.minY) model.minY = y; if (y > model.maxY) model.maxY = y;
				if (z < model.minZ) model.minZ = z; if (z > model.maxZ) model.maxZ = z;
				total++;
			}
		}
		if (total == 0) {
			return null;
		}
		if (groundToFeet) {
			float dy = -model.minY, dx = 0f, dz = 0f;
			float cx = (model.minX + model.maxX) * 0.5f, cz = (model.minZ + model.maxZ) * 0.5f;
			float span = Math.max(model.width(), model.depth());
			if (Math.abs(cx) > 0.25f * span) dx = -cx;
			if (Math.abs(cz) > 0.25f * span) dz = -cz;
			if (dx != 0f || dy != 0f || dz != 0f) {
				for (FloatList g : groups.values()) {
					for (int i = 0; i + 2 < g.n; i += FLOATS_PER_VERTEX) { g.a[i] += dx; g.a[i + 1] += dy; g.a[i + 2] += dz; }
				}
				model.minX += dx; model.maxX += dx; model.minY += dy; model.maxY += dy; model.minZ += dz; model.maxZ += dz;
			}
		}

		// -----------------------------------------------------------
		// AUTO RIGGING
		// -----------------------------------------------------------
		// The OBJ has already been grounded to feet. Now we compute the rig
		// pivots from model bounds and assign per-vertex (partId, weight) so
		// ModelShader's vertex rig actually rotates things.
		//
		// partId:  0 = left leg,  1 = right leg,  2 = left arm,  3 = right arm
		// weight:  0 = fully static (head/hips/torso), 1 = fully rigged
		// -----------------------------------------------------------
		if (GameSettings.AUTO_RIG_OBJ) {
			float H = model.height();
			float halfW = model.width() * 0.5f;
			float halfD = model.depth() * 0.5f;
			boolean latX = model.width() >= model.depth(); // arms spread along X (typical)

			model.rigOk = true;
			model.rigHipY      = model.minY + H * GameSettings.RIG_HIP_FRAC;
			model.rigShoulderY = model.minY + H * GameSettings.RIG_SHOULDER_FRAC;
			model.rigLatAxis   = latX ? 0f : 1f;
			model.rigCenterLat = (latX ? (model.minX + model.maxX) : (model.minZ + model.maxZ)) * 0.5f;
			model.rigCenterFwd = (latX ? (model.minZ + model.maxZ) : (model.minX + model.maxX)) * 0.5f;
			model.rigHipHalf   = (latX ? halfW : halfD) * 0.18f;
			model.rigArmStart  = (latX ? halfW : halfD) * GameSettings.RIG_ARM_START_FRAC;

			final float hipY = model.rigHipY;
			final float shoulderY = model.rigShoulderY;
			final float centerLat = model.rigCenterLat;
			final float centerFwd = model.rigCenterFwd;
			final float armStart = model.rigArmStart;
			final float legSplit = model.rigHipHalf;
			final boolean latAxis = latX;

			for (FloatList g : groups.values()) {
				for (int i = 0; i + 2 < g.n; i += FLOATS_PER_VERTEX) {
					float px = g.a[i], py = g.a[i + 1], pz = g.a[i + 2];
					float lat = (latAxis ? px : pz) - centerLat;
					float fwd = (latAxis ? pz : px) - centerFwd;

					int   partId = 0;
					float weight = 0f;

					if (py < hipY - 0.02f) {
						// LEGS
						partId = (lat >= 0f) ? 1 : 0;
						weight = 1f;
					} else if (py > shoulderY + 0.02f) {
						// HEAD/TORSO top – static
						weight = 0f;
					} else if (Math.abs(lat) > armStart && Math.abs(fwd) < armStart * 0.9f) {
						// ARMS (extend outward beyond the shoulder line, not too far front/back)
						partId = (lat >= 0f) ? 3 : 2;
						weight = 1f;
					} else if (Math.abs(lat) > legSplit && py < (hipY + shoulderY) * 0.5f) {
						// side of the torso near hips could be arms in T-pose – treat as arm
						partId = (lat >= 0f) ? 3 : 2;
						weight = 0.5f;
					}

					g.a[i + 9] = partId;
					g.a[i + 10] = weight;
				}
			}
		}

		HashMap<String, Integer> texCache = new HashMap<>();
		for (Map.Entry<String, FloatList> e : groups.entrySet()) {
			FloatList g = e.getValue();
			if (g.n == 0) continue;
			int texId = 0;
			String texFile = matTex.get(e.getKey());
			if (texFile != null) {
				Integer cached = texCache.get(texFile.toLowerCase(Locale.ROOT));
				if (cached != null) texId = cached;
				else {
					texId = loadTexture(am, texFile, objDir);
					texCache.put(texFile.toLowerCase(Locale.ROOT), texId);
				}
			}
			int vbo = upload(g.a, g.n);
			model.meshes.add(new Mesh(vbo, g.n / FLOATS_PER_VERTEX, texId));
		}
		return model;
	}

	private static float[] colorFor(HashMap<String, float[]> m, String mat) {
		float[] c = m.get(mat);
		return c != null ? c : DEFAULT_COLOR;
	}

	private static int idx(String s, int count) {
		int i = Integer.parseInt(s);
		return i < 0 ? count + i : i - 1;
	}

	private static boolean addFace(String line, FloatList pos, FloatList nrm, FloatList uv,
	FloatList out, float[] color) {
		String[] parts = line.split("\\s+");
		int n = parts.length - 1;
		if (n < 3) return false;
		int vc = pos.n / 3, tc = uv.n / 2, nc = nrm.n / 3;
		int[] vi = new int[n], ti = new int[n], ni = new int[n];
		boolean allNormals = true;
		for (int i = 0; i < n; i++) {
			String[] f = parts[i + 1].split("/", -1);
			vi[i] = idx(f[0], vc);
			ti[i] = (f.length > 1 && !f[1].isEmpty()) ? idx(f[1], tc) : -1;
			ni[i] = (f.length > 2 && !f[2].isEmpty()) ? idx(f[2], nc) : -1;
			if (vi[i] < 0 || vi[i] >= vc) return false;
			if (ti[i] >= tc) ti[i] = -1;
			if (ni[i] < 0 || ni[i] >= nc) { ni[i] = -1; allNormals = false; }
		}
		float fnx = 0f, fny = 1f, fnz = 0f;
		if (!allNormals) {
			float ax = pos.a[vi[1] * 3] - pos.a[vi[0] * 3], ay = pos.a[vi[1] * 3 + 1] - pos.a[vi[0] * 3 + 1], az = pos.a[vi[1] * 3 + 2] - pos.a[vi[0] * 3 + 2];
			float bx = pos.a[vi[2] * 3] - pos.a[vi[0] * 3], by = pos.a[vi[2] * 3 + 1] - pos.a[vi[0] * 3 + 1], bz = pos.a[vi[2] * 3 + 2] - pos.a[vi[0] * 3 + 2];
			fnx = ay * bz - az * by; fny = az * bx - ax * bz; fnz = ax * by - ay * bx;
			float l = (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
			if (l > 1e-12f) { fnx /= l; fny /= l; fnz /= l; } else { fnx = 0f; fny = 1f; fnz = 0f; }
		}
		for (int i = 2; i < n; i++) {
			emit(out, pos, nrm, uv, vi[0], ti[0], ni[0], color, fnx, fny, fnz);
			emit(out, pos, nrm, uv, vi[i - 1], ti[i - 1], ni[i - 1], color, fnx, fny, fnz);
			emit(out, pos, nrm, uv, vi[i], ti[i], ni[i], color, fnx, fny, fnz);
		}
		return true;
	}

	private static void emit(FloatList out, FloatList pos, FloatList nrm, FloatList uv,
	int vi, int ti, int ni, float[] color, float fnx, float fny, float fnz) {
		out.add(pos.a[vi * 3]); out.add(pos.a[vi * 3 + 1]); out.add(pos.a[vi * 3 + 2]);
		if (ni >= 0) { out.add(nrm.a[ni * 3]); out.add(nrm.a[ni * 3 + 1]); out.add(nrm.a[ni * 3 + 2]); }
		else { out.add(fnx); out.add(fny); out.add(fnz); }
		out.add(color[0]); out.add(color[1]); out.add(color[2]);
		if (ti >= 0) { out.add(uv.a[ti * 2]); out.add(1f - uv.a[ti * 2 + 1]); }
		else { out.add(0f); out.add(0f); }
		// aPart = (partId, weight) — filled by rig pass after parse; default no rig.
		out.add(0f); out.add(0f);
	}

	private static int upload(float[] data, int floatCount) {
		FloatBuffer fb = ByteBuffer.allocateDirect(floatCount * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
		fb.put(data, 0, floatCount).position(0);
		int[] h = new int[1];
		GLES20.glGenBuffers(1, h, 0);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, h[0]);
		GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floatCount * 4, fb, GLES20.GL_STATIC_DRAW);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
		return h[0];
	}

	private static final String[] TEX_EXT = { "", ".png", ".jpg", ".jpeg", ".webp" };

	private static int loadTexture(AssetManager am, String file, String objDir) {
		String[] dirs = { objDir, "textures", "models", "models/textures", "" };
		String stem = file;
		int dot = file.lastIndexOf('.');
		if (dot > 0) stem = file.substring(0, dot);
		String path = findAsset(am, file, dirs);
		for (int i = 1; path == null && i < TEX_EXT.length; i++) path = findAsset(am, stem + TEX_EXT[i], dirs);
		if (path == null) {
			return 0;
		}
		try {
			BitmapFactory.Options o = new BitmapFactory.Options();
			o.inJustDecodeBounds = true;
			InputStream a = am.open(path);
			try { BitmapFactory.decodeStream(a, null, o); } finally { a.close(); }
			int sample = 1;
			while (o.outWidth / sample > MAX_TEX || o.outHeight / sample > MAX_TEX) sample *= 2;

			BitmapFactory.Options d = new BitmapFactory.Options();
			d.inScaled = false;
			d.inSampleSize = sample;
			InputStream b = am.open(path);
			Bitmap bmp;
			try { bmp = BitmapFactory.decodeStream(b, null, d); } finally { b.close(); }
			if (bmp == null) { return 0; }

			int w = bmp.getWidth(), h = bmp.getHeight();
			int pw = nearestPot(w), ph = nearestPot(h);
			if (pw != w || ph != h) {
				Bitmap s = Bitmap.createScaledBitmap(bmp, pw, ph, true);
				if (s != bmp) bmp.recycle();
				bmp = s;
			}

			int[] t = new int[1];
			GLES20.glGenTextures(1, t, 0);
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
			GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
			GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
			bmp.recycle();
			return t[0];
		} catch (Throwable th) {
			return 0;
		}
	}

	private static int nearestPot(int v) {
		if (v <= 1) return 1;
		int p = Integer.highestOneBit(v);
		if (p == v) return v;
		int lo = p, hi = p << 1;
		return (v - lo < hi - v) ? lo : Math.min(hi, MAX_TEX);
	}
}
