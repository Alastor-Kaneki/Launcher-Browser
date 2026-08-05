package dev.alastorkaneki.launcherbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public final class SettingsActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.applyImmersive(this);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        Ui.applyImmersive(this);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 30));
        root.setBackgroundColor(Prefs.amoled(this) ? Color.BLACK : Color.rgb(16, 16, 22));
        scroll.addView(root);

        root.addView(Ui.title(this, "Launcher Browser settings"));
        addSubtitle(root, "Appearance");
        addToggle(root, "Transparent wallpaper homepage", Prefs.TRANSPARENT_HOME, true);
        addToggle(root, "True-black AMOLED panels", Prefs.AMOLED, true);
        addToggle(root, "Immersive mode", Prefs.IMMERSIVE, true);
        addToggle(root, "Show app labels", Prefs.SHOW_LABELS, true);

        TextView gridLabel = label("Home and drawer columns: " + Prefs.columns(this));
        root.addView(gridLabel);
        SeekBar grid = new SeekBar(this);
        grid.setMax(5);
        grid.setProgress(Prefs.columns(this) - 3);
        grid.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int columns = progress + 3;
                gridLabel.setText("Home and drawer columns: " + columns);
                Prefs.get(SettingsActivity.this).edit().putInt(Prefs.GRID_COLUMNS, columns).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(grid);

        addSubtitle(root, "Icon pack");
        List<IconPackManager.Pack> packs = IconPackManager.discover(this);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<IconPackManager.Pack> packAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                packs);
        spinner.setAdapter(packAdapter);
        String selectedPack = Prefs.get(this).getString(Prefs.ICON_PACK, "");
        for (int i = 0; i < packs.size(); i++) {
            if (packs.get(i).packageName.equals(selectedPack)) spinner.setSelection(i);
        }
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                Prefs.get(SettingsActivity.this).edit().putString(
                        Prefs.ICON_PACK,
                        packs.get(position).packageName).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        root.addView(spinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)));

        addSubtitle(root, "Browser power tools");
        addAction(root, "Open Opera GX Store", () -> {
            Intent browser = new Intent(this, BrowserActivity.class);
            browser.setData(Uri.parse("https://store.gx.me/mods/"));
            startActivity(browser);
        });
        addAction(root, "Lite Extensions", () -> startActivity(new Intent(this, ExtensionsActivity.class)));
        addAction(root, "Userscripts and userstyles", () -> startActivity(new Intent(this, UserscriptManagerActivity.class)));
        addAction(root, "Widget Space (isolated process)", () -> startActivity(new Intent(this, WidgetSpaceActivity.class)));

        addSubtitle(root, "Default roles");
        addAction(root, "Set as default home app", () -> requestRole(RoleManager.ROLE_HOME));
        addAction(root, "Set as default browser", () -> requestRole(RoleManager.ROLE_BROWSER));
        addAction(root, "Allow APK installs from this browser", () -> {
            if (Build.VERSION.SDK_INT >= 26) {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            }
        });

        addSubtitle(root, "Shizuku");
        TextView status = label(ShellExecutor.identity());
        status.setTextColor(Ui.PURPLE);
        root.addView(status);
        addAction(root, "Request Shizuku permission", () -> {
            ShellExecutor.requestPermission(this);
            status.postDelayed(() -> status.setText(ShellExecutor.identity()), 800);
        });
        addAction(root, "Open ADB shell", () -> startActivity(new Intent(this, ShellActivity.class)));

        addSubtitle(root, "Browser data");
        addAction(root, "Clear cookies, cache and site storage", () -> new AlertDialog.Builder(this)
                .setTitle("Clear browser data?")
                .setMessage("This signs you out of websites and clears cached pages. Downloads, userscripts and extension settings are not deleted.")
                .setPositiveButton("Clear", (dialog, which) -> clearBrowserData())
                .setNegativeButton("Cancel", null)
                .show());

        addSubtitle(root, "About");
        TextView about = label(
                "Launcher Browser 0.1.4-alpha\n"
                        + "Native Android launcher + WebView browser + isolated widget host + icon packs + Shizuku shell + userscripts + Lite Extensions + native Opera GX mod downloads.\n\n"
                        + "On an Opera GX Store mod page, tap the GX button to save the original .crx or extract the contained mod as a .zip. Downloads are never installed or activated automatically.");
        about.setTextColor(Color.LTGRAY);
        root.addView(about);
        setContentView(scroll);
    }

    private void addSubtitle(LinearLayout root, String text) {
        TextView view = label(text);
        view.setTextColor(Ui.PURPLE);
        view.setTextSize(17);
        view.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 6));
        root.addView(view);
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        view.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        return view;
    }

    private void addToggle(LinearLayout root, String text, String key, boolean defaultValue) {
        CheckBox check = new CheckBox(this);
        check.setText(text);
        check.setTextColor(Color.WHITE);
        check.setTextSize(15);
        check.setChecked(Prefs.get(this).getBoolean(key, defaultValue));
        check.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        check.setOnCheckedChangeListener((button, checked) -> Prefs.get(this).edit().putBoolean(key, checked).apply());
        root.addView(check);
    }

    private void addAction(LinearLayout root, String text, Runnable action) {
        Button button = Ui.button(this, text);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setOnClickListener(v -> {
            try { action.run(); }
            catch (Throwable error) {
                Toast.makeText(this, "Action failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 50));
        params.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        root.addView(button, params);
    }

    private void requestRole(String role) {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Choose Launcher Browser in Android's default apps settings", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            return;
        }
        RoleManager roles = getSystemService(RoleManager.class);
        if (roles != null && roles.isRoleAvailable(role)) {
            if (roles.isRoleHeld(role)) {
                Toast.makeText(this, "Already selected", Toast.LENGTH_SHORT).show();
            } else {
                startActivityForResult(
                        roles.createRequestRoleIntent(role),
                        role.equals(RoleManager.ROLE_HOME) ? 3001 : 3002);
            }
        }
    }

    private void clearBrowserData() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
        WebView temp = new WebView(this);
        temp.clearCache(true);
        temp.destroy();
        Prefs.get(this).edit()
                .remove(Prefs.BROWSER_URLS)
                .remove(Prefs.BROWSER_SELECTED)
                .apply();
        Toast.makeText(this, "Browser data cleared", Toast.LENGTH_SHORT).show();
    }
}
