package dev.alastorkaneki.launcherbrowser;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class IconPackManager {
    static final class Pack {
        final String label;
        final String packageName;
        Pack(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
        @Override public String toString() { return label; }
    }

    private final Context context;
    private final String packageName;
    private final Map<String, String> mappings = new LinkedHashMap<>();
    private Resources resources;

    IconPackManager(Context context) {
        this.context = context.getApplicationContext();
        this.packageName = Prefs.get(context).getString(Prefs.ICON_PACK, "");
        if (!packageName.isEmpty()) load();
    }

    static List<Pack> discover(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> packageNames = new LinkedHashSet<>();
        String[] actions = {
                "org.adw.launcher.THEMES",
                "com.gau.go.launcherex.theme",
                "com.novalauncher.THEME"
        };
        for (String action : actions) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(action), PackageManager.MATCH_ALL)) {
                if (info.activityInfo != null) packageNames.add(info.activityInfo.packageName);
            }
        }
        List<Pack> packs = new ArrayList<>();
        packs.add(new Pack("System icons", ""));
        for (String pkg : packageNames) {
            try {
                CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                packs.add(new Pack(String.valueOf(label), pkg));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return packs;
    }

    Drawable iconFor(ComponentName component) {
        if (resources == null) return null;
        String drawable = mappings.get(normalize(component.flattenToString()));
        if (drawable == null) drawable = mappings.get(normalize(component.flattenToShortString()));
        if (drawable == null) return null;
        int id = resources.getIdentifier(drawable, "drawable", packageName);
        if (id == 0) id = resources.getIdentifier(drawable, "mipmap", packageName);
        if (id == 0) return null;
        try {
            return resources.getDrawable(id, context.getTheme());
        } catch (Resources.NotFoundException ignored) {
            return null;
        }
    }

    private void load() {
        try {
            resources = context.getPackageManager().getResourcesForApplication(packageName);
            InputStream asset = null;
            try {
                asset = resources.getAssets().open("appfilter.xml");
                parse(Xml.newPullParser(), asset);
                return;
            } catch (Exception ignored) {
                if (asset != null) try { asset.close(); } catch (Exception ignoredClose) {}
            }
            int id = resources.getIdentifier("appfilter", "xml", packageName);
            if (id != 0) {
                XmlResourceParser parser = resources.getXml(id);
                parse(parser, null);
                parser.close();
            }
        } catch (Exception ignored) {
            resources = null;
            mappings.clear();
        }
    }

    private void parse(XmlPullParser parser, InputStream stream) throws Exception {
        if (stream != null) parser.setInput(stream, "utf-8");
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event != XmlPullParser.START_TAG || !"item".equals(parser.getName())) continue;
            String component = parser.getAttributeValue(null, "component");
            String drawable = parser.getAttributeValue(null, "drawable");
            if (component != null && drawable != null) mappings.put(normalize(component), drawable);
        }
        if (stream != null) stream.close();
    }

    private static String normalize(String value) {
        String result = value.trim();
        if (result.startsWith("ComponentInfo{")) result = result.substring(14, result.length() - 1);
        return result.replace("/.", "/").toLowerCase();
    }
}
