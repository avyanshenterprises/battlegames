package com.avyansh.battleroyale;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.util.Log;

import java.util.WeakHashMap;

public class CodewareApplication extends Application {

    private static Context mApplicationContext;

    public static Context getContext() {
        return mApplicationContext;
    }

    @Override
    public void onCreate() {
        mApplicationContext = getApplicationContext();

        Thread.setDefaultUncaughtExceptionHandler(
                new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread thread, Throwable throwable) {
                        Intent intent = new Intent(getApplicationContext(), DebugActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        intent.putExtra("error", Log.getStackTraceString(throwable));
                        startActivity(intent);
                        SKLogger.broadcastLog(Log.getStackTraceString(throwable));
                    Process.killProcess(Process.myPid());
                        System.exit(1);
                    }
                });
        SystemBars.register(this);
        SKLogger.startLogging();
        super.onCreate();
    }

    /**
     * Apps that target Android 15 (API 35) or newer are drawn behind the status and navigation bars on Android 15
     * and newer. Every screen is kept clear of the bars, as on older versions, and the bars keep the colours of the
     * theme (or the ones the app sets).
     */
    static final class SystemBars extends Drawable {

        private static final WeakHashMap<View, Boolean> FITTED = new WeakHashMap<>();

        private final Window window;
        private final Drawable inner;
        private final Paint paint = new Paint();
        private final int themeStatus;
        private final int themeNavigation;
        private int top;
        private int bottom;
        private int left;
        private int right;

        static void register(Application application) {
            if (Build.VERSION.SDK_INT < 35 || application.getApplicationInfo().targetSdkVersion < 35) return;
            application.registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                }

                @Override
                public void onActivityStarted(Activity activity) {
                    fit(activity);
                }

                @Override
                public void onActivityResumed(Activity activity) {
                }

                @Override
                public void onActivityPaused(Activity activity) {
                }

                @Override
                public void onActivityStopped(Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                }
            });
        }

        static void fit(Activity activity) {
            View content = activity.findViewById(android.R.id.content);
            if (content == null || FITTED.containsKey(content)) return;
            FITTED.put(content, Boolean.TRUE);
            final SystemBars bars = new SystemBars(activity, content.getBackground());
            content.setBackground(bars);
            content.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                    bars.left = insets.getSystemWindowInsetLeft();
                    bars.top = insets.getSystemWindowInsetTop();
                    bars.right = insets.getSystemWindowInsetRight();
                    bars.bottom = insets.getSystemWindowInsetBottom();
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    bars.invalidateSelf();
                    return insets.consumeSystemWindowInsets();
                }
            });
            content.requestApplyInsets();
        }

        private SystemBars(Activity activity, Drawable inner) {
            this.window = activity.getWindow();
            this.inner = inner;
            TypedArray a = activity.getTheme().obtainStyledAttributes(new int[]{
                    android.R.attr.statusBarColor, android.R.attr.colorPrimaryDark, android.R.attr.navigationBarColor});
            int status = a.getColor(0, Color.TRANSPARENT);
            if (Color.alpha(status) == 0) status = a.getColor(1, Color.BLACK);
            themeStatus = status;
            themeNavigation = a.getColor(2, Color.BLACK);
            a.recycle();
        }

        @SuppressWarnings("deprecation")
        private int statusColor() {
            int c = window.getStatusBarColor();
            return Color.alpha(c) == 0 ? themeStatus : c;
        }

        @SuppressWarnings("deprecation")
        private int navigationColor() {
            int c = window.getNavigationBarColor();
            return Color.alpha(c) == 0 ? themeNavigation : c;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (inner != null) {
                inner.setBounds(b.left + left, b.top + top, b.right - right, b.bottom - bottom);
                inner.draw(canvas);
            }
            if (top > 0) {
                paint.setColor(statusColor());
                canvas.drawRect(b.left, b.top, b.right, b.top + top, paint);
            }
            paint.setColor(navigationColor());
            if (bottom > 0) canvas.drawRect(b.left, b.bottom - bottom, b.right, b.bottom, paint);
            if (left > 0) canvas.drawRect(b.left, b.top + top, b.left + left, b.bottom - bottom, paint);
            if (right > 0) canvas.drawRect(b.right - right, b.top + top, b.right, b.bottom - bottom, paint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
