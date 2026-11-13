package com.avyansh.battleroyale;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
* Drives the XML HUD (res/layout/hud_layout.xml): button actions, live values, joystick visuals,
* emote picker, map panel and the drag-to-move HUD editor.
*/
public class HudController implements Game3DView.JoystickListener {
	private final Context ctx;
	private final View root;
	private final Game3DView game;
	private final GameEngine eng;
	private final Handler h = new Handler(Looper.getMainLooper());
	private final SharedPreferences prefs;
	
	// HudController.java - Replace DRAG_IDS array
	private final int[] DRAG_IDS = {
		R.id.hud_fire, R.id.hud_jump, R.id.hud_prone, R.id.hud_ads, 
		R.id.hud_reload, R.id.hud_melee, R.id.hud_switch, R.id.hud_run, R.id.hud_medkit,
		R.id.hud_drop_weapon, R.id.hud_view, R.id.hud_emote, R.id.hud_map,
		R.id.hud_minimap, R.id.hud_vitals, R.id.hud_joystick,
		R.id.hud_weapon_box, R.id.hud_compass, R.id.hud_medkit_count,
		R.id.hud_alive, R.id.hud_kills, R.id.hud_health_text,
		R.id.hud_ammo, R.id.hud_weapon_name, R.id.hud_players_text,
		R.id.hud_armor_text, R.id.hud_map_panel, R.id.hud_notice,
		R.id.hud_feed, R.id.hud_plane_jump, R.id.hud_respawn
	};
	private View joystick, knob, stickZone, mapPanel, emotePanel, editBar;
	private TextView alive, kills, compass, notice, feed, health, ammo, weaponName, weaponSlots, medCount, respawn, stickMode;
	private ProgressBar hpBar, armorBar, healBar, reloadBar;
	private MapView mapView;
	private View planeJump, minimap, lootPanel;
	private TextView playersText, armorText;
	private android.widget.LinearLayout lootList;
	private MapView miniView;
	private int totalPlayers;
	private volatile String lastLootKey = "";
	private boolean editing, floatingStick, mapOpen, lootDismissed;
	/** Online matches: no respawn (GameActivity shows a "back to lobby" button instead). */
	public boolean allowRespawn = true;
	private View selected;
	private boolean running;
	// PUBG-style sprint lock: push the stick to its rim, straight up, and the character sprints until you let go
	private static final float SPRINT_PUSH = 0.92f;      // fraction of the stick radius
	private static final float SPRINT_FORWARD = 0.75f;   // how "upwards" the push has to be (0..1)
	private static final float SPRINT_KNOB_SCALE = 1.18f;
	private boolean runButtonOn, sprintLock;
	private int firePtr = -1;
	private float fireLastX, fireLastY;
	private String lastNotice = "", lastFeed = "";
	
	private final Runnable tick = new Runnable() {
		@Override public void run() {
			if (!running) return;
			update();
			h.postDelayed(this, 60);
		}
	};
	
	public HudController(Context c, View hudRoot, Game3DView g) {
		ctx = c; root = hudRoot; game = g; eng = g.getEngine();
		prefs = c.getSharedPreferences("hud_layout", Context.MODE_PRIVATE);
		floatingStick = prefs.getBoolean("floating", false);
		bind();
		root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
			@Override public void onGlobalLayout() { syncStick(); }
		});
		applyLayout();
		game.setJoystickListener(this);
	}
	
	public void start() { running = true; h.post(tick); }
	public void stop() { running = false; h.removeCallbacks(tick); }
	public void show(boolean on) { root.setVisibility(on ? View.VISIBLE : View.GONE); if (on) { totalPlayers = 0; start(); root.post(new Runnable() { public void run() { syncStick(); sizeMap(); } }); } else stop(); }
	
	// ------------------------------------------------------------------ setup
	private <T extends View> T f(int id) { return root.findViewById(id); }
	
	private void bind() {
		joystick = f(R.id.hud_joystick); knob = f(R.id.hud_knob); stickZone = f(R.id.hud_joystick_zone);
		mapPanel = f(R.id.hud_map_panel); emotePanel = f(R.id.hud_emote_panel); editBar = f(R.id.hud_edit_bar);
		alive = f(R.id.hud_alive); kills = f(R.id.hud_kills); compass = f(R.id.hud_compass); notice = f(R.id.hud_notice); feed = f(R.id.hud_feed);
		health = f(R.id.hud_health_text); ammo = f(R.id.hud_ammo); weaponName = f(R.id.hud_weapon_name); weaponSlots = f(R.id.hud_weapon_slots); medCount = f(R.id.hud_medkit_count);
		respawn = f(R.id.hud_respawn); stickMode = f(R.id.hud_edit_stick); planeJump = f(R.id.hud_plane_jump);
		hpBar = f(R.id.hud_health); armorBar = f(R.id.hud_armor); healBar = f(R.id.hud_heal); reloadBar = f(R.id.hud_reload_bar);
		mapView = f(R.id.hud_map_view); mapView.setEngine(eng);
		minimap = f(R.id.hud_minimap); miniView = f(R.id.hud_minimap_view); miniView.setMini(true); miniView.setEngine(eng);
		minimap.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (!editing) setMap(!mapOpen); } });
		lootPanel = f(R.id.hud_loot_panel); lootList = f(R.id.hud_loot_list);
		View lootTitle = lootPanel.findViewById(R.id.textview112);
		if (lootTitle != null) lootTitle.setVisibility(View.GONE);
		playersText = f(R.id.hud_players_text); armorText = f(R.id.hud_armor_text);
		stickMode.setText(floatingStick ? "STICK: FLOATING" : "STICK: FIXED");
		
		bindFire(f(R.id.hud_fire));
		press(f(R.id.hud_jump), new Runnable() { public void run() { game.requestJump(); } });
		press(planeJump, new Runnable() { public void run() { game.requestJump(); } });
		press(f(R.id.hud_reload), new Runnable() { public void run() { game.requestReload(); } });
		press(f(R.id.hud_melee), new Runnable() { public void run() { game.requestMelee(); } });
		press(f(R.id.hud_switch), new Runnable() { public void run() { game.requestSwitch(); } });
		press(f(R.id.hud_drop_weapon), new Runnable() { public void run() { game.requestDropWeapon(); } });
		press(respawn, new Runnable() { public void run() { game.requestRespawn(); } });
		press(f(R.id.hud_medkit), new Runnable() { public void run() { eng.healing.tryStart(eng.player, eng); } });
		toggle(f(R.id.hud_prone), new Toggle() { public void on(boolean v) { game.setCrouch(v); } });
		toggle(f(R.id.hud_run), new Toggle() { public void on(boolean v) { runButtonOn = v; game.setRun(v || sprintLock); } });
		toggle(f(R.id.hud_ads), new Toggle() { public void on(boolean v) { game.setAiming(v); } });
		final View eye = f(R.id.hud_view);
		eye.setSelected(!eng.firstPerson);
		press(eye, new Runnable() { public void run() { game.toggleView(); eye.setSelected(!eng.firstPerson); } });
		press(f(R.id.hud_emote), new Runnable() { public void run() { openEmotes(); } });
		
		press(f(R.id.hud_map), new Runnable() { public void run() { setMap(!mapOpen); } });
		press(f(R.id.hud_edit), new Runnable() { public void run() { setEditing(true); } });
		press(f(R.id.hud_map_close), new Runnable() { public void run() { setMap(false); } });
		press(f(R.id.hud_map_zoom_in), new Runnable() { public void run() { mapView.zoomBy(1.4f); } });
		press(f(R.id.hud_map_zoom_out), new Runnable() { public void run() { mapView.zoomBy(1f / 1.4f); } });
		press(f(R.id.hud_map_center), new Runnable() { public void run() { mapView.recenter(); } });
		emotePanel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { emotePanel.setVisibility(View.GONE); } });
		// Editor toolbar controls must stay clickable while editing is enabled.
		bindEditorAction(R.id.hud_edit_minus, new Runnable() { public void run() { scaleSel(-0.1f); } });
		bindEditorAction(R.id.hud_edit_plus, new Runnable() { public void run() { scaleSel(0.1f); } });
		bindEditorAction(R.id.hud_edit_stick, new Runnable() { public void run() {
				floatingStick = !floatingStick;
				prefs.edit().putBoolean("floating", floatingStick).apply();
				stickMode.setText(floatingStick ? "STICK: FLOATING" : "STICK: FIXED");
				syncStick();
			} });
		bindEditorAction(R.id.hud_edit_reset, new Runnable() { public void run() {
				prefs.edit().clear().apply();
				floatingStick = false;
				selected = null;
				stickMode.setText("STICK: FIXED");
				for (int id : DRAG_IDS) {
					View v = f(id);
					v.setTranslationX(0f); v.setTranslationY(0f);
					v.setScaleX(1f); v.setScaleY(1f); v.setSelected(false);
				}
				syncStick();
			} });
		bindEditorAction(R.id.hud_edit_done, new Runnable() { public void run() { setEditing(false); } });
	}
	
	private void bindEditorAction(int id, final Runnable action) {
		View v = f(id);
		if (v == null) return;
		v.setClickable(true);
		v.setFocusable(false);
		v.setOnTouchListener(null);
		v.setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View view) { action.run(); }
		});
	}
	
	private interface Toggle { void on(boolean v); }
	
	/** run on finger DOWN (instant response) */
	private void press(View v, final Runnable r) {
		v.setOnTouchListener(new View.OnTouchListener() {
			@Override public boolean onTouch(View view, MotionEvent e) {
				if (editing) return false;
				if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { view.setPressed(true); r.run(); }
				else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) view.setPressed(false);
				return true;
			}
		});
	}
	
	private void toggle(View v, final Toggle t) {
		v.setOnTouchListener(new View.OnTouchListener() {
			@Override public boolean onTouch(View view, MotionEvent e) {
				if (editing) return false;
				if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { boolean n = !view.isSelected(); view.setSelected(n); t.on(n); }
				return true;
			}
		});
	}
	
	private void bindFire(View v) {
		v.setOnTouchListener(new View.OnTouchListener() {
			@Override public boolean onTouch(View view, MotionEvent e) {
				if (editing) return false;
				switch (e.getActionMasked()) {
					case MotionEvent.ACTION_DOWN:
					firePtr = e.getPointerId(0); view.setPressed(true); game.setFireHeld(true);
					fireLastX = e.getRawX(); fireLastY = e.getRawY();
					return true;
					case MotionEvent.ACTION_MOVE: {
						int pi = e.findPointerIndex(firePtr);
						if (pi >= 0) {
							float x = e.getRawX(pi), y = e.getRawY(pi);
							eng.addLook((x - fireLastX) * 0.35f, (y - fireLastY) * 0.35f);   // drag the fire button to aim while shooting
							fireLastX = x; fireLastY = y;
						}
						return true;
					}
					case MotionEvent.ACTION_UP:
					case MotionEvent.ACTION_CANCEL:
					view.setPressed(false); game.setFireHeld(false); firePtr = -1;
					return true;
				}
				return true;
			}
		});
	}
	
	// ------------------------------------------------------------------ joystick visuals
	/** Enables / disables sprint depending on how far and in which direction the stick is pushed. */
	private void updateSprintLock(boolean visible, float ox, float oy, float kx, float ky, float radius) {
		boolean lock = false;
		if (visible && radius > 0f) {
			float dx = kx - ox, dy = ky - oy;
			float len = (float) Math.sqrt(dx * dx + dy * dy);
			lock = len >= radius * SPRINT_PUSH && (-dy / len) >= SPRINT_FORWARD;
		}
		if (lock == sprintLock) return;
		sprintLock = lock;
		game.setRun(runButtonOn || lock);
		knob.setScaleX(lock ? SPRINT_KNOB_SCALE : 1f);
		knob.setScaleY(lock ? SPRINT_KNOB_SCALE : 1f);
	}
	
	@Override public void onJoystick(final boolean visible, final float ox, final float oy, final float kx, final float ky, final float radius) {
		updateSprintLock(visible, ox, oy, kx, ky, radius);
		knob.post(new Runnable() {
			@Override public void run() {
				float cx = joystick.getLeft() + joystick.getTranslationX() + joystick.getWidth() / 2f;
				float cy = joystick.getTop() + joystick.getTranslationY() + joystick.getHeight() / 2f;
				if (!visible) { knob.setTranslationX(0); knob.setTranslationY(0); return; }
				float baseX = floatingStick ? ox : cx, baseY = floatingStick ? oy : cy;
				if (floatingStick) { joystick.setTranslationX(ox - (joystick.getLeft() + joystick.getWidth() / 2f)); joystick.setTranslationY(oy - (joystick.getTop() + joystick.getHeight() / 2f)); }
				knob.setTranslationX(kx - baseX); knob.setTranslationY(ky - baseY);
			}
		});
	}
	
	private void syncStick() {
		if (joystick.getWidth() == 0) return;
		float sc = joystick.getScaleX();
		float r = joystick.getWidth() * sc / 2f;
		float cx = joystick.getLeft() + joystick.getTranslationX() + joystick.getWidth() / 2f;
		float cy = joystick.getTop() + joystick.getTranslationY() + joystick.getHeight() / 2f;
		game.setStickRadius(r);
		game.setStickAnchor(cx, cy, !floatingStick);
		game.setStickZone(0f, 0f, root.getWidth() * 0.45f, root.getHeight());
		if (!floatingStick) joystick.setAlpha(1f);
	}
	
	private void sizeMap() {
		ViewGroup.LayoutParams lp = mapPanel.getLayoutParams();
		int w = root.getWidth() / 2;
		if (w > 0 && lp.width != w) { lp.width = w; mapPanel.setLayoutParams(lp); }
	}
	
	// ------------------------------------------------------------------ panels
	private void setMap(boolean on) {
		mapOpen = on; sizeMap();
		mapPanel.setVisibility(on ? View.VISIBLE : View.GONE);
		if (on) mapView.recenter();
		f(R.id.hud_map).setSelected(on);
	}
	
	private void openEmotes() {
		GridLayout g = f(R.id.hud_emote_grid);
		g.removeAllViews();
		String[] names = eng.emoteNames;
		if (names == null || names.length == 0) { notice.setText("Emotes are still loading..."); return; }
		for (int i = 0; i < names.length; i++) {
			final int idx = i;
			TextView t = new TextView(ctx);
			String n = names[i].replaceAll("(?i)^emote[_\\- ]?", "").replace('_', ' ').replace('-', ' ');
			if (n.length() > 0) n = Character.toUpperCase(n.charAt(0)) + n.substring(1);
			t.setText(n);
			t.setTextColor(0xFFFFFFFF); t.setTextSize(12); t.setGravity(android.view.Gravity.CENTER);
			t.setBackgroundResource(R.drawable.bg_btn_secondary);
			GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
			lp.width = (int) (96 * ctx.getResources().getDisplayMetrics().density); lp.height = (int) (44 * ctx.getResources().getDisplayMetrics().density);
			lp.setMargins(6, 6, 6, 6);
			t.setLayoutParams(lp);
			t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { eng.emoteRequest = idx; emotePanel.setVisibility(View.GONE); } });
			g.addView(t);
		}
		emotePanel.setVisibility(View.VISIBLE);
	}
	
	// ------------------------------------------------------------------ HUD editor
	// HudController.java - Update setEditing to include all views
	private void setEditing(boolean on) {
		editing = on;
		editBar.setVisibility(on ? View.VISIBLE : View.GONE);
		if (on) {
			editBar.setClickable(true);
			editBar.setFocusable(false);
			editBar.bringToFront();
		}
		selected = null;
		
		for (int id : DRAG_IDS) {
			final View v = f(id);
			if (v == null) continue;
			
			if (on) {
				v.setAlpha(0.9f);
				v.setClickable(true);
				v.setOnTouchListener(new View.OnTouchListener() {
					float dx, dy;
					final int[] rootLoc = new int[2];
					@Override public boolean onTouch(View view, MotionEvent e) {
						root.getLocationOnScreen(rootLoc);
						float rx = e.getRawX() - rootLoc[0], ry = e.getRawY() - rootLoc[1];
						switch (e.getActionMasked()) {
							case MotionEvent.ACTION_DOWN:
							selected = view;
							dx = view.getX() - rx;
							dy = view.getY() - ry;
							for (int k : DRAG_IDS) {
								View other = f(k);
								if (other != null) other.setSelected(false);
							}
							view.setSelected(true);
							view.bringToFront();
							return true;
							case MotionEvent.ACTION_MOVE:
							float nx = rx + dx, ny = ry + dy;
							view.setX(Math.max(0f, Math.min(root.getWidth() - view.getWidth() * view.getScaleX(), nx)));
							view.setY(Math.max(0f, Math.min(root.getHeight() - view.getHeight() * view.getScaleY(), ny)));
							return true;
							case MotionEvent.ACTION_UP:
							saveView(view);
							syncStick();
							return true;
						}
						return true;
					}
				});
			}
		}
		
		if (!on) {
			// Restore gameplay handlers
			for (int k : DRAG_IDS) {
				View v = f(k);
				if (v == null) continue;
				v.setOnTouchListener(null);
				v.setSelected(false);
				v.setAlpha(1f);
				v.setClickable(true);
			}
			root.post(new Runnable() {
				@Override public void run() {
					bind();
					applyLayout();
					syncStick();
				}
			});
		}
	}
	
	private void scaleSel(float d) {
		if (selected == null) return;
		float s = Math.max(0.5f, Math.min(2.2f, selected.getScaleX() + d));
		selected.setScaleX(s); selected.setScaleY(s);
		saveView(selected); syncStick();
	}
	
	private void saveView(View v) {
		String k = root.getResources().getResourceEntryName(v.getId());
		prefs.edit().putFloat(k + "_x", v.getTranslationX() / Math.max(1, root.getWidth()))
		.putFloat(k + "_y", v.getTranslationY() / Math.max(1, root.getHeight()))
		.putFloat(k + "_s", v.getScaleX()).apply();
	}
	
	private void applyLayout() {
		for (int id : DRAG_IDS) {
			View v = f(id);
			String k = root.getResources().getResourceEntryName(id);
			if (!prefs.contains(k + "_s")) { v.setTranslationX(0); v.setTranslationY(0); v.setScaleX(1f); v.setScaleY(1f); continue; }
			final View vv = v; final String kk = k;
			root.post(new Runnable() { public void run() {
					vv.setTranslationX(prefs.getFloat(kk + "_x", 0f) * root.getWidth());
					vv.setTranslationY(prefs.getFloat(kk + "_y", 0f) * root.getHeight());
					float s = prefs.getFloat(kk + "_s", 1f); vv.setScaleX(s); vv.setScaleY(s);
					syncStick();
				} });
		}
	}
	
	// ------------------------------------------------------------------ loot pop-up
	private void updateLoot(boolean on) {
		java.util.ArrayList<Loot> near = on ? eng.loot.nearby(eng.player.x, eng.player.y, eng.player.z) : new java.util.ArrayList<Loot>();
		StringBuilder key = new StringBuilder();
		for (Loot l : near) key.append(System.identityHashCode(l)).append(',');
		String k = key.toString();
		if (!k.equals(lastLootKey)) lootDismissed = false;
		if (k.equals(lastLootKey)) {
			if (near.isEmpty()) lootPanel.setVisibility(View.GONE);
			else if (!lootDismissed) lootPanel.setVisibility(View.VISIBLE);
			return;
		}
		lastLootKey = k;
		lootList.removeAllViews();
		lootPanel.setVisibility(near.isEmpty() || lootDismissed ? View.GONE : View.VISIBLE);
		// Premium compact header with a real close control. Rebuilt only when loot changes.
		android.widget.LinearLayout header = new android.widget.LinearLayout(ctx);
		header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
		header.setGravity(android.view.Gravity.CENTER_VERTICAL);
		TextView title = new TextView(ctx); title.setText("NEARBY LOOT"); title.setTextColor(0xFFFFD36A);
		title.setTextSize(10); title.setTypeface(null, android.graphics.Typeface.BOLD);
		header.addView(title, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
		TextView close = new TextView(ctx); close.setText("✕"); close.setTextColor(0xFFFFFFFF);
		close.setTextSize(16); close.setGravity(android.view.Gravity.CENTER);
		close.setBackgroundResource(R.drawable.bg_btn_round);
		close.setContentDescription("Close nearby loot");
		close.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
				lootDismissed = true; lootPanel.setVisibility(View.GONE);
			} });
		header.addView(close, new android.widget.LinearLayout.LayoutParams((int)(30*ctx.getResources().getDisplayMetrics().density), (int)(28*ctx.getResources().getDisplayMetrics().density)));
		lootList.addView(header);
		float d = ctx.getResources().getDisplayMetrics().density;
		for (final Loot l : near) {
			android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
			row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
			row.setGravity(android.view.Gravity.CENTER_VERTICAL);
			row.setPadding(0, (int) (5 * d), 0, 0);
			android.widget.ImageView ic = new android.widget.ImageView(ctx);
			ic.setImageResource(LootManager.iconOf(l.type));
			ic.setColorFilter(LootManager.colorOf(l.type));
			ic.setPadding((int) (6 * d), (int) (6 * d), (int) (6 * d), (int) (6 * d));
			ic.setBackgroundResource(R.drawable.bg_btn_round);
			row.addView(ic, new android.widget.LinearLayout.LayoutParams((int) (36 * d), (int) (36 * d)));
			android.widget.LinearLayout txt = new android.widget.LinearLayout(ctx);
			txt.setOrientation(android.widget.LinearLayout.VERTICAL);
			TextView n = new TextView(ctx); n.setText(LootManager.nameOf(l.type)); n.setTextColor(LootManager.colorOf(l.type)); n.setTextSize(12); n.setTypeface(null, android.graphics.Typeface.BOLD);
			TextView sub = new TextView(ctx); sub.setText(LootManager.subOf(l.type)); sub.setTextColor(0xFFB5C9D5); sub.setTextSize(9);
			txt.addView(n); txt.addView(sub);
			android.widget.LinearLayout.LayoutParams tp = new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
			tp.setMargins((int) (8 * d), 0, (int) (6 * d), 0);
			row.addView(txt, tp);
			// The complete item row is the hit target: no separate PICK button.
			row.setBackgroundResource(R.drawable.bg_btn_round);
			row.setClickable(true);
			row.setFocusable(true);
			row.setContentDescription("Pick up " + LootManager.nameOf(l.type));
			row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
					v.setEnabled(false);
					game.queueEvent(new Runnable() { public void run() {
							eng.loot.pickup(l, eng);
							lastLootKey = "";
						} });
				} });
			lootList.addView(row);
		}
	}
	
	// ------------------------------------------------------------------ live values
	private static final String[] DIRS = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };
	
	private void update() {
		if (eng.lobby) return;
		MatchPhase ph = eng.match.phase;
		Player p = eng.player;
		boolean playing = ph == MatchPhase.PLAYING;
		boolean air = ph == MatchPhase.AIRPLANE, fall = ph == MatchPhase.FREEFALL || ph == MatchPhase.PARACHUTE;
		boolean dead = ph == MatchPhase.DEAD || ph == MatchPhase.WIN;
		if (!editing) {
			int vis = playing ? View.VISIBLE : View.GONE;
			for (int id : new int[] { R.id.hud_fire, R.id.hud_prone, R.id.hud_ads, R.id.hud_reload, R.id.hud_melee, R.id.hud_switch, R.id.hud_run, R.id.hud_medkit, R.id.hud_drop_weapon, R.id.hud_emote, R.id.hud_vitals, R.id.hud_weapon_box, R.id.hud_medkit_count })
			f(id).setVisibility(vis);
			f(R.id.hud_jump).setVisibility(playing ? View.VISIBLE : View.GONE);
			f(R.id.hud_view).setVisibility(air ? View.GONE : View.VISIBLE);
			f(R.id.hud_vitals).setVisibility((playing || fall) ? View.VISIBLE : View.GONE);
			planeJump.setVisibility(air ? View.VISIBLE : View.GONE);
			respawn.setVisibility(dead && allowRespawn ? View.VISIBLE : View.GONE);
			respawn.setText(ph == MatchPhase.WIN ? "PLAY AGAIN" : "RESPAWN");
		}
		minimap.setVisibility((playing || fall || air || dead) ? View.VISIBLE : View.GONE);
		updateLoot(playing && p.alive);
		int aliveN = (p.alive ? 1 : 0) + eng.bots.aliveCount() + ((Net.active && Net.inMatch) ? Net.aliveRemotes() : 0);
		if (aliveN > totalPlayers) totalPlayers = aliveN;
		alive.setText(String.valueOf(aliveN));
		playersText.setText("PLAYERS " + aliveN + " / " + Math.max(totalPlayers, aliveN));
		armorText.setText(String.valueOf((int) p.armor));
		kills.setText(String.valueOf(p.kills));
		float yaw = eng.camera.yaw; if (yaw < 0) yaw += 360f;
		compass.setText(DIRS[((int) Math.round(yaw / 45f)) & 7] + "  " + Math.round(yaw) + "\u00B0");
		
		String n;
		switch (ph) {
			case AIRPLANE: n = "PLANE \u2014 tap JUMP to drop"; break;
			case FREEFALL: n = "FREEFALL"; break;
			case PARACHUTE: n = "PARACHUTE"; break;
			case PLAYING: n = (Net.active && Net.inMatch) ? "ONLINE \u2014 " + (1 + Net.otherCount()) + " players" : "LANDED \u2014 fight!"; break;
			case DEAD: n = "YOU DIED"; break;
			case WIN: n = "WINNER WINNER!"; break;
			default: n = "";
		}
		if (!n.equals(lastNotice)) { notice.setText(n); notice.setVisibility(n.length() == 0 ? View.GONE : View.VISIBLE); lastNotice = n; }
		
		String fd = Net.killFeedTime > 0f ? Net.killFeed : "";
		if (!fd.equals(lastFeed)) { feed.setText(fd); feed.setVisibility(fd.length() == 0 ? View.GONE : View.VISIBLE); lastFeed = fd; }
		
		hpBar.setProgress((int) (100f * Math.max(0f, p.health) / Math.max(1f, p.maxHealth)));
		health.setText((int) Math.ceil(p.health) + " / " + (int) p.maxHealth);
		armorBar.setProgress((int) (100f * Math.max(0f, p.armor) / Math.max(1f, p.maxArmor)));
		medCount.setText("x" + p.healCount());
		boolean healing = eng.healing.isHealing();
		healBar.setVisibility(healing ? View.VISIBLE : View.GONE);
		if (healing) healBar.setProgress((int) (100f * eng.healing.progress()));
		Weapon w = eng.weapons.current();
		ammo.setText(w == null ? "-- / --" : w.ammo + " / " + eng.weapons.reserveOf(w));
		weaponName.setText(w == null ? "NO WEAPON" : w.name);
		StringBuilder slots = new StringBuilder();
		for (int si = 0; si < WeaponManager.SLOTS; si++) {
			Weapon sw = eng.weapons.slot(si);
			if (si > 0) slots.append("   ");
			slots.append(si == eng.weapons.currentIndex() && sw != null ? "\u25B6" : " ").append(si + 1).append(' ').append(sw == null ? "-" : sw.name);
		}
		weaponSlots.setText(slots.toString());
		boolean rl = eng.weapons.isReloading();
		reloadBar.setVisibility(rl ? View.VISIBLE : View.INVISIBLE);
		if (rl) reloadBar.setProgress((int) (100f * eng.weapons.reloadProgress()));
	}
}
