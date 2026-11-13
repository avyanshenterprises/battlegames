package com.avyansh.battleroyale;

import java.util.Arrays;
import java.util.Random;

public class MeshBuilder {
	public static boolean REALISTIC = true;
	
	public static final int FLOATS = 11;
	private static final int MAX_VERTS = 200_000;
	private static final int GROW_BLOCK = 2048;
	
	public float[] v = new float[FLOATS * 3 * 1024];
	public int vc = 0;
	private float ocx, ocy, ocz;
	
	public void setCenter(float x, float y, float z) { ocx = x; ocy = y; ocz = z; }
	
	// ==================================================================
	// Public convenience wrappers (UNCHANGED)
	// ==================================================================
	
	public void addBox(float cx, float cy, float cz, float hx, float hy, float hz,
	float r, float g, float b) {
		box(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz, r, g, b);
	}
	
	public void addCylinder(float cx, float by, float cz, float rad, float h, int sides,
	float r, float g, float b) {
		cylinder(cx, by, cz, rad, h, sides, r, g, b);
	}
	
	public void addTrunk(float cx, float by, float cz, float radBase, float radTop, float h,
	int sides, float r, float g, float b) {
		taperedCylinder(cx, by, cz, radBase, radTop, h, sides, r, g, b);
		if (REALISTIC) {
			for (int i = 0; i < sides; i++) {
				float a0 = (float) (6.2831853 * i / sides);
				float a1 = (float) (6.2831853 * (i + 1) / sides);
				float ox = (float) Math.cos(a0) * radBase * 1.4f;
				float oz = (float) Math.sin(a0) * radBase * 1.4f;
				float nx = (float) Math.cos(a1) * radBase * 1.4f;
				float nz = (float) Math.sin(a1) * radBase * 1.4f;
				tri(cx + ox, by - 0.15f, cz + oz,
				cx + nx, by - 0.15f, cz + nz,
				cx + nx * 0.85f, by + 0.4f, cz + nz * 0.85f,
				r * 0.85f, g * 0.85f, b * 0.85f);
			}
		}
	}
	
	public void addCone(float cx, float by, float cz, float rad, float h, int sides,
	float r, float g, float b) {
		cone(cx, by, cz, rad, h, sides, r, g, b);
	}
	
	public void addCone(float cx, float by, float cz, float rad, float h, int sides,
	float rotY, float r, float g, float b) {
		setCenter(cx, by + h * 0.25f, cz);
		for (int i = 0; i < sides; i++) {
			float a0 = rotY + (float) (6.2831853 * i / sides);
			float a1 = rotY + (float) (6.2831853 * (i + 1) / sides);
			tri(cx + (float) Math.cos(a0) * rad, by, cz + (float) Math.sin(a0) * rad,
			cx + (float) Math.cos(a1) * rad, by, cz + (float) Math.sin(a1) * rad,
			cx, by + h, cz, r, g, b);
		}
		if (REALISTIC) {
			for (int i = 0; i < sides; i++) {
				float a0 = rotY + (float) (6.2831853 * i / sides);
				float a1 = rotY + (float) (6.2831853 * (i + 1) / sides);
				tri(cx, by, cz,
				cx + (float) Math.cos(a0) * rad, by, cz + (float) Math.sin(a0) * rad,
				cx + (float) Math.cos(a1) * rad, by, cz + (float) Math.sin(a1) * rad,
				r * 0.8f, g * 0.8f, b * 0.8f);
			}
		}
	}
	
	public void addRock(float x, float y, float z, float size, int seed,
	float r, float g, float b) {
		if (!REALISTIC) {
			float jitter = ((seed * 2654435761L) & 0xFF) / 255f;
			diamond(x, y + size * 0.1f, z, size, size * (0.7f + 0.3f * jitter),
			size * 0.5f, 5, r, g, b);
			return;
		}
		Random rnd = new Random(seed * 0x9E3779B97F4A7C15L);
		addJitteredRock(x, y + size * 0.15f, z, size, 9, rnd, r, g, b);
		float ox = (rnd.nextFloat() - 0.5f) * size * 1.4f;
		float oz = (rnd.nextFloat() - 0.5f) * size * 1.4f;
		addJitteredRock(x + ox, y + size * 0.05f, z + oz, size * 0.55f, 7, rnd,
		r * 0.92f, g * 0.92f, b * 0.92f);
	}
	
	public void addPart(float x, float y, float z, float yaw, float tilt,
	float offX, float offY, float offZ,
	float pitch, float roll,
	float cy, float cx,
	float hx, float hy, float hz,
	float r, float g, float b) {
		
		float sy = (float) Math.sin(yaw), cyw = (float) Math.cos(yaw);
		float st = (float) Math.sin(tilt), ct = (float) Math.cos(tilt);
		float sp = (float) Math.sin(pitch), cp = (float) Math.cos(pitch);
		float sr = (float) Math.sin(roll),  cr = (float) Math.cos(roll);
		
		float[] lx = { cx - hx, cx + hx, cx + hx, cx - hx, cx - hx, cx + hx, cx + hx, cx - hx };
		float[] ly = { cy - hy, cy - hy, cy + hy, cy + hy, cy - hy, cy - hy, cy + hy, cy + hy };
		float[] lz = { -hz, -hz, -hz, -hz, hz, hz, hz, hz };
		
		float[] wx = new float[8], wy = new float[8], wz = new float[8];
		for (int i = 0; i < 8; i++) {
			float px = lx[i], py = ly[i], pz = lz[i];
			float xr = px * cr - py * sr;
			float yr = px * sr + py * cr;
			float yp = yr * cp - pz * sp;
			float zp = yr * sp + pz * cp;
			float bx = offX + xr;
			float by = offY + yp;
			float bz = offZ + zp;
			float yt = by * ct - bz * st;
			float zt = by * st + bz * ct;
			wx[i] = x + bx * cyw + zt * sy;
			wy[i] = y + yt;
			wz[i] = z - bx * sy + zt * cyw;
		}
		
		int[][] faces = {
			{0,1,2,3}, {4,7,6,5}, {0,4,5,1},
			{3,2,6,7}, {0,3,7,4}, {1,5,6,2}
		};
		for (int[] f : faces) {
			quad(wx[f[0]], wy[f[0]], wz[f[0]],
			wx[f[1]], wy[f[1]], wz[f[1]],
			wx[f[2]], wy[f[2]], wz[f[2]],
			wx[f[3]], wy[f[3]], wz[f[3]], r, g, b);
		}
	}
	
	public void triGrass(float ax, float ay, float az,
	float bx, float by, float bz,
	float cx, float cy, float cz,
	float r, float g, float b) {
		if (!REALISTIC) {
			pv(ax, ay, az, 0f, 1f, 0f, r, g, b);
			pv(bx, by, bz, 0f, 1f, 0f, r, g, b);
			pv(cx, cy, cz, 0f, 1f, 0f, r, g, b);
			return;
		}
		float mx = (ax + bx) * 0.5f, my = (ay + by) * 0.5f, mz = (az + bz) * 0.5f;
		float wx = (bx - ax) * 0.5f, wz = (bz - az) * 0.5f;
		float halfH = cy - my;
		float tipY = my + halfH;
		tri(ax, ay, az, bx, by, bz, cx, tipY, cz, r, g, b);
		tri(mx - wz, my, mz + wx, mx + wz, my, mz - wx, cx, tipY, cz,
		r * 1.08f, g * 1.08f, b * 0.9f);
	}
	
	// ==================================================================
	// Primitive writers (UNCHANGED, small tweak to MAX_VERTS)
	// ==================================================================
	
	private void ensure(int addVerts) {
		int need = (vc + addVerts) * FLOATS;
		if (need > v.length) {
			int target = v.length;
			while (target < need) target += GROW_BLOCK * FLOATS;
			v = Arrays.copyOf(v, target);
		}
	}
	
	private void pv(float x, float y, float z, float nx, float ny, float nz,
	float r, float g, float b) {
		if (vc >= MAX_VERTS) return;
		ensure(1);
		int o = vc * FLOATS;
		v[o]     = x; v[o + 1] = y; v[o + 2] = z;
		v[o + 3] = nx; v[o + 4] = ny; v[o + 5] = nz;
		v[o + 6] = r; v[o + 7] = g; v[o + 8] = b;
		v[o + 9] = x * 0.1f; v[o + 10] = z * 0.1f;
		vc++;
	}
	
	public void tri(float ax, float ay, float az, float bx, float by, float bz,
	float cx, float cy, float cz, float r, float g, float b) {
		if (vc + 3 > MAX_VERTS) return;
		ensure(3);
		
		float ux = bx - ax, uy = by - ay, uz = bz - az;
		float wx = cx - ax, wy = cy - ay, wz = cz - az;
		float nx = uy * wz - uz * wy;
		float ny = uz * wx - ux * wz;
		float nz = ux * wy - uy * wx;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1e-6f) return;
		nx /= len; ny /= len; nz /= len;
		
		float mx = (ax + bx + cx) / 3f - ocx;
		float my = (ay + by + cy) / 3f - ocy;
		float mz = (az + bz + cz) / 3f - ocz;
		if (nx * mx + ny * my + nz * mz < 0f) {
			float tx = bx, ty = by, tz = bz;
			bx = cx; by = cy; bz = cz;
			cx = tx; cy = ty; cz = tz;
			nx = -nx; ny = -ny; nz = -nz;
		}
		
		float ja = 1f + ((ax * 12.9898f + ay * 78.233f + az * 37.719f) % 1f) * 0.06f - 0.03f;
		float jb = 1f + ((bx * 12.9898f + by * 78.233f + bz * 37.719f) % 1f) * 0.06f - 0.03f;
		float jc = 1f + ((cx * 12.9898f + cy * 78.233f + cz * 37.719f) % 1f) * 0.06f - 0.03f;
		
		pv(ax, ay, az, nx, ny, nz, r * ja, g * ja, b * ja);
		pv(bx, by, bz, nx, ny, nz, r * jb, g * jb, b * jb);
		pv(cx, cy, cz, nx, ny, nz, r * jc, g * jc, b * jc);
	}
	
	private void quad(float ax, float ay, float az, float bx, float by, float bz,
	float cx, float cy, float cz, float dx, float dy, float dz,
	float r, float g, float b) {
		tri(ax, ay, az, bx, by, bz, cx, cy, cz, r, g, b);
		tri(ax, ay, az, cx, cy, cz, dx, dy, dz, r, g, b);
	}
	
	// ==================================================================
	// High-level shapes (UNCHANGED)
	// ==================================================================
	
	public void box(float x0, float y0, float z0, float x1, float y1, float z1,
	float r, float g, float b) {
		setCenter((x0 + x1) * 0.5f, (y0 + y1) * 0.5f, (z0 + z1) * 0.5f);
		quad(x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, r, g, b);
		quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b);
		quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b);
		quad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, r, g, b);
		quad(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, r, g, b);
		quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b);
	}
	
	public void roof(float x0, float x1, float z0, float z1, float y, float h,
	float r, float g, float b) {
		float ax = (x0 + x1) * 0.5f, az = (z0 + z1) * 0.5f, ay = y + h;
		setCenter(ax, y + h * 0.3f, az);
		tri(x0, y, z0, x1, y, z0, ax, ay, az, r, g, b);
		tri(x1, y, z0, x1, y, z1, ax, ay, az, r, g, b);
		tri(x1, y, z1, x0, y, z1, ax, ay, az, r, g, b);
		tri(x0, y, z1, x0, y, z0, ax, ay, az, r, g, b);
		if (REALISTIC) {
			float tr = r * 0.55f, tg = g * 0.55f, tb = b * 0.55f;
			quad(x0, y - 0.08f, z0, x1, y - 0.08f, z0, x1, y, z0, x0, y, z0, tr, tg, tb);
			quad(x1, y - 0.08f, z1, x0, y - 0.08f, z1, x0, y, z1, x1, y, z1, tr, tg, tb);
			quad(x0, y - 0.08f, z1, x0, y - 0.08f, z0, x0, y, z0, x0, y, z1, tr, tg, tb);
			quad(x1, y - 0.08f, z0, x1, y - 0.08f, z1, x1, y, z1, x1, y, z0, tr, tg, tb);
		}
	}
	
	public void cone(float cx, float by, float cz, float rad, float h, int sides,
	float r, float g, float b) {
		setCenter(cx, by + h * 0.25f, cz);
		for (int i = 0; i < sides; i++) {
			float a0 = (float) (6.2831853 * i / sides);
			float a1 = (float) (6.2831853 * (i + 1) / sides);
			tri(cx + (float) Math.cos(a0) * rad, by, cz + (float) Math.sin(a0) * rad,
			cx + (float) Math.cos(a1) * rad, by, cz + (float) Math.sin(a1) * rad,
			cx, by + h, cz, r, g, b);
		}
		if (REALISTIC) {
			for (int i = 0; i < sides; i++) {
				float a0 = (float) (6.2831853 * i / sides);
				float a1 = (float) (6.2831853 * (i + 1) / sides);
				tri(cx, by, cz,
				cx + (float) Math.cos(a0) * rad, by, cz + (float) Math.sin(a0) * rad,
				cx + (float) Math.cos(a1) * rad, by, cz + (float) Math.sin(a1) * rad,
				r * 0.8f, g * 0.8f, b * 0.8f);
			}
		}
	}
	
	public void cylinder(float cx, float by, float cz, float rad, float h, int sides,
	float r, float g, float b) {
		setCenter(cx, by + h * 0.5f, cz);
		for (int i = 0; i < sides; i++) {
			float a0 = (float) (6.2831853 * i / sides);
			float a1 = (float) (6.2831853 * (i + 1) / sides);
			float x0 = cx + (float) Math.cos(a0) * rad, z0 = cz + (float) Math.sin(a0) * rad;
			float x1 = cx + (float) Math.cos(a1) * rad, z1 = cz + (float) Math.sin(a1) * rad;
			tri(x0, by, z0, x1, by, z1, x1, by + h, z1, r, g, b);
			tri(x0, by, z0, x1, by + h, z1, x0, by + h, z0, r, g, b);
		}
		if (REALISTIC) {
			for (int i = 0; i < sides; i++) {
				float a0 = (float) (6.2831853 * i / sides);
				float a1 = (float) (6.2831853 * (i + 1) / sides);
				float x0 = cx + (float) Math.cos(a0) * rad, z0 = cz + (float) Math.sin(a0) * rad;
				float x1 = cx + (float) Math.cos(a1) * rad, z1 = cz + (float) Math.sin(a1) * rad;
				tri(cx, by + h, cz, x0, by + h, z0, x1, by + h, z1, r, g, b);
				tri(cx, by, cz, x1, by, z1, x0, by, z0, r * 0.85f, g * 0.85f, b * 0.85f);
			}
		}
	}
	
	public void taperedCylinder(float cx, float by, float cz,
	float r0, float r1, float h, int sides,
	float r, float g, float b) {
		setCenter(cx, by + h * 0.5f, cz);
		for (int i = 0; i < sides; i++) {
			float a0 = (float) (6.2831853 * i / sides);
			float a1 = (float) (6.2831853 * (i + 1) / sides);
			float xa0 = cx + (float) Math.cos(a0) * r0, za0 = cz + (float) Math.sin(a0) * r0;
			float xa1 = cx + (float) Math.cos(a1) * r0, za1 = cz + (float) Math.sin(a1) * r0;
			float xb0 = cx + (float) Math.cos(a0) * r1, zb0 = cz + (float) Math.sin(a0) * r1;
			float xb1 = cx + (float) Math.cos(a1) * r1, zb1 = cz + (float) Math.sin(a1) * r1;
			tri(xa0, by, za0, xa1, by, za1, xb1, by + h, zb1, r, g, b);
			tri(xa0, by, za0, xb1, by + h, zb1, xb0, by + h, zb0, r, g, b);
		}
		if (REALISTIC) {
			for (int i = 0; i < sides; i++) {
				float a0 = (float) (6.2831853 * i / sides);
				float a1 = (float) (6.2831853 * (i + 1) / sides);
				tri(cx, by + h, cz,
				cx + (float) Math.cos(a0) * r1, by + h, cz + (float) Math.sin(a0) * r1,
				cx + (float) Math.cos(a1) * r1, by + h, cz + (float) Math.sin(a1) * r1,
				r * 0.9f, g * 0.9f, b * 0.9f);
			}
		}
	}
	
	public void diamond(float cx, float cy, float cz, float rad, float hu, float hd, int sides,
	float r, float g, float b) {
		setCenter(cx, cy, cz);
		for (int i = 0; i < sides; i++) {
			float a0 = (float) (6.2831853 * i / sides);
			float a1 = (float) (6.2831853 * (i + 1) / sides);
			float x0 = cx + (float) Math.cos(a0) * rad, z0 = cz + (float) Math.sin(a0) * rad;
			float x1 = cx + (float) Math.cos(a1) * rad, z1 = cz + (float) Math.sin(a1) * rad;
			tri(x0, cy, z0, x1, cy, z1, cx, cy + hu, cz, r, g, b);
			tri(x0, cy, z0, x1, cy, z1, cx, cy - hd, cz, r * 0.8f, g * 0.8f, b * 0.8f);
		}
	}
	
	private void addJitteredRock(float cx, float cy, float cz, float size, int sides,
	Random rnd, float r, float g, float b) {
		setCenter(cx, cy, cz);
		float[] ringY = { 0.45f, -0.30f };
		float[] ringR = { 0.85f, 0.95f };
		int n = sides;
		float[][] ringX = new float[2][n];
		float[][] ringZ = new float[2][n];
		for (int band = 0; band < 2; band++) {
			for (int i = 0; i < n; i++) {
				float ang = (float) (6.2831853 * i / n);
				float jit = 0.85f + rnd.nextFloat() * 0.3f;
				ringX[band][i] = cx + (float) Math.cos(ang) * size * ringR[band] * jit;
				ringZ[band][i] = cz + (float) Math.sin(ang) * size * ringR[band] * jit;
			}
		}
		for (int band = 0; band < 1; band++) {
			for (int i = 0; i < n; i++) {
				int j = (i + 1) % n;
				float x0 = ringX[band][i],   z0 = ringZ[band][i];
				float x1 = ringX[band][j],   z1 = ringZ[band][j];
				float x2 = ringX[band+1][j], z2 = ringZ[band+1][j];
				float x3 = ringX[band+1][i], z3 = ringZ[band+1][i];
				float y0 = cy + size * ringY[band];
				float y1 = cy + size * ringY[band+1];
				quad(x0, y0, z0, x1, y0, z1, x2, y1, z2, x3, y1, z3, r, g, b);
			}
		}
		float apexY = cy + size * 0.95f;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			tri(ringX[0][i], cy + size * ringY[0], ringZ[0][i],
			ringX[0][j], cy + size * ringY[0], ringZ[0][j],
			cx, apexY, cz, r * 1.05f, g * 1.05f, b * 1.05f);
		}
		float botY = cy + size * -0.65f;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			tri(ringX[1][i], cy + size * ringY[1], ringZ[1][i],
			ringX[1][j], cy + size * ringY[1], ringZ[1][j],
			cx, botY, cz, r * 0.7f, g * 0.7f, b * 0.7f);
		}
	}
	
	// ==================================================================
	// Legacy building (kept for backward compatibility)
	// ==================================================================
	
	/** Flat decal on a wall. face: 0=front(+Z) 1=back(-Z) 2=right(+X) 3=left(-X). */
	private void panel(int face, float a0, float a1, float ylo, float yhi, float plane,
	float r, float g, float b) {
		if (face < 2) quad(a0, ylo, plane, a1, ylo, plane, a1, yhi, plane, a0, yhi, plane, r, g, b);
		else          quad(plane, ylo, a0, plane, ylo, a1, plane, yhi, a1, plane, yhi, a0, r, g, b);
	}
	
	private void windowDecal(int face, float c, float cy, float plane, boolean shutters, Building bd) {
		float hw = 0.55f, hh = 0.72f;
		float s = (face == 0 || face == 3) ? 1f : -1f;   // outward direction sign is baked in 'plane'
		float o = (face == 0 || face == 2) ? 1f : -1f;
		float[] tr = bd.trim, ac = bd.accent;
		panel(face, c - hw - 0.10f, c + hw + 0.10f, cy - hh - 0.10f, cy + hh + 0.10f, plane + 0.03f * o, tr[0], tr[1], tr[2]);
		panel(face, c - hw, c + hw, cy - hh, cy + hh, plane + 0.06f * o, 0.035f, 0.045f, 0.055f);
		panel(face, c - hw - 0.20f, c + hw + 0.20f, cy - hh - 0.20f, cy - hh - 0.08f, plane + 0.08f * o, tr[0] * 0.8f, tr[1] * 0.8f, tr[2] * 0.8f);
		if (shutters) {
			panel(face, c - hw - 0.55f, c - hw - 0.12f, cy - hh, cy + hh, plane + 0.04f * o, ac[0], ac[1], ac[2]);
			panel(face, c + hw + 0.12f, c + hw + 0.55f, cy - hh, cy + hh, plane + 0.04f * o, ac[0], ac[1], ac[2]);
		}
	}
	
	private void gableRoof(float x0, float x1, float z0, float z1, float y, float h,
	float r, float g, float b, float wr, float wg, float wb) {
		float o = 0.5f;
		float xc = (x0 + x1) * 0.5f, zc = (z0 + z1) * 0.5f;
		boolean alongX = (x1 - x0) >= (z1 - z0);
		setCenter(xc, y - 2f, zc);
		if (alongX) {
			float xa = x0 - o, xb = x1 + o, ze0 = z0 - o, ze1 = z1 + o;
			quad(xa, y, ze0, xb, y, ze0, xb, y + h, zc, xa, y + h, zc, r, g, b);
			quad(xa, y, ze1, xb, y, ze1, xb, y + h, zc, xa, y + h, zc, r * 0.92f, g * 0.92f, b * 0.92f);
			tri(x0, y, z0, x0, y, z1, x0, y + h, zc, wr, wg, wb);
			tri(x1, y, z0, x1, y, z1, x1, y + h, zc, wr, wg, wb);
			box(xa, y + h - 0.08f, zc - 0.15f, xb, y + h + 0.10f, zc + 0.15f, r * 0.7f, g * 0.7f, b * 0.7f);
		} else {
			float za = z0 - o, zb = z1 + o, xe0 = x0 - o, xe1 = x1 + o;
			quad(xe0, y, za, xe0, y, zb, xc, y + h, zb, xc, y + h, za, r, g, b);
			quad(xe1, y, za, xe1, y, zb, xc, y + h, zb, xc, y + h, za, r * 0.92f, g * 0.92f, b * 0.92f);
			tri(x0, y, z0, x1, y, z0, xc, y + h, z0, wr, wg, wb);
			tri(x0, y, z1, x1, y, z1, xc, y + h, z1, wr, wg, wb);
			box(xc - 0.15f, y + h - 0.08f, za, xc + 0.15f, y + h + 0.10f, zb, r * 0.7f, g * 0.7f, b * 0.7f);
		}
	}
	
	/** Box on a wall: u0..u1 along the wall, d0..d1 outward from the outer plane. face: 0=+Z 1=-Z 2=+X 3=-X. */
	private void winBox(int face, float plane, float u0, float u1, float y0, float y1, float d0, float d1,
	float r, float g, float b) {
		switch (face) {
			case 0: box(u0, y0, plane + d0, u1, y1, plane + d1, r, g, b); break;
			case 1: box(u0, y0, plane - d1, u1, y1, plane - d0, r, g, b); break;
			case 2: box(plane + d0, y0, u0, plane + d1, y1, u1, r, g, b); break;
			default: box(plane - d1, y0, u0, plane - d0, y1, u1, r, g, b); break;
		}
	}
	
	public void building(Building bd) {
		final float T  = Building.WALL_T;
		final float dh = Building.DOOR_W * 0.5f;
		float x0 = bd.x - bd.w * 0.5f, x1 = bd.x + bd.w * 0.5f;
		float z0 = bd.z - bd.d * 0.5f, z1 = bd.z + bd.d * 0.5f;
		float y0 = bd.baseY, y1 = bd.baseY + bd.h;
		float doorH = bd.doorH();
		float wr = bd.wall[0], wg = bd.wall[1], wb = bd.wall[2];
		float tr = bd.trim[0], tg = bd.trim[1], tb = bd.trim[2];
		float ar = bd.accent[0], ag = bd.accent[1], ab = bd.accent[2];
		int floors = Math.max(1, bd.floors);
		float floorH = bd.h / floors;
		float fr = 0.52f, fg = 0.38f, fb = 0.26f;   // wooden floors
		
		// ---------- exterior shell ----------
		box(x0 + T, y0 + 0.50f, z0 + T, x1 - T, bd.groundTop, z1 - T, fr * 0.9f, fg * 0.9f, fb * 0.9f);
		// walls: exactly the collision pieces; the gaps are the door and the open windows
		for (int i = 0; i < bd.wallBoxes.size(); i++) {
			float[] w = bd.wallBoxes.get(i);
			box(w[0], w[1], w[2], w[3], w[4], w[5], wr, wg, wb);
		}
		// ceiling under the roof
		box(x0 + T, y1 - 0.25f, z0 + T, x1 - T, y1 - 0.02f, z1 - T, wr * 0.92f, wg * 0.92f, wb * 0.92f);
		
		// ---------- upper floor slabs (with stairwell hole) ----------
		for (int k = 0; k < bd.floorTops.length; k++) {
			float top = bd.floorTops[k];
			float[][] parts = bd.slabParts[k];
			for (int i = 0; i < parts.length; i++) {
				float[] p = parts[i];
				box(p[0], top - 0.20f, p[1], p[2], top, p[3], fr, fg, fb);
			}
		}
		
		// ---------- stairs (solid treads, exactly the collision boxes) ----------
		for (int i = 0; i < bd.stairSteps.size(); i++) {
			FloorStep s = bd.stairSteps.get(i);
			float shade = (i & 1) == 0 ? 1.0f : 0.90f;
			box(s.x0, s.topY - s.thickness, s.z0, s.x1, s.topY, s.z1, 0.60f * shade, 0.45f * shade, 0.30f * shade);
		}
		
		// ---------- painted facade decals (cheap: flat quads) ----------
		setCenter(bd.x, y0 + bd.h * 0.5f, bd.z);
		float pl = 0.9f;
		// plinth
		panel(1, x0, x1, y0 - 0.3f, y0 + pl, z0 - 0.03f, tr * 0.6f, tg * 0.6f, tb * 0.6f);
		panel(2, z0, z1, y0 - 0.3f, y0 + pl, x1 + 0.03f, tr * 0.6f, tg * 0.6f, tb * 0.6f);
		panel(3, z0, z1, y0 - 0.3f, y0 + pl, x0 - 0.03f, tr * 0.6f, tg * 0.6f, tb * 0.6f);
		panel(0, x0, bd.x - dh, y0 - 0.3f, y0 + pl, z1 + 0.03f, tr * 0.6f, tg * 0.6f, tb * 0.6f);
		panel(0, bd.x + dh, x1, y0 - 0.3f, y0 + pl, z1 + 0.03f, tr * 0.6f, tg * 0.6f, tb * 0.6f);
		// corner pilasters
		float pw = 0.8f;
		panel(0, x0, x0 + pw, y0, y1, z1 + 0.04f, tr, tg, tb);
		panel(0, x1 - pw, x1, y0, y1, z1 + 0.04f, tr, tg, tb);
		panel(1, x0, x0 + pw, y0, y1, z0 - 0.04f, tr, tg, tb);
		panel(1, x1 - pw, x1, y0, y1, z0 - 0.04f, tr, tg, tb);
		panel(2, z0, z0 + pw, y0, y1, x1 + 0.04f, tr, tg, tb);
		panel(2, z1 - pw, z1, y0, y1, x1 + 0.04f, tr, tg, tb);
		panel(3, z0, z0 + pw, y0, y1, x0 - 0.04f, tr, tg, tb);
		panel(3, z1 - pw, z1, y0, y1, x0 - 0.04f, tr, tg, tb);
		// belt courses between floors + cornice
		for (int f = 1; f < floors; f++) {
			float by = y0 + f * floorH;
			panel(0, x0, x1, by - 0.15f, by + 0.15f, z1 + 0.035f, tr * 0.95f, tg * 0.95f, tb * 0.95f);
			panel(1, x0, x1, by - 0.15f, by + 0.15f, z0 - 0.035f, tr * 0.95f, tg * 0.95f, tb * 0.95f);
		}
		panel(0, x0, x1, y1 - 0.5f, y1, z1 + 0.045f, tr, tg, tb);
		panel(1, x0, x1, y1 - 0.5f, y1, z0 - 0.045f, tr, tg, tb);
		panel(2, z0, z1, y1 - 0.5f, y1, x1 + 0.045f, tr, tg, tb);
		panel(3, z0, z1, y1 - 0.5f, y1, x0 - 0.045f, tr, tg, tb);
		// door frame (accent colour) + small awning
		panel(0, bd.x - dh - 0.25f, bd.x - dh, y0, y0 + doorH + 0.25f, z1 + 0.05f, ar, ag, ab);
		panel(0, bd.x + dh, bd.x + dh + 0.25f, y0, y0 + doorH + 0.25f, z1 + 0.05f, ar, ag, ab);
		panel(0, bd.x - dh - 0.25f, bd.x + dh + 0.25f, y0 + doorH, y0 + doorH + 0.25f, z1 + 0.05f, ar, ag, ab);
		box(bd.x - dh - 0.5f, y0 + doorH + 0.30f, z1, bd.x + dh + 0.5f, y0 + doorH + 0.42f, z1 + 1.0f,
		ar * 1.1f, ag * 1.1f, ab * 1.1f);
		
		// ---------- open windows: frame, sill and swung-open shutters around every opening ----------
		setCenter(bd.x, y0 + bd.h * 0.5f, bd.z);
		for (int i = 0; i < bd.windows.size(); i++) {
			float[] w = bd.windows.get(i);
			int face = (int) w[0];
			float c = w[1], lo = w[2], hi = w[3], hw = w[4];
			float plane = face == 0 ? z1 : face == 1 ? z0 : face == 2 ? x1 : x0;
			float fw = 0.10f;
			winBox(face, plane, c - hw - 0.14f, c + hw + 0.14f, lo - 0.08f, lo + 0.03f, 0f, 0.24f, tr * 0.88f, tg * 0.88f, tb * 0.88f);   // sill ledge
			winBox(face, plane, c - hw - fw, c + hw + fw, hi, hi + 0.12f, 0f, 0.09f, tr, tg, tb);                                       // head
			winBox(face, plane, c - hw - fw, c - hw, lo, hi, 0f, 0.08f, tr, tg, tb);                                                    // jambs
			winBox(face, plane, c + hw, c + hw + fw, lo, hi, 0f, 0.08f, tr, tg, tb);
			// shutters swung open 90 degrees, standing out from the wall
			winBox(face, plane, c - hw - fw - 0.05f, c - hw - fw, lo - 0.02f, hi + 0.02f, 0.06f, 0.06f + hw * 0.95f, ar, ag, ab);
			winBox(face, plane, c + hw + fw, c + hw + fw + 0.05f, lo - 0.02f, hi + 0.02f, 0.06f, 0.06f + hw * 0.95f, ar, ag, ab);
		}
		
		// ---------- roof ----------
		if (bd.roofStyle == 1 && bd.roofH > 0f) {
			gableRoof(x0, x1, z0, z1, y1, bd.roofH, bd.roof[0], bd.roof[1], bd.roof[2], wr, wg, wb);
			// chimney
			box(bd.x + bd.w * 0.25f, y1 + 0.5f, bd.z - 0.4f, bd.x + bd.w * 0.25f + 0.8f, y1 + bd.roofH + 0.8f, bd.z + 0.4f,
			0.55f, 0.30f, 0.24f);
		} else if (bd.roofH > 0f) {
			roof(x0 - 0.5f, x1 + 0.5f, z0 - 0.5f, z1 + 0.5f, y1, bd.roofH, bd.roof[0], bd.roof[1], bd.roof[2]);
		} else {
			box(x0 - 0.3f, y1, z0 - 0.3f, x1 + 0.3f, y1 + 0.15f, z1 + 0.3f, bd.roof[0], bd.roof[1], bd.roof[2]);
			box(bd.x - 1.2f, y1 + 0.15f, bd.z - 1.2f, bd.x + 1.2f, y1 + 1.2f, bd.z + 1.2f,
			bd.roof[0] * 1.15f, bd.roof[1] * 1.15f, bd.roof[2] * 1.15f);
		}
	}
	
	/** Raw vertex with a given normal (used by baked OBJ tree models). */
	public void vertex(float x, float y, float z, float nx, float ny, float nz,
	float r, float g, float b) {
		pv(x, y, z, nx, ny, nz, r, g, b);
	}
	
	/**
* Very cheap far-LOD tree: 4-sided trunk + two 6-sided cones (~32 triangles).
*/	
	public void addLowPolyTree(float x, float y, float z, float height, float radius, float tint, int seed) {
		float trunkH = height * 0.42f;
		float trunkR = Math.max(0.30f, height * 0.05f);
		setCenter(x, y + trunkH * 0.5f, z);
		float rot = (seed & 255) * 0.0245f;
		for (int i = 0; i < 4; i++) {
			float a0 = rot + i * 1.5707963f, a1 = rot + (i + 1) * 1.5707963f;
			float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
			float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
			quad(x + c0 * trunkR, y, z + s0 * trunkR,
			x + c1 * trunkR, y, z + s1 * trunkR,
			x + c1 * trunkR * 0.5f, y + trunkH, z + s1 * trunkR * 0.5f,
			x + c0 * trunkR * 0.5f, y + trunkH, z + s0 * trunkR * 0.5f,
			0.31f, 0.20f, 0.10f);
		}
		float[] sc = speciesColor(seed, tint);
		float gr = sc[0], gg = sc[1], gb = sc[2];
		float cy0 = y + trunkH * 0.55f;
		float span = height - trunkH * 0.55f;
		addCone(x, cy0, z, radius, span * 0.60f, 6, rot, gr, gg, gb);
		addCone(x, cy0 + span * 0.42f, z, radius * 0.62f, span * 0.58f, 6, rot + 0.5f,
		gr * 1.1f, gg * 1.1f, gb * 1.1f);
	}
	
	/**
* Bake one instance of the custom tree OBJ (TreeModel) into this mesh.
* The model is unit height; height is in metres.
*/	
	public void addModelTree(float x, float y, float z, float height, float yaw, float tint) {
		addModelTree(x, y, z, height, yaw, tint, 0);
	}
	
	/**
* Same, but picks one of the loaded tree models by 'seed' and varies width, height and colour
* per tree so a forest never looks copy-pasted.
*/	
	public void addModelTree(float x, float y, float z, float height, float yaw, float tint, int seed) {
		if (!TreeModel.ready || TreeModel.variantN == 0) return;
		int h = seed * 0x9E3779B1;
		h ^= (h >>> 15);
		int vi = (h & 0x7fffffff) % TreeModel.variantN;
		float[] mv = TreeModel.variants[vi];
		int n = TreeModel.variantCounts[vi];
		if (vc + n > MAX_VERTS) return;
		float r1 = ((h >>> 3) & 255) / 255f, r2 = ((h >>> 11) & 255) / 255f, r3 = ((h >>> 19) & 255) / 255f;
		float hs = 0.80f + 0.45f * r1;                 // taller / shorter
		float ws = 0.80f + 0.50f * r2;                 // slimmer / fatter
		float cr = 1f + 0.16f * (r3 - 0.5f) * 2f;      // hue drift: warmer <-> cooler greens
		float cb = 1f - 0.14f * (r3 - 0.5f) * 2f;
		float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
		float sH = height * hs, sW = height * ws;
		for (int i = 0; i < n; i++) {
			int o = i * FLOATS;
			float px = mv[o] * sW, py = mv[o + 1] * sH, pz = mv[o + 2] * sW;
			float wx = x + px * cy + pz * sy;
			float wz = z - px * sy + pz * cy;
			float nx = mv[o + 3] * cy + mv[o + 5] * sy;
			float nz = -mv[o + 3] * sy + mv[o + 5] * cy;
			pv(wx, y + py, wz, nx, mv[o + 4], nz,
			Math.min(1f, mv[o + 6] * tint * cr), Math.min(1f, mv[o + 7] * tint), Math.min(1f, mv[o + 8] * tint * cb));
		}
	}
	
	private void addWindow(float cx, float cy, float cz, float w, float h, float yawOff,
	float r, float g, float b) {
		float hw = w * 0.5f, hh = h * 0.5f;
		box(cx - hw, cy - hh, cz - 0.02f, cx + hw, cy + hh, cz + 0.02f, r, g, b);
	}
	
	// ==================================================================
	// NEW: Realistic giant tree
	// ==================================================================
	
	/** 0 pine, 1 oak, 2 birch, 3 autumn - chosen per tree from its position seed. */
	static int species(int seed) {
		int h = seed * 0x85EBCA6B;
		h ^= (h >>> 13);
		int r = (h & 0x7fffffff) % 100;
		return r < 40 ? 0 : (r < 68 ? 1 : (r < 86 ? 2 : 3));
	}
	
	/** Canopy colour for the tree's species (with a little per-tree variation). */
	static float[] speciesColor(int seed, float tint) {
		switch (species(seed)) {
			case 1:  return new float[] { 0.14f + 0.08f * tint, 0.36f + 0.20f * tint, 0.09f + 0.04f * tint };
			case 2:  return new float[] { 0.30f + 0.10f * tint, 0.50f + 0.16f * tint, 0.16f + 0.05f * tint };
			case 3:  return new float[] { 0.62f + 0.20f * tint, 0.30f + 0.18f * tint, 0.07f + 0.04f * tint };
			default: return new float[] { 0.08f + 0.08f * tint, 0.26f + 0.20f * tint, 0.10f + 0.04f * tint };
		}
	}
	
	// Procedural high-detail trees: buttress roots, tapered bark, branch structure and layered crowns.
	// The same deterministic seed is used for every chunk rebuild, so the forest does not pop/change.
	private void addBranchCylinder(float ax, float ay, float az, float bx, float by, float bz,
	float r0, float r1, int sides, float cr, float cg, float cb) {
		float dx = bx - ax, dy = by - ay, dz = bz - az;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 0.02f) return;
		dx /= len; dy /= len; dz /= len;
		float ux = -dz, uy = 0f, uz = dx;
		float ul = (float) Math.sqrt(ux * ux + uz * uz);
		if (ul < 0.001f) { ux = 1f; uy = 0f; uz = 0f; ul = 1f; }
		ux /= ul; uz /= ul;
		float vx = dy * uz - dz * uy, vy = dz * ux - dx * uz, vz = dx * uy - dy * ux;
		setCenter((ax + bx) * 0.5f, (ay + by) * 0.5f, (az + bz) * 0.5f);
		for (int i = 0; i < sides; i++) {
			float a0 = (float) (6.2831853 * i / sides), a1 = (float) (6.2831853 * (i + 1) / sides);
			float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
			float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
			float x00 = ax + (ux * c0 + vx * s0) * r0, y00 = ay + (uy * c0 + vy * s0) * r0, z00 = az + (uz * c0 + vz * s0) * r0;
			float x01 = ax + (ux * c1 + vx * s1) * r0, y01 = ay + (uy * c1 + vy * s1) * r0, z01 = az + (uz * c1 + vz * s1) * r0;
			float x10 = bx + (ux * c0 + vx * s0) * r1, y10 = by + (uy * c0 + vy * s0) * r1, z10 = bz + (uz * c0 + vz * s0) * r1;
			float x11 = bx + (ux * c1 + vx * s1) * r1, y11 = by + (uy * c1 + vy * s1) * r1, z11 = bz + (uz * c1 + vz * s1) * r1;
			tri(x00,y00,z00, x01,y01,z01, x11,y11,z11, cr,cg,cb);
			tri(x00,y00,z00, x11,y11,z11, x10,y10,z10, cr*0.88f,cg*0.88f,cb*0.88f);
		}
	}

	public void addRealisticTree(float x, float y, float z, float height, float radius, float tint, int seed) {
		Random rnd = new Random(seed * 0x9E3779B97F4A7C15L);
		int kind = species(seed); // 0 pine, 1 oak, 2 birch, 3 autumn
		float trunkH = height * (kind == 1 ? 0.36f : (kind == 2 ? 0.48f : 0.43f));
		float trunkRad = Math.max(0.22f, height * (kind == 2 ? 0.027f : 0.038f));
		float barkR = kind == 2 ? 0.78f : 0.30f;
		float barkG = kind == 2 ? 0.77f : 0.19f;
		float barkB = kind == 2 ? 0.70f : 0.10f;
		float[] crown = speciesColor(seed, tint);
		float cr = crown[0], cg = crown[1], cb = crown[2];

		// Flared root buttresses make the trunk sit naturally in the terrain.
		for (int i = 0; i < 6; i++) {
			float a = (float) (i * Math.PI / 3.0) + rnd.nextFloat() * 0.22f;
			float reach = trunkRad * (2.0f + rnd.nextFloat() * 1.2f);
			float ex = x + (float) Math.cos(a) * reach;
			float ez = z + (float) Math.sin(a) * reach;
			addBranchCylinder(x, y + 0.05f, z, ex, y + trunkH * 0.22f, ez,
				trunkRad * 0.46f, trunkRad * 0.10f, 5, barkR * 0.88f, barkG * 0.82f, barkB * 0.76f);
		}

		// Main trunk and subtle vertical bark ridges.
		taperedCylinder(x, y, z, trunkRad * 1.18f, trunkRad * 0.42f, trunkH, 9, barkR, barkG, barkB);
		for (int i = 0; i < 5; i++) {
			float a = (float) (i * 6.2831853 / 5.0) + 0.18f;
			float ox = (float) Math.cos(a) * trunkRad * 0.94f;
			float oz = (float) Math.sin(a) * trunkRad * 0.94f;
			addBranchCylinder(x + ox, y + trunkH * 0.08f, z + oz,
				x + ox * 0.43f, y + trunkH * 0.94f, z + oz * 0.43f,
				trunkRad * 0.055f, trunkRad * 0.018f, 3,
				barkR * 1.16f, barkG * 1.10f, barkB * 1.02f);
		}
		if (kind == 2) { // birch: characteristic dark horizontal scars on pale bark
			for (int i = 0; i < 7; i++) {
				float yy = y + trunkH * (0.12f + i * 0.105f);
				float a = rnd.nextFloat() * 6.2831853f;
				float rr = trunkRad * (0.92f - 0.35f * yy / Math.max(0.01f, y + trunkH));
				float px = x + (float) Math.cos(a) * rr, pz = z + (float) Math.sin(a) * rr;
				addBranchCylinder(px, yy, pz, px + (float) Math.cos(a + 1.1f) * trunkRad * 0.30f,
				yy + trunkH * 0.012f, pz + (float) Math.sin(a + 1.1f) * trunkRad * 0.30f,
				trunkRad * 0.045f, trunkRad * 0.025f, 3, 0.16f, 0.15f, 0.13f);
			}
		}

		float crownBase = y + trunkH * (kind == 0 ? 0.58f : 0.76f);
		float crownH = Math.max(1f, y + height - crownBase);
		float crownR = radius * (kind == 0 ? 0.72f : 0.88f);

		// Real branch scaffolding, tapering toward the tips.
		int branchCount = kind == 0 ? 8 : 7;
		for (int i = 0; i < branchCount; i++) {
			float a = (float) (i * 6.2831853 / branchCount) + rnd.nextFloat() * 0.35f;
			float f = 0.32f + rnd.nextFloat() * 0.43f;
			float sy = crownBase + crownH * f;
			float len = crownR * (kind == 0 ? (0.48f + f * 0.24f) : (0.50f + rnd.nextFloat() * 0.28f));
			float ex = x + (float) Math.cos(a) * len;
			float ez = z + (float) Math.sin(a) * len;
			float ey = sy + len * (kind == 0 ? 0.26f : 0.16f);
			addBranchCylinder(x, sy, z, ex, ey, ez, trunkRad * 0.42f, trunkRad * 0.09f,
				5, barkR * 0.94f, barkG * 0.90f, barkB * 0.84f);
			if (kind != 0) {
				for (int j = 0; j < 3; j++) {
					float t = 0.48f + j * 0.20f;
					float bx = x + (ex - x) * t, bz = z + (ez - z) * t, by = sy + (ey - sy) * t;
					float side = (j - 1) * 0.24f;
					float tx = bx - (float) Math.sin(a) * side, tz = bz + (float) Math.cos(a) * side;
					addBranchCylinder(bx, by, bz, tx, by + crownH * 0.08f, tz,
						trunkRad * 0.13f, trunkRad * 0.035f, 4, barkR, barkG, barkB);
				}
			}
		}

		if (kind == 0) {
			// Layered conifer silhouette with overlapping bough tiers.
			int tiers = 5;
			for (int i = 0; i < tiers; i++) {
				float f = i / (float) tiers;
				float by = crownBase + crownH * f * 0.82f;
				float rr = crownR * (1f - f * 0.82f);
				addCone(x, by, z, rr, crownH * (0.34f - f * 0.08f), 8,
					(float) (i * 0.31f), cr * (0.88f + f * 0.10f), cg * (0.90f + f * 0.10f), cb);
			}
		} else {
			// Broadleaf crowns: overlapping small foliage masses, not one faceted blob.
			int clusters = REALISTIC ? 30 : 14;
			for (int i = 0; i < clusters; i++) {
				float a = rnd.nextFloat() * 6.2831853f;
				float f = 0.08f + rnd.nextFloat() * 0.86f;
				float radial = (float) Math.sqrt(rnd.nextFloat()) * crownR * (1f - f * 0.20f);
				float lx = x + (float) Math.cos(a) * radial;
				float lz = z + (float) Math.sin(a) * radial;
				float ly = crownBase + f * crownH;
				float size = crownR * (0.18f + rnd.nextFloat() * 0.17f);
				float shade = 0.84f + rnd.nextFloat() * 0.30f;
				diamond(lx, ly, lz, size, size * (0.65f + rnd.nextFloat() * 0.35f), size * 0.55f,
					6, Math.min(1f, cr * shade), Math.min(1f, cg * shade), Math.min(1f, cb * shade));
			}
			// A small crown cap closes the top silhouette.
			diamond(x, crownBase + crownH * 0.82f, z, crownR * 0.34f, crownH * 0.16f, crownH * 0.10f,
				6, cr * 0.92f, cg * 1.04f, cb * 0.92f);
		}
	}

	// ==================================================================
	// NEW: Multi-floor house with windows, door, chimney
	// ==================================================================
	
	public void addHouse(float x, float z, float baseY,
	float w, float d, float wallH, int floors,
	float wallR, float wallG, float wallB,
	float roofR, float roofG, float roofB,
	float roofH, int seed) {
		
		Random rnd = new Random(seed * 0x2545F4914F6CDD1DL);
		
		float x0 = x - w * 0.5f, x1 = x + w * 0.5f;
		float z0 = z - d * 0.5f, z1 = z + d * 0.5f;
		float y0 = baseY;
		float y1 = baseY + wallH;
		
		// Foundation
		box(x0 - 0.20f, y0 - 0.35f, z0 - 0.20f, x1 + 0.20f, y0 + 0.15f, z1 + 0.20f, 0.32f, 0.30f, 0.28f);
		
		// Outer Walls (Solid for simplicity, door hole left)
		float doorW = 1.5f;
		float doorH = 2.2f;
		float t = 0.4f; // Wall thickness
		
		// Build walls with a door hole
		box(x0, y0, z0, x1, y1, z0 + t, wallR, wallG, wallB); // Back
		box(x0, y0, z0, x0 + t, y1, z1, wallR, wallG, wallB); // Left
		box(x1 - t, y0, z0, x1, y1, z1, wallR, wallG, wallB); // Right
		
		// Front wall with door
		box(x0, y0, z1 - t, x - doorW/2, y1, z1, wallR, wallG, wallB);
		box(x + doorW/2, y0, z1 - t, x1, y1, z1, wallR, wallG, wallB);
		box(x - doorW/2, y0 + doorH, z1 - t, x + doorW/2, y1, z1, wallR, wallG, wallB); // Lintel
		
		// Roof (Peaked)
		roof(x0 - 0.5f, x1 + 0.5f, z0 - 0.5f, z1 + 0.5f, y1, roofH, roofR, roofG, roofB);
		
		// Floors and Stairs
		for (int f = 1; f < floors; f++) {
			float floorY = y0 + (wallH / floors) * f;
			// Floor slab for upper level
			box(x0 + t, floorY - 0.2f, z0 + t, x1 - t, floorY, z1 - t, 0.4f, 0.35f, 0.3f);
			
			// Stairs (Simple block steps in the corner)
			int steps = 6;
			float stepHeight = (floorY - y0) / steps;
			float stepDepth = 0.6f;
			float stairX = x1 - t - 2f; // Position stairs at right-back
			float stairZ = z0 + t;
			
			for (int s = 0; s < steps; s++) {
				float sy = y0 + s * stepHeight;
				float sz = stairZ + s * stepDepth;
				box(stairX, sy, sz, stairX + 1.5f, sy + stepHeight, sz + stepDepth, 0.55f, 0.55f, 0.55f);
			}
		}
		
		// Windows (Visual only)
		float wy = y0 + 1.5f;
		addWindow(x0 + 1.5f, wy, z1 - 0.05f, 1f, 1.2f, 0, 0.1f, 0.1f, 0.2f);
		addWindow(x1 - 1.5f, wy, z0 + 0.05f, 1f, 1.2f, 0, 0.1f, 0.1f, 0.2f);
	}
	
	// ==================================================================
	// NEW: Tree stump (small filler prop)
	// ==================================================================
	
	public void addTreeStump(float x, float y, float z, float radius, float height) {
		// Ring of bark
		cylinder(x, y - 0.05f, z, radius, height, 8,
		0.32f, 0.20f, 0.11f);
		// Top cut face (light wood)
		addCone(x, y + height, z, radius * 0.95f, 0.02f, 8,
		0.62f, 0.48f, 0.32f);
		// Root nubs
		for (int i = 0; i < 4; i++) {
			float ang = (float) (i * 1.5707f + 0.4f);
			float rx = (float) Math.cos(ang) * radius * 1.4f;
			float rz = (float) Math.sin(ang) * radius * 1.4f;
			addCone(x + rx, y - 0.1f, z + rz, radius * 0.45f, height * 0.6f, 5,
			0.30f, 0.19f, 0.10f);
		}
	}
	
	// ==================================================================
	// NEW: Big boulder (for terrain decorations)
	// ==================================================================
	
	public void addBoulder(float x, float y, float z, float size, int seed,
	float r, float g, float b) {
		Random rnd = new Random(seed * 0x94D049BB133111EBL);
		// Two overlapping big rocks + one small companion
		addJitteredRock(x, y + size * 0.25f, z, size, 10, rnd, r, g, b);
		float ox = (rnd.nextFloat() - 0.5f) * size * 1.6f;
		float oz = (rnd.nextFloat() - 0.5f) * size * 1.6f;
		addJitteredRock(x + ox, y + size * 0.10f, z + oz, size * 0.65f, 8, rnd,
		r * 0.92f, g * 0.92f, b * 0.92f);
		if (REALISTIC) {
			float ox2 = (rnd.nextFloat() - 0.5f) * size * 2.2f;
			float oz2 = (rnd.nextFloat() - 0.5f) * size * 2.2f;
			addJitteredRock(x + ox2, y + size * 0.05f, z + oz2, size * 0.40f, 7, rnd,
			r * 0.85f, g * 0.85f, b * 0.85f);
		}
	}
}
