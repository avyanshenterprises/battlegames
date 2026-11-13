package com.avyansh.battleroyale;

import java.util.ArrayList;

/**
* Decides where trees, rocks, buildings and grass go. Everything is derived from grid-cell
* hashes, so any chunk can ask "is there a tree in that cell?" and always get the same answer.
*
* NEW: giant realistic trees (14–22 m), boulder-sized rocks, upgraded houses.
*/
public class PropPlacer {
	private static final float TREE_CELL  = 6f;
	private static final float ROCK_CELL  = 9f;
	private static final float GRASS_CELL = 2.5f;
	private static final float BUILD_CELL = 48f;
	private static final float WORLD      = TerrainGenerator.WORLD_SIZE;
	
	private final TerrainGenerator tg;
	private final ArrayList<float[]> buildings = new ArrayList<>();
	
	public PropPlacer(TerrainGenerator tg) {
		this.tg = tg;
		int cells = (int) Math.ceil(WORLD / BUILD_CELL);
		for (int bi = 0; bi < cells; bi++) {
			for (int bj = 0; bj < cells; bj++) {
				float[] b = computeBuilding(bi, bj);
				if (b != null) buildings.add(b);
			}
		}
	}
	
	public int buildingCount() { return buildings.size(); }
	
	private float[] computeBuilding(int bi, int bj) {
		float x = (bi + 0.2f + 0.6f * tg.rand(bi, bj, 21)) * BUILD_CELL;
		float z = (bj + 0.2f + 0.6f * tg.rand(bi, bj, 22)) * BUILD_CELL;
		if (tg.rand(bi, bj, 23) > 0.7f) return null;
		float half = 3.5f + 2.0f * tg.rand(bi, bj, 24);
		float min = 1e9f, max = -1e9f;
		for (int i = 0; i < 9; i++) {
			float sx = x + ((i % 3) - 1) * half;
			float sz = z + ((i / 3) - 1) * half;
			float h = tg.height(sx, sz);
			if (h < min) min = h;
			if (h > max) max = h;
		}
		if (max - min > 2.5f || min < 3.2f || max > 18f) return null;
		float wall = 3f + 1.2f * tg.rand(bi, bj, 25);
		return new float[]{x, min - 2f, z, half, max + wall};
	}
	
	private boolean nearBuilding(float x, float z, float margin) {
		for (int i = 0; i < buildings.size(); i++) {
			float[] b = buildings.get(i);
			float lim = b[3] + margin;
			if (Math.abs(x - b[0]) < lim && Math.abs(z - b[2]) < lim) return true;
		}
		return false;
	}
	
	/** @return {x, groundY, z, scale} or null */
	private float[] tree(int ci, int cj) {
		float x = (ci + 0.15f + 0.7f * tg.rand(ci, cj, 11)) * TREE_CELL;
		float z = (cj + 0.15f + 0.7f * tg.rand(ci, cj, 12)) * TREE_CELL;
		if (x < 6f || z < 6f || x > WORLD - 6f || z > WORLD - 6f) return null;
		float dens = tg.forestDensity(x, z);
		// Sharpened threshold: clusters are denser, gaps are emptier
		float chance = 0.06f + 0.72f * TerrainGenerator.smoothstep(0.42f, 0.58f, dens);
		if (tg.rand(ci, cj, 13) > chance) return null;
		float h = tg.height(x, z);
		if (h < 2.6f || h > 24f) return null;
		if (tg.normalY(x, z) < 0.78f) return null;
		if (nearBuilding(x, z, 5f)) return null;
		// scale 0.8–1.5 → final height 14–22 m via addTree()'s mapping
		float scale = 0.85f + 0.65f * tg.rand(ci, cj, 14);
		return new float[]{x, h, z, scale};
	}
	
	/** @return {x, groundY, z, size} or null */
	private float[] rock(int ci, int cj) {
		float x = (ci + 0.2f + 0.6f * tg.rand(ci, cj, 31)) * ROCK_CELL;
		float z = (cj + 0.2f + 0.6f * tg.rand(ci, cj, 32)) * ROCK_CELL;
		if (x < 3f || z < 3f || x > WORLD - 3f || z > WORLD - 3f) return null;
		float h = tg.height(x, z);
		if (h < 2.4f || h > 48f) return null;
		float chance = h > 22f ? 0.55f : 0.22f;
		if (tg.rand(ci, cj, 33) > chance) return null;
		if (nearBuilding(x, z, 4f)) return null;
		// 2x bigger rocks
		float size = 1.4f + 3.0f * tg.rand(ci, cj, 34);
		if (h > 22f) size *= 1.7f;
		return new float[]{x, h, z, size};
	}
	
	/** @return {x, groundY, z, random} or null */
	private float[] grass(int ci, int cj) {
		float x = (ci + tg.rand(ci, cj, 41)) * GRASS_CELL;
		float z = (cj + tg.rand(ci, cj, 44)) * GRASS_CELL;
		if (x < 2f || z < 2f || x > WORLD - 2f || z > WORLD - 2f) return null;
		if (tg.rand(ci, cj, 42) > 0.55f) return null;
		float h = tg.height(x, z);
		if (h < 2.9f || h > 21f) return null;
		if (tg.normalY(x, z) < 0.86f) return null;
		if (nearBuilding(x, z, 1.5f)) return null;
		return new float[]{x, h, z, tg.rand(ci, cj, 43)};
	}
	
	// ---------- mesh generation ----------
	
	public void buildProps(int cx, int cz, MeshBuilder mb) {
		float x0 = cx * TerrainGenerator.CHUNK_SIZE;
		float z0 = cz * TerrainGenerator.CHUNK_SIZE;
		float x1 = x0 + TerrainGenerator.CHUNK_SIZE;
		float z1 = z0 + TerrainGenerator.CHUNK_SIZE;
		
		// ---- trees ----
		int i0 = (int) Math.floor(x0 / TREE_CELL), i1 = (int) Math.floor(x1 / TREE_CELL);
		int j0 = (int) Math.floor(z0 / TREE_CELL), j1 = (int) Math.floor(z1 / TREE_CELL);
		for (int ci = i0; ci <= i1; ci++) {
			for (int cj = j0; cj <= j1; cj++) {
				float[] t = tree(ci, cj);
				if (t == null || t[0] < x0 || t[0] >= x1 || t[2] < z0 || t[2] >= z1) continue;
				addTree(mb, t, ci, cj);
			}
		}
		
		// ---- rocks ----
		i0 = (int) Math.floor(x0 / ROCK_CELL); i1 = (int) Math.floor(x1 / ROCK_CELL);
		j0 = (int) Math.floor(z0 / ROCK_CELL); j1 = (int) Math.floor(z1 / ROCK_CELL);
		for (int ci = i0; ci <= i1; ci++) {
			for (int cj = j0; cj <= j1; cj++) {
				float[] r = rock(ci, cj);
				if (r == null || r[0] < x0 || r[0] >= x1 || r[2] < z0 || r[2] >= z1) continue;
				float shade = 0.85f + 0.20f * tg.rand(ci, cj, 35);
				int seed = ci * 1000 + cj;
				if (r[3] > 3.0f) {
					mb.addBoulder(r[0], r[1], r[2], r[3], seed,
					0.47f * shade, 0.45f * shade, 0.42f * shade);
				} else {
					mb.addRock(r[0], r[1], r[2], r[3], seed,
					0.47f * shade, 0.45f * shade, 0.42f * shade);
				}
			}
		}
		
		// ---- grass ----
		i0 = (int) Math.floor(x0 / GRASS_CELL); i1 = (int) Math.floor(x1 / GRASS_CELL);
		j0 = (int) Math.floor(z0 / GRASS_CELL); j1 = (int) Math.floor(z1 / GRASS_CELL);
		for (int ci = i0; ci <= i1; ci++) {
			for (int cj = j0; cj <= j1; cj++) {
				float[] g = grass(ci, cj);
				if (g == null || g[0] < x0 || g[0] >= x1 || g[2] < z0 || g[2] >= z1) continue;
				addGrass(mb, g);
			}
		}
		
		// ---- buildings ----
		for (int i = 0; i < buildings.size(); i++) {
			float[] b = buildings.get(i);
			if (b[0] >= x0 && b[0] < x1 && b[2] >= z0 && b[2] < z1) {
				addBuilding(mb, b, i);
			}
		}
	}
	
	// ==================================================================
	// Tree assembly (uses new addRealisticTree)
	// ==================================================================
	
	private void addTree(MeshBuilder mb, float[] t, int ci, int cj) {
		float x = t[0], y = t[1] - 0.2f, z = t[2];
		float s = t[3];   // 0.85 .. 1.5
		
		// World height mapping: s * GameSettings.TREE_SCALE + base
		// With TREE_SCALE = 15, s=0.85 → 12.75 m, s=1.5 → 22.5 m
		float height = (10f + s * 8f) * GameSettings.TREE_SCALE;
		if (height > GameSettings.MAX_TREE_HEIGHT) height = GameSettings.MAX_TREE_HEIGHT;
		
		float radius = height * 0.36f;
		float tint = tg.rand(ci, cj, 15);
		int seed = ci * 73856093 ^ cj * 19349663;
		
		mb.addRealisticTree(x, y, z, height, radius, tint, seed);
		
		// Occasionally add a stump + companion sapling to break up the silhouette
		if (MeshBuilder.REALISTIC && tg.rand(ci, cj, 16) < 0.18f) {
			float offX = (tg.rand(ci, cj, 17) - 0.5f) * 3.5f;
			float offZ = (tg.rand(ci, cj, 18) - 0.5f) * 3.5f;
			float sx = x + offX, sz = z + offZ;
			float sg = tg.height(sx, sz);
			if (Math.abs(sg - y) < 0.8f) {
				mb.addTreeStump(sx, sg, sz, 0.55f, 0.6f);
			}
		}
	}
	
	private void addGrass(MeshBuilder mb, float[] g) {
		float x = g[0], y = g[1] - 0.05f, z = g[2];
		float hgt = 0.55f + 0.55f * g[3];
		float w = 0.30f;
		float ang = g[3] * 6.2831f;
		float c = (float) Math.cos(ang) * w, s = (float) Math.sin(ang) * w;
		float cr = 0.30f + 0.08f * g[3], cg = 0.56f + 0.10f * g[3], cb = 0.18f;
		mb.triGrass(x - c, y, z - s, x + c, y, z + s, x, y + hgt, z, cr, cg, cb);
		mb.triGrass(x + s, y, z - c, x - s, y, z + c, x, y + hgt, z, cr, cg, cb);
	}
	
	// ==================================================================
	// Building assembly (uses new addHouse)
	// ==================================================================
	
	private void addBuilding(MeshBuilder mb, float[] b, int index) {
		float x = b[0], baseY = b[1], z = b[2], half = b[3], topY = b[4];
		float wallH = topY - baseY;
		int floors = wallH > 7f ? 3 : (wallH > 4.5f ? 2 : 1);
		
		// Colour variation per building
		float hueShift = tg.rand(index, 0, 51);
		float wallR = 0.72f + 0.15f * hueShift;
		float wallG = 0.68f + 0.10f * hueShift;
		float wallB = 0.58f + 0.08f * hueShift;
		float roofR = 0.55f + 0.25f * tg.rand(index, 0, 52);
		float roofG = 0.22f + 0.12f * tg.rand(index, 0, 53);
		float roofB = 0.18f + 0.08f * tg.rand(index, 0, 54);
		
		// Pitch roof only if height allows
		float roofH = half * 0.85f;
		
		mb.addHouse(
			x, z, baseY - 1.5f, 
			half * 2f, half * 2f, 
			wallH + 0.5f, floors, 
			wallR, wallG, wallB, 
			roofR, roofG, roofB, 
			roofH, 
			index * 977 + 13
		);
	}
	
	// ==================================================================
	// Tree shadows (unchanged, just bigger radius for bigger trees)
	// ==================================================================
	
	public void bakeTreeShadows(float minX, float minZ, int n, float[] shade) {
		int i0 = (int) Math.floor((minX - 10f) / TREE_CELL);
		int i1 = (int) Math.floor((minX + n + 10f) / TREE_CELL);
		int j0 = (int) Math.floor((minZ - 10f) / TREE_CELL);
		int j1 = (int) Math.floor((minZ + n + 10f) / TREE_CELL);
		for (int ci = i0; ci <= i1; ci++) {
			for (int cj = j0; cj <= j1; cj++) {
				float[] t = tree(ci, cj);
				if (t == null) continue;
				float s = t[3];
				float height = (10f + s * 8f) * GameSettings.TREE_SCALE;
				float radius = height * 0.36f;
				// Big trees cast a larger, softer shadow away from the sun
				float sx = t[0] - radius * 0.55f;
				float sz = t[2] - radius * 0.40f;
				int xa = Math.max((int) Math.floor(sx - radius - minX), 0);
				int xb = Math.min((int) Math.ceil(sx + radius - minX), n - 1);
				int za = Math.max((int) Math.floor(sz - radius - minZ), 0);
				int zb = Math.min((int) Math.ceil(sz + radius - minZ), n - 1);
				for (int z = za; z <= zb; z++) {
					for (int x = xa; x <= xb; x++) {
						float ddx = minX + x - sx;
						float ddz = minZ + z - sz;
						float d = (float) Math.sqrt(ddx * ddx + ddz * ddz) / radius;
						if (d >= 1f) continue;
						float dark = 1f - 0.45f * (1f - d);
						int idx = z * n + x;
						if (dark < shade[idx]) shade[idx] = dark;
					}
				}
			}
		}
	}
}
