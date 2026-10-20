package com.zafri.atvlauncher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen. Focus is not handled by Android's focus search: every D-pad key is
 * routed through {@link #dispatchKeyEvent} into a small zone/index model, so Up and Down always move
 * exactly one row and nothing can fall through to an unexpected view.
 */
public class MainActivity extends Activity {
    private static final int TOP = 0, DOCK = 1, GRID = 2, MENU = 3, PICK = 4; // PICK: choosing a dock slot to pin into
    private static final long LONG_PRESS_MS = 600;
    private static final int PILL_INPUTS = 0, PILL_WIFI = 1, PILL_SETTINGS = 2, PILL_POWER = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private float dp;
    private int screenW, screenH;
    private Typeface thin, light, regular;

    // Geometry, in px.
    private int gridLeft, gridGap;
    private int side, bandMargin, headerH, bandH, gridTop, gridRowH, gridTileH, dockTileW, dockTileH, rowAnchor, glowSize;

    private FrameLayout root, page, header, dockBand;
    private ImageView wallpaper, wallpaperIncoming;
    private View wallDim, wallScrim, glow;
    private final GradientDrawable glowDrawable = new GradientDrawable();
    private int glowColor = 0xFF606060;
    private ValueAnimator glowAnim;
    private TextView greeting, clock, date, appName, wifiText;
    private final View[] pills = new View[4];
    private LinearLayout dockRow;
    private RecyclerView grid;
    private GridAdapter adapter;
    private GlassView glass;
    private LinearLayout menu;
    private final List<View> menuRows = new ArrayList<>();
    private final List<Runnable> menuActions = new ArrayList<>();
    private final List<Setting> menuSettings = new ArrayList<>(); // parallel to menuRows; null = plain menu item
    private int panelWidthDp = 270;
    private boolean layoutChanged; // apps per row changed: rebuild the screen when the panel closes
    private int menuReturnZone = TOP;
    private AppInfo pinning;
    private boolean selectDown, longPressFired;
    private final Runnable longPress = () -> {
        longPressFired = true;
        openAppMenu();
    };

    private final List<AppInfo> dockApps = new ArrayList<>();
    private final List<Tile> dockTiles = new ArrayList<>();
    private int cols;
    private int zone = DOCK, topIdx, dockIdx, gridIdx, menuIdx;
    private String focusPkg;
    private Tile focusedTile;
    private View focusedPill;
    private boolean resumed;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private String shownWallpaper; // path + timestamp of the image on screen
    private boolean rotateOnResume, resortOnResume;

    private final Runnable reload = this::reloadApps;

    private final Runnable rotateTick = new Runnable() {
        @Override
        public void run() {
            showWallpaper(true);
            scheduleRotation();
        }
    };

    private final BroadcastReceiver screenOnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // TV woke up: new wallpaper, and re-sort the grid by usage (kept stable while the TV is on).
            if (resumed) {
                showWallpaper(true);
                reloadApps();
            } else {
                rotateOnResume = true;
                resortOnResume = true;
            }
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            handler.removeCallbacks(reload);
            handler.postDelayed(reload, 600);
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network n) {
            handler.post(MainActivity.this::updateNetwork);
        }

        @Override
        public void onLost(@NonNull Network n) {
            handler.post(MainActivity.this::updateNetwork);
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) {
            handler.post(MainActivity.this::updateNetwork);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        focusPkg = prefs.getString("focus_pkg", null);

        DisplayMetrics m = getResources().getDisplayMetrics();
        dp = m.density;
        screenW = m.widthPixels;
        screenH = m.heightPixels;
        cols = appsPerRow();
        thin = font(200);
        light = font(300);
        regular = font(400);

        computeGeometry();
        buildUi();
        setContentView(root);
        updateClock();
        updateNetwork();
        reloadApps();

        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        register(timeReceiver, time);
        IntentFilter pkgs = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pkgs.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgs.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgs.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkgs.addDataScheme("package");
        register(packageReceiver, pkgs);
        register(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(timeReceiver);
        unregisterReceiver(packageReceiver);
        unregisterReceiver(screenOnReceiver);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        updateClock();
        updateNetwork();
        showWallpaper(rotateOnResume || rotationDue());
        rotateOnResume = false;
        scheduleRotation();
        if (resortOnResume) {
            resortOnResume = false;
            reloadApps();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(rotateTick);
        handler.removeCallbacks(longPress);
        selectDown = false;
        closeMenu();
        if (zone == PICK) cancelPick();
        prefs.edit().putString("focus_pkg", focusPkg).apply();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already on the home screen: go back to the dock. Coming back from an
        // app (not resumed yet) keeps the remembered tile.
        if (resumed) {
            closeMenu();
            pinning = null;
            zone = DOCK;
            applyFocus(true);
        }
    }

    // ---------------------------------------------------------------- layout

    /** The grid uses the dock's columns, tile size and spacing, so every grid column sits under a dock tile. */
    private void computeGeometry() {
        int n = cols;
        side = px(48);
        bandMargin = px(32);
        int bandPad = px(24), dockGap = px(20), labelH = px(34); // dock tiles have no label: the big name above shows it
        dockTileW = (screenW - 2 * bandMargin - 2 * bandPad - (n - 1) * dockGap) / n;
        dockTileH = dockTileW * 9 / 16;
        bandH = bandPad + dockTileH + bandPad;
        headerH = screenH - bandH - px(22);

        gridGap = dockGap;
        gridLeft = bandMargin + bandPad;
        gridTileH = dockTileH;
        gridRowH = gridTileH + labelH + px(14);
        gridTop = headerH + bandH + px(44);
        rowAnchor = px(110);
        glowSize = px(720);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaper, match());
        wallpaperIncoming = new ImageView(this);
        wallpaperIncoming.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperIncoming.setAlpha(0f);
        root.addView(wallpaperIncoming, match());
        wallDim = new View(this);
        applyDim();
        wallDim.setVisibility(View.GONE);
        root.addView(wallDim, match());
        // Darker at the top (clock, pills) and bottom (app name, dock) so white text reads on bright photos.
        wallScrim = new View(this);
        wallScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x14000000, 0x80000000}));
        wallScrim.setVisibility(View.GONE);
        root.addView(wallScrim, match());

        page = new FrameLayout(this);
        page.setClipChildren(false);
        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, screenH * 2));

        // Header: glow, clock block, pills, focused dock app name.
        header = new FrameLayout(this);
        header.setClipChildren(false);
        page.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerH));

        glow = new View(this);
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(glowSize / 2f);
        applyGlow(glowColor);
        glow.setBackground(glowDrawable);
        glow.setScaleY(0.42f);
        glow.setTranslationY(headerH - glowSize / 2f);
        header.addView(glow, new FrameLayout.LayoutParams(glowSize, glowSize));

        LinearLayout clockBlock = new LinearLayout(this);
        clockBlock.setOrientation(LinearLayout.VERTICAL);
        greeting = text(light, 18, 0x99FFFFFF);
        clock = text(thin, 84, 0xFFFFFFFF);
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.02f);
        date = text(light, 18, 0xB3FFFFFF);
        clockBlock.addView(greeting);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = px(2);
        clockBlock.addView(clock, clp);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = px(4);
        clockBlock.addView(date, dlp);
        FrameLayout.LayoutParams cbl = wrap(Gravity.TOP | Gravity.START);
        cbl.leftMargin = side;
        cbl.topMargin = px(30);
        header.addView(clockBlock, cbl);

        LinearLayout pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pills[PILL_INPUTS] = pill(R.drawable.ic_input, "Inputs");
        pills[PILL_WIFI] = pill(R.drawable.ic_wifi, "Wi-Fi");
        wifiText = (TextView) ((LinearLayout) pills[PILL_WIFI]).getChildAt(1);
        pills[PILL_SETTINGS] = pill(R.drawable.ic_settings, null);
        pills[PILL_POWER] = pill(R.drawable.ic_power, null);
        for (int i = 0; i < pills.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    i >= PILL_SETTINGS ? px(38) : ViewGroup.LayoutParams.WRAP_CONTENT, px(38));
            if (i > 0) lp.leftMargin = px(10);
            pillRow.addView(pills[i], lp);
        }
        FrameLayout.LayoutParams prl = wrap(Gravity.TOP | Gravity.END);
        prl.rightMargin = side;
        prl.topMargin = px(34);
        header.addView(pillRow, prl);

        appName = text(light, 40, 0xF2FFFFFF);
        appName.setSingleLine(true);
        appName.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams anl = wrap(Gravity.BOTTOM | Gravity.START);
        anl.leftMargin = bandMargin + px(24);
        anl.bottomMargin = px(12);
        header.addView(appName, anl);

        // Dock band.
        dockBand = new FrameLayout(this);
        dockBand.setClipChildren(false);
        GradientDrawable band = new GradientDrawable();
        band.setColor(0x14FFFFFF);
        band.setStroke(px(1), 0x0FFFFFFF);
        band.setCornerRadius(px(30));
        dockBand.setBackground(band);
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.START); // left-aligned so a part-full dock still lines up with the grid
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(px(24), px(24), px(24), px(24));
        dockBand.addView(dockRow, match());
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(screenW - 2 * bandMargin, bandH);
        bl.leftMargin = bandMargin;
        bl.topMargin = headerH;
        page.addView(dockBand, bl);

        // App grid.
        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, cols));
        grid.setItemAnimator(null);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setFocusable(false);
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        int gap = gridGap;
        grid.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect out, @NonNull View v, @NonNull RecyclerView p, @NonNull RecyclerView.State s) {
                int col = p.getChildAdapterPosition(v) % cols;
                out.left = col * gap / cols;
                out.right = gap - (col + 1) * gap / cols;
            }
        });
        adapter = new GridAdapter(() -> new Tile(this, ViewGroup.LayoutParams.MATCH_PARENT, gridTileH, dp, regular, true), gridRowH);
        grid.setAdapter(adapter);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(cols * dockTileW + (cols - 1) * gridGap, gridRowH);
        gl.leftMargin = gridLeft;
        gl.topMargin = gridTop;
        page.addView(grid, gl);

        glass = new GlassView(this, dp);
        root.addView(glass, match());
    }

    private View pill(int icon, String label) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x1FFFFFFF);
        bg.setStroke(px(1), 0x1AFFFFFF);
        bg.setCornerRadius(px(19));
        p.setBackground(bg);
        if (label != null) p.setPadding(px(16), 0, px(18), 0);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(0xE6FFFFFF);
        p.addView(iv, new LinearLayout.LayoutParams(px(18), px(18)));
        if (label != null) {
            TextView t = text(regular, 14, 0xFFFFFFFF);
            t.setText(label);
            t.setSingleLine(true);
            t.setMaxWidth(px(220));
            t.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = px(8);
            p.addView(t, lp);
        }
        p.setAlpha(0.85f);
        return p;
    }

    // ---------------------------------------------------------------- apps

    private void reloadApps() {
        List<AppInfo> all = AppLoader.load(this);
        Set<String> hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_packages)));
        Map<String, AppInfo> byPkg = new HashMap<>();
        for (AppInfo a : all) byPkg.put(a.pkg, a);

        dockApps.clear();
        Set<String> inDock = new HashSet<>();
        for (String pkg : savedDock()) {
            AppInfo a = byPkg.get(pkg);
            if (a != null && dockApps.size() < dockCapacity() && inDock.add(pkg)) dockApps.add(a);
        }
        List<AppInfo> rest = new ArrayList<>();
        for (AppInfo a : all) if (!inDock.contains(a.pkg) && !hidden.contains(a.pkg)) rest.add(a);
        if (sortByUsageSetting()) sortByUsage(rest);

        dockRow.removeAllViews();
        dockTiles.clear();
        for (int i = 0; i < dockApps.size(); i++) {
            Tile t = new Tile(this, dockTileW, dockTileH, dp, regular, false);
            t.bind(dockApps.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dockTileW, ViewGroup.LayoutParams.MATCH_PARENT);
            if (i > 0) lp.leftMargin = px(20);
            dockRow.addView(t.root, lp);
            dockTiles.add(t);
        }

        focusedTile = null;
        adapter.setApps(rest);
        int rows = Math.max(1, (rest.size() + cols - 1) / cols);
        grid.getLayoutParams().height = rows * gridRowH;
        page.getLayoutParams().height = gridTop + rows * gridRowH + screenH;
        grid.requestLayout();

        resolveFocus();
        afterLayout(root, () -> applyFocus(false));
    }

    /**
     * Most used first: total time in each app over the last 30 days, from Android's usage stats.
     * Apps not used in that time keep their alphabetical order after the rest. Needs usage access
     * (granted by build-install.sh); without it the map is empty and the grid stays alphabetical.
     */
    private void sortByUsage(List<AppInfo> apps) {
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        if (usm == null) return;
        long now = System.currentTimeMillis();
        Map<String, UsageStats> stats;
        try {
            stats = usm.queryAndAggregateUsageStats(now - 30L * 24 * 60 * 60 * 1000, now);
        } catch (RuntimeException e) {
            return;
        }
        Map<String, Long> time = new HashMap<>();
        for (AppInfo a : apps) {
            UsageStats u = stats.get(a.pkg);
            time.put(a.pkg, u == null ? 0L : u.getTotalTimeInForeground());
        }
        // Stable sort: equal times (including never used) keep the alphabetical order.
        apps.sort((a, b) -> Long.compare(time.get(b.pkg), time.get(a.pkg)));
    }

    /** Puts the focus back on the remembered app, or a sensible default if it is gone. */
    private void resolveFocus() {
        List<AppInfo> gridApps = adapter.apps();
        if (zone != TOP && zone != MENU && focusPkg != null) {
            for (int i = 0; i < dockApps.size(); i++) {
                if (dockApps.get(i).pkg.equals(focusPkg)) {
                    zone = DOCK;
                    dockIdx = i;
                    return;
                }
            }
            for (int i = 0; i < gridApps.size(); i++) {
                if (gridApps.get(i).pkg.equals(focusPkg)) {
                    zone = GRID;
                    gridIdx = i;
                    return;
                }
            }
        }
        dockIdx = clamp(dockIdx, dockApps.size());
        gridIdx = clamp(gridIdx, gridApps.size());
        if (zone == DOCK && dockApps.isEmpty()) zone = gridApps.isEmpty() ? TOP : GRID;
        if (zone == GRID && gridApps.isEmpty()) zone = dockApps.isEmpty() ? TOP : DOCK;
    }

    private void launch(AppInfo app) {
        startSafe(app.launchIntent());
    }

    // ---------------------------------------------------------------- dock editing (hold OK on a tile)

    /** Dock packages: the order saved on the TV, or config.xml's dock_packages until the dock is first edited. */
    private List<String> savedDock() {
        String saved = prefs.getString("dock", null);
        if (saved == null) return new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.dock_packages)));
        List<String> out = new ArrayList<>();
        for (String p : saved.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private void saveDock(List<String> pkgs, String focus) {
        prefs.edit().putString("dock", TextUtils.join(",", pkgs)).apply();
        focusPkg = focus;
        zone = DOCK; // resolveFocus moves to the grid if focus is no longer in the dock
        reloadApps();
    }

    private List<String> dockPackages() {
        List<String> out = new ArrayList<>();
        for (AppInfo a : dockApps) out.add(a.pkg);
        return out;
    }

    private int dockCapacity() {
        return cols;
    }

    private void pin(AppInfo app) {
        List<String> dock = dockPackages();
        if (dock.size() < dockCapacity()) {
            dock.add(app.pkg);
            saveDock(dock, app.pkg);
            return;
        }
        pinning = app; // dock is full: pick the slot to replace
        zone = PICK;
        applyFocus(true);
    }

    private void replaceSlot() {
        List<String> dock = dockPackages();
        dock.set(dockIdx, pinning.pkg);
        String pkg = pinning.pkg;
        pinning = null;
        saveDock(dock, pkg);
    }

    private void cancelPick() {
        pinning = null;
        zone = GRID;
    }

    private void moveInDock(int delta) {
        List<String> dock = dockPackages();
        int to = dockIdx + delta;
        if (to < 0 || to >= dock.size()) return;
        String pkg = dock.remove(dockIdx);
        dock.add(to, pkg);
        saveDock(dock, pkg);
    }

    private void unpin(AppInfo app) {
        List<String> dock = dockPackages();
        dock.remove(app.pkg);
        saveDock(dock, app.pkg);
    }

    /**
     * Apps can't force-stop other apps (that needs a system permission); this ends the app's process
     * when it is in the background, which it is while the home screen is showing. App info has the
     * system's real Force stop button.
     */
    private void forceClose(AppInfo app) {
        ActivityManager am = getSystemService(ActivityManager.class);
        if (am != null) am.killBackgroundProcesses(app.pkg);
        Toast.makeText(this, app.label + " closed", Toast.LENGTH_SHORT).show();
    }

    /**
     * Ends the background processes of every app on the home screen (except Live TV, which handles
     * the inputs), then reports how much memory came back. Same mechanism as Force close: apps in use
     * or playing audio are left alone, and so are system services.
     */
    private void freeMemory() {
        ActivityManager am = getSystemService(ActivityManager.class);
        if (am == null) return;
        long before = availableMemory(am);
        String liveTv = getString(R.string.live_tv_package);
        List<AppInfo> apps = new ArrayList<>(dockApps);
        apps.addAll(adapter.apps());
        for (AppInfo a : apps) {
            if (!a.pkg.equals(liveTv)) am.killBackgroundProcesses(a.pkg);
        }
        // Processes take a moment to exit and their memory to be counted as free.
        handler.postDelayed(() -> {
            long after = availableMemory(am);
            long freedMb = Math.max(0, after - before) / (1024 * 1024);
            Toast.makeText(this, "Freed " + freedMb + " MB \u00b7 " + after / (1024 * 1024) + " MB now free",
                    Toast.LENGTH_LONG).show();
        }, 1200);
    }

    private static long availableMemory(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    private void openAppInfo(AppInfo app) {
        startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null)));
    }

    /** The menu for the focused tile: pin/move/remove, App info, Force close. */
    private void openAppMenu() {
        if (focusedTile == null || (zone != DOCK && zone != GRID)) return;
        AppInfo app = focusedTile.app;
        boolean inDock = zone == DOCK;
        closeMenu();
        menu = newPanel();
        if (inDock) {
            if (dockIdx > 0) addMenuItem("Move left", null, () -> moveInDock(-1));
            if (dockIdx < dockApps.size() - 1) addMenuItem("Move right", null, () -> moveInDock(1));
            addMenuItem("Remove from dock", null, () -> unpin(app));
        } else {
            addMenuItem("Pin to dock", null, () -> pin(app));
        }
        addMenuItem("App info", null, () -> openAppInfo(app));
        addMenuItem("Force close", null, () -> forceClose(app));

        RectF r = new RectF();
        rectIn(focusedTile.card, page, r);
        r.offset(0, pageTargetY());
        r.inset(-r.width() * (Tile.FOCUS_SCALE - 1) / 2, -r.height() * (Tile.FOCUS_SCALE - 1) / 2);
        showPanel(r, zone);
    }

    private void startSafe(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean isSelect = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
        boolean ours = isSelect || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU;
        if (!ours) return super.dispatchKeyEvent(e);
        if (isSelect) {
            onSelectKey(e);
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: up(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down(); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: left(); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right(); break;
            case KeyEvent.KEYCODE_BACK: back(); break;
            case KeyEvent.KEYCODE_MENU:
                if (e.getRepeatCount() == 0) openAppMenu();
                return true;
        }
        applyFocus(true);
        return true;
    }

    /**
     * OK acts on release, so a hold can be told apart from a tap: held for LONG_PRESS_MS on a tile
     * it opens the tile menu instead. A timer is used rather than key repeats, which remotes send differently.
     */
    private void onSelectKey(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() != 0) {
                // Remotes that send key repeats get Android's own long-press flag; use it if it comes first.
                if ((e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0 && selectDown && !longPressFired
                        && (zone == DOCK || zone == GRID)) {
                    handler.removeCallbacks(longPress);
                    longPress.run();
                }
                return;
            }
            selectDown = true;
            longPressFired = false;
            handler.removeCallbacks(longPress);
            if (zone == DOCK || zone == GRID) handler.postDelayed(longPress, LONG_PRESS_MS);
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(longPress);
            boolean wasDown = selectDown;
            selectDown = false;
            if (longPressFired) {
                longPressFired = false;
                return;
            }
            // Ignore a release whose press happened elsewhere (e.g. OK pressed in an app that then closed).
            if (wasDown && !e.isCanceled()) select();
        }
    }

    private void up() {
        switch (zone) {
            case DOCK: zone = TOP; break;
            case GRID:
                if (gridIdx < cols) zone = dockApps.isEmpty() ? TOP : DOCK;
                else gridIdx -= cols;
                break;
            case MENU: if (menuIdx > 0) menuIdx--; break;
            default: break; // TOP: nothing above
        }
    }

    private void down() {
        int count = adapter.getItemCount();
        switch (zone) {
            case TOP:
                if (!dockApps.isEmpty()) zone = DOCK;
                else if (count > 0) zone = GRID;
                break;
            case DOCK:
                if (count > 0) {
                    zone = GRID;
                    gridIdx = Math.min(dockColumn(), count - 1);
                }
                break;
            case GRID:
                if (gridIdx / cols < (count - 1) / cols) gridIdx = Math.min(gridIdx + cols, count - 1);
                break;
            case MENU: if (menuIdx < menuRows.size() - 1) menuIdx++; break;
        }
    }

    private void left() {
        switch (zone) {
            case TOP: if (topIdx > 0) topIdx--; break;
            case DOCK:
            case PICK: if (dockIdx > 0) dockIdx--; break;
            case GRID: if (gridIdx % cols > 0) gridIdx--; break;
            case MENU: changeSetting(-1, false); break;
            default: break;
        }
    }

    private void right() {
        switch (zone) {
            case TOP: if (topIdx < pills.length - 1) topIdx++; break;
            case DOCK:
            case PICK: if (dockIdx < dockApps.size() - 1) dockIdx++; break;
            case GRID: if (gridIdx % cols < cols - 1 && gridIdx + 1 < adapter.getItemCount()) gridIdx++; break;
            case MENU: changeSetting(1, false); break;
            default: break;
        }
    }

    private void back() {
        if (zone == MENU) closeMenu();
        else if (zone == PICK) cancelPick();
        else if ((zone == GRID || zone == TOP) && !dockApps.isEmpty()) zone = DOCK;
    }

    private void select() {
        switch (zone) {
            case TOP:
                if (topIdx == PILL_INPUTS) openInputs();
                else if (topIdx == PILL_WIFI) startSafe(new Intent(Settings.ACTION_WIFI_SETTINGS));
                else if (topIdx == PILL_SETTINGS) openSettingsMenu();
                else openPowerMenu();
                break;
            case DOCK: if (!dockApps.isEmpty()) launch(dockApps.get(dockIdx)); break;
            case GRID: if (adapter.getItemCount() > 0) launch(adapter.apps().get(gridIdx)); break;
            case PICK: replaceSlot(); break;
            case MENU:
                if (changeSetting(1, true)) break; // settings rows change in place; the panel stays open
                Runnable action = menuActions.get(menuIdx);
                closeMenu();
                applyFocus(false);
                action.run();
                break;
        }
    }

    /** Grid column under the focused dock tile. */
    private int dockColumn() {
        return Math.min(dockIdx, cols - 1); // dock and grid share columns
    }

    // ---------------------------------------------------------------- focus

    /** Page scroll for the current zone: the focused grid row sits near the top, everything else at 0. */
    private float pageTargetY() {
        return zone == GRID ? -(gridTop + (gridIdx / cols) * gridRowH - rowAnchor) : 0;
    }

    private void applyFocus(boolean animate) {
        animate = animate && animationsOn();
        float ty = zone == MENU ? page.getTranslationY() : pageTargetY();
        page.animate().cancel();
        dockBand.animate().cancel();
        float bandAlpha = zone == GRID || (zone == MENU && menuReturnZone == GRID) ? 0.45f : 1f;
        if (animate) {
            page.animate().translationY(ty).setDuration(360).setInterpolator(new DecelerateInterpolator(1.6f)).start();
            dockBand.animate().alpha(bandAlpha).setDuration(300).start();
        } else {
            page.setTranslationY(ty);
            dockBand.setAlpha(bandAlpha);
        }

        Tile tile = null;
        View pill = null;
        RectF r = new RectF();
        float radius = 0;
        switch (zone) {
            case TOP:
                pill = pills[topIdx];
                break;
            case DOCK:
            case PICK:
                if (!dockTiles.isEmpty()) tile = dockTiles.get(dockIdx);
                break;
            case GRID:
                RecyclerView.LayoutManager lm = grid.getLayoutManager();
                View v = lm == null ? null : lm.findViewByPosition(gridIdx);
                if (v != null) tile = (Tile) v.getTag();
                break;
            case MENU:
                View row = menuRows.get(menuIdx);
                rectIn(row, root, r);
                radius = px(12);
                break;
        }

        if (zone == MENU && menuReturnZone != TOP) tile = focusedTile; // tile menu open: keep its tile raised
        if (focusedTile != null && focusedTile != tile) focusedTile.setFocused(false, animate);
        if (tile != null && tile != focusedTile) tile.setFocused(true, animate);
        focusedTile = tile;
        if (focusedPill != null && focusedPill != pill) focusedPill.setAlpha(0.85f);
        if (pill != null) pill.setAlpha(1f);
        focusedPill = pill;

        if (zone == MENU) {
            // r is the menu row, already set above
        } else if (tile != null) {
            rectIn(tile.card, page, r);
            r.offset(0, ty);
            float dx = r.width() * (Tile.FOCUS_SCALE - 1) / 2 + px(5);
            float dy = r.height() * (Tile.FOCUS_SCALE - 1) / 2 + px(5);
            r.inset(-dx, -dy);
            radius = px(17);
            focusPkg = tile.app.pkg;
        } else if (pill != null) {
            rectIn(pill, page, r);
            r.offset(0, ty);
            r.inset(-px(3), -px(3));
            radius = r.height() / 2;
        }
        glass.moveTo(r, radius, animate);

        updateDockHeader(animate);
    }

    /** The big app name above the dock and the coloured glow behind it follow the dock focus. */
    private void updateDockHeader(boolean animate) {
        if (dockApps.isEmpty()) {
            appName.setText("");
            glow.setAlpha(0f);
            return;
        }
        AppInfo app = dockApps.get(dockIdx);
        appName.setText(zone == PICK && pinning != null ? "Replace " + app.label + " with " + pinning.label + "?" : app.label);
        int effective = zone == MENU ? menuReturnZone : zone;
        boolean dockActive = effective == DOCK || effective == PICK;
        float nameAlpha = dockActive ? 1f : effective == GRID ? 0f : 0.5f;
        float glowAlpha = dockActive ? 1f : effective == GRID ? 0f : 0.55f;

        RectF r = new RectF();
        rectIn(dockTiles.get(dockIdx).card, page, r);
        float gx = r.centerX() - glowSize / 2f;

        if (app.glow == null) app.glow = GlowColor.sample(app.banner != null ? app.banner : app.icon);
        if (glowAnim != null) glowAnim.cancel();
        appName.animate().cancel();
        glow.animate().cancel();
        if (animate) {
            appName.animate().alpha(nameAlpha).setDuration(250).start();
            glow.animate().alpha(glowAlpha).translationX(gx).setDuration(450).setInterpolator(new DecelerateInterpolator()).start();
            glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, app.glow);
            glowAnim.setDuration(450);
            glowAnim.addUpdateListener(a -> applyGlow((Integer) a.getAnimatedValue()));
            glowAnim.start();
        } else {
            appName.setAlpha(nameAlpha);
            glow.setAlpha(glowAlpha);
            glow.setTranslationX(gx);
            applyGlow(app.glow);
        }
    }

    private void applyGlow(int c) {
        glowColor = c;
        glowDrawable.setColors(new int[]{withAlpha(c, 0x73), withAlpha(c, 0x2A), withAlpha(c, 0)});
    }

    // ---------------------------------------------------------------- inputs menu

    /** A compact glass dropdown; fill it with addMenuItem, then showPanel. */
    private LinearLayout newPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(px(8), px(8), px(8), px(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEB1C1C1E);
        bg.setStroke(px(1), 0x26FFFFFF);
        bg.setCornerRadius(px(20));
        p.setBackground(bg);
        return p;
    }

    /** Shows the panel under the anchor (root coordinates), or above it when it would run off the bottom. */
    private void showPanel(RectF anchor, int returnZone) {
        int w = px(panelWidthDp);
        panelWidthDp = 270;
        menu.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = menu.getMeasuredHeight();
        int top = (int) anchor.bottom + px(10);
        if (top + h > screenH - px(10)) top = (int) anchor.top - px(10) - h;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) Math.max(side, Math.min(anchor.left, screenW - side - w));
        lp.topMargin = Math.max(px(10), top);
        root.addView(menu, root.indexOfChild(glass), lp);
        boolean below = top > anchor.top;
        menu.setAlpha(0f);
        menu.setTranslationY(below ? -px(8) : px(8));
        menu.animate().alpha(1f).translationY(0).setDuration(180).start();
        menuReturnZone = returnZone;
        zone = MENU;
        menuIdx = 0;
        afterLayout(menu, () -> applyFocus(true));
    }

    // ---------------------------------------------------------------- settings

    /** One Home settings row: a label and a value changed with Left/Right or OK. */
    private static final class Setting {
        final String[] names;
        final int[] values;
        final TextView valueView;
        final java.util.function.IntConsumer onChange;
        int idx;

        Setting(String[] names, int[] values, int idx, TextView valueView, java.util.function.IntConsumer onChange) {
            this.names = names;
            this.values = values;
            this.idx = idx;
            this.valueView = valueView;
            this.onChange = onChange;
        }
    }

    // Values set on the TV override config.xml, which only supplies the defaults.
    private int appsPerRow() {
        return prefs.getInt("set_columns", getResources().getInteger(R.integer.grid_columns));
    }

    private int rotateMinutes() {
        return prefs.getInt("set_rotate", getResources().getInteger(R.integer.wallpaper_rotate_minutes));
    }

    private boolean shuffleSetting() {
        return prefs.getBoolean("set_shuffle", getResources().getBoolean(R.bool.wallpaper_shuffle));
    }

    private int dimPercent() {
        return prefs.getInt("set_dim", getResources().getInteger(R.integer.wallpaper_dim_percent));
    }

    private boolean sortByUsageSetting() {
        return prefs.getBoolean("set_sort_usage", getResources().getBoolean(R.bool.grid_sort_by_usage));
    }

    private boolean animationsOn() {
        return prefs.getBoolean("set_animations", true);
    }

    private void applyDim() {
        wallDim.setBackgroundColor(Color.argb(dimPercent() * 255 / 100, 0, 0, 0));
    }

    /** Settings pill: the launcher's own settings, or Android's. */
    private void openSettingsMenu() {
        closeMenu();
        menu = newPanel();
        addMenuItem("Home settings", null, this::openHomeSettings);
        addMenuItem("TV settings", null, () -> startSafe(new Intent(Settings.ACTION_SETTINGS)));
        RectF r = new RectF();
        rectIn(pills[PILL_SETTINGS], page, r);
        showPanel(r, TOP);
    }

    private void openHomeSettings() {
        closeMenu();
        menu = newPanel();
        addSetting("Apps per row", new String[]{"4", "5", "6"}, new int[]{4, 5, 6}, appsPerRow(), v -> {
            prefs.edit().putInt("set_columns", v).apply();
            layoutChanged = v != cols;
        });
        addSetting("Wallpaper changes", new String[]{"On wake only", "Every 15 min", "Every 30 min", "Every hour"},
                new int[]{0, 15, 30, 60}, rotateMinutes(), v -> {
                    prefs.edit().putInt("set_rotate", v).apply();
                    scheduleRotation();
                });
        addSetting("Wallpaper order", new String[]{"Shuffle", "A\u2013Z"}, new int[]{1, 0}, shuffleSetting() ? 1 : 0,
                v -> prefs.edit().putBoolean("set_shuffle", v == 1).apply());
        addSetting("Wallpaper darkness", new String[]{"20%", "35%", "50%"}, new int[]{20, 35, 50}, dimPercent(), v -> {
            prefs.edit().putInt("set_dim", v).apply();
            applyDim();
        });
        addSetting("App order", new String[]{"Most used", "A\u2013Z"}, new int[]{1, 0}, sortByUsageSetting() ? 1 : 0, v -> {
            prefs.edit().putBoolean("set_sort_usage", v == 1).apply();
            reloadApps();
        });
        addSetting("Animations", new String[]{"On", "Off"}, new int[]{1, 0}, animationsOn() ? 1 : 0,
                v -> prefs.edit().putBoolean("set_animations", v == 1).apply());
        addMenuItem("Reset dock to default", null, () -> {
            prefs.edit().remove("dock").apply();
            reloadApps();
            Toast.makeText(this, "Dock reset", Toast.LENGTH_SHORT).show();
        });
        RectF r = new RectF();
        rectIn(pills[PILL_SETTINGS], page, r);
        panelWidthDp = 380;
        showPanel(r, TOP);
    }

    private void addSetting(String label, String[] names, int[] values, int current, java.util.function.IntConsumer onChange) {
        int idx = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == current) idx = i;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(16), 0, px(16), 0);
        TextView name = text(regular, 15, 0xFFFFFFFF);
        name.setText(label);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text(regular, 15, 0xB3FFFFFF);
        row.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Setting st = new Setting(names, values, idx, value, onChange);
        showValue(st);
        menu.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44)));
        menuRows.add(row);
        menuActions.add(null);
        menuSettings.add(st);
    }

    private static void showValue(Setting st) {
        st.valueView.setText("\u2039  " + st.names[st.idx] + "  \u203A");
    }

    /**
     * Steps the focused settings row: Left/Right move one value and stop at the ends, OK wraps around.
     * Returns false when the focused row is a plain menu item.
     */
    private boolean changeSetting(int delta, boolean wrap) {
        if (menuIdx >= menuSettings.size()) return false;
        Setting st = menuSettings.get(menuIdx);
        if (st == null) return false;
        int n = st.names.length;
        int idx = wrap ? (st.idx + delta + n) % n : Math.max(0, Math.min(n - 1, st.idx + delta));
        if (idx == st.idx) return true;
        st.idx = idx;
        showValue(st);
        st.onChange.accept(st.values[idx]);
        return true;
    }

    private void openPowerMenu() {
        closeMenu();
        menu = newPanel();
        addMenuItem("Sleep", null, () -> {
            if (!PowerService.sleep()) askForPowerService();
        });
        addMenuItem("Free up memory", null, this::freeMemory);
        addMenuItem("Restart or shut down\u2026", null, () -> {
            if (!PowerService.powerMenu()) askForPowerService();
        });
        RectF r = new RectF();
        rectIn(pills[PILL_POWER], page, r);
        showPanel(r, TOP);
    }

    /** Sleep and the power menu go through PowerService, which has to be switched on once. */
    private void askForPowerService() {
        Toast.makeText(this, "Turn on \"Home power controls\" in Accessibility", Toast.LENGTH_LONG).show();
        startSafe(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void openInputs() {
        closeMenu();
        menu = newPanel();

        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> all = tim.getTvInputList();
            Map<String, String> deviceNames = new HashMap<>(); // HDMI-CEC devices, e.g. "PS5" on HDMI 2
            for (TvInputInfo i : all) {
                if (i.getParentId() != null) deviceNames.put(i.getParentId(), String.valueOf(i.loadLabel(this)));
            }
            List<TvInputInfo> ports = new ArrayList<>();
            for (TvInputInfo i : all) {
                if (i.getParentId() != null || i.isHidden(this)) continue;
                if (i.getType() == TvInputInfo.TYPE_TUNER || i.getType() == TvInputInfo.TYPE_OTHER) {
                    if (tunerId == null && i.getType() == TvInputInfo.TYPE_TUNER) tunerId = i.getId();
                    continue;
                }
                ports.add(i);
            }
            ports.sort((a, b) -> {
                int ta = a.getType() == TvInputInfo.TYPE_HDMI ? 0 : 1;
                int tb = b.getType() == TvInputInfo.TYPE_HDMI ? 0 : 1;
                return ta != tb ? ta - tb : inputLabel(a).compareToIgnoreCase(inputLabel(b));
            });
            for (TvInputInfo i : ports) {
                String id = i.getId();
                addMenuItem(inputLabel(i), deviceNames.get(id),
                        () -> startSafe(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))));
            }
        }
        Intent liveTv = getPackageManager().getLeanbackLaunchIntentForPackage(getString(R.string.live_tv_package));
        if (tunerId != null || liveTv != null) {
            String tuner = tunerId;
            addMenuItem("Live TV", null, () -> openLiveTv(tuner, liveTv));
        }
        if (menuRows.isEmpty()) {
            menu = null;
            Toast.makeText(this, "No inputs found", Toast.LENGTH_SHORT).show();
            return;
        }

        RectF pr = new RectF();
        rectIn(pills[PILL_INPUTS], page, pr);
        showPanel(pr, TOP);
    }

    /** Tune the tuner input if the TV app accepts it, otherwise just open the TV app. */
    private void openLiveTv(String tunerId, Intent fallback) {
        if (tunerId != null) {
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(tunerId));
            if (i.resolveActivity(getPackageManager()) != null) {
                startSafe(i);
                return;
            }
        }
        if (fallback != null) startSafe(fallback);
    }

    private String inputLabel(TvInputInfo i) {
        CharSequence l = i.loadCustomLabel(this);
        if (l == null || l.length() == 0) l = i.loadLabel(this);
        return String.valueOf(l);
    }

    private void addMenuItem(String label, String device, Runnable action) {
        TextView row = text(regular, 15, 0xFFFFFFFF);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(16), 0, px(16), 0);
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.END);
        if (device != null && !device.isEmpty() && !device.equals(label)) {
            SpannableString s = new SpannableString(label + "   " + device);
            s.setSpan(new ForegroundColorSpan(0x99FFFFFF), label.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            row.setText(s);
        } else {
            row.setText(label);
        }
        menu.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44)));
        menuRows.add(row);
        menuActions.add(action);
        menuSettings.add(null);
    }

    private void closeMenu() {
        menuSettings.clear();
        if (layoutChanged) {
            layoutChanged = false;
            handler.post(this::recreate); // new column count: rebuild the layout
        }
        if (menu != null) root.removeView(menu);
        menu = null;
        menuRows.clear();
        menuActions.clear();
        if (zone == MENU) zone = menuReturnZone;
    }

    // ---------------------------------------------------------------- clock, network, wallpaper

    private void updateClock() {
        Date now = new Date();
        Locale loc = Locale.getDefault();
        clock.setText(new SimpleDateFormat(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", loc).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", loc).format(now));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String name = ownerName();
        greeting.setText((h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening")
                + (name.isEmpty() ? "" : ", " + name));
    }

    /** config.xml's greeting_name, else "Afif" from a device name like "Afif's TV"; empty for "Android TV". */
    private String ownerName() {
        String fixed = getString(R.string.greeting_name).trim();
        if (!fixed.isEmpty()) return fixed;
        String device = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        if (device == null) return "";
        int i = device.replace('\u2019', '\'').indexOf("'s ");
        return i > 0 ? device.substring(0, i).trim() : "";
    }

    private void updateNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
        String text;
        if (c == null) {
            text = "Offline";
        } else if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            text = "Ethernet";
        } else if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            String ssid = wm == null ? null : wm.getConnectionInfo().getSSID();
            if (ssid != null && ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            text = ssid == null || ssid.isEmpty() || ssid.equals(WifiManager.UNKNOWN_SSID) ? "Wi-Fi" : ssid;
        } else {
            text = "Online";
        }
        if (text.contentEquals(wifiText.getText())) return;
        wifiText.setText(text);
        if (zone == TOP) afterLayout(root, () -> applyFocus(false));
    }

    /**
     * Wallpaper images: everything in the app's files/wallpapers/ folder, or else a single
     * files/wallpaper.jpg (.png/.webp). Empty list = pitch black.
     */
    private List<File> wallpaperFiles() {
        List<File> out = new ArrayList<>();
        File dir = getExternalFilesDir(null);
        if (dir == null) return out;
        File[] folder = new File(dir, "wallpapers").listFiles(f -> f.isFile() && isImage(f.getName()));
        if (folder != null && folder.length > 0) {
            Arrays.sort(folder, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            out.addAll(Arrays.asList(folder));
            return out;
        }
        for (String name : new String[]{"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) {
            File f = new File(dir, name);
            if (f.isFile()) {
                out.add(f);
                break;
            }
        }
        return out;
    }

    private static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    private long rotateIntervalMs() {
        return rotateMinutes() * 60_000L;
    }

    private boolean rotationDue() {
        long interval = rotateIntervalMs();
        return interval > 0 && System.currentTimeMillis() - prefs.getLong("wp_changed", 0) >= interval;
    }

    /** While the home screen is showing, change the wallpaper when the interval runs out. */
    private void scheduleRotation() {
        handler.removeCallbacks(rotateTick);
        long interval = rotateIntervalMs();
        if (interval <= 0 || !resumed) return;
        long left = interval - (System.currentTimeMillis() - prefs.getLong("wp_changed", 0));
        handler.postDelayed(rotateTick, Math.max(left, 1000));
    }

    /** Shows the current wallpaper, or moves on to the next one when advance is true. */
    private void showWallpaper(boolean advance) {
        List<File> files = wallpaperFiles();
        if (files.isEmpty()) {
            shownWallpaper = null;
            wallpaper.setImageDrawable(null);
            wallpaperIncoming.setImageDrawable(null);
            wallDim.setVisibility(View.GONE);
            wallScrim.setVisibility(View.GONE);
            return;
        }
        int current = -1;
        String currentName = prefs.getString("wp_name", null);
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getName().equals(currentName)) current = i;
        }
        File next;
        if (current < 0 || advance) {
            int n = files.size();
            int i;
            if (n == 1) i = 0;
            else if (shuffleSetting()) i = current < 0 ? random.nextInt(n) : (current + 1 + random.nextInt(n - 1)) % n;
            else i = (current + 1) % n;
            next = files.get(i);
            prefs.edit().putString("wp_name", next.getName()).putLong("wp_changed", System.currentTimeMillis()).apply();
        } else {
            next = files.get(current);
        }

        String key = next.getPath() + ":" + next.lastModified();
        if (key.equals(shownWallpaper)) return;
        shownWallpaper = key;
        io.execute(() -> {
            Bitmap b = decodeWallpaper(next);
            handler.post(() -> {
                if (key.equals(shownWallpaper)) crossfadeTo(b);
            });
        });
    }

    private Bitmap decodeWallpaper(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= screenW && o.outHeight / (sample * 2) >= screenH) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private void crossfadeTo(Bitmap b) {
        if (b == null) return;
        wallDim.setVisibility(View.VISIBLE);
        wallScrim.setVisibility(View.VISIBLE);
        wallpaperIncoming.animate().cancel();
        wallpaperIncoming.setImageBitmap(b);
        wallpaperIncoming.setAlpha(0f);
        wallpaperIncoming.animate().alpha(1f).setDuration(900).withEndAction(() -> {
            wallpaper.setImageBitmap(b);
            wallpaperIncoming.setImageDrawable(null);
            wallpaperIncoming.setAlpha(0f);
        }).start();
    }

    // ---------------------------------------------------------------- helpers

    private Typeface font(int weight) {
        Typeface t = null;
        try {
            t = new Typeface.Builder(getAssets(), "fonts/Outfit.ttf").setFontVariationSettings("'wght' " + weight).build();
        } catch (RuntimeException ignored) {
        }
        return t != null ? t : Typeface.create("sans-serif-light", Typeface.NORMAL);
    }

    private TextView text(Typeface tf, float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    /** Position of v relative to ancestor, ignoring translations and scale (layout position). */
    private static void rectIn(View v, View ancestor, RectF out) {
        float x = 0, y = 0;
        View c = v;
        while (c != null && c != ancestor) {
            x += c.getLeft();
            y += c.getTop();
            ViewParent p = c.getParent();
            c = p instanceof View ? (View) p : null;
        }
        out.set(x, y, x + v.getWidth(), y + v.getHeight());
    }

    private static void afterLayout(View v, Runnable r) {
        v.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                v.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                r.run();
            }
        });
        v.requestLayout();
    }

    private void register(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(r, f);
    }

    private static int withAlpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    private static int clamp(int i, int size) {
        return Math.max(0, Math.min(i, size - 1));
    }

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static FrameLayout.LayoutParams wrap(int gravity) {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
    }
}
