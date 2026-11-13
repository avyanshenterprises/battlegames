package com.avyansh.battleroyale;

import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Base for the two inventory screens. Shared helpers for the character and weapon screens. */
public abstract class InventoryActivity extends ScreenActivity {
	protected SharedPreferences prefs;

	@Override protected View buildContent() {
		prefs = getSharedPreferences("avyansh_game_settings", MODE_PRIVATE);
		return buildInventory();
	}

	protected abstract View buildInventory();

	protected LinearLayout card() {
		LinearLayout l = Ui.col(this);
		l.setBackground(Ui.rect(this, 0x80101C30, Ui.STROKE, 10));
		l.setPadding(dp(12), dp(8), dp(12), dp(8));
		return l;
	}

	protected TextView stat(String s) { return Ui.label(this, s, 11, Ui.MUTED, false); }
}
