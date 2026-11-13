package com.avyansh.battleroyale;

public class CollisionSystem {
	public static boolean lastHitHead = false;
	
	private static final float[] BODY_Y = {1.15f, 0.72f, 0.30f};
	private static final float[] BODY_R = {0.40f, 0.38f, 0.36f};
	
	public static float rayTerrain(TerrainGenerator tg, float ox, float oy, float oz,
	float dx, float dy, float dz, float maxDist, float baseStep) {
		float best = -1f;
		float t = baseStep;
		while (t <= maxDist) {
			float x = ox + dx * t, y = oy + dy * t, z = oz + dz * t;
			if (!TerrainGenerator.inWorld(x, z, 0f)) break;      // world is centred on (0,0)
			float h = Math.max(tg.height(x, z), TerrainGenerator.WATER_LEVEL);
			if (y < h) { best = t; break; }
			t += baseStep + t * 0.01f;
		}
		// Walls and roofs of buildings also stop bullets / line of sight.
		float lim = best >= 0f ? best : maxDist;
		for (int i = 0; i < tg.buildings.size(); i++) {
			Building b = tg.buildings.get(i);
			float cx = b.x - ox, cz = b.z - oz;
			float reach = Math.max(b.w, b.d) + 2f;
			float along = cx * dx + cz * dz;
			if (along < -reach || along > lim + reach) continue;
			float px = cx - dx * along, pz = cz - dz * along;
			if (px * px + pz * pz > reach * reach) continue;
			float bt = b.rayHit(ox, oy, oz, dx, dy, dz, lim);
			if (bt >= 0f && (best < 0f || bt < best)) { best = bt; lim = bt; }
		}
		return best;
	}

	public static float raySphere(float ox, float oy, float oz, float dx, float dy, float dz,
	float cx, float cy, float cz, float r) {
		float lx = cx - ox, ly = cy - oy, lz = cz - oz;
		float tca = lx * dx + ly * dy + lz * dz;
		if (tca < 0f) return -1f;
		float d2 = lx * lx + ly * ly + lz * lz - tca * tca;
		float r2 = r * r;
		if (d2 > r2) return -1f;
		float t = tca - (float) Math.sqrt(r2 - d2);
		return t < 0f ? 0f : t;
	}
	
	public static float rayCharacter(float ox, float oy, float oz, float dx, float dy, float dz,
	float fx, float fy, float fz) {
		float best = -1f;
		boolean head = false;
		float t = raySphere(ox, oy, oz, dx, dy, dz, fx, fy + 1.62f, fz, 0.28f);
		if (t >= 0f) { best = t; head = true; }
		for (int i = 0; i < 3; i++) {
			t = raySphere(ox, oy, oz, dx, dy, dz, fx, fy + BODY_Y[i], fz, BODY_R[i]);
			if (t >= 0f && (best < 0f || t < best)) { best = t; head = false; }
		}
		lastHitHead = head;
		return best;
	}
}
