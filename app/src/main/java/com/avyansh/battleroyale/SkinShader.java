package com.avyansh.battleroyale;

import android.opengl.GLES20;

/**
* Skinning shader — 4-bone linear blend. Joint matrices uploaded as a mat4 array
* uniform (MAX_JOINTS = 60). Falls through terrain fog + directional + ambient lighting.
*/
public final class SkinShader {
	public static final int MAX_JOINTS = 64;
	
	private static final String VS =
	"uniform mat4 uMVP;\n" +
	"uniform mat4 uModel;\n" +
	"uniform mat4 uJoints[" + MAX_JOINTS + "];\n" +
	"uniform float uSkinOn;\n" +
	"attribute vec3 aPos;\n" +
	"attribute vec3 aNormal;\n" +
	"attribute vec2 aUv;\n" +
	"attribute vec4 aJoints;\n" +
	"attribute vec4 aWeights;\n" +
	"attribute vec4 aVColor;\n" +
	"varying vec4 vVC;\n" +
	"varying vec3 vN;\n" +
	"varying vec2 vUv;\n" +
	"varying vec3 vWorld;\n" +
	"varying float vDist;\n" +
	"void main(){\n" +
	"  vec4 skinnedPos = vec4(aPos, 1.0);\n" +
	"  vec3 skinnedNrm = aNormal;\n" +
	"  if (uSkinOn > 0.5) {\n" +
	"    ivec4 idx = ivec4(aJoints + 0.5);\n" +
	"    mat4 skin = uJoints[idx.x] * aWeights.x\n" +
	"              + uJoints[idx.y] * aWeights.y\n" +
	"              + uJoints[idx.z] * aWeights.z\n" +
	"              + uJoints[idx.w] * aWeights.w;\n" +
	"    skinnedPos = skin * vec4(aPos, 1.0);\n" +
	"    skinnedNrm = mat3(skin) * aNormal;\n" +
	"  }\n" +
	"  vN = mat3(uModel) * skinnedNrm;\n" +
	"  vUv = aUv;\n" +
	"  vVC = aVColor;\n" +
	"  vWorld = (uModel * skinnedPos).xyz;\n" +
	"  gl_Position = uMVP * vec4(skinnedPos.xyz, 1.0);\n" +
	"  vDist = gl_Position.w;\n" +
	"}\n";
	
	private static final String FS = ShaderUtil.FRAG_HEADER +
	"varying vec3 vN; varying vec2 vUv; varying vec3 vWorld; varying float vDist; varying vec4 vVC;\n" +
	"uniform sampler2D uTex; uniform float uHasTex;\n" +
	"uniform vec4 uBaseColor; uniform float uUseVC; uniform float uAlphaMode; uniform float uCutoff;\n" +
	"uniform vec3 uCamPos; uniform vec3 uLightDir; uniform vec3 uLightColor; uniform vec3 uAmbient;\n" +
	"uniform vec3 uFogColor; uniform float uFogStart; uniform float uFogEnd;\n" +
	"void main(){\n" +
	"  vec3 albedo = uBaseColor.rgb;\n" +
	"  float alpha = uBaseColor.a;\n" +
	"  if (uUseVC > 0.5) { albedo *= vVC.rgb; alpha *= vVC.a; }\n" +
	"  if (uHasTex > 0.5) { vec4 t = texture2D(uTex, vUv); albedo *= t.rgb; alpha *= t.a; }\n" +
	"  if (uAlphaMode > 0.5 && alpha < uCutoff) discard;\n" +
	"  vec3 n = normalize(vN);\n" +
	"  if (!gl_FrontFacing) n = -n;\n" +
	"  vec3 L = normalize(uLightDir);\n" +
	"  vec3 V = normalize(uCamPos - vWorld);\n" +
	"  float ndl = dot(n, L);\n" +
	"  float wrap = clamp((ndl + 0.35) / 1.35, 0.0, 1.0);\n" +
	"  float hemi = 0.55 + 0.45 * (0.5 + 0.5 * n.y);\n" +
	"  vec3 H = normalize(L + V);\n" +
	"  float spec = pow(max(dot(n, H), 0.0), 36.0) * 0.15 * step(0.0, ndl);\n" +
	"  float rim = pow(1.0 - max(dot(n, V), 0.0), 3.0) * 0.10;\n" +
	"  vec3 col = albedo * (uAmbient * hemi + uLightColor * wrap) + uLightColor * spec + uAmbient * rim;\n" +
	"  col = mix(col, uFogColor, smoothstep(uFogStart, uFogEnd, vDist));\n" +
	"  gl_FragColor = vec4(col, 1.0);\n" +
	"}\n";
	
	private static int program = 0;
	private static int aPos, aNormal, aUv, aJoints, aWeights, aVColor;
	private static int uMVP, uModel, uJoints, uTex, uHasTex;
	private static int uSkinOn, uBaseColor, uUseVC, uAlphaMode, uCutoff;
	private static int uCamPos, uLightDir, uLightColor, uAmbient;
	private static int uFogColor, uFogStart, uFogEnd;
	
	private static final float[] fogColor = { 0.60f, 0.75f, 0.90f };
	private static float fogStart = 60f, fogEnd = 230f;
	private static final float[] mvp = new float[16];
	
	private SkinShader() {}
	
	public static void invalidate() { program = 0; }
	
	public static void setFog(float[] color, float start, float end) {
		fogColor[0] = color[0]; fogColor[1] = color[1]; fogColor[2] = color[2];
		fogStart = start;
		fogEnd = end;
	}
	
	private static void ensure() {
		if (program != 0) return;
		program = ShaderUtil.createProgram(VS, FS);
		aPos     = GLES20.glGetAttribLocation(program, "aPos");
		aNormal  = GLES20.glGetAttribLocation(program, "aNormal");
		aUv      = GLES20.glGetAttribLocation(program, "aUv");
		aJoints  = GLES20.glGetAttribLocation(program, "aJoints");
		aWeights = GLES20.glGetAttribLocation(program, "aWeights");
		aVColor  = GLES20.glGetAttribLocation(program, "aVColor");
		uSkinOn  = GLES20.glGetUniformLocation(program, "uSkinOn");
		uBaseColor = GLES20.glGetUniformLocation(program, "uBaseColor");
		uUseVC   = GLES20.glGetUniformLocation(program, "uUseVC");
		uAlphaMode = GLES20.glGetUniformLocation(program, "uAlphaMode");
		uCutoff  = GLES20.glGetUniformLocation(program, "uCutoff");
		uMVP     = GLES20.glGetUniformLocation(program, "uMVP");
		uModel   = GLES20.glGetUniformLocation(program, "uModel");
		uJoints  = GLES20.glGetUniformLocation(program, "uJoints");
		uTex     = GLES20.glGetUniformLocation(program, "uTex");
		uHasTex  = GLES20.glGetUniformLocation(program, "uHasTex");
		uCamPos  = GLES20.glGetUniformLocation(program, "uCamPos");
		uLightDir = GLES20.glGetUniformLocation(program, "uLightDir");
		uLightColor = GLES20.glGetUniformLocation(program, "uLightColor");
		uAmbient = GLES20.glGetUniformLocation(program, "uAmbient");
		uFogColor = GLES20.glGetUniformLocation(program, "uFogColor");
		uFogStart = GLES20.glGetUniformLocation(program, "uFogStart");
		uFogEnd   = GLES20.glGetUniformLocation(program, "uFogEnd");
	}
	
	public static void draw(GltfModel model, GltfUploader uploader,
	float[] modelMatrix, float[] jointMatrices,
	float[] vp, float[] camPos,
	float[] lightDir, float[] lightColor, float[] ambient) {
		if (model == null || !model.uploaded || model.meshes == null || model.vboPos == null) return;
		ensure();
		
		GLES20.glDisable(GLES20.GL_CULL_FACE);
		
		GLES20.glUseProgram(program);
		android.opengl.Matrix.multiplyMM(mvp, 0, vp, 0, modelMatrix, 0);
		GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
		GLES20.glUniformMatrix4fv(uModel, 1, false, modelMatrix, 0);
		int jc = Math.min(model.jointCount(), MAX_JOINTS);
		boolean haveJoints = jointMatrices != null && jc > 0 && jointMatrices.length >= jc * 16;
		if (haveJoints) GLES20.glUniformMatrix4fv(uJoints, jc, false, jointMatrices, 0);
		GLES20.glUniform3f(uCamPos, camPos[0], camPos[1], camPos[2]);
		GLES20.glUniform3fv(uLightDir, 1, lightDir, 0);
		GLES20.glUniform3fv(uLightColor, 1, lightColor, 0);
		GLES20.glUniform3fv(uAmbient, 1, ambient, 0);
		GLES20.glUniform3fv(uFogColor, 1, fogColor, 0);
		GLES20.glUniform1f(uFogStart, fogStart);
		GLES20.glUniform1f(uFogEnd, fogEnd);
		GLES20.glUniform1i(uTex, 0);
		
		int primIdx = 0;
		for (int mi = 0; mi < model.meshes.length; mi++) {
			GltfModel.Mesh mesh = model.meshes[mi];
			for (int pi = 0; pi < mesh.primitives.length; pi++) {
				GltfModel.Primitive pr = mesh.primitives[pi];
				int k = primIdx++;
				if (k >= model.vboPos.length) break;
				int vboP = model.vboPos[k];
				int vboI = model.vboIdx[k];
				if (vboP == 0 || vboI == 0) continue;
				int vboN = model.vboNrm[k], vboU = model.vboUv[k], vboC = model.vboCol[k];
				int vboJ = model.vboJoints[k], vboW = model.vboWeights[k];
				
				boolean skin = haveJoints && pr.skinned && vboJ != 0 && vboW != 0;
				GLES20.glUniform1f(uSkinOn, skin ? 1f : 0f);
				GLES20.glUniform4f(uBaseColor, pr.baseColor[0], pr.baseColor[1], pr.baseColor[2], pr.baseColor[3]);
				GLES20.glUniform1f(uUseVC, vboC != 0 ? 1f : 0f);
				GLES20.glUniform1f(uAlphaMode, pr.alphaMask ? 1f : 0f);
				GLES20.glUniform1f(uCutoff, pr.alphaCutoff);
				
				GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
				if (pr.texture != 0 && vboU != 0) {
					GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pr.texture);
					GLES20.glUniform1f(uHasTex, 1f);
				} else {
					GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
					GLES20.glUniform1f(uHasTex, 0f);
				}
				
				GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboP);
				GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 12, 0);
				GLES20.glEnableVertexAttribArray(aPos);
				
				if (vboN != 0) {
					GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboN);
					GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 12, 0);
					GLES20.glEnableVertexAttribArray(aNormal);
				} else {
					GLES20.glDisableVertexAttribArray(aNormal);
					GLES20.glVertexAttrib3f(aNormal, 0f, 1f, 0f);
				}
				
				if (vboU != 0) {
					GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboU);
					GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 8, 0);
					GLES20.glEnableVertexAttribArray(aUv);
				} else {
					GLES20.glDisableVertexAttribArray(aUv);
					GLES20.glVertexAttrib2f(aUv, 0f, 0f);
				}
				
				if (aVColor >= 0) {
					if (vboC != 0) {
						GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboC);
						GLES20.glVertexAttribPointer(aVColor, 4, GLES20.GL_FLOAT, false, 16, 0);
						GLES20.glEnableVertexAttribArray(aVColor);
					} else {
						GLES20.glDisableVertexAttribArray(aVColor);
						GLES20.glVertexAttrib4f(aVColor, 1f, 1f, 1f, 1f);
					}
				}
				
				if (skin) {
					GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboJ);
					GLES20.glVertexAttribPointer(aJoints, 4, GLES20.GL_FLOAT, false, 16, 0);
					GLES20.glEnableVertexAttribArray(aJoints);
					GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboW);
					GLES20.glVertexAttribPointer(aWeights, 4, GLES20.GL_FLOAT, false, 16, 0);
					GLES20.glEnableVertexAttribArray(aWeights);
				} else {
					GLES20.glDisableVertexAttribArray(aJoints);
					GLES20.glDisableVertexAttribArray(aWeights);
					GLES20.glVertexAttrib4f(aJoints, 0f, 0f, 0f, 0f);
					GLES20.glVertexAttrib4f(aWeights, 1f, 0f, 0f, 0f);
				}
				
				GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vboI);
				GLES20.glDrawElements(GLES20.GL_TRIANGLES, model.indexCount[k], model.indexType[k], 0);
			}
		}
		
		GLES20.glDisableVertexAttribArray(aPos);
		GLES20.glDisableVertexAttribArray(aNormal);
		GLES20.glDisableVertexAttribArray(aUv);
		GLES20.glDisableVertexAttribArray(aJoints);
		GLES20.glDisableVertexAttribArray(aWeights);
		if (aVColor >= 0) GLES20.glDisableVertexAttribArray(aVColor);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
		GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
		
		GLES20.glEnable(GLES20.GL_CULL_FACE);
		// give the entity pass its program back (loot / FPP weapon are drawn after the models)
		if (restoreProgram != 0) GLES20.glUseProgram(restoreProgram);
	}

	/** Program that was active before models were drawn (set once per frame by EntityRenderer). */
	public static int restoreProgram = 0;
}
