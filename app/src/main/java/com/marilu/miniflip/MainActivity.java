package com.marilu.miniflip;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    public static final String EXTRA_PAGE = "miniflip_page";
    public static final String EXTRA_OPEN_SETTINGS = "irving_open_settings";

    private static final int PAGE_HOME = 0;
    private static final int PAGE_APPS = 1;
    private static final int REQUEST_WALLPAPER = 7001;
    private static final int REQUEST_SHIZUKU = 7101;
    private static final int HOME_SLOTS = 8;
    private static final int DOCK_SLOTS = 5;
    private static final int COVER_DISPLAY_ID = 1;
    private static final long HOME_HOLD_MS = 3000L;

    private static final String PREFS = "miniflip_prefs";
    private static final String WALLPAPER_URI = "wallpaper_uri";
    private static final String HOME_PREFIX = "home_";
    private static final String DOCK_PREFIX = "dock_";
    private static final String OLD_FAVORITE_PREFIX = "favorite_";

    private final List<AppEntry> allApps = new ArrayList<>();
    private final Handler holdHandler = new Handler(Looper.getMainLooper());

    private FrameLayout content;
    private FrameLayout homePage;
    private FrameLayout appsPage;
    private GridLayout homeGrid;
    private GridLayout appsGrid;
    private LinearLayout dock;
    private EditText search;
    private ImageView wallpaperView;
    private int currentPage = PAGE_HOME;

    private float homeDownX;
    private float homeDownY;
    private boolean homeHoldTriggered;

    private final Runnable homeHoldRunnable = new Runnable() {
        @Override
        public void run() {
            homeHoldTriggered = true;
            showAddToHomePicker();
        }
    };

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != REQUEST_SHIZUKU) return;
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        RotationController.startRotationEnforcer(MainActivity.this);
                        Toast.makeText(MainActivity.this,
                                "Rotación completa de la pantalla externa activada",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this,
                                "Se necesita permiso de Shizuku para girar también las otras aplicaciones",
                                Toast.LENGTH_LONG).show();
                    }
                }
            };

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        applyImmersiveMode();
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {}

        loadLauncherApps();
        buildUi();
        RotationController.startRotationEnforcer(this);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        showPage(resolveRequestedPage(intent));
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            content.postDelayed(this::showIrvingSettings, 180);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        RotationController.startRotationEnforcer(this);
    }

    @Override
    protected void onDestroy() {
        holdHandler.removeCallbacks(homeHoldRunnable);
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_WALLPAPER && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {}
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(WALLPAPER_URI, uri.toString())
                    .apply();
            applySavedWallpaper();
        }
    }

    private int resolveRequestedPage(Intent intent) {
        if (intent == null) return PAGE_HOME;
        int page = intent.getIntExtra(EXTRA_PAGE, PAGE_HOME);
        return page == PAGE_APPS ? PAGE_APPS : PAGE_HOME;
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void buildUi() {
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(Color.BLACK);

        wallpaperView = new ImageView(this);
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperView.setBackgroundColor(Color.BLACK);
        shell.addView(wallpaperView, new FrameLayout.LayoutParams(-1, -1));

        View scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(38, 0, 0, 0));
        shell.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        content = new FrameLayout(this);
        shell.addView(content, new FrameLayout.LayoutParams(-1, -1));

        homePage = buildHomePage();
        appsPage = buildAppsPage();
        content.addView(homePage, new FrameLayout.LayoutParams(-1, -1));
        content.addView(appsPage, new FrameLayout.LayoutParams(-1, -1));

        setContentView(shell);
        applySavedWallpaper();
    }

    private FrameLayout buildHomePage() {
        FrameLayout page = new FrameLayout(this);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setClickable(true);
        installHomeBackgroundHold(page);

        TextView title = new TextView(this);
        title.setText("Irving OS");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setShadowLayer(5f, 0f, 1f, Color.BLACK);
        FrameLayout.LayoutParams titleLp =
                new FrameLayout.LayoutParams(dp(130), dp(38), Gravity.TOP | Gravity.START);
        titleLp.leftMargin = dp(12);
        titleLp.topMargin = dp(7);
        page.addView(title, titleLp);

        homeGrid = new GridLayout(this);
        homeGrid.setColumnCount(4);
        homeGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        homeGrid.setUseDefaultMargins(false);
        FrameLayout.LayoutParams gridLp =
                new FrameLayout.LayoutParams(-1, dp(176), Gravity.TOP);
        gridLp.leftMargin = dp(8);
        gridLp.rightMargin = dp(8);
        gridLp.topMargin = dp(47);
        page.addView(homeGrid, gridLp);
        renderHomeShortcuts();

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(8), dp(4), dp(8), dp(4));
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setBackground(rounded(Color.argb(205, 37, 39, 48), 18));

        FrameLayout.LayoutParams dockLp =
                new FrameLayout.LayoutParams(-2, dp(66), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dockLp.bottomMargin = dp(60);
        page.addView(dock, dockLp);
        renderDock();
        installDockTouchBehavior();

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(2), dp(2), dp(2), dp(2));
        controls.setBackground(rounded(Color.argb(205, 29, 30, 37), 14));

        TextView appsButton = compactControl("Apps", 10);
        appsButton.setOnClickListener(v -> showPage(PAGE_APPS));
        controls.addView(appsButton, new LinearLayout.LayoutParams(dp(46), dp(38)));

        TextView settingsButton = compactControl("⚙", 18);
        settingsButton.setOnClickListener(v -> showIrvingSettings());
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(42), dp(38));
        settingsLp.leftMargin = dp(4);
        controls.addView(settingsButton, settingsLp);

        FrameLayout.LayoutParams controlsLp =
                new FrameLayout.LayoutParams(dp(98), dp(44), Gravity.BOTTOM | Gravity.START);
        controlsLp.leftMargin = dp(8);
        controlsLp.bottomMargin = dp(8);
        page.addView(controls, controlsLp);

        return page;
    }

    private void installHomeBackgroundHold(FrameLayout page) {
        page.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    homeDownX = event.getX();
                    homeDownY = event.getY();
                    homeHoldTriggered = false;
                    holdHandler.removeCallbacks(homeHoldRunnable);
                    holdHandler.postDelayed(homeHoldRunnable, HOME_HOLD_MS);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - homeDownX) > dp(18)
                            || Math.abs(event.getY() - homeDownY) > dp(18)) {
                        holdHandler.removeCallbacks(homeHoldRunnable);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    holdHandler.removeCallbacks(homeHoldRunnable);
                    return homeHoldTriggered;

                default:
                    return true;
            }
        });
    }

    private FrameLayout buildAppsPage() {
        FrameLayout page = new FrameLayout(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(6), dp(8), dp(4));
        page.addView(root, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = compactControl("Inicio", 10);
        back.setOnClickListener(v -> showPage(PAGE_HOME));
        header.addView(back, new LinearLayout.LayoutParams(dp(54), dp(34)));

        TextView label = new TextView(this);
        label.setText("Aplicaciones");
        label.setTextColor(Color.WHITE);
        label.setTextSize(16);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        header.addView(label, new LinearLayout.LayoutParams(0, dp(36), 1f));

        TextView settings = compactControl("⚙", 18);
        settings.setOnClickListener(v -> showIrvingSettings());
        header.addView(settings, new LinearLayout.LayoutParams(dp(40), dp(34)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(40)));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Buscar aplicación");
        search.setHintTextColor(Color.LTGRAY);
        search.setTextColor(Color.WHITE);
        search.setTextSize(13);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackground(rounded(Color.argb(215, 25, 26, 32), 14));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(40));
        searchLp.setMargins(0, dp(3), 0, dp(3));
        root.addView(search, searchLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(92));

        appsGrid = new GridLayout(this);
        appsGrid.setColumnCount(4);
        appsGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        appsGrid.setUseDefaultMargins(false);
        scroll.addView(appsGrid, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        renderApps("");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderApps(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        return page;
    }

    private void showPage(int page) {
        currentPage = page == PAGE_APPS ? PAGE_APPS : PAGE_HOME;
        if (homePage != null) {
            homePage.setVisibility(currentPage == PAGE_HOME ? View.VISIBLE : View.GONE);
        }
        if (appsPage != null) {
            appsPage.setVisibility(currentPage == PAGE_APPS ? View.VISIBLE : View.GONE);
        }
        if (currentPage == PAGE_HOME) {
            renderHomeShortcuts();
            renderDock();
        }
        applyImmersiveMode();
    }

    private TextView compactControl(String text, int textSize) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(textSize);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setBackground(rounded(Color.argb(220, 45, 46, 56), 11));
        return v;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radiusDp));
        return bg;
    }

    private void renderHomeShortcuts() {
        if (homeGrid == null) return;
        homeGrid.removeAllViews();

        for (int slot = 0; slot < HOME_SLOTS; slot++) {
            AppEntry app = getHomeApp(slot);
            if (app == null) continue;
            homeGrid.addView(makeHomeTile(app, slot));
        }
    }

    private View makeHomeTile(AppEntry app, int slot) {
        LinearLayout box = baseTile(84);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(dp(43), dp(43)));
        box.addView(tileLabel(app.label), new LinearLayout.LayoutParams(-1, dp(25)));

        box.setOnClickListener(v -> openApp(app));
        box.setOnLongClickListener(v -> {
            chooseAppForHomeSlot(slot);
            return true;
        });
        return box;
    }

    private void showAddToHomePicker() {
        int freeSlot = firstFreeSlot(HOME_PREFIX, HOME_SLOTS);
        if (freeSlot < 0) {
            Toast.makeText(this,
                    "La pantalla principal está llena. Puedes cambiar aplicaciones desde Ajustes.",
                    Toast.LENGTH_LONG).show();
            showHomeManager();
            return;
        }

        String[] items = new String[allApps.size()];
        for (int i = 0; i < allApps.size(); i++) items[i] = allApps.get(i).label;

        new AlertDialog.Builder(this)
                .setTitle("Agregar a pantalla principal")
                .setItems(items, (dialog, which) -> {
                    AppEntry app = allApps.get(which);
                    saveAppToSlot(HOME_PREFIX, freeSlot, app);
                    renderHomeShortcuts();
                    Toast.makeText(this, app.label + " agregada", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void renderDock() {
        if (dock == null) return;
        dock.removeAllViews();

        int added = 0;
        for (int slot = 0; slot < DOCK_SLOTS; slot++) {
            AppEntry app = getDockApp(slot);
            if (app == null) continue;
            View item = makeDockItem(app, slot);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(50), dp(58));
            lp.setMargins(dp(1), 0, dp(1), 0);
            dock.addView(item, lp);
            added++;
        }

        dock.setVisibility(added == 0 ? View.GONE : View.VISIBLE);
    }

    private View makeDockItem(AppEntry app, int slot) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(1), dp(2), dp(1), dp(1));
        item.setTag(slot);
        item.setClickable(false);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        item.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        item.addView(dockLabel(shortLabel(app.label)), new LinearLayout.LayoutParams(-1, dp(18)));
        return item;
    }

    private TextView dockLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(7);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        return label;
    }

    private String shortLabel(String label) {
        if (label == null) return "";
        return label.length() > 9 ? label.substring(0, 8) + "…" : label;
    }

    private void installDockTouchBehavior() {
        dock.setOnTouchListener((v, event) -> {
            if (dock.getChildCount() == 0 || dock.getWidth() <= 0) return true;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    magnifyDockAt(event.getX());
                    return true;

                case MotionEvent.ACTION_MOVE:
                    magnifyDockAt(event.getX());
                    return true;

                case MotionEvent.ACTION_UP:
                    int childIndex = dockIndexAt(event.getX());
                    restoreDockScale();

                    if (childIndex >= 0 && childIndex < dock.getChildCount()) {
                        View child = dock.getChildAt(childIndex);
                        Object tag = child.getTag();
                        if (tag instanceof Integer) {
                            AppEntry app = getDockApp((Integer) tag);
                            if (app != null) openApp(app);
                        }
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    restoreDockScale();
                    return true;

                default:
                    return true;
            }
        });
    }

    private int dockIndexAt(float x) {
        int count = dock.getChildCount();
        if (count <= 0 || dock.getWidth() <= 0) return -1;

        int index = (int) (x / ((float) dock.getWidth() / count));
        if (index < 0) index = 0;
        if (index >= count) index = count - 1;
        return index;
    }

    private void magnifyDockAt(float x) {
        int count = dock.getChildCount();
        if (count <= 0) return;

        float cell = dock.getWidth() / (float) count;
        for (int i = 0; i < count; i++) {
            View child = dock.getChildAt(i);
            float center = cell * (i + 0.5f);
            float distance = Math.abs(x - center) / Math.max(1f, cell);

            float scale;
            if (distance < 0.55f) scale = 1.42f;
            else if (distance < 1.25f) scale = 1.20f;
            else scale = 1.0f;

            child.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationY(scale > 1f ? -dp(6) : 0)
                    .setDuration(70)
                    .start();
        }
    }

    private void restoreDockScale() {
        for (int i = 0; i < dock.getChildCount(); i++) {
            dock.getChildAt(i)
                    .animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(120)
                    .start();
        }
    }

    private void showDockManager() {
        String[] rows = new String[DOCK_SLOTS];
        for (int i = 0; i < DOCK_SLOTS; i++) {
            AppEntry app = getDockApp(i);
            rows[i] = "Posición " + (i + 1) + " · " + (app == null ? "Vacía" : app.label);
        }

        new AlertDialog.Builder(this)
                .setTitle("Editar Dock")
                .setItems(rows, (dialog, which) -> chooseAppForDockSlot(which))
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void showHomeManager() {
        String[] rows = new String[HOME_SLOTS];
        for (int i = 0; i < HOME_SLOTS; i++) {
            AppEntry app = getHomeApp(i);
            rows[i] = "Espacio " + (i + 1) + " · " + (app == null ? "Vacío" : app.label);
        }

        new AlertDialog.Builder(this)
                .setTitle("Editar pantalla principal")
                .setItems(rows, (dialog, which) -> chooseAppForHomeSlot(which))
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void chooseAppForHomeSlot(int slot) {
        chooseAppForSlot("Elegir aplicación para Inicio",
                HOME_PREFIX, slot, this::renderHomeShortcuts);
    }

    private void chooseAppForDockSlot(int slot) {
        chooseAppForSlot("Elegir aplicación para Dock",
                DOCK_PREFIX, slot, this::renderDock);
    }

    private void chooseAppForSlot(String title, String prefix, int slot, Runnable afterSave) {
        String[] items = new String[allApps.size() + 1];
        items[0] = "Vaciar este espacio";
        for (int i = 0; i < allApps.size(); i++) items[i + 1] = allApps.get(i).label;

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items, (dialog, which) -> {
                    SharedPreferences.Editor editor =
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit();

                    if (which == 0) {
                        editor.putString(prefix + slot, "");
                    } else {
                        AppEntry app = allApps.get(which - 1);
                        editor.putString(prefix + slot,
                                app.packageName + "\n" + app.activityName);
                    }
                    editor.apply();
                    afterSave.run();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private int firstFreeSlot(String prefix, int slots) {
        for (int i = 0; i < slots; i++) {
            AppEntry app = prefix.equals(DOCK_PREFIX) ? getDockApp(i) : getHomeApp(i);
            if (app == null) return i;
        }
        return -1;
    }

    private void saveAppToSlot(String prefix, int slot, AppEntry app) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(prefix + slot, app.packageName + "\n" + app.activityName)
                .apply();
    }

    private AppEntry getHomeApp(int slot) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String key = HOME_PREFIX + slot;

        if (!prefs.contains(key) && prefs.contains(OLD_FAVORITE_PREFIX + slot)) {
            String old = prefs.getString(OLD_FAVORITE_PREFIX + slot, "");
            prefs.edit().putString(key, old == null ? "" : old).apply();
        }

        String defaultPkg =
                slot == 0 ? "com.whatsapp"
                        : slot == 1 ? "com.android.chrome"
                        : slot == 2 ? "com.google.android.youtube"
                        : null;

        return getSavedApp(HOME_PREFIX, slot, defaultPkg);
    }

    private AppEntry getDockApp(int slot) {
        String defaultPkg =
                slot == 0 ? "com.whatsapp"
                        : slot == 1 ? "com.android.chrome"
                        : slot == 2 ? "com.google.android.youtube"
                        : null;

        return getSavedApp(DOCK_PREFIX, slot, defaultPkg);
    }

    private AppEntry getSavedApp(String prefix, int slot, String defaultPkg) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String key = prefix + slot;

        if (!prefs.contains(key)) {
            return defaultPkg == null ? null : findByPackage(defaultPkg);
        }

        String raw = prefs.getString(key, "");
        if (raw == null || raw.isEmpty()) return null;

        String[] parts = raw.split("\n", 2);
        if (parts.length == 2) {
            AppEntry exact = findByComponent(parts[0], parts[1]);
            if (exact != null) return exact;
        }
        return findByPackage(parts[0]);
    }

    private void renderApps(String query) {
        if (appsGrid == null) return;
        appsGrid.removeAllViews();

        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (AppEntry app : allApps) {
            if (!q.isEmpty() && !app.label.toLowerCase(Locale.ROOT).contains(q)) continue;
            appsGrid.addView(makeAppTile(app));
        }
    }

    private View makeAppTile(AppEntry app) {
        LinearLayout box = baseTile(86);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        box.addView(tileLabel(app.label), new LinearLayout.LayoutParams(-1, dp(27)));

        box.setOnClickListener(v -> openApp(app));
        box.setOnLongClickListener(v -> {
            showAddAppDialog(app);
            return true;
        });
        return box;
    }

    private void showAddAppDialog(AppEntry app) {
        String[] options = {"Agregar a pantalla principal", "Agregar al Dock"};

        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        chooseDestinationForApp(app, HOME_PREFIX, HOME_SLOTS, "Pantalla principal");
                    } else {
                        chooseDestinationForApp(app, DOCK_PREFIX, DOCK_SLOTS, "Dock");
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void chooseDestinationForApp(AppEntry app, String prefix, int slots, String title) {
        String[] rows = new String[slots];
        for (int i = 0; i < slots; i++) {
            AppEntry existing = prefix.equals(DOCK_PREFIX) ? getDockApp(i) : getHomeApp(i);
            rows[i] = "Posición " + (i + 1) + " · "
                    + (existing == null ? "Vacía" : existing.label);
        }

        new AlertDialog.Builder(this)
                .setTitle("Agregar a " + title)
                .setItems(rows, (d, which) -> {
                    saveAppToSlot(prefix, which, app);
                    if (prefix.equals(DOCK_PREFIX)) renderDock();
                    else renderHomeShortcuts();
                    Toast.makeText(this, app.label + " agregada", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private TextView tileLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(9);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        return label;
    }

    private LinearLayout baseTile(int heightDp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(2), dp(4), dp(2), dp(2));

        GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
        gp.width = 0;
        gp.height = dp(heightDp);
        gp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        gp.setMargins(dp(1), dp(1), dp(1), dp(1));
        box.setLayoutParams(gp);
        return box;
    }

    private void showIrvingSettings() {
        String[] items = {
                "Editar pantalla principal",
                "Editar Dock",
                "Cambiar imagen de fondo",
                "Quitar imagen de fondo",
                "Activar rotación completa de pantalla externa",
                "Ajustes del teléfono"
        };

        new AlertDialog.Builder(this)
                .setTitle("Irving OS")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showHomeManager();
                    else if (which == 1) showDockManager();
                    else if (which == 2) pickWallpaper();
                    else if (which == 3) clearWallpaper();
                    else if (which == 4) enableForcedRotation();
                    else if (which == 5) openSettings();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void pickWallpaper() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_WALLPAPER);
    }

    public void clearWallpaper() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .remove(WALLPAPER_URI)
                .apply();

        if (wallpaperView != null) {
            wallpaperView.setImageDrawable(null);
            wallpaperView.setBackgroundColor(Color.BLACK);
        }
    }

    private void applySavedWallpaper() {
        if (wallpaperView == null) return;

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(WALLPAPER_URI, "");

        if (saved == null || saved.isEmpty()) {
            wallpaperView.setImageDrawable(null);
            wallpaperView.setBackgroundColor(Color.BLACK);
            return;
        }

        try {
            wallpaperView.setImageURI(Uri.parse(saved));
        } catch (Exception e) {
            clearWallpaper();
        }
    }

    private void enableForcedRotation() {
        if (!RotationController.isShizukuRunning()) {
            Toast.makeText(this,
                    "Para girar también todas las aplicaciones, inicia Shizuku y vuelve a pulsar esta opción.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (!RotationController.hasShizukuPermission()) {
            try {
                Shizuku.requestPermission(REQUEST_SHIZUKU);
            } catch (Throwable e) {
                Toast.makeText(this,
                        "No se pudo solicitar el permiso de Shizuku",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        RotationController.startRotationEnforcer(this);
        Toast.makeText(this,
                "Rotación completa de la pantalla externa activada",
                Toast.LENGTH_SHORT).show();
    }

    private void loadLauncherApps() {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> results = pm.queryIntentActivities(intent, 0);
        String self = getPackageName();
        Set<String> seen = new LinkedHashSet<>();

        for (ResolveInfo r : results) {
            if (r.activityInfo == null || r.activityInfo.packageName == null) continue;
            if (self.equals(r.activityInfo.packageName)) continue;

            String key = r.activityInfo.packageName + "/" + r.activityInfo.name;
            if (!seen.add(key)) continue;

            CharSequence labelCs = r.loadLabel(pm);
            String label =
                    labelCs == null ? r.activityInfo.packageName : labelCs.toString();
            Drawable icon = r.loadIcon(pm);

            allApps.add(new AppEntry(
                    label,
                    r.activityInfo.packageName,
                    r.activityInfo.name,
                    icon
            ));
        }

        final Collator collator = Collator.getInstance(new Locale("es", "MX"));
        Collections.sort(allApps, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return collator.compare(a.label, b.label);
            }
        });
    }

    private AppEntry findByPackage(String packageName) {
        for (AppEntry app : allApps) {
            if (app.packageName.equals(packageName)) return app;
        }
        return null;
    }

    private AppEntry findByComponent(String packageName, String activityName) {
        for (AppEntry app : allApps) {
            if (app.packageName.equals(packageName)
                    && app.activityName.equals(activityName)) {
                return app;
            }
        }
        return null;
    }

    private void openApp(AppEntry app) {
        RotationController.startRotationEnforcer(this);

        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setClassName(app.packageName, app.activityName);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(COVER_DISPLAY_ID)
                    .toBundle();

            startActivity(i, options);
        } catch (Exception e) {
            try {
                Intent fallback = getPackageManager()
                        .getLaunchIntentForPackage(app.packageName);

                if (fallback != null) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

                    Bundle options = ActivityOptions.makeBasic()
                            .setLaunchDisplayId(COVER_DISPLAY_ID)
                            .toBundle();

                    startActivity(fallback, options);
                    return;
                }
            } catch (Exception ignored) {}

            Toast.makeText(this,
                    "No se pudo abrir " + app.label,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings() {
        RotationController.startRotationEnforcer(this);
        try {
            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(COVER_DISPLAY_ID)
                    .toBundle();
            startActivity(new Intent(Settings.ACTION_SETTINGS), options);
        } catch (Exception e) {
            Toast.makeText(this,
                    "No se pudieron abrir Ajustes",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static class AppEntry {
        final String label;
        final String packageName;
        final String activityName;
        final Drawable icon;

        AppEntry(String label, String packageName, String activityName, Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.activityName = activityName;
            this.icon = icon;
        }
    }
}
