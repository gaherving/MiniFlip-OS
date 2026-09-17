package com.marilu.miniflip;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
    private static final String PREFS = "miniflip_prefs";
    private static final String RECENTS = "recents";

    private final List<AppEntry> allApps = new ArrayList<>();
    private FrameLayout content;
    private LinearLayout homePage;
    private LinearLayout appsPage;
    private LinearLayout recentsPage;
    private GridLayout appsGrid;
    private GridLayout recentsGrid;
    private EditText search;
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
        RotationController.enableSystemAutoRotate(this);

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
        RotationController.enableSystemAutoRotate(this);
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(7, 7, 9));
        root.setPadding(dp(8), dp(6), dp(8), dp(6));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(4), 0);

        TextView title = new TextView(this);
        title.setText("MiniFlip OS");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1f));

        TextView rotateButton = makeRoundTextButton("↻");
        rotateButton.setTextSize(16);
        rotateButton.setOnClickListener(v -> configureRotation());
        LinearLayout.LayoutParams rotateParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        rotateParams.setMargins(0, 0, dp(5), 0);
        header.addView(rotateButton, rotateParams);

        TextView settingsButton = makeRoundTextButton("⚙");
        settingsButton.setTextSize(16);
        settingsButton.setOnClickListener(v -> openSettings());
        header.addView(settingsButton, new LinearLayout.LayoutParams(dp(34), dp(34)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(40)));

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

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(dp(56), dp(32));
        p1.setMargins(0, 0, dp(4), 0);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(dp(50), dp(32));
        p2.setMargins(0, 0, dp(4), 0);
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(dp(54), dp(32));
        p3.setMargins(0, 0, dp(4), 0);

        nav.addView(navHome, p1);
        nav.addView(navApps, p2);
        nav.addView(navRecents, p3);
        nav.addView(new Space(this), new LinearLayout.LayoutParams(0, dp(32), 1f));
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(38)));

        setContentView(root);
    }

    private void configureRotation() {
        if (RotationController.canWrite(this)) {
            RotationController.enableSystemAutoRotate(this);
            Toast.makeText(this, "Giro automático activado", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Toast.makeText(this, "Activa 'Permitir modificar ajustes del sistema' para MiniFlip OS", Toast.LENGTH_LONG).show();
            startActivity(RotationController.permissionIntent(this));
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir el permiso de giro", Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout buildHomePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(2), dp(4), dp(2), dp(2));

        TextView label = new TextView(this);
        label.setText("Favoritos");
        label.setTextColor(Color.LTGRAY);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(label, new LinearLayout.LayoutParams(-1, dp(28)));

        GridLayout favorites = new GridLayout(this);
        favorites.setColumnCount(4);
        favorites.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        favorites.setUseDefaultMargins(false);

        addFavorite(favorites, "com.whatsapp", "WhatsApp");
        addFavorite(favorites, "com.android.chrome", "Chrome");
        addFavorite(favorites, "com.google.android.youtube", "YouTube");
        favorites.addView(makeSettingsTile());
        page.addView(favorites, new LinearLayout.LayoutParams(-1, dp(102)));

        TextView allApps = makeWideButton("Todas las aplicaciones");
        allApps.setOnClickListener(v -> showPage(PAGE_APPS));
        LinearLayout.LayoutParams allAppsParams = new LinearLayout.LayoutParams(-1, dp(42));
        allAppsParams.setMargins(0, dp(6), 0, 0);
        page.addView(allApps, allAppsParams);

        TextView recent = makeWideButton("Abrir recientes");
        recent.setOnClickListener(v -> showPage(PAGE_RECENTS));
        LinearLayout.LayoutParams recentParams = new LinearLayout.LayoutParams(-1, dp(42));
        recentParams.setMargins(0, dp(6), 0, 0);
        page.addView(recent, recentParams);

        TextView tip = new TextView(this);
        tip.setText("MiniFlip OS Home · diseñado para la pantalla exterior del Flip5");
        tip.setTextColor(Color.GRAY);
        tip.setTextSize(9);
        tip.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tipParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        tipParams.setMargins(dp(6), dp(6), dp(6), 0);
        page.addView(tip, tipParams);
        return page;
    }

    private LinearLayout buildAppsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Buscar aplicación");
        search.setHintTextColor(Color.GRAY);
        search.setTextColor(Color.WHITE);
        search.setTextSize(13);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackgroundColor(Color.rgb(28, 28, 34));
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
        label.setTextColor(Color.LTGRAY);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(label, new LinearLayout.LayoutParams(-1, dp(28)));

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
        v.setTextColor(selected ? Color.WHITE : Color.LTGRAY);
        v.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        v.setBackgroundColor(selected ? Color.rgb(39, 39, 48) : Color.rgb(20, 20, 25));
    }

    private TextView makeNavButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.LTGRAY);
        v.setTextSize(10);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(2), 0, dp(2), 0);
        return v;
    }

    private TextView makeRoundTextButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(18);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Color.rgb(31, 31, 38));
        return v;
    }

    private TextView makeWideButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(12);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Color.rgb(31, 31, 38));
        return v;
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
            @Override public int compare(AppEntry a, AppEntry b) {
                return collator.compare(a.label, b.label);
            }
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
            empty.setTextColor(Color.GRAY);
            empty.setTextSize(12);
            empty.setGravity(Gravity.CENTER);
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
            gp.width = -1;
            gp.height = dp(110);
            gp.columnSpec = GridLayout.spec(0, 4);
            empty.setLayoutParams(gp);
            recentsGrid.addView(empty);
        }
    }

    private void addFavorite(GridLayout grid, String packageName, String fallbackLabel) {
        AppEntry app = findByPackage(packageName);
        if (app != null) {
            grid.addView(makeTile(app));
        } else {
            grid.addView(makeMissingTile(fallbackLabel));
        }
    }

    private AppEntry findByPackage(String packageName) {
        for (AppEntry app : allApps) {
            if (app.packageName.equals(packageName)) return app;
        }
        return null;
    }

    private View makeTile(AppEntry app) {
        LinearLayout box = baseTile();
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(9);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        box.addView(label, new LinearLayout.LayoutParams(-1, dp(28)));
        box.setOnClickListener(v -> openApp(app));
        return box;
    }

    private View makeSettingsTile() {
        LinearLayout box = baseTile();
        TextView icon = new TextView(this);
        icon.setText("⚙");
        icon.setTextColor(Color.WHITE);
        icon.setTextSize(29);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView label = new TextView(this);
        label.setText("Ajustes");
        label.setTextColor(Color.WHITE);
        label.setTextSize(9);
        label.setGravity(Gravity.CENTER);
        box.addView(label, new LinearLayout.LayoutParams(-1, dp(28)));
        box.setOnClickListener(v -> openSettings());
        return box;
    }

    private View makeMissingTile(String labelText) {
        LinearLayout box = baseTile();
        TextView icon = new TextView(this);
        icon.setText("•");
        icon.setTextColor(Color.GRAY);
        icon.setTextSize(30);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextColor(Color.GRAY);
        label.setTextSize(9);
        label.setGravity(Gravity.CENTER);
        box.addView(label, new LinearLayout.LayoutParams(-1, dp(28)));
        box.setOnClickListener(v -> Toast.makeText(this, labelText + " no está instalada", Toast.LENGTH_SHORT).show());
        return box;
    }

    private LinearLayout baseTile() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(2), dp(5), dp(2), dp(3));
        GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
        gp.width = 0;
        gp.height = dp(94);
        gp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        gp.setMargins(dp(2), dp(2), dp(2), dp(2));
        box.setLayoutParams(gp);
        return box;
    }

    private void openApp(AppEntry app) {
        saveRecent(app.packageName);
        RotationController.enableSystemAutoRotate(this);
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
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "No se pudieron abrir Ajustes", Toast.LENGTH_SHORT).show();
        }
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
