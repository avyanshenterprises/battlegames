package com.avyansh.battleroyale;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Free Fire-inspired programmatic UI kit shared by all the new screens (dark glass panels, gold/orange accents). */
public final class Ui {
	private Ui() {}

	public static final int BG1 = 0xFF070D18, BG2 = 0xFF12223C, PANEL = 0xE61A2A44, PANEL_DARK = 0xCC0E1829, STROKE = 0x4DFFFFFF;
	public static final int GOLD = 0xFFFFCD4B, ORANGE = 0xFFFF8A1F, TEXT = 0xFFEAF1F7, MUTED = 0xFFA4B5C5;
	public static final int GREEN = 0xFF7DFF9A, RED = 0xFFFF6B6B, BLUE = 0xFF4FA3FF;
	public static final int PRIMARY = 0, SECONDARY = 1, DANGER = 2;

	public static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

	public static GradientDrawable rect(Context c, int fill, int stroke, float radiusDp) {
		GradientDrawable d = new GradientDrawable();
		d.setColor(fill);
		d.setCornerRadius(dp(c, radiusDp));
		if (stroke != 0) d.setStroke(dp(c, 1), stroke);
		return d;
	}

	public static GradientDrawable gradient(Context c, int top, int bottom, float radiusDp) {
		GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { top, bottom });
		d.setCornerRadius(dp(c, radiusDp));
		return d;
	}

	public static TextView label(Context c, String s, float sp, int color, boolean bold) {
		TextView t = new TextView(c);
		t.setText(s);
		t.setTextSize(sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		return t;
	}

	public static LinearLayout col(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }

	public static LinearLayout row(Context c) {
		LinearLayout l = new LinearLayout(c);
		l.setOrientation(LinearLayout.HORIZONTAL);
		l.setGravity(Gravity.CENTER_VERTICAL);
		return l;
	}

	public static LinearLayout panel(Context c) {
		LinearLayout l = col(c);
		l.setBackground(rect(c, PANEL, STROKE, 14));
		int p = dp(c, 12);
		l.setPadding(p, p, p, p);
		return l;
	}

	/** width/height in dp (or -1 match, -2 wrap), weight, margins in dp (l,t,r,b). */
	public static LinearLayout.LayoutParams lp(Context c, int w, int h, float weight, int ml, int mt, int mr, int mb) {
		LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w > 0 ? dp(c, w) : w, h > 0 ? dp(c, h) : h, weight);
		p.setMargins(dp(c, ml), dp(c, mt), dp(c, mr), dp(c, mb));
		return p;
	}

	public static void pressAnim(final View v) {
		v.setOnTouchListener(new View.OnTouchListener() {
			@Override public boolean onTouch(View view, MotionEvent e) {
				int a = e.getActionMasked();
				if (a == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start();
				else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
				return false;   // let the click through
			}
		});
	}

	public static TextView button(Context c, String text, int kind, View.OnClickListener l) {
		TextView t = label(c, text, 14, kind == SECONDARY ? TEXT : 0xFF1A1205, true);
		t.setGravity(Gravity.CENTER);
		int ph = dp(c, 18), pv = dp(c, 10);
		t.setPadding(ph, pv, ph, pv);
		if (kind == PRIMARY) t.setBackground(gradient(c, 0xFFFFC04A, 0xFFFF8A1F, 12));
		else if (kind == DANGER) { t.setTextColor(Color.WHITE); t.setBackground(gradient(c, 0xFFFF6B6B, 0xFFC0392B, 12)); }
		else t.setBackground(rect(c, 0xCC22375C, STROKE, 12));
		t.setClickable(true);
		t.setFocusable(true);
		if (l != null) t.setOnClickListener(l);
		pressAnim(t);
		return t;
	}

	public static void setEnabled(TextView b, boolean on) { b.setEnabled(on); b.setAlpha(on ? 1f : 0.45f); }

	public static TextView chip(Context c, String text, boolean selected, View.OnClickListener l) {
		TextView t = label(c, text, 12, TEXT, true);
		t.setGravity(Gravity.CENTER);
		int ph = dp(c, 14), pv = dp(c, 8);
		t.setPadding(ph, pv, ph, pv);
		t.setClickable(true);
		if (l != null) t.setOnClickListener(l);
		setChip(t, selected);
		pressAnim(t);
		return t;
	}

	public static void setChip(TextView t, boolean selected) {
		Context c = t.getContext();
		t.setBackground(selected ? gradient(c, 0xFFFFC04A, 0xFFFF8A1F, 10) : rect(c, 0xB3223457, STROKE, 10));
		t.setTextColor(selected ? 0xFF1A1205 : TEXT);
	}

	public static EditText field(Context c, String hint, boolean password) {
		EditText e = new EditText(c);
		e.setHint(hint);
		e.setHintTextColor(0x88A4B5C5);
		e.setTextColor(TEXT);
		e.setTextSize(14);
		e.setSingleLine(true);
		e.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
		e.setBackground(rect(c, 0xB30B1424, STROKE, 10));
		int p = dp(c, 12);
		e.setPadding(p, dp(c, 9), p, dp(c, 9));
		return e;
	}

	public static void section(Context c, ViewGroup parent, String title) {
		TextView t = label(c, title.toUpperCase(java.util.Locale.US), 11, GOLD, true);
		t.setPadding(0, dp(c, 12), 0, dp(c, 6));
		parent.addView(t);
	}

	public static void popIn(View v, long delayMs) {
		v.setAlpha(0f);
		v.setTranslationY(dpPx(v, 14));
		v.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(260).start();
	}

	private static float dpPx(View v, float dp) { return dp * v.getResources().getDisplayMetrics().density; }

	/** Full-screen scaffold: gradient background, top bar with back button, title and connection chip. */
	public static FrameLayout screen(final Activity a, String title, View content, View.OnClickListener onBack, TextView[] connOut) {
		FrameLayout root = new FrameLayout(a);
		root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { BG2, BG1 }));
		LinearLayout page = col(a);
		int pad = dp(a, 14);
		page.setPadding(pad, pad, pad, pad);
		LinearLayout bar = row(a);
		TextView back = button(a, "‹  BACK", SECONDARY, onBack);
		bar.addView(back, lp(a, -2, -2, 0, 0, 0, 12, 0));
		TextView ttl = label(a, title.toUpperCase(java.util.Locale.US), 20, GOLD, true);
		ttl.setShadowLayer(6f, 0f, 2f, 0xFF000000);
		bar.addView(ttl, lp(a, 0, -2, 1f, 0, 0, 0, 0));
		TextView conn = label(a, "OFFLINE", 11, RED, true);
		conn.setPadding(dp(a, 10), dp(a, 5), dp(a, 10), dp(a, 5));
		conn.setBackground(rect(a, 0xB30B1424, STROKE, 10));
		bar.addView(conn);
		if (connOut != null && connOut.length > 0) connOut[0] = conn;
		page.addView(bar, lp(a, -1, -2, 0, 0, 0, 0, 8));
		page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
		root.addView(page, new FrameLayout.LayoutParams(-1, -1));
		return root;
	}

	public static ScrollView scroll(Context c, View child) {
		ScrollView s = new ScrollView(c);
		s.setFillViewport(true);
		s.setVerticalScrollBarEnabled(false);
		s.addView(child, new ViewGroup.LayoutParams(-1, -2));
		return s;
	}
}
