package com.avyansh.battleroyale;

import android.opengl.GLES20;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/** Gradient sky sphere centred on the camera with sun, moon and stars. */
public class SkyDome {
	private static final float RADIUS = 1500f;
	private static final int STACKS = 14, SLICES = 28;
	
	private static final String VS =
	"uniform mat4 uMVP;\n" +
	"attribute vec3 aPos;\n" +
	"varying vec3 vDir;\n" +
	"void main(){ vDir = aPos; gl_Position = uMVP * vec4(aPos, 1.0); }\n";
	private static final String FS = ShaderUtil.FRAG_HEADER +
	"varying vec3 vDir;\n" +
	"uniform vec3 uHorizon; uniform vec3 uZenith; uniform vec3 uSunDir; uniform vec3 uSunColor;\n" +
	"uniform float uNight; uniform float uTime; uniform sampler2D uNoise;\n" +
	"float hash(vec3 p){ p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }\n" +
	"float cloudDensity(vec2 p, vec2 wind){\n" +
	"  float a = texture2D(uNoise, p * 0.16 + wind).r;\n" +
	"  float b = texture2D(uNoise, p * 0.43 + wind * 1.7 + vec2(0.31, 0.17)).r;\n" +
	"  float c = texture2D(uNoise, p * 1.15 + wind * 2.6 + vec2(0.67, 0.41)).r;\n" +
	"  return a * 0.52 + b * 0.30 + c * 0.18;\n" +
	"}\n" +
	"void main(){\n" +
	"  vec3 d = normalize(vDir);\n" +
	"  float up = clamp(d.y, 0.0, 1.0);\n" +
	"  vec3 sunDir = normalize(uSunDir);\n" +
	"  float sd = max(dot(d, sunDir), 0.0);\n" +
	"  vec3 col = mix(uHorizon, uZenith, pow(up, 0.5));\n" +
	// thick horizon haze that warms towards the sun (matches the terrain fog colour)
	"  float haze = exp(-up * 6.5);\n" +
	"  col = mix(col, uHorizon * 1.06 + uSunColor * 0.10 * pow(sd, 3.0), haze * 0.6);\n" +
	// sun: tight disc, corona and a wide glow
	"  col += uSunColor * (pow(sd, 2000.0) * 8.0 + pow(sd, 120.0) * 0.5 + pow(sd, 8.0) * 0.22);\n" +
	"  float md = max(dot(d, -sunDir), 0.0);\n" +
	"  col += vec3(0.75, 0.82, 1.0) * pow(md, 900.0) * 1.6 * uNight;\n" +
	"  float star = step(0.9965, hash(floor(d * 220.0))) * uNight * smoothstep(0.05, 0.35, d.y);\n" +
	"  col += vec3(star);\n" +
	// cloud layer: three scrolling octaves of a tileable noise texture, projected on a flat plane.
	// (no branches around texture reads: mip-level derivatives must stay defined)
	"  float horizonFade = smoothstep(0.015, 0.20, d.y);\n" +
	"  vec2 p = d.xz / (max(d.y, 0.0) + 0.12);\n" +
	"  vec2 wind = vec2(uTime * 0.0035, uTime * 0.0012);\n" +
	"  float dens = cloudDensity(p, wind);\n" +
	"  float cov = 0.47;\n" +
	"  float c = smoothstep(cov, cov + 0.20, dens);\n" +
	"  vec2 toSun = normalize(sunDir.xz + vec2(0.0001));\n" +
	"  float dens2 = cloudDensity(p + toSun * 0.10, wind);\n" +
	"  float lit = clamp(0.62 + (dens - dens2) * 5.5, 0.0, 1.0);\n" +
	"  float thick = smoothstep(cov, cov + 0.45, dens);\n" +
	"  float day = 1.0 - uNight;\n" +
	"  vec3 tint = uSunColor / max(max(uSunColor.r, uSunColor.g), max(uSunColor.b, 0.001));\n" +
	"  vec3 bright = mix(vec3(1.0), tint, 0.4 * day);\n" +
	"  vec3 shade = vec3(0.50, 0.55, 0.65);\n" +
	"  vec3 cc = mix(shade, bright, lit);\n" +
	"  cc = mix(cc, cc * 0.82, thick * 0.6);\n" +
	"  cc = mix(vec3(0.05, 0.06, 0.09), cc, 0.15 + 0.85 * day);\n" +
	"  cc = mix(cc, uHorizon, haze * 0.5);\n" +
	"  col = mix(col, cc, c * horizonFade * 0.95);\n" +
	"  gl_FragColor = vec4(col, 1.0);\n" +
	"}\n";

	private int program, vbo, ibo, indexCount;
	private int aPos, uMvp, uHorizon, uZenith, uSunDir, uSunColor, uNight, uNoise, uTime;
	private int noiseTex;
	private final long t0 = System.nanoTime();
	private final float[] model = new float[16];
	private final float[] mvp = new float[16];
	
	public void init() {
		program = ShaderUtil.createProgram(VS, FS);
		aPos = GLES20.glGetAttribLocation(program, "aPos");
		uMvp = GLES20.glGetUniformLocation(program, "uMVP");
		uHorizon = GLES20.glGetUniformLocation(program, "uHorizon");
		uZenith = GLES20.glGetUniformLocation(program, "uZenith");
		uSunDir = GLES20.glGetUniformLocation(program, "uSunDir");
		uSunColor = GLES20.glGetUniformLocation(program, "uSunColor");
		uNight = GLES20.glGetUniformLocation(program, "uNight");
		uNoise = GLES20.glGetUniformLocation(program, "uNoise");
		uTime = GLES20.glGetUniformLocation(program, "uTime");
		noiseTex = TextureUtil.createBumpTexture();   // tileable value noise, drives the cloud layer
		
		float[] verts = new float[(STACKS + 1) * (SLICES + 1) * 3];
		int k = 0;
		for (int i = 0; i <= STACKS; i++) {
			float phi = (float) (Math.PI * i / STACKS - Math.PI / 2.0);
			float y = (float) Math.sin(phi) * RADIUS;
			float r = (float) Math.cos(phi) * RADIUS;
			for (int j = 0; j <= SLICES; j++) {
				float th = (float) (2.0 * Math.PI * j / SLICES);
				verts[k++] = (float) Math.cos(th) * r;
				verts[k++] = y;
				verts[k++] = (float) Math.sin(th) * r;
			}
		}
		short[] idx = new short[STACKS * SLICES * 6];
		k = 0;
		for (int i = 0; i < STACKS; i++) {
			for (int j = 0; j < SLICES; j++) {
				int a = i * (SLICES + 1) + j;
				int b = a + SLICES + 1;
				idx[k++] = (short) a;
				idx[k++] = (short) b;
				idx[k++] = (short) (a + 1);
				idx[k++] = (short) (a + 1);
				idx[k++] = (short) b;
				idx[k++] = (short) (b + 1);
			}
		}
		indexCount = idx.length;
		
		FloatBuffer fb = ByteBuffer.allocateDirect(verts.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
		fb.put(verts).position(0);
		ShortBuffer sb = ByteBuffer.allocateDirect(idx.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
		sb.put(idx).position(0);
		int[] h = new int[2];
		GLES20.glGenBuffers(2, h, 0);
		vbo = h[0];
		ibo = h[1];
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
		GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.length * 4, fb, GLES20.GL_STATIC_DRAW);
		GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo);
		GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idx.length * 2, sb, GLES20.GL_STATIC_DRAW);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
		GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
	}
	
	public void render(GameCamera cam, GameEngine eng) {
		GLES20.glDisable(GLES20.GL_DEPTH_TEST);
		GLES20.glDisable(GLES20.GL_CULL_FACE);
		GLES20.glDisable(GLES20.GL_BLEND);
		GLES20.glUseProgram(program);
		
		Matrix.setIdentityM(model, 0);
		Matrix.translateM(model, 0, cam.pos[0], cam.pos[1], cam.pos[2]);
		Matrix.multiplyMM(mvp, 0, cam.vp, 0, model, 0);
		GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
		GLES20.glUniform3fv(uHorizon, 1, eng.horizon, 0);
		GLES20.glUniform3fv(uZenith, 1, eng.zenith, 0);
		GLES20.glUniform3fv(uSunDir, 1, eng.sunDir, 0);
		GLES20.glUniform3fv(uSunColor, 1, eng.skySunColor, 0);
		GLES20.glUniform1f(uNight, eng.nightFactor);
		GLES20.glUniform1f(uTime, (System.nanoTime() - t0) * 1e-9f);
		GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTex);
		GLES20.glUniform1i(uNoise, 0);
		
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
		GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 12, 0);
		GLES20.glEnableVertexAttribArray(aPos);
		GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo);
		GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0);
		GLES20.glDisableVertexAttribArray(aPos);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
		GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
	}
}
