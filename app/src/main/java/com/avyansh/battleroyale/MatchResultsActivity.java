package com.avyansh.battleroyale;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Screen 12: Match results. Everything shown here was computed by the server (placement, kills, damage, survival time). */
public class MatchResultsActivity extends ScreenActivity {
	private static final String KEY_RECORDED = "recorded";
	private boolean recorded;

	@Override protected String screenTitle() { return "Match results"; }

	@Override protected void onCreate(Bundle b) {
		recorded = b != null && b.getBoolean(KEY_RECORDED, false);
		super.onCreate(b);
	}

	@Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putBoolean(KEY_RECORDED, recorded); }

	@Override protected View buildContent() {
		final ServerNet.MatchEnd e = ServerNet.lastResult;
		LinearLayout root = Ui.row(this);
		if (e == null) {
			LinearLayout p = Ui.panel(this);
			p.addView(Ui.label(this, "No result yet.", 16, Ui.TEXT, true));
			p.addView(Ui.label(this, "The server sends the result when the match ends.", 12, Ui.MUTED, false));
			root.addView(p, new LinearLayout.LayoutParams(-1, -1));
			return root;
		}
		ServerNet.Result mine = null;
		int real = 0, bots = 0, teams = 0;
		java.util.HashSet<Integer> seen = new java.util.HashSet<Integer>();
		for (int i = 0; i < e.results.size(); i++) {
			ServerNet.Result r = e.results.get(i);
			if (r.id.equals(ServerNet.myId())) mine = r;
			if (r.isBot) bots++; else real++;
			if (seen.add(Integer.valueOf(r.team))) teams++;
		}
		if (!recorded && mine != null) { CharacterInventoryActivity.record(this, e, mine); recorded = true; }

		LinearLayout left = Ui.panel(this);
		TextView verdict = Ui.label(this, e.iWon ? "VICTORY" : "ELIMINATED", 30, e.iWon ? Ui.GOLD : Ui.RED, true);
		verdict.setShadowLayer(8f, 0f, 2f, 0xFF000000);
		left.addView(verdict);
		left.addView(Ui.label(this, "Placement  #" + Math.max(1, e.myPlacement) + " of " + teams + (e.mode.equals("br") ? " teams" : ""), 14, Ui.TEXT, true));
		left.addView(Ui.label(this, real + " real player(s)  •  " + bots + " bot(s)", 12, Ui.MUTED, false));
		if (mine != null) {
			left.addView(stat("Kills", String.valueOf(mine.kills)));
			left.addView(stat("Damage dealt", String.valueOf(mine.damage)));
			left.addView(stat("Survived", fmt(mine.surviveSec)));
		}
		left.addView(stat("Match length", fmt(e.durationSec)));
		left.addView(Ui.button(this, "BACK TO LOBBY", Ui.PRIMARY, new View.OnClickListener() { @Override public void onClick(View v) { toLobby(); } }), Ui.lp(this, -1, -2, 0, 0, 14, 0, 0));
		root.addView(Ui.scroll(this, left), Ui.lp(this, 0, -1, 1f, 0, 0, 8, 0));

		LinearLayout right = Ui.panel(this);
		Ui.section(this, right, "Standings");
		LinearLayout list = Ui.col(this);
		java.util.ArrayList<ServerNet.Result> sorted = new java.util.ArrayList<ServerNet.Result>(e.results);
		java.util.Collections.sort(sorted, new java.util.Comparator<ServerNet.Result>() {
			@Override public int compare(ServerNet.Result a, ServerNet.Result b) {
				if (a.placement != b.placement) return a.placement - b.placement;
				return b.kills - a.kills;
			}
		});
		for (int i = 0; i < sorted.size(); i++) {
			ServerNet.Result r = sorted.get(i);
			boolean me = r.id.equals(ServerNet.myId());
			LinearLayout row = Ui.row(this);
			row.setBackground(Ui.rect(this, me ? 0x40FFCD4B : 0x80101C30, me ? Ui.GOLD : Ui.STROKE, 8));
			row.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
			row.addView(Ui.label(this, "#" + r.placement, 13, r.placement == 1 ? Ui.GOLD : Ui.TEXT, true), Ui.lp(this, 36, -2, 0, 0, 0, 6, 0));
			row.addView(Ui.label(this, r.name + (r.isBot ? "  [BOT]" : "") + (me ? "  (you)" : ""), 13, r.isBot ? Ui.MUTED : Ui.TEXT, true), Ui.lp(this, 0, -2, 1f, 0, 0, 0, 0));
			row.addView(Ui.label(this, r.kills + " K   " + r.damage + " dmg   " + fmt(r.surviveSec), 11, Ui.MUTED, false));
			list.addView(row, Ui.lp(this, -1, -2, 0, 0, 0, 0, 4));
		}
		right.addView(Ui.scroll(this, list), new LinearLayout.LayoutParams(-1, 0, 1f));
		root.addView(right, Ui.lp(this, 0, -1, 1.4f, 8, 0, 0, 0));
		return root;
	}

	private LinearLayout stat(String k, String v) {
		LinearLayout r = Ui.row(this);
		r.setPadding(0, Ui.dp(this, 6), 0, 0);
		r.addView(Ui.label(this, k, 13, Ui.MUTED, false), Ui.lp(this, 0, -2, 1f, 0, 0, 0, 0));
		r.addView(Ui.label(this, v, 15, Ui.GOLD, true));
		return r;
	}

	private static String fmt(int s) { return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60); }

	private void toLobby() {
		ServerNet.endMatchLocal();
		ServerNet.lastResult = null;
		Intent i = new Intent(this, LobbyActivity.class);
		i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		startActivity(i);
		finish();
	}

	@Override protected void onBackClicked() { toLobby(); }
}
