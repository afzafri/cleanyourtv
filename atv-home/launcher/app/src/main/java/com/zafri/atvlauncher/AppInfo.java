package com.zafri.atvlauncher;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Drawable;

/** One launchable app: its banner (or icon when it has no banner) and the colour sampled for its glow. */
final class AppInfo {
    final String pkg;
    final String label;
    final ComponentName component;
    final Drawable banner;
    final Drawable icon;
    Integer glow;

    AppInfo(String pkg, String label, ComponentName component, Drawable banner, Drawable icon) {
        this.pkg = pkg;
        this.label = label;
        this.component = component;
        this.banner = banner;
        this.icon = icon;
    }

    Intent launchIntent() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }
}
