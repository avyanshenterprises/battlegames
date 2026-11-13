package com.avyansh.battleroyale;

import java.util.Arrays;

/**
 * Procedural 3D guns (one per Weapon type) that characters hold in their hands.
 * Built once as GltfModels so they are drawn with the normal SkinShader (vertex colours).
 *
 * Local axes: +Z = barrel direction, +Y = up, +X = the character's LEFT.
 * The local origin is the pistol grip, i.e. where the right hand holds the gun.
 */
public final class WeaponMeshes {
	private WeaponMeshes() {}

	public static final GltfModel[] MODELS = new GltfModel[Weapon.COUNT];
	/** right-hand grip and left-hand fore-grip points in weapon-local metres, per weapon type */
	public static final float[][] GRIP_R = new float[Weapon.COUNT][];
	public static final float[][] GRIP_L = new float[Weapon.COUNT][];

	private static final class B {
		float[] pos = new float[24 * 3 * 64], nrm = new float[24 * 3 * 64], col = new float[24 * 4 * 64];
		int[] idx = new int[36 * 64];
		int v = 0, i = 0;

		void box(float x0, float y0, float z0, float x1, float y1, float z1, float r, float g, float b) {
			if (v + 24 > pos.length / 3) {
				pos = Arrays.copyOf(pos, pos.length * 2); nrm = Arrays.copyOf(nrm, nrm.length * 2);
				col = Arrays.copyOf(col, col.length * 2 * 4 / 3 + 96);
			}
			if (i + 36 > idx.length) idx = Arrays.copyOf(idx, idx.length * 2);
			// +Z, -Z, +X, -X, +Y, -Y
			face(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, r, g, b);
			face(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, r * 0.85f, g * 0.85f, b * 0.85f);
			face(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0, r * 0.92f, g * 0.92f, b * 0.92f);
			face(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0, r * 0.92f, g * 0.92f, b * 0.92f);
			face(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0, Math.min(1f, r * 1.15f), Math.min(1f, g * 1.15f), Math.min(1f, b * 1.15f));
			face(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, r * 0.7f, g * 0.7f, b * 0.7f);
		}

		void face(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
		float dx, float dy, float dz, float nx, float ny, float nz, float r, float g, float b) {
			float[] p = { ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz };
			for (int k = 0; k < 4; k++) {
				pos[(v + k) * 3] = p[k * 3]; pos[(v + k) * 3 + 1] = p[k * 3 + 1]; pos[(v + k) * 3 + 2] = p[k * 3 + 2];
				nrm[(v + k) * 3] = nx; nrm[(v + k) * 3 + 1] = ny; nrm[(v + k) * 3 + 2] = nz;
				col[(v + k) * 4] = r; col[(v + k) * 4 + 1] = g; col[(v + k) * 4 + 2] = b; col[(v + k) * 4 + 3] = 1f;
			}
			idx[i++] = v; idx[i++] = v + 1; idx[i++] = v + 2;
			idx[i++] = v; idx[i++] = v + 2; idx[i++] = v + 3;
			v += 4;
		}

		GltfModel finish() {
			GltfModel.Primitive p = new GltfModel.Primitive();
			p.pos = Arrays.copyOf(pos, v * 3);
			p.nrm = Arrays.copyOf(nrm, v * 3);
			p.col = Arrays.copyOf(col, v * 4);
			p.idx = Arrays.copyOf(idx, i);
			p.baseColor = new float[] { 1f, 1f, 1f, 1f };
			GltfModel.Mesh m = new GltfModel.Mesh();
			m.name = "weapon";
			m.primitives = new GltfModel.Primitive[] { p };
			GltfModel g = new GltfModel();
			g.name = "weapon";
			g.nodes = new GltfModel.Node[0];
			g.skins = new GltfModel.Skin[0];
			g.meshes = new GltfModel.Mesh[] { m };
			g.ready = true;
			return g;
		}
	}

	// ------------------------------------------------------------------ weapon specs
	/** Describes one gun; everything is built from boxes along +Z (barrel). */
	private static final class Spec {
		float recBack = 0.18f, recFront = 0.30f;   // receiver extent behind / in front of the grip
		float hg = 0.22f;                          // handguard length
		float barrel = 0.24f;                      // exposed barrel
		float stock = 0.30f;                       // 0 = none
		int mag = 1;                               // 0 none, 1 straight, 2 curved (AK), 3 drum, 4 belt box, 5 tube
		float magLen = 0.11f;                      // magazine height
		int sight = 0;                             // 0 iron, 1 red dot, 2 short scope, 3 long scope
		boolean bipod, dbl, bolt, revolver, pistol, wood, pump, carry;
		float[] body = { 0.13f, 0.13f, 0.15f };
		float[] hgc = { 0.20f, 0.20f, 0.22f };
		float[] stk = { 0.18f, 0.14f, 0.10f };
	}

	private static Spec spec(int id) {
		Spec s = new Spec();
		switch (id) {
			case Weapon.M416:   s.sight = 1; s.mag = 1; break;
			case Weapon.AKM:    s.mag = 2; s.wood = true; s.hg = 0.24f; s.barrel = 0.20f; s.magLen = 0.16f; break;
			case Weapon.SCAR:   s.mag = 1; s.sight = 1; s.recFront = 0.34f; s.hg = 0.20f; s.body = new float[] { 0.36f, 0.30f, 0.20f }; s.hgc = new float[] { 0.34f, 0.28f, 0.19f }; s.stk = new float[] { 0.34f, 0.28f, 0.19f }; break;
			case Weapon.GROZA:  s.mag = 1; s.sight = 1; s.stock = 0.24f; s.hg = 0.16f; s.barrel = 0.14f; s.body = new float[] { 0.10f, 0.12f, 0.10f }; break;
			case Weapon.M762:   s.mag = 2; s.sight = 1; s.magLen = 0.15f; s.body = new float[] { 0.12f, 0.12f, 0.12f }; s.hgc = new float[] { 0.16f, 0.16f, 0.17f }; break;
			case Weapon.UZI:    s.pistol = true; s.stock = 0.0f; s.recBack = 0.10f; s.recFront = 0.22f; s.hg = 0.04f; s.barrel = 0.10f; s.magLen = 0.17f; s.mag = 1; break;
			case Weapon.UMP:    s.stock = 0.22f; s.recBack = 0.14f; s.recFront = 0.26f; s.hg = 0.10f; s.barrel = 0.14f; s.mag = 1; s.magLen = 0.15f; s.sight = 1; s.body = new float[] { 0.10f, 0.10f, 0.12f }; break;
			case Weapon.VECTOR: s.stock = 0.20f; s.recBack = 0.12f; s.recFront = 0.30f; s.hg = 0.06f; s.barrel = 0.10f; s.mag = 1; s.magLen = 0.14f; s.body = new float[] { 0.30f, 0.27f, 0.20f }; break;
			case Weapon.TOMMY:  s.stock = 0.26f; s.wood = true; s.recBack = 0.14f; s.recFront = 0.26f; s.hg = 0.12f; s.barrel = 0.20f; s.mag = 3; s.body = new float[] { 0.14f, 0.14f, 0.15f }; break;
			case Weapon.S686:   s.dbl = true; s.wood = true; s.mag = 0; s.recBack = 0.12f; s.recFront = 0.16f; s.hg = 0.20f; s.barrel = 0.34f; s.stock = 0.28f; s.body = new float[] { 0.30f, 0.30f, 0.32f }; s.hgc = new float[] { 0.36f, 0.22f, 0.12f }; break;
			case Weapon.S1897:  s.pump = true; s.wood = true; s.mag = 5; s.recBack = 0.14f; s.recFront = 0.20f; s.hg = 0.26f; s.barrel = 0.34f; s.stock = 0.30f; s.body = new float[] { 0.12f, 0.12f, 0.13f }; s.hgc = new float[] { 0.34f, 0.21f, 0.11f }; break;
			case Weapon.S12K:   s.mag = 1; s.magLen = 0.12f; s.recBack = 0.16f; s.recFront = 0.26f; s.hg = 0.20f; s.barrel = 0.20f; s.stock = 0.24f; s.body = new float[] { 0.12f, 0.12f, 0.14f }; break;
			case Weapon.KAR98:  s.bolt = true; s.wood = true; s.mag = 0; s.sight = 2; s.recBack = 0.12f; s.recFront = 0.22f; s.hg = 0.34f; s.barrel = 0.44f; s.stock = 0.34f; s.body = new float[] { 0.16f, 0.16f, 0.17f }; s.hgc = new float[] { 0.36f, 0.22f, 0.12f }; break;
			case Weapon.M24:    s.bolt = true; s.mag = 0; s.sight = 3; s.recBack = 0.12f; s.recFront = 0.24f; s.hg = 0.30f; s.barrel = 0.46f; s.stock = 0.34f; s.bipod = true; s.body = new float[] { 0.14f, 0.18f, 0.12f }; s.hgc = new float[] { 0.16f, 0.22f, 0.14f }; s.stk = new float[] { 0.14f, 0.20f, 0.12f }; break;
			case Weapon.AWM:    s.bolt = true; s.mag = 1; s.magLen = 0.07f; s.sight = 3; s.recBack = 0.14f; s.recFront = 0.26f; s.hg = 0.30f; s.barrel = 0.52f; s.stock = 0.36f; s.bipod = true; s.body = new float[] { 0.10f, 0.20f, 0.30f }; s.hgc = new float[] { 0.09f, 0.18f, 0.28f }; s.stk = new float[] { 0.09f, 0.18f, 0.28f }; break;
			case Weapon.SKS:    s.wood = true; s.mag = 1; s.magLen = 0.08f; s.sight = 2; s.recBack = 0.14f; s.recFront = 0.24f; s.hg = 0.30f; s.barrel = 0.28f; s.stock = 0.30f; s.hgc = new float[] { 0.36f, 0.22f, 0.12f }; break;
			case Weapon.MINI14: s.wood = true; s.mag = 1; s.magLen = 0.10f; s.sight = 2; s.recBack = 0.14f; s.recFront = 0.24f; s.hg = 0.28f; s.barrel = 0.26f; s.stock = 0.30f; s.hgc = new float[] { 0.30f, 0.20f, 0.12f }; break;
			case Weapon.SLR:    s.mag = 1; s.magLen = 0.11f; s.sight = 2; s.recBack = 0.14f; s.recFront = 0.28f; s.hg = 0.28f; s.barrel = 0.30f; s.stock = 0.30f; s.body = new float[] { 0.12f, 0.13f, 0.12f }; break;
			case Weapon.MK14:   s.mag = 1; s.magLen = 0.11f; s.sight = 3; s.bipod = true; s.recBack = 0.16f; s.recFront = 0.30f; s.hg = 0.30f; s.barrel = 0.30f; s.stock = 0.32f; s.body = new float[] { 0.13f, 0.12f, 0.10f }; break;
			case Weapon.M249:   s.mag = 4; s.sight = 1; s.bipod = true; s.carry = true; s.recBack = 0.16f; s.recFront = 0.32f; s.hg = 0.26f; s.barrel = 0.30f; s.stock = 0.30f; s.body = new float[] { 0.12f, 0.13f, 0.12f }; break;
			case Weapon.DP28:   s.mag = 3; s.bipod = true; s.wood = true; s.recBack = 0.14f; s.recFront = 0.26f; s.hg = 0.20f; s.barrel = 0.34f; s.stock = 0.30f; s.body = new float[] { 0.13f, 0.14f, 0.12f }; break;
			case Weapon.P92:    s.pistol = true; s.stock = 0f; s.recBack = 0.07f; s.recFront = 0.15f; s.hg = 0f; s.barrel = 0.05f; s.mag = 0; break;
			case Weapon.P1911:  s.pistol = true; s.stock = 0f; s.recBack = 0.07f; s.recFront = 0.16f; s.hg = 0f; s.barrel = 0.05f; s.mag = 0; s.body = new float[] { 0.30f, 0.30f, 0.32f }; break;
			case Weapon.R1895:  s.pistol = true; s.revolver = true; s.stock = 0f; s.recBack = 0.06f; s.recFront = 0.14f; s.hg = 0f; s.barrel = 0.14f; s.mag = 0; s.body = new float[] { 0.30f, 0.30f, 0.34f }; break;
			case Weapon.R45:    s.pistol = true; s.revolver = true; s.stock = 0f; s.recBack = 0.06f; s.recFront = 0.14f; s.hg = 0f; s.barrel = 0.20f; s.mag = 0; s.body = new float[] { 0.18f, 0.18f, 0.20f }; break;
			case Weapon.DEAGLE: s.pistol = true; s.stock = 0f; s.recBack = 0.08f; s.recFront = 0.18f; s.hg = 0f; s.barrel = 0.10f; s.mag = 0; s.body = new float[] { 0.62f, 0.60f, 0.55f }; break;
			default: break;
		}
		return s;
	}

	private static GltfModel build(Spec s) {
		B b = new B();
		float[] c = s.body;
		float top = s.pistol ? 0.050f : 0.060f;
		float rb = -s.recBack, rf = s.recFront;
		// receiver / slide
		b.box(-0.030f, -0.045f, rb, 0.030f, top, rf, c[0], c[1], c[2]);
		if (s.pistol && !s.revolver) b.box(-0.020f, -0.030f, rb + 0.01f, 0.020f, 0.000f, rf - 0.01f, c[0] * 1.3f, c[1] * 1.3f, c[2] * 1.3f); // frame
		float z = rf;
		// handguard
		if (s.hg > 0f) {
			if (s.dbl) {
				b.box(-0.034f, -0.020f, z, 0.034f, 0.040f, z + s.hg, s.hgc[0], s.hgc[1], s.hgc[2]);
			} else if (s.pump) {
				b.box(-0.030f, -0.032f, z, 0.030f, 0.020f, z + s.hg, s.hgc[0], s.hgc[1], s.hgc[2]);   // pump forend
			} else {
				b.box(-0.032f, -0.010f, z, 0.032f, 0.050f, z + s.hg, s.hgc[0], s.hgc[1], s.hgc[2]);
			}
			z += s.hg;
		}
		// barrel(s)
		float bEnd = z + s.barrel;
		if (s.dbl) {
			b.box(-0.026f, 0.014f, z - s.hg, -0.002f, 0.042f, bEnd, 0.08f, 0.08f, 0.09f);
			b.box(0.002f, 0.014f, z - s.hg, 0.026f, 0.042f, bEnd, 0.08f, 0.08f, 0.09f);
		} else if (s.revolver) {
			b.box(-0.026f, 0.000f, rf - 0.02f, 0.026f, 0.052f, rf + 0.07f, 0.34f, 0.34f, 0.37f);   // cylinder
			b.box(-0.010f, 0.020f, rf + 0.05f, 0.010f, 0.044f, bEnd, 0.07f, 0.07f, 0.08f);
		} else {
			b.box(-0.011f, 0.016f, z - 0.02f, 0.011f, 0.042f, bEnd, 0.06f, 0.06f, 0.07f);
			if (!s.pistol) b.box(-0.016f, 0.010f, bEnd - 0.04f, 0.016f, 0.048f, bEnd + 0.02f, 0.05f, 0.05f, 0.05f); // muzzle brake
		}
		if (s.pump) b.box(-0.014f, -0.012f, rf, 0.014f, 0.012f, bEnd - 0.04f, 0.10f, 0.10f, 0.11f);   // magazine tube
		// stock
		if (s.stock > 0f) {
			float[] k = s.wood ? new float[] { 0.34f, 0.21f, 0.11f } : s.stk;
			b.box(-0.026f, -0.070f, rb - s.stock, 0.026f, 0.050f, rb, k[0], k[1], k[2]);
			b.box(-0.030f, -0.075f, rb - s.stock - 0.02f, 0.030f, 0.055f, rb - s.stock, 0.06f, 0.06f, 0.06f); // butt pad
		}
		// pistol grip
		float gy = s.pistol ? -0.130f : -0.150f;
		float[] gc = s.wood && s.pistol ? new float[] { 0.34f, 0.21f, 0.11f } : new float[] { 0.12f, 0.12f, 0.13f };
		b.box(-0.019f, gy, rb + 0.01f, 0.019f, -0.040f, rb + 0.08f, gc[0], gc[1], gc[2]);
		// magazine
		float mz0 = Math.min(0.10f, rf - 0.12f);
		switch (s.mag) {
			case 1: b.box(-0.020f, -0.040f - s.magLen, mz0, 0.020f, -0.040f, mz0 + 0.06f, 0.10f, 0.10f, 0.11f); break;
			case 2: b.box(-0.020f, -0.040f - s.magLen * 0.55f, mz0, 0.020f, -0.040f, mz0 + 0.06f, 0.14f, 0.12f, 0.10f);
					b.box(-0.020f, -0.040f - s.magLen, mz0 + 0.03f, 0.020f, -0.040f - s.magLen * 0.55f, mz0 + 0.09f, 0.14f, 0.12f, 0.10f); break;
			case 3: b.box(-0.050f, -0.150f, mz0 - 0.02f, 0.050f, -0.040f, mz0 + 0.10f, 0.16f, 0.16f, 0.17f);
					b.box(-0.040f, -0.165f, mz0 - 0.005f, 0.040f, -0.150f, mz0 + 0.085f, 0.10f, 0.10f, 0.11f); break;
			case 4: b.box(-0.045f, -0.140f, mz0 - 0.01f, 0.045f, -0.040f, mz0 + 0.12f, 0.22f, 0.24f, 0.18f); break;
			default: break;
		}
		// sights / optics
		if (s.sight == 0) {
			b.box(-0.012f, top, rb + 0.06f, 0.012f, top + 0.030f, rb + 0.14f, 0.08f, 0.08f, 0.09f);
			b.box(-0.008f, 0.042f, bEnd - 0.12f, 0.008f, 0.072f, bEnd - 0.08f, 0.08f, 0.08f, 0.09f);
		} else if (s.sight == 1) {
			b.box(-0.020f, top, rb + 0.10f, 0.020f, top + 0.050f, rb + 0.20f, 0.07f, 0.07f, 0.08f);
			b.box(-0.014f, top + 0.012f, rb + 0.195f, 0.014f, top + 0.044f, rb + 0.205f, 0.7f, 0.1f, 0.1f);
		} else {
			float len = s.sight == 3 ? 0.30f : 0.20f, hw = s.sight == 3 ? 0.026f : 0.022f;
			float z0 = rb + 0.04f;
			b.box(-hw, top + 0.030f, z0, hw, top + 0.030f + hw * 2f, z0 + len, 0.07f, 0.07f, 0.08f);
			b.box(-hw - 0.006f, top + 0.024f, z0 - 0.02f, hw + 0.006f, top + 0.036f + hw * 2f, z0, 0.04f, 0.05f, 0.08f);       // ocular
			b.box(-hw - 0.008f, top + 0.022f, z0 + len, hw + 0.008f, top + 0.038f + hw * 2f, z0 + len + 0.03f, 0.05f, 0.08f, 0.14f);  // objective
			b.box(-0.008f, top, z0 + 0.03f, 0.008f, top + 0.032f, z0 + 0.05f, 0.08f, 0.08f, 0.09f);
			b.box(-0.008f, top, z0 + len - 0.05f, 0.008f, top + 0.032f, z0 + len - 0.03f, 0.08f, 0.08f, 0.09f);
		}
		if (s.carry) b.box(-0.006f, top, rf - 0.20f, 0.006f, top + 0.050f, rf - 0.04f, 0.08f, 0.08f, 0.09f);   // carry handle
		if (s.bolt) {
			b.box(0.028f, 0.020f, rb + 0.02f, 0.075f, 0.036f, rb + 0.05f, 0.12f, 0.12f, 0.13f);               // bolt handle
			b.box(0.068f, 0.012f, rb + 0.015f, 0.090f, 0.044f, rb + 0.055f, 0.20f, 0.20f, 0.22f);             // bolt knob
		}
		if (s.bipod) {
			float bz = z - 0.04f;
			b.box(-0.050f, -0.090f, bz, -0.040f, -0.010f, bz + 0.015f, 0.08f, 0.08f, 0.09f);
			b.box(0.040f, -0.090f, bz, 0.050f, -0.010f, bz + 0.015f, 0.08f, 0.08f, 0.09f);
		}
		return b.finish();
	}

	/** Drops the Java references only (GL context is gone). */
	public static void drop() {
		java.util.Arrays.fill(MODELS, null);
	}

	/** Call on the GL thread (ModelCache.init). */
	public static void init() {
		drop();
		for (int id = 0; id < Weapon.COUNT; id++) {
			Spec s = spec(id);
			MODELS[id] = build(s);
			float fg = s.pistol ? 0.02f : (s.dbl || s.pump ? s.recFront + s.hg * 0.55f : s.recFront + Math.max(0.04f, s.hg * 0.5f));
			GRIP_R[id] = new float[] { 0f, -0.01f, 0.00f };
			GRIP_L[id] = new float[] { 0f, s.pistol ? -0.04f : -0.03f, fg };
		}
		for (GltfModel m : MODELS) if (m != null) GltfUploader.upload(m);
	}

	public static void release() {
		for (int i = 0; i < MODELS.length; i++) {
			if (MODELS[i] != null) { MODELS[i].release(); MODELS[i] = null; }
		}
	}

	public static GltfModel get(int type) {
		return (type >= 0 && type < MODELS.length) ? MODELS[type] : null;
	}
}
