package com.avyansh.battleroyale;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Public matchmaking queue (Battle Royale / Clash Squad). Real players are queued on the server; bots only fill empty seats and are always flagged. */
public abstract class MatchmakingActivity extends ScreenActivity {
	protected abstract String mode();            // "br" or "cs"
	protected abstract int[] teamSizes();        // selectable team sizes
	protected abstract String blurb();

	private int teamSize;
	private TextView status, detail, action, hint;
	private final java.util.ArrayList<TextView> chips = new java.util.ArrayList<TextView>();
	private long queueStart;

	@Override protected View buildContent() {
		teamSize = teamSizes()[teamSizes().length - 1];
		LinearLayout root = Ui.row(this);
		LinearLayout left = Ui.panel(this);
		left.addView(Ui.label(this, mode().equals("br") ? "BATTLE ROYALE" : "CLASH SQUAD", 22, Ui.TEXT, true));
		TextView b = Ui.label(this, blurb(), 12, Ui.MUTED, false);
		b.setPadding(0, dp(4), 0, dp(10));
		left.addView(b);
		Ui.section(this, left, "Team size");
		LinearLayout row = Ui.row(this);
		for (final int ts : teamSizes()) {
			final TextView c = Ui.chip(this, label(ts), ts == teamSize, null);
			c.setOnClickListener(new View.OnClickListener() {
				@Override public void onClick(View v) {
					if (ServerNet.queued) { toast("Cancel the search first"); return; }
					teamSize = ts;
					for (int i = 0; i < chips.size(); i++) Ui.setChip(chips.get(i), chips.get(i) == c);
				}
			});
			chips.add(c);
			row.addView(c, Ui.lp(this, -2, -2, 0, 0, 0, 8, 0));
		}
		left.addView(row);
		hint = Ui.label(this, "", 11, Ui.MUTED, false);
		hint.setPadding(0, dp(12), 0, 0);
		left.addView(hint);
		root.addView(left, Ui.lp(this, 0, -1, 1f, 0, 0, 8, 0));

		LinearLayout right = Ui.panel(this);
		right.setGravity(android.view.Gravity.CENTER);
		status = Ui.label(this, "Ready", 22, Ui.GOLD, true);
		status.setGravity(android.view.Gravity.CENTER);
		detail = Ui.label(this, "", 13, Ui.TEXT, false);
		detail.setGravity(android.view.Gravity.CENTER);
		detail.setPadding(0, dp(8), 0, dp(16));
		action = Ui.button(this, "FIND MATCH", Ui.PRIMARY, new View.OnClickListener() {
			@Override public void onClick(View v) {
				if (ServerNet.queued) { ServerNet.cancelQueue(); return; }
				if (!ServerNet.isOnline()) { toast("Not connected to the game server"); return; }
				ServerRoomGuard.leaveRoomIfAny();
				queueStart = System.currentTimeMillis();
				ServerNet.joinQueue(mode(), teamSize);
			}
		});
		right.addView(status);
		right.addView(detail);
		right.addView(action, Ui.lp(this, 220, -2, 0, 0, 0, 0, 0));
		root.addView(right, Ui.lp(this, 0, -1, 1f, 8, 0, 0, 0));
		return root;
	}

	private String label(int ts) {
		if (mode().equals("br")) return ts == 1 ? "SOLO" : ts == 2 ? "DUO" : "SQUAD";
		return ts + " v " + ts;
	}

	@Override protected void onState() {
		if (status == null) return;
		if (!ServerNet.isOnline()) {
			status.setText("No connection");
			detail.setText("Server: " + ServerNet.serverAddress() + "\nSet the address in Settings > Server.");
			Ui.setEnabled(action, false);
			action.setText("FIND MATCH");
			return;
		}
		Ui.setEnabled(action, true);
		if (ServerNet.queued && ServerNet.queueMode.equals(mode())) {
			long s = (System.currentTimeMillis() - queueStart) / 1000L;
			status.setText("Searching… " + s + "s");
			StringBuilder d = new StringBuilder();
			d.append(ServerNet.queueWaiting).append(" real player(s) waiting");
			if (ServerNet.queueNeeded > 0) d.append("\n").append(ServerNet.queueNeeded).append(" more needed");
			if (ServerNet.queueEta > 0) d.append("\nMatch starts in ~").append(ServerNet.queueEta).append("s");
			d.append("\nEmpty seats are filled with clearly marked bots.");
			detail.setText(d.toString());
			action.setText("CANCEL");
		} else {
			status.setText("Ready");
			detail.setText("Ping " + ServerNet.rtt() + " ms");
			action.setText("FIND MATCH");
		}
		if (hint != null) hint.setText(mode().equals("br") ? "Up to 50 players. Real players first, bots fill the rest." : "Two teams fight to the last squad.");
	}

	@Override protected void onPause() {
		super.onPause();
	}

	@Override protected void onBackClicked() {
		if (ServerNet.queued) ServerNet.cancelQueue();
		finish();
	}
}
