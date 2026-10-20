package com.zafri.atvlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists TV apps (LEANBACK_LAUNCHER), plus sideloaded phone-style apps that only declare LAUNCHER,
 * sorted by name. System apps without a TV entry (Clock, Files...) are left out.
 */
final class AppLoader {
    private AppLoader() {}

    static List<AppInfo> load(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, AppInfo> byPkg = new LinkedHashMap<>();
        addAll(pm, byPkg, Intent.CATEGORY_LEANBACK_LAUNCHER, ctx.getPackageName(), true);
        addAll(pm, byPkg, Intent.CATEGORY_LAUNCHER, ctx.getPackageName(), false);
        List<AppInfo> apps = new ArrayList<>(byPkg.values());
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    private static void addAll(PackageManager pm, Map<String, AppInfo> out, String category, String self,
                               boolean includeSystem) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(self) || out.containsKey(ai.packageName)) continue;
            if (!includeSystem && (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            Drawable banner = ai.loadBanner(pm);
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            out.put(ai.packageName, new AppInfo(ai.packageName, String.valueOf(ri.loadLabel(pm)),
                    new ComponentName(ai.packageName, ai.name), banner, icon));
        }
    }
}
