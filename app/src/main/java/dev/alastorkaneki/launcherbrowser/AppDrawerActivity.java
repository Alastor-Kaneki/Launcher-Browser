package dev.alastorkaneki.launcherbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Editable;
import android.text.TextWatcher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AppDrawerActivity extends Activity {
    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> visibleApps = new ArrayList<>();
    private AppAdapter adapter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.applyImmersive(this);
        buildUi();
        loadApps();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        root.setBackgroundColor(Prefs.amoled(this) ? Color.BLACK : Color.rgb(16, 16, 22));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.title(this, "Apps");
        top.addView(title);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search apps");
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Color.GRAY);
        search.setBackground(Ui.rounded(Ui.PANEL_LIGHT, 18, this));
        search.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1);
        searchParams.setMargins(Ui.dp(this, 12), 0, 0, 0);
        top.addView(search, searchParams);
        root.addView(top);

        GridView grid = new GridView(this);
        grid.setNumColumns(Prefs.columns(this));
        grid.setVerticalSpacing(Ui.dp(this, 10));
        grid.setHorizontalSpacing(Ui.dp(this, 6));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> launch(visibleApps.get(position)));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(visibleApps.get(position));
            return true;
        });
        root.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        setContentView(root);
    }

    private void loadApps() {
        new Thread(() -> {
            List<AppEntry> loaded = AppRepository.load(this, new IconPackManager(this));
            runOnUiThread(() -> {
                allApps.clear();
                allApps.addAll(loaded);
                filter("");
            });
        }, "load-apps").start();
    }

    private void filter(String query) {
        String needle = query.trim().toLowerCase();
        visibleApps.clear();
        for (AppEntry app : allApps) {
            if (needle.isEmpty() || app.label.toLowerCase().contains(needle) || app.packageName.toLowerCase().contains(needle)) visibleApps.add(app);
        }
        adapter.notifyDataSetChanged();
    }

    private void launch(AppEntry app) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(this, "Unable to launch " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void showActions(AppEntry app) {
        boolean pinned = pinnedApps().contains(app.component.flattenToString());
        String[] actions = {"Open", pinned ? "Unpin from home" : "Pin to home", "Force stop (Shizuku)", "Open shell with package", "App info"};
        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(actions, (dialog, which) -> {
                    switch (which) {
                        case 0 -> launch(app);
                        case 1 -> togglePin(app, pinned);
                        case 2 -> forceStop(app);
                        case 3 -> {
                            Intent shell = new Intent(this, ShellActivity.class);
                            shell.putExtra("command", "pkg=" + ShellExecutor.quote(app.packageName));
                            startActivity(shell);
                        }
                        case 4 -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.packageName)));
                    }
                }).show();
    }

    private Set<String> pinnedApps() {
        return new LinkedHashSet<>(Prefs.get(this).getStringSet(Prefs.PINNED_APPS, new LinkedHashSet<>()));
    }

    private void togglePin(AppEntry app, boolean currentlyPinned) {
        Set<String> pinned = pinnedApps();
        if (currentlyPinned) pinned.remove(app.component.flattenToString()); else pinned.add(app.component.flattenToString());
        Prefs.get(this).edit().putStringSet(Prefs.PINNED_APPS, pinned).apply();
        Toast.makeText(this, currentlyPinned ? "Unpinned" : "Pinned to home", Toast.LENGTH_SHORT).show();
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
        }, "force-stop").start();
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleApps.size(); }
        @Override public AppEntry getItem(int position) { return visibleApps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout cell;
            ImageView icon;
            TextView label;
            if (convertView instanceof LinearLayout) {
                cell = (LinearLayout) convertView;
                icon = (ImageView) cell.getChildAt(0);
                label = (TextView) cell.getChildAt(1);
            } else {
                cell = new LinearLayout(AppDrawerActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(Ui.dp(AppDrawerActivity.this, 4), Ui.dp(AppDrawerActivity.this, 8), Ui.dp(AppDrawerActivity.this, 4), Ui.dp(AppDrawerActivity.this, 8));
                icon = new ImageView(AppDrawerActivity.this);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                cell.addView(icon, new LinearLayout.LayoutParams(Ui.dp(AppDrawerActivity.this, 54), Ui.dp(AppDrawerActivity.this, 54)));
                label = new TextView(AppDrawerActivity.this);
                label.setTextColor(Color.WHITE);
                label.setTextSize(11);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Ui.dp(AppDrawerActivity.this, 4);
                cell.addView(label, lp);
            }
            AppEntry app = getItem(position);
            icon.setImageDrawable(app.icon);
            label.setText(Prefs.showLabels(AppDrawerActivity.this) ? app.label : "");
            return cell;
        }
    }
}
