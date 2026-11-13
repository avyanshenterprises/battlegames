package com.avyansh.battleroyale;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;

public final class ModelCache {
	private static final String TAG = "ModelCache";

	// GLB (preferred)
	public static GltfModel PLAYER_GLB;
	public static GltfModel BOT_GLB;
	public static GltfModel AIRPLANE_GLB;
	public static GltfModel PARACHUTE_GLB;

	// OBJ (fallback)
	public static ObjLoader.Model PLAYER;
	public static ObjLoader.Model BOT;
	public static ObjLoader.Model PARACHUTE;
	public static ObjLoader.Model AIRPLANE;

	public static boolean ready = false;
	/** metres from the airplane centre up to its roof */
	public static float PLANE_TOP = 3f;

	private static final String[] DIRS = { "models", "", "model", "models/glb" };

	public static void init(Context ctx) {
		// init() runs once per NEW GL context (onSurfaceCreated). Every handle from the previous context is already
		// gone, so only the Java references may be dropped - deleting them would hit unrelated objects of the new context.
		dropAll();

		HashSet<String> used = new HashSet<String>();
		PLAYER_GLB    = safeLoadGlb(ctx, findGlb(ctx, used, "player", new String[] { "character", "hero", "soldier", "survivor", "human" }));
		BOT_GLB       = safeLoadGlb(ctx, findGlb(ctx, used, "bot", new String[] { "enemy", "zombie", "npc", "opponent" }));
		AIRPLANE_GLB  = safeLoadGlb(ctx, findGlb(ctx, used, "airplane", new String[] { "plane", "aircraft", "jet", "cargo" })); 
        PARACHUTE_GLB = safeLoadGlb(ctx, findGlb(ctx, used, "parachute", new String[] { "chute", "paraglider" }));

		// separate animation files (models/animations/*.glb) are retargeted onto the characters
		if (PLAYER_GLB != null) GltfLoader.addExternalAnimations(ctx, PLAYER_GLB);
		if (BOT_GLB != null && BOT_GLB != PLAYER_GLB) GltfLoader.addExternalAnimations(ctx, BOT_GLB);
		if (GameSettings.STRIP_ROOT_MOTION) {
			GltfLoader.stripRootMotion(PLAYER_GLB);
			if (BOT_GLB != PLAYER_GLB) GltfLoader.stripRootMotion(BOT_GLB);
		}

		// height of the airplane's roof above its centre (the player is shown standing there)
		PLANE_TOP = 3f;
		if (AIRPLANE_GLB != null && AIRPLANE_GLB.hasBounds) {
			float ext = Math.max(AIRPLANE_GLB.maxX - AIRPLANE_GLB.minX, AIRPLANE_GLB.maxZ - AIRPLANE_GLB.minZ);
			float fit = (ext > 1e-4f && GameSettings.AIRPLANE_TARGET_SIZE > 0f) ? GameSettings.AIRPLANE_TARGET_SIZE / ext : 1f;
			PLANE_TOP = Math.max(1f, (AIRPLANE_GLB.maxY - (AIRPLANE_GLB.minY + AIRPLANE_GLB.maxY) * 0.5f) * fit);
		}

		// Upload to GPU (must be on GL thread - this is called from onSurfaceCreated)
		if (PLAYER_GLB != null)    GltfUploader.upload(PLAYER_GLB);
		if (BOT_GLB != null)       GltfUploader.upload(BOT_GLB);
		if (AIRPLANE_GLB != null)  GltfUploader.upload(AIRPLANE_GLB);
		if (PARACHUTE_GLB != null) GltfUploader.upload(PARACHUTE_GLB);

		WeaponMeshes.init();

		// OBJ fallback
		PLAYER    = safeLoadObj(ctx, "player.obj",    "player.mtl",    true);
		BOT       = safeLoadObj(ctx, "bot.obj",       "bot.mtl",       true);
		PARACHUTE = safeLoadObj(ctx, "parachute.obj", "parachute.mtl", false);
		AIRPLANE  = safeLoadObj(ctx, "airplane.obj",  "airplane.mtl",  false);

		// no bot model at all: bots wear the player model so they are never invisible
		if ((BOT_GLB == null || !BOT_GLB.uploaded) && (BOT == null || BOT.isEmpty())
		&& PLAYER_GLB != null && PLAYER_GLB.uploaded) {
			BOT_GLB = PLAYER_GLB;
		}

		Log.i(TAG, "=== MODEL LOAD RESULTS ===");
		Log.i(TAG, "PLAYER GLB:    " + okGlb(PLAYER_GLB) + " | OBJ: " + okObj(PLAYER));
		Log.i(TAG, "BOT GLB:       " + okGlb(BOT_GLB) + " | OBJ: " + okObj(BOT));
		Log.i(TAG, "AIRPLANE GLB:  " + okGlb(AIRPLANE_GLB) + " | OBJ: " + okObj(AIRPLANE));
		Log.i(TAG, "PARACHUTE GLB: " + okGlb(PARACHUTE_GLB) + " | OBJ: " + okObj(PARACHUTE));
		TreeModel.load(ctx);
		Log.i(TAG, "TREE model: " + (TreeModel.ready ? TreeModel.triCount() + " tris" : "NOT LOADED (procedural trees)"));
		ready = true;
	}

	/**
	 * Finds a GLB/GLTF asset: exact "&lt;main&gt;.glb" first, then any file whose name contains
	 * the main word or one of the alternatives. Returns the full asset path or null.
	 */
	private static String findGlb(Context ctx, HashSet<String> used, String main, String[] alts) {
		AssetManager am = ctx.getAssets();
		for (String d : DIRS) {
			String[] list;
			try { list = am.list(d); } catch (IOException e) { continue; }
			if (list == null) continue;
			for (String have : list) {
				String l = have.toLowerCase(Locale.ROOT);
				if (l.equals(main + ".glb") || l.equals(main + ".gltf")) {
					String path = d.isEmpty() ? have : d + "/" + have;
					if (used.add(path)) return path;
				}
			}
		}
		for (String d : DIRS) {
			String[] list;
			try { list = am.list(d); } catch (IOException e) { continue; }
			if (list == null) continue;
			for (String have : list) {
				String l = have.toLowerCase(Locale.ROOT);
				if (!(l.endsWith(".glb") || l.endsWith(".gltf"))) continue;
				String path = d.isEmpty() ? have : d + "/" + have;
				if (used.contains(path)) continue;
				boolean hit = l.contains(main);
				for (int i = 0; i < alts.length && !hit; i++) hit = l.contains(alts[i]);
				if (hit && !l.contains("tree") && !l.contains("street")) {
					used.add(path);
					return path;
				}
			}
		}
		return null;
	}

	private static GltfModel safeLoadGlb(Context ctx, String path) {
		if (path == null) return null;
		try {
			GltfModel m = GltfLoader.loadPath(ctx, path, true);
			return (m != null && m.ready) ? m : null;
		} catch (Throwable t) {
			Log.e(TAG, "GLB load failed (" + path + "): " + t, t);
			return null;
		}
	}

	private static ObjLoader.Model safeLoadObj(Context ctx, String obj, String mtl, boolean ground) {
		try { return ObjLoader.load(ctx, obj, mtl, ground); }
		catch (Throwable t) { Log.e(TAG, "OBJ load failed (" + obj + "): " + t, t); return null; }
	}

	private static String okGlb(GltfModel m) {
		if (m == null) return "NOT LOADED";
		int prims = (m.meshes != null && m.meshes.length > 0) ? m.meshes[0].primitives.length : 0;
		return prims + " prim / "
		+ (m.clips != null ? m.clips.size() : 0) + " clip / "
		+ m.jointCount() + " joints";
	}

	private static String okObj(ObjLoader.Model m) {
		return m == null || m.isEmpty() ? "NOT LOADED" : (m.meshes.size() + " mesh");
	}

	/** Forgets all models without issuing GL calls (use when the GL context was destroyed). */
	public static void dropAll() {
		WeaponMeshes.drop();
		PLAYER_GLB = null; BOT_GLB = null; AIRPLANE_GLB = null; PARACHUTE_GLB = null;
		PLAYER = null; BOT = null; PARACHUTE = null; AIRPLANE = null;
		ready = false;
	}

	/** Releases all models on the CURRENT GL context. */
	public static void invalidate() {
		WeaponMeshes.release();
		if (PLAYER_GLB != null)    { PLAYER_GLB.release();    PLAYER_GLB = null; }
		if (BOT_GLB != null)       { BOT_GLB.release();       BOT_GLB = null; }
		if (AIRPLANE_GLB != null)  { AIRPLANE_GLB.release();  AIRPLANE_GLB = null; }
		if (PARACHUTE_GLB != null) { PARACHUTE_GLB.release(); PARACHUTE_GLB = null; }
		if (PLAYER != null)        { PLAYER.release();        PLAYER = null; }
		if (BOT != null)           { BOT.release();           BOT = null; }
		if (PARACHUTE != null)     { PARACHUTE.release();     PARACHUTE = null; }
		if (AIRPLANE != null)      { AIRPLANE.release();      AIRPLANE = null; }
		ready = false;
	}
}
