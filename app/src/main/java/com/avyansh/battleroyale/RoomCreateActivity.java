package com.avyansh.battleroyale;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Screen 4: Room creation (separate from matchmaking). Modes, team sizes, public/private, password, map. */
public class RoomCreateActivity extends ScreenActivity {
	private String mode = "cs";
	private int teamSize = 2;
	private boolean priv;
	private String map = MapCatalog.ids()[0];
	private EditText password, joinId, joinPw;
	private TextView summary, customVal;
	private final java.util.ArrayList<TextView> modeChips = new java.util.ArrayList<TextView>();
	private final java.util.ArrayList<TextView> sizeChips = new java.util.ArrayList<TextView>();
	private LinearLayout customRow;
	private boolean creating;

	@Override protected String screenTitle() { return "Create room"; }

	@Override protected View buildContent() {
		LinearLayout form = Ui.col(this);
		LinearLayout p = Ui.panel(this);

		Ui.section(this, p, "Game mode");
		LinearLayout modes = Ui.row(this);
		addMode(modes, "BATTLE ROYALE", "br");
		addMode(modes, "CLASH SQUAD", "cs");
		addMode(modes, "CUSTOM", "custom");
		p.addView(modes);

		Ui.section(this, p, "Team size");
		LinearLayout sizes = Ui.row(this);
		int[] presets = { 1, 2, 4 };
		for (final int ts : presets) {
			final TextView c = Ui.chip(this, ts + " v " + ts, ts == teamSize, null);
			c.setTag(Integer.valueOf(ts));
			c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setTeamSize(ts); } });
			sizeChips.add(c);
			sizes.addView(c, Ui.lp(this, -2, -2, 0, 0, 0, 8, 0));
		}
		customRow = Ui.row(this);
		customRow.addView(Ui.button(this, "−", Ui.SECONDARY, new View.OnClickListener() { @Override public void onClick(View v) { setTeamSize(Math.max(1, teamSize - 1)); } }));
		customVal = Ui.label(this, "", 14, Ui.GOLD, true);
		customVal.setGravity(android.view.Gravity.CENTER);
		customRow.addView(customVal, Ui.lp(this, 90, -2, 0, 6, 0, 6, 0));
		customRow.addView(Ui.button(this, "+", Ui.SECONDARY, new View.OnClickListener() { @Override public void onClick(View v) { setTeamSize(Math.min(maxTeam(), teamSize + 1)); } }));
		sizes.addView(customRow, Ui.lp(this, -2, -2, 0, 12, 0, 0, 0));
		p.addView(sizes);

		Ui.section(this, p, "Visibility");
		LinearLayout vis = Ui.row(this);
		final TextView pub = Ui.chip(this, "PUBLIC ROOM", true, null);
		final TextView prv = Ui.chip(this, "PRIVATE ROOM", false, null);
		pub.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { priv = false; Ui.setChip(pub, true); Ui.setChip(prv, false); refresh(); } });
		prv.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { priv = true; Ui.setChip(pub, false); Ui.setChip(prv, true); refresh(); } });
		vis.addView(pub, Ui.lp(this, -2, -2, 0, 0, 0, 8, 0));
		vis.addView(prv);
		p.addView(vis);
		password = Ui.field(this, "Room password (optional)", true);
		p.addView(password, Ui.lp(this, -1, -2, 0, 0, 8, 0, 0));

		Ui.section(this, p, "Map");
		LinearLayout maps = Ui.row(this);
		final java.util.ArrayList<TextView> mapChips = new java.util.ArrayList<TextView>();
		final String[] ids = MapCatalog.ids();
		for (int i = 0; i < ids.length; i++) {
			final String id = ids[i];
			final TextView c = Ui.chip(this, MapCatalog.name(id), id.equals(map), null);
			c.setOnClickListener(new View.OnClickListener() {
				@Override public void onClick(View v) { map = id; for (int k = 0; k < mapChips.size(); k++) Ui.setChip(mapChips.get(k), mapChips.get(k) == c); refresh(); }
			});
			mapChips.add(c);
			maps.addView(c, Ui.lp(this, -2, -2, 0, 0, 0, 8, 0));
		}
		p.addView(maps);

		summary = Ui.label(this, "", 12, Ui.MUTED, false);
		summary.setPadding(0, dp(12), 0, dp(8));
		p.addView(summary);
		p.addView(Ui.button(this, "CREATE ROOM", Ui.PRIMARY, new View.OnClickListener() { @Override public void onClick(View v) { create(); } }), Ui.lp(this, 240, -2, 0, 0, 0, 0, 0));

		LinearLayout j = Ui.panel(this);
		Ui.section(this, j, "Join by room ID");
		joinId = Ui.field(this, "ROOM ID", false);
		joinId.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.AllCaps(), new android.text.InputFilter.LengthFilter(6) });
		joinPw = Ui.field(this, "Password (if any)", true);
		j.addView(joinId);
		j.addView(joinPw, Ui.lp(this, -1, -2, 0, 0, 8, 0, 0));
		j.addView(Ui.button(this, "JOIN ROOM", Ui.SECONDARY, new View.OnClickListener() { @Override public void onClick(View v) { join(); } }), Ui.lp(this, -1, -2, 0, 0, 10, 0, 0));
		j.addView(Ui.button(this, "BROWSE PUBLIC ROOMS", Ui.SECONDARY, new View.OnClickListener() {
			@Override public void onClick(View v) { startActivity(new android.content.Intent(RoomCreateActivity.this, RoomBrowserActivity.class)); }
		}), Ui.lp(this, -1, -2, 0, 0, 8, 0, 0));

		LinearLayout row = Ui.row(this);
		row.addView(Ui.scroll(this, p), Ui.lp(this, 0, -1, 1.5f, 0, 0, 8, 0));
		row.addView(j, Ui.lp(this, 0, -1, 1f, 8, 0, 0, 0));
		form.addView(row, new LinearLayout.LayoutParams(-1, -1));
		refresh();
		return form;
	}

	private void addMode(LinearLayout row, String text, final String m) {
		final TextView c = Ui.chip(this, text, m.equals(mode), null);
		c.setTag(m);
		c.setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View v) {
				mode = m;
				if (teamSize > maxTeam()) teamSize = maxTeam();
				refresh();
			}
		});
		modeChips.add(c);
		row.addView(c, Ui.lp(this, -2, -2, 0, 0, 0, 8, 0));
	}

	private int maxTeam() { return mode.equals("custom") ? 8 : 4; }

	private void setTeamSize(int ts) { teamSize = ts; refresh(); }

	private void refresh() {
		for (int i = 0; i < modeChips.size(); i++) Ui.setChip(modeChips.get(i), modeChips.get(i).getTag().equals(mode));
		for (int i = 0; i < sizeChips.size(); i++) Ui.setChip(sizeChips.get(i), ((Integer) sizeChips.get(i).getTag()).intValue() == teamSize);
		customRow.setVisibility(mode.equals("custom") ? View.VISIBLE : View.GONE);
		customVal.setText(teamSize + " v " + teamSize);
		int max = maxPlayers();
		String s = (mode.equals("br") ? "Battle Royale" : mode.equals("cs") ? "Clash Squad" : "Custom") + "  •  "
			+ (mode.equals("br") ? (teamSize == 1 ? "Solo" : teamSize == 2 ? "Duo" : "Squad of " + teamSize) : teamSize + " v " + teamSize)
			+ "  •  up to " + max + " players  •  " + (priv ? "private" : "public") + "  •  " + MapCatalog.name(map);
		summary.setText(s);
	}

	private int maxPlayers() { return mode.equals("br") ? 50 : teamSize * 2; }

	private void create() {
		if (!ServerNet.isOnline()) { toast("Not connected to the game server"); return; }
		if (creating) return;
		creating = true;
		ServerNet.createRoom(mode, teamSize, maxPlayers(), priv, password.getText().toString(), map);
	}

	private void join() {
		if (!ServerNet.isOnline()) { toast("Not connected to the game server"); return; }
		String id = joinId.getText().toString().trim().toUpperCase(java.util.Locale.US);
		if (id.length() != 6) { toast("Room IDs have 6 characters"); return; }
		creating = true;
		ServerNet.joinRoom(id, joinPw.getText().toString());
	}

	@Override protected void onState() {
		if (creating && ServerNet.room != null) {
			creating = false;
			startActivity(new android.content.Intent(this, RoomLobbyActivity.class));
		} else if (creating && ServerNet.lastError.length() > 0 && !ServerNet.isOnline()) creating = false;
	}

	@Override public void onToast(String msg) { creating = false; super.onToast(msg); }
}
