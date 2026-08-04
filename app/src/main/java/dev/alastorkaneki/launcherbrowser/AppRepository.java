package dev.alastorkaneki.launcherbrowser;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class AppRepository {
    private AppRepository() {}

    static List<AppEntry> load(Context context, IconPackManager iconPacks) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);
        List<AppEntry> apps = new ArrayList<>();
        for (ResolveInfo info : infos) {
            if (info.activityInfo == null) continue;
            ComponentName component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
            String label = String.valueOf(info.loadLabel(pm));
            AppEntry entry = new AppEntry(label, info.activityInfo.packageName, component, info.loadIcon(pm));
            if (iconPacks != null) {
                android.graphics.drawable.Drawable themed = iconPacks.iconFor(component);
                if (themed != null) entry.icon = themed;
            }
            apps.add(entry);
        }
        Collections.sort(apps, Comparator.comparing(a -> a.label.toLowerCase()));
        return apps;
    }
}
