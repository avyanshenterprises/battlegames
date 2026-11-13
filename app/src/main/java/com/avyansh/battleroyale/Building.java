package com.avyansh.battleroyale;

import java.util.ArrayList;
import java.util.List;

/**
 * One enterable building.
 *
 * SINGLE SOURCE OF TRUTH: bake() creates the floor slabs (with a stairwell hole) and the
 * stair treads. MeshBuilder draws exactly these, and blocksAt()/groundHeight()/rayHit() use
 * exactly these, so what you see is what you collide with.
 *
 * Layout: door in the front (+Z) wall. Stairs run along a side wall, low end near the
 * door, rising toward the back wall. Flights alternate right wall / left wall so the
 * stairwell hole of one floor never sits on top of the next flight's first tread.
 */
public final class Building {
	public float x, z, w, d, h, roofH, baseY;
	public int floors = 1;
	public final float[] wall = new float[3];
	public final float[] roof = new float[3];
	/** Trim colour (corner pilasters, window frames, cornice) and accent colour (door, shutters, awning). */
	public final float[] trim = { 0.92f, 0.90f, 0.85f };
	public final float[] accent = { 0.35f, 0.22f, 0.14f };
	/** 0 = hip (pyramid) roof, 1 = gable roof, 2 = flat roof. */
	public int roofStyle = 0;

	public static final float WALL_T = 0.45f;
	public static final float DOOR_W = 2.2f;
	public static final float DOOR_H = 3.0f;
	public static final float GROUND_LIP = 0.62f;   // interior ground floor top above baseY
	public static final float STAIR_W = 2.0f;
	private static final float ENTRY_GAP = 1.2f;    // free floor between front wall and first tread
	private static final float LANDING = 1.4f;      // free floor behind the last tread
	private static final float STEP_D = 0.48f;
	private static final float STEP_MAX_H = 0.31f;  // must stay < 0.35 (player step-up margin)

	/** Top surface of each upper slab (index 0 = floor 1). */
	public float[] floorTops = new float[0];
	/** slabParts[slab][part] = {x0, z0, x1, z1}. Together they are the slab minus the stairwell. */
	public float[][][] slabParts = new float[0][][];
	public float groundTop;
	public final List<FloorStep> stairSteps = new ArrayList<>();
	/** Solid wall pieces {x0,y0,z0,x1,y1,z1,flag} (flag 1 = door lintel). The gaps between them are the door and the OPEN windows. */
	public final List<float[]> wallBoxes = new ArrayList<>();
	/** Open window openings {face(0 front,1 back,2 right,3 left), centre, yLow, yHigh, halfWidth} - used by the mesh for frames and shutters. */
	public final List<float[]> windows = new ArrayList<>();
	public static final float WIN_HALF_W = 0.65f;

	public float doorH() { return Math.min(DOOR_H, h - 0.6f); }
	public float floorH() { return h / Math.max(1, floors); }

	public float x0() { return x - w * 0.5f; }
	public float x1() { return x + w * 0.5f; }
	public float z0() { return z - d * 0.5f; }
	public float z1() { return z + d * 0.5f; }

	/** Build slabs + stairs from the current x/z/w/d/h/floors/baseY. Call once after placing. */
	public void bake() {
		stairSteps.clear();
		int fl = Math.max(1, floors);
		int slabCount = fl - 1;
		float fh = h / fl;
		groundTop = baseY + GROUND_LIP;
		floorTops = new float[slabCount];
		slabParts = new float[slabCount][][];
		for (int k = 0; k < slabCount; k++) floorTops[k] = baseY + (k + 1) * fh;

		float ix0 = x0() + WALL_T, ix1 = x1() - WALL_T;
		float iz0 = z0() + WALL_T, iz1 = z1() - WALL_T;

		for (int k = 0; k < slabCount; k++) {
			float bottom = (k == 0) ? groundTop : floorTops[k - 1];
			float top = floorTops[k];
			float rise = top - bottom;

			float avail = (iz1 - iz0) - ENTRY_GAP - LANDING;
			int nWanted = (int) Math.ceil(rise / STEP_MAX_H);
			int nFit = (int) Math.floor(avail / STEP_D);
			int n = Math.max(4, Math.min(nWanted, Math.max(nFit, 4)));
			float stepH = rise / n;
			float stepD = STEP_D;
			if (n * stepD > avail) stepD = Math.max(0.42f, avail / n);

			boolean right = (k % 2 == 0);
			float sx0 = right ? (ix1 - STAIR_W) : ix0;
			float sx1 = sx0 + STAIR_W;
			float zLow = iz1 - ENTRY_GAP;               // low end (front)

			for (int i = 0; i < n; i++) {
				float zs1 = zLow - i * stepD;
				float zs0 = zs1 - stepD;
				float topY = bottom + stepH * (i + 1);
				stairSteps.add(new FloorStep(sx0, zs0, sx1, zs1, topY, topY - (bottom - 0.05f)));
			}
			float zHigh = zLow - n * stepD;             // high end (back)

			// stairwell hole in the slab above this flight
			float hx0 = right ? (sx0 - 0.25f) : ix0;
			float hx1 = right ? ix1 : (sx1 + 0.25f);
			float hz0 = zHigh, hz1 = zLow;

			ArrayList<float[]> parts = new ArrayList<>();
			if (hx0 > ix0 + 0.01f) parts.add(new float[] { ix0, iz0, hx0, iz1 });
			if (ix1 > hx1 + 0.01f) parts.add(new float[] { hx1, iz0, ix1, iz1 });
			if (hz0 > iz0 + 0.01f) parts.add(new float[] { hx0, iz0, hx1, hz0 });
			if (iz1 > hz1 + 0.01f) parts.add(new float[] { hx0, hz1, hx1, iz1 });
			slabParts[k] = parts.toArray(new float[0][]);
		}
		bakeWalls();
	}

	// ---------------------------------------------------------------- walls with real window openings
	private void addWin(ArrayList<ArrayList<float[]>> per, int face, float c, float lo, float hi) {
		per.get(face).add(new float[] { c, WIN_HALF_W, lo, hi, 0f });
		windows.add(new float[] { face, c, lo, hi, WIN_HALF_W });
	}

	private void bakeWalls() {
		wallBoxes.clear();
		windows.clear();
		int fl = Math.max(1, floors);
		float yB = baseY, yT = baseY + h;
		float dh = DOOR_W * 0.5f;
		ArrayList<ArrayList<float[]>> per = new ArrayList<>();
		for (int i = 0; i < 4; i++) per.add(new ArrayList<float[]>());
		per.get(0).add(new float[] { x, dh, yB, yB + doorH(), 1f });   // doorway

		float m = WALL_T + 1.1f;
		for (int f = 0; f < fl; f++) {
			float level = f == 0 ? groundTop : floorTops[f - 1];
			float ceil = f < fl - 1 ? floorTops[f] - 0.22f : yT - 0.30f;
			float lo = level + 0.95f;
			float hi = Math.min(level + 2.25f, ceil - 0.15f);
			if (hi - lo < 0.85f) continue;
			boolean wideX = w >= 10f, wideZ = d >= 10f;
			// front (+Z): beside the door on the ground floor, centred above it on upper floors
			if (f == 0) {
				float off = dh + 1.9f;
				if (x - off > x0() + m) addWin(per, 0, x - off, lo, hi);
				if (x + off < x1() - m) addWin(per, 0, x + off, lo, hi);
			} else if (wideX) {
				addWin(per, 0, x - w * 0.22f, lo, hi); addWin(per, 0, x + w * 0.22f, lo, hi);
			} else addWin(per, 0, x, lo, hi);
			// back (-Z)
			if (wideX) { addWin(per, 1, x - w * 0.22f, lo, hi); addWin(per, 1, x + w * 0.22f, lo, hi); }
			else addWin(per, 1, x, lo, hi);
			// sides
			for (int face = 2; face <= 3; face++) {
				if (wideZ) { addWin(per, face, z - d * 0.22f, lo, hi); addWin(per, face, z + d * 0.22f, lo, hi); }
				else addWin(per, face, z, lo, hi);
			}
		}
		float t = WALL_T;
		cutWall(true,  z0(), z0() + t, x0(), x1(), yB, yT, per.get(1));            // back
		cutWall(true,  z1() - t, z1(), x0(), x1(), yB, yT, per.get(0));            // front (with door)
		cutWall(false, x0(), x0() + t, z0() + t, z1() - t, yB, yT, per.get(3));    // left
		cutWall(false, x1() - t, x1(), z0() + t, z1() - t, yB, yT, per.get(2));    // right
	}

	private void wallBox(boolean alongX, float f0, float f1, float u0, float u1, float y0, float y1, float flag) {
		if (u1 - u0 < 0.02f || y1 - y0 < 0.02f) return;
		if (alongX) wallBoxes.add(new float[] { u0, y0, f0, u1, y1, f1, flag });
		else wallBoxes.add(new float[] { f0, y0, u0, f1, y1, u1, flag });
	}

	/** Builds one wall from solid pieces, leaving the given openings (door / windows) empty. */
	private void cutWall(boolean alongX, float f0, float f1, float u0, float u1, float yB, float yT, ArrayList<float[]> ops) {
		java.util.Collections.sort(ops, new java.util.Comparator<float[]>() {
			@Override public int compare(float[] a, float[] b) { return Float.compare(a[0], b[0]); }
		});
		float cur = u0;
		for (int i = 0; i < ops.size(); i++) {
			float[] o = ops.get(i);
			float a = Math.max(u0, o[0] - o[1]), b = Math.min(u1, o[0] + o[1]);
			if (a < cur) continue;   // overlapping opening, skip
			wallBox(alongX, f0, f1, cur, a, yB, yT, 0f);
			if (o[4] > 0.5f) {                 // door: only the lintel above it
				wallBox(alongX, f0, f1, a, b, o[3], yT, 1f);
			} else {                           // window: wall below (sill) and above (header)
				wallBox(alongX, f0, f1, a, b, yB, o[2], 0f);
				wallBox(alongX, f0, f1, a, b, o[3], yT, 0f);
			}
			cur = b;
		}
		wallBox(alongX, f0, f1, cur, u1, yB, yT, 0f);
	}

	private static boolean hitRect(float px, float pz, float r,
	float ax0, float az0, float ax1, float az1) {
		float cx = px < ax0 ? ax0 : (px > ax1 ? ax1 : px);
		float cz = pz < az0 ? az0 : (pz > az1 ? az1 : pz);
		float dx = px - cx, dz = pz - cz;
		return dx * dx + dz * dz < r * r;
	}

	private boolean insideOuter(float px, float pz, float r) {
		return px >= x0() - r && px <= x1() + r && pz >= z0() - r && pz <= z1() + r;
	}

	private boolean onSlab(int k, float px, float pz) {
		float[][] parts = slabParts[k];
		for (int i = 0; i < parts.length; i++) {
			float[] p = parts[i];
			if (px >= p[0] - 0.05f && px <= p[2] + 0.05f && pz >= p[1] - 0.05f && pz <= p[3] + 0.05f) return true;
		}
		return false;
	}

	public boolean blocksAt(float px, float pz, float yFeet, float r) {
		if (!insideOuter(px, pz, r)) return false;

		float capsuleTop = yFeet + 1.75f;
		float bTop = baseY + h + Math.max(0.25f, roofH);
		if (capsuleTop < baseY - 0.5f) return false;
		if (yFeet > bTop + 0.5f) return false;
		if (yFeet >= baseY + h - 0.1f) return false;   // on the roof — free

		// ---------- upper slabs (with the stairwell hole) ----------
		for (int f = 0; f < floorTops.length; f++) {
			float slabTop = floorTops[f];
			float slabBot = slabTop - 0.20f;
			if (capsuleTop > slabBot && yFeet < slabTop - 0.02f) {
				if (yFeet >= slabTop - 0.35f) continue;   // standing on this slab
				float[][] parts = slabParts[f];
				for (int i = 0; i < parts.length; i++) {
					float[] p = parts[i];
					if (hitRect(px, pz, r, p[0], p[1], p[2], p[3])) return true;
				}
			}
		}

		// ---------- walls (door + open windows are real gaps between the wall pieces) ----------
		float doorTopY = baseY + doorH();
		boolean feetInDoorZone = yFeet < doorTopY - 1.0f;   // walking / jumping / crouching through the doorway
		for (int i = 0; i < wallBoxes.size(); i++) {
			float[] wb = wallBoxes.get(i);
			if (wb[6] > 0.5f && feetInDoorZone) continue;      // lintel is open for anyone at ground level
			float lowTol = wb[1] <= baseY + 0.01f ? 0.4f : 0f;
			if (capsuleTop <= wb[1] - lowTol || yFeet >= wb[4]) continue;
			if (hitRect(px, pz, r, wb[0], wb[2], wb[3], wb[5])) return true;
		}

		// ---------- stairs ----------
		for (int i = 0; i < stairSteps.size(); i++) {
			FloorStep s = stairSteps.get(i);
			if (s.containsXZ(px, pz, r)) {
				float sTop = s.topY;
				float sBot = s.topY - s.thickness;
				if (capsuleTop > sBot && yFeet < sTop - 0.35f) return true;
			}
		}
		return false;
	}

	public float groundHeight(float px, float pz, float yFeet, float tolerance) {
		if (!insideOuter(px, pz, 0f)) return Float.NEGATIVE_INFINITY;

		float best = Float.NEGATIVE_INFINITY;

		if (groundTop <= yFeet + tolerance) best = Math.max(best, groundTop);

		// slab only counts where the slab really exists (not over the stairwell hole)
		for (int f = 0; f < floorTops.length; f++) {
			float top = floorTops[f];
			if (top <= yFeet + tolerance && onSlab(f, px, pz)) best = Math.max(best, top);
		}

		for (int i = 0; i < stairSteps.size(); i++) {
			FloorStep s = stairSteps.get(i);
			if (s.containsXZ(px, pz, 0.10f)) {
				if (s.topY <= yFeet + tolerance) best = Math.max(best, s.topY);
			}
		}

		float roofTop = baseY + h + Math.max(0.15f, roofH * 0.35f);
		if (roofTop <= yFeet + tolerance && insideOuter(px, pz, -0.1f)) {
			best = Math.max(best, roofTop);
		}

		return best;
	}

	public float rayHit(float ox, float oy, float oz, float dx, float dy, float dz, float maxT) {
		float best = -1f, r;
		float t = WALL_T, dh = DOOR_W * 0.5f;
		float doorH = doorH();
		float bx0 = x0(), bx1 = x1(), bz0 = z0(), bz1 = z1();
		float yB = baseY, yT = baseY + h;

		for (int i = 0; i < wallBoxes.size(); i++) {
			float[] wb = wallBoxes.get(i);
			r = rayBox(ox, oy, oz, dx, dy, dz, wb[0], wb[1], wb[2], wb[3], wb[4], wb[5], maxT);
			if (r >= 0f && (best < 0f || r < best)) best = r;
		}
		r = rayBox(ox, oy, oz, dx, dy, dz, bx0, yT - 0.25f, bz0, bx1, yT + 0.4f, bz1, maxT); if (r >= 0f && (best < 0f || r < best)) best = r;
		for (int f = 0; f < floorTops.length; f++) {
			float top = floorTops[f];
			float[][] parts = slabParts[f];
			for (int i = 0; i < parts.length; i++) {
				float[] p = parts[i];
				r = rayBox(ox, oy, oz, dx, dy, dz, p[0], top - 0.20f, p[1], p[2], top, p[3], maxT);
				if (r >= 0f && (best < 0f || r < best)) best = r;
			}
		}
		for (int i = 0; i < stairSteps.size(); i++) {
			FloorStep s = stairSteps.get(i);
			r = rayBox(ox, oy, oz, dx, dy, dz, s.x0, s.topY - s.thickness, s.z0,
				s.x1, s.topY, s.z1, maxT);
			if (r >= 0f && (best < 0f || r < best)) best = r;
		}
		return best;
	}

	private static float rayBox(float ox, float oy, float oz, float dx, float dy, float dz,
	float ax0, float ay0, float az0, float ax1, float ay1, float az1, float maxT) {
		float tmin = 0f, tmax = maxT;
		if (Math.abs(dx) < 1e-6f) { if (ox < ax0 || ox > ax1) return -1f; }
		else {
			float t1 = (ax0 - ox) / dx, t2 = (ax1 - ox) / dx;
			if (t1 > t2) { float s = t1; t1 = t2; t2 = s; }
			if (t1 > tmin) tmin = t1;
			if (t2 < tmax) tmax = t2;
			if (tmin > tmax) return -1f;
		}
		if (Math.abs(dy) < 1e-6f) { if (oy < ay0 || oy > ay1) return -1f; }
		else {
			float t1 = (ay0 - oy) / dy, t2 = (ay1 - oy) / dy;
			if (t1 > t2) { float s = t1; t1 = t2; t2 = s; }
			if (t1 > tmin) tmin = t1;
			if (t2 < tmax) tmax = t2;
			if (tmin > tmax) return -1f;
		}
		if (Math.abs(dz) < 1e-6f) { if (oz < az0 || oz > az1) return -1f; }
		else {
			float t1 = (az0 - oz) / dz, t2 = (az1 - oz) / dz;
			if (t1 > t2) { float s = t1; t1 = t2; t2 = s; }
			if (t1 > tmin) tmin = t1;
			if (t2 < tmax) tmax = t2;
			if (tmin > tmax) return -1f;
		}
		return tmin;
	}
}
