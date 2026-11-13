package com.avyansh.battleroyale;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.util.Random;

public final class TextureUtil {
	
	private TextureUtil() {}
	
	private static final int DETAIL_SIZE = 256;   // 256 tiles cleanly at 0.1 world-UV scale
	private static final int BUMP_SIZE   = 128;
	
	public static int createDetailTexture() {
		int[] rgba = buildDetailPixels(DETAIL_SIZE);
		
		Bitmap bmp = Bitmap.createBitmap(rgba, DETAIL_SIZE, DETAIL_SIZE, Bitmap.Config.ARGB_8888);
		
		int[] tex = new int[1];
		GLES20.glGenTextures(1, tex, 0);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
		GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
		GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
		bmp.recycle();
		return tex[0];
	}

	public static int createBumpTexture() {
		int[] rgba = buildBumpPixels(BUMP_SIZE);
		
		Bitmap bmp = Bitmap.createBitmap(rgba, BUMP_SIZE, BUMP_SIZE, Bitmap.Config.ARGB_8888);
		
		int[] tex = new int[1];
		GLES20.glGenTextures(1, tex, 0);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
		GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
		GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
		bmp.recycle();
		return tex[0];
	}

	private static int[] buildDetailPixels(int size) {
		int[] out = new int[size * size];
		
		float[] n1 = new float[size * size];   // 4x4 grid period at full tile
		float[] n2 = new float[size * size];   // 8x8
		float[] n3 = new float[size * size];   // 16x16
		float[] n4 = new float[size * size];   // 32x32
		fillTileable(n1, size, 4,  new Random(11));
		fillTileable(n2, size, 8,  new Random(22));
		fillTileable(n3, size, 16, new Random(33));
		fillTileable(n4, size, 32, new Random(44));
	
		Random speckle = new Random(101);
		
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int i = y * size + x;
				// Weighted sum of octaves, tuned for a natural "dirt / stone / grass grain" look.
				float f = n1[i] * 0.35f + n2[i] * 0.28f + n3[i] * 0.22f + n4[i] * 0.15f;
				// Broad contrast stretch: values mostly in [0.4, 1.0] with rare bright patches.
				f = f * 0.75f + 0.45f;
				
				// Add 0..1 speckle (~+/- 0.05) for micro-grain
				float s = (speckle.nextInt(100) / 100f - 0.5f) * 0.06f;
				f += s;
				
				// Clamp / gamma for a slightly "photo"-like, non-linear distribution
				if (f < 0f)   f = 0f;
				if (f > 1f)   f = 1f;
				f = (float) Math.pow(f, 0.85);   // gentle contrast lift
				
				int v = (int) (f * 255f + 0.5f);
				// Neutral grey so the shader's `base = vC * (a + b*detail)` stays hue-correct.
				out[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
			}
		}
		return out;
	}

	private static int[] buildBumpPixels(int size) {
		int[] out = new int[size * size];
		float[] h1 = new float[size * size];
		float[] h2 = new float[size * size];
		float[] h3 = new float[size * size];
		fillTileable(h1, size, 4,  new Random(201));
		fillTileable(h2, size, 8,  new Random(202));
		fillTileable(h3, size, 16, new Random(203));
		
		for (int i = 0; i < out.length; i++) {
			float f = h1[i] * 0.5f + h2[i] * 0.3f + h3[i] * 0.2f;
			int v = (int) (f * 255f + 0.5f);
			out[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
		}
		return out;
	}
	
	private static void fillTileable(float[] out, int size, int period, Random rnd) {
		int gw = period;
		float[] lattice = new float[gw * gw];
		for (int i = 0; i < lattice.length; i++) lattice[i] = rnd.nextFloat();
		
		float step = (float) period / (float) size;   // lattice cells per pixel
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				float fx = x * step;
				float fy = y * step;
				
				int xi = (int) Math.floor(fx);
				int yi = (int) Math.floor(fy);
				float tx = fx - xi;
				float ty = fy - yi;
				
				// quintic smoothstep for C2 continuity
				float u = tx * tx * tx * (tx * (tx * 6f - 15f) + 10f);
				float v = ty * ty * ty * (ty * (ty * 6f - 15f) + 10f);
				
				int x0 = ((xi % gw) + gw) % gw;
				int x1 = (x0 + 1) % gw;
				int y0 = ((yi % gw) + gw) % gw;
				int y1 = (y0 + 1) % gw;
				
				float a = lattice[y0 * gw + x0];
				float b = lattice[y0 * gw + x1];
				float c = lattice[y1 * gw + x0];
				float d = lattice[y1 * gw + x1];
				
				float ab = a + (b - a) * u;
				float cd = c + (d - c) * u;
				out[y * size + x] = ab + (cd - ab) * v;
			}
		}
	}
}
