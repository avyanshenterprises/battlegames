package com.avyansh.battleroyale;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

public class CloudRenderer {
	
	// ---------- tuning ----------
	private static final int   CLOUD_COUNT   = 28;    // number of cloud "systems"
	private static final int   PUFFS_PER     = 9;     // billboards per cloud
	private static final float ALTITUDE      = 450f;  // metres above sea level
	private static final float AREA          = 2600f; // horizontal spread (world units)
	private static final float MIN_SCALE     = 55f;
	private static final float MAX_SCALE     = 130f;
	private static final float DRIFT_SPEED   = 1.8f;  // world units / second
	
	// ---------- shader ----------
	private static final String VS =
	"uniform mat4 uVP;\n" +
	"attribute vec3 aPos;\n" +
	"attribute vec2 aUv;\n" +
	"varying vec2 vUv;\n" +
	"void main(){\n" +
	"  vUv = aUv;\n" +
	"  gl_Position = uVP * vec4(aPos, 1.0);\n" +
	"}\n";
	
	private static final String FS = ShaderUtil.FRAG_HEADER +
	"varying vec2 vUv;\n" +
	"uniform sampler2D uTex;\n" +
	"uniform float uAlpha;\n" +
	"uniform vec3  uTint;\n" +
	"void main(){\n" +
	"  vec4 c = texture2D(uTex, vUv);\n" +
	"  vec2 d = vUv - 0.5;\n" +
	"  float r = length(d) * 2.0;\n" +
	"  float edge = 1.0 - smoothstep(0.55, 1.0, r);\n" +
	"  float k = uAlpha * edge;\n" +
	// texture is premultiplied by GLUtils: output premultiplied and blend with (ONE, 1-SRC_ALPHA)
	"  gl_FragColor = vec4(c.rgb * uTint * k, c.a * k);\n" +
	"}\n";

	// ---------- GL state ----------
	private int program;
	private int aPos, aUv;
	private int uVP, uTex, uAlpha, uTint;
	private int texture;
	
	// One flat buffer holds all billboards, rebuilt every frame (very cheap — 28*7*6 verts).
	private FloatBuffer verts;
	private static final int VERTS_PER_PUFF = 6;      // two triangles
	private static final int FLOATS_PER_VERT = 5;     // pos3 + uv2
	
	// ---------- per-cloud data ----------
	private final float[] baseX   = new float[CLOUD_COUNT];
	private final float[] baseZ   = new float[CLOUD_COUNT];
	private final float[] baseY   = new float[CLOUD_COUNT];
	private final float[] scale   = new float[CLOUD_COUNT];
	private final float[] drift   = new float[CLOUD_COUNT];
	
	// per-puff offsets, relative to cloud base
	private final float[] puffDX  = new float[CLOUD_COUNT * PUFFS_PER];
	private final float[] puffDY  = new float[CLOUD_COUNT * PUFFS_PER];
	private final float[] puffDZ  = new float[CLOUD_COUNT * PUFFS_PER];
	private final float[] puffSz  = new float[CLOUD_COUNT * PUFFS_PER];
	
	private float totalTime = 0f;
	
	// ==================================================================
	// Init
	// ==================================================================
	public void init() {
		program = ShaderUtil.createProgram(VS, FS);
		aPos = GLES20.glGetAttribLocation(program, "aPos");
		aUv  = GLES20.glGetAttribLocation(program, "aUv");
		uVP  = GLES20.glGetUniformLocation(program, "uVP");
		uTex = GLES20.glGetUniformLocation(program, "uTex");
		uAlpha = GLES20.glGetUniformLocation(program, "uAlpha");
		uTint  = GLES20.glGetUniformLocation(program, "uTint");
		
		texture = makeCloudTexture();
		
		// Allocate enough space for every vertex of every puff of every cloud
		int totalVerts = CLOUD_COUNT * PUFFS_PER * VERTS_PER_PUFF;
		verts = ByteBuffer.allocateDirect(totalVerts * FLOATS_PER_VERT * 4)
		.order(ByteOrder.nativeOrder())
		.asFloatBuffer();
		
		Random rnd = new Random(1337);
		for (int i = 0; i < CLOUD_COUNT; i++) {
			// Scatter around origin — clouds are recentered on the camera each frame
			baseX[i] = (rnd.nextFloat() - 0.5f) * AREA;
			baseZ[i] = (rnd.nextFloat() - 0.5f) * AREA;
			baseY[i] = ALTITUDE + rnd.nextFloat() * 80f;
			scale[i] = MIN_SCALE + rnd.nextFloat() * (MAX_SCALE - MIN_SCALE);
			drift[i] = rnd.nextFloat() * 6.28f;
			
			// Build puffs for this cloud — a wide, flat cluster with more mass in the middle
			for (int p = 0; p < PUFFS_PER; p++) {
				int idx = i * PUFFS_PER + p;
				
				// Gaussian-ish distribution: more mass near center
				float gx = (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat()) / 3f - 0.5f;
				float gz = (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat()) / 3f - 0.5f;
				float g = Math.max(Math.abs(gx), Math.abs(gz));
				puffDX[idx] = gx * scale[i] * 2.2f;
				puffDZ[idx] = gz * scale[i] * 1.5f;
				
				// Flatter vertically — clouds are wide, not tall
				puffDY[idx] = (rnd.nextFloat() - 0.5f) * scale[i] * 0.5f;
				
				// Vary puff sizes: some big, some small, center puffs bigger
				float centreBias = 1f - Math.abs(g) * 1.5f;
				puffSz[idx] = scale[i] * (0.55f + rnd.nextFloat() * 0.55f) *
				Math.max(0.35f, centreBias);
			}
		}
	}
	
	// ==================================================================
	// Render — called from GameRenderer.onDrawFrame after sky
	// ==================================================================
	public void render(GameCamera cam, GameEngine eng, float dt) {
		if (GameSettings.CLOUD_COVER <= 0f) return;
		totalTime += dt;
		
		// Clouds follow the camera so they always cover the sky
		float cx = cam.pos[0];
		float cz = cam.pos[2];
		
		// Camera-facing right vector (only XZ matters for cylindrical billboard)
		float yawRad = (float) Math.toRadians(cam.yaw);
		float rightX = (float) Math.cos(yawRad);
		float rightZ = (float) Math.sin(yawRad);
		
		// Build vertex data
		verts.position(0);
		int activeClouds = (int) (CLOUD_COUNT * GameSettings.CLOUD_COVER);
		
		for (int i = 0; i < activeClouds; i++) {
			// Slow drift per cloud
			float driftX = (float) Math.sin(totalTime * 0.05f + drift[i]) * DRIFT_SPEED * 30f;
			float driftZ = (float) Math.cos(totalTime * 0.04f + drift[i] * 1.7f) * DRIFT_SPEED * 15f;
			
			// Slight vertical bob per cloud (looks alive)
			float bob = (float) Math.sin(totalTime * 0.15f + drift[i] * 2.3f) * 8f;
			
			float baseCx = cx + baseX[i] + driftX;
			float baseCz = cz + baseZ[i] + driftZ;
			float baseCy = baseY[i] + bob;
			
			for (int p = 0; p < PUFFS_PER; p++) {
				int idx = i * PUFFS_PER + p;
				float px = baseCx + puffDX[idx];
				float py = baseCy + puffDY[idx];
				float pz = baseCz + puffDZ[idx];
				float s  = puffSz[idx];
				
				// Billboard quad — camera-facing (right + up)
				// Up is world-up (0,1,0) so clouds stay "horizontal"
				float rx = rightX * s;
				float rz = rightZ * s;
				
				// Triangle 1
				putV(px - rx, py - s, pz - rz, 0f, 0f);
				putV(px + rx, py - s, pz + rz, 1f, 0f);
				putV(px - rx, py + s, pz - rz, 0f, 1f);
				// Triangle 2
				putV(px + rx, py - s, pz + rz, 1f, 0f);
				putV(px + rx, py + s, pz + rz, 1f, 1f);
				putV(px - rx, py + s, pz - rz, 0f, 1f);
			}
		}
		verts.position(0);
		
		// ---------- GL state ----------
		GLES20.glDisable(GLES20.GL_DEPTH_TEST);
		GLES20.glDepthMask(false);
		GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
		GLES20.glDisable(GLES20.GL_CULL_FACE);
		
		GLES20.glUseProgram(program);
		GLES20.glUniformMatrix4fv(uVP, 1, false, cam.vp, 0);
		
		// Day/night tint — bright white by day, warm/pinkish at dawn/dusk, dark blue at night
		float[] tint = new float[3];
		if (eng != null) {
			float day = eng.dayFactor;
			float night = eng.nightFactor;
			// Base white
			tint[0] = 1.0f;
			tint[1] = 1.0f;
			tint[2] = 1.0f;
			// Blend toward horizon colour slightly (gives sunset pinks)
			tint[0] = tint[0] * (0.55f + 0.55f * day) + eng.horizon[0] * 0.25f;
			tint[1] = tint[1] * (0.55f + 0.55f * day) + eng.horizon[1] * 0.25f;
			tint[2] = tint[2] * (0.55f + 0.55f * day) + eng.horizon[2] * 0.25f;
			// Darken at night
			tint[0] *= (1f - 0.55f * night);
			tint[1] *= (1f - 0.55f * night);
			tint[2] *= (1f - 0.45f * night);
		} else {
			tint[0] = tint[1] = tint[2] = 1f;
		}
		GLES20.glUniform3f(uTint, tint[0], tint[1], tint[2]);
		
		GLES20.glUniform1f(uAlpha, GameSettings.CLOUD_OPACITY);
		
		GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
		GLES20.glUniform1i(uTex, 0);
		
		// Upload vertex data
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
		verts.position(0);
		GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, FLOATS_PER_VERT * 4, verts);
		GLES20.glEnableVertexAttribArray(aPos);
		verts.position(3);
		GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, FLOATS_PER_VERT * 4, verts);
		GLES20.glEnableVertexAttribArray(aUv);
		
		int totalVerts = activeClouds * PUFFS_PER * VERTS_PER_PUFF;
		GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, totalVerts);
		
		GLES20.glDisableVertexAttribArray(aPos);
		GLES20.glDisableVertexAttribArray(aUv);
		
		GLES20.glEnable(GLES20.GL_DEPTH_TEST);
		GLES20.glDepthMask(true);
		GLES20.glDisable(GLES20.GL_BLEND);
	}
	
	private void putV(float x, float y, float z, float u, float v) {
		verts.put(x).put(y).put(z).put(u).put(v);
	}
	
	// ==================================================================
	// Procedural cloud texture (soft fluffy alpha blob)
	// ==================================================================
	private int makeCloudTexture() {
		final int S = 256;
		int[] px = new int[S * S];
		float[] g1 = lattice(8, 7), g2 = lattice(16, 11), g3 = lattice(32, 13);

		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				float nx = (x - S * 0.5f) / (S * 0.5f);   // -1..1
				float ny = (y - S * 0.5f) / (S * 0.5f);   // row 0 is the BOTTOM of the quad (v = 0)
				float u = (x + 0.5f) / S, v = (y + 0.5f) / S;

				float n = sample(g1, 8, u, v) * 0.50f + sample(g2, 16, u, v) * 0.30f + sample(g3, 32, u, v) * 0.20f;

				float rx = nx / 0.90f, ry = ny / 0.62f;
				float r = (float) Math.sqrt(rx * rx + ry * ry);
				float shape = 1f - smoothstep(0.25f, 1.0f, r + (n - 0.5f) * 0.9f);
				float a = (float) Math.pow(Math.min(1f, Math.max(0f, shape * 1.25f)), 1.2f);

				// lit top, blue-grey belly, slightly darker dense core
				float top = ny * 0.5f + 0.5f;
				float l = 0.80f + 0.20f * top;
				l *= 0.86f + 0.14f * n;
				l *= 1f - 0.12f * a * (1f - top);
				float rr = Math.min(1f, l);
				float gg = Math.min(1f, l * (0.97f + 0.03f * top));
				float bb = Math.min(1f, l * (1.02f + 0.04f * (1f - top)));

				int ai = (int) (a * 255f);
				px[y * S + x] = (ai << 24) | ((int) (rr * 255f) << 16) | ((int) (gg * 255f) << 8) | (int) (bb * 255f);
			}
		}

		Bitmap bmp = Bitmap.createBitmap(px, S, S, Bitmap.Config.ARGB_8888);
		int[] t = new int[1];
		GLES20.glGenTextures(1, t, 0);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
		GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
		GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
		bmp.recycle();
		return t[0];
	}

	private static float[] lattice(int n, int seed) {
		Random r = new Random(seed);
		float[] g = new float[n * n];
		for (int i = 0; i < g.length; i++) g[i] = r.nextFloat();
		return g;
	}

	/** Smooth (quintic) value noise on an n x n lattice that wraps. u,v in 0..1. */
	private static float sample(float[] g, int n, float u, float v) {
		float fx = u * n, fy = v * n;
		int xi = (int) Math.floor(fx), yi = (int) Math.floor(fy);
		float tx = fx - xi, ty = fy - yi;
		tx = tx * tx * tx * (tx * (tx * 6f - 15f) + 10f);
		ty = ty * ty * ty * (ty * (ty * 6f - 15f) + 10f);
		int x0 = ((xi % n) + n) % n, x1 = (x0 + 1) % n;
		int y0 = ((yi % n) + n) % n, y1 = (y0 + 1) % n;
		float a = g[y0 * n + x0], b = g[y0 * n + x1], c = g[y1 * n + x0], d = g[y1 * n + x1];
		float ab = a + (b - a) * tx, cd = c + (d - c) * tx;
		return ab + (cd - ab) * ty;
	}

	private static float smoothstep(float e0, float e1, float x) {
		float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
		return t * t * (3f - 2f * t);
	}
}
