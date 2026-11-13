package com.avyansh.battleroyale;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * glTF 2.0 loader - handles ANY .glb and self contained or multi-file .gltf.
 *
 * Supported:
 *  - GLB container, .gltf with data: URIs or external .bin / image files in assets
 *  - skinned AND static models, any number of meshes / nodes / materials / primitives
 *  - node hierarchy (static meshes get their node transform baked in, scene graph respected)
 *  - POSITION / NORMAL / TEXCOORD_0 / COLOR_0 / JOINTS_0 / WEIGHTS_0, any component type
 *    (float, normalized byte / short, ...), byteStride, sparse accessors
 *  - missing normals (generated), missing indices (generated), triangle strips / fans
 *  - meshes with more than 65535 vertices (split, no uint index extension needed)
 *  - per-material base colour factor + base colour texture + alpha mask / blend
 *  - PNG / JPEG / WebP textures (embedded, data: URI or external), any size
 *    (resized to a power of two for GLES2)
 *  - animations: translation / rotation / scale with LINEAR, STEP and CUBICSPLINE
 *  - automatic bounds + facing detection so any model is scaled and placed correctly
 *
 * Not supported: Draco / meshopt compressed files (re-export without compression).
 */
public final class GltfLoader {
	private static final String TAG = "GltfLoader";
	private static final int GLB_MAGIC = 0x46546C67; // "glTF"
	private static final int GLB_JSON  = 0x4E4F534A; // "JSON"
	private static final int GLB_BIN   = 0x004E4942; // "BIN\0"
	private static final int MAX_TEX   = 2048;

	private GltfLoader() {}

	// ----------------------------------------------------------------
	// Entry points
	// ----------------------------------------------------------------
	/** Loads assets/models/&lt;name&gt; (falls back to assets/&lt;name&gt;). */
	public static GltfModel load(Context ctx, String name) {
		return load(ctx, name, true);
	}

	public static GltfModel load(Context ctx, String name, boolean gpu) {
		String[] tries = { "models/" + name, name };
		for (String path : tries) {
			try {
				return loadPath(ctx, path, gpu);
			} catch (java.io.FileNotFoundException e) {
				// try next location
			} catch (Throwable t) {
				Log.e(TAG, "GLB load failed: " + path + " - " + t, t);
				return null;
			}
		}
		Log.e(TAG, "GLB not found in assets: " + name);
		return null;
	}

	/** Loads a model by its full asset path, e.g. "models/player.glb". */
	public static GltfModel loadPath(Context ctx, String path, boolean gpu) throws Exception {
		AssetManager am = ctx.getAssets();
		byte[] bytes = readAll(am.open(path));
		return parse(ctx, bytes, dirOf(path), gpu);
	}

	private static String dirOf(String p) {
		int s = p.lastIndexOf('/');
		return s >= 0 ? p.substring(0, s) : "";
	}

	private static byte[] readAll(InputStream in) throws Exception {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
			byte[] buf = new byte[16384];
			int n;
			while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
			return out.toByteArray();
		} finally {
			try { in.close(); } catch (Throwable ignored) {}
		}
	}

	/** Reads a uri (data: or relative asset path) used by buffers / images. */
	private static byte[] readUri(Context ctx, String baseDir, String uri) throws Exception {
		if (uri.startsWith("data:")) {
			int comma = uri.indexOf(',');
			String b64 = uri.substring(comma + 1);
			return Base64.decode(b64, Base64.DEFAULT);
		}
		String clean = URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8");
		String path = baseDir.isEmpty() ? clean : baseDir + "/" + clean;
		return readAll(ctx.getAssets().open(path));
	}

	// ----------------------------------------------------------------
	// Container
	// ----------------------------------------------------------------
	private static GltfModel parse(Context ctx, byte[] bytes, String baseDir, boolean gpu) throws Exception {
		JSONObject json;
		ByteBuffer bin = null;

		boolean isGlb = bytes.length > 12
		&& (bytes[0] & 0xFF) == 0x67 && (bytes[1] & 0xFF) == 0x6C
		&& (bytes[2] & 0xFF) == 0x54 && (bytes[3] & 0xFF) == 0x46;

		if (isGlb) {
			ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
			int magic = bb.getInt();
			if (magic != GLB_MAGIC) throw new IllegalArgumentException("Not a GLB");
			int version = bb.getInt();
			int totalLen = Math.min(bb.getInt(), bytes.length);
			if (version != 2) throw new IllegalArgumentException("glTF 2 required, got " + version);
			json = null;
			while (bb.position() + 8 <= totalLen) {
				int chunkLen = bb.getInt();
				int chunkType = bb.getInt();
				int start = bb.position();
				if (chunkLen < 0 || start + chunkLen > bytes.length) break;
				if (chunkType == GLB_JSON) {
					byte[] jb = new byte[chunkLen];
					bb.get(jb);
					json = new JSONObject(new String(jb, "UTF-8"));
				} else if (chunkType == GLB_BIN && bin == null) {
					byte[] raw = new byte[chunkLen];
					bb.get(raw);
					bin = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
				}
				bb.position(start + chunkLen);
				int pad = (4 - (chunkLen & 3)) & 3;
				if (bb.position() + pad <= bytes.length) bb.position(bb.position() + pad);
			}
			if (json == null) throw new IllegalArgumentException("No JSON chunk");
		} else {
			json = new JSONObject(new String(bytes, "UTF-8"));   // plain .gltf
		}

		// compressed geometry cannot be decoded here
		JSONArray req = json.optJSONArray("extensionsRequired");
		if (req != null) {
			for (int i = 0; i < req.length(); i++) {
				String e = req.optString(i);
				if ("KHR_draco_mesh_compression".equals(e) || "EXT_meshopt_compression".equals(e)) {
					throw new IllegalStateException("Model uses " + e
					+ " compression - re-export the GLB without compression");
				}
			}
		}

		GltfModel model = new GltfModel();
		JSONObject asset = json.optJSONObject("asset");
		model.name = json.optString("name", asset != null ? asset.optString("generator", "gltf") : "gltf");

		// -------- buffers --------
		JSONArray buffersJson = json.optJSONArray("buffers");
		int bufCount = buffersJson == null ? 0 : buffersJson.length();
		ByteBuffer[] buffers = new ByteBuffer[Math.max(1, bufCount)];
		for (int i = 0; i < buffers.length; i++) {
			ByteBuffer data = null;
			if (buffersJson != null && i < bufCount) {
				JSONObject b = buffersJson.getJSONObject(i);
				if (b.has("uri")) {
					byte[] raw = readUri(ctx, baseDir, b.getString("uri"));
					data = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
				}
			}
			if (data == null) data = bin != null ? bin : ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN);
			buffers[i] = data;
		}

		// -------- bufferViews --------
		JSONArray bvJson = json.optJSONArray("bufferViews");
		int bvCount = bvJson == null ? 0 : bvJson.length();
		ByteBuffer[] bufferViews = new ByteBuffer[bvCount];
		int[] bvStride = new int[bvCount];
		for (int i = 0; i < bvCount; i++) {
			JSONObject v = bvJson.getJSONObject(i);
			int bufIdx = v.optInt("buffer", 0);
			int off = v.optInt("byteOffset", 0);
			int len = v.getInt("byteLength");
			ByteBuffer src = buffers[Math.min(bufIdx, buffers.length - 1)];
			ByteBuffer slice = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
			int limit = Math.min(off + len, slice.capacity());
			slice.limit(limit);
			slice.position(Math.min(off, limit));
			bufferViews[i] = slice.slice().order(ByteOrder.LITTLE_ENDIAN);
			bvStride[i] = v.optInt("byteStride", 0);
		}

		// -------- accessors --------
		JSONArray accJson = json.optJSONArray("accessors");
		int accCount = accJson == null ? 0 : accJson.length();
		Accessor[] accessors = new Accessor[accCount];
		for (int i = 0; i < accCount; i++) {
			JSONObject a = accJson.getJSONObject(i);
			Accessor acc = new Accessor();
			acc.count = a.getInt("count");
			acc.componentType = a.getInt("componentType");
			acc.type = a.getString("type");
			acc.byteOffset = a.optInt("byteOffset", 0);
			acc.normalized = a.optBoolean("normalized", false);
			int natural = componentCount(acc.type) * componentSize(acc.componentType);
			int bvi = a.optInt("bufferView", -1);
			if (bvi >= 0 && bvi < bvCount) {
				acc.byteStride = bvStride[bvi] != 0 ? bvStride[bvi] : natural;
				acc.data = bufferViews[bvi].duplicate().order(ByteOrder.LITTLE_ENDIAN);
			} else {
				acc.byteStride = natural;
			}
			JSONObject sp = a.optJSONObject("sparse");
			if (sp != null) {
				acc.sparseCount = sp.optInt("count", 0);
				JSONObject si = sp.optJSONObject("indices");
				JSONObject sv = sp.optJSONObject("values");
				if (si != null && sv != null) {
					int ib = si.optInt("bufferView", -1), vb = sv.optInt("bufferView", -1);
					if (ib >= 0 && ib < bvCount && vb >= 0 && vb < bvCount) {
						acc.spIdx = bufferViews[ib].duplicate().order(ByteOrder.LITTLE_ENDIAN);
						acc.spIdxOff = si.optInt("byteOffset", 0);
						acc.spIdxType = si.optInt("componentType", 5125);
						acc.spVal = bufferViews[vb].duplicate().order(ByteOrder.LITTLE_ENDIAN);
						acc.spValOff = sv.optInt("byteOffset", 0);
					} else {
						acc.sparseCount = 0;
					}
				}
			}
			accessors[i] = acc;
		}

		// -------- nodes --------
		JSONArray nodesJson = json.optJSONArray("nodes");
		int nodeCount = nodesJson == null ? 0 : nodesJson.length();
		model.nodes = new GltfModel.Node[nodeCount];
		for (int i = 0; i < nodeCount; i++) {
			JSONObject n = nodesJson.getJSONObject(i);
			GltfModel.Node node = new GltfModel.Node();
			node.index = i;
			node.name = n.optString("name", "node" + i);
			node.mesh = n.optInt("mesh", -1);
			node.skin = n.optInt("skin", -1);
			JSONArray ch = n.optJSONArray("children");
			if (ch != null) {
				node.children = new int[ch.length()];
				for (int c = 0; c < ch.length(); c++) node.children[c] = ch.getInt(c);
			}
			if (n.has("matrix")) {
				JSONArray m = n.getJSONArray("matrix");
				for (int k = 0; k < 16; k++) node.localMatrix[k] = (float) m.getDouble(k);
			} else {
				float[] t = readVec3(n.optJSONArray("translation"), 0f, 0f, 0f);
				float[] r = readVec4(n.optJSONArray("rotation"), 0f, 0f, 0f, 1f);
				float[] s = readVec3(n.optJSONArray("scale"), 1f, 1f, 1f);
				android.opengl.Matrix.setIdentityM(node.localMatrix, 0);
				android.opengl.Matrix.translateM(node.localMatrix, 0, t[0], t[1], t[2]);
				float[] q = new float[16];
				quatToMat(q, r[0], r[1], r[2], r[3]);
				float[] tr = new float[16];
				android.opengl.Matrix.multiplyMM(tr, 0, node.localMatrix, 0, q, 0);
				System.arraycopy(tr, 0, node.localMatrix, 0, 16);
				android.opengl.Matrix.scaleM(node.localMatrix, 0, s[0], s[1], s[2]);
			}
			model.nodes[i] = node;
		}
		computeWorlds(model);

		// which nodes belong to the active scene
		boolean[] inScene = new boolean[nodeCount];
		JSONArray scenes = json.optJSONArray("scenes");
		if (scenes != null && scenes.length() > 0) {
			int si = json.optInt("scene", 0);
			if (si < 0 || si >= scenes.length()) si = 0;
			JSONArray roots = scenes.getJSONObject(si).optJSONArray("nodes");
			if (roots != null) {
				for (int i = 0; i < roots.length(); i++) markScene(model, roots.getInt(i), inScene, 0);
			} else {
				Arrays.fill(inScene, true);
			}
		} else {
			Arrays.fill(inScene, true);
		}

		// -------- skins --------
		JSONArray skinsJson = json.optJSONArray("skins");
		int skinCount = skinsJson == null ? 0 : skinsJson.length();
		model.skins = new GltfModel.Skin[skinCount];
		for (int i = 0; i < skinCount; i++) {
			JSONObject s = skinsJson.getJSONObject(i);
			GltfModel.Skin skin = new GltfModel.Skin();
			JSONArray joints = s.getJSONArray("joints");
			skin.joints = new int[joints.length()];
			for (int k = 0; k < joints.length(); k++) skin.joints[k] = joints.getInt(k);
			int ibm = s.optInt("inverseBindMatrices", -1);
			if (ibm >= 0 && ibm < accCount) {
				skin.inverseBind = readMat4Array(accessors[ibm], skin.joints.length);
			} else {
				skin.inverseBind = new float[skin.joints.length * 16];
				for (int k = 0; k < skin.joints.length; k++) {
					android.opengl.Matrix.setIdentityM(skin.inverseBind, k * 16);
				}
			}
			model.skins[i] = skin;
		}

		// -------- materials / textures --------
		JSONArray materialsJson = json.optJSONArray("materials");
		int matCount = materialsJson == null ? 0 : materialsJson.length();
		Material[] mats = new Material[matCount];
		for (int i = 0; i < matCount; i++) mats[i] = parseMaterial(materialsJson.getJSONObject(i));
		TexLoader texLoader = new TexLoader(ctx, baseDir, json, bufferViews, gpu, model);

		// -------- meshes: one resolved primitive list --------
		JSONArray meshesJson = json.optJSONArray("meshes");
		ArrayList<GltfModel.Primitive> prims = new ArrayList<>();
		if (meshesJson != null) {
			for (int ni = 0; ni < nodeCount; ni++) {
				GltfModel.Node node = model.nodes[ni];
				if (node.mesh < 0 || node.mesh >= meshesJson.length() || !inScene[ni]) continue;
				JSONObject m = meshesJson.getJSONObject(node.mesh);
				JSONArray pa = m.optJSONArray("primitives");
				if (pa == null) continue;
				for (int p = 0; p < pa.length(); p++) {
					try {
						buildPrimitive(pa.getJSONObject(p), node, model, accessors, mats, texLoader, prims);
					} catch (Throwable t) {
						Log.w(TAG, "skipped a primitive in " + node.name + ": " + t);
					}
				}
			}
		}
		GltfModel.Mesh mesh = new GltfModel.Mesh();
		mesh.name = "merged";
		mesh.primitives = prims.toArray(new GltfModel.Primitive[0]);
		model.meshes = new GltfModel.Mesh[] { mesh };
		if (model.glTextures.size() > 0) model.textureId = model.glTextures.get(0);

		// -------- animations --------
		JSONArray animsJson = json.optJSONArray("animations");
		int animCount = animsJson == null ? 0 : animsJson.length();
		model.clips = new ArrayList<AnimationClip>();
		for (int i = 0; i < animCount; i++) {
			try {
				AnimationClip clip = parseClip(animsJson.getJSONObject(i), accessors, model, i);
				if (clip != null && clip.tracks.size() > 0) model.clips.add(clip);
			} catch (Throwable t) {
				Log.w(TAG, "skipped animation " + i + ": " + t);
			}
		}

		// -------- bind pose, bounds, facing --------
		model.bindPose = new float[model.nodes.length * 16];
		for (int i = 0; i < model.nodes.length; i++) {
			System.arraycopy(model.nodes[i].worldMatrix, 0, model.bindPose, i * 16, 16);
		}
		computeBoundsAndFacing(model);

		model.ready = !model.isEmpty() && prims.size() > 0;
		Log.i(TAG, "loaded '" + model.name + "' prims=" + prims.size() + " joints=" + model.jointCount()
		+ " clips=" + model.clips.size() + " h=" + model.height() + " facesPlusZ=" + model.facesPlusZ);
		return model;
	}

	// ----------------------------------------------------------------
	// Scene graph
	// ----------------------------------------------------------------
	private static void computeWorlds(GltfModel model) {
		int n = model.nodes.length;
		boolean[] isChild = new boolean[n];
		for (int i = 0; i < n; i++) {
			int[] ch = model.nodes[i].children;
			if (ch != null) for (int c : ch) if (c >= 0 && c < n) isChild[c] = true;
		}
		boolean[] done = new boolean[n];
		for (int i = 0; i < n; i++) {
			if (!isChild[i]) {
				System.arraycopy(model.nodes[i].localMatrix, 0, model.nodes[i].worldMatrix, 0, 16);
				done[i] = true;
				propagate(model, i, done, 0);
			}
		}
		for (int i = 0; i < n; i++) {
			if (!done[i]) {   // cyclic / orphan - treat as a root
				System.arraycopy(model.nodes[i].localMatrix, 0, model.nodes[i].worldMatrix, 0, 16);
				done[i] = true;
				propagate(model, i, done, 0);
			}
		}
	}

	private static void propagate(GltfModel model, int idx, boolean[] done, int depth) {
		if (depth > 512) return;
		int[] ch = model.nodes[idx].children;
		if (ch == null) return;
		float[] tmp = new float[16];
		for (int c : ch) {
			if (c < 0 || c >= model.nodes.length || done[c]) continue;
			android.opengl.Matrix.multiplyMM(tmp, 0, model.nodes[idx].worldMatrix, 0, model.nodes[c].localMatrix, 0);
			System.arraycopy(tmp, 0, model.nodes[c].worldMatrix, 0, 16);
			done[c] = true;
			propagate(model, c, done, depth + 1);
		}
	}

	private static void markScene(GltfModel model, int idx, boolean[] inScene, int depth) {
		if (idx < 0 || idx >= inScene.length || inScene[idx] || depth > 512) return;
		inScene[idx] = true;
		int[] ch = model.nodes[idx].children;
		if (ch != null) for (int c : ch) markScene(model, c, inScene, depth + 1);
	}

	// ----------------------------------------------------------------
	// Materials / textures
	// ----------------------------------------------------------------
	private static final class Material {
		float[] base = { 1f, 1f, 1f, 1f };
		int texIndex = -1;
		boolean mask;
		float cutoff = 0.5f;
		boolean doubleSided;
	}

	private static Material parseMaterial(JSONObject mat) {
		Material m = new Material();
		m.doubleSided = mat.optBoolean("doubleSided", false);
		String mode = mat.optString("alphaMode", "OPAQUE");
		if ("MASK".equals(mode)) { m.mask = true; m.cutoff = (float) mat.optDouble("alphaCutoff", 0.5); }
		else if ("BLEND".equals(mode)) { m.mask = true; m.cutoff = 0.35f; }

		JSONObject pbr = mat.optJSONObject("pbrMetallicRoughness");
		if (pbr != null) {
			JSONArray f = pbr.optJSONArray("baseColorFactor");
			if (f != null && f.length() >= 4) {
				for (int i = 0; i < 4; i++) m.base[i] = (float) f.optDouble(i, 1.0);
			}
			JSONObject bt = pbr.optJSONObject("baseColorTexture");
			if (bt != null) m.texIndex = bt.optInt("index", -1);
		} else {
			JSONObject ext = mat.optJSONObject("extensions");
			JSONObject sg = ext != null ? ext.optJSONObject("KHR_materials_pbrSpecularGlossiness") : null;
			if (sg != null) {
				JSONArray f = sg.optJSONArray("diffuseFactor");
				if (f != null && f.length() >= 4) {
					for (int i = 0; i < 4; i++) m.base[i] = (float) f.optDouble(i, 1.0);
				}
				JSONObject dt = sg.optJSONObject("diffuseTexture");
				if (dt != null) m.texIndex = dt.optInt("index", -1);
			}
		}
		return m;
	}

	/** Decodes (and optionally uploads) the textures a model uses; each texture only once. */
	private static final class TexLoader {
		final Context ctx; final String baseDir; final JSONObject json;
		final ByteBuffer[] bufferViews; final boolean gpu; final GltfModel model;
		final HashMap<Integer, Integer> glIds = new HashMap<>();
		final HashMap<Integer, Bitmap> bitmaps = new HashMap<>();

		TexLoader(Context ctx, String baseDir, JSONObject json, ByteBuffer[] bufferViews,
		boolean gpu, GltfModel model) {
			this.ctx = ctx; this.baseDir = baseDir; this.json = json;
			this.bufferViews = bufferViews; this.gpu = gpu; this.model = model;
		}

		void resolve(int texIndex, GltfModel.Primitive out) {
			if (texIndex < 0) return;
			try {
				if (gpu) {
					Integer id = glIds.get(texIndex);
					if (id == null) {
						Bitmap bmp = decode(texIndex);
						int gl = bmp == null ? 0 : upload(bmp);
						if (bmp != null) bmp.recycle();
						id = gl;
						glIds.put(texIndex, id);
						if (gl != 0) model.glTextures.add(gl);
					}
					out.texture = id;
				} else {
					Bitmap bmp = bitmaps.get(texIndex);
					if (bmp == null && !bitmaps.containsKey(texIndex)) {
						bmp = decode(texIndex);
						bitmaps.put(texIndex, bmp);
					}
					out.texBitmap = bmp;
				}
			} catch (Throwable t) {
				Log.w(TAG, "texture " + texIndex + " failed: " + t);
			}
		}

		private Bitmap decode(int texIndex) throws Exception {
			JSONArray textures = json.optJSONArray("textures");
			JSONArray images = json.optJSONArray("images");
			if (textures == null || images == null || texIndex >= textures.length()) return null;
			JSONObject tex = textures.getJSONObject(texIndex);
			int src = tex.optInt("source", -1);
			if (src < 0) {
				JSONObject ext = tex.optJSONObject("extensions");
				JSONObject webp = ext != null ? ext.optJSONObject("EXT_texture_webp") : null;
				if (webp != null) src = webp.optInt("source", -1);
			}
			if (src < 0 || src >= images.length()) return null;
			JSONObject img = images.getJSONObject(src);
			byte[] pixels = null;
			int bvIdx = img.optInt("bufferView", -1);
			if (bvIdx >= 0 && bvIdx < bufferViews.length) {
				ByteBuffer data = bufferViews[bvIdx].duplicate();
				pixels = new byte[data.remaining()];
				data.get(pixels);
			} else if (img.has("uri")) {
				pixels = readUri(ctx, baseDir, img.getString("uri"));
			}
			if (pixels == null) return null;

			BitmapFactory.Options o = new BitmapFactory.Options();
			o.inJustDecodeBounds = true;
			BitmapFactory.decodeByteArray(pixels, 0, pixels.length, o);
			int ss = 1;
			int limit = gpu ? MAX_TEX : 256;
			while (o.outWidth / ss > limit || o.outHeight / ss > limit) ss *= 2;
			BitmapFactory.Options o2 = new BitmapFactory.Options();
			o2.inSampleSize = ss;
			o2.inPremultiplied = false;
			Bitmap bmp = BitmapFactory.decodeByteArray(pixels, 0, pixels.length, o2);
			if (bmp == null) return null;
			if (!gpu) return bmp;
			int w = bmp.getWidth(), h = bmp.getHeight();
			int pw = floorPow2(w), ph = floorPow2(h);
			if (pw != w || ph != h) {      // GLES2: mipmaps + repeat need power-of-two textures
				Bitmap scaled = Bitmap.createScaledBitmap(bmp, pw, ph, true);
				if (scaled != bmp) bmp.recycle();
				bmp = scaled;
			}
			return bmp;
		}

		private static int floorPow2(int v) {
			int p = 1;
			while (p * 2 <= v) p *= 2;
			return p;
		}

		private int upload(Bitmap bmp) {
			int[] t = new int[1];
			GLES20.glGenTextures(1, t, 0);
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
			GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
			GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
			return t[0];
		}
	}

	// ----------------------------------------------------------------
	// Primitives
	// ----------------------------------------------------------------
	private static void buildPrimitive(JSONObject pr, GltfModel.Node node, GltfModel model,
	Accessor[] acc, Material[] mats, TexLoader texLoader, ArrayList<GltfModel.Primitive> out) throws Exception {

		int mode = pr.optInt("mode", 4);
		if (mode != 4 && mode != 5 && mode != 6) return;      // only triangles / strips / fans
		JSONObject attrs = pr.getJSONObject("attributes");
		int posA = attrs.optInt("POSITION", -1);
		if (posA < 0 || posA >= acc.length) return;

		float[] pos = readFloatArray(acc[posA]);
		int vcount = pos.length / 3;
		if (vcount < 3) return;

		float[] nrm = null, uv = null, col = null, jnt = null, wgt = null;
		int a = attrs.optInt("NORMAL", -1);
		if (a >= 0 && a < acc.length) nrm = readFloatArray(acc[a]);
		a = attrs.optInt("TEXCOORD_0", -1);
		if (a >= 0 && a < acc.length) uv = readFloatArray(acc[a]);
		a = attrs.optInt("COLOR_0", -1);
		if (a >= 0 && a < acc.length) {
			float[] c = readFloatArray(acc[a]);
			int cc = componentCount(acc[a].type);
			if (cc >= 3 && c.length >= vcount * cc) {
				col = new float[vcount * 4];
				for (int i = 0; i < vcount; i++) {
					col[i * 4] = c[i * cc]; col[i * 4 + 1] = c[i * cc + 1]; col[i * 4 + 2] = c[i * cc + 2];
					col[i * 4 + 3] = cc == 4 ? c[i * cc + 3] : 1f;
				}
			}
		}
		int ja = attrs.optInt("JOINTS_0", -1), wa = attrs.optInt("WEIGHTS_0", -1);
		if (ja >= 0 && wa >= 0 && ja < acc.length && wa < acc.length) {
			jnt = readFloatArray(acc[ja]);
			wgt = readFloatArray(acc[wa]);
		}

		if (nrm != null && nrm.length < vcount * 3) nrm = null;
		if (uv != null && uv.length < vcount * 2) uv = null;
		if (jnt != null && (jnt.length < vcount * 4 || wgt.length < vcount * 4)) { jnt = null; wgt = null; }

		// indices
		int[] idx;
		int ia = pr.optInt("indices", -1);
		if (ia >= 0 && ia < acc.length) {
			idx = readIndexArray(acc[ia]);
		} else {
			idx = new int[vcount];
			for (int i = 0; i < vcount; i++) idx[i] = i;
		}
		idx = toTriangles(idx, mode);
		idx = filterIndices(idx, vcount);
		if (idx.length < 3) return;

		if (nrm == null) nrm = smoothNormals(pos, idx);

		boolean skinned = jnt != null && node.skin >= 0 && model.skins != null
		&& node.skin < model.skins.length && model.skins[0].joints != null && model.skins[0].joints.length > 0;

		if (skinned) {
			int maxJ = Math.min(model.skins[0].joints.length, SkinShader.MAX_JOINTS) - 1;
			for (int i = 0; i < vcount; i++) {
				float sum = 0f;
				for (int k = 0; k < 4; k++) {
					int j = (int) jnt[i * 4 + k];
					if (j < 0 || j > maxJ) { j = 0; wgt[i * 4 + k] = 0f; }
					jnt[i * 4 + k] = j;
					sum += wgt[i * 4 + k];
				}
				if (sum < 1e-6f) { wgt[i * 4] = 1f; jnt[i * 4] = 0f; }
				else for (int k = 0; k < 4; k++) wgt[i * 4 + k] /= sum;
			}
		} else {
			jnt = null; wgt = null;
			// static mesh: bake the node's world transform
			float[] w = node.worldMatrix;
			if (!isIdentity(w)) {
				float det = w[0] * (w[5] * w[10] - w[9] * w[6])
				- w[4] * (w[1] * w[10] - w[9] * w[2])
				+ w[8] * (w[1] * w[6] - w[5] * w[2]);
				float sgn = det < 0f ? -1f : 1f;
				// cofactor matrix of the upper 3x3 (inverse transpose up to a scale)
				float c00 = w[5] * w[10] - w[9] * w[6], c01 = w[8] * w[6] - w[4] * w[10], c02 = w[4] * w[9] - w[8] * w[5];
				float c10 = w[9] * w[2] - w[1] * w[10], c11 = w[0] * w[10] - w[8] * w[2], c12 = w[8] * w[1] - w[0] * w[9];
				float c20 = w[1] * w[6] - w[5] * w[2], c21 = w[4] * w[2] - w[0] * w[6], c22 = w[0] * w[5] - w[4] * w[1];
				for (int i = 0; i < vcount; i++) {
					float x = pos[i * 3], y = pos[i * 3 + 1], z = pos[i * 3 + 2];
					pos[i * 3]     = w[0] * x + w[4] * y + w[8] * z + w[12];
					pos[i * 3 + 1] = w[1] * x + w[5] * y + w[9] * z + w[13];
					pos[i * 3 + 2] = w[2] * x + w[6] * y + w[10] * z + w[14];
					float nx = nrm[i * 3], ny = nrm[i * 3 + 1], nz = nrm[i * 3 + 2];
					float rx = (c00 * nx + c01 * ny + c02 * nz) * sgn;
					float ry = (c10 * nx + c11 * ny + c12 * nz) * sgn;
					float rz = (c20 * nx + c21 * ny + c22 * nz) * sgn;
					float l = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
					if (l > 1e-9f) { rx /= l; ry /= l; rz /= l; }
					nrm[i * 3] = rx; nrm[i * 3 + 1] = ry; nrm[i * 3 + 2] = rz;
				}
				if (det < 0f) {      // mirrored: restore counter-clockwise winding
					for (int t = 0; t + 2 < idx.length; t += 3) {
						int tmp = idx[t + 1]; idx[t + 1] = idx[t + 2]; idx[t + 2] = tmp;
					}
				}
			}
		}

		GltfModel.Primitive p = new GltfModel.Primitive();
		p.pos = pos; p.nrm = nrm; p.uv = uv; p.col = col;
		p.joints = jnt; p.weights = wgt; p.idx = idx; p.skinned = skinned;

		int mi = pr.optInt("material", -1);
		if (mi >= 0 && mi < mats.length) {
			Material m = mats[mi];
			p.baseColor = m.base.clone();
			p.alphaMask = m.mask;
			p.alphaCutoff = m.cutoff;
			p.doubleSided = m.doubleSided;
			if (uv != null) texLoader.resolve(m.texIndex, p);
		} else {
			p.baseColor = new float[] { 0.75f, 0.72f, 0.68f, 1f };   // no material: neutral grey-beige
		}
		split(p, out);
	}

	private static boolean isIdentity(float[] m) {
		for (int i = 0; i < 16; i++) {
			float want = (i % 5 == 0) ? 1f : 0f;
			if (Math.abs(m[i] - want) > 1e-6f) return false;
		}
		return true;
	}

	private static int[] toTriangles(int[] idx, int mode) {
		if (mode == 4) {
			int n = (idx.length / 3) * 3;
			return n == idx.length ? idx : Arrays.copyOf(idx, n);
		}
		int tris = Math.max(0, idx.length - 2);
		int[] out = new int[tris * 3];
		for (int i = 0; i < tris; i++) {
			if (mode == 5) {            // strip
				if ((i & 1) == 0) { out[i * 3] = idx[i]; out[i * 3 + 1] = idx[i + 1]; out[i * 3 + 2] = idx[i + 2]; }
				else              { out[i * 3] = idx[i + 1]; out[i * 3 + 1] = idx[i]; out[i * 3 + 2] = idx[i + 2]; }
			} else {                    // fan
				out[i * 3] = idx[0]; out[i * 3 + 1] = idx[i + 1]; out[i * 3 + 2] = idx[i + 2];
			}
		}
		return out;
	}

	/** Drops triangles that reference vertices which do not exist, and degenerate ones. */
	private static int[] filterIndices(int[] idx, int vcount) {
		boolean ok = true;
		for (int v : idx) if (v < 0 || v >= vcount) { ok = false; break; }
		if (ok) return idx;
		int[] out = new int[idx.length];
		int n = 0;
		for (int t = 0; t + 2 < idx.length; t += 3) {
			int a = idx[t], b = idx[t + 1], c = idx[t + 2];
			if (a < 0 || b < 0 || c < 0 || a >= vcount || b >= vcount || c >= vcount) continue;
			out[n++] = a; out[n++] = b; out[n++] = c;
		}
		return Arrays.copyOf(out, n);
	}

	private static float[] smoothNormals(float[] pos, int[] idx) {
		int vc = pos.length / 3;
		float[] n = new float[vc * 3];
		for (int t = 0; t + 2 < idx.length; t += 3) {
			int a = idx[t] * 3, b = idx[t + 1] * 3, c = idx[t + 2] * 3;
			float ux = pos[b] - pos[a], uy = pos[b + 1] - pos[a + 1], uz = pos[b + 2] - pos[a + 2];
			float vx = pos[c] - pos[a], vy = pos[c + 1] - pos[a + 1], vz = pos[c + 2] - pos[a + 2];
			float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			n[a] += nx; n[a + 1] += ny; n[a + 2] += nz;
			n[b] += nx; n[b + 1] += ny; n[b + 2] += nz;
			n[c] += nx; n[c + 1] += ny; n[c + 2] += nz;
		}
		for (int i = 0; i < vc; i++) {
			float l = (float) Math.sqrt(n[i * 3] * n[i * 3] + n[i * 3 + 1] * n[i * 3 + 1] + n[i * 3 + 2] * n[i * 3 + 2]);
			if (l > 1e-9f) { n[i * 3] /= l; n[i * 3 + 1] /= l; n[i * 3 + 2] /= l; }
			else { n[i * 3] = 0f; n[i * 3 + 1] = 1f; n[i * 3 + 2] = 0f; }
		}
		return n;
	}

	/** GLES2 only guarantees 16-bit indices, so very big meshes are cut into chunks. */
	private static void split(GltfModel.Primitive p, ArrayList<GltfModel.Primitive> out) {
		int vc = p.pos.length / 3;
		if (vc <= 65535) { out.add(p); return; }
		int[] remap = new int[vc];
		Arrays.fill(remap, -1);
		int[] order = new int[65535];
		int used = 0;
		int[] cidx = new int[p.idx.length];
		int ci = 0;
		for (int t = 0; t + 2 < p.idx.length; t += 3) {
			int[] tri = { p.idx[t], p.idx[t + 1], p.idx[t + 2] };
			int need = 0;
			for (int q = 0; q < 3; q++) if (remap[tri[q]] < 0) need++;
			if (used + need > 65535) {
				out.add(gather(p, order, used, cidx, ci));
				for (int i = 0; i < used; i++) remap[order[i]] = -1;
				used = 0; ci = 0;
			}
			for (int q = 0; q < 3; q++) {
				int v = tri[q];
				if (remap[v] < 0) { remap[v] = used; order[used++] = v; }
				cidx[ci++] = remap[v];
			}
		}
		if (ci > 0) out.add(gather(p, order, used, cidx, ci));
	}

	private static GltfModel.Primitive gather(GltfModel.Primitive p, int[] order, int used, int[] cidx, int ci) {
		GltfModel.Primitive o = new GltfModel.Primitive();
		o.pos = new float[used * 3];
		o.nrm = new float[used * 3];
		o.uv = p.uv != null ? new float[used * 2] : null;
		o.col = p.col != null ? new float[used * 4] : null;
		o.joints = p.joints != null ? new float[used * 4] : null;
		o.weights = p.weights != null ? new float[used * 4] : null;
		for (int i = 0; i < used; i++) {
			int s = order[i];
			System.arraycopy(p.pos, s * 3, o.pos, i * 3, 3);
			System.arraycopy(p.nrm, s * 3, o.nrm, i * 3, 3);
			if (o.uv != null) System.arraycopy(p.uv, s * 2, o.uv, i * 2, 2);
			if (o.col != null) System.arraycopy(p.col, s * 4, o.col, i * 4, 4);
			if (o.joints != null) {
				System.arraycopy(p.joints, s * 4, o.joints, i * 4, 4);
				System.arraycopy(p.weights, s * 4, o.weights, i * 4, 4);
			}
		}
		o.idx = Arrays.copyOf(cidx, ci);
		o.skinned = p.skinned;
		o.baseColor = p.baseColor;
		o.texture = p.texture;
		o.texBitmap = p.texBitmap;
		o.alphaMask = p.alphaMask;
		o.alphaCutoff = p.alphaCutoff;
		o.doubleSided = p.doubleSided;
		return o;
	}

	// ----------------------------------------------------------------
	// Bounds + facing (measured on the real bind-pose result)
	// ----------------------------------------------------------------
	private static void computeBoundsAndFacing(GltfModel model) {
		GltfModel.Primitive[] ps = model.meshes[0].primitives;
		if (ps.length == 0) return;

		float[] jm = null;
		if (model.jointCount() > 0) {
			GltfModel.Skin sk = model.skins[0];
			int jc = sk.joints.length;
			jm = new float[jc * 16];
			float[] tmp = new float[16];
			for (int j = 0; j < jc; j++) {
				int node = sk.joints[j];
				if (node < 0 || node >= model.nodes.length) { android.opengl.Matrix.setIdentityM(jm, j * 16); continue; }
				android.opengl.Matrix.multiplyMM(tmp, 0, model.nodes[node].worldMatrix, 0, sk.inverseBind, j * 16);
				System.arraycopy(tmp, 0, jm, j * 16, 16);
			}
		}

		float[][] fin = new float[ps.length][];
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		for (int pi = 0; pi < ps.length; pi++) {
			GltfModel.Primitive p = ps[pi];
			int vc = p.pos.length / 3;
			float[] f = new float[vc * 3];
			boolean skin = p.skinned && jm != null;
			for (int i = 0; i < vc; i++) {
				float x = p.pos[i * 3], y = p.pos[i * 3 + 1], z = p.pos[i * 3 + 2];
				float ox = x, oy = y, oz = z;
				if (skin) {
					ox = 0f; oy = 0f; oz = 0f;
					for (int k = 0; k < 4; k++) {
						float wt = p.weights[i * 4 + k];
						if (wt <= 0f) continue;
						int j = (int) p.joints[i * 4 + k] * 16;
						if (j + 15 >= jm.length) continue;
						ox += wt * (jm[j] * x + jm[j + 4] * y + jm[j + 8] * z + jm[j + 12]);
						oy += wt * (jm[j + 1] * x + jm[j + 5] * y + jm[j + 9] * z + jm[j + 13]);
						oz += wt * (jm[j + 2] * x + jm[j + 6] * y + jm[j + 10] * z + jm[j + 14]);
					}
				}
				f[i * 3] = ox; f[i * 3 + 1] = oy; f[i * 3 + 2] = oz;
				if (ox < minX) minX = ox; if (ox > maxX) maxX = ox;
				if (oy < minY) minY = oy; if (oy > maxY) maxY = oy;
				if (oz < minZ) minZ = oz; if (oz > maxZ) maxZ = oz;
			}
			fin[pi] = f;
		}
		if (minY > maxY || Float.isNaN(minY) || Float.isNaN(maxY)) return;
		model.hasBounds = true;
		model.minX = minX; model.minY = minY; model.minZ = minZ;
		model.maxX = maxX; model.maxY = maxY; model.maxZ = maxZ;

		// ---- facing: a person's feet point forward ----
		float h = maxY - minY;
		if (h < 1e-5f) return;
		float footTop = minY + h * 0.06f;
		float shinLo = minY + h * 0.12f, shinHi = minY + h * 0.35f;
		double footZ = 0, shinZ = 0;
		int footN = 0, shinN = 0;
		for (int pi = 0; pi < fin.length; pi++) {
			float[] f = fin[pi];
			for (int i = 0; i + 2 < f.length; i += 3) {
				float y = f[i + 1];
				if (y <= footTop) { footZ += f[i + 2]; footN++; }
				else if (y >= shinLo && y <= shinHi) { shinZ += f[i + 2]; shinN++; }
			}
		}
		if (footN >= 6 && shinN >= 6) {
			float delta = (float) (footZ / footN - shinZ / shinN) / h;
			if (Math.abs(delta) > 0.006f) {
				model.facesPlusZ = delta > 0f;
				model.facingDetected = true;
			}
		}
	}

	// ----------------------------------------------------------------
	// Animations
	// ----------------------------------------------------------------
	private static AnimationClip parseClip(JSONObject a, Accessor[] accessors, GltfModel model, int index) throws Exception {
		AnimationClip clip = new AnimationClip();
		clip.name = a.optString("name", "clip" + index);
		JSONArray chans = a.getJSONArray("channels");
		JSONArray samps = a.getJSONArray("samplers");
		clip.tracks = new ArrayList<>();
		float maxTime = 0f;
		for (int c = 0; c < chans.length(); c++) {
			JSONObject ch = chans.getJSONObject(c);
			JSONObject target = ch.getJSONObject("target");
			int node = target.optInt("node", -1);
			if (node < 0 || node >= model.nodes.length) continue;
			String path = target.getString("path");
			int comps;
			if ("rotation".equals(path)) comps = 4;
			else if ("translation".equals(path) || "scale".equals(path)) comps = 3;
			else continue;                                  // "weights" etc.
			JSONObject samp = samps.getJSONObject(ch.getInt("sampler"));
			int inAcc = samp.getInt("input"), outAcc = samp.getInt("output");
			if (inAcc >= accessors.length || outAcc >= accessors.length) continue;
			String interp = samp.optString("interpolation", "LINEAR");

			float[] times = readFloatArray(accessors[inAcc]);
			float[] values = readFloatArray(accessors[outAcc]);
			if (times.length == 0) continue;

			if ("CUBICSPLINE".equals(interp)) {
				// stored as (in-tangent, value, out-tangent) per key: keep the values only
				float[] v = new float[times.length * comps];
				for (int i = 0; i < times.length; i++) {
					int src = (i * 3 + 1) * comps;
					if (src + comps <= values.length) System.arraycopy(values, src, v, i * comps, comps);
				}
				values = v;
				interp = "LINEAR";
			}
			if (values.length < times.length * comps) continue;

			AnimationClip.Track track = new AnimationClip.Track();
			track.nodeIndex = node;
			track.path = path;
			track.times = times;
			track.values = values;
			track.components = comps;
			track.interpolation = interp;
			clip.tracks.add(track);
			if (times[times.length - 1] > maxTime) maxTime = times[times.length - 1];
		}
		clip.duration = maxTime;
		return clip;
	}

	// ----------------------------------------------------------------
	// Accessor readers
	// ----------------------------------------------------------------
	private static int componentCount(String type) {
		switch (type) {
			case "SCALAR": return 1;
			case "VEC2":   return 2;
			case "VEC3":   return 3;
			case "VEC4":   return 4;
			case "MAT2":   return 4;
			case "MAT3":   return 9;
			case "MAT4":   return 16;
			default: return 1;
		}
	}

	private static int componentSize(int type) {
		switch (type) {
			case 5120: case 5121: return 1;
			case 5122: case 5123: return 2;
			default:   return 4;
		}
	}

	private static float[] readFloatArray(Accessor a) {
		int cc = componentCount(a.type);
		float[] out = new float[a.count * cc];
		if (a.data != null) {
			ByteBuffer buf = a.data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < a.count; i++) {
				int p = a.byteOffset + i * a.byteStride;
				if (p + cc * componentSize(a.componentType) > buf.limit()) break;
				buf.position(p);
				for (int k = 0; k < cc; k++) out[i * cc + k] = readComponent(buf, a.componentType, a.normalized);
			}
		}
		if (a.sparseCount > 0 && a.spIdx != null && a.spVal != null) {
			int isz = componentSize(a.spIdxType), vsz = cc * componentSize(a.componentType);
			for (int s = 0; s < a.sparseCount; s++) {
				if (a.spIdxOff + (s + 1) * isz > a.spIdx.limit() || a.spValOff + (s + 1) * vsz > a.spVal.limit()) break;
				ByteBuffer ib = a.spIdx.duplicate().order(ByteOrder.LITTLE_ENDIAN);
				ib.position(a.spIdxOff + s * isz);
				int target = readIndex(ib, a.spIdxType);
				if (target < 0 || target >= a.count) continue;
				ByteBuffer vb = a.spVal.duplicate().order(ByteOrder.LITTLE_ENDIAN);
				vb.position(a.spValOff + s * vsz);
				for (int k = 0; k < cc; k++) out[target * cc + k] = readComponent(vb, a.componentType, a.normalized);
			}
		}
		return out;
	}

	private static int[] readIndexArray(Accessor a) {
		int[] out = new int[a.count];
		if (a.data == null) return out;
		ByteBuffer buf = a.data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < a.count; i++) {
			int p = a.byteOffset + i * a.byteStride;
			if (p + componentSize(a.componentType) > buf.limit()) break;
			buf.position(p);
			out[i] = readIndex(buf, a.componentType);
		}
		return out;
	}

	private static int readIndex(ByteBuffer b, int type) {
		switch (type) {
			case 5121: return b.get() & 0xFF;
			case 5123: return b.getShort() & 0xFFFF;
			default:   return b.getInt();
		}
	}

	private static float[] readMat4Array(Accessor a, int count) {
		float[] out = new float[count * 16];
		for (int i = 0; i < count; i++) android.opengl.Matrix.setIdentityM(out, i * 16);
		if (a.data == null) return out;
		ByteBuffer buf = a.data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int stride = a.byteStride > 0 ? a.byteStride : 64;
		for (int i = 0; i < count; i++) {
			int p = a.byteOffset + i * stride;
			if (p + 64 > buf.limit()) break;
			buf.position(p);
			for (int k = 0; k < 16; k++) out[i * 16 + k] = buf.getFloat();
		}
		return out;
	}

	private static float readComponent(ByteBuffer buf, int type, boolean norm) {
		switch (type) {
			case 5126: return buf.getFloat();
			case 5120: { int v = buf.get(); return norm ? Math.max(v / 127f, -1f) : v; }
			case 5121: { int v = buf.get() & 0xFF; return norm ? v / 255f : v; }
			case 5122: { int v = buf.getShort(); return norm ? Math.max(v / 32767f, -1f) : v; }
			case 5123: { int v = buf.getShort() & 0xFFFF; return norm ? v / 65535f : v; }
			case 5125: { long v = buf.getInt() & 0xFFFFFFFFL; return v; }
			default: return 0f;
		}
	}


	// ----------------------------------------------------------------
	// Separate animation files (Mixamo: character without animations + one GLB per animation)
	// ----------------------------------------------------------------
	private static final String[] ANIM_DIRS = { "models/animations", "models/anim", "models/emotes", "models/clips",
		"animations", "anim", "emotes" };

	private static String nodeKey(String name) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; name != null && i < name.length(); i++) {
			char c = Character.toLowerCase(name.charAt(i));
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
		}
		String k = sb.toString();
		if (k.startsWith("mixamorig")) k = k.substring(9);
		return k;
	}

	private static boolean genericClipName(String n) {
		String k = nodeKey(n);
		return k.isEmpty() || k.contains("mixamo") || k.startsWith("take") || k.contains("armature")
		|| k.startsWith("default") || k.startsWith("animation") || k.equals("action") || k.startsWith("clip");
	}

	/**
	 * Loads every .glb/.gltf in assets/models/animations (also anim, emotes, clips) and in assets/models
	 * when the file name starts with "anim" or "emote", and adds their clips to 'target' retargeted
	 * by bone name. The clip is named after the FILE (rifle_run.glb -> "rifle_run") when the file has
	 * one clip or an unnamed clip (Mixamo "mixamo.com").
	 *
	 * @return number of clips added
	 */
	public static int addExternalAnimations(Context ctx, GltfModel target) {
		if (target == null || target.nodes == null) return 0;
		AssetManager am = ctx.getAssets();
		ArrayList<String> files = new ArrayList<>();
		for (String d : ANIM_DIRS) {
			try {
				String[] list = am.list(d);
				if (list == null) continue;
				Arrays.sort(list);
				for (String f : list) {
					String l = f.toLowerCase(java.util.Locale.ROOT);
					if (l.endsWith(".glb") || l.endsWith(".gltf")) files.add(d + "/" + f);
				}
			} catch (Throwable ignored) {}
		}
		try {
			String[] list = am.list("models");
			if (list != null) {
				Arrays.sort(list);
				for (String f : list) {
					String l = f.toLowerCase(java.util.Locale.ROOT);
					if ((l.endsWith(".glb") || l.endsWith(".gltf")) && (l.startsWith("anim") || l.startsWith("emote"))) {
						files.add("models/" + f);
					}
				}
			}
		} catch (Throwable ignored) {}
		if (files.isEmpty()) return 0;

		// target bone lookup
		HashMap<String, Integer> byKey = new HashMap<>();
		for (int i = 0; i < target.nodes.length; i++) byKey.put(nodeKey(target.nodes[i].name), i);

		int added = 0;
		java.util.HashSet<AnimationClip> nativeClips = new java.util.HashSet<AnimationClip>(target.clips);
		for (String path : files) {
			try {
				GltfModel src = loadPath(ctx, path, false);
				if (src == null || src.clips == null || src.clips.isEmpty()) continue;
				String base = path.substring(path.lastIndexOf('/') + 1);
				int dot = base.lastIndexOf('.');
				if (dot > 0) base = base.substring(0, dot);
				for (AnimationClip sc : src.clips) {
					String cname = (src.clips.size() == 1 || genericClipName(sc.name)) ? base : sc.name;
					boolean native_ = false;
					for (AnimationClip ex : target.clips) if (ex.name.equalsIgnoreCase(cname) && nativeClips.contains(ex)) native_ = true;
					if (native_) continue;                       // the character's own built-in clip wins
					AnimationClip nc = Retargeter.retarget(src, sc, target, cname);
					if (nc == null || nc.tracks.size() < 3) {
						Log.w(TAG, "animation '" + cname + "' (" + path + ") could not be retargeted - is it a humanoid rig?");
						continue;
					}
					for (int i = target.clips.size() - 1; i >= 0; i--) {     // same name: replace
						if (target.clips.get(i).name.equalsIgnoreCase(nc.name)) target.clips.remove(i);
					}
					target.clips.add(nc);
					added++;
				}
			} catch (Throwable t) {
				Log.e(TAG, "animation file failed (" + path + "): " + t, t);
			}
		}
		Log.i(TAG, "external animations added to '" + target.name + "': " + added);
		return added;
	}


	/**
	 * Mixamo clips that are not "In Place" move the hips forward, so the character would run away from
	 * (or snap back to) the game's own position. Keeps the hips' height bob, removes the X/Z travel.
	 */
	public static void stripRootMotion(GltfModel m) {
		if (m == null || m.nodes == null || m.clips == null) return;
		int hips = -1;
		for (int i = 0; i < m.nodes.length; i++) {
			String k = nodeKey(m.nodes[i].name);
			if (k.equals("hips") || k.equals("pelvis")) { hips = i; break; }
		}
		if (hips < 0 && m.skins != null && m.skins.length > 0 && m.skins[0].joints.length > 0) hips = m.skins[0].joints[0];
		if (hips < 0) return;
		for (AnimationClip c : m.clips) {
			for (AnimationClip.Track t : c.tracks) {
				if (t.nodeIndex != hips || !"translation".equals(t.path) || t.values == null || t.values.length < 3) continue;
				float[] v = t.values.clone();
				float x0 = v[0], z0 = v[2];
				for (int k = 0; k + 2 < v.length; k += 3) { v[k] = x0; v[k + 2] = z0; }
				t.values = v;
			}
		}
	}

	// ----------------------------------------------------------------
	// Utils
	// ----------------------------------------------------------------
	private static float[] readVec3(JSONArray a, float dx, float dy, float dz) {
		if (a == null) return new float[] { dx, dy, dz };
		try { return new float[] { (float) a.getDouble(0), (float) a.getDouble(1), (float) a.getDouble(2) }; }
		catch (Exception e) { return new float[] { dx, dy, dz }; }
	}

	private static float[] readVec4(JSONArray a, float dx, float dy, float dz, float dw) {
		if (a == null) return new float[] { dx, dy, dz, dw };
		try { return new float[] {
				(float) a.getDouble(0), (float) a.getDouble(1),
				(float) a.getDouble(2), (float) a.getDouble(3) }; }
		catch (Exception e) { return new float[] { dx, dy, dz, dw }; }
	}

	private static void quatToMat(float[] out, float x, float y, float z, float w) {
		float xx = x * x, yy = y * y, zz = z * z;
		float xy = x * y, xz = x * z, yz = y * z;
		float wx = w * x, wy = w * y, wz = w * z;
		out[0]  = 1 - 2 * (yy + zz); out[1]  = 2 * (xy + wz);     out[2]  = 2 * (xz - wy);     out[3]  = 0;
		out[4]  = 2 * (xy - wz);     out[5]  = 1 - 2 * (xx + zz); out[6]  = 2 * (yz + wx);     out[7]  = 0;
		out[8]  = 2 * (xz + wy);     out[9]  = 2 * (yz - wx);     out[10] = 1 - 2 * (xx + yy); out[11] = 0;
		out[12] = 0;                 out[13] = 0;                 out[14] = 0;                 out[15] = 1;
	}

	private static class Accessor {
		int count, componentType, byteOffset, byteStride;
		String type;
		boolean normalized;
		ByteBuffer data;
		int sparseCount, spIdxOff, spIdxType, spValOff;
		ByteBuffer spIdx, spVal;
	}
}
