package com.avyansh.battleroyale;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Lightweight, dependency-free premium game dialog skin. Safe for Sketchware/Java projects. */
public final class PremiumDialog {
    private static final int GOLD = Color.rgb(255, 205, 75);
    private static final int MUTED = Color.rgb(164, 181, 197);
    private static final int PANEL = Color.rgb(20, 31, 45);
    private PremiumDialog() {}

    public static int dp(Activity a, float v) { return (int)(v * a.getResources().getDisplayMetrics().density + .5f); }
    public static GradientDrawable shape(int color, int stroke, int radius, Activity a) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(a, radius));
        if (stroke != 0) d.setStroke(dp(a, 1), stroke); return d;
    }
    public static TextView text(Activity a, String value, int size, int color, boolean bold) {
        TextView t = new TextView(a); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    public static LinearLayout column(Activity a) { LinearLayout l = new LinearLayout(a); l.setOrientation(LinearLayout.VERTICAL); return l; }
    public static LinearLayout row(Activity a) { LinearLayout l = new LinearLayout(a); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    public static void section(Activity a, LinearLayout parent, String title) {
        TextView t = text(a, title.toUpperCase(), 11, GOLD, true); t.setPadding(0, dp(a,14), 0, dp(a,7)); parent.addView(t);
    }
    public static void style(Dialog dialog, Activity a) {
        Window w = dialog.getWindow(); if (w == null) return;
        w.setBackgroundDrawableResource(android.R.color.transparent);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams p = w.getAttributes(); p.dimAmount = .72f; w.setAttributes(p);
        int maxH = a.getResources().getDisplayMetrics().heightPixels - dp(a, 24);
        w.setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels - dp(a, 28), dp(a, 480)), Math.min(WindowManager.LayoutParams.WRAP_CONTENT, maxH));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = w.getInsetsController();
            if (c != null) c.hide(android.view.WindowInsets.Type.systemBars());
        }
    }
    /** Shows a standard dialog (see {@link GameDialog}); kept as the entry point used across the game. */
    public static Dialog show(Activity a, String title, String subtitle, View content,
                              String positive, final Runnable onPositive,
                              String negative, final Runnable onNegative) {
        return show(a, title, subtitle, content, positive, onPositive, negative, onNegative, GameDialog.Tone.DEFAULT);
    }

    /** Same as above with an explicit tone (use {@link GameDialog.Tone#DANGER} for destructive actions). */
    public static Dialog show(Activity a, String title, String subtitle, View content,
                              String positive, Runnable onPositive,
                              String negative, Runnable onNegative, GameDialog.Tone tone) {
        return GameDialog.with(a).tone(tone).title(title).subtitle(subtitle).content(content)
            .primary(positive, onPositive).secondary(negative, onNegative).show();
    }
    public static TextView button(Activity a, String label, boolean primary) {
        TextView t = text(a, label.toUpperCase(), 12, primary ? Color.rgb(30,35,39) : Color.WHITE, true);
        t.setGravity(Gravity.CENTER); t.setPadding(dp(a,10),0,dp(a,10),0);
        if (primary) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Color.rgb(255,226,128), Color.rgb(255,176,40)});
            g.setCornerRadius(dp(a, 12)); t.setBackground(g);
        } else {
            t.setBackground(shape(Color.argb(150, 28, 42, 58), Color.rgb(85,108,128), 12, a));
        }
        t.setClickable(true); t.setFocusable(true); return t;
    }
    public static void card(Activity a, LinearLayout parent, View child) {
        LinearLayout wrap = column(a); wrap.setPadding(dp(a,12), dp(a,10), dp(a,12), dp(a,10));
        wrap.setBackground(shape(PANEL, Color.rgb(48,66,82), 10, a)); wrap.addView(child);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(a,7); parent.addView(wrap,p);
    }
}
