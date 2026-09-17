package com.marilu.miniflip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
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

public class MainActivity extends Activity {
    public static final String EXTRA_PAGE = "miniflip_page";

    private static final int PAGE_HOME = 0;
    private static final int PAGE_APPS = 1;
    private static final int PAGE_RECENTS = 2;
    private static final int REQUEST_WALLPAPER = 7001;
    private static final int FAVORITE_SLOTS = 8;

    private static final String PREFS = "miniflip_prefs";
    private static final String RECENTS = "recents";
    private static final String WALLPAPER_URI = "wallpaper_uri";
    private static final String FAVORITE_PREFIX = "favorite_";

    private final List<AppEntry> allApps = new ArrayList<>();
    private FrameLayout content;
    private LinearLayout homePage;
    private LinearLayout appsPage;
    private LinearLayout recentsPage;
    private GridLayout favoritesGrid;
    private GridLayout appsGrid;
    private GridLayout recentsGrid;
    private EditText search;
    private ImageView wallpaperView;
    private TextView navHome;
    private TextView navApps;
    private TextView navRecents;
    private int currentPage = PAGE_HOME;

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
        loadLauncherApps();
        buildUi();
        showPage(resolveRequestedPage(getIntent()));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showPage(resolveRequestedPage(intent));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_WALLPAPER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(WALLPAPER_URI, uri.toString()).apply();
            applySavedWallpaper();
        }
    }

    private int resolveRequestedPage(Intent intent) {
        if (intent == null) return PAGE_HOME;
        int page = intent.getIntExtra(EXTRA_PAGE, PAGE_HOME);
        if (page < PAGE_HOME || page > PAGE_RECENTS) return PAGE_HOME;
        return page;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        if (currentPage == PAGE_RECENTS && recentsGrid != null) renderRecents();
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
        scrim.setBackgroundColor(Color.argb(105, 0, 0, 0));
        shell.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(6), dp(8), dp(5));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(4), 0);

        TextView title = new TextView(this);
        title.setText("MiniFlip OS");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(38), 1f));

        TextView editButton = makeHeaderButton("Editar", 11);
        editButton.setOnClickListener(v -> showFavoriteManager());
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(dp(58), dp(34));
        editParams.setMargins(0, 0, dp(5), 0);
        header.addView(editButton, editParams);

        TextView settingsButton = makeHeaderButton("⚙", 18);
        settingsButton.setOnClickListener(v -> showMiniFlipSettings());
        header.addView(settingsButton, new LinearLayout.LayoutParams(dp(38), dp(34)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(42)));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        homePage = buildHomePage();
        appsPage = buildAppsPage();
        recentsPage = buildRecentsPage();
        content.addView(homePage, new FrameLayout.LayoutParams(-1, -1));
        content.addView(appsPage, new FrameLayout.LayoutParams(-1, -1));
        content.addView(recentsPage, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(2), dp(2), 0, dp(2));

        navHome = makeNavButton("Inicio");
        navApps = makeNavButton("Apps");
        navRecents = makeNavButton("Rec.");
        navHome.setOnClickListener(v -> showPage(PAGE_HOME));
        navApps.setOnClickListener(v -> showPage(PAGE_APPS));
        navRecents.setOnClickListener(v -> showPage(PAGE_RECENTS));

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(dp(54), dp(31));
        p1.setMargins(0, 0, dp(4), 0);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(dp(50), dp(31));
        p2.setMargins(0, 0, dp(4), 0);
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(dp(54), dp(31));
        p3.setMargins(0, 0, dp(4), 0);

        nav.addView(navHome, p1);
        nav.addView(navApps, p2);
        nav.addView(navRecents, p3);
        nav.addView(new Space(this), new LinearLayout.LayoutParams(0, dp(31), 1f));
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(36)));

        shell.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(shell);
        applySavedWallpaper();
    }

    private LinearLayout buildHomePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(2), dp(2), dp(2), dp(1));

        TextView label = new TextView(this);
        label.setText("Favoritos");
        label.setTextColor(Color.WHITE);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(label, new LinearLayout.LayoutParams(-1, dp(26)));

        favoritesGrid = new GridLayout(this);
        favoritesGrid.setColumnCount(4);
        favoritesGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        favoritesGrid.setUseDefaultMargins(false);
        page.addView(favoritesGrid, new LinearLayout.LayoutParams(-1, dp(176)));
        renderFavorites();

        TextView tip = new TextView(this);
        tip.setText("Mantén pulsado un icono para cambiarlo");
        tip.setTextColor(Color.LTGRAY);
        tip.setTextSize(9);
        tip.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        page.addView(tip, new LinearLayout.LayoutParams(-1, 0, 1f));
        return page;
    }

    private LinearLayout buildAppsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Buscar aplicación");
        search.setHintTextColor(Color.LTGRAY);
        search.setTextColor(Color.WHITE);
        search.setTextSize(13);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackgroundColor(Color.argb(205, 28, 28, 34));
        page.addView(search, new LinearLayout.LayoutParams(-1, dp(40)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        appsGrid = new GridLayout(this);
        appsGrid.setColumnCount(4);
        appsGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        appsGrid.setUseDefaultMargins(false);
        scroll.addView(appsGrid, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

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

    private LinearLayout buildRecentsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(this);
        label.setText("Abiertas desde MiniFlip");
        label.setTextColor(Color.WHITE);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(label, new LinearLayout.LayoutParams(-1, dp(26)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        recentsGrid = new GridLayout(this);
        recentsGrid.setColumnCount(4);
        recentsGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        recentsGrid.setUseDefaultMargins(false);
        scroll.addView(recentsGrid, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        renderRecents();
        return page;
    }

    private void showPage(int page) {
        currentPage = page;
        if (homePage != null) homePage.setVisibility(page == PAGE_HOME ? View.VISIBLE : View.GONE);
        if (appsPage != null) appsPage.setVisibility(page == PAGE_APPS ? View.VISIBLE : View.GONE);
        if (recentsPage != null) recentsPage.setVisibility(page == PAGE_RECENTS ? View.VISIBLE : View.GONE);
        if (page == PAGE_HOME) renderFavorites();
        if (page == PAGE_RECENTS) renderRecents();
        updateNav();
        applyImmersiveMode();
    }

    private void updateNav() {
        if (navHome == null) return;
        styleNav(navHome, currentPage == PAGE_HOME);
        styleNav(navApps, currentPage == PAGE_APPS);
        styleNav(navRecents, currentPage == PAGE_RECENTS);
    }

    private void styleNav(TextView v, boolean selected) {
        v.setTextColor(Color.WHITE);
        v.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        v.setBackgroundColor(selected ? Color.argb(235, 45, 45, 56) : Color.argb(205, 22, 22, 28));
    }

    private TextView makeNavButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(10);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(2), 0, dp(2), 0);
        return v;
    }

    private TextView makeHeaderButton(String text, int textSize) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(textSize);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Color.argb(220, 31, 31, 38));
        return v;
    }

    private void renderFavorites() {
        if (favoritesGrid == null) return;
        favoritesGrid.removeAllViews();
        for (int i = 0; i < FAVORITE_SLOTS; i++) favoritesGrid.addView(makeFavoriteSlot(i));
    }

    private View makeFavoriteSlot(final int slot) {
        AppEntry app = getFavoriteApp(slot);
        LinearLayout box = baseTile(86);

        if (app == null) {
            TextView plus = new TextView(this);
            plus.setText("+");
            plus.setTextColor(Color.WHITE);
            plus.setTextSize(28);
            plus.setGravity(Gravity.CENTER);
            plus.setBackgroundColor(Color.argb(125, 45, 45, 55));
            box.addView(plus, new LinearLayout.LayoutParams(dp(40), dp(40)));
            box.addView(tileLabel("Agregar"), new LinearLayout.LayoutParams(-1, dp(26)));
            box.setOnClickListener(v -> chooseFavoriteForSlot(slot));
            return box;
        }

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        box.addView(tileLabel(app.label), new LinearLayout.LayoutParams(-1, dp(26)));
        box.setOnClickListener(v -> openApp(app));
        box.setOnLongClickListener(v -> { chooseFavoriteForSlot(slot); return true; });
        return box;
    }

    private TextView tileLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(9);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        return label;
    }

    private void showFavoriteManager() {
        String[] rows = new String[FAVORITE_SLOTS];
        for (int i = 0; i < FAVORITE_SLOTS; i++) {
            AppEntry app = getFavoriteApp(i);
            rows[i] = "Espacio " + (i + 1) + " · " + (app == null ? "Vacío" : app.label);
        }
        new AlertDialog.Builder(this)
                .setTitle("Editar favoritos")
                .setItems(rows, (dialog, which) -> chooseFavoriteForSlot(which))
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void chooseFavoriteForSlot(final int slot) {
        String[] items = new String[allApps.size() + 1];
        items[0] = "Vaciar este espacio";
        for (int i = 0; i < allApps.size(); i++) items[i + 1] = allApps.get(i).label;

        new AlertDialog.Builder(this)
                .setTitle("Elegir aplicación")
                .setItems(items, (dialog, which) -> {
                    SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                    if (which == 0) editor.putString(FAVORITE_PREFIX + slot, "");
                    else {
                        AppEntry app = allApps.get(which - 1);
                        editor.putString(FAVORITE_PREFIX + slot, app.packageName + "\n" + app.activityName);
                    }
                    editor.apply();
                    renderFavorites();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private AppEntry getFavoriteApp(int slot) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String key = FAVORITE_PREFIX + slot;
        String raw;
        if (prefs.contains(key)) {
            raw = prefs.getString(key, "");
            if (raw == null || raw.isEmpty()) return null;
        } else {
            if (slot == 0) return findByPackage("com.whatsapp");
            if (slot == 1) return findByPackage("com.android.chrome");
            if (slot == 2) return findByPackage("com.google.android.youtube");
            return null;
        }

        String[] parts = raw.split("\n", 2);
        if (parts.length == 2) {
            AppEntry exact = findByComponent(parts[0], parts[1]);
            if (exact != null) return exact;
        }
        return findByPackage(parts[0]);
    }

    private void showMiniFlipSettings() {
        String[] items = {"Editar favoritos", "Cambiar imagen de fondo", "Quitar imagen de fondo", "Activar rotación automática", "Ajustes del teléfono"};
        new AlertDialog.Builder(this)
                .setTitle("MiniFlip OS")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showFavoriteManager();
                    else if (which == 1) pickWallpaper();
                    else if (which == 2) clearMiniFlipWallpaper();
                    else if (which == 3) enableSystemAutoRotate();
                    else if (which == 4) openSettings();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void pickWallpaper() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_WALLPAPER);
    }

    private void clearMiniFlipWallpaper() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(WALLPAPER_URI).apply();
        wallpaperView.setImageDrawable(null);
        wallpaperView.setBackgroundColor(Color.BLACK);
    }

    private void applySavedWallpaper() {
        if (wallpaperView == null) return;
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(WALLPAPER_URI, "");
        if (saved == null || saved.isEmpty()) {
            wallpaperView.setImageDrawable(null);
            wallpaperView.setBackgroundColor(Color.BLACK);
            return;
        }
        try {
            wallpaperView.setImageURI(Uri.parse(saved));
        } catch (Exception e) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(WALLPAPER_URI).apply();
            wallpaperView.setImageDrawable(null);
            wallpaperView.setBackgroundColor(Color.BLACK);
        }
    }

    private void enableSystemAutoRotate() {
        try {
            if (!Settings.System.canWrite(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, "Activa 'Permitir modificar ajustes del sistema' y vuelve a MiniFlip", Toast.LENGTH_LONG).show();
                return;
            }
            Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1);
            Toast.makeText(this, "Rotación automática activada", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo cambiar la rotación", Toast.LENGTH_SHORT).show();
        }
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
            allApps.add(new AppEntry(label, r.activityInfo.packageName, r.activityInfo.name, icon));
        }

        final Collator collator = Collator.getInstance(new Locale("es", "MX"));
        Collections.sort(allApps, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) { return collator.compare(a.label, b.label); }
        });
    }

    private void renderApps(String query) {
        if (appsGrid == null) return;
        appsGrid.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (AppEntry app : allApps) {
            if (!q.isEmpty() && !app.label.toLowerCase(Locale.ROOT).contains(q)) continue;
            appsGrid.addView(makeTile(app));
        }
    }

    private void renderRecents() {
        if (recentsGrid == null) return;
        recentsGrid.removeAllViews();
        List<String> recentPkgs = getRecentPackages();
        int added = 0;
        for (String pkg : recentPkgs) {
            if ("__settings__".equals(pkg)) {
                recentsGrid.addView(makeSettingsTile());
                added++;
                continue;
            }
            AppEntry app = findByPackage(pkg);
            if (app != null) {
                recentsGrid.addView(makeTile(app));
                added++;
            }
        }
        if (added == 0) {
            TextView empty = new TextView(this);
            empty.setText("Aquí aparecerán las aplicaciones que abras desde MiniFlip OS.");
            empty.setTextColor(Color.LTGRAY);
            empty.setTextSize(11);
            empty.setGravity(Gravity.CENTER);
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
            gp.width = -1;
            gp.height = dp(100);
            gp.columnSpec = GridLayout.spec(0, 4);
            empty.setLayoutParams(gp);
            recentsGrid.addView(empty);
        }
    }

    private AppEntry findByPackage(String packageName) {
        for (AppEntry app : allApps) if (app.packageName.equals(packageName)) return app;
        return null;
    }

    private AppEntry findByComponent(String packageName, String activityName) {
        for (AppEntry app : allApps) if (app.packageName.equals(packageName) && app.activityName.equals(activityName)) return app;
        return null;
    }

    private View makeTile(AppEntry app) {
        LinearLayout box = baseTile(86);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        box.addView(tileLabel(app.label), new LinearLayout.LayoutParams(-1, dp(26)));
        box.setOnClickListener(v -> openApp(app));
        return box;
    }

    private View makeSettingsTile() {
        LinearLayout box = baseTile(86);
        TextView icon = new TextView(this);
        icon.setText("⚙");
        icon.setTextColor(Color.WHITE);
        icon.setTextSize(28);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        box.addView(tileLabel("Ajustes"), new LinearLayout.LayoutParams(-1, dp(26)));
        box.setOnClickListener(v -> openSettings());
        return box;
    }

    private LinearLayout baseTile(int heightDp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(2), dp(5), dp(2), dp(3));
        GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
        gp.width = 0;
        gp.height = dp(heightDp);
        gp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        gp.setMargins(dp(1), dp(1), dp(1), dp(1));
        box.setLayoutParams(gp);
        return box;
    }

    private void openApp(AppEntry app) {
        saveRecent(app.packageName);
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setClassName(app.packageName, app.activityName);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent fallback = getPackageManager().getLaunchIntentForPackage(app.packageName);
                if (fallback != null) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(fallback);
                    return;
                }
            } catch (Exception ignored) {}
            Toast.makeText(this, "No se pudo abrir " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings() {
        saveRecent("__settings__");
        try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        catch (Exception e) { Toast.makeText(this, "No se pudieron abrir Ajustes", Toast.LENGTH_SHORT).show(); }
    }

    private void saveRecent(String packageName) {
        List<String> current = getRecentPackages();
        current.remove(packageName);
        current.add(0, packageName);
        while (current.size() > 12) current.remove(current.size() - 1);
        StringBuilder joined = new StringBuilder();
        for (String s : current) {
            if (joined.length() > 0) joined.append('|');
            joined.append(s);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(RECENTS, joined.toString()).apply();
    }

    private List<String> getRecentPackages() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = prefs.getString(RECENTS, "");
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return list;
        String[] parts = raw.split("\\|");
        Collections.addAll(list, parts);
        return list;
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
