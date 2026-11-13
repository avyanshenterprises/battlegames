package com.avyansh.battleroyale;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class TextSprite {
	private static final String VS =
	"uniform mat4 uMVP;\n" +
	"attribute vec2 aPos;\n" +
	"attribute vec2 aUv;\n" +
	"varying vec2 vUv;\n" +
	"void main(){ vUv = aUv; gl_Position = uMVP * vec4(aPos, 0.0, 1.0); }\n";
	private static final String FS = ShaderUtil.FRAG_HEADER +
	"uniform sampler2D uTex;\n" +
	"uniform float uAlpha;\n" +
	"varying vec2 vUv;\n" +
	"void main(){ gl_FragColor = texture2D(uTex, vUv) * uAlpha; }\n";
	
	private int program, texture, aPos, aUv, uMvp, uTex, uAlpha;
	private FloatBuffer quad;
	private int widthPx = 1, heightPx = 1;
	private boolean ready = false;
	
	public void init() {
		program = ShaderUtil.createProgram(VS, FS);
		aPos = GLES20.glGetAttribLocation(program, "aPos");
		aUv = GLES20.glGetAttribLocation(program, "aUv");
		uMvp = GLES20.glGetUniformLocation(program, "uMVP");
		uTex = GLES20.glGetUniformLocation(program, "uTex");
		uAlpha = GLES20.glGetUniformLocation(program, "uAlpha");
		float[] q = {
			-0.5f, -0.5f, 0f, 1f,
			0.5f, -0.5f, 1f, 1f,
			-0.5f,  0.5f, 0f, 0f,
			0.5f,  0.5f, 1f, 0f };
		quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
		quad.put(q).position(0);
		texture = 0;
		ready = false;
	}
	
	public int getWidthPx() { return widthPx; }
	public int getHeightPx() { return heightPx; }
	public float getAspect() { return (float) widthPx / (float) heightPx; }
	public boolean isReady() { return ready; }
	
	private static int blend(int a, int b, float t) {
		int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
		int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
		int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
		return Color.rgb(r, g, bl);
	}
	
	public void setText(String text, int sizePx, int color, int glowColor, boolean glow) {
		Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
		base.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
		base.setLetterSpacing(glow ? 0.12f : 0.02f);
		
		int size = sizePx;
		float tw = 0f, pad = 0f;
		Paint.FontMetrics fm = null;
		int w = 1, h = 1;
		do {
			base.setTextSize(size);
			tw = base.measureText(text);
			pad = glow ? size * 0.7f : size * 0.3f;
			fm = base.getFontMetrics();
			w = (int) Math.ceil(tw + 2f * pad);
			h = (int) Math.ceil(fm.descent - fm.ascent + 2f * pad);
			if (w <= 2048) break;
			size = (int) (size * 0.85f);
		} while (size > 12);
		
		Bitmap bmp = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
		Canvas c = new Canvas(bmp);
		float x = pad;
		float y = pad - fm.ascent;
		
		if (glow) {
			Paint g1 = new Paint(base);
			g1.setColor(glowColor);
			g1.setStyle(Paint.Style.FILL_AND_STROKE);
			g1.setStrokeWidth(size * 0.10f);
			g1.setMaskFilter(new BlurMaskFilter(Math.max(1f, pad * 0.6f), BlurMaskFilter.Blur.NORMAL));
			c.drawText(text, x, y, g1);
			c.drawText(text, x, y, g1);
			
			Paint g2 = new Paint(base);
			g2.setColor(glowColor);
			g2.setStyle(Paint.Style.FILL_AND_STROKE);
			g2.setStrokeWidth(size * 0.06f);
			g2.setMaskFilter(new BlurMaskFilter(Math.max(1f, pad * 0.22f), BlurMaskFilter.Blur.NORMAL));
			c.drawText(text, x, y, g2);
			
			Paint outline = new Paint(base);
			outline.setStyle(Paint.Style.STROKE);
			outline.setStrokeWidth(size * 0.13f);
			outline.setStrokeJoin(Paint.Join.ROUND);
			outline.setColor(Color.rgb(6, 22, 70));
			c.drawText(text, x, y, outline);
			
			Paint fill = new Paint(base);
			fill.setStyle(Paint.Style.FILL);
			fill.setShader(new LinearGradient(0f, y + fm.ascent, 0f, y + fm.descent,
			color, blend(color, glowColor, 0.45f), Shader.TileMode.CLAMP));
			c.drawText(text, x, y, fill);
		} else {
			Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
			bg.setColor(Color.argb(120, 0, 0, 0));
			c.drawRoundRect(new RectF(0f, 0f, w, h), pad, pad, bg);
			Paint fill = new Paint(base);
			fill.setColor(color);
			c.drawText(text, x, y, fill);
		}
		
		if (texture == 0) {
			int[] t = new int[1];
			GLES20.glGenTextures(1, t, 0);
			texture = t[0];
		}
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
		GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
		widthPx = bmp.getWidth();
		heightPx = bmp.getHeight();
		bmp.recycle();
		ready = true;
	}
	
	public void draw(float[] mvp, float alpha) {
		if (!ready) return;
		GLES20.glDisable(GLES20.GL_CULL_FACE);
		GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
		GLES20.glUseProgram(program);
		GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
		GLES20.glUniform1f(uAlpha, alpha);
		GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
		GLES20.glUniform1i(uTex, 0);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
		quad.position(0);
		GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
		GLES20.glEnableVertexAttribArray(aPos);
		quad.position(2);
		GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad);
		GLES20.glEnableVertexAttribArray(aUv);
		GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
		GLES20.glDisableVertexAttribArray(aPos);
		GLES20.glDisableVertexAttribArray(aUv);
		GLES20.glDisable(GLES20.GL_BLEND);
	}

	// NEW: Draw with custom color (for hit marker)
	public void drawColor(float[] mvp, float alpha, float r, float g, float b) {
		if (!ready) return;
		GLES20.glDisable(GLES20.GL_CULL_FACE);
		GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
		GLES20.glUseProgram(program);
		GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
		GLES20.glUniform1f(uAlpha, alpha);
		GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
		GLES20.glUniform1i(uTex, 0);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
		quad.position(0);
		GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
		GLES20.glEnableVertexAttribArray(aPos);
		quad.position(2);
		GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad);
		GLES20.glEnableVertexAttribArray(aUv);
		GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
		GLES20.glDisableVertexAttribArray(aPos);
		GLES20.glDisableVertexAttribArray(aUv);
		GLES20.glDisable(GLES20.GL_BLEND);
	}
}
