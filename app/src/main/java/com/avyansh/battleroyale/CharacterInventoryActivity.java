package com.avyansh.battleroyale;

import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;

/** Screen 7: Character inventory: profile (name, UID) and career statistics recorded from server-controlled match results. */
public class CharacterInventoryActivity extends InventoryActivity {
	public static final String STATS_PREFS = "career_stats";

	/** Called by the results screen with the server's result for this player. */
	public static void record(android.content.Context c, ServerNet.MatchEnd e, ServerNet.Result mine) {
		if (e == null || mine == null) return;
		SharedPreferences sp = c.getSharedPreferences(STATS_PREFS, android.content.Context.MODE_PRIVATE);
		sp.edit().putInt("matches", sp.getInt("matches", 0) + 1)
			.putInt("wins", sp.getInt("wins", 0) + (e.iWon ? 1 : 0))
			.putInt("kills", sp.getInt("kills", 0) + mine.kills)
			.putInt("damage", sp.getInt("damage", 0) + mine.damage)
			.putInt("best", sp.getInt("best", 0) == 0 ? Math.max(1, mine.placement) : Math.min(sp.getInt("best", 99), Math.max(1, mine.placement)))
			.apply();
	}

	@Override protected String screenTitle() { return "Character"; }

	@Override protected View buildInventory() {
		LinearLayout root = Ui.row(this);
		LinearLayout left = Ui.panel(this);
		Ui.section(this, left, "Profile");
		left.addView(Ui.label(this, Net.name, 20, Ui.TEXT, true));
		left.addView(Ui.label(this, "UID  " + ServerNet.uid(), 11, Ui.MUTED, false));
		left.addView(Ui.button(this, "CHANGE NAME", Ui.SECONDARY, new View.OnClickListener() { @Override public void onClick(View v) { rename(); } }), Ui.lp(this, -1, -2, 0, 0, 12, 0, 0));
		root.addView(left, Ui.lp(this, 0, -1, 1f, 0, 0, 8, 0));

		LinearLayout right = Ui.panel(this);
		Ui.section(this, right, "Career (online matches)");
		SharedPreferences sp = getSharedPreferences(STATS_PREFS, MODE_PRIVATE);
		int matches = sp.getInt("matches", 0), wins = sp.getInt("wins", 0), kills = sp.getInt("kills", 0);
		right.addView(statRow("Matches played", String.valueOf(matches)));
		right.addView(statRow("Wins", String.valueOf(wins)));
		right.addView(statRow("Win rate", matches == 0 ? "-" : (wins * 100 / matches) + "%"));
		right.addView(statRow("Total kills", String.valueOf(kills)));
		right.addView(statRow("K / match", matches == 0 ? "-" : String.format(java.util.Locale.US, "%.2f", kills / (float) matches)));
		right.addView(statRow("Total damage", String.valueOf(sp.getInt("damage", 0))));
		right.addView(statRow("Best placement", sp.getInt("best", 0) == 0 ? "-" : "#" + sp.getInt("best", 0)));
		root.addView(right, Ui.lp(this, 0, -1, 1.6f, 8, 0, 0, 0));
		return root;
	}

	private LinearLayout statRow(String k, String v) {
		LinearLayout row = card();
		LinearLayout r = Ui.row(this);
		r.addView(Ui.label(this, k, 13, Ui.MUTED, false), Ui.lp(this, 0, -2, 1f, 0, 0, 0, 0));
		r.addView(Ui.label(this, v, 15, Ui.GOLD, true));
		row.addView(r);
		LinearLayout.LayoutParams lp = Ui.lp(this, -1, -2, 0, 0, 0, 0, 6);
		row.setLayoutParams(lp);
		return row;
	}

	private void rename() {
		final android.widget.EditText e = Ui.field(this, "Name (3-14 characters)", false);
		e.setText(Net.name);
		PremiumDialog.show(this, "CHANGE NAME", "", e, "SAVE", new Runnable() {
			@Override public void run() {
				String n = e.getText().toString().trim();
				if (n.length() < 3) { toast("Name must contain at least 3 characters"); return; }
				Net.setName(CharacterInventoryActivity.this, n);
				ServerNet.setName(n);
				toast("Name saved - applies on next connection");
			}
		}, "CANCEL", null);
	}
}
