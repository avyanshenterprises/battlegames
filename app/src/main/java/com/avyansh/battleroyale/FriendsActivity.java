package com.avyansh.battleroyale;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Screen 6: Friends. Add by UID, accept/decline requests, see who is online / in a room, invite, join their room. */
public class FriendsActivity extends ScreenActivity {
	private LinearLayout list;
	private EditText addField;
	private String lastSig = "";

	@Override protected String screenTitle() { return "Friends"; }

	@Override protected View buildContent() {
		LinearLayout root = Ui.row(this);
		LinearLayout add = Ui.panel(this);
		Ui.section(this, add, "Your UID");
		TextView uid = Ui.label(this, ServerNet.uid(), 16, Ui.TEXT, true);
		uid.setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View v) {
				android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
				if (cm != null) { cm.setPrimaryClip(android.content.ClipData.newPlainText("UID", ServerNet.uid())); toast("UID copied"); }
			}
		});
		add.addView(uid);
		add.addView(Ui.label(this, "Tap to copy and share it with friends.", 11, Ui.MUTED, false));
		Ui.section(this, add, "Add friend");
		addField = Ui.field(this, "Username or Player UID", false);
		add.addView(addField);
		add.addView(Ui.button(this, "SEARCH PLAYERS", Ui.SECONDARY, new View.OnClickListener() {
			@Override public void onClick(View v) {
				String q = addField.getText().toString().trim();
				if (q.length() < 2) { toast("Enter at least 2 characters"); return; }
				ServerNet.searchFriends(q);
			}
		}), Ui.lp(this, -1, -2, 0, 0, 8, 0, 0));
		add.addView(Ui.button(this, "SEND REQUEST BY UID", Ui.PRIMARY, new View.OnClickListener() {
			@Override public void onClick(View v) {
				String id = addField.getText().toString().trim();
				if (id.length() < 3) { toast("Enter a valid UID"); return; }
				ServerNet.addFriend(id);
				addField.setText("");
			}
		}), Ui.lp(this, -1, -2, 0, 0, 10, 0, 0));
		add.addView(Ui.button(this, "REFRESH", Ui.SECONDARY, new View.OnClickListener() { @Override public void onClick(View v) { ServerNet.refreshFriends(); } }), Ui.lp(this, -1, -2, 0, 0, 8, 0, 0));
		root.addView(add, Ui.lp(this, 0, -1, 1f, 0, 0, 8, 0));

		LinearLayout right = Ui.panel(this);
		list = Ui.col(this);
		right.addView(Ui.scroll(this, list), new LinearLayout.LayoutParams(-1, 0, 1f));
		root.addView(right, Ui.lp(this, 0, -1, 1.6f, 8, 0, 0, 0));
		return root;
	}

	@Override protected void onResume() { super.onResume(); ServerNet.refreshFriends(); }

	@Override protected void onState() {
		if (list == null) return;
		StringBuilder sig = new StringBuilder();
		for (int i = 0; i < ServerNet.friends.size(); i++) { ServerNet.Friend f = ServerNet.friends.get(i); sig.append(f.uid).append(f.online).append(f.inMatch).append(f.room); }
		for (int i = 0; i < ServerNet.pending.size(); i++) sig.append('p').append(ServerNet.pending.get(i).uid);
		for (int i = 0; i < ServerNet.searchResults.size(); i++) sig.append('s').append(ServerNet.searchResults.get(i).uid);
		for (int i = 0; i < ServerNet.invites.size(); i++) sig.append('i').append(ServerNet.invites.get(i).room);
		sig.append(ServerNet.room == null ? "-" : ServerNet.room.id).append(ServerNet.isOnline());
		if (sig.toString().equals(lastSig)) return;
		lastSig = sig.toString();
		list.removeAllViews();

		if (!ServerNet.searchResults.isEmpty()) {
			Ui.section(this, list, "Player search results");
			for (int i = 0; i < ServerNet.searchResults.size(); i++) {
				final ServerNet.Friend f = ServerNet.searchResults.get(i);
				LinearLayout row = rowBox();
				LinearLayout names = Ui.col(this);
				names.addView(Ui.label(this, f.name, 13, Ui.TEXT, true));
				names.addView(Ui.label(this, f.uid, 10, Ui.MUTED, false));
				row.addView(names, Ui.lp(this, 0, -2, 1f, 0, 0, 0, 0));
				row.addView(Ui.button(this, "ADD", Ui.PRIMARY, new View.OnClickListener() {
					@Override public void onClick(View v) { ServerNet.addFriend(f.uid); }
				}), Ui.lp(this, -2, -2, 0, 8, 0, 0, 0));
				list.addView(row, Ui.lp(this, -1, -2, 0, 0, 0, 0, 6));
			}
		}

		if (!ServerNet.invites.isEmpty()) {
			Ui.section(this, list, "Room invitations");
			for (int i = 0; i < ServerNet.invites.size(); i++) {
				final ServerNet.Invite inv = ServerNet.invites.get(i);
				LinearLayout row = rowBox();
				row.addView(Ui.label(this, inv.name + " invited you to room " + inv.room, 13, Ui.TEXT, true), Ui.lp(this, 0, -2, 1f, 0, 0, 0, 0));
				row.addView(Ui.button(this, "JOIN", Ui.PRIMARY, new View.OnClickListener() {
					@Override public void onClick(View v) {
						ServerNet.removeInvite(inv);
						ServerNet.joinRoom(inv.room, "");
						startActivity(new android.content.Intent(FriendsActivity.this, RoomLobbyActivity.class));
					}
				}), Ui.lp(this, -2, -2, 0, 8, 0, 0, 0));
				row.addView(Ui.button(this, "IGNORE", Ui.SECONDARY, new View.OnClickListener() {
					@Override public void onClick(View v) { ServerNet.removeInvite(inv); lastSig = ""; onState(); }
				}), Ui.lp(this, -2, -2, 0, 8, 0, 0, 0));
				list.addView(row, Ui.lp(this, -1, -2, 0, 0, 0, 0, 6));
			}
		}
		if (!ServerNet.pending.isEmpty()) {
			Ui.section(this, list, "Friend requests");
			for (int i = 0; i < ServerNet.pending.size(); i++) {
				final ServerNet.Friend f = ServerNet.pending.get(i);
				LinearLayout row = rowBox();
				row.addView(Ui.label(this, f.name, 13, Ui.TEXT, true), Ui.lp(this, 0, -2, 1f, 0, 0, 0, 0));
				row.addView(Ui.button(this, "ACCEPT", Ui.PRIMARY, new View.OnClickListener() { @Override public void onClick(View v) { ServerNet.acceptFriend(f.uid); } }), Ui.lp(this, -2, -2, 0, 8, 0, 0, 0));
				row.addView(Ui.button(this, "DECLINE", Ui.SECONDARY, new View.OnClickListener() { @Override public void onClick(View v) { ServerNet.declineFriend(f.uid); } }), Ui.lp(this, -2, -2, 0, 8, 0, 0, 0));
				list.addView(row, Ui.lp(this, -1, -2, 0, 0, 0, 0, 6));
			}
		}
		Ui.section(this, list, "Friends (" + ServerNet.friends.size() + ")");
		if (ServerNet.friends.isEmpty()) list.addView(Ui.label(this, ServerNet.isOnline() ? "No friends yet." : "Not connected to the game server.", 13, Ui.MUTED, false));
		for (int i = 0; i < ServerNet.friends.size(); i++) {
			final ServerNet.Friend f = ServerNet.friends.get(i);
			LinearLayout row = rowBox();
			TextView dot = Ui.label(this, "● ", 14, f.inMatch ? Ui.ORANGE : (f.online ? Ui.GREEN : Ui.MUTED), true);
			row.addView(dot);
			LinearLayout names = Ui.col(this);
			names.addView(Ui.label(this, f.name, 13, Ui.TEXT, true));
			names.addView(Ui.label(this, f.inMatch ? "In match" : (f.room != null && f.room.length() > 0 ? "In room " + f.room : (f.online ? "Online" : "Offline")), 11, Ui.MUTED, false));
			row.addView(names, Ui.lp(this, 0, -2, 1f, 0, 0, 0, 0));
			if (f.online && !f.inMatch && ServerNet.room != null) row.addView(Ui.button(this, "INVITE", Ui.PRIMARY, new View.OnClickListener() {
				@Override public void onClick(View v) { ServerNet.invite(f.uid); toast("Invited " + f.name); }
			}), Ui.lp(this, -2, -2, 0, 8, 0, 0, 0));
			if (f.online && f.room != null && f.room.length() > 0 && ServerNet.room == null) row.addView(Ui.button(this, "JOIN", Ui.SECONDARY, new View.OnClickListener() {
				@Override public void onClick(View v) { ServerNet.joinRoom(f.room, ""); startActivity(new android.content.Intent(FriendsActivity.this, RoomLobbyActivity.class)); }
			}), Ui.lp(this, -2, -2, 0, 8, 0, 0, 0));
			row.addView(Ui.button(this, "REMOVE", Ui.SECONDARY, new View.OnClickListener() {
				@Override public void onClick(View v) { ServerNet.removeFriend(f.uid); }
			}), Ui.lp(this, -2, -2, 0, 8, 0, 0, 0));
			list.addView(row, Ui.lp(this, -1, -2, 0, 0, 0, 0, 6));
		}
	}

	private LinearLayout rowBox() {
		LinearLayout row = Ui.row(this);
		row.setBackground(Ui.rect(this, 0x80101C30, Ui.STROKE, 10));
		row.setPadding(dp(12), dp(8), dp(12), dp(8));
		return row;
	}
}
