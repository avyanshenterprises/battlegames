package com.avyansh.battleroyale;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Base of every new menu screen: full-screen scaffold, connection status chip, ServerNet message pump
 * (runs on the UI thread while the screen is visible) and automatic jump into the match when the server starts it.
 */
public abstract class ScreenActivity extends Activity implements ServerNet.Listener {
	protected final Handler ui = new Handler(Looper.getMainLooper());
	private final TextView[] conn = new TextView[1];
	private boolean resumed;

	protected abstract String screenTitle();
	protected abstract View buildContent();
	/** Called whenever the room / friends / queue / connection state changed. */
	protected void onState() {}
	protected void onBackClicked() { finish(); }

	@Override protected void onCreate(Bundle b) {
		super.onCreate(b);
		goFullscreen();
		ServerNet.init(this);
		ServerNet.connect();
		setContentView(Ui.screen(this, screenTitle(), buildContent(), new View.OnClickListener() {
			@Override public void onClick(View v) { onBackClicked(); }
		}, conn));
		updateConn();
	}

	@Override protected void onResume() {
		super.onResume();
		resumed = true;
		ServerNet.listener = this;
		ServerNet.connect();
		ui.removeCallbacks(pump);
		ui.post(pump);
		onState();
	}

	@Override protected void onPause() {
		super.onPause();
		resumed = false;
		ui.removeCallbacks(pump);
		if (ServerNet.listener == this) ServerNet.listener = null;
	}

	private final Runnable pump = new Runnable() {
		@Override public void run() {
			if (!resumed || isFinishing()) return;
			ServerNet.pump();
			updateConn();
			ui.postDelayed(this, 100);
		}
	};

	private void updateConn() {
		TextView c = conn[0];
		if (c == null) return;
		int s = ServerNet.stateCode();
		if (ServerNet.isOnline()) { c.setText("ONLINE  " + ServerNet.rtt() + " ms"); c.setTextColor(Ui.GREEN); }
		else if (s == GameClient.CONNECTING) { c.setText("CONNECTING…"); c.setTextColor(Ui.GOLD); }
		else { c.setText("OFFLINE"); c.setTextColor(Ui.RED); }
	}

	@Override public void onChanged() { if (!isFinishing()) onState(); }

	@Override public void onToast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

	/** The server started a match: open the game. */
	@Override public void onMatchStart() {
		Intent i = new Intent(this, GameActivity.class);
		String id = "";
		if (ServerNet.startMessage() != null && ServerNet.startMessage().get("matchId") != null) id = String.valueOf(ServerNet.startMessage().get("matchId"));
		i.putExtra(GameActivity.EXTRA_GAME_ID, id);
		i.putExtra(GameActivity.EXTRA_ONLINE, true);
		ServerNet.RoomInfo r = ServerNet.room;
		i.putExtra(GameActivity.EXTRA_ROOM, r == null ? "" : r.id);
		i.putExtra(GameActivity.EXTRA_TEAM, ServerNet.myTeam());
		i.putExtra(GameActivity.EXTRA_TEAM_SIZE, r == null ? 1 : r.teamSize);
		startActivity(i);
	}

	@Override public void onBackPressed() { onBackClicked(); }

	protected void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
	protected int dp(float v) { return Ui.dp(this, v); }

	private void goFullscreen() {
		try {
			Window w = getWindow();
			w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				w.setDecorFitsSystemWindows(false);
				WindowInsetsController c = w.getInsetsController();
				if (c != null) {
					c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
					c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
				}
			} else {
				w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
					| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
					| View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
			}
		} catch (RuntimeException ignored) { }
	}
}
