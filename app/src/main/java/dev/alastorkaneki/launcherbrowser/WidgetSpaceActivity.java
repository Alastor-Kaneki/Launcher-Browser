package dev.alastorkaneki.launcherbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Widgets intentionally live in their own process. A broken third-party
 * RemoteViews implementation can crash this activity without taking down the
 * launcher home or browser processes.
 */
public final class WidgetSpaceActivity extends Activity {
    private static final int HOST_ID = 1187;
    private static final int REQUEST_PICK_WIDGET = 6101;
    private static final int REQUEST_CONFIGURE_WIDGET = 6102;

    private AppWidgetHost widgetHost;
    private AppWidgetManager widgetManager;
    private LinearLayout widgetArea;
    private TextView status;
    private int pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.applyImmersive(this);
        try {
            widgetManager = AppWidgetManager.getInstance(this);
            widgetHost = new AppWidgetHost(this, HOST_ID);
            buildUi();
            quarantinePreviousFailure();
            restoreWidgetsSequentially();
        } catch (Throwable error) {
            showSafeWidgetScreen(error);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (widgetHost != null) {
            try { widgetHost.startListening(); }
            catch (Throwable error) { showStatus("Widget updates unavailable: " + safeMessage(error)); }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        Ui.applyImmersive(this);
    }

    @Override protected void onStop() {
        if (widgetHost != null) {
            try { widgetHost.stopListening(); } catch (Throwable ignored) {}
        }
        super.onStop();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16));
        root.setBackgroundColor(Prefs.amoled(this) ? Color.BLACK : Color.rgb(15, 15, 21));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.title(this, "Widget Space");
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button add = Ui.button(this, "+ Add");
        add.setOnClickListener(v -> pickWidget());
        header.addView(add, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 46)));
        root.addView(header);

        TextView info = text("Widgets are isolated from the launcher home. If a third-party widget crashes, Launcher Browser stays alive and the offending widget is quarantined on the next launch.");
        info.setTextColor(Color.LTGRAY);
        root.addView(info);

        status = text("");
        status.setTextColor(Ui.PURPLE);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        widgetArea = new LinearLayout(this);
        widgetArea.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(widgetArea, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        Button reset = Ui.button(this, "Reset widgets");
        reset.setOnClickListener(v -> confirmReset());
        actions.addView(reset, weightedButton());
        Button home = Ui.button(this, "Home");
        home.setOnClickListener(v -> finish());
        actions.addView(home, weightedButton());
        root.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)));

        setContentView(root);
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4));
        return params;
    }

    private void quarantinePreviousFailure() {
        int failedId = Prefs.get(this).getInt(
                Prefs.WIDGET_PENDING_LOAD,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (failedId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            discardWidgetId(failedId);
            Prefs.get(this).edit().remove(Prefs.WIDGET_PENDING_LOAD).apply();
            showStatus("Removed widget " + failedId + " because it did not finish loading last time.");
        }
    }

    private void restoreWidgetsSequentially() {
        if (widgetArea == null) return;
        widgetArea.removeAllViews();
        List<Integer> ids = new ArrayList<>();
        for (String value : widgetIds()) {
            try { ids.add(Integer.parseInt(value)); }
            catch (NumberFormatException ignored) {}
        }
        if (ids.isEmpty()) {
            TextView empty = text("No widgets added.");
            empty.setTextColor(Color.GRAY);
            widgetArea.addView(empty);
            showStatus("Widget Space is ready.");
            return;
        }
        showStatus("Loading " + ids.size() + " widget" + (ids.size() == 1 ? "" : "s") + " safely…");
        restoreNext(ids, 0);
    }

    private void restoreNext(List<Integer> ids, int index) {
        if (index >= ids.size()) {
            Prefs.get(this).edit().remove(Prefs.WIDGET_PENDING_LOAD).apply();
            showStatus("Widgets loaded.");
            return;
        }
        int id = ids.get(index);
        Prefs.get(this).edit().putInt(Prefs.WIDGET_PENDING_LOAD, id).commit();
        boolean added = addWidgetView(id);
        if (!added) {
            Prefs.get(this).edit().remove(Prefs.WIDGET_PENDING_LOAD).commit();
            widgetArea.post(() -> restoreNext(ids, index + 1));
            return;
        }
        widgetArea.postDelayed(() -> {
            if (Prefs.get(this).getInt(Prefs.WIDGET_PENDING_LOAD, -1) == id) {
                Prefs.get(this).edit().remove(Prefs.WIDGET_PENDING_LOAD).commit();
            }
            restoreNext(ids, index + 1);
        }, 1200);
    }

    private boolean addWidgetView(int id) {
        if (widgetHost == null || widgetManager == null || widgetArea == null) return false;
        try {
            AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
            if (info == null) {
                discardWidgetId(id);
                return false;
            }
            AppWidgetHostView hostView = widgetHost.createView(this, id, info);
            hostView.setAppWidget(id, info);
            hostView.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            hostView.setOnLongClickListener(v -> {
                showRemoveDialog(id, info.label);
                return true;
            });
            int height = Math.max(160, Math.min(480, info.minHeight > 0 ? info.minHeight + 48 : 220));
            widgetArea.addView(hostView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, height)));
            return true;
        } catch (Throwable error) {
            discardWidgetId(id);
            showStatus("Skipped broken widget " + id + ": " + safeMessage(error));
            return false;
        }
    }

    private void showRemoveDialog(int id, String label) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle(label == null || label.isBlank() ? "Widget" : label)
                    .setMessage("Remove this widget from Widget Space?")
                    .setPositiveButton("Remove", (dialog, which) -> {
                        discardWidgetId(id);
                        restoreWidgetsSequentially();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Throwable error) {
            discardWidgetId(id);
            restoreWidgetsSequentially();
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
            Toast.makeText(this, "Widget picker failed: " + safeMessage(error), Toast.LENGTH_LONG).show();
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
                if (resultCode == RESULT_OK) finishAddWidget(pendingWidgetId);
                else deletePendingWidget();
            }
        } catch (Throwable error) {
            deletePendingWidget();
            Toast.makeText(this, "Widget setup failed: " + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private void finishAddWidget(int id) {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        Set<String> ids = widgetIds();
        ids.add(String.valueOf(id));
        saveWidgetIds(ids);
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        restoreWidgetsSequentially();
    }

    private Set<String> widgetIds() {
        Set<String> ids = new LinkedHashSet<>();
        String stored = Prefs.get(this).getString(Prefs.WIDGET_IDS, "");
        if (stored != null && !stored.isBlank()) {
            for (String id : stored.split(",")) {
                if (!id.isBlank()) ids.add(id.trim());
            }
        }
        return ids;
    }

    private void saveWidgetIds(Set<String> ids) {
        Prefs.get(this).edit().putString(Prefs.WIDGET_IDS, String.join(",", ids)).commit();
    }

    private void discardWidgetId(int id) {
        Set<String> ids = widgetIds();
        ids.remove(String.valueOf(id));
        saveWidgetIds(ids);
        if (widgetHost != null) {
            try { widgetHost.deleteAppWidgetId(id); } catch (Throwable ignored) {}
        }
    }

    private void deletePendingWidget() {
        if (pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && widgetHost != null) {
            try { widgetHost.deleteAppWidgetId(pendingWidgetId); } catch (Throwable ignored) {}
        }
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset Widget Space?")
                .setMessage("All hosted widgets will be removed. This does not uninstall their apps.")
                .setPositiveButton("Reset", (dialog, which) -> resetWidgets())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void resetWidgets() {
        for (String value : new LinkedHashSet<>(widgetIds())) {
            try { discardWidgetId(Integer.parseInt(value)); }
            catch (NumberFormatException ignored) {}
        }
        Prefs.get(this).edit()
                .remove(Prefs.WIDGET_IDS)
                .remove(Prefs.WIDGET_PENDING_LOAD)
                .commit();
        if (widgetHost != null) {
            try { widgetHost.deleteHost(); } catch (Throwable ignored) {}
            widgetHost = new AppWidgetHost(this, HOST_ID);
            try { widgetHost.startListening(); } catch (Throwable ignored) {}
        }
        restoreWidgetsSequentially();
    }

    private void showStatus(String value) {
        if (status != null) status.setText(value == null ? "" : value);
    }

    private void showSafeWidgetScreen(Throwable error) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22));
        root.setBackgroundColor(Color.BLACK);
        TextView title = Ui.title(this, "Widget Space safe mode");
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView message = text("Widget hosting failed without affecting the launcher.\n\n" + safeMessage(error));
        message.setGravity(Gravity.CENTER);
        root.addView(message);
        Button reset = Ui.button(this, "Clear widget state");
        reset.setOnClickListener(v -> {
            Prefs.get(this).edit().remove(Prefs.WIDGET_IDS).remove(Prefs.WIDGET_PENDING_LOAD).commit();
            recreate();
        });
        root.addView(reset, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)));
        setContentView(root);
    }

    private TextView text(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        view.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        return view;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "Unknown error";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
