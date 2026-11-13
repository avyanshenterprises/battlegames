package com.avyansh.battleroyale;

import android.opengl.GLES20;
import android.opengl.Matrix;

public final class ModelShader {
	private ModelShader() {}
	
	private static final String VS =
	"uniform mat4 uMVP; uniform mat4 uModel;\n" +
	"uniform vec4 uRig;\n" +
	"uniform vec4 uRig2;\n" +
	"uniform vec4 uPoseA;\n" +
	"uniform vec4 uPoseB;\n" +
	"attribute vec3 aPos; attribute vec3 aNormal; attribute vec3 aColor; attribute vec2 aUv; attribute vec2 aPart;\n" +
	"varying vec3 vN; varying vec3 vC; varying vec2 vUv; varying vec3 vWorld; varying float vDist;\n" +
	"vec3 rotAxis(vec3 v, vec3 a, float ang){\n" +
	"  float c = cos(ang); float s = sin(ang);\n" +
	"  return v * c + cross(a, v) * s + a * dot(a, v) * (1.0 - c);\n" +
	"}\n" +
	"void main(){\n" +
	"  vec3 p = aPos; vec3 n = aNormal;\n" +
	"  float w = aPart.y;\n" +
	"  if (w > 0.001) {\n" +
	"    float id = aPart.x;\n" +
	"    bool lat0 = uRig2.x < 0.5;\n" +
	"    vec3 L = lat0 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 0.0, 1.0);\n" +
	"    vec3 F = lat0 ? vec3(0.0, 0.0, uRig2.y) : vec3(uRig2.y, 0.0, 0.0);\n" +
	"    vec3 down = vec3(0.0, -1.0, 0.0);\n" +
	"    vec3 swingAxis = normalize(cross(down, F));\n" +
	"    vec3 ctr = lat0 ? vec3(uRig.z, 0.0, uRig2.z) : vec3(uRig2.z, 0.0, uRig.z);\n" +
	"    float side = (mod(id, 2.0) > 0.5) ? 1.0 : -1.0;\n" +
	"    vec3 pr; vec3 nr;\n" +
	"    if (id < 2.5) {\n" +
	"      vec3 pivot = ctr + L * (side * uRig2.w) + vec3(0.0, uRig.x, 0.0);\n" +
	"      float ang = side > 0.0 ? uPoseA.x : uPoseA.y;\n" +
	"      pr = pivot + rotAxis(p - pivot, swingAxis, ang);\n" +
	"      nr = rotAxis(n, swingAxis, ang);\n" +
	"    } else {\n" +
	"      vec3 pivot = ctr + L * (side * uRig.w) + vec3(0.0, uRig.y, 0.0);\n" +
	"      vec3 D = L * side;\n" +
	"      float swing = side > 0.0 ? uPoseA.z : uPoseA.w;\n" +
	"      vec3 axDown = normalize(cross(D, down));\n" +
	"      vec3 q = rotAxis(p - pivot, axDown, uPoseB.x * 1.38);\n" +
	"      vec3 qn = rotAxis(n, axDown, uPoseB.x * 1.38);\n" +
	"      q = rotAxis(q, swingAxis, swing); qn = rotAxis(qn, swingAxis, swing);\n" +
	"      vec3 axYaw = normalize(cross(D, F));\n" +
	"      vec3 q2 = rotAxis(p - pivot, axYaw, 1.75);\n" +
	"      vec3 q2n = rotAxis(n, axYaw, 1.75);\n" +
	"      vec3 axUp = normalize(cross(F, vec3(0.0, 1.0, 0.0)));\n" +
	"      q2 = rotAxis(q2, axUp, uPoseB.w); q2n = rotAxis(q2n, axUp, uPoseB.w);\n" +
	"      q = mix(q, q2, uPoseB.y); qn = mix(qn, q2n, uPoseB.y);\n" +
	"      pr = pivot + q; nr = qn;\n" +
	"    }\n" +
	"    p = mix(p, pr, w);\n" +
	"    n = normalize(mix(n, nr, w));\n" +
	"  }\n" +
	"  vN = mat3(uModel) * n; vC = aColor; vUv = aUv;\n" +
	"  vWorld = (uModel * vec4(p, 1.0)).xyz;\n" +
	"  gl_Position = uMVP * vec4(p, 1.0);\n" +
	"  vDist = gl_Position.w;\n" +
	"}\n";
	
	private static final String FS = ShaderUtil.FRAG_HEADER +
	"varying vec3 vN; varying vec3 vC; varying vec2 vUv; varying vec3 vWorld; varying float vDist;\n" +
	"uniform sampler2D uTex; uniform float uHasTex;\n" +
	"uniform vec3 uCamPos; uniform vec3 uLightDir; uniform vec3 uLightColor; uniform vec3 uAmbient;\n" +
	"uniform vec3 uFogColor; uniform float uFogStart; uniform float uFogEnd;\n" +
	"void main(){\n" +
	"  vec4 t = texture2D(uTex, vUv);\n" +
	"  vec3 albedo = vC;\n" +
	"  if (uHasTex > 0.5) {\n" +
	"    if (t.a < 0.35) discard;\n" +
	"    albedo = t.rgb;\n" +
	"  }\n" +
	"  vec3 n = normalize(vN);\n" +
	"  if (!gl_FrontFacing) n = -n;\n" +
	"  vec3 L = normalize(uLightDir);\n" +
	"  vec3 V = normalize(uCamPos - vWorld);\n" +
	"  float ndl = dot(n, L);\n" +
	"  float wrap = clamp((ndl + 0.35) / 1.35, 0.0, 1.0);\n" +
	"  float hemi = 0.55 + 0.45 * (0.5 + 0.5 * n.y);\n" +
	"  vec3 H = normalize(L + V);\n" +
	"  float spec = pow(max(dot(n, H), 0.0), 36.0) * 0.18 * step(0.0, ndl);\n" +
	"  float rim = pow(1.0 - max(dot(n, V), 0.0), 3.0) * 0.12;\n" +
	"  vec3 col = albedo * (uAmbient * hemi + uLightColor * wrap) + uLightColor * spec + uAmbient * rim;\n" +
	"  col = mix(col, uFogColor, smoothstep(uFogStart, uFogEnd, vDist));\n" +
	"  gl_FragColor = vec4(col, 1.0);\n" +
	"}\n";
	
	private static int program = 0;
	private static int aPos, aNormal, aColor, aUv, aPart;
	private static int uMVP, uModel, uTex, uHasTex, uCamPos, uLightDir, uLightColor, uAmbient;
	private static int uFogColor, uFogStart, uFogEnd, uRig, uRig2, uPoseA, uPoseB;
	
	private static final float[] fogColor = { 0.60f, 0.75f, 0.90f };
	private static float fogStart = 60f, fogEnd = 230f;
	private static final float[] mvp = new float[16];
	private static final int[] tmp = new int[1];
	
	public static void invalidate() { program = 0; }
	
	public static void setFog(float[] color, float start, float end) {
		fogColor[0] = color[0]; fogColor[1] = color[1]; fogColor[2] = color[2];
		fogStart = start;
		fogEnd = end;
	}
	
	private static void ensure() {
		if (program != 0) return;
		program = ShaderUtil.createProgram(VS, FS);
		aPos = GLES20.glGetAttribLocation(program, "aPos");
		aNormal = GLES20.glGetAttribLocation(program, "aNormal");
		aColor = GLES20.glGetAttribLocation(program, "aColor");
		aUv = GLES20.glGetAttribLocation(program, "aUv");
		aPart = GLES20.glGetAttribLocation(program, "aPart");
		uMVP = GLES20.glGetUniformLocation(program, "uMVP");
		uModel = GLES20.glGetUniformLocation(program, "uModel");
		uTex = GLES20.glGetUniformLocation(program, "uTex");
		uHasTex = GLES20.glGetUniformLocation(program, "uHasTex");
		uCamPos = GLES20.glGetUniformLocation(program, "uCamPos");
		uLightDir = GLES20.glGetUniformLocation(program, "uLightDir");
		uLightColor = GLES20.glGetUniformLocation(program, "uLightColor");
		uAmbient = GLES20.glGetUniformLocation(program, "uAmbient");
		uFogColor = GLES20.glGetUniformLocation(program, "uFogColor");
		uFogStart = GLES20.glGetUniformLocation(program, "uFogStart");
		uFogEnd = GLES20.glGetUniformLocation(program, "uFogEnd");
		uRig = GLES20.glGetUniformLocation(program, "uRig");
		uRig2 = GLES20.glGetUniformLocation(program, "uRig2");
		uPoseA = GLES20.glGetUniformLocation(program, "uPoseA");
		uPoseB = GLES20.glGetUniformLocation(program, "uPoseB");
	}
	
	public static void draw(ObjLoader.Model model, float[] modelMatrix, float[] vp, float[] camPos,
	float[] lightDir, float[] lightColor, float[] ambient) {
		draw(model, modelMatrix, vp, camPos, lightDir, lightColor, ambient, null);
	}
	
	public static void draw(ObjLoader.Model model, float[] modelMatrix, float[] vp, float[] camPos,
	float[] lightDir, float[] lightColor, float[] ambient, CharacterPose pose) {
		if (model == null || model.isEmpty()) return;
		ensure();
		
		boolean culling = true;
		GLES20.glDisable(GLES20.GL_CULL_FACE);
		int previous = SkinShader.restoreProgram;
		if (previous == 0) { GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, tmp, 0); previous = tmp[0]; }
		GLES20.glUseProgram(program);
		
		Matrix.multiplyMM(mvp, 0, vp, 0, modelMatrix, 0);
		GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
		GLES20.glUniformMatrix4fv(uModel, 1, false, modelMatrix, 0);
		GLES20.glUniform3f(uCamPos, camPos[0], camPos[1], camPos[2]);
		GLES20.glUniform3fv(uLightDir, 1, lightDir, 0);
		GLES20.glUniform3fv(uLightColor, 1, lightColor, 0);
		GLES20.glUniform3fv(uAmbient, 1, ambient, 0);
		GLES20.glUniform3fv(uFogColor, 1, fogColor, 0);
		GLES20.glUniform1f(uFogStart, fogStart);
		GLES20.glUniform1f(uFogEnd, fogEnd);
		
		if (pose != null && model.rigOk) {
			float fwdSign = GameSettings.MODEL_FACES_PLUS_Z ? 1f : -1f;
			GLES20.glUniform4f(uRig, model.rigHipY, model.rigShoulderY, model.rigCenterLat, model.rigArmStart);
			GLES20.glUniform4f(uRig2, model.rigLatAxis, fwdSign, model.rigCenterFwd, model.rigHipHalf);
			GLES20.glUniform4f(uPoseA, pose.legA, pose.legB, pose.armA, pose.armB);
			GLES20.glUniform4f(uPoseB, pose.armDown, pose.aimBlend, 0f, pose.raise);
		} else {
			GLES20.glUniform4f(uRig, 0f, 0f, 0f, 0f);
			GLES20.glUniform4f(uRig2, 0f, -1f, 0f, 0f);
			GLES20.glUniform4f(uPoseA, 0f, 0f, 0f, 0f);
			GLES20.glUniform4f(uPoseB, 0f, 0f, 0f, 0f);
		}
		
		final int stride = ObjLoader.FLOATS_PER_VERTEX * 4;
		for (ObjLoader.Mesh mesh : model.meshes) {
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
			if (mesh.textureId != 0) {
				GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mesh.textureId);
				GLES20.glUniform1f(uHasTex, 1f);
			} else {
				GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ShaderState.terrainTexture());
				GLES20.glUniform1f(uHasTex, 0f);
			}
			GLES20.glUniform1i(uTex, 0);
			
			GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.vbo);
			GLES20.glVertexAttribPointer(aPos,    3, GLES20.GL_FLOAT, false, stride, 0);
			GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, stride, 12);
			GLES20.glVertexAttribPointer(aColor,  3, GLES20.GL_FLOAT, false, stride, 24);
			GLES20.glVertexAttribPointer(aUv,     2, GLES20.GL_FLOAT, false, stride, 36);
			GLES20.glVertexAttribPointer(aPart,   2, GLES20.GL_FLOAT, false, stride, 44);
			GLES20.glEnableVertexAttribArray(aPos);
			GLES20.glEnableVertexAttribArray(aNormal);
			GLES20.glEnableVertexAttribArray(aColor);
			GLES20.glEnableVertexAttribArray(aUv);
			GLES20.glEnableVertexAttribArray(aPart);
			GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount);
		}
		
		GLES20.glDisableVertexAttribArray(aPos);
		GLES20.glDisableVertexAttribArray(aNormal);
		GLES20.glDisableVertexAttribArray(aColor);
		GLES20.glDisableVertexAttribArray(aUv);
		GLES20.glDisableVertexAttribArray(aPart);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
		
		if (culling) GLES20.glEnable(GLES20.GL_CULL_FACE);
		GLES20.glUseProgram(previous);
	}
}
