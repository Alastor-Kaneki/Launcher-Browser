package dev.alastorkaneki.launcherbrowser;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String FILE = "launcher_browser";
    static final String TRANSPARENT_HOME = "transparent_home";
    static final String AMOLED = "amoled";
    static final String IMMERSIVE = "immersive";
    static final String SHOW_LABELS = "show_labels";
    static final String GRID_COLUMNS = "grid_columns";
    static final String ICON_PACK = "icon_pack";
    static final String PINNED_APPS = "pinned_apps";
    static final String WIDGET_IDS = "widget_ids";
    static final String BROWSER_URLS = "browser_urls";
    static final String BROWSER_SELECTED = "browser_selected";

    private Prefs() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean transparentHome(Context context) {
        return get(context).getBoolean(TRANSPARENT_HOME, true);
    }

    static boolean amoled(Context context) {
        return get(context).getBoolean(AMOLED, true);
    }

    static boolean immersive(Context context) {
        return get(context).getBoolean(IMMERSIVE, true);
    }

    static boolean showLabels(Context context) {
        return get(context).getBoolean(SHOW_LABELS, true);
    }

    static int columns(Context context) {
        return Math.max(3, Math.min(8, get(context).getInt(GRID_COLUMNS, 4)));
    }
}
