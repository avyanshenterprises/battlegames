package com.avyansh.battleroyale;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public final class ZoneRenderer {
	
	private static final int SEGMENTS = 180;
	private static final float WALL_BOTTOM = -40f;
	private static final float WALL_TOP = 380f;
	
	private static final int FLOATS_PER_VERTEX = 5; // x, y, z, heightFactor, angleFactor
	private static final int VERTICES_PER_RING = (SEGMENTS + 1) * 2;
	
	private static final String VERTEX =
	"uniform mat4 uVP;\n" +
	"attribute vec3 aPos;\n" +
	"attribute float aH;\n" +
	"attribute float aA;\n" +
	"varying float vH;\n" +
	"varying float vA;\n" +
	"varying float vY;\n" +
	"void main() {\n" +
	"    vH = aH;\n" +
	"    vA = aA;\n" +
	"    vY = aPos.y;\n" +
	"    gl_Position = uVP * vec4(aPos, 1.0);\n" +
	"}\n";
	
	private static final String FRAGMENT =
	"precision mediump float;\n" +
	"uniform vec4 uColor;\n" +
	"uniform float uTime;\n" +
	"uniform float uIntensity;\n" +
	"uniform float uWallMode;\n" + // 0 = blue zone, 1 = next safe zone
	"varying float vH;\n" +
	"varying float vA;\n" +
	"varying float vY;\n" +
	"\n" +
	"void main() {\n" +
	"    float t = uTime;\n" +
	"\n" +
	"    // Fast electric bands travelling upward\n" +
	"    float electric = sin(vY * 0.055 - t * 3.0);\n" +
	"    electric += 0.5 * sin(vY * 0.13 + t * 5.5);\n" +
	"    electric += 0.35 * sin(vA * 18.0 + t * 2.2);\n" +
	"    electric = pow(max(electric * 0.5 + 0.5, 0.0), 3.0);\n" +
	"\n" +
	"    // Slow storm pulse\n" +
	"    float pulse = 0.5 + 0.5 * sin(t * 1.35);\n" +
	"\n" +
	"    // Fade at bottom and top\n" +
	"    float vertical = smoothstep(0.0, 0.18, vH) * (1.0 - smoothstep(0.72, 1.0, vH));\n" +
	"\n" +
	"    // Strong glowing edge at ground level\n" +
	"    float groundGlow = 1.0 - smoothstep(0.0, 0.10, vH);\n" +
	"\n" +
	"    float energy = electric * (0.55 + 0.45 * pulse);\n" +
	"    float alpha = uColor.a * uIntensity;\n" +
	"    alpha *= vertical;\n" +
	"    alpha *= 0.35 + 0.65 * energy;\n" +
	"    alpha += groundGlow * uColor.a * 0.55;\n" +
	"\n" +
	"    // Blue zone has stronger electric look; next circle is cleaner and thinner\n" +
	"    vec3 color = uColor.rgb;\n" +
	"    if (uWallMode < 0.5) {\n" +
	"        color += vec3(0.10, 0.28, 0.55) * energy;\n" +
	"    } else {\n" +
	"        color += vec3(0.25, 0.25, 0.30) * energy * 0.35;\n" +
	"    }\n" +
	"\n" +
	"    gl_FragColor = vec4(color, clamp(alpha, 0.0, 1.0));\n" +
	"}\n";
	
	private int program;
	private int uVP;
	private int uColor;
	private int uTime;
	private int uIntensity;
	private int uWallMode;
	private int aPos;
	private int aH;
	private int aA;
	
	private final FloatBuffer wallBuffer = allocateBuffer(VERTICES_PER_RING * FLOATS_PER_VERTEX);
	private final FloatBuffer ringBuffer = allocateBuffer((SEGMENTS + 1) * 3);
	
	/** Must be called whenever a new GL context is created. */
	public void invalidate() {
		program = 0;
	}
	
	private static FloatBuffer allocateBuffer(int floatCount) {
		return ByteBuffer.allocateDirect(floatCount * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
	}
	
	private boolean ensureProgram() {
		if (program != 0) {
			return true;
		}
		
		program = ShaderUtil.createProgram(VERTEX, FRAGMENT);
		if (program == 0) {
			return false;
		}
		
		uVP = GLES20.glGetUniformLocation(program, "uVP");
		uColor = GLES20.glGetUniformLocation(program, "uColor");
		uTime = GLES20.glGetUniformLocation(program, "uTime");
		uIntensity = GLES20.glGetUniformLocation(program, "uIntensity");
		uWallMode = GLES20.glGetUniformLocation(program, "uWallMode");
		aPos = GLES20.glGetAttribLocation(program, "aPos");
		aH = GLES20.glGetAttribLocation(program, "aH");
		aA = GLES20.glGetAttribLocation(program, "aA");
		
		return true;
	}
	
	public void render(GameCamera cam, ZoneController zone, float time) {
		if (zone == null || !zone.isActive() || !ensureProgram()) {
			return;
		}
		
		GLES20.glUseProgram(program);
		GLES20.glUniformMatrix4fv(uVP, 1, false, cam.vp, 0);
		GLES20.glUniform1f(uTime, time);
		GLES20.glEnable(GLES20.GL_DEPTH_TEST);
		GLES20.glDepthMask(false);
		GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
		GLES20.glDisable(GLES20.GL_CULL_FACE);
		GLES20.glEnableVertexAttribArray(aPos);
		GLES20.glEnableVertexAttribArray(aH);
		GLES20.glEnableVertexAttribArray(aA);
		
		// Next safe circle: white warning wall
		if (!zone.isFinished()) {
			drawWall(zone.targetX(), zone.targetZ(), zone.targetRadius(), 0.90f, 0.95f, 1.0f, 0.13f, 0.75f, 1.0f);
			drawGroundRing(zone.targetX(), zone.targetZ(), zone.targetRadius(), 0.90f, 0.95f, 1.0f, 0.28f, 1.0f);
		}
		
		// Current death zone: blue electric wall
		float shrink = zone.getShrinkProgress(); // optional; falls back safely below
		float intensity = 1.0f;
		
		// If ZoneController exposes shrink progress, make the wall more intense near the end.
		if (shrink >= 0f) {
			intensity = 0.85f + 0.55f * shrink;
		}
		drawWall(zone.centerX(), zone.centerZ(), zone.radius(), 0.10f, 0.48f, 1.0f, 0.36f, intensity, 0.0f);
		drawGroundRing(zone.centerX(), zone.centerZ(), zone.radius(), 0.18f, 0.62f, 1.0f, 0.55f, 0.0f);
		
		GLES20.glDisableVertexAttribArray(aPos);
		GLES20.glDisableVertexAttribArray(aH);
		GLES20.glDisableVertexAttribArray(aA);
		GLES20.glDepthMask(true);
		GLES20.glDisable(GLES20.GL_BLEND);
		GLES20.glEnable(GLES20.GL_CULL_FACE);
	}
	
	private void drawWall(float cx, float cz, float radius, float r, float g, float b, float a, float intensity, float wallMode) {
		if (radius <= 0f) {
			return;
		}
		
		wallBuffer.clear();
		
		for (int i = 0; i <= SEGMENTS; i++) {
			float angle = (float) ((Math.PI * 2.0) * i / SEGMENTS);
			float x = cx + (float) Math.cos(angle) * radius;
			float z = cz + (float) Math.sin(angle) * radius;
			
			wallBuffer.put(x).put(WALL_BOTTOM).put(z).put(0f).put(angle);
			wallBuffer.put(x).put(WALL_TOP).put(z).put(1f).put(angle);
		}
		
		wallBuffer.position(0);
		
		int stride = FLOATS_PER_VERTEX * 4;
		GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, wallBuffer);
		wallBuffer.position(3);
		GLES20.glVertexAttribPointer(aH, 1, GLES20.GL_FLOAT, false, stride, wallBuffer);
		wallBuffer.position(4);
		GLES20.glVertexAttribPointer(aA, 1, GLES20.GL_FLOAT, false, stride, wallBuffer);
		GLES20.glUniform4f(uColor, r, g, b, a);
		GLES20.glUniform1f(uIntensity, intensity);
		GLES20.glUniform1f(uWallMode, wallMode);
		GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTICES_PER_RING);
	}
	
	private void drawGroundRing(float cx, float cz, float radius, float r, float g, float b, float a, float wallMode) {
		if (radius <= 0f) {
			return;
		}
		
		ringBuffer.clear();
		for (int i = 0; i <= SEGMENTS; i++) {
			float angle = (float) ((Math.PI * 2.0) * i / SEGMENTS);
			float x = cx + (float) Math.cos(angle) * radius;
			float z = cz + (float) Math.sin(angle) * radius;
			
			ringBuffer.put(x).put(WALL_BOTTOM).put(z);
		}
		
		ringBuffer.position(0);
		GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, ringBuffer);
		GLES20.glUniform4f(uColor, r, g, b, a);
		GLES20.glUniform1f(uIntensity, 1.0f);
		GLES20.glUniform1f(uWallMode, wallMode);
		
		// A line loop gives a crisp zone boundary without filling the whole circle.
		GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, SEGMENTS + 1);
	}
}
