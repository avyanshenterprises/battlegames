package com.avyansh.battleroyale;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public final class Effects {
	private static final int MAX = 360;
	private static final int MAX_TRACERS = 24;

	private final float[] px = new float[MAX], py = new float[MAX], pz = new float[MAX];
	private final float[] vx = new float[MAX], vy = new float[MAX], vz = new float[MAX];
	private final float[] life = new float[MAX], maxLife = new float[MAX];
	private final float[] s0 = new float[MAX], s1 = new float[MAX];
	private final float[] cr = new float[MAX], cg = new float[MAX], cb = new float[MAX], ca = new float[MAX];
	private final float[] grav = new float[MAX], drag = new float[MAX], floorY = new float[MAX];
	private final boolean[] additive = new boolean[MAX];
	private int count = 0;

	private final float[] tx0 = new float[MAX_TRACERS], ty0 = new float[MAX_TRACERS], tz0 = new float[MAX_TRACERS];
	private final float[] tx1 = new float[MAX_TRACERS], ty1 = new float[MAX_TRACERS], tz1 = new float[MAX_TRACERS];
	private final float[] tAge = new float[MAX_TRACERS], tLen = new float[MAX_TRACERS];
	private int tracerCount = 0;
	private static final float TRACER_SPEED = 260f;

	private final java.util.Random rnd = new java.util.Random(7);

	private static final String VS =
	"uniform mat4 uVP;\n" +
	"attribute vec3 aPos; attribute vec2 aUv; attribute vec4 aColor;\n" +
	"varying vec2 vUv; varying vec4 vC;\n" +
	"void main(){ vUv = aUv; vC = aColor; gl_Position = uVP * vec4(aPos, 1.0); }\n";
	private static final String FS = ShaderUtil.FRAG_HEADER +
	"varying vec2 vUv; varying vec4 vC;\n" +
	"void main(){\n" +
	"  float d = length(vUv - 0.5) * 2.0;\n" +
	"  float f = clamp(1.0 - d, 0.0, 1.0);\n" +
	"  f = f * f * (3.0 - 2.0 * f);\n" +
	"  gl_FragColor = vec4(vC.rgb, vC.a * f);\n" +
	"}\n";

	private int program = 0, aPos, aUv, aColor, uVP;
	private FloatBuffer buf = ByteBuffer.allocateDirect((MAX + MAX_TRACERS) * 6 * 9 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
	private final float[] verts = new float[(MAX + MAX_TRACERS) * 6 * 9];

	public void init() {
		program = ShaderUtil.createProgram(VS, FS);
		aPos = GLES20.glGetAttribLocation(program, "aPos");
		aUv = GLES20.glGetAttribLocation(program, "aUv");
		aColor = GLES20.glGetAttribLocation(program, "aColor");
		uVP = GLES20.glGetUniformLocation(program, "uVP");
	}

	private int alloc() {
		if (count >= MAX) {
			System.arraycopy(px, 1, px, 0, MAX - 1); System.arraycopy(py, 1, py, 0, MAX - 1); System.arraycopy(pz, 1, pz, 0, MAX - 1);
			System.arraycopy(vx, 1, vx, 0, MAX - 1); System.arraycopy(vy, 1, vy, 0, MAX - 1); System.arraycopy(vz, 1, vz, 0, MAX - 1);
			System.arraycopy(life, 1, life, 0, MAX - 1); System.arraycopy(maxLife, 1, maxLife, 0, MAX - 1);
			System.arraycopy(s0, 1, s0, 0, MAX - 1); System.arraycopy(s1, 1, s1, 0, MAX - 1);
			System.arraycopy(cr, 1, cr, 0, MAX - 1); System.arraycopy(cg, 1, cg, 0, MAX - 1);
			System.arraycopy(cb, 1, cb, 0, MAX - 1); System.arraycopy(ca, 1, ca, 0, MAX - 1);
			System.arraycopy(grav, 1, grav, 0, MAX - 1); System.arraycopy(drag, 1, drag, 0, MAX - 1);
			System.arraycopy(floorY, 1, floorY, 0, MAX - 1); System.arraycopy(additive, 1, additive, 0, MAX - 1);
			count = MAX - 1;
		}
		return count++;
	}

	private void spawn(float x, float y, float z, float vX, float vY, float vZ, float lifeT,
	float size0, float size1, float r, float g, float b, float a,
	float gravity, float dragK, float floor, boolean add) {
		int i = alloc();
		px[i] = x; py[i] = y; pz[i] = z; vx[i] = vX; vy[i] = vY; vz[i] = vZ;
		life[i] = lifeT; maxLife[i] = lifeT; s0[i] = size0; s1[i] = size1;
		cr[i] = r; cg[i] = g; cb[i] = b; ca[i] = a;
		grav[i] = gravity; drag[i] = dragK; floorY[i] = floor; additive[i] = add;
	}

	private float rf() { return rnd.nextFloat() - 0.5f; }

	public void blood(float x, float y, float z, float dx, float dy, float dz, int n, float floor) {
		for (int i = 0; i < n; i++) {
			float sp = 1.5f + rnd.nextFloat() * 4.5f;
			float vX = dx * sp * 0.7f + rf() * 4f;
			float vY = dy * sp * 0.5f + 1.2f + rnd.nextFloat() * 2.8f;
			float vZ = dz * sp * 0.7f + rf() * 4f;
			float red = 0.45f + rnd.nextFloat() * 0.35f;
			float sz = 0.06f + rnd.nextFloat() * 0.10f;
			spawn(x, y, z, vX, vY, vZ, 0.45f + rnd.nextFloat() * 0.4f, sz, sz * 0.4f,
			red, 0.02f, 0.02f, 0.95f, 13f, 0.4f, floor, false);
		}
		for (int i = 0; i < 3; i++) {
			spawn(x, y, z, dx * 0.8f + rf() * 0.6f, 0.3f + rnd.nextFloat() * 0.5f, dz * 0.8f + rf() * 0.6f,
			0.35f, 0.25f, 0.9f, 0.55f, 0.03f, 0.03f, 0.40f, 0f, 2.5f, floor, false);
		}
	}

	public void impact(float x, float y, float z, float nx, float ny, float nz) {
		for (int i = 0; i < 6; i++) {
			spawn(x, y, z, nx * 1.2f + rf() * 1.8f, 1.0f + rnd.nextFloat() * 1.6f, nz * 1.2f + rf() * 1.8f,
			0.55f + rnd.nextFloat() * 0.35f, 0.35f, 0.9f, 0.55f, 0.48f, 0.38f, 0.45f, -1.0f, 1.6f, -1000f, false);
		}
		for (int i = 0; i < 4; i++) {
			spawn(x, y, z, nx * 3f + rf() * 5f, 1.5f + rnd.nextFloat() * 3f, nz * 3f + rf() * 5f,
			0.18f, 0.06f, 0.02f, 1.0f, 0.75f, 0.35f, 1.0f, 9f, 0.5f, -1000f, true);
		}
	}

	public void muzzle(float x, float y, float z, float dx, float dy, float dz) {
		spawn(x, y, z, 0f, 0f, 0f, 0.055f, 0.45f, 0.18f, 1.0f, 0.82f, 0.42f, 1.0f, 0f, 0f, -1000f, true);
		spawn(x + dx * 0.25f, y + dy * 0.25f, z + dz * 0.25f, 0f, 0f, 0f, 0.045f, 0.25f, 0.10f, 1.0f, 0.95f, 0.75f, 1.0f, 0f, 0f, -1000f, true);
		spawn(x, y, z, dx * 1.2f + rf() * 0.4f, 0.5f, dz * 1.2f + rf() * 0.4f, 0.7f, 0.10f, 0.45f, 0.70f, 0.70f, 0.70f, 0.30f, -0.5f, 1.5f, -1000f, false);
	}

	/** Water splash – bright white-blue droplets that arc up and out. */
	public void waterSplash(float x, float y, float z, float strength) {
		int n = (int) (10 * strength + 6);
		for (int i = 0; i < n; i++) {
			float sp = (1.5f + rnd.nextFloat() * 3.5f) * strength;
			float vX = rf() * sp * 2.5f;
			float vY = 2.0f + rnd.nextFloat() * 3.5f * strength;
			float vZ = rf() * sp * 2.5f;
			float sz = 0.06f + rnd.nextFloat() * 0.12f;
			spawn(x + rf() * 0.4f, y, z + rf() * 0.4f, vX, vY, vZ,
				0.55f + rnd.nextFloat() * 0.35f, sz, sz * 0.4f,
				0.85f, 0.92f, 1.0f, 0.85f, 9f, 0.3f, y - 2f, false);
		}
	}

	/** Ground dust ring – brown puffs at the feet. */
	public void groundDust(float x, float y, float z, float strength) {
		int n = (int) (6 * strength + 4);
		for (int i = 0; i < n; i++) {
			float ang = rnd.nextFloat() * 6.2831853f;
			float sp = (0.8f + rnd.nextFloat() * 1.5f) * strength;
			float vX = (float) Math.cos(ang) * sp;
			float vY = 0.4f + rnd.nextFloat() * 0.9f;
			float vZ = (float) Math.sin(ang) * sp;
			spawn(x + rf() * 0.3f, y + 0.1f, z + rf() * 0.3f, vX, vY, vZ,
				0.55f + rnd.nextFloat() * 0.35f, 0.12f, 0.35f,
				0.55f, 0.48f, 0.38f, 0.45f, 3f, 1.2f, y - 0.5f, false);
		}
	}

	public void tracer(float x0, float y0, float z0, float x1, float y1, float z1) {
		if (tracerCount >= MAX_TRACERS) return;
		int i = tracerCount++;
		tx0[i] = x0; ty0[i] = y0; tz0[i] = z0; tx1[i] = x1; ty1[i] = y1; tz1[i] = z1;
		float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
		tLen[i] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		tAge[i] = 0f;
	}

	public void update(float dt) {
		for (int i = 0; i < count; ) {
			life[i] -= dt;
			float k = 1f - drag[i] * dt; if (k < 0f) k = 0f;
			vx[i] *= k; vz[i] *= k; vy[i] = vy[i] * k - grav[i] * dt;
			px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * dt;
			if (life[i] <= 0f || py[i] < floorY[i]) { remove(i); } else i++;
		}
		for (int i = 0; i < tracerCount; ) {
			tAge[i] += dt;
			if (tAge[i] * TRACER_SPEED > tLen[i] + 8f) {
				int l = tracerCount - 1;
				tx0[i] = tx0[l]; ty0[i] = ty0[l]; tz0[i] = tz0[l];
				tx1[i] = tx1[l]; ty1[i] = ty1[l]; tz1[i] = tz1[l];
				tAge[i] = tAge[l]; tLen[i] = tLen[l];
				tracerCount--;
			} else i++;
		}
	}

	private void remove(int i) {
		int l = count - 1;
		px[i] = px[l]; py[i] = py[l]; pz[i] = pz[l]; vx[i] = vx[l]; vy[i] = vy[l]; vz[i] = vz[l];
		life[i] = life[l]; maxLife[i] = maxLife[l]; s0[i] = s0[l]; s1[i] = s1[l];
		cr[i] = cr[l]; cg[i] = cg[l]; cb[i] = cb[l]; ca[i] = ca[l];
		grav[i] = grav[l]; drag[i] = drag[l]; floorY[i] = floorY[l]; additive[i] = additive[l];
		count--;
	}

	private int vc;

	private void put(float x, float y, float z, float u, float v, float r, float g, float b, float a) {
		int o = vc * 9;
		verts[o] = x; verts[o + 1] = y; verts[o + 2] = z; verts[o + 3] = u; verts[o + 4] = v;
		verts[o + 5] = r; verts[o + 6] = g; verts[o + 7] = b; verts[o + 8] = a;
		vc++;
	}

	private void quad(float ax, float ay, float az, float bx, float by, float bz,
	float cx, float cy, float cz, float dx, float dy, float dz,
	float r, float g, float b, float a, float uMid) {
		put(ax, ay, az, 0f, 0f, r, g, b, a);
		put(bx, by, bz, 1f, 0f, r, g, b, a);
		put(cx, cy, cz, 1f, 1f, r, g, b, a);
		put(ax, ay, az, 0f, 0f, r, g, b, a);
		put(cx, cy, cz, 1f, 1f, r, g, b, a);
		put(dx, dy, dz, 0f, 1f, r, g, b, a);
	}

	public void render(GameCamera cam) {
		if (program == 0 || (count == 0 && tracerCount == 0)) return;

		float rX = cam.rx, rZ = cam.rz;
		float uX = cam.ux, uY = cam.uy, uZ = cam.uz;

		GLES20.glUseProgram(program);
		GLES20.glUniformMatrix4fv(uVP, 1, false, cam.vp, 0);
		GLES20.glEnable(GLES20.GL_DEPTH_TEST);
		GLES20.glDepthMask(false);
		GLES20.glDisable(GLES20.GL_CULL_FACE);
		GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

		for (int pass = 0; pass < 2; pass++) {
			boolean add = pass == 1;
			vc = 0;
			for (int i = 0; i < count; i++) {
				if (additive[i] != add) continue;
				float t = 1f - life[i] / maxLife[i];
				float s = (s0[i] + (s1[i] - s0[i]) * t) * 0.5f;
				float a = ca[i] * (1f - t * t);
				float hx = rX * s, hz = rZ * s, ux = uX * s, uy = uY * s, uz = uZ * s;
				quad(px[i] - hx - ux, py[i] - uy, pz[i] - hz - uz,
				px[i] + hx - ux, py[i] - uy, pz[i] + hz - uz,
				px[i] + hx + ux, py[i] + uy, pz[i] + hz + uz,
				px[i] - hx + ux, py[i] + uy, pz[i] - hz + uz,
				cr[i], cg[i], cb[i], a, 0.5f);
			}
			if (add) {
				for (int i = 0; i < tracerCount; i++) {
					float head = Math.min(tAge[i] * TRACER_SPEED, tLen[i]);
					float tail = Math.max(0f, tAge[i] * TRACER_SPEED - 7f);
					if (head <= tail + 0.01f || tLen[i] < 0.5f) continue;
					float inv = 1f / tLen[i];
					float dx = (tx1[i] - tx0[i]) * inv, dy = (ty1[i] - ty0[i]) * inv, dz = (tz1[i] - tz0[i]) * inv;
					float ax = tx0[i] + dx * tail, ay = ty0[i] + dy * tail, az = tz0[i] + dz * tail;
					float bx = tx0[i] + dx * head, by = ty0[i] + dy * head, bz = tz0[i] + dz * head;
					float mx = (ax + bx) * 0.5f - cam.pos[0], my = (ay + by) * 0.5f - cam.pos[1], mz = (az + bz) * 0.5f - cam.pos[2];
					float sx = dy * mz - dz * my, sy = dz * mx - dx * mz, sz = dx * my - dy * mx;
					float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
					if (sl < 1e-5f) continue;
					float w = 0.035f / sl;
					sx *= w; sy *= w; sz *= w;
					put(ax - sx, ay - sy, az - sz, 0.5f, 0f, 1f, 0.9f, 0.55f, 0.9f);
					put(bx - sx, by - sy, bz - sz, 0.5f, 0f, 1f, 0.9f, 0.55f, 0.9f);
					put(bx + sx, by + sy, bz + sz, 0.5f, 1f, 1f, 0.9f, 0.55f, 0.9f);
					put(ax - sx, ay - sy, az - sz, 0.5f, 0f, 1f, 0.9f, 0.55f, 0.9f);
					put(bx + sx, by + sy, bz + sz, 0.5f, 1f, 1f, 0.9f, 0.55f, 0.9f);
					put(ax + sx, ay + sy, az + sz, 0.5f, 1f, 1f, 0.9f, 0.55f, 0.9f);
				}
			}
			if (vc == 0) continue;

			GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, add ? GLES20.GL_ONE : GLES20.GL_ONE_MINUS_SRC_ALPHA);
			buf.clear();
			buf.put(verts, 0, vc * 9).position(0);
			final int stride = 9 * 4;
			buf.position(0);
			GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, buf);
			GLES20.glEnableVertexAttribArray(aPos);
			buf.position(3);
			GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, stride, buf);
			GLES20.glEnableVertexAttribArray(aUv);
			buf.position(5);
			GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, stride, buf);
			GLES20.glEnableVertexAttribArray(aColor);
			GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vc);
			GLES20.glDisableVertexAttribArray(aPos);
			GLES20.glDisableVertexAttribArray(aUv);
			GLES20.glDisableVertexAttribArray(aColor);
		}

		GLES20.glDepthMask(true);
		GLES20.glDisable(GLES20.GL_BLEND);
		GLES20.glEnable(GLES20.GL_CULL_FACE);
	}
}
