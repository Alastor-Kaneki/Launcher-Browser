package dev.alastorkaneki.launcherbrowser;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

final class Ui {
    static final int PURPLE = Color.rgb(139, 92, 246);
    static final int RED = Color.rgb(225, 29, 72);
    static final int WHITE = Color.WHITE;
    static final int PANEL = Color.argb(190, 10, 10, 14);
    static final int PANEL_LIGHT = Color.argb(150, 25, 25, 32);

    private static final int LEGACY_IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;

    private Ui() {}

    static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(int color, float radiusDp, Activity activity) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(activity, Math.round(radiusDp)));
        drawable.setStroke(dp(activity, 1), Color.argb(150, 139, 92, 246));
        return drawable;
    }

    static Button button(Activity activity, String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextColor(WHITE);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(PANEL_LIGHT, 18, activity));
        button.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
        return button;
    }

    static TextView title(Activity activity, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(WHITE);
        view.setTextSize(22);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    static void applyImmersive(Activity activity) {
        if (activity == null || !Prefs.immersive(activity)) return;

        final Window window;
        final View decor;
        try {
            window = activity.getWindow();
            if (window == null) return;
            decor = window.getDecorView();
            if (decor == null) return;
        } catch (Throwable ignored) {
            return;
        }

        // Some Motorola Android 15 builds crash inside Window#getInsetsController()
        // while the DecorView has not been attached yet. Always defer the modern
        // insets call and obtain the controller from the attached decor view instead.
        decor.post(() -> {
            if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;
            applyImmersiveNow(window, decor);
        });
    }

    private static void applyImmersiveNow(Window window, View decor) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    return;
                }
            } catch (Throwable ignored) {
                // OEM framework bug or an activity that is still detaching.
            }
        }
        applyLegacyImmersive(decor);
    }

    private static void applyLegacyImmersive(View decor) {
        try {
            decor.setSystemUiVisibility(LEGACY_IMMERSIVE_FLAGS);
        } catch (Throwable ignored) {
            // Immersive mode must never take down the launcher.
        }
    }

    static void showWallpaper(Activity activity) {
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        activity.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }
}
