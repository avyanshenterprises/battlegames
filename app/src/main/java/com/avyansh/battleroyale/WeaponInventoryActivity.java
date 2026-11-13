package com.avyansh.battleroyale;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Screen 8: Weapon inventory / armoury. Every weapon with its real stats, straight from the game's weapon table. */
public class WeaponInventoryActivity extends InventoryActivity {
	private int category = -1;
	private LinearLayout list;

	@Override protected String screenTitle() { return "Weapons"; }

	@Override protected View buildInventory() {
		LinearLayout root = Ui.col(this);
		LinearLayout tabs = Ui.row(this);
		final java.util.ArrayList<TextView> tabChips = new java.util.ArrayList<TextView>();
		for (int c = -1; c < Weapon.CAT_NAMES.length; c++) {
			final int cat = c;
			final TextView t = Ui.chip(this, c < 0 ? "ALL" : Weapon.CAT_NAMES[c], c == category, null);
			t.setOnClickListener(new View.OnClickListener() {
				@Override public void onClick(View v) { category = cat; for (int k = 0; k < tabChips.size(); k++) Ui.setChip(tabChips.get(k), tabChips.get(k) == t); fill(); }
			});
			tabChips.add(t);
			tabs.addView(t, Ui.lp(this, -2, -2, 0, 0, 0, 6, 0));
		}
		android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
		hs.setHorizontalScrollBarEnabled(false);
		hs.addView(tabs);
		root.addView(hs, Ui.lp(this, -1, -2, 0, 0, 0, 0, 8));
		LinearLayout p = Ui.panel(this);
		list = Ui.col(this);
		p.addView(Ui.scroll(this, list), new LinearLayout.LayoutParams(-1, 0, 1f));
		root.addView(p, new LinearLayout.LayoutParams(-1, 0, 1f));
		fill();
		return root;
	}

	private void fill() {
		list.removeAllViews();
		for (int id = 0; id < Weapon.COUNT; id++) {
			final Weapon.Def d = Weapon.DEFS[id];
			if (category >= 0 && d.cat != category) continue;
			LinearLayout c = card();
			LinearLayout top = Ui.row(this);
			TextView name = Ui.label(this, d.name, 15, Weapon.CAT_COLORS[d.cat], true);
			top.addView(name, Ui.lp(this, 0, -2, 1f, 0, 0, 0, 0));
			c.addView(top);
			float dps = d.damage * d.pellets / Math.max(0.05f, d.interval);
			c.addView(stat(Weapon.CAT_NAMES[d.cat] + "  •  " + Weapon.AMMO_NAMES[d.ammo] + "  •  " + (d.auto ? "Auto" : "Semi")));
			c.addView(stat(String.format(java.util.Locale.US, "Damage %.0f%s  •  Fire rate %.0f/min  •  Mag %d  •  Reload %.1fs  •  Range %.0fm  •  Max DPS %.0f",
				d.damage, d.pellets > 1 ? " x" + d.pellets : "", 60f / d.interval, d.mag, d.reload, d.range, dps)));
			list.addView(c, Ui.lp(this, -1, -2, 0, 0, 0, 0, 6));
		}
	}
}
