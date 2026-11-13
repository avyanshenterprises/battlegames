package com.avyansh.battleroyale;

public final class GameMath {
	private GameMath() {}
	
	public static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
	
	public static float lerp(float a, float b, float t) { return a + (b - a) * t; }
	
	public static float smooth(float e0, float e1, float x) {
		float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
		return t * t * (3f - 2f * t);
	}
	
	/** out = a + (b - a) * t, per component. out may be the same array as a or b. */
	public static void mix3(float[] out, float[] a, float[] b, float t) {
		out[0] = a[0] + (b[0] - a[0]) * t;
		out[1] = a[1] + (b[1] - a[1]) * t;
		out[2] = a[2] + (b[2] - a[2]) * t;
	}
}