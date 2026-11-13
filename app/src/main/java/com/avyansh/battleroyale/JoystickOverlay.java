package com.avyansh.battleroyale;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
* PUBG / Free Fire style floating joystick overlay.
* Draws a glassy double-ring base + glowing knob with a direction arrow.
* Never intercepts touches — Game3DView handles input.
*/
public class JoystickOverlay extends View implements Game3DView.JoystickListener {
	
	private static final float RING_STROKE_DP    = 2.5f;
	private static final float INNER_RING_DP     = 1.5f;
	private static final float KNOB_RADIUS_RATIO = 0.40f;
	private static final long  FADE_MS           = 140L;
	
	private final Paint baseFill   = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint baseStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint innerRing  = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint knobFill   = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint knobStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	
	private final Path arrowPath = new Path();
	private final float density;
	
	private boolean visible = false;
	private float originX, originY, knobX, knobY, radius;
	
	private long fadeStartNs = 0L;
	private float alphaMul = 0f;
	
	public JoystickOverlay(Context context) {
		super(context);
		density = context.getResources().getDisplayMetrics().density;
		
		baseStroke.setStyle(Paint.Style.STROKE);
		baseStroke.setStrokeWidth(RING_STROKE_DP * density);
		baseStroke.setColor(0xCCFFFFFF);
		
		innerRing.setStyle(Paint.Style.STROKE);
		innerRing.setStrokeWidth(INNER_RING_DP * density);
		innerRing.setColor(0x66FFFFFF);
		
		knobStroke.setStyle(Paint.Style.STROKE);
		knobStroke.setStrokeWidth(2.5f * density);
		knobStroke.setColor(0xFFFFFFFF);
		
		arrowPaint.setStyle(Paint.Style.FILL);
		arrowPaint.setColor(0xFFFFFFFF);
		
		setClickable(false);
		setFocusable(false);
	}
	
	@Override public boolean onTouchEvent(MotionEvent event) {
		return false; // fully pass-through
	}
	
	// -------------------------------------------------- Game3DView.JoystickListener
	@Override public void onJoystick(boolean vis, float ox, float oy,
	float kx, float ky, float r) {
		boolean was = visible;
		visible = vis;
		originX = ox; originY = oy;
		knobX = kx;   knobY = ky;
		radius = r;
		
		if (vis && !was) {
			fadeStartNs = SystemClock.uptimeMillis();
			alphaMul = 0f;
			// build gradients sized to radius
			buildPaintShaders();
		} else if (!vis && was) {
			fadeStartNs = SystemClock.uptimeMillis();
		}
		invalidate();
	}
	
	private void buildPaintShaders() {
		if (radius <= 0f) return;
		// Outer base: soft radial dark → transparent
		baseFill.setShader(new RadialGradient(
		originX, originY, radius,
		new int[]{0x3340C4FF, 0x11FFFFFF, 0x00000000},
		new float[]{0f, 0.55f, 1f},
		Shader.TileMode.CLAMP));
		// Knob: glossy radial
		knobFill.setShader(new RadialGradient(
		knobX, knobY - radius * 0.12f, radius * KNOB_RADIUS_RATIO,
		new int[]{0xFFFFFFFF, 0xFFD9E6F2, 0xFF6FA8D6, 0xFF2E5A82},
		new float[]{0f, 0.45f, 0.85f, 1f},
		Shader.TileMode.CLAMP));
	}
	
	// -------------------------------------------------- Draw
	@Override protected void onDraw(Canvas c) {
		super.onDraw(c);
		if (!visible && alphaMul <= 0.001f) return;
		
		long now = SystemClock.uptimeMillis();
		float t = (now - fadeStartNs) / (float) FADE_MS;
		if (t > 1f) t = 1f;
		alphaMul = visible ? t : 1f - t;
		if (alphaMul <= 0.001f && !visible) return;
		if (t < 1f) postInvalidateOnAnimation();
		
		int a = (int) (255 * alphaMul);
		
		// ---- base ring (outer soft fill + 2 rings) ----
		baseFill.setAlpha((int) (a * 0.55f));
		c.drawCircle(originX, originY, radius, baseFill);
		
		baseStroke.setAlpha((int) (a * 0.85f));
		c.drawCircle(originX, originY, radius, baseStroke);
		
		innerRing.setAlpha((int) (a * 0.65f));
		c.drawCircle(originX, originY, radius * 0.78f, innerRing);
		
		// ---- knob ----
		buildPaintShaders(); // refresh in case knob moved
		knobFill.setAlpha(a);
		c.drawCircle(knobX, knobY, radius * KNOB_RADIUS_RATIO, knobFill);
		
		knobStroke.setAlpha(a);
		c.drawCircle(knobX, knobY, radius * KNOB_RADIUS_RATIO, knobStroke);
		
		// ---- direction arrow inside knob ----
		float dx = knobX - originX;
		float dy = knobY - originY;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len > radius * 0.15f) {
			float nx = dx / len, ny = dy / len;
			float cx = knobX, cy = knobY;
			float ar = radius * KNOB_RADIUS_RATIO * 0.55f;
			arrowPath.reset();
			arrowPath.moveTo(cx + nx * ar,               cy + ny * ar);
			arrowPath.lineTo(cx - nx * ar * 0.4f - ny * ar * 0.6f,
			cy - ny * ar * 0.4f + nx * ar * 0.6f);
			arrowPath.lineTo(cx - nx * ar * 0.4f + ny * ar * 0.6f,
			cy - ny * ar * 0.4f - nx * ar * 0.6f);
			arrowPath.close();
			arrowPaint.setAlpha((int) (a * 0.9f));
			c.drawPath(arrowPath, arrowPaint);
		}
	}
}
