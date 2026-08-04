package dev.alastorkaneki.launcherbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class HomeActivity extends Activity {
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
    private GestureDetector gestures;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Prefs.transparentHome(this)) Ui.showWallpaper(this);
        Ui.applyImmersive(this);
        widgetManager = AppWidgetManager.getInstance(this);
        widgetHost = new AppWidgetHost(this, HOST_ID);
        buildUi();
        loadPinnedApps();
        restoreWidgets();
    }

    @Override protected void onResume() {
        super.onResume();
        Ui.applyImmersive(this);
        try { widgetHost.startListening(); } catch (Exception ignored) {}
        loadPinnedApps();
    }

    @Override protected void onPause() {
        widgetHost.stopListening();
        super.onPause();
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
        root.addView(page, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

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
        search.setOnLongClickListener(v -> {
            startActivity(new Intent(this, BrowserActivity.class));
            return true;
        });
        page.addView(search, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        pinnedGrid = new GridView(this);
        pinnedGrid.setNumColumns(Prefs.columns(this));
        pinnedGrid.setGravity(Gravity.CENTER);
        pinnedGrid.setHorizontalSpacing(Ui.dp(this, 6));
        pinnedGrid.setVerticalSpacing(Ui.dp(this, 8));
        pinnedGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        pinnedAdapter = new PinnedAdapter();
        pinnedGrid.setAdapter(pinnedAdapter);
        pinnedGrid.setOnItemClickListener((parent, view, position, id) -> launch(pinnedApps.get(position)));
        pinnedGrid.setOnItemLongClickListener((parent, view, position, id) -> {
            showPinnedActions(pinnedApps.get(position));
            return true;
        });
        content.addView(pinnedGrid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 210)));

        LinearLayout widgetHeader = new LinearLayout(this);
        widgetHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView widgetsTitle = Ui.title(this, "Widgets");
        widgetsTitle.setTextSize(17);
        widgetHeader.addView(widgetsTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button addWidget = Ui.button(this, "+ Add");
        addWidget.setOnClickListener(v -> pickWidget());
        widgetHeader.addView(addWidget, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 42)));
        content.addView(widgetHeader);

        widgetArea = new LinearLayout(this);
        widgetArea.setOrientation(LinearLayout.VERTICAL);
        content.addView(widgetArea, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(Ui.dp(this, 6), Ui.dp(this, 7), Ui.dp(this, 6), Ui.dp(this, 7));
        dock.setBackground(Ui.rounded(Ui.PANEL, 24, this));
        addDockButton(dock, "Browser", () -> startActivity(new Intent(this, BrowserActivity.class)));
        addDockButton(dock, "Apps", () -> startActivity(new Intent(this, AppDrawerActivity.class)));
        addDockButton(dock, "Shell", () -> startActivity(new Intent(this, ShellActivity.class)));
        addDockButton(dock, "Widgets", this::pickWidget);
        addDockButton(dock, "Settings", () -> startActivity(new Intent(this, SettingsActivity.class)));
        page.addView(dock, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 62)));

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 != null && e2 != null && e1.getY() - e2.getY() > Ui.dp(HomeActivity.this, 80)) {
                    startActivity(new Intent(HomeActivity.this, AppDrawerActivity.class));
                    return true;
                }
                return false;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                startActivity(new Intent(HomeActivity.this, BrowserActivity.class));
                return true;
            }
        });
        root.setOnTouchListener((v, event) -> gestures.onTouchEvent(event));
        setContentView(root);
    }

    private void addDockButton(LinearLayout dock, String text, Runnable action) {
        Button button = Ui.button(this, text);
        button.setTextSize(11);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        dock.addView(button, params);
    }

    private void loadPinnedApps() {
        new Thread(() -> {
            List<AppEntry> all = AppRepository.load(this, new IconPackManager(this));
            Set<String> pinned = new LinkedHashSet<>(Prefs.get(this).getStringSet(Prefs.PINNED_APPS, new LinkedHashSet<>()));
            if (pinned.isEmpty()) {
                for (AppEntry app : all) {
                    if (!app.packageName.equals(getPackageName())) pinned.add(app.component.flattenToString());
                    if (pinned.size() >= 8) break;
                }
                Prefs.get(this).edit().putStringSet(Prefs.PINNED_APPS, pinned).apply();
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
            runOnUiThread(() -> {
                pinnedApps.clear();
                pinnedApps.addAll(selected);
                pinnedGrid.getLayoutParams().height = Ui.dp(this, Math.max(110, ((pinnedApps.size() + Prefs.columns(this) - 1) / Prefs.columns(this)) * 105));
                pinnedGrid.requestLayout();
                pinnedAdapter.notifyDataSetChanged();
            });
        }, "load-pinned").start();
    }

    private void launch(AppEntry app) {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Exception error) {
            Toast.makeText(this, "Unable to launch " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void showPinnedActions(AppEntry app) {
        String[] actions = {"Open", "Unpin", "Force stop (Shizuku)", "App info"};
        new AlertDialog.Builder(this).setTitle(app.label).setItems(actions, (dialog, which) -> {
            if (which == 0) launch(app);
            if (which == 1) {
                Set<String> pinned = new LinkedHashSet<>(Prefs.get(this).getStringSet(Prefs.PINNED_APPS, new LinkedHashSet<>()));
                pinned.remove(app.component.flattenToString());
                Prefs.get(this).edit().putStringSet(Prefs.PINNED_APPS, pinned).apply();
                loadPinnedApps();
            }
            if (which == 2) forceStop(app);
            if (which == 3) startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.packageName)));
        }).show();
    }

    private void forceStop(AppEntry app) {
        if (!ShellExecutor.hasPermission()) {
            ShellExecutor.requestPermission(this);
            Toast.makeText(this, "Grant Shizuku permission, then try again", Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            try {
                ShellExecutor.Result result = ShellExecutor.executeBlocking("am force-stop " + ShellExecutor.quote(app.packageName));
                runOnUiThread(() -> Toast.makeText(this, result.exitCode == 0 ? "Force-stopped " + app.label : result.stderr, Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this, error.toString(), Toast.LENGTH_LONG).show());
            }
        }, "force-stop-home").start();
    }

    private void openBrowser(String input) {
        Intent intent = new Intent(this, BrowserActivity.class);
        if (input != null && !input.trim().isEmpty()) intent.setData(Uri.parse(BrowserActivity.normalizeInput(input)));
        startActivity(intent);
    }

    private void pickWidget() {
        pendingWidgetId = widgetHost.allocateAppWidgetId();
        Intent pick = new Intent(AppWidgetManager.ACTION_APPWIDGET_PICK);
        pick.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
        startActivityForResult(pick, REQUEST_PICK_WIDGET);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_WIDGET) {
            if (resultCode != RESULT_OK) {
                deletePendingWidget();
                return;
            }
            int id = data == null ? pendingWidgetId : data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
            AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
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
            if (resultCode == RESULT_OK) finishAddWidget(pendingWidgetId); else deletePendingWidget();
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
        widgetArea.removeAllViews();
        for (String id : widgetIds()) {
            try { addWidgetView(Integer.parseInt(id)); } catch (NumberFormatException ignored) {}
        }
    }

    private void addWidgetView(int id) {
        AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
        if (info == null) {
            removeWidget(id);
            return;
        }
        AppWidgetHostView hostView = widgetHost.createView(this, id, info);
        hostView.setAppWidget(id, info);
        hostView.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        hostView.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(info.label == null ? "Widget" : info.label)
                    .setMessage("Remove this widget from the home page?")
                    .setPositiveButton("Remove", (dialog, which) -> removeWidget(id))
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });
        widgetArea.addView(hostView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 220)));
    }

    private Set<String> widgetIds() {
        String stored = Prefs.get(this).getString(Prefs.WIDGET_IDS, "");
        Set<String> ids = new LinkedHashSet<>();
        if (!stored.isEmpty()) for (String id : stored.split(",")) if (!id.isBlank()) ids.add(id);
        return ids;
    }

    private void saveWidgetIds(Set<String> ids) {
        Prefs.get(this).edit().putString(Prefs.WIDGET_IDS, String.join(",", ids)).apply();
    }

    private void removeWidget(int id) {
        Set<String> ids = widgetIds();
        ids.remove(String.valueOf(id));
        saveWidgetIds(ids);
        widgetHost.deleteAppWidgetId(id);
        restoreWidgets();
    }

    private void deletePendingWidget() {
        if (pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) widgetHost.deleteAppWidgetId(pendingWidgetId);
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    }

    private final class PinnedAdapter extends BaseAdapter {
        @Override public int getCount() { return pinnedApps.size(); }
        @Override public AppEntry getItem(int position) { return pinnedApps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
            LinearLayout cell;
            ImageView icon;
            TextView label;
            if (convertView instanceof LinearLayout) {
                cell = (LinearLayout) convertView;
                icon = (ImageView) cell.getChildAt(0);
                label = (TextView) cell.getChildAt(1);
            } else {
                cell = new LinearLayout(HomeActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                icon = new ImageView(HomeActivity.this);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                cell.addView(icon, new LinearLayout.LayoutParams(Ui.dp(HomeActivity.this, 54), Ui.dp(HomeActivity.this, 54)));
                label = new TextView(HomeActivity.this);
                label.setTextColor(Color.WHITE);
                label.setShadowLayer(6, 0, 2, Color.BLACK);
                label.setTextSize(11);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                cell.addView(label, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            AppEntry app = getItem(position);
            icon.setImageDrawable(app.icon);
            label.setText(Prefs.showLabels(HomeActivity.this) ? app.label : "");
            return cell;
        }
    }
}
