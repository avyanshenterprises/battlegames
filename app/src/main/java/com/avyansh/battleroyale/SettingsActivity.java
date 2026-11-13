package com.avyansh.battleroyale;

import android.content.SharedPreferences;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Screen 9: Settings. Camera/scope sensitivity, aim assist, graphics, voice chat and server address. Values are read by GameEngine.applyPrefs / GameActivity. */
public class SettingsActivity extends ScreenActivity {
	public static final String PREFS = "avyansh_game_settings";
	private SharedPreferences sp;
	private EditText server;
	private TextView serverState;

	@Override protected String screenTitle() { return "Settings"; }

	@Override protected View buildContent() {
		sp = getSharedPreferences(PREFS, MODE_PRIVATE);
		LinearLayout col = Ui.col(this);

		LinearLayout cam = Ui.panel(this);
		Ui.section(this, cam, "Camera sensitivity");
		slider(cam, "Look sensitivity", "sensitivity", 0.5f, 3.0f, 1.0f);
		slider(cam, "Hip / third-person", "sens_hip", 0.2f, 2.0f, 1.0f);
		slider(cam, "Aiming (ADS)", "sens_ads", 0.2f, 2.0f, 0.75f);
		slider(cam, "Scope 2x - 3x", "sens_2x", 0.1f, 2.0f, 0.55f);
		slider(cam, "Scope 4x", "sens_4x", 0.1f, 2.0f, 0.38f);
		slider(cam, "Scope 6x+", "sens_6x", 0.1f, 2.0f, 0.26f);
		toggle(cam, "Gyroscope aim", "gyro", false);
		col.addView(cam, Ui.lp(this, -1, -2, 0, 0, 0, 0, 10));

		LinearLayout aim = Ui.panel(this);
		Ui.section(this, aim, "Aim assist");
		toggle(aim, "Aim assist (friction + gentle tracking, never auto-aims)", "aa_on", true);
		slider(aim, "Aim assist strength", "aa_strength", 0f, 1f, 0.5f);
		col.addView(aim, Ui.lp(this, -1, -2, 0, 0, 0, 0, 10));

		LinearLayout gfx = Ui.panel(this);
		Ui.section(this, gfx, "Graphics");
		LinearLayout q = Ui.row(this);
		final String[] names = { "PERFORMANCE", "BALANCED", "ULTRA" };
		final java.util.ArrayList<TextView> chips = new java.util.ArrayList<TextView>();
		int sel = sp.getInt("quality", 1);
		for (int i = 0; i < names.length; i++) {
			final int idx = i;
			final TextView c = Ui.chip(this, names[i], i == sel, null);
			c.setOnClickListener(new View.OnClickListener() {
				@Override public void onClick(View v) { sp.edit().putInt("quality", idx).apply(); for (int k = 0; k < chips.size(); k++) Ui.setChip(chips.get(k), k == idx); }
			});
			chips.add(c);
			q.addView(c, Ui.lp(this, -2, -2, 0, 0, 0, 8, 0));
		}
		gfx.addView(q);
		col.addView(gfx, Ui.lp(this, -1, -2, 0, 0, 0, 0, 10));

		LinearLayout hud = Ui.panel(this);
		Ui.section(this, hud, "HUD");
		hud.addView(Ui.button(this, "EDIT HUD LAYOUT", Ui.SECONDARY, new View.OnClickListener() {
			@Override public void onClick(View v) { startActivity(new android.content.Intent(SettingsActivity.this, HudEditorActivity.class)); }
		}), Ui.lp(this, 240, -2, 0, 0, 0, 0, 0));
		col.addView(hud, Ui.lp(this, -1, -2, 0, 0, 0, 0, 10));

		LinearLayout voice = Ui.panel(this);
		Ui.section(this, voice, "Squad voice chat");
		toggle(voice, "Mute incoming squad voice", "voice_muted", false);
		VoiceChat.muted = sp.getBoolean("voice_muted", false);
		voice.addView(Ui.label(this, "Hold the mic button in a squad match to talk. Voice goes only to living squad mates.", 11, Ui.MUTED, false));
		col.addView(voice, Ui.lp(this, -1, -2, 0, 0, 0, 0, 10));

        LinearLayout account = Ui.panel(this);
        Ui.section(this, account, "Avyansh account");
        account.addView(Ui.label(this, "Signed in as " + SessionManager.username(this) + "  •  " + SessionManager.uid(this), 12, Ui.MUTED, false));
        account.addView(Ui.button(this, "LOG OUT", Ui.SECONDARY, new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String uid = SessionManager.uid(SettingsActivity.this);
                final String token = SessionManager.token(SettingsActivity.this);
                AuthManager.get().logout(uid, token, () -> {
                    ServerNet.resetForLogout();
                    SessionManager.clear(SettingsActivity.this);
                    getSharedPreferences("net", MODE_PRIVATE).edit().remove("uid").remove("name").apply();
                    Intent go = new Intent(SettingsActivity.this, MainActivity.class);
                    go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(go); finish();
                });
            }
        }), Ui.lp(this, 240, -2, 0, 0, 0, 0, 0));
        col.addView(account, Ui.lp(this, -1, -2, 0, 0, 0, 0, 10));
		return Ui.scroll(this, col);
	}

	@Override protected void onState() {
		if (serverState == null) return;
		serverState.setText(!ServerNet.isConfigured() ? "Firebase online mode (no dedicated server)" : ServerNet.isOnline() ? "Connected - ping " + ServerNet.rtt() + " ms" : "Not connected to " + ServerNet.serverAddress());
	}

	private void slider(LinearLayout parent, final String title, final String key, final float min, final float max, float def) {
		final TextView t = Ui.label(this, "", 12, Ui.TEXT, true);
		float v = Math.max(min, Math.min(max, sp.getFloat(key, def)));
		t.setText(title + "   " + String.format(java.util.Locale.US, "%.2f", v));
		SeekBar sb = new SeekBar(this);
		sb.setMax(100);
		sb.setProgress(Math.round((v - min) / (max - min) * 100f));
		sb.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.GOLD));
		sb.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.GOLD));
		sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override public void onProgressChanged(SeekBar s, int p, boolean user) {
				float val = min + (max - min) * p / 100f;
				t.setText(title + "   " + String.format(java.util.Locale.US, "%.2f", val));
				if (user) sp.edit().putFloat(key, val).apply();
			}
			@Override public void onStartTrackingTouch(SeekBar s) { }
			@Override public void onStopTrackingTouch(SeekBar s) { }
		});
		t.setPadding(0, dp(6), 0, 0);
		parent.addView(t);
		parent.addView(sb);
	}

	private void toggle(LinearLayout parent, String title, final String key, boolean def) {
		final TextView c = Ui.chip(this, "", sp.getBoolean(key, def), null);
		final String label = title;
		c.setText(label + ":  " + (sp.getBoolean(key, def) ? "ON" : "OFF"));
		final boolean d = def;
		c.setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View v) {
				boolean now = !sp.getBoolean(key, d);
				sp.edit().putBoolean(key, now).apply();
				c.setText(label + ":  " + (now ? "ON" : "OFF"));
				Ui.setChip(c, now);
				if (key.equals("voice_muted")) VoiceChat.muted = now;
			}
		});
		parent.addView(c, Ui.lp(this, -2, -2, 0, 0, 8, 0, 4));
	}
}
