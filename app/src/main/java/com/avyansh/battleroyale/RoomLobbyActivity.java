package com.avyansh.battleroyale;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Screen 5: Room lobby. Members by team, ready/unready, team selection, host controls, invite friends, kick, start. */
public class RoomLobbyActivity extends ScreenActivity {
	private TextView idText, info;
	private LinearLayout teams, hostBar;
	private TextView readyBtn, startBtn;
	private String lastSig = "";

	@Override protected String screenTitle() { return "Room lobby"; }

	@Override protected View buildContent() {
		LinearLayout root = Ui.row(this);
		LinearLayout left = Ui.panel(this);
		LinearLayout head = Ui.row(this);
		idText = Ui.label(this, "ROOM ------", 20, Ui.TEXT, true);
		head.addView(idText, Ui.lp(this, 0, -2, 1f, 0, 0, 0, 0));
		head.addView(Ui.button(this, "COPY ID", Ui.SECONDARY, new View.OnClickListener() { @Override public void onClick(View v) { copyId(); } }));
		left.addView(head);
		info = Ui.label(this, "", 12, Ui.MUTED, false);
		info.setPadding(0, dp(4), 0, dp(8));
		left.addView(info);
		teams = Ui.col(this);
		left.addView(Ui.scroll(this, teams), new LinearLayout.LayoutParams(-1, 0, 1f));
		root.addView(left, Ui.lp(this, 0, -1, 1.6f, 0, 0, 8, 0));

		LinearLayout right = Ui.panel(this);
		readyBtn = Ui.button(this, "READY", Ui.PRIMARY, new View.OnClickListener() { @Override public void onClick(View v) { toggleReady(); } });
		right.addView(readyBtn, Ui.lp(this, -1, -2, 0, 0, 0, 0, 8));
		startBtn = Ui.button(this, "START MATCH", Ui.PRIMARY, new View.OnClickListener() { @Override public void onClick(View v) { ServerNet.startRoomMatch(); } });
		right.addView(startBtn, Ui.lp(this, -1, -2, 0, 0, 0, 0, 8));
		right.addView(Ui.button(this, "INVITE FRIENDS", Ui.SECONDARY, new View.OnClickListener() { @Override public void onClick(View v) { inviteDialog(); } }), Ui.lp(this, -1, -2, 0, 0, 0, 0, 8));
		hostBar = Ui.col(this);
		hostBar.addView(Ui.button(this, "ROOM SETTINGS", Ui.SECONDARY, new View.OnClickListener() { @Override public void onClick(View v) { settingsDialog(); } }), Ui.lp(this, -1, -2, 0, 0, 0, 0, 8));
		right.addView(hostBar);
		right.addView(Ui.button(this, "LEAVE ROOM", Ui.DANGER, new View.OnClickListener() { @Override public void onClick(View v) { leave(); } }));
		root.addView(right, Ui.lp(this, 0, -1, 1f, 8, 0, 0, 0));
		return root;
	}

	private ServerNet.RoomMember me(ServerNet.RoomInfo r) {
		for (int i = 0; i < r.members.size(); i++) if (r.members.get(i).isMe) return r.members.get(i);
		return null;
	}

	@Override protected void onState() {
		if (teams == null) return;
		final ServerNet.RoomInfo r = ServerNet.room;
		if (r == null) { finish(); return; }   // kicked / room closed
		idText.setText("ROOM  " + r.id + (r.hasPassword ? "  🔒" : ""));
		String mode = r.mode.equals("br") ? "Battle Royale" : r.mode.equals("cs") ? "Clash Squad" : "Custom";
		info.setText(mode + "  •  " + r.teamSize + " per team  •  " + MapCatalog.name(r.map) + "  •  " + (r.priv ? "Private" : "Public")
			+ "  •  " + r.members.size() + " / " + r.maxPlayers
			+ (r.mode.equals("br") ? "\nEmpty seats are filled with flagged bots when the host starts." : ""));
		ServerNet.RoomMember m = me(r);
		boolean host = ServerNet.amHost();
		readyBtn.setVisibility(host ? View.GONE : View.VISIBLE);
		startBtn.setVisibility(host ? View.VISIBLE : View.GONE);
		hostBar.setVisibility(host ? View.VISIBLE : View.GONE);
		if (m != null) readyBtn.setText(m.ready ? "UNREADY" : "READY");
		boolean allReady = true;
		for (int i = 0; i < r.members.size(); i++) if (!r.members.get(i).host && !r.members.get(i).ready) allReady = false;
		Ui.setEnabled(startBtn, allReady);

		// rebuild member list only when something changed
		StringBuilder sig = new StringBuilder();
		for (int i = 0; i < r.members.size(); i++) { ServerNet.RoomMember x = r.members.get(i); sig.append(x.uid).append(x.team).append(x.ready).append(x.host).append(x.name).append(';'); }
		sig.append(r.teamSize).append(r.mode).append(host);
		if (sig.toString().equals(lastSig)) return;
		lastSig = sig.toString();
		teams.removeAllViews();
		int teamCount = 0;
		for (int i = 0; i < r.members.size(); i++) teamCount = Math.max(teamCount, r.members.get(i).team + 1);
		if (r.mode.equals("cs") || r.mode.equals("custom")) teamCount = 2;
		for (int t = 0; t < teamCount; t++) {
			final int team = t;
			LinearLayout head = Ui.row(this);
			String tn = (r.mode.equals("cs") || r.mode.equals("custom")) ? (t == 0 ? "TEAM A" : "TEAM B") : "TEAM " + (t + 1);
			head.addView(Ui.label(this, tn, 12, t % 2 == 0 ? Ui.BLUE : Ui.ORANGE, true), Ui.lp(this, 0, -2, 1f, 0, 0, 0, 0));
			if (m != null && m.team != team) head.addView(Ui.button(this, "JOIN TEAM", Ui.SECONDARY, new View.OnClickListener() { @Override public void onClick(View v) { ServerNet.setTeam(team); } }));
			teams.addView(head, Ui.lp(this, -1, -2, 0, 0, 6, 0, 4));
			boolean any = false;
			for (int i = 0; i < r.members.size(); i++) {
				final ServerNet.RoomMember x = r.members.get(i);
				if (x.team != team) continue;
				any = true;
				LinearLayout row = Ui.row(this);
				row.setBackground(Ui.rect(this, 0x80101C30, x.isMe ? Ui.GOLD : Ui.STROKE, 10));
				row.setPadding(dp(12), dp(7), dp(12), dp(7));
				row.addView(Ui.label(this, (x.host ? "★ " : "") + x.name + (x.isMe ? "  (you)" : ""), 13, Ui.TEXT, true), Ui.lp(this, 0, -2, 1f, 0, 0, 0, 0));
				row.addView(Ui.label(this, x.host ? "HOST" : (x.ready ? "READY" : "NOT READY"), 11, x.host ? Ui.GOLD : (x.ready ? Ui.GREEN : Ui.MUTED), true));
				if (host && !x.isMe) row.addView(Ui.button(this, "KICK", Ui.DANGER, new View.OnClickListener() { @Override public void onClick(View v) { ServerNet.kick(x.uid); } }), Ui.lp(this, -2, -2, 0, 10, 0, 0, 0));
				teams.addView(row, Ui.lp(this, -1, -2, 0, 0, 0, 0, 4));
			}
			if (!any) teams.addView(Ui.label(this, "empty", 12, Ui.MUTED, false));
		}
	}

	private void toggleReady() {
		ServerNet.RoomInfo r = ServerNet.room;
		if (r == null) return;
		ServerNet.RoomMember m = me(r);
		ServerNet.setReady(m == null || !m.ready);
	}

	private void copyId() {
		ServerNet.RoomInfo r = ServerNet.room;
		if (r == null) return;
		ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		if (cm != null) { cm.setPrimaryClip(ClipData.newPlainText("Room ID", r.id)); toast("Room ID copied"); }
	}

	private void leave() { ServerNet.leaveRoom(); finish(); }

	@Override protected void onBackClicked() {
		PremiumDialog.show(this, "LEAVE ROOM?", "", PremiumDialog.text(this, "You will leave this room.", 13, 0xFFEAF1F7, false),
			"LEAVE", new Runnable() { @Override public void run() { leave(); } }, "STAY", null, GameDialog.Tone.DANGER);
	}

	private void inviteDialog() {
		ServerNet.refreshFriends();
		LinearLayout col = Ui.col(this);
		boolean any = false;
		for (int i = 0; i < ServerNet.friends.size(); i++) {
			final ServerNet.Friend f = ServerNet.friends.get(i);
			LinearLayout row = Ui.row(this);
			row.addView(Ui.label(this, f.name + (f.online ? "" : "  (offline)"), 13, f.online ? Ui.TEXT : Ui.MUTED, true), Ui.lp(this, 0, -2, 1f, 0, 0, 0, 0));
			if (f.online && !f.inMatch) row.addView(Ui.button(this, "INVITE", Ui.PRIMARY, new View.OnClickListener() {
				@Override public void onClick(View v) { ServerNet.invite(f.uid); toast("Invited " + f.name); }
			}));
			col.addView(row, Ui.lp(this, -1, -2, 0, 0, 0, 0, 6));
			any = true;
		}
		if (!any) col.addView(Ui.label(this, "No friends yet. Add some from the Friends screen.", 13, Ui.MUTED, false));
		PremiumDialog.show(this, "INVITE FRIENDS", "ROOM " + (ServerNet.room == null ? "" : ServerNet.room.id), Ui.scroll(this, col), "DONE", null, null, null);
	}

	private void settingsDialog() {
		final ServerNet.RoomInfo r = ServerNet.room;
		if (r == null) return;
		final String[] modeBox = { r.mode };
		final int[] sizeBox = { r.teamSize };
		final boolean[] privBox = { r.priv };
		final EditText pw = Ui.field(this, "New password (blank = none)", true);
		LinearLayout col = Ui.col(this);
		Ui.section(this, col, "Mode");
		LinearLayout modes = Ui.row(this);
		final String[] ids = { "br", "cs", "custom" }, names = { "BR", "CLASH", "CUSTOM" };
		final java.util.ArrayList<TextView> mc = new java.util.ArrayList<TextView>();
		for (int i = 0; i < ids.length; i++) {
			final String id = ids[i];
			final TextView c = Ui.chip(this, names[i], id.equals(modeBox[0]), null);
			c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { modeBox[0] = id; for (int k = 0; k < mc.size(); k++) Ui.setChip(mc.get(k), mc.get(k) == c); } });
			mc.add(c);
			modes.addView(c, Ui.lp(this, -2, -2, 0, 0, 0, 6, 0));
		}
		col.addView(modes);
		Ui.section(this, col, "Team size");
		LinearLayout sizes = Ui.row(this);
		final java.util.ArrayList<TextView> sc = new java.util.ArrayList<TextView>();
		for (int n = 1; n <= 4; n++) {
			final int ts = n;
			final TextView c = Ui.chip(this, String.valueOf(n), n == sizeBox[0], null);
			c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { sizeBox[0] = ts; for (int k = 0; k < sc.size(); k++) Ui.setChip(sc.get(k), sc.get(k) == c); } });
			sc.add(c);
			sizes.addView(c, Ui.lp(this, -2, -2, 0, 0, 0, 6, 0));
		}
		col.addView(sizes);
		final TextView vis = Ui.chip(this, privBox[0] ? "PRIVATE" : "PUBLIC", privBox[0], null);
		vis.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { privBox[0] = !privBox[0]; vis.setText(privBox[0] ? "PRIVATE" : "PUBLIC"); Ui.setChip(vis, privBox[0]); } });
		Ui.section(this, col, "Visibility");
		col.addView(vis);
		col.addView(pw, Ui.lp(this, -1, -2, 0, 0, 10, 0, 0));
		PremiumDialog.show(this, "ROOM SETTINGS", "ROOM " + r.id, col, "APPLY", new Runnable() {
			@Override public void run() {
				String p = pw.getText().toString();
				ServerNet.configRoom(modeBox[0], sizeBox[0], r.map, privBox[0], p.length() == 0 ? null : p);
			}
		}, "CANCEL", null);
	}
}
