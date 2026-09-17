package com.marilu.miniflip;

import android.animation.LayoutTransition;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
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
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
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
    private static final long DOCK_DRAG_HOLD_MS = 800L;

    private static final String PREFS = "miniflip_prefs";
    private static final String WALLPAPER_URI = "wallpaper_uri";
    private static final String HOME_PREFIX = "home_";
    private static final String DOCK_PREFIX = "dock_";
    private static final String OLD_FAVORITE_PREFIX = "favorite_";
    private static final String DOCK_SIZE_PREF = "dock_icon_size";
    private static final String DOCK_MAGNIFY_PREF = "dock_magnify";

    private final List<AppEntry> allApps = new ArrayList<>();
    private final Handler gestureHandler = new Handler(Looper.getMainLooper());
    private final DecelerateInterpolator dockInterpolator = new DecelerateInterpolator();

    private FrameLayout content;
    private GestureHomeLayout homePage;
    private FrameLayout appsPage;
    private GridLayout homeGrid;
    private GridLayout appsGrid;
    private LinearLayout dock;
    private EditText search;
    private ImageView wallpaperView;
    private TextView homeSettingsButton;
    private FrameLayout.LayoutParams dockLayoutParams;

    private int currentPage = PAGE_HOME;

    private float homeDownX;
    private float homeDownY;
    private boolean homeStartedOnInteractive;
    private boolean homeHoldTriggered;
    private boolean homeSwipeTriggered;

    private int dockDownChildIndex = -1;
    private float dockDownX;
    private float dockDownY;
    private float dockLastX;
    private float dockLastY;
    private boolean dockDragging;

    private final Runnable homeHoldRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentPage != PAGE_HOME || homeStartedOnInteractive) return;
            homeHoldTriggered = true;
            showHomeEditMenu();
        }
    };

    private final Runnable dockLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (dock == null || dockDownChildIndex < 0
                    || dockDownChildIndex >= dock.getChildCount()) return;

            int current = dockHitIndex(dockLastX, dockLastY);
            if (current != dockDownChildIndex) return;

            View child = dock.getChildAt(dockDownChildIndex);
            Object tag = child.getTag();
            if (!(tag instanceof Integer)) return;

            restoreDockScale();
            dockDragging = startItemDrag(child, DOCK_PREFIX, (Integer) tag);
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
        // Irving OS always starts on Home. Apps is an internal drawer opened by gesture.
        showPage(PAGE_HOME);
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            content.postDelayed(this::showIrvingSettings, 180);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        RotationController.startRotationEnforcer(this);
        if (currentPage == PAGE_HOME) {
            renderHomeShortcuts();
            renderDock();
        }
    }

    @Override
    protected void onDestroy() {
        gestureHandler.removeCallbacks(homeHoldRunnable);
        gestureHandler.removeCallbacks(dockLongPressRunnable);
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
        scrim.setBackgroundColor(Color.argb(44, 0, 0, 0));
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

    private GestureHomeLayout buildHomePage() {
        GestureHomeLayout page = new GestureHomeLayout(this);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setClickable(true);

        TextView title = new TextView(this);
        title.setText("Irving OS");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setShadowLayer(5f, 0f, 1f, Color.BLACK);
        FrameLayout.LayoutParams titleLp =
                new FrameLayout.LayoutParams(dp(130), dp(34), Gravity.TOP | Gravity.START);
        titleLp.leftMargin = dp(11);
        titleLp.topMargin = dp(5);
        page.addView(title, titleLp);

        homeGrid = new GridLayout(this);
        homeGrid.setColumnCount(4);
        homeGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        homeGrid.setUseDefaultMargins(false);
        homeGrid.setClipChildren(false);
        homeGrid.setClipToPadding(false);
        homeGrid.setLayoutTransition(makeLayoutTransition());
        homeGrid.setOnDragListener((v, event) ->
                handleDragTarget(HOME_PREFIX, HOME_SLOTS, homeGrid, event));

        FrameLayout.LayoutParams gridLp =
                new FrameLayout.LayoutParams(-1, dp(176), Gravity.TOP);
        gridLp.leftMargin = dp(7);
        gridLp.rightMargin = dp(7);
        gridLp.topMargin = dp(42);
        page.addView(homeGrid, gridLp);
        renderHomeShortcuts();

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(7), dp(3), dp(7), dp(3));
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setClickable(true);
        dock.setLayoutTransition(makeLayoutTransition());
        dock.setBackground(rounded(Color.argb(202, 37, 39, 48), 17));
        dock.setOnDragListener((v, event) ->
                handleDragTarget(DOCK_PREFIX, DOCK_SLOTS, dock, event));

        dockLayoutParams = new FrameLayout.LayoutParams(
                -2,
                dp(60),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        dockLayoutParams.bottomMargin = dp(44);
        page.addView(dock, dockLayoutParams);
        renderDock();
        installDockTouchBehavior();

        homeSettingsButton = compactControl("⚙", 16);
        homeSettingsButton.setOnClickListener(v -> showIrvingSettings());
        FrameLayout.LayoutParams settingsLp = new FrameLayout.LayoutParams(
                dp(52),
                dp(28),
                Gravity.BOTTOM | Gravity.START
        );
        settingsLp.leftMargin = dp(8);
        settingsLp.bottomMargin = dp(8);
        page.addView(homeSettingsButton, settingsLp);

        return page;
    }

    private LayoutTransition makeLayoutTransition() {
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(LayoutTransition.APPEARING, 120);
        transition.setDuration(LayoutTransition.DISAPPEARING, 90);
        transition.setDuration(LayoutTransition.CHANGE_APPEARING, 140);
        transition.setDuration(LayoutTransition.CHANGE_DISAPPEARING, 140);
        transition.enableTransitionType(LayoutTransition.CHANGING);
        return transition;
    }

    private boolean handleHomeGesture(MotionEvent event) {
        if (currentPage != PAGE_HOME) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                homeDownX = event.getX();
                homeDownY = event.getY();
                homeHoldTriggered = false;
                homeSwipeTriggered = false;
                homeStartedOnInteractive = isHomeInteractiveAt(event.getRawX(), event.getRawY());
                gestureHandler.removeCallbacks(homeHoldRunnable);
                if (!homeStartedOnInteractive) {
                    gestureHandler.postDelayed(homeHoldRunnable, HOME_HOLD_MS);
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                if (homeStartedOnInteractive || homeHoldTriggered) return false;

                float dx = event.getX() - homeDownX;
                float dy = event.getY() - homeDownY;

                if (Math.abs(dx) > dp(16) || Math.abs(dy) > dp(16)) {
                    gestureHandler.removeCallbacks(homeHoldRunnable);
                }

                if (!homeSwipeTriggered
                        && dy < -dp(48)
                        && Math.abs(dx) < dp(85)) {
                    homeSwipeTriggered = true;
                    gestureHandler.removeCallbacks(homeHoldRunnable);
                    showPage(PAGE_APPS);
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                gestureHandler.removeCallbacks(homeHoldRunnable);
                return homeHoldTriggered || homeSwipeTriggered;

            default:
                return false;
        }
    }

    private boolean isHomeInteractiveAt(float rawX, float rawY) {
        if (homeSettingsButton != null && rawPointInside(homeSettingsButton, rawX, rawY)) {
            return true;
        }

        if (dock != null && dock.getVisibility() == View.VISIBLE
                && rawPointInside(dock, rawX, rawY)) {
            return true;
        }

        if (homeGrid != null) {
            for (int i = 0; i < homeGrid.getChildCount(); i++) {
                View child = homeGrid.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE && rawPointInside(child, rawX, rawY)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean rawPointInside(View view, float rawX, float rawY) {
        if (view == null || !view.isShown()) return false;
        Rect rect = new Rect();
        if (!view.getGlobalVisibleRect(rect)) return false;
        return rect.contains(Math.round(rawX), Math.round(rawY));
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
        header.addView(back, new LinearLayout.LayoutParams(dp(54), dp(32)));

        TextView label = new TextView(this);
        label.setText("Aplicaciones");
        label.setTextColor(Color.WHITE);
        label.setTextSize(15);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        header.addView(label, new LinearLayout.LayoutParams(0, dp(34), 1f));

        TextView settings = compactControl("⚙", 16);
        settings.setOnClickListener(v -> showIrvingSettings());
        header.addView(settings, new LinearLayout.LayoutParams(dp(42), dp(32)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(38)));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Buscar aplicación");
        search.setHintTextColor(Color.LTGRAY);
        search.setTextColor(Color.WHITE);
        search.setTextSize(12);
        search.setPadding(dp(11), 0, dp(11), 0);
        search.setBackground(rounded(Color.argb(215, 25, 26, 32), 13));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(38));
        searchLp.setMargins(0, dp(3), 0, dp(3));
        root.addView(search, searchLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(82));

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
        } else if (search != null) {
            search.clearFocus();
        }

        applyImmersiveMode();
    }

    @Override
    public void onBackPressed() {
        if (currentPage == PAGE_APPS) {
            showPage(PAGE_HOME);
            return;
        }
        super.onBackPressed();
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

        List<AppEntry> apps = getPackedSlotApps(HOME_PREFIX, HOME_SLOTS);
        for (int i = 0; i < apps.size(); i++) {
            homeGrid.addView(makeHomeTile(apps.get(i), i));
        }
    }

    private View makeHomeTile(AppEntry app, int packedIndex) {
        LinearLayout box = baseTile(82);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        box.addView(tileLabel(app.label), new LinearLayout.LayoutParams(-1, dp(24)));

        box.setOnClickListener(v -> openApp(app));
        box.setOnLongClickListener(v -> startItemDrag(v, HOME_PREFIX, packedIndex));
        return box;
    }

    private void renderDock() {
        if (dock == null) return;
        dock.removeAllViews();

        int iconDp = getDockIconSizeDp();
        int itemWidth = iconDp + 14;
        int itemHeight = iconDp + 21;

        List<AppEntry> apps = getPackedSlotApps(DOCK_PREFIX, DOCK_SLOTS);
        for (int i = 0; i < apps.size(); i++) {
            View item = makeDockItem(apps.get(i), i, iconDp);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(dp(itemWidth), dp(itemHeight));
            lp.setMargins(dp(1), 0, dp(1), 0);
            dock.addView(item, lp);
        }

        dock.setVisibility(apps.isEmpty() ? View.GONE : View.VISIBLE);

        if (dockLayoutParams != null) {
            dockLayoutParams.height = dp(iconDp + 27);
            dock.setLayoutParams(dockLayoutParams);
        }
    }

    private View makeDockItem(AppEntry app, int packedIndex, int iconDp) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(1), dp(2), dp(1), dp(1));
        item.setTag(packedIndex);
        item.setClickable(false);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        item.addView(icon, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
        item.addView(dockLabel(shortLabel(app.label)), new LinearLayout.LayoutParams(-1, dp(17)));
        return item;
    }

    private TextView dockLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(7);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setShadowLayer(3f, 0f, 1f, Color.BLACK);
        return label;
    }

    private String shortLabel(String label) {
        if (label == null) return "";
        return label.length() > 9 ? label.substring(0, 8) + "…" : label;
    }

    private int getDockIconSizeDp() {
        int option = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(DOCK_SIZE_PREF, 1);
        switch (option) {
            case 0: return 30;
            case 2: return 42;
            case 3: return 48;
            default: return 36;
        }
    }

    private float getDockMaxScale() {
        int option = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(DOCK_MAGNIFY_PREF, 1);
        switch (option) {
            case 0: return 1.28f;
            case 2: return 1.70f;
            case 3: return 1.92f;
            default: return 1.48f;
        }
    }

    private void installDockTouchBehavior() {
        dock.setOnTouchListener((v, event) -> {
            if (dock.getChildCount() == 0 || dock.getWidth() <= 0) return true;

            dockLastX = event.getX();
            dockLastY = event.getY();

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dockDragging = false;
                    dockDownX = event.getX();
                    dockDownY = event.getY();
                    dockDownChildIndex = dockHitIndex(event.getX(), event.getY());
                    gestureHandler.removeCallbacks(dockLongPressRunnable);

                    if (dockDownChildIndex >= 0) {
                        magnifyDockAt(event.getX(), event.getY());
                        gestureHandler.postDelayed(dockLongPressRunnable, DOCK_DRAG_HOLD_MS);
                    } else {
                        restoreDockScale();
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - dockDownX) > dp(10)
                            || Math.abs(event.getY() - dockDownY) > dp(10)) {
                        gestureHandler.removeCallbacks(dockLongPressRunnable);
                    }

                    if (!dockDragging) {
                        int hover = dockHitIndex(event.getX(), event.getY());
                        if (hover >= 0) {
                            magnifyDockAt(event.getX(), event.getY());
                        } else {
                            restoreDockScale();
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    gestureHandler.removeCallbacks(dockLongPressRunnable);
                    int childIndex = dockHitIndex(event.getX(), event.getY());
                    boolean wasDragging = dockDragging;
                    dockDragging = false;
                    restoreDockScale();

                    if (!wasDragging && childIndex >= 0
                            && childIndex < dock.getChildCount()) {
                        List<AppEntry> apps = getPackedSlotApps(DOCK_PREFIX, DOCK_SLOTS);
                        if (childIndex < apps.size()) {
                            openApp(apps.get(childIndex));
                        }
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    gestureHandler.removeCallbacks(dockLongPressRunnable);
                    dockDragging = false;
                    restoreDockScale();
                    return true;

                default:
                    return true;
            }
        });
    }

    private int dockHitIndex(float x, float y) {
        if (dock == null) return -1;
        for (int i = 0; i < dock.getChildCount(); i++) {
            View child = dock.getChildAt(i);
            float left = child.getLeft();
            float right = child.getRight();
            float top = child.getTop();
            float bottom = child.getBottom();
            if (x >= left && x <= right && y >= top && y <= bottom) {
                return i;
            }
        }
        return -1;
    }

    private void magnifyDockAt(float x, float y) {
        int active = dockHitIndex(x, y);
        if (active < 0) {
            restoreDockScale();
            return;
        }

        float maxScale = getDockMaxScale();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View child = dock.getChildAt(i);
            int distance = Math.abs(i - active);
            float scale;
            if (distance == 0) scale = maxScale;
            else if (distance == 1) scale = 1f + ((maxScale - 1f) * 0.42f);
            else scale = 1f;

            child.animate().cancel();
            child.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationY(scale > 1f ? -dp(5) : 0f)
                    .setInterpolator(dockInterpolator)
                    .setDuration(55)
                    .start();
        }
    }

    private void restoreDockScale() {
        if (dock == null) return;
        for (int i = 0; i < dock.getChildCount(); i++) {
            View child = dock.getChildAt(i);
            child.animate().cancel();
            child.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setInterpolator(dockInterpolator)
                    .setDuration(95)
                    .start();
        }
    }

    private boolean startItemDrag(View source, String prefix, int packedIndex) {
        if (source == null) return false;
        DragPayload payload = new DragPayload(prefix, packedIndex, source);
        ClipData clip = ClipData.newPlainText(
                "Irving OS",
                prefix + ":" + packedIndex
        );
        boolean started = source.startDragAndDrop(
                clip,
                new View.DragShadowBuilder(source),
                payload,
                0
        );
        if (started) source.setAlpha(0.55f);
        return started;
    }

    private boolean handleDragTarget(String targetPrefix,
                                     int targetSlots,
                                     View targetView,
                                     DragEvent event) {
        Object state = event.getLocalState();
        if (!(state instanceof DragPayload)) {
            return event.getAction() != DragEvent.ACTION_DRAG_STARTED;
        }

        DragPayload payload = (DragPayload) state;

        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;

            case DragEvent.ACTION_DRAG_ENTERED:
                targetView.setAlpha(0.96f);
                return true;

            case DragEvent.ACTION_DRAG_EXITED:
                targetView.setAlpha(1f);
                return true;

            case DragEvent.ACTION_DROP:
                int targetIndex = targetIndexForDrop(
                        targetPrefix,
                        event.getX(),
                        event.getY()
                );
                moveDraggedItem(payload, targetPrefix, targetSlots, targetIndex);
                targetView.setAlpha(1f);
                return true;

            case DragEvent.ACTION_DRAG_ENDED:
                targetView.setAlpha(1f);
                payload.sourceView.setAlpha(1f);
                dockDragging = false;
                restoreDockScale();
                return true;

            default:
                return true;
        }
    }

    private int targetIndexForDrop(String prefix, float x, float y) {
        List<AppEntry> apps = getPackedSlotApps(
                prefix,
                prefix.equals(DOCK_PREFIX) ? DOCK_SLOTS : HOME_SLOTS
        );

        if (prefix.equals(DOCK_PREFIX)) {
            if (dock == null || dock.getChildCount() == 0) return 0;
            for (int i = 0; i < dock.getChildCount(); i++) {
                View child = dock.getChildAt(i);
                float center = (child.getLeft() + child.getRight()) / 2f;
                if (x < center) return i;
            }
            return Math.min(apps.size(), DOCK_SLOTS);
        }

        if (homeGrid == null || homeGrid.getWidth() <= 0) return apps.size();
        float cellWidth = homeGrid.getWidth() / 4f;
        int col = Math.max(0, Math.min(3, (int) (x / Math.max(1f, cellWidth))));
        int row = Math.max(0, (int) (y / Math.max(1, dp(82))));
        int index = row * 4 + col;
        return Math.max(0, Math.min(index, apps.size()));
    }

    private void moveDraggedItem(DragPayload payload,
                                 String targetPrefix,
                                 int targetSlots,
                                 int targetIndex) {
        int sourceSlots = payload.prefix.equals(DOCK_PREFIX) ? DOCK_SLOTS : HOME_SLOTS;
        List<AppEntry> sourceApps = getPackedSlotApps(payload.prefix, sourceSlots);

        if (payload.index < 0 || payload.index >= sourceApps.size()) return;
        AppEntry moved = sourceApps.remove(payload.index);

        if (payload.prefix.equals(targetPrefix)) {
            int insert = Math.max(0, Math.min(targetIndex, sourceApps.size()));
            sourceApps.add(insert, moved);
            savePackedSlotApps(payload.prefix, sourceSlots, sourceApps);
        } else {
            List<AppEntry> targetApps = getPackedSlotApps(targetPrefix, targetSlots);
            int insert = Math.max(0, Math.min(targetIndex, targetApps.size()));
            AppEntry displaced = null;

            if (targetApps.size() >= targetSlots) {
                int removeIndex = targetSlots - 1;
                displaced = targetApps.remove(removeIndex);
                if (insert > targetApps.size()) insert = targetApps.size();
            }

            targetApps.add(insert, moved);
            while (targetApps.size() > targetSlots) {
                displaced = targetApps.remove(targetApps.size() - 1);
            }

            if (displaced != null && sourceApps.size() < sourceSlots) {
                int returnAt = Math.max(0, Math.min(payload.index, sourceApps.size()));
                sourceApps.add(returnAt, displaced);
            }

            savePackedSlotApps(payload.prefix, sourceSlots, sourceApps);
            savePackedSlotApps(targetPrefix, targetSlots, targetApps);
        }

        renderHomeShortcuts();
        renderDock();
        refreshWidgets();
    }

    private void showHomeEditMenu() {
        String[] items = {
                "Agregar aplicación al Inicio",
                "Agregar aplicación al Dock",
                "Editar iconos del Inicio",
                "Editar Dock",
                "Ajustes de Irving OS"
        };

        new AlertDialog.Builder(this)
                .setTitle("Personalizar Irving OS")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) chooseAppAndAppend(HOME_PREFIX, HOME_SLOTS, "Agregar al Inicio");
                    else if (which == 1) chooseAppAndAppend(DOCK_PREFIX, DOCK_SLOTS, "Agregar al Dock");
                    else if (which == 2) showHomeManager();
                    else if (which == 3) showDockManager();
                    else if (which == 4) showIrvingSettings();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void chooseAppAndAppend(String prefix, int slots, String title) {
        List<AppEntry> current = getPackedSlotApps(prefix, slots);
        if (current.size() >= slots) {
            Toast.makeText(this,
                    prefix.equals(DOCK_PREFIX)
                            ? "El Dock está lleno. Mantén un icono pulsado para moverlo o edítalo en Ajustes."
                            : "La pantalla principal está llena. Mantén un icono pulsado para moverlo o edítala en Ajustes.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        String[] items = new String[allApps.size()];
        for (int i = 0; i < allApps.size(); i++) items[i] = allApps.get(i).label;

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items, (dialog, which) -> {
                    AppEntry selected = allApps.get(which);
                    current.add(selected);
                    savePackedSlotApps(prefix, slots, current);
                    renderHomeShortcuts();
                    renderDock();
                    refreshWidgets();
                })
                .setNegativeButton("Cancelar", null)
                .show();
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
                HOME_PREFIX, HOME_SLOTS, slot, this::renderHomeShortcuts);
    }

    private void chooseAppForDockSlot(int slot) {
        chooseAppForSlot("Elegir aplicación para Dock",
                DOCK_PREFIX, DOCK_SLOTS, slot, this::renderDock);
    }

    private void chooseAppForSlot(String title,
                                  String prefix,
                                  int slots,
                                  int slot,
                                  Runnable afterSave) {
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

                    List<AppEntry> packed = getPackedSlotApps(prefix, slots);
                    savePackedSlotApps(prefix, slots, packed);
                    afterSave.run();
                    refreshWidgets();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private List<AppEntry> getPackedSlotApps(String prefix, int slots) {
        List<AppEntry> result = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            AppEntry app = prefix.equals(DOCK_PREFIX) ? getDockApp(i) : getHomeApp(i);
            if (app != null) result.add(app);
        }
        return result;
    }

    private void savePackedSlotApps(String prefix, int slots, List<AppEntry> apps) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        for (int i = 0; i < slots; i++) {
            if (i < apps.size()) {
                AppEntry app = apps.get(i);
                editor.putString(prefix + i, app.packageName + "\n" + app.activityName);
            } else {
                editor.putString(prefix + i, "");
            }
        }
        editor.apply();
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
        LinearLayout box = baseTile(84);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        box.addView(tileLabel(app.label), new LinearLayout.LayoutParams(-1, dp(26)));

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
                        addSpecificApp(app, HOME_PREFIX, HOME_SLOTS, "Inicio");
                    } else {
                        addSpecificApp(app, DOCK_PREFIX, DOCK_SLOTS, "Dock");
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void addSpecificApp(AppEntry app, String prefix, int slots, String where) {
        List<AppEntry> current = getPackedSlotApps(prefix, slots);
        if (current.size() >= slots) {
            Toast.makeText(this, where + " está lleno", Toast.LENGTH_SHORT).show();
            return;
        }
        current.add(app);
        savePackedSlotApps(prefix, slots, current);
        renderHomeShortcuts();
        renderDock();
        refreshWidgets();
        Toast.makeText(this, app.label + " agregada a " + where, Toast.LENGTH_SHORT).show();
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
                "Tamaño de iconos del Dock",
                "Ampliación del Dock al tocar",
                "Cambiar imagen de fondo",
                "Restaurar fondo predeterminado",
                "Activar rotación completa de pantalla externa",
                "Ajustes del teléfono"
        };

        new AlertDialog.Builder(this)
                .setTitle("Irving OS")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showHomeManager();
                    else if (which == 1) showDockManager();
                    else if (which == 2) showDockSizeDialog();
                    else if (which == 3) showDockMagnifyDialog();
                    else if (which == 4) pickWallpaper();
                    else if (which == 5) clearWallpaper();
                    else if (which == 6) enableForcedRotation();
                    else if (which == 7) openSettings();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void showDockSizeDialog() {
        String[] sizes = {"Pequeños", "Medianos", "Grandes", "Muy grandes"};
        new AlertDialog.Builder(this)
                .setTitle("Tamaño de iconos del Dock")
                .setItems(sizes, (dialog, which) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit()
                            .putInt(DOCK_SIZE_PREF, which)
                            .apply();
                    renderDock();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showDockMagnifyDialog() {
        String[] sizes = {"Suave", "Normal", "Grande", "Máxima"};
        new AlertDialog.Builder(this)
                .setTitle("Ampliación del Dock")
                .setItems(sizes, (dialog, which) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit()
                            .putInt(DOCK_MAGNIFY_PREF, which)
                            .apply();
                    restoreDockScale();
                })
                .setNegativeButton("Cancelar", null)
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
        applySavedWallpaper();
    }

    private void applySavedWallpaper() {
        if (wallpaperView == null) return;

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(WALLPAPER_URI, "");

        if (saved == null || saved.isEmpty()) {
            try {
                wallpaperView.setImageResource(R.drawable.irving_default_bg);
            } catch (Exception e) {
                wallpaperView.setImageDrawable(null);
                wallpaperView.setBackgroundColor(Color.BLACK);
            }
            return;
        }

        try {
            wallpaperView.setImageURI(Uri.parse(saved));
        } catch (Exception e) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .remove(WALLPAPER_URI)
                    .apply();
            try {
                wallpaperView.setImageResource(R.drawable.irving_default_bg);
            } catch (Exception ignored) {
                wallpaperView.setImageDrawable(null);
            }
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
                "Rotación completa activada. Ahora Irving OS también ignora la orientación fija de otras apps en la pantalla externa.",
                Toast.LENGTH_LONG).show();
    }

    private void refreshWidgets() {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(this);
            ComponentName provider = new ComponentName(this, MiniFlipWidgetProvider.class);
            int[] ids = manager.getAppWidgetIds(provider);
            if (ids != null && ids.length > 0) {
                new MiniFlipWidgetProvider().onUpdate(this, manager, ids);
            }
        } catch (Exception ignored) {}
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
            String label = labelCs == null ? r.activityInfo.packageName : labelCs.toString();
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
        if (packageName == null) return null;
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
                Intent fallback = getPackageManager().getLaunchIntentForPackage(app.packageName);
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

    private class GestureHomeLayout extends FrameLayout {
        GestureHomeLayout(Context context) {
            super(context);
            setClickable(true);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            boolean consume = handleHomeGesture(event);
            if (consume) return true;
            return super.dispatchTouchEvent(event);
        }
    }

    private static class DragPayload {
        final String prefix;
        final int index;
        final View sourceView;

        DragPayload(String prefix, int index, View sourceView) {
            this.prefix = prefix;
            this.index = index;
            this.sourceView = sourceView;
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
