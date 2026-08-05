package dev.alastorkaneki.launcherbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Defensive launcher entry point. No individual app, icon pack, or widget is
 * allowed to take down the entire home process.
 */
public final class ResilientHomeActivity extends Activity {
    private static final int HOST_ID = 1187;
    private static final int REQUEST_PICK_WIDGET = 2101;
    private static final int REQUEST_CONFIGURE_WIDGET = 2102;

    private final List<AppEntry> pinnedApps = new ArrayList<>();
    private GridView pinnedGrid;
    private PinnedAdapter pinnedAdapter;
    private LinearLayout widgetArea;
    private AppWidgetHost widgetHost;
    private AppWidgetManager widgetManager;
    private int pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (Prefs.transparentHome(this)) Ui.showWallpaper(this);
            Ui.applyImmersive(this);
            widgetManager = AppWidgetManager.getInstance(this);
            widgetHost = new AppWidgetHost(this, HOST_ID);
            buildUi();
            loadPinnedApps();
            restoreWidgets();
        } catch (Throwable error) {
            showEmergencyHome(error);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        try { Ui.applyImmersive(this); } catch (Throwable ignored) {}
        if (widgetHost != null) {
            try { widgetHost.startListening(); } catch (Throwable ignored) {}
        }
        if (pinnedGrid != null) loadPinnedApps();
    }

    @Override protected void onPause() {
        if (widgetHost != null) {
            try { widgetHost.stopListening(); } catch (Throwable ignored) {}
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (widgetHost != null) {
            try { widgetHost.stopListening(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        // A launcher remains on its home surface.
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Prefs.transparentHome(this) ? Color.TRANSPARENT : Color.BLACK);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 12));
        root.addView(page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search or enter address");
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Color.LTGRAY);
        search.setBackground(Ui.rounded(Ui.PANEL, 24, this));
        search.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        search.setOnEditorActionListener((view, actionId, event) -> {
            if (event == null || event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                openBrowser(search.getText().toString());
                return true;
            }
            return false;
        });
        page.addView(search, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)));

        String previousCrash = CrashLogApplication.readLastCrash(this);
        if (!previousCrash.isEmpty()) {
            Button crashDetails = Ui.button(this, "Previous crash details");
            crashDetails.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Last Launcher Browser crash")
                    .setMessage(previousCrash)
                    .setPositiveButton("Clear", (dialog, which) -> {
                        CrashLogApplication.clearLastCrash(this);
                        crashDetails.setVisibility(View.GONE);
                    })
                    .setNegativeButton("Close", null)
                    .show());
            LinearLayout.LayoutParams crashParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
            crashParams.setMargins(0, Ui.dp(this, 7), 0, 0);
            page.addView(crashDetails, crashParams);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        pinnedGrid = new GridView(this);
        pinnedGrid.setNumColumns(Prefs.columns(this));
        pinnedGrid.setGravity(Gravity.CENTER);
        pinnedGrid.setHorizontalSpacing(Ui.dp(this, 6));
        pinnedGrid.setVerticalSpacing(Ui.dp(this, 8));
        pinnedGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        pinnedAdapter = new PinnedAdapter();
        pinnedGrid.setAdapter(pinnedAdapter);
        pinnedGrid.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < pinnedApps.size()) launch(pinnedApps.get(position));
        });
        pinnedGrid.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < pinnedApps.size()) showPinnedActions(pinnedApps.get(position));
            return true;
        });
        content.addView(pinnedGrid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 210)));

        LinearLayout widgetHeader = new LinearLayout(this);
        widgetHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView widgetsTitle = Ui.title(this, "Widgets");
        widgetsTitle.setTextSize(17);
        widgetHeader.addView(widgetsTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button addWidget = Ui.button(this, "+ Add");
        addWidget.setEnabled(widgetHost != null && widgetManager != null);
        addWidget.setOnClickListener(v -> pickWidget());
        widgetHeader.addView(addWidget, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 42)));
        content.addView(widgetHeader);

        widgetArea = new LinearLayout(this);
        widgetArea.setOrientation(LinearLayout.VERTICAL);
        content.addView(widgetArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(Ui.dp(this, 6), Ui.dp(this, 7), Ui.dp(this, 6), Ui.dp(this, 7));
        dock.setBackground(Ui.rounded(Ui.PANEL, 24, this));
        addDockButton(dock, "Browser", () -> safeStart(new Intent(this, BrowserActivity.class)));
        addDockButton(dock, "Apps", () -> safeStart(new Intent(this, AppDrawerActivity.class)));
        addDockButton(dock, "Shell", () -> safeStart(new Intent(this, ShellActivity.class)));
        addDockButton(dock, "Widgets", this::pickWidget);
        addDockButton(dock, "Settings", () -> safeStart(new Intent(this, SettingsActivity.class)));
        page.addView(dock, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 62)));

        setContentView(root);
    }

    private void addDockButton(LinearLayout dock, String text, Runnable action) {
        Button button = Ui.button(this, text);
        button.setTextSize(11);
        button.setOnClickListener(v -> {
            try { action.run(); } catch (Throwable error) { showError("Unable to open " + text, error); }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        dock.addView(button, params);
    }

    private void loadPinnedApps() {
        new Thread(() -> {
            try {
                List<AppEntry> all = loadAppsSafely();
                Set<String> pinned = readPinnedComponents();
                if (pinned.isEmpty()) {
                    for (AppEntry app : all) {
                        if (!app.packageName.equals(getPackageName())) {
                            pinned.add(app.component.flattenToString());
                        }
                        if (pinned.size() >= 8) break;
                    }
                    savePinnedComponents(pinned);
                }

                List<AppEntry> selected = new ArrayList<>();
                for (String component : pinned) {
                    for (AppEntry app : all) {
                        if (app.component.flattenToString().equals(component)) {
                            selected.add(app);
                            break;
                        }
                    }
                }
                runOnUiThread(() -> applyPinnedApps(selected));
            } catch (Throwable error) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "App list failed safely: " + safeMessage(error),
                        Toast.LENGTH_LONG).show());
            }
        }, "load-pinned-safe").start();
    }

    private List<AppEntry> loadAppsSafely() {
        PackageManager pm = getPackageManager();
        List<AppEntry> result = new ArrayList<>();
        List<ResolveInfo> infos;
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            infos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);
        } catch (Throwable error) {
            return result;
        }

        IconPackManager iconPack = null;
        try { iconPack = new IconPackManager(this); } catch (Throwable ignored) {}

        for (ResolveInfo info : infos) {
            try {
                if (info == null || info.activityInfo == null) continue;
                ComponentName component = new ComponentName(
                        info.activityInfo.packageName,
                        info.activityInfo.name);
                String label;
                try { label = String.valueOf(info.loadLabel(pm)); }
                catch (Throwable ignored) { label = info.activityInfo.packageName; }

                Drawable icon;
                try { icon = info.loadIcon(pm); }
                catch (Throwable ignored) { icon = pm.getDefaultActivityIcon(); }
                if (icon == null) icon = pm.getDefaultActivityIcon();

                AppEntry app = new AppEntry(label, info.activityInfo.packageName, component, icon);
                if (iconPack != null) {
                    try {
                        Drawable themed = iconPack.iconFor(component);
                        if (themed != null) app.icon = themed;
                    } catch (Throwable ignored) {
                    }
                }
                result.add(app);
            } catch (Throwable ignored) {
                // One malformed package must never crash the launcher.
            }
        }
        result.sort(Comparator.comparing(
                app -> app.label == null ? "" : app.label.toLowerCase(Locale.ROOT)));
        return result;
    }

    private void applyPinnedApps(List<AppEntry> selected) {
        if (isFinishing() || isDestroyed() || pinnedGrid == null || pinnedAdapter == null) return;
        pinnedApps.clear();
        pinnedApps.addAll(selected);
        int columns = Math.max(3, Prefs.columns(this));
        int rows = Math.max(1, (pinnedApps.size() + columns - 1) / columns);
        if (pinnedGrid.getLayoutParams() != null) {
            pinnedGrid.getLayoutParams().height = Ui.dp(this, Math.max(110, rows * 105));
            pinnedGrid.requestLayout();
        }
        pinnedAdapter.notifyDataSetChanged();
    }

    private Set<String> readPinnedComponents() {
        try {
            Set<String> stored = Prefs.get(this).getStringSet(Prefs.PINNED_APPS, null);
            return stored == null ? new LinkedHashSet<>() : new LinkedHashSet<>(stored);
        } catch (Throwable error) {
            Prefs.get(this).edit().remove(Prefs.PINNED_APPS).apply();
            return new LinkedHashSet<>();
        }
    }

    private void savePinnedComponents(Set<String> components) {
        try {
            Prefs.get(this).edit().putStringSet(
                    Prefs.PINNED_APPS,
                    new LinkedHashSet<>(components)).apply();
        } catch (Throwable ignored) {
        }
    }

    private void launch(AppEntry app) {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Throwable error) {
            showError("Unable to launch " + app.label, error);
        }
    }

    private void showPinnedActions(AppEntry app) {
        String[] actions = {"Open", "Unpin", "Force stop (Shizuku)", "App info"};
        try {
            new AlertDialog.Builder(this).setTitle(app.label).setItems(actions, (dialog, which) -> {
                if (which == 0) launch(app);
                if (which == 1) {
                    Set<String> pinned = readPinnedComponents();
                    pinned.remove(app.component.flattenToString());
                    savePinnedComponents(pinned);
                    loadPinnedApps();
                }
                if (which == 2) forceStop(app);
                if (which == 3) safeStart(new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + app.packageName)));
            }).show();
        } catch (Throwable error) {
            showError("Unable to show app actions", error);
        }
    }

    private void forceStop(AppEntry app) {
        if (!ShellExecutor.hasPermission()) {
            ShellExecutor.requestPermission(this);
            Toast.makeText(this, "Grant Shizuku permission, then try again", Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            try {
                ShellExecutor.Result result = ShellExecutor.executeBlocking(
                        "am force-stop " + ShellExecutor.quote(app.packageName));
                runOnUiThread(() -> Toast.makeText(
                        this,
                        result.exitCode == 0 ? "Force-stopped " + app.label : result.stderr,
                        Toast.LENGTH_LONG).show());
            } catch (Throwable error) {
                runOnUiThread(() -> showError("Force stop failed", error));
            }
        }, "force-stop-home").start();
    }

    private void openBrowser(String input) {
        try {
            Intent intent = new Intent(this, BrowserActivity.class);
            if (input != null && !input.trim().isEmpty()) {
                intent.setData(Uri.parse(BrowserActivity.normalizeInput(input)));
            }
            startActivity(intent);
        } catch (Throwable error) {
            showError("Browser failed to open", error);
        }
    }

    private void pickWidget() {
        if (widgetHost == null || widgetManager == null) {
            Toast.makeText(this, "Widget hosting is unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            pendingWidgetId = widgetHost.allocateAppWidgetId();
            Intent pick = new Intent(AppWidgetManager.ACTION_APPWIDGET_PICK);
            pick.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
            startActivityForResult(pick, REQUEST_PICK_WIDGET);
        } catch (Throwable error) {
            deletePendingWidget();
            showError("Widget picker failed", error);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        try {
            if (requestCode == REQUEST_PICK_WIDGET) {
                if (resultCode != RESULT_OK) {
                    deletePendingWidget();
                    return;
                }
                int id = data == null
                        ? pendingWidgetId
                        : data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
                AppWidgetProviderInfo info = widgetManager == null ? null : widgetManager.getAppWidgetInfo(id);
                if (info != null && info.configure != null) {
                    Intent configure = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
                    configure.setComponent(info.configure);
                    configure.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                    pendingWidgetId = id;
                    startActivityForResult(configure, REQUEST_CONFIGURE_WIDGET);
                } else {
                    finishAddWidget(id);
                }
            } else if (requestCode == REQUEST_CONFIGURE_WIDGET) {
                if (resultCode == RESULT_OK) finishAddWidget(pendingWidgetId);
                else deletePendingWidget();
            }
        } catch (Throwable error) {
            deletePendingWidget();
            showError("Widget setup failed", error);
        }
    }

    private void finishAddWidget(int id) {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        Set<String> ids = widgetIds();
        ids.add(String.valueOf(id));
        saveWidgetIds(ids);
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        addWidgetView(id);
    }

    private void restoreWidgets() {
        if (widgetArea == null || widgetHost == null || widgetManager == null) return;
        try { widgetArea.removeAllViews(); } catch (Throwable ignored) {}
        Set<String> stored = new LinkedHashSet<>(widgetIds());
        for (String value : stored) {
            try { addWidgetView(Integer.parseInt(value)); }
            catch (Throwable error) {
                try { discardWidgetId(Integer.parseInt(value)); } catch (Throwable ignored) {}
            }
        }
    }

    private void addWidgetView(int id) {
        if (widgetHost == null || widgetManager == null || widgetArea == null) return;
        try {
            AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
            if (info == null) {
                discardWidgetId(id);
                return;
            }
            AppWidgetHostView hostView = widgetHost.createView(this, id, info);
            hostView.setAppWidget(id, info);
            hostView.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            hostView.setOnLongClickListener(v -> {
                try {
                    new AlertDialog.Builder(this)
                            .setTitle(info.label == null ? "Widget" : info.label)
                            .setMessage("Remove this widget from the home page?")
                            .setPositiveButton("Remove", (dialog, which) -> removeWidget(id))
                            .setNegativeButton("Cancel", null)
                            .show();
                } catch (Throwable error) {
                    removeWidget(id);
                }
                return true;
            });
            widgetArea.addView(hostView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 220)));
        } catch (Throwable error) {
            discardWidgetId(id);
        }
    }

    private Set<String> widgetIds() {
        Set<String> ids = new LinkedHashSet<>();
        try {
            String stored = Prefs.get(this).getString(Prefs.WIDGET_IDS, "");
            if (!stored.isEmpty()) {
                for (String id : stored.split(",")) {
                    if (!id.isBlank()) ids.add(id);
                }
            }
        } catch (Throwable error) {
            Prefs.get(this).edit().remove(Prefs.WIDGET_IDS).apply();
        }
        return ids;
    }

    private void saveWidgetIds(Set<String> ids) {
        try {
            Prefs.get(this).edit().putString(Prefs.WIDGET_IDS, String.join(",", ids)).apply();
        } catch (Throwable ignored) {
        }
    }

    private void discardWidgetId(int id) {
        Set<String> ids = widgetIds();
        ids.remove(String.valueOf(id));
        saveWidgetIds(ids);
        if (widgetHost != null) {
            try { widgetHost.deleteAppWidgetId(id); } catch (Throwable ignored) {}
        }
    }

    private void removeWidget(int id) {
        discardWidgetId(id);
        restoreWidgets();
    }

    private void deletePendingWidget() {
        if (pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && widgetHost != null) {
            try { widgetHost.deleteAppWidgetId(pendingWidgetId); } catch (Throwable ignored) {}
        }
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    }

    private void safeStart(Intent intent) {
        try { startActivity(intent); }
        catch (Throwable error) { showError("Unable to open screen", error); }
    }

    private void showError(String title, Throwable error) {
        Toast.makeText(this, title + ": " + safeMessage(error), Toast.LENGTH_LONG).show();
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank()
                ? (error == null ? "Unknown error" : error.getClass().getSimpleName())
                : message;
    }

    private void showEmergencyHome(Throwable error) {
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
            root.setBackgroundColor(Color.BLACK);

            TextView title = Ui.title(this, "Launcher Browser safe mode");
            title.setGravity(Gravity.CENTER);
            root.addView(title);

            TextView message = new TextView(this);
            message.setTextColor(Color.WHITE);
            message.setGravity(Gravity.CENTER);
            message.setText("The normal home surface failed, so the launcher stayed alive.\n\n" + safeMessage(error));
            LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            messageParams.setMargins(0, Ui.dp(this, 18), 0, Ui.dp(this, 18));
            root.addView(message, messageParams);

            Button browser = Ui.button(this, "Open browser");
            browser.setOnClickListener(v -> safeStart(new Intent(this, BrowserActivity.class)));
            root.addView(browser, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

            Button settings = Ui.button(this, "Open settings");
            settings.setOnClickListener(v -> safeStart(new Intent(this, SettingsActivity.class)));
            LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
            settingsParams.setMargins(0, Ui.dp(this, 10), 0, 0);
            root.addView(settings, settingsParams);
            setContentView(root);
        } catch (Throwable fatal) {
            TextView fallback = new TextView(this);
            fallback.setTextColor(Color.WHITE);
            fallback.setBackgroundColor(Color.BLACK);
            fallback.setGravity(Gravity.CENTER);
            fallback.setText("Launcher Browser safe mode");
            setContentView(fallback);
        }
    }

    private final class PinnedAdapter extends BaseAdapter {
        @Override public int getCount() { return pinnedApps.size(); }
        @Override public AppEntry getItem(int position) { return pinnedApps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
            LinearLayout cell;
            ImageView icon;
            TextView label;
            if (convertView instanceof LinearLayout && ((LinearLayout) convertView).getChildCount() >= 2) {
                cell = (LinearLayout) convertView;
                icon = (ImageView) cell.getChildAt(0);
                label = (TextView) cell.getChildAt(1);
            } else {
                cell = new LinearLayout(ResilientHomeActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                icon = new ImageView(ResilientHomeActivity.this);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                cell.addView(icon, new LinearLayout.LayoutParams(
                        Ui.dp(ResilientHomeActivity.this, 54),
                        Ui.dp(ResilientHomeActivity.this, 54)));
                label = new TextView(ResilientHomeActivity.this);
                label.setTextColor(Color.WHITE);
                label.setShadowLayer(6, 0, 2, Color.BLACK);
                label.setTextSize(11);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                cell.addView(label, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            AppEntry app = getItem(position);
            icon.setImageDrawable(app.icon);
            label.setText(Prefs.showLabels(ResilientHomeActivity.this) ? app.label : "");
            return cell;
        }
    }
}
