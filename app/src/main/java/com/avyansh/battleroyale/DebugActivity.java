package com.avyansh.battleroyale;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

/**
 * Shown when the app crashes (debug builds made with Codeware Studio). Explains the error in plain words,
 * points to the line in your code, and lets you copy the details or restart the app.
 * Built without resources or libraries so it works in every project.
 */
public class DebugActivity extends Activity {

    /** Exception simple name -> {title, explanation}. */
    private static final Map<String, String[]> EXPLANATIONS = new HashMap<String, String[]>();

    static {
        explain("NullPointerException", "Something was empty (null)",
                "A view, variable, list item or map value was used before it had a value.");
        explain("IndexOutOfBoundsException", "Invalid list position",
                "A list was read at a position that doesn't exist. Check the list's length first.");
        explain("ArrayIndexOutOfBoundsException", "Invalid list position",
                "An array was read at a position that doesn't exist. Check its length first.");
        explain("StringIndexOutOfBoundsException", "Invalid text position",
                "A substring or character position is outside the text. Check the text's length first.");
        explain("NumberFormatException", "This text isn't a number",
                "A toNumber operation got text that is empty or contains letters.");
        explain("ArithmeticException", "Invalid math operation",
                "Usually a division by zero.");
        explain("ActivityNotFoundException", "Screen or app not found",
                "An intent points to a screen that isn't declared or to an app that isn't installed.");
        explain("ClassCastException", "Wrong type of value",
                "A value was used as another type, for example a number used as text.");
        explain("IllegalStateException", "Done at the wrong moment",
                "An action ran in a state that doesn't allow it.");
        explain("IllegalArgumentException", "Invalid value",
                "A method received a value it doesn't accept.");
        explain("SecurityException", "Permission missing",
                "Add the permission in the manifest and ask for it at runtime.");
        explain("NetworkOnMainThreadException", "Network call on the main thread",
                "Use the RequestNetwork component or a background thread for network calls.");
        explain("OutOfMemoryError", "Out of memory",
                "Images or lists are probably too big. Load smaller images.");
        explain("JSONException", "Invalid JSON",
                "The text isn't valid JSON, or a key you read doesn't exist.");
        explain("NotFoundException", "Resource not found",
                "An image, color, string or layout name doesn't exist in the project.");
        explain("BadTokenException", "Dialog shown too late",
                "A dialog or popup was shown after its screen was closed.");
        explain("FileNotFoundException", "File not found",
                "Check the file path and the storage permission.");
        explain("StackOverflowError", "Endless chain of calls",
                "A function keeps calling itself (or two functions call each other) without stopping.");
        explain("InflateException", "Layout couldn't be built",
                "Check custom views and the Inject code of the views on this screen.");
        explain("UnsatisfiedLinkError", "Native library missing",
                "A library needs native files that aren't included for this device.");
    }

    private static void explain(String name, String title, String text) {
        EXPLANATIONS.put(name, new String[]{title, text});
    }

    // Codeware Studio palette (light / dark)
    private boolean dark;
    private int cBg, cInk, cMid, cLine, cSnow, cRed, cRedText, cRedLight, cRedMid, cBlue, cBlueText, cBlueLight, cBlueMid;
    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Intent intent = getIntent();
        String error = intent == null ? null : intent.getStringExtra("error");
        if (error == null || error.trim().length() == 0) error = "No error message available.";
        final String trace = error;
        String[] crash = parseCrash(trace, getPackageName());

        setupColors();
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(cBg);
            getWindow().setNavigationBarColor(cBg);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            // set explicitly both ways: the app theme may already ask for light (dark-icon) bars
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            flags = dark ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                flags = dark ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(cBg);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(content);

        // header: red "!" tile + title
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text("!", 28, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackgroundDrawable(tile(cRed, darken(cRed)));
        badge.setPadding(0, 0, 0, dp(4));
        header.addView(badge, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Oops! " + appName() + " crashed", 22, cInk, true));
        TextView subtitle = text("Don't worry. Here's what happened.", 14, cMid, false);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(subtitle);
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titlesParams.leftMargin = dp(14);
        header.addView(titles, titlesParams);
        content.addView(header);

        // what happened
        LinearLayout what = card(cRedLight, cRedMid);
        what.addView(label("WHAT HAPPENED", cRedText));
        String[] explanation = EXPLANATIONS.get(crash[0]);
        TextView whatTitle = text(explanation != null ? explanation[0] : "Unexpected error", 17, cInk, true);
        whatTitle.setPadding(0, dp(4), 0, 0);
        what.addView(whatTitle);
        if (explanation != null) {
            TextView tip = text(explanation[1], 14, cMid, false);
            tip.setPadding(0, dp(2), 0, 0);
            what.addView(tip);
        }
        TextView exception = text(crash[0] + (crash[1].length() > 0 ? ": " + crash[1] : ""), 12, cRedText, false);
        exception.setTypeface(Typeface.MONOSPACE);
        exception.setPadding(0, dp(8), 0, 0);
        exception.setTextIsSelectable(true);
        what.addView(exception);
        content.addView(what, cardParams(18));

        // where
        if (crash[2].length() > 0) {
            LinearLayout where = card(cBlueLight, cBlueMid);
            where.addView(label("WHERE", cBlueText));
            TextView place = text(crash[2], 15, cInk, true);
            place.setPadding(0, dp(4), 0, 0);
            where.addView(place);
            content.addView(where, cardParams(12));
        }

        // details
        TextView detailsLabel = label("DETAILS", cMid);
        LinearLayout.LayoutParams detailsLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        detailsLabelParams.topMargin = dp(20);
        detailsLabelParams.leftMargin = dp(4);
        content.addView(detailsLabel, detailsLabelParams);
        HorizontalScrollView traceScroll = new HorizontalScrollView(this);
        traceScroll.setBackgroundDrawable(rounded(cSnow, cLine, 12));
        TextView traceView = text(trace, 12, cInk, false);
        traceView.setTypeface(Typeface.MONOSPACE);
        traceView.setTextIsSelectable(true);
        traceView.setPadding(dp(12), dp(10), dp(12), dp(10));
        traceScroll.addView(traceView);
        content.addView(traceScroll, cardParams(6));

        // actions
        TextView restart = button("RESTART APP", Color.WHITE, threeD(0xFF58CC02, 0xFF58A700));
        restart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartApp();
            }
        });
        content.addView(restart, cardParams(24));

        TextView copy = button("COPY ERROR", cBlueText, threeD(cBg, cLine, cLine));
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("error", trace));
                    Toast.makeText(DebugActivity.this, "Error copied", Toast.LENGTH_SHORT).show();
                }
            }
        });
        content.addView(copy, cardParams(12));

        TextView close = button("CLOSE", cMid, null);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        content.addView(close, cardParams(4));

        setContentView(scroll);
    }

    /**
     * Returns {exception simple name, message, location}. The innermost "Caused by" is used, since
     * it is the real cause. Location is the first stack frame inside {@code packageName}, e.g.
     * "MainActivity.java · line 54 (onCreate)", or "".
     */
    static String[] parseCrash(String trace, String packageName) {
        String[] lines = trace.split("\n");
        String head = lines.length > 0 ? lines[0].trim() : "";
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Caused by: ")) head = trimmed.substring("Caused by: ".length());
        }
        String type = head;
        String message = "";
        int colon = head.indexOf(": ");
        if (colon >= 0) {
            type = head.substring(0, colon);
            message = head.substring(colon + 2);
        }
        if (type.length() == 0 || type.indexOf(' ') >= 0 || type.endsWith(".")) {
            // not "pkg.SomeException": plain text
            message = head;
            type = "Error";
        } else {
            type = type.substring(type.lastIndexOf('.') + 1);
            type = type.substring(type.lastIndexOf('$') + 1);
        }

        String location = "";
        String prefix = "at " + packageName + ".";
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(prefix)) continue;
            int open = trimmed.lastIndexOf('(');
            int close = trimmed.lastIndexOf(')');
            if (open < 0 || close < open) continue;
            String file = trimmed.substring(open + 1, close); // MainActivity.java:54
            String method = trimmed.substring(0, open);
            method = method.substring(method.lastIndexOf('.') + 1);
            int fileColon = file.lastIndexOf(':');
            if (fileColon > 0) {
                location = file.substring(0, fileColon) + " · line " + file.substring(fileColon + 1);
            } else {
                location = file;
            }
            if (method.length() > 0 && !method.startsWith("lambda$")) location += " (" + method + ")";
            break;
        }
        return new String[]{type, message, location};
    }

    /**
     * This screen already runs in a fresh process (the crashed one was killed before it opened),
     * so starting the launcher screen in a cleared task is a full restart.
     */
    private void restartApp() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(launch);
        }
        finish();
    }

    private String appName() {
        try {
            CharSequence label = getApplicationInfo().loadLabel(getPackageManager());
            if (label != null && label.length() > 0) return label.toString();
        } catch (Exception ignored) {
        }
        return "The app";
    }

    // ------------------------------------------------------------------ styling helpers

    private void setupColors() {
        density = getResources().getDisplayMetrics().density;
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        cRed = 0xFFFF4B4B;
        cBlue = 0xFF1CB0F6;
        if (dark) {
            cBg = 0xFF131F24;
            cInk = 0xFFF1F7FB;
            cMid = 0xFFA4B3BB;
            cLine = 0xFF37464F;
            cSnow = 0xFF202F36;
            cRedText = 0xFFFF7A7A;
            cRedLight = 0xFF3A2328;
            cRedMid = 0xFF6B3036;
            cBlueText = 0xFF49C0F8;
            cBlueLight = 0xFF15303F;
            cBlueMid = 0xFF1F4F66;
        } else {
            cBg = 0xFFFFFFFF;
            cInk = 0xFF3C3C3C;
            cMid = 0xFF777777;
            cLine = 0xFFE5E5E5;
            cSnow = 0xFFF7F7F7;
            cRedText = 0xFFEA2B2B;
            cRedLight = 0xFFFFDFE0;
            cRedMid = 0xFFFFB2B2;
            cBlueText = 0xFF1899D6;
            cBlueLight = 0xFFDDF4FF;
            cBlueMid = 0xFF84D8FF;
        }
    }

    private int dp(int value) {
        return (int) (value * density + 0.5f);
    }

    private static int darken(int color) {
        return Color.rgb((int) (Color.red(color) * 0.82f), (int) (Color.green(color) * 0.82f), (int) (Color.blue(color) * 0.82f));
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView label(String value, int color) {
        TextView view = text(value, 11, color, true);
        if (Build.VERSION.SDK_INT >= 21) view.setLetterSpacing(0.08f);
        return view;
    }

    private TextView button(String value, int textColor, Drawable background) {
        TextView view = text(value, 15, textColor, true);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(true);
        if (Build.VERSION.SDK_INT >= 21) view.setLetterSpacing(0.05f);
        view.setPadding(dp(16), dp(13), dp(16), dp(17));
        if (background != null) view.setBackgroundDrawable(background);
        return view;
    }

    private LinearLayout.LayoutParams cardParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private LinearLayout card(int fill, int stroke) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(16));
        card.setBackgroundDrawable(lipped(fill, stroke, stroke, 16, false));
        return card;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (stroke != 0) shape.setStroke(dp(2), stroke);
        return shape;
    }

    private Drawable tile(int fill, int lip) {
        return lipped(fill, 0, lip, 14, false);
    }

    /** A "3D" surface: a darker lip under the face; pressed, the face sinks onto it. */
    private Drawable lipped(int fill, int stroke, int lip, int radiusDp, boolean pressed) {
        GradientDrawable bottom = rounded(lip, 0, radiusDp);
        GradientDrawable face = rounded(fill, stroke, radiusDp);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{bottom, face});
        int lipHeight = dp(4);
        layers.setLayerInset(1, 0, pressed ? lipHeight : 0, 0, pressed ? 0 : lipHeight);
        return layers;
    }

    private Drawable threeD(int fill, int lip) {
        return threeD(fill, 0, lip);
    }

    private Drawable threeD(int fill, int stroke, int lip) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, lipped(fill, stroke, lip, 16, true));
        states.addState(new int[]{}, lipped(fill, stroke, lip, 16, false));
        return states;
    }
}
