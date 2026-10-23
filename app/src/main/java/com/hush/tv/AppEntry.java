package com.hush.tv;

import android.content.ComponentName;
import android.graphics.Bitmap;

/** A single launchable application shown on the desktop. */
final class AppEntry {
    final String label;
    final ComponentName component;
    final Bitmap icon;

    AppEntry(String label, ComponentName component, Bitmap icon) {
        this.label = label;
        this.component = component;
        this.icon = icon;
    }

    String key() {
        return component.flattenToString();
    }
}
