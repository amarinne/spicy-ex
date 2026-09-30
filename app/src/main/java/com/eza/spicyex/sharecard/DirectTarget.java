package com.eza.spicyex.sharecard;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

/** One installed app's share activity, with its icon and name. */
final class DirectTarget {
    final ComponentName component;
    final Drawable icon;
    final String label;

    DirectTarget(ComponentName component, Drawable icon, String label) {
        this.component = component;
        this.icon = icon;
        this.label = label;
    }
}
