package dev.alastorkaneki.launcherbrowser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class ExtensionsActivity extends Activity {
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
        root.setBackgroundColor(Prefs.amoled(this) ? Color.BLACK : Color.rgb(15, 15, 21));
        scroll.addView(root);

        root.addView(Ui.title(this, "Lite Extensions"));

        TextView info = text("Launcher Browser supports a safe WebView-native extension layer. These are not Chrome CRX extensions; they are browser modules, userscripts and CSS userstyles that run only inside this app.");
        info.setTextColor(Color.LTGRAY);
        root.addView(info);

        addToggle(root,
                "Basic ad and tracker blocker",
                "Blocks requests to a built-in list of common advertising and tracking hosts.",
                Prefs.EXT_CONTENT_BLOCKER,
                true);
        addToggle(root,
                "Dark pages",
                "Applies a reversible dark-page filter after each page loads.",
                Prefs.EXT_DARK_PAGES,
                false);
        addToggle(root,
                "Hide common cookie banners",
                "Uses CSS heuristics. Some sites may need this disabled.",
                Prefs.EXT_HIDE_COOKIE_BANNERS,
                false);

        Button userscripts = Ui.button(this, "Manage userscripts and userstyles");
        userscripts.setGravity(Gravity.CENTER);
        userscripts.setOnClickListener(v -> startActivity(new Intent(this, UserscriptManagerActivity.class)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 54));
        params.setMargins(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        root.addView(userscripts, params);

        TextView limits = text("Current userscript support: @name, @match, @include, @exclude, JavaScript, CSS, GM_addStyle and GM_log. Scripts run after page load. Browser-extension APIs such as chrome.tabs are not emulated yet.");
        limits.setTextColor(Color.GRAY);
        root.addView(limits);

        setContentView(scroll);
    }

    private void addToggle(LinearLayout root, String title, String detail, String key, boolean defaultValue) {
        CheckBox check = new CheckBox(this);
        check.setText(title);
        check.setTextColor(Color.WHITE);
        check.setTextSize(15);
        check.setChecked(Prefs.get(this).getBoolean(key, defaultValue));
        check.setOnCheckedChangeListener((button, checked) -> Prefs.get(this).edit().putBoolean(key, checked).apply());
        root.addView(check);

        TextView description = text(detail);
        description.setTextColor(Color.LTGRAY);
        description.setPadding(Ui.dp(this, 38), 0, Ui.dp(this, 4), Ui.dp(this, 12));
        root.addView(description);
    }

    private TextView text(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        view.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        return view;
    }
}
