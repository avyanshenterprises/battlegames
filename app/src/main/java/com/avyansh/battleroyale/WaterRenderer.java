package com.avyansh.battleroyale;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

public class WaterRenderer {
	
	private static final float HALF_SIZE   = 1800f;   // world-units half-extent of the grid
	private static final int   GRID        = 48;      // quads per side (GRID+1 verts each way)
	
	// Wave parameters. Change here to alter look globally.
	private static final int   WAVES       = 5;
	private static final float BASE_AMP    = 1.4f;
	private static final float BASE_LEN    = 27f;
	private static final float BASE_SPEED  = 1.4f;
	
	// Vertex shader — computes wave pos, normal, shore height, world pos
	private static final String VS =
	"uniform mat4 uVP; uniform vec3 uCenter; uniform float uSize; uniform float uLevel;\n" +
	"uniform float uTime; uniform float uFogStart; uniform float uFogEnd;\n" +
	// Wave params (pairs): dir.x, dir.z, amp, length, speed, phase — 5 waves = 30 floats
	"uniform vec4 uWaveDirAmp[5];\n" +      // dir.x, dir.z, amp, length
	"uniform vec2 uWaveSpeedPhase[5];\n" +  // speed, phase
	"uniform sampler2D uHeightMap;\n" +     // (r=terrain height, g=shore-dist) fallback: none
	"attribute vec2 aPos;\n" +
	"varying vec3 vWorld; varying vec3 vNormal; varying vec2 vXZ;\n" +
	"varying float vDepth01; varying float vFogF; varying float vShore;\n" +
	"void main(){\n" +
	"  vec2 gridUV = aPos * 0.5 + 0.5;                 // 0..1\n" +
	"  vec2 warped = aPos * abs(aPos);                  // dense mesh near the camera, sparse far away\n" +
	"  vec2 worldXZ = uCenter.xz + warped * uSize;\n" +
	// shore distance: 0 near shore, 1 offshore. Approx via distance from grid centre
	// (accurate enough because we only need it to attenuate waves / blend colour).
	"  float centerDist = length(aPos);\n" +
	"  vShore = clamp(centerDist * 0.7, 0.0, 1.0);\n" +
	"  float ampScale = mix(0.7, 1.0, vShore);\n" +
	"  float fineFade = 1.0 - smoothstep(0.05, 0.25, centerDist);\n" +
	"  vec3 pos = vec3(worldXZ.x, uLevel, worldXZ.y);\n" +
	"  vec3 tangent = vec3(1.0, 0.0, 0.0);\n" +
	"  vec3 bitangent = vec3(0.0, 0.0, 1.0);\n" +
	"  for (int i = 0; i < 5; i++) {\n" +
	"    vec2 dir = normalize(uWaveDirAmp[i].xy);\n" +
	"    float amp = uWaveDirAmp[i].z * ampScale;\n" +
	"    if (i >= 3) amp *= fineFade;\n" +
	"    float len = max(uWaveDirAmp[i].w, 0.1);\n" +
	"    float k = 6.2831853 / len;\n" +
	"    float speed = uWaveSpeedPhase[i].x;\n" +
	"    float phase = uWaveSpeedPhase[i].y + uTime * speed;\n" +
	"    float ph = dot(dir, worldXZ) * k + phase;\n" +
	"    float s = sin(ph);\n" +
	"    float c = cos(ph);\n" +
	"    pos.y += s * amp;\n" +
	// analytic tangent / bitangent contribution
	"    float dHdx = c * amp * k * dir.x;\n" +
	"    float dHdz = c * amp * k * dir.y;\n" +
	"    tangent   += vec3(0.0, dHdx, 0.0);\n" +
	"    bitangent += vec3(0.0, dHdz, 0.0);\n" +
	"  }\n" +
	"  vNormal = normalize(cross(bitangent, tangent));\n" +
	"  if (vNormal.y < 0.0) vNormal = -vNormal;\n" +
	"  vWorld = pos;\n" +
	"  vXZ = worldXZ;\n" +
	"  float dist = length(pos - uCenter - vec3(0.0, 0.0, 0.0) * 0.0);\n" +
	// depth01 = pseudo depth using heightmap value if provided, else 0 (deep)
	// We estimate from distance to grid centre: closer = shallower.
	"  vDepth01 = clamp(1.0 - vShore, 0.0, 1.0);\n" +
	"  vFogF = clamp((length(pos.xz - uCenter.xz) - uFogStart) / (uFogEnd - uFogStart), 0.0, 1.0);\n" +
	"  gl_Position = uVP * vec4(pos, 1.0);\n" +
	"}\n";
	
	// Fragment shader — lighting, fresnel, specular, foam, shore tint, fog
	private static final String FS = ShaderUtil.FRAG_HEADER +
	"varying vec3 vWorld; varying vec3 vNormal; varying vec2 vXZ;\n" +
	"varying float vDepth01; varying float vFogF; varying float vShore;\n" +
	"uniform vec3 uCamPos; uniform vec3 uLightDir; uniform vec3 uLightColor;\n" +
	"uniform vec3 uSunDir; uniform vec3 uSunColor; uniform vec3 uMoonColor;\n" +
	"uniform vec3 uWaterDeep; uniform vec3 uWaterShallow; uniform vec3 uSky;\n" +
	"uniform vec3 uFogColor;\n" +
	"uniform float uTime; uniform float uNight; uniform float uUnderwater;\n" +
	"void main(){\n" +
	"  vec3 toCam = uCamPos - vWorld;\n" +
	"  float dist = length(toCam);\n" +
	"  vec3 v = toCam / max(dist, 0.001);\n" +
	"  vec2 p = vXZ;\n" +
	"  float t = uTime;\n" +
	// four travelling ripples evaluated per pixel (the mesh is too coarse for small waves)
	"  vec2 g = vec2(0.0);\n" +
	"  g += vec2(0.80, 0.60) * cos(dot(vec2(0.80, 0.60), p) * 0.62 + t * 1.35) * 0.074;\n" +
	"  g += vec2(-0.55, 0.83) * cos(dot(vec2(-0.55, 0.83), p) * 0.95 - t * 1.10) * 0.086;\n" +
	"  g += vec2(0.30, -0.95) * cos(dot(vec2(0.30, -0.95), p) * 1.70 + t * 1.90) * 0.085;\n" +
	"  g += vec2(-0.92, -0.38) * cos(dot(vec2(-0.92, -0.38), p) * 2.90 - t * 2.40) * 0.087;\n" +
	"  float rip = 1.0 - smoothstep(50.0, 280.0, dist);\n" +
	"  vec3 n = normalize(normalize(vNormal) + vec3(-g.x, 0.0, -g.y) * rip);\n" +
	"  float ndv = max(dot(n, v), 0.0);\n" +
	"  float fres = 0.02 + 0.98 * pow(1.0 - ndv, 5.0);\n" +
	"  float sunUp = clamp(uSunDir.y * 2.5 + 0.35, 0.15, 1.0);\n" +
	"  float deepMix = clamp(dist / 260.0, 0.0, 1.0);\n" +
	"  vec3 body = mix(uWaterShallow, uWaterDeep, deepMix) * sunUp;\n" +
	"  body += uWaterShallow * (1.0 - n.y) * 1.5 * sunUp;\n" +
	"  vec3 r = reflect(-v, n);\n" +
	"  float ry = clamp(r.y, 0.0, 1.0);\n" +
	"  vec3 skyRefl = mix(uSky, uSky * vec3(0.55, 0.72, 1.05), pow(ry, 0.45));\n" +
	"  vec3 col = mix(body, skyRefl, fres);\n" +
	"  vec3 sd = normalize(uSunDir);\n" +
	"  vec3 h = normalize(v + sd);\n" +
	"  float ndh = max(dot(n, h), 0.0);\n" +
	"  float glint = pow(ndh, 700.0) * 5.0 + pow(ndh, 90.0) * 0.30;\n" +
	"  col += uSunColor * glint * uNight;\n" +
	"  float mh = pow(max(dot(n, normalize(v - sd)), 0.0), 180.0) * 0.6;\n" +
	"  col += uMoonColor * mh * uNight * 0.5;\n" +
	"  float steep = length(g) * rip;\n" +
	"  float foam = smoothstep(0.17, 0.30, steep);\n" +
	"  col = mix(col, vec3(0.93, 0.96, 0.98), foam * 0.35);\n" +
	"  col = mix(col, uFogColor, vFogF);\n" +
	"  col = mix(col, vec3(0.10, 0.28, 0.42), uUnderwater);\n" +
	// transparent close up (seabed / sand shows through the shallows), opaque far away
	"  float alpha = mix(0.58, 0.93, clamp(dist / 140.0, 0.0, 1.0));\n" +
	"  alpha = mix(alpha, 0.85, uUnderwater);\n" +
	"  gl_FragColor = vec4(col, alpha);\n" +
	"}\n";

	private int program;
	private int aPos, uVP, uCenter, uSize, uLevel, uTime, uFogStart, uFogEnd;
	private int uCamPos, uLightDir, uLightColor, uSunDir, uSunColor, uMoonColor;
	private int uWaterDeep, uWaterShallow, uSky, uFogColor, uNight, uUnderwater;
	private int uWaveDirAmp, uWaveSpeedPhase;
	
	// Precomputed wave directions/amplitudes/speeds.
	private final float[] waveDirAmp = new float[5 * 4];
	private final float[] waveSpeedPhase = new float[5 * 2];
	
	// Dynamic mesh
	private float[] verts;
	private short[] indices;
	private FloatBuffer vb;
	private ShortBuffer ib;
	
	public void init() {
		program = ShaderUtil.createProgram(VS, FS);
		
		aPos             = GLES20.glGetAttribLocation(program, "aPos");
		uVP              = GLES20.glGetUniformLocation(program, "uVP");
		uCenter          = GLES20.glGetUniformLocation(program, "uCenter");
		uSize            = GLES20.glGetUniformLocation(program, "uSize");
		uLevel           = GLES20.glGetUniformLocation(program, "uLevel");
		uTime            = GLES20.glGetUniformLocation(program, "uTime");
		uFogStart        = GLES20.glGetUniformLocation(program, "uFogStart");
		uFogEnd          = GLES20.glGetUniformLocation(program, "uFogEnd");
		uCamPos          = GLES20.glGetUniformLocation(program, "uCamPos");
		uLightDir        = GLES20.glGetUniformLocation(program, "uLightDir");
		uLightColor      = GLES20.glGetUniformLocation(program, "uLightColor");
		uSunDir          = GLES20.glGetUniformLocation(program, "uSunDir");
		uSunColor        = GLES20.glGetUniformLocation(program, "uSunColor");
		uMoonColor       = GLES20.glGetUniformLocation(program, "uMoonColor");
		uWaterDeep       = GLES20.glGetUniformLocation(program, "uWaterDeep");
		uWaterShallow    = GLES20.glGetUniformLocation(program, "uWaterShallow");
		uSky             = GLES20.glGetUniformLocation(program, "uSky");
		uFogColor        = GLES20.glGetUniformLocation(program, "uFogColor");
		uNight           = GLES20.glGetUniformLocation(program, "uNight");
		uUnderwater      = GLES20.glGetUniformLocation(program, "uUnderwater");
		uWaveDirAmp      = GLES20.glGetUniformLocation(program, "uWaveDirAmp");
		uWaveSpeedPhase  = GLES20.glGetUniformLocation(program, "uWaveSpeedPhase");
		
		// Wave setup — diverse directions and wavelengths.
		setWave(0, 1.0f,  0.15f, 0.55f,  95f, 0.25f, 0.0f);
		setWave(1, -0.45f, 0.90f, 0.35f,  61f, 0.35f, 1.2f);
		setWave(2, 0.25f, -0.95f, 0.22f,  37f, 0.55f, 2.7f);
		setWave(3, 0.95f,  0.30f, 0.10f,  17f, 0.85f, 4.1f);
		setWave(4, -0.75f,-0.40f, 0.05f,   9f, 1.30f, 5.4f);
		
		// Build the static index buffer once.
		int vn = GRID + 1;
		indices = new short[GRID * GRID * 6];
		int k = 0;
		for (int j = 0; j < GRID; j++) {
			for (int i = 0; i < GRID; i++) {
				int a = j * vn + i;
				int b = a + vn;
				indices[k++] = (short) a;
				indices[k++] = (short) b;
				indices[k++] = (short) (a + 1);
				indices[k++] = (short) (a + 1);
				indices[k++] = (short) b;
				indices[k++] = (short) (b + 1);
			}
		}
		ib = ByteBuffer.allocateDirect(indices.length * 2)
		.order(ByteOrder.nativeOrder()).asShortBuffer();
		ib.put(indices).position(0);
		
		// Dynamic vertex buffer (rebuilt each frame).
		int vertCount = vn * vn;
		verts = new float[vertCount * 2];
		vb = ByteBuffer.allocateDirect(verts.length * 4)
		.order(ByteOrder.nativeOrder()).asFloatBuffer();
	}
	
	private void setWave(int i, float dx, float dz, float amp, float len,
	float speed, float phase) {
		float l = (float) Math.sqrt(dx * dx + dz * dz);
		if (l < 1e-5f) { dx = 1f; dz = 0f; l = 1f; }
		waveDirAmp[i * 4 + 0] = dx / l;
		waveDirAmp[i * 4 + 1] = dz / l;
		waveDirAmp[i * 4 + 2] = amp * BASE_AMP;
		waveDirAmp[i * 4 + 3] = len * BASE_LEN / 20f;
		waveSpeedPhase[i * 2 + 0] = speed * BASE_SPEED;
		waveSpeedPhase[i * 2 + 1] = phase;
	}
	
	public void render(GameCamera cam, GameEngine eng, float time) {
		// Center the grid on the camera (snapped to avoid swimming with tiny moves)
		// (waves are functions of world position, so following the camera exactly causes no swimming)
		float cx = cam.pos[0];
		float cz = cam.pos[2];
		
		// Rebuild vertex array in camera-local [-1,1] grid space
		int vn = GRID + 1;
		int idx = 0;
		for (int j = 0; j < vn; j++) {
			for (int i = 0; i < vn; i++) {
				verts[idx++] = (i / (float) GRID) * 2f - 1f;
				verts[idx++] = (j / (float) GRID) * 2f - 1f;
			}
		}
		vb.position(0);
		vb.put(verts, 0, idx).position(0);
		
		// Underwater test
		float underwater = cam.pos[1] < GameSettings.WATER_LEVEL - 0.1f ? 1f : 0f;
		
		GLES20.glEnable(GLES20.GL_DEPTH_TEST);
		GLES20.glDepthMask(false);
		GLES20.glDisable(GLES20.GL_CULL_FACE);
		GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
		
		GLES20.glUseProgram(program);
		GLES20.glUniformMatrix4fv(uVP, 1, false, cam.vp, 0);
		GLES20.glUniform3f(uCenter, cx, 0f, cz);
		GLES20.glUniform1f(uSize, HALF_SIZE);
		GLES20.glUniform1f(uLevel, GameSettings.WATER_LEVEL);
		GLES20.glUniform3f(uCamPos, cam.pos[0], cam.pos[1], cam.pos[2]);
		GLES20.glUniform3fv(uLightDir, 1, eng.lightDir, 0);
		GLES20.glUniform3fv(uLightColor, 1, eng.lightColor, 0);
		GLES20.glUniform3fv(uSunDir, 1, eng.sunDir, 0);
		GLES20.glUniform3fv(uSunColor, 1, eng.skySunColor, 0);
		// moon colour: cool blue
		GLES20.glUniform3f(uMoonColor, 0.55f, 0.65f, 0.85f);
		GLES20.glUniform3fv(uWaterDeep, 1, eng.waterColor, 0);
		// shallow tint: brighter, greener — mix of water colour and sky
		GLES20.glUniform3f(uWaterShallow,
		0.10f + eng.waterColor[0] * 0.6f,
		0.32f + eng.waterColor[1] * 0.8f,
		0.38f + eng.waterColor[2] * 0.5f);
		GLES20.glUniform3fv(uSky, 1, eng.horizon, 0);
		GLES20.glUniform3fv(uFogColor, 1, eng.fog, 0);
		GLES20.glUniform1f(uTime, time);
		GLES20.glUniform1f(uFogStart, GameSettings.fogStart());
		GLES20.glUniform1f(uFogEnd, GameSettings.fogEnd());
		GLES20.glUniform1f(uNight, 0.4f + 0.6f * eng.nightFactor);
		GLES20.glUniform1f(uUnderwater, underwater);
		GLES20.glUniform4fv(uWaveDirAmp, 5, waveDirAmp, 0);
		GLES20.glUniform2fv(uWaveSpeedPhase, 5, waveSpeedPhase, 0);
		
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
		vb.position(0);
		GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vb);
		GLES20.glEnableVertexAttribArray(aPos);
		
		GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
		ib.position(0);
		GLES20.glDrawElements(GLES20.GL_TRIANGLES, indices.length,
		GLES20.GL_UNSIGNED_SHORT, ib);
		
		GLES20.glDisableVertexAttribArray(aPos);
		GLES20.glDisable(GLES20.GL_BLEND);
		GLES20.glDepthMask(true);
	}
}

