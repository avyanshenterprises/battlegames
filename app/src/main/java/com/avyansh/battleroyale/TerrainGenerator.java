package com.avyansh.battleroyale;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TerrainGenerator {
	public static final float ISLAND_RADIUS = 1250f;
	public static final int SITE_VILLAGE = 0, SITE_CITY = 1, SITE_BASE = 2, SITE_FARM = 3;
	public static final float WORLD_HALF = GameSettings.WORLD_HALF_CHUNKS * GameSettings.chunkSize();
	public static final float WORLD_SIZE = WORLD_HALF * 2f;
	public static final float PLAY_RADIUS = ISLAND_RADIUS + 150f;
	
	// Compatibility alias so old code that said TerrainGenerator.Building still compiles.
	public static final class Building_ {} // unused sentinel
	
	public static boolean inWorld(float x, float z, float margin) {
		float lim = WORLD_HALF - margin;
		return x >= -lim && x <= lim && z >= -lim && z <= lim;
	}
	
	public static boolean inPlayArea(float x, float z, float margin) {
		float r = PLAY_RADIUS - margin;
		return inWorld(x, z, margin) && (x * x + z * z) <= r * r;
	}
	public static final float CHUNK_SIZE   = 64f;
	public static final float WATER_LEVEL  = 0f;
	
	public float rand(int x, int z, int salt) {
		int h = x * 73856093 ^ z * 19349663 ^ salt * 83492791 ^ seed;
		h ^= (h >>> 13);
		h *= 0x5bd1e995;
		h ^= (h >>> 15);
		return (h & 0xFFFFFF) / (float) 0x1000000;
	}
	
	public float height(float x, float z) { return heightAt(x, z); }
	
	public float normalY(float x, float z) {
		float e = 1.5f;
		float hL = heightAt(x - e, z), hR = heightAt(x + e, z);
		float hD = heightAt(x, z - e), hU = heightAt(x, z + e);
		float nx = hL - hR, nz = hD - hU;
		float ny = 2f * e;
		float inv = 1f / (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		return ny * inv;
	}
	
	public static float smoothstep(float e0, float e1, float x) {
		float t = GameMath.clamp((x - e0) / (e1 - e0), 0f, 1f);
		return t * t * (3f - 2f * t);
	}
	
	public static float clamp(float v, float lo, float hi) { return GameMath.clamp(v, lo, hi); }
	
	public static final class Site {
		public final int type;
		public final float x, z, radius;
		public float h;
		Site(int type, float x, float z, float radius) {
			this.type = type;
			this.x = x;
			this.z = z;
			this.radius = radius;
		}
	}
	
	// ================================================================
	// Buildings list — now uses top-level Building class with real
	// per-floor collision data baked in at generation time.
	// ================================================================
	public final List<Building> buildings = new ArrayList<Building>();
	private final float[] np = new float[2];
	private final float[] np2 = new float[2];
	
	public TerrainGenerator(int seed) {
		this.seed = seed;
		sites = new Site[] {
			new Site(SITE_VILLAGE, 90f, 60f, 110f),
			new Site(SITE_CITY, -380f, -260f, 170f),
			new Site(SITE_BASE, 420f, 380f, 150f),
			new Site(SITE_FARM, -250f, 330f, 130f)
		};
		for (int i = 0; i < sites.length; i++) {
			sites[i].h = GameMath.clamp(rawHeight(sites[i].x, sites[i].z), 3.6f, 14f);
		}
		generateBuildings();
	}
	
	private float hash(int x, int z) {
		int h = x * 374761393 + z * 668265263 + seed * 1274126177;
		h = (h ^ (h >>> 13)) * 1274126177;
		h ^= (h >>> 16);
		return (h & 0xFFFF) / 65535f;
	}
	
	private float noise2(float x, float z) {
		int xi = (int) Math.floor(x);
		int zi = (int) Math.floor(z);
		float fx = x - xi, fz = z - zi;
		float u = fx * fx * fx * (fx * (fx * 6f - 15f) + 10f);
		float v = fz * fz * fz * (fz * (fz * 6f - 15f) + 10f);
		float a = hash(xi, zi), b = hash(xi + 1, zi);
		float c = hash(xi, zi + 1), d = hash(xi + 1, zi + 1);
		return GameMath.lerp(GameMath.lerp(a, b, u), GameMath.lerp(c, d, u), v);
	}
	
	private float fbm(float x, float z, int octaves) {
		float sum = 0f, amp = 0.5f, f = 1f, norm = 0f;
		for (int i = 0; i < octaves; i++) {
			sum += noise2(x * f, z * f) * amp;
			norm += amp;
			amp *= 0.5f;
			f *= 2.03f;
		}
		return sum / norm;
	}
	
	private float siteMask(float x, float z) {
		float m = 0f;
		for (int i = 0; i < sites.length; i++) {
			Site s = sites[i];
			float dx = x - s.x, dz = z - s.z;
			float d = (float) Math.sqrt(dx * dx + dz * dz);
			float v = 1f - GameMath.smooth(s.radius * 0.75f, s.radius * 1.35f, d);
			if (v > m) m = v;
		}
		return m;
	}
	
	private float rawHeight(float x, float z) {
		float d = (float) Math.sqrt(x * x + z * z);
		float warp = noise2(x * 0.0013f + 17.3f, z * 0.0013f - 9.1f);
		float r = d / ISLAND_RADIUS * (1f + 0.35f * (warp - 0.5f));
		float mask = 1f - GameMath.smooth(0.60f, 0.98f, r);
		float far = GameMath.smooth(150f, 550f, d);
		float base = fbm(x * 0.004f, z * 0.004f, 5);
		float hills = Math.max(0f, base - 0.3f) * 55f * (0.35f + 0.65f * far);
		float mn = fbm(x * 0.0012f + 40f, z * 0.0012f + 40f, 4);
		float mt = GameMath.smooth(0.52f, 0.72f, mn) * GameMath.smooth(500f, 900f, d);
		float ridge = 1f - Math.abs(2f * fbm(x * 0.006f + 5f, z * 0.006f + 5f, 4) - 1f);
		float mount = mt * (40f + ridge * ridge * 120f);
		float land = 3.2f + hills + mount + (base - 0.5f) * 3f;
		float h = GameMath.lerp(-24f, land, mask);
		float inland = GameMath.smooth(0.5f, 0.8f, mask) * (1f - siteMask(x, z));
		float ldx = x - 230f, ldz = z + 420f;
		float ld = (float) Math.sqrt(ldx * ldx + ldz * ldz) / 110f;
		float lake = (1f - GameMath.smooth(0.55f, 1.15f, ld)) * inland;
		h = GameMath.lerp(h, -5f, lake);
		float rx = -520f + 140f * (float) Math.sin(z * 0.006f) + 60f * (float) Math.sin(z * 0.017f);
		float river = (1f - GameMath.smooth(8f, 32f, Math.abs(x - rx))) * inland;
		h = GameMath.lerp(h, -2.5f, river);
		return h;
	}
	
	private float heightNoRoad(float x, float z) {
		float h = rawHeight(x, z);
		for (int i = 0; i < sites.length; i++) {
			Site s = sites[i];
			float dx = x - s.x, dz = z - s.z;
			float d = (float) Math.sqrt(dx * dx + dz * dz);
			if (d < s.radius * 1.15f) {
				float t = 1f - GameMath.smooth(s.radius * 0.65f, s.radius * 1.15f, d);
				h = GameMath.lerp(h, s.h, t);
			}
		}
		return h;
	}
	
	public float heightAt(float x, float z) {
		float h = heightNoRoad(x, z);
		float d = nearestRoad(x, z, np);
		if (d < 16f) {
			float t = 1f - GameMath.smooth(6f, 16f, d);
			float axis = Math.max(1.4f, heightNoRoad(np[0], np[1]));
			h = GameMath.lerp(h, axis, t);
		}
		return h;
	}
	
	private static final float[][] ROADS = {
		{ 90f, 60f, -380f, -260f },
		{ 90f, 60f, 420f, 380f },
		{ 90f, 60f, -250f, 330f }
	};
	
	public float nearestRoad(float x, float z, float[] out) {
		float best = 1e18f;
		for (int i = 0; i < ROADS.length; i++) {
			float[] r = ROADS[i];
			float abx = r[2] - r[0], abz = r[3] - r[1];
			float t = ((x - r[0]) * abx + (z - r[1]) * abz) / (abx * abx + abz * abz);
			t = GameMath.clamp(t, 0f, 1f);
			float px = r[0] + abx * t, pz = r[1] + abz * t;
			float dx = x - px, dz = z - pz;
			float d2 = dx * dx + dz * dz;
			if (d2 < best) {
				best = d2;
				out[0] = px;
				out[1] = pz;
			}
		}
		return (float) Math.sqrt(best);
	}
	
	public float roadFactor(float x, float z) {
		return 1f - GameMath.smooth(4f, 7f, nearestRoad(x, z, np2));
	}
	
	public boolean insideSite(float x, float z, float margin) {
		for (int i = 0; i < sites.length; i++) {
			Site s = sites[i];
			float dx = x - s.x, dz = z - s.z;
			if (Math.sqrt(dx * dx + dz * dz) < s.radius + margin) return true;
		}
		return false;
	}
	
	public float desertness(float x, float z) {
		float d = (float) Math.sqrt(x * x + z * z);
		return GameMath.smooth(0.58f, 0.68f, noise2(x * 0.0016f + 90f, z * 0.0016f + 30f))
		* GameMath.smooth(300f, 600f, d);
	}
	
	public float forestDensity(float x, float z) {
		float f = GameMath.smooth(0.45f, 0.62f, fbm(x * 0.006f + 300f, z * 0.006f + 120f, 3));
		return (0.04f + 0.80f * f) * (1f - desertness(x, z));
	}
	
	public float propHeight(float x, float z) {
		float h = heightAt(x, z);
		if (h < 2.0f || h > 75f) return Float.NaN;
		if (roadFactor(x, z) > 0.01f) return Float.NaN;
		if (insideSite(x, z, 4f)) return Float.NaN;
		float slope = Math.abs(heightAt(x + 2f, z) - h) + Math.abs(heightAt(x, z + 2f) - h);
		if (slope > 2.4f) return Float.NaN;
		return h;
	}
	
	private static void blend(float[] c, float[] t, float w) {
		c[0] += (t[0] - c[0]) * w;
		c[1] += (t[1] - c[1]) * w;
		c[2] += (t[2] - c[2]) * w;
	}
	
	private static final float[] GRASS = { 0.30f, 0.52f, 0.18f };
	private static final float[] FOREST = { 0.15f, 0.36f, 0.12f };
	private static final float[] DRYGRASS = { 0.52f, 0.54f, 0.24f };
	private static final float[] DESERT = { 0.82f, 0.70f, 0.43f };
	private static final float[] SAND = { 0.86f, 0.79f, 0.56f };
	private static final float[] SEABED = { 0.50f, 0.46f, 0.32f };
	private static final float[] ROCK = { 0.46f, 0.44f, 0.41f };
	private static final float[] SNOW = { 0.94f, 0.96f, 0.98f };
	private static final float[] ROAD = { 0.20f, 0.20f, 0.22f };
	private static final float[] CONCRETE = { 0.52f, 0.52f, 0.50f };
	private static final float[] DIRT = { 0.48f, 0.40f, 0.28f };
	private static final float[] FIELD_A = { 0.55f, 0.62f, 0.20f };
	private static final float[] FIELD_B = { 0.45f, 0.33f, 0.18f };
	
	public void groundColor(float x, float z, float h, float ny, float[] c) {
		float v = noise2(x * 0.06f, z * 0.06f);
		float shade = 0.88f + 0.24f * v;
		float f = GameMath.smooth(0.45f, 0.62f, fbm(x * 0.006f + 300f, z * 0.006f + 120f, 3));
		float des = desertness(x, z);
		c[0] = GRASS[0]; c[1] = GRASS[1]; c[2] = GRASS[2];
		blend(c, FOREST, f);
		blend(c, DRYGRASS, GameMath.smooth(0.55f, 0.75f, noise2(x * 0.012f + 51f, z * 0.012f + 13f)) * (1f - f) * 0.7f);
		blend(c, DESERT, des);
		blend(c, SAND, 1f - GameMath.smooth(1.8f, 3.6f, h));
		if (h < 0.2f) {
			blend(c, SEABED, GameMath.smooth(0.2f, -2f, h));
			shade *= GameMath.clamp(1f + h * 0.025f, 0.55f, 1f);
		}
		blend(c, ROCK, GameMath.smooth(0.84f, 0.64f, ny));
		blend(c, SNOW, GameMath.smooth(75f, 100f, h));
		for (int i = 0; i < sites.length; i++) {
			Site s = sites[i];
			float dx = x - s.x, dz = z - s.z;
			float d = (float) Math.sqrt(dx * dx + dz * dz);
			float sw = 1f - GameMath.smooth(s.radius * 0.55f, s.radius * 0.9f, d);
			if (sw <= 0f) continue;
			switch (s.type) {
				case SITE_VILLAGE: blend(c, DIRT, sw * (0.35f + 0.4f * v)); break;
				case SITE_CITY:    blend(c, CONCRETE, sw * 0.95f); break;
				case SITE_BASE:    blend(c, CONCRETE, sw * 0.7f); blend(c, DIRT, sw * 0.25f); break;
				default:
				int cell = ((int) Math.floor(x / 14f) + (int) Math.floor(z / 22f)) & 1;
				blend(c, cell == 0 ? FIELD_A : FIELD_B, sw * 0.95f);
				break;
			}
		}
		blend(c, ROAD, roadFactor(x, z));
		c[0] = GameMath.clamp(c[0] * shade, 0f, 1f);
		c[1] = GameMath.clamp(c[1] * shade, 0f, 1f);
		c[2] = GameMath.clamp(c[2] * shade, 0f, 1f);
	}
	
	// ================================================================
	// Building generation — now bakes floors + stairs
	// ================================================================
	public final int seed;
	public final Site[] sites;
	
	private static void setColor(float[] a, float r, float g, float b) {
		a[0] = r; a[1] = g; a[2] = b;
	}
	
	// PUBG-style painted facades: {wall, roof, trim, accent}
	private static final float[][][] PAL_VILLAGE = {
		{ { 0.93f, 0.86f, 0.68f }, { 0.64f, 0.22f, 0.14f }, { 0.98f, 0.96f, 0.90f }, { 0.30f, 0.45f, 0.62f } },  // cream, terracotta roof, blue shutters
		{ { 0.88f, 0.68f, 0.34f }, { 0.50f, 0.17f, 0.12f }, { 0.96f, 0.92f, 0.80f }, { 0.26f, 0.40f, 0.30f } },  // ochre
		{ { 0.55f, 0.74f, 0.88f }, { 0.28f, 0.30f, 0.38f }, { 0.95f, 0.97f, 0.98f }, { 0.60f, 0.24f, 0.18f } },  // sky blue
		{ { 0.92f, 0.62f, 0.60f }, { 0.46f, 0.20f, 0.18f }, { 0.98f, 0.94f, 0.92f }, { 0.28f, 0.34f, 0.46f } },  // pink
		{ { 0.60f, 0.82f, 0.66f }, { 0.58f, 0.26f, 0.16f }, { 0.96f, 0.98f, 0.94f }, { 0.52f, 0.30f, 0.18f } },  // mint
		{ { 0.95f, 0.95f, 0.91f }, { 0.30f, 0.33f, 0.40f }, { 0.60f, 0.58f, 0.54f }, { 0.55f, 0.20f, 0.16f } },  // white
		{ { 0.96f, 0.83f, 0.44f }, { 0.62f, 0.20f, 0.14f }, { 0.98f, 0.95f, 0.85f }, { 0.34f, 0.30f, 0.24f } },  // yellow
		{ { 0.72f, 0.36f, 0.27f }, { 0.24f, 0.24f, 0.27f }, { 0.92f, 0.88f, 0.80f }, { 0.22f, 0.32f, 0.42f } }   // brick
	};
	private static final float[][][] PAL_CITY = {
		{ { 0.80f, 0.74f, 0.62f }, { 0.30f, 0.31f, 0.33f }, { 0.96f, 0.94f, 0.88f }, { 0.28f, 0.46f, 0.64f } },  // sand apartment
		{ { 0.62f, 0.70f, 0.78f }, { 0.28f, 0.29f, 0.32f }, { 0.92f, 0.94f, 0.96f }, { 0.60f, 0.26f, 0.20f } },  // grey-blue block
		{ { 0.84f, 0.58f, 0.40f }, { 0.30f, 0.30f, 0.32f }, { 0.96f, 0.92f, 0.86f }, { 0.24f, 0.36f, 0.30f } },  // salmon
		{ { 0.70f, 0.34f, 0.26f }, { 0.26f, 0.26f, 0.28f }, { 0.90f, 0.86f, 0.78f }, { 0.20f, 0.30f, 0.42f } },  // brick
		{ { 0.90f, 0.88f, 0.80f }, { 0.34f, 0.34f, 0.36f }, { 0.60f, 0.58f, 0.54f }, { 0.62f, 0.28f, 0.20f } },  // off-white
		{ { 0.66f, 0.80f, 0.70f }, { 0.30f, 0.31f, 0.33f }, { 0.94f, 0.96f, 0.94f }, { 0.50f, 0.32f, 0.20f } }   // pale green
	};
	private static final float[][][] PAL_BASE = {
		{ { 0.52f, 0.58f, 0.40f }, { 0.28f, 0.32f, 0.24f }, { 0.74f, 0.74f, 0.64f }, { 0.34f, 0.30f, 0.22f } },  // olive
		{ { 0.70f, 0.64f, 0.46f }, { 0.34f, 0.32f, 0.26f }, { 0.84f, 0.82f, 0.72f }, { 0.30f, 0.34f, 0.24f } },  // khaki
		{ { 0.58f, 0.62f, 0.60f }, { 0.28f, 0.30f, 0.30f }, { 0.84f, 0.86f, 0.84f }, { 0.50f, 0.20f, 0.16f } }   // concrete grey-green
	};
	private static final float[][][] PAL_OTHER = {
		{ { 0.74f, 0.28f, 0.22f }, { 0.30f, 0.30f, 0.33f }, { 0.94f, 0.90f, 0.84f }, { 0.26f, 0.34f, 0.46f } },  // red shack
		{ { 0.40f, 0.52f, 0.68f }, { 0.34f, 0.24f, 0.18f }, { 0.94f, 0.94f, 0.92f }, { 0.78f, 0.62f, 0.30f } },  // blue shack
		{ { 0.86f, 0.74f, 0.50f }, { 0.44f, 0.22f, 0.16f }, { 0.96f, 0.94f, 0.88f }, { 0.28f, 0.42f, 0.34f } }   // sand
	};

	private void applyPalette(Building b, int siteType, Random pr, float tint) {
		float[][][] set;
		switch (siteType) {
			case SITE_VILLAGE: set = PAL_VILLAGE; break;
			case SITE_CITY:    set = PAL_CITY;    break;
			case SITE_BASE:    set = PAL_BASE;    break;
			default:           set = PAL_OTHER;   break;
		}
		float[][] p = set[pr.nextInt(set.length)];
		float v = 0.94f + 0.12f * tint;   // small per-building brightness variation
		for (int i = 0; i < 3; i++) {
			b.wall[i]   = Math.min(1f, p[0][i] * v);
			b.roof[i]   = Math.min(1f, p[1][i] * (0.94f + 0.12f * (1f - tint)));
			b.trim[i]   = p[2][i];
			b.accent[i] = p[3][i];
		}
		switch (siteType) {
			case SITE_CITY:
				b.roofStyle = 2; b.roofH = 0f; break;
			case SITE_BASE:
				if (pr.nextInt(10) < 7) { b.roofStyle = 2; b.roofH = 0f; }
				else { b.roofStyle = 1; b.roofH = 2.2f; }
				break;
			default:
				if (pr.nextInt(100) < 15) { b.roofStyle = 2; b.roofH = 0f; }
				else { b.roofStyle = 1; b.roofH = 2.5f; }
				break;
		}
	}

	private void generateBuildings() {
		Random r = new Random(seed * 7919L + 13L);
		Random pal = new Random(seed * 31L + 7L);   // separate stream: colours never change the layout
		for (int si = 0; si < sites.length; si++) {
			Site s = sites[si];
			int count;
			switch (s.type) {
				case SITE_VILLAGE: count = 16; break;
				case SITE_CITY:    count = 26; break;
				case SITE_BASE:    count = 9;  break;
				default:           count = 4;  break;
			}
			int placed = 0;
			for (int t = 0; t < count * 30 && placed < count; t++) {
				float ang = r.nextFloat() * 6.2831853f;
				float dist = (float) Math.sqrt(r.nextFloat()) * s.radius * 0.6f;
				Building b = new Building();
				b.x = s.x + (float) Math.cos(ang) * dist;
				b.z = s.z + (float) Math.sin(ang) * dist;
				float tint = r.nextFloat();
				b.w = 10f + r.nextFloat() * 8f;
				b.d = 10f + r.nextFloat() * 8f;
				b.floors = 2 + r.nextInt(2);
				b.h = 3.5f * b.floors + 1f;
				b.roofH = 2.5f;
				
				applyPalette(b, s.type, pal, tint);
				
				float clear = Math.max(b.w, b.d) * 0.5f + 7f;
				if (nearestRoad(b.x, b.z, np) < clear) continue;
				
				boolean overlap = false;
				for (int k = 0; k < buildings.size(); k++) {
					Building o = buildings.get(k);
					if (Math.abs(o.x - b.x) < (o.w + b.w) * 0.5f + 4f
					&& Math.abs(o.z - b.z) < (o.d + b.d) * 0.5f + 4f) {
						overlap = true;
						break;
					}
				}
				if (overlap) continue;
				
				b.baseY = s.h - 0.6f;
				b.bake();
				buildings.add(b);
				placed++;
			}
		}
	}
	
}