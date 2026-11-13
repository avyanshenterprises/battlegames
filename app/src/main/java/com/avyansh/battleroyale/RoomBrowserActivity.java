package com.avyansh.battleroyale;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** List of public rooms (join with one tap; password rooms ask for it). */
public class RoomBrowserActivity extends ScreenActivity {
	private LinearLayout list;
	private boolean joining;

	@Override protected String screenTitle() { return "Public rooms"; }

	@Override protected View buildContent() {
		LinearLayout p = Ui.panel(this);
		LinearLayout head = Ui.row(this);
		head.addView(Ui.label(this, "OPEN ROOMS", 12, Ui.GOLD, true), Ui.lp(this, 0, -2, 1f, 0, 0, 0, 0));
		head.addView(Ui.button(this, "REFRESH", Ui.SECONDARY, new View.OnClickListener() { @Override public void onClick(View v) { ServerNet.refreshRooms(); } }));
		p.addView(head, Ui.lp(this, -1, -2, 0, 0, 0, 0, 8));
		list = Ui.col(this);
		p.addView(Ui.scroll(this, list), new LinearLayout.LayoutParams(-1, 0, 1f));
		return p;
	}

	@Override protected void onResume() { super.onResume(); ServerNet.refreshRooms(); }

	@Override protected void onState() {
		if (list == null) return;
		if (joining && ServerNet.room != null) { joining = false; startActivity(new android.content.Intent(this, RoomLobbyActivity.class)); finish(); return; }
		list.removeAllViews();
		if (ServerNet.roomList.isEmpty()) {
			list.addView(Ui.label(this, ServerNet.isOnline() ? "No public rooms right now. Create one!" : "Not connected to the game server.", 13, Ui.MUTED, false));
			return;
		}
		for (int i = 0; i < ServerNet.roomList.size(); i++) {
			final ServerNet.RoomRow r = ServerNet.roomList.get(i);
			LinearLayout row = Ui.row(this);
			row.setBackground(Ui.rect(this, 0x80101C30, Ui.STROKE, 10));
			row.setPadding(dp(12), dp(8), dp(12), dp(8));
			String mode = r.mode.equals("br") ? "Battle Royale" : r.mode.equals("cs") ? "Clash Squad" : "Custom";
			TextView t = Ui.label(this, r.id + "   " + mode + "  " + r.teamSize + "v" + r.teamSize + "  •  " + MapCatalog.name(r.map)
				+ (r.hasPassword ? "  🔒" : ""), 13, Ui.TEXT, true);
			row.addView(t, Ui.lp(this, 0, -2, 1f, 0, 0, 0, 0));
			row.addView(Ui.label(this, r.players + " / " + r.maxPlayers, 13, Ui.GOLD, true), Ui.lp(this, -2, -2, 0, 0, 0, 12, 0));
			row.addView(Ui.button(this, "JOIN", Ui.PRIMARY, new View.OnClickListener() { @Override public void onClick(View v) { join(r); } }));
			list.addView(row, Ui.lp(this, -1, -2, 0, 0, 0, 0, 6));
		}
	}

	private void join(final ServerNet.RoomRow r) {
		if (!r.hasPassword) { joining = true; ServerNet.joinRoom(r.id, ""); return; }
		final android.widget.EditText pw = Ui.field(this, "Room password", true);
		PremiumDialog.show(this, "PASSWORD", "ROOM " + r.id, pw, "JOIN", new Runnable() {
			@Override public void run() { joining = true; ServerNet.joinRoom(r.id, pw.getText().toString()); }
		}, "CANCEL", null);
	}
}
