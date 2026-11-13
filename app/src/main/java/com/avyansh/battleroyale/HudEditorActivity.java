package com.avyansh.battleroyale;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Screen 10: HUD editor. Shows the real in-game HUD (res/layout/hud_layout.xml); drag any element, change its size,
 * then save. Positions are stored in the same "hud_layout" preferences HudController applies at the start of every match.
 */
public class HudEditorActivity extends Activity {
	private static final int[] IDS = {
		R.id.hud_fire, R.id.hud_jump, R.id.hud_prone, R.id.hud_ads, R.id.hud_reload, R.id.hud_melee, R.id.hud_switch, R.id.hud_run, R.id.hud_medkit,
		R.id.hud_drop_weapon, R.id.hud_view, R.id.hud_emote, R.id.hud_map, R.id.hud_minimap, R.id.hud_vitals, R.id.hud_joystick,
		R.id.hud_weapon_box, R.id.hud_compass, R.id.hud_medkit_count, R.id.hud_alive, R.id.hud_kills, R.id.hud_health_text,
		R.id.hud_ammo, R.id.hud_weapon_name, R.id.hud_players_text, R.id.hud_armor_text, R.id.hud_notice, R.id.hud_feed,
		R.id.hud_plane_jump, R.id.hud_respawn
	};
	private SharedPreferences prefs;
	private FrameLayout frame;
	private View hud, selected;
	private TextView info;

	@Override protected void onCreate(Bundle b) {
		super.onCreate(b);
		goFullscreen();
		prefs = getSharedPreferences("hud_layout", MODE_PRIVATE);
		frame = new FrameLayout(this);
		frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xFF1B2E1F, 0xFF0B1220 }));
		hud = getLayoutInflater().inflate(R.layout.hud_layout, frame, false);
		frame.addView(hud, new FrameLayout.LayoutParams(-1, -1));
		hideNonEditable();
		for (int i = 0; i < IDS.length; i++) {
			final View v = hud.findViewById(IDS[i]);
			if (v == null) continue;
			v.setVisibility(View.VISIBLE);
			v.setAlpha(0.92f);
			v.setClickable(true);
			v.setOnClickListener(null);
			v.setOnTouchListener(new DragListener());
		}
		frame.addView(controls(), new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
		setContentView(frame);
		hud.post(new Runnable() { @Override public void run() { applySaved(); } });
	}

	private void hideNonEditable() {
		int[] hide = { R.id.hud_map_panel, R.id.hud_edit_bar, R.id.hud_emote_panel, R.id.hud_loot_panel, R.id.hud_edit, R.id.hud_joystick_zone };
		for (int i = 0; i < hide.length; i++) { View v = hud.findViewById(hide[i]); if (v != null) v.setVisibility(View.GONE); }
	}

	private View controls() {
		LinearLayout bar = Ui.row(this);
		bar.setBackground(Ui.rect(this, 0xE60E1829, Ui.STROKE, 14));
		int p = Ui.dp(this, 8);
		bar.setPadding(p, p, p, p);
		info = Ui.label(this, "Tap an element, drag it, resize with + / -", 12, Ui.TEXT, true);
		info.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 12), 0);
		bar.addView(info);
		bar.addView(Ui.button(this, "−", Ui.SECONDARY, new View.OnClickListener() { @Override public void onClick(View v) { scale(-0.1f); } }), Ui.lp(this, -2, -2, 0, 0, 0, 6, 0));
		bar.addView(Ui.button(this, "+", Ui.SECONDARY, new View.OnClickListener() { @Override public void onClick(View v) { scale(0.1f); } }), Ui.lp(this, -2, -2, 0, 0, 0, 6, 0));
		bar.addView(Ui.button(this, "RESET", Ui.SECONDARY, new View.OnClickListener() { @Override public void onClick(View v) { reset(); } }), Ui.lp(this, -2, -2, 0, 0, 0, 6, 0));
		bar.addView(Ui.button(this, "SAVE", Ui.PRIMARY, new View.OnClickListener() { @Override public void onClick(View v) { save(); finish(); } }), Ui.lp(this, -2, -2, 0, 0, 0, 6, 0));
		bar.addView(Ui.button(this, "CANCEL", Ui.DANGER, new View.OnClickListener() { @Override public void onClick(View v) { finish(); } }));
		return bar;
	}

	private void select(View v) {
		if (selected != null) selected.setForeground(null);
		selected = v;
		if (v != null) {
			v.setForeground(Ui.rect(this, 0x33FFCD4B, Ui.GOLD, 8));
			String n = getResources().getResourceEntryName(v.getId()).replace("hud_", "").replace('_', ' ');
			info.setText("Selected: " + n.toUpperCase(java.util.Locale.US));
		}
	}

	private void scale(float d) {
		if (selected == null) return;
		float s = Math.max(0.5f, Math.min(2.2f, selected.getScaleX() + d));
		selected.setScaleX(s);
		selected.setScaleY(s);
	}

	private final class DragListener implements View.OnTouchListener {
		private float lastX, lastY;
		@Override public boolean onTouch(View v, MotionEvent e) {
			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN:
					select(v);
					lastX = e.getRawX(); lastY = e.getRawY();
					v.getParent().requestDisallowInterceptTouchEvent(true);
					return true;
				case MotionEvent.ACTION_MOVE:
					v.setTranslationX(v.getTranslationX() + e.getRawX() - lastX);
					v.setTranslationY(v.getTranslationY() + e.getRawY() - lastY);
					lastX = e.getRawX(); lastY = e.getRawY();
					return true;
				default:
					return true;
			}
		}
	}

	private void save() {
		SharedPreferences.Editor ed = prefs.edit();
		float w = Math.max(1, hud.getWidth()), h = Math.max(1, hud.getHeight());
		for (int i = 0; i < IDS.length; i++) {
			View v = hud.findViewById(IDS[i]);
			if (v == null) continue;
			String k = getResources().getResourceEntryName(IDS[i]);
			ed.putFloat(k + "_x", v.getTranslationX() / w).putFloat(k + "_y", v.getTranslationY() / h).putFloat(k + "_s", v.getScaleX());
		}
		// Commit before closing so the next match always reads the final layout, even after process death.
		boolean stored = ed.commit();
		if (stored) setResult(RESULT_OK);
	}

	private void applySaved() {
		for (int i = 0; i < IDS.length; i++) {
			View v = hud.findViewById(IDS[i]);
			if (v == null) continue;
			String k = getResources().getResourceEntryName(IDS[i]);
			if (!prefs.contains(k + "_s")) continue;
			v.setTranslationX(prefs.getFloat(k + "_x", 0f) * hud.getWidth());
			v.setTranslationY(prefs.getFloat(k + "_y", 0f) * hud.getHeight());
			float s = prefs.getFloat(k + "_s", 1f);
			v.setScaleX(s); v.setScaleY(s);
		}
	}

	private void reset() {
		for (int i = 0; i < IDS.length; i++) {
			View v = hud.findViewById(IDS[i]);
			if (v == null) continue;
			v.setTranslationX(0f); v.setTranslationY(0f); v.setScaleX(1f); v.setScaleY(1f);
		}
		prefs.edit().clear().commit();
	}

	private void goFullscreen() {
		try {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				getWindow().setDecorFitsSystemWindows(false);
				WindowInsetsController c = getWindow().getInsetsController();
				if (c != null) { c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
			} else {
				getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
					| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
			}
		} catch (RuntimeException ignored) { }
	}
}
