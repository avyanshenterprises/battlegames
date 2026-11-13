package com.avyansh.battleroyale;

import android.opengl.GLES20;
import android.util.Log;

public final class ShaderUtil {
	private ShaderUtil() {}
	
	public static final String TAG = "AvyanshGL";
	
	/** Put at the start of every fragment shader. */
	public static final String FRAG_HEADER =
	"#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
	"precision highp float;\n" +
	"#else\n" +
	"precision mediump float;\n" +
	"#endif\n";
	
	public static int createProgram(String vs, String fs) {
		int v = compile(GLES20.GL_VERTEX_SHADER, vs);
		int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
		int p = GLES20.glCreateProgram();
		GLES20.glAttachShader(p, v);
		GLES20.glAttachShader(p, f);
		GLES20.glLinkProgram(p);
		int[] ok = new int[1];
		GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
		if (ok[0] == 0) {
			String log = GLES20.glGetProgramInfoLog(p);
			Log.e(TAG, "Program link failed: " + log);
			GLES20.glDeleteProgram(p);
			throw new RuntimeException("Program link failed: " + log);
		}
		return p;
	}
	
	private static int compile(int type, String src) {
		int s = GLES20.glCreateShader(type);
		GLES20.glShaderSource(s, src);
		GLES20.glCompileShader(s);
		int[] ok = new int[1];
		GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
		if (ok[0] == 0) {
			String log = GLES20.glGetShaderInfoLog(s);
			Log.e(TAG, "Shader compile failed: " + log + "\n" + src);
			GLES20.glDeleteShader(s);
			throw new RuntimeException("Shader compile failed: " + log);
		}
		return s;
	}
}