package dev.alastorkaneki.launcherbrowser;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

final class AppEntry {
    final String label;
    final String packageName;
    final ComponentName component;
    Drawable icon;

    AppEntry(String label, String packageName, ComponentName component, Drawable icon) {
        this.label = label;
        this.packageName = packageName;
        this.component = component;
        this.icon = icon;
    }
}
