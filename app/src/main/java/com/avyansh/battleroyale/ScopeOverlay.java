
package com.avyansh.battleroyale;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class ScopeOverlay {
	
	public static final int RED_DOT = 0;
	public static final int SCOPE_2X = 1;
	public static final int SCOPE_3X = 2;
	public static final int SCOPE_4X = 3;
	public static final int SCOPE_6X = 4;
	
	private static final int SCOPE_COUNT = 5;
	
	private static final float[] ZOOMS = {
		1f, 2f, 3f, 4f, 6f
	};
	
	private static final String VS =
	"attribute vec2 aPos;" +
	"attribute vec2 aUv;" +
	"varying vec2 vUv;" +
	"void main(){" +
	"vUv=aUv;" +
	"gl_Position=vec4(aPos,0.0,1.0);" +
	"}";
	
	private static final String FS =
	ShaderUtil.FRAG_HEADER +
	"varying vec2 vUv;" +
	"uniform sampler2D uTex;" +
	"uniform float uAlpha;" +
	"void main(){" +
	"gl_FragColor=texture2D(uTex,vUv)*uAlpha;" +
	"}";
	
	private int program;
	private int aPos, aUv, uTex, uAlpha;
	
	private final int[] textures = new int[SCOPE_COUNT];
	
	private int width, height;
	private boolean needBuild = true;
	
	private final FloatBuffer quad =
	ByteBuffer.allocateDirect(16 * 4)
	.order(ByteOrder.nativeOrder())
	.asFloatBuffer();
	
	public void init() {
		
		program = ShaderUtil.createProgram(VS, FS);
		
		aPos = GLES20.glGetAttribLocation(program, "aPos");
		aUv = GLES20.glGetAttribLocation(program, "aUv");
		
		uTex = GLES20.glGetUniformLocation(program, "uTex");
		uAlpha = GLES20.glGetUniformLocation(program, "uAlpha");
		
		quad.put(new float[] {
			-1,-1, 0,1,
			1,-1, 1,1,
			-1, 1, 0,0,
			1, 1, 1,0
		}).position(0);
		
		needBuild = true;
	}
	
	public void resize(int w, int h, float density) {
		
		width = Math.max(2,w);
		height = Math.max(2,h);
		
		needBuild = true;
	}
	
	public float getZoom(int scope) {
		return ZOOMS[clamp(scope)];
	}
	
	public int getScopeCount() {
		return SCOPE_COUNT;
	}
	
	private int clamp(int scope) {
		return Math.max(0,Math.min(SCOPE_COUNT-1,scope));
	}
	
	private void build() {
		
		needBuild = false;
		
		float scale = Math.min(1f,1280f/width);
		
		int bw = Math.max(2,(int)(width*scale));
		int bh = Math.max(2,(int)(height*scale));
		
		for(int i=0;i<SCOPE_COUNT;i++) {
			
			Bitmap bitmap = createScope(bw,bh,i);
			
			if(textures[i] == 0) {
				GLES20.glGenTextures(1,textures,i);
			}
			
			GLES20.glBindTexture(
			GLES20.GL_TEXTURE_2D,textures[i]);
			
			GLES20.glTexParameteri(
			GLES20.GL_TEXTURE_2D,
			GLES20.GL_TEXTURE_MIN_FILTER,
			GLES20.GL_LINEAR);
			
			GLES20.glTexParameteri(
			GLES20.GL_TEXTURE_2D,
			GLES20.GL_TEXTURE_MAG_FILTER,
			GLES20.GL_LINEAR);
			
			GLES20.glTexParameteri(
			GLES20.GL_TEXTURE_2D,
			GLES20.GL_TEXTURE_WRAP_S,
			GLES20.GL_CLAMP_TO_EDGE);
			
			GLES20.glTexParameteri(
			GLES20.GL_TEXTURE_2D,
			GLES20.GL_TEXTURE_WRAP_T,
			GLES20.GL_CLAMP_TO_EDGE);
			
			GLUtils.texImage2D(
			GLES20.GL_TEXTURE_2D,0,bitmap,0);
			
			bitmap.recycle();
		}
	}
	
	private Bitmap createScope(int w,int h,int type) {
		
		Bitmap bitmap = Bitmap.createBitmap(
		w,h,Bitmap.Config.ARGB_8888);
		
		Canvas canvas = new Canvas(bitmap);
		
		float cx = w * 0.5f;
		float cy = h * 0.5f;
		
		float s = h / 1080f;
		
		Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
		
		Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
		clear.setXfermode(
		new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
		
		if(type == RED_DOT) {
			drawRedDot(canvas,p,clear,cx,cy,w,h,s);
		} else {
			drawMagnifiedScope(
			canvas,p,clear,cx,cy,w,h,s,type);
		}
		
		return bitmap;
	}
	
	private void drawRedDot(
	Canvas c,Paint p,Paint clear,
	float cx,float cy,int w,int h,float s) {
		
		float r = Math.min(w,h)*0.50f;
		
		c.drawColor(0x8C000000);
		c.drawCircle(cx,cy,r,clear);
		
		p.setStyle(Paint.Style.STROKE);
		p.setStrokeWidth(4*s);
		p.setColor(Color.WHITE);
		c.drawCircle(cx,cy,r*0.34f,p);
		
		p.setStrokeWidth(2*s);
		p.setColor(0x99FF3030);
		c.drawCircle(cx,cy,r*0.34f+3*s,p);
		
		p.setStyle(Paint.Style.FILL);
		p.setColor(Color.RED);
		c.drawCircle(cx,cy,3*s,p);
	}
	
	private void drawMagnifiedScope(
	Canvas c,Paint p,Paint clear,
	float cx,float cy,int w,int h,
	float s,int type) {
		
		float min = Math.min(w,h);
		
		float radius;
		float vignette;
		
		if(type == SCOPE_2X) {
			radius = min*0.43f;
			vignette = 0.93f;
		} else if(type == SCOPE_3X) {
			radius = min*0.46f;
			vignette = 0.91f;
		} else if(type == SCOPE_4X) {
			radius = min*0.47f;
			vignette = 0.90f;
		} else {
			radius = min*0.47f;
			vignette = 0.88f;
		}
		
		// Black outer mask
		c.drawColor(Color.BLACK);
		c.drawCircle(cx,cy,radius,clear);
		
		// Lens shading
		p.setShader(new RadialGradient(
		cx,cy,radius,
		new int[] {
			0x00000000,
			0x00000000,
			0x44000000,
			0xDD000000
		},
		new float[] {
			0f, vignette-0.15f,
			vignette,1f
		},
		Shader.TileMode.CLAMP));
		
		c.drawCircle(cx,cy,radius,p);
		p.setShader(null);
		
		p.setStyle(Paint.Style.STROKE);
		
		// Outer metallic rim
		p.setStrokeWidth(5*s);
		p.setColor(0xFF292929);
		c.drawCircle(cx,cy,radius,p);
		
		p.setStrokeWidth(2*s);
		p.setColor(0x88FFFFFF);
		c.drawCircle(cx,cy,radius-5*s,p);
		
		float gap = type == SCOPE_2X
		? radius*0.13f : radius*0.045f;
		
		// Reticle color
		int reticle = type == SCOPE_2X
		? 0xFFFF3838 : 0xFF171717;
		
		p.setColor(reticle);
		p.setStrokeWidth(type == SCOPE_6X ? 2.4f*s : 2f*s);
		
		// Main crosshair
		c.drawLine(cx-radius,cy,cx-gap,cy,p);
		c.drawLine(cx+gap,cy,cx+radius,cy,p);
		
		c.drawLine(cx,cy-radius,cx,cy-gap,p);
		c.drawLine(cx,cy+gap,cx,cy+radius,p);
		
		// Different reticle patterns
		if(type == SCOPE_2X) {
			
			p.setStrokeWidth(2.5f*s);
			p.setColor(0xCCFFFFFF);
			
			c.drawCircle(cx,cy,radius*0.28f,p);
			
		} else if(type == SCOPE_3X) {
			
			p.setStrokeWidth(2*s);
			drawTicks(c,p,cx,cy,radius*0.40f,s,8);
			
			p.setStyle(Paint.Style.STROKE);
			c.drawCircle(cx,cy,radius*0.12f,p);
			
		} else if(type == SCOPE_4X) {
			
			p.setStrokeWidth(2*s);
			drawTicks(c,p,cx,cy,radius*0.55f,s,12);
			
			c.drawCircle(cx,cy,radius*0.09f,p);
			
			// Range marks
			for(int i=1;i<=4;i++) {
				float y = cy + radius*(0.12f*i);
				
				c.drawLine(
				cx-radius*0.045f,y,
				cx+radius*0.045f,y,p);
			}
			
		} else if(type == SCOPE_6X) {
			
			// Sniper heavy posts
			p.setColor(0xFF111111);
			p.setStrokeWidth(9*s);
			
			c.drawLine(
			cx-radius,cy,
			cx-radius*0.43f,cy,p);
			
			c.drawLine(
			cx+radius*0.43f,cy,
			cx+radius,cy,p);
			
			c.drawLine(
			cx,cy-radius,
			cx,cy-radius*0.43f,p);
			
			c.drawLine(
			cx,cy+radius*0.43f,
			cx,cy+radius,p);
			
			p.setStrokeWidth(2*s);
			
			drawTicks(c,p,cx,cy,radius*0.40f,s,12);
			
			p.setStyle(Paint.Style.STROKE);
			
			c.drawCircle(cx,cy,radius*0.07f,p);
		}
		
		// Center red aiming point
		p.setStyle(Paint.Style.FILL);
		
		p.setColor(0xFFFF2525);
		
		float dot = type == SCOPE_6X ? 3.5f*s : 2.5f*s;
		
		c.drawCircle(cx,cy,dot,p);
	}
	
	private void drawTicks(
	Canvas c,Paint p,float cx,float cy,
	float radius,float s,int count) {
		
		p.setStyle(Paint.Style.STROKE);
		
		for(int i=1;i<=count;i++) {
			
			float t = radius*i/count;
			float size = (i%2==0 ? 9f : 5f)*s;
			
			c.drawLine(
			cx-t,cy-size,cx-t,cy+size,p);
			
			c.drawLine(
			cx+t,cy-size,cx+t,cy+size,p);
			
			c.drawLine(
			cx-size,cy+t,cx+size,cy+t,p);
		}
	}
	
	public void render(float alpha,boolean sniper) {
		
		render(alpha,sniper ? SCOPE_6X : RED_DOT);
	}
	
	public void render(float alpha,int scope) {
		
		if(alpha<=0.01f || program==0 || width<2) {
			return;
		}
		
		if(needBuild) build();
		
		int index = clamp(scope);
		int texture = textures[index];
		
		if(texture==0) return;
		
		GLES20.glDisable(GLES20.GL_DEPTH_TEST);
		GLES20.glDisable(GLES20.GL_CULL_FACE);
		
		GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glBlendFunc(
		GLES20.GL_ONE,
		GLES20.GL_ONE_MINUS_SRC_ALPHA);
		
		GLES20.glUseProgram(program);
		
		GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
		GLES20.glBindTexture(
		GLES20.GL_TEXTURE_2D,texture);
		
		GLES20.glUniform1i(uTex,0);
		GLES20.glUniform1f(
		uAlpha,Math.min(1f,alpha));
		
		GLES20.glBindBuffer(
		GLES20.GL_ARRAY_BUFFER,0);
		
		quad.position(0);
		
		GLES20.glVertexAttribPointer(
		aPos,2,GLES20.GL_FLOAT,
		false,16,quad);
		
		GLES20.glEnableVertexAttribArray(aPos);
		
		quad.position(2);
		
		GLES20.glVertexAttribPointer(
		aUv,2,GLES20.GL_FLOAT,
		false,16,quad);
		
		GLES20.glEnableVertexAttribArray(aUv);
		
		GLES20.glDrawArrays(
		GLES20.GL_TRIANGLE_STRIP,0,4);
		
		GLES20.glDisableVertexAttribArray(aPos);
		GLES20.glDisableVertexAttribArray(aUv);
		
		GLES20.glDisable(GLES20.GL_BLEND);
		
		GLES20.glEnable(GLES20.GL_CULL_FACE);
		GLES20.glEnable(GLES20.GL_DEPTH_TEST);
	}
	
	public void release() {
		
		for(int i=0;i<SCOPE_COUNT;i++) {
			if(textures[i]!=0) {
				GLES20.glDeleteTextures(
				1,textures,i);
				textures[i]=0;
			}
		}
		
		if(program!=0) {
			GLES20.glDeleteProgram(program);
			program=0;
		}
	}
}
