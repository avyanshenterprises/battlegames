package com.avyansh.battleroyale;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Single, consistent dialog skin for the whole game.
 *
 * <pre>
 * GameDialog.with(activity)
 *     .tone(GameDialog.Tone.DANGER)
 *     .glyph("!")
 *     .title("LEAVE MATCH?")
 *     .subtitle("GAME ID  G1AB2CXYZ")
 *     .message("Your team keeps playing.")
 *     .primary("LEAVE", this::exitToLobby)
 *     .secondary("STAY", null)
 *     .show();
 * </pre>
 *
 * Layout: tinted header (icon badge, title, subtitle, close button) / scrollable body / action bar.
 * Everything is built in code so the dialog has no XML or library dependency.
 */
public final class GameDialog {

	/** Colour scheme of a dialog; drives the accent stripe, icon badge and primary button. */
	public enum Tone {
		DEFAULT(0xFFFFC94A, 0xFFFFA81F, "\u25C6"),
		DANGER(0xFFFF6B6B, 0xFFE03A3A, "!"),
		SUCCESS(0xFF5BE38A, 0xFF1FB85A, "\u2713");

		final int light;
		final int dark;
		final String defaultGlyph;

		Tone(int light, int dark, String defaultGlyph) {
			this.light = light;
			this.dark = dark;
			this.defaultGlyph = defaultGlyph;
		}
	}

	// ---- design tokens (dp unless noted) ----
	private static final int CARD_RADIUS = 22;
	private static final int BUTTON_RADIUS = 14;
	private static final int BUTTON_HEIGHT = 46;
	private static final int BADGE_SIZE = 42;
	private static final int ACCENT_HEIGHT = 4;
	private static final int MAX_WIDTH = 480;
	private static final int SCREEN_MARGIN = 28;
	private static final int ENTER_MS = 190;
	private static final float ENTER_SCALE = 0.92f;
	private static final float DIM = 0.72f;

	private static final int COLOR_BG_TOP = Color.rgb(24, 37, 52);
	private static final int COLOR_BG_BOTTOM = Color.rgb(8, 13, 21);
	private static final int COLOR_HEADER = Color.argb(70, 255, 255, 255);
	private static final int COLOR_FOOTER = Color.argb(150, 5, 9, 15);
	private static final int COLOR_TEXT = Color.WHITE;
	private static final int COLOR_MUTED = Color.rgb(160, 178, 194);
	private static final int COLOR_OUTLINE = Color.rgb(88, 111, 131);
	private static final int COLOR_BUTTON_FILL = Color.argb(150, 28, 42, 58);

	private final Activity activity;
	private Tone tone = Tone.DEFAULT;
	private String glyph;
	private String title = "";
	private String subtitle;
	private View content;
	private String primaryLabel;
	private Runnable primaryAction;
	private String secondaryLabel;
	private Runnable secondaryAction;
	private boolean cancelable = true;

	private GameDialog(Activity activity) {
		this.activity = activity;
	}

	public static GameDialog with(Activity activity) {
		return new GameDialog(activity);
	}

	public GameDialog tone(Tone value) { this.tone = value == null ? Tone.DEFAULT : value; return this; }
	public GameDialog glyph(String value) { this.glyph = value; return this; }
	public GameDialog title(String value) { this.title = value == null ? "" : value; return this; }
	public GameDialog subtitle(String value) { this.subtitle = value; return this; }
	public GameDialog content(View value) { this.content = value; return this; }
	public GameDialog cancelable(boolean value) { this.cancelable = value; return this; }

	/** Plain paragraph as the dialog body. */
	public GameDialog message(String text) {
		TextView body = label(text, 13, COLOR_TEXT, false);
		body.setLineSpacing(0f, 1.15f);
		this.content = body;
		return this;
	}

	/** Main action; the dialog dismisses itself after {@code action} runs. */
	public GameDialog primary(String label, Runnable action) {
		this.primaryLabel = label;
		this.primaryAction = action;
		return this;
	}

	/** Secondary action; the dialog dismisses itself before {@code action} runs. */
	public GameDialog secondary(String label, Runnable action) {
		this.secondaryLabel = label;
		this.secondaryAction = action;
		return this;
	}

	public Dialog show() {
		final Dialog dialog = new Dialog(activity);
		dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

		final LinearLayout card = new LinearLayout(activity);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setBackground(cardBackground());
		card.addView(buildAccent(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ACCENT_HEIGHT)));
		card.addView(buildHeader(dialog));
		card.addView(buildBody(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		if (primaryLabel != null || secondaryLabel != null) card.addView(buildFooter(dialog));

		card.setScaleX(ENTER_SCALE);
		card.setScaleY(ENTER_SCALE);
		card.setAlpha(0f);

		dialog.setContentView(card);
		dialog.setCancelable(cancelable);
		dialog.setCanceledOnTouchOutside(cancelable);
		dialog.setOnShowListener(d -> {
			applyWindow(dialog);
			card.animate().scaleX(1f).scaleY(1f).alpha(1f)
				.setDuration(ENTER_MS).setInterpolator(new DecelerateInterpolator()).start();
		});
		dialog.show();
		applyWindow(dialog);
		return dialog;
	}

	// ------------------------------------------------------------------ building blocks
	private GradientDrawable cardBackground() {
		GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
			new int[] { COLOR_BG_TOP, COLOR_BG_BOTTOM });
		bg.setCornerRadius(dp(CARD_RADIUS));
		bg.setStroke(dp(1), withAlpha(tone.light, 170));
		return bg;
	}

	private View buildAccent() {
		View accent = new View(activity);
		GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
			new int[] { tone.light, tone.dark, tone.light });
		float r = dp(CARD_RADIUS - 1);
		g.setCornerRadii(new float[] { r, r, r, r, 0, 0, 0, 0 });
		accent.setBackground(g);
		return accent;
	}

	private View buildHeader(final Dialog dialog) {
		LinearLayout header = new LinearLayout(activity);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(dp(16), dp(14), dp(12), dp(12));
		header.setBackgroundColor(COLOR_HEADER);

		TextView badge = label(glyph != null ? glyph : tone.defaultGlyph, 20, Color.rgb(22, 28, 36), true);
		badge.setGravity(Gravity.CENTER);
		GradientDrawable badgeBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
			new int[] { tone.light, tone.dark });
		badgeBg.setShape(GradientDrawable.OVAL);
		badge.setBackground(badgeBg);
		header.addView(badge, new LinearLayout.LayoutParams(dp(BADGE_SIZE), dp(BADGE_SIZE)));

		LinearLayout texts = new LinearLayout(activity);
		texts.setOrientation(LinearLayout.VERTICAL);
		texts.setPadding(dp(12), 0, dp(8), 0);
		texts.addView(label(title, 18, COLOR_TEXT, true));
		if (subtitle != null && subtitle.length() > 0) {
			TextView sub = label(subtitle, 11, COLOR_MUTED, false);
			sub.setPadding(0, dp(2), 0, 0);
			texts.addView(sub);
		}
		header.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		if (cancelable) {
			TextView close = label("\u2715", 14, COLOR_MUTED, true);
			close.setGravity(Gravity.CENTER);
			close.setBackground(rounded(Color.argb(90, 255, 255, 255), 0, 16));
			close.setOnClickListener(v -> dialog.dismiss());
			header.addView(close, new LinearLayout.LayoutParams(dp(32), dp(32)));
		}
		return header;
	}

	private View buildBody() {
		ScrollView scroll = new ScrollView(activity);
		scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
		scroll.setVerticalScrollBarEnabled(false);
		LinearLayout body = new LinearLayout(activity);
		body.setOrientation(LinearLayout.VERTICAL);
		body.setPadding(dp(18), dp(14), dp(18), dp(14));
		if (content != null) {
			if (content.getParent() instanceof ViewGroup) ((ViewGroup) content.getParent()).removeView(content);
			body.addView(content);
		}
		scroll.addView(body);
		return scroll;
	}

	private View buildFooter(final Dialog dialog) {
		LinearLayout footer = new LinearLayout(activity);
		footer.setOrientation(LinearLayout.HORIZONTAL);
		footer.setGravity(Gravity.CENTER_VERTICAL);
		footer.setPadding(dp(14), dp(10), dp(14), dp(14));
		footer.setBackgroundColor(COLOR_FOOTER);

		if (secondaryLabel != null) {
			TextView b = actionButton(secondaryLabel, false);
			b.setOnClickListener(v -> {
				dialog.dismiss();
				if (secondaryAction != null) secondaryAction.run();
			});
			footer.addView(b, new LinearLayout.LayoutParams(0, dp(BUTTON_HEIGHT), 1f));
		}
		if (primaryLabel != null) {
			TextView b = actionButton(primaryLabel, true);
			b.setOnClickListener(v -> {
				if (primaryAction != null) primaryAction.run();
				dialog.dismiss();
			});
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(BUTTON_HEIGHT), 1.25f);
			if (secondaryLabel != null) lp.leftMargin = dp(10);
			footer.addView(b, lp);
		}
		return footer;
	}

	private TextView actionButton(String text, boolean primary) {
		TextView b = label(text.toUpperCase(), 12, primary ? Color.rgb(24, 28, 34) : COLOR_TEXT, true);
		b.setGravity(Gravity.CENTER);
		b.setClickable(true);
		b.setFocusable(true);
		if (primary) {
			GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
				new int[] { tone.light, tone.dark });
			g.setCornerRadius(dp(BUTTON_RADIUS));
			b.setBackground(g);
			if (tone == Tone.DANGER) b.setTextColor(COLOR_TEXT);
		} else {
			b.setBackground(rounded(COLOR_BUTTON_FILL, COLOR_OUTLINE, BUTTON_RADIUS));
		}
		b.setOnTouchListener((v, e) -> {
			int a = e.getActionMasked();
			if (a == MotionEvent.ACTION_DOWN) v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).start();
			else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
			return false;
		});
		return b;
	}

	// ------------------------------------------------------------------ window
	private void applyWindow(Dialog dialog) {
		Window w = dialog.getWindow();
		if (w == null) return;
		w.setBackgroundDrawableResource(android.R.color.transparent);
		w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
		WindowManager.LayoutParams p = w.getAttributes();
		p.dimAmount = DIM;
		w.setAttributes(p);

		DisplayMetrics dm = activity.getResources().getDisplayMetrics();
		int width = Math.min(dm.widthPixels - dp(SCREEN_MARGIN), dp(MAX_WIDTH));
		int maxHeight = dm.heightPixels - dp(24);
		w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
		w.getDecorView().setMinimumHeight(0);
		w.getDecorView().post(() -> {
			View root = w.getDecorView();
			if (root.getHeight() > maxHeight) w.setLayout(width, maxHeight);
		});

		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
			WindowInsetsController c = w.getInsetsController();
			if (c != null) c.hide(WindowInsets.Type.systemBars());
		}
	}

	// ------------------------------------------------------------------ helpers
	private TextView label(String text, int sp, int color, boolean bold) {
		TextView t = new TextView(activity);
		t.setText(text);
		t.setTextSize(sp);
		t.setTextColor(color);
		if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		return t;
	}

	private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
		GradientDrawable d = new GradientDrawable();
		d.setColor(fill);
		d.setCornerRadius(dp(radiusDp));
		if (stroke != 0) d.setStroke(dp(1), stroke);
		return d;
	}

	private static int withAlpha(int color, int alpha) {
		return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
	}

	private int dp(int v) {
		return Math.round(activity.getResources().getDisplayMetrics().density * v);
	}
}
