package com.marilu.miniflip;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
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
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private GridLayout grid;
    private final List<AppEntry> allApps = new ArrayList<>();

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(6), dp(8), dp(4));
        root.setBackgroundColor(Color.rgb(8, 8, 10));

        TextView title = new TextView(this);
        title.setText("MiniFlip OS");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(34)));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Buscar app");
        search.setHintTextColor(Color.GRAY);
        search.setTextColor(Color.WHITE);
        search.setTextSize(13);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackgroundColor(Color.rgb(30, 30, 36));
        root.addView(search, new LinearLayout.LayoutParams(-1, dp(42)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        scroll.addView(grid, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);

        loadLauncherApps();
        renderApps("");

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderApps(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void loadLauncherApps() {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> results = pm.queryIntentActivities(intent, 0);
        String self = getPackageName();

        for (ResolveInfo r : results) {
            if (r.activityInfo == null || r.activityInfo.packageName == null) continue;
            if (self.equals(r.activityInfo.packageName)) continue;

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
        grid.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        for (AppEntry app : allApps) {
            if (!q.isEmpty() && !app.label.toLowerCase(Locale.ROOT).contains(q)) continue;
            grid.addView(makeTile(app));
        }
    }

    private View makeTile(AppEntry app) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(2), dp(7), dp(2), dp(5));

        GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
        gp.width = 0;
        gp.height = dp(84);
        gp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        gp.setMargins(dp(2), dp(2), dp(2), dp(2));
        box.setLayoutParams(gp);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));

        TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(9);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        box.addView(label, new LinearLayout.LayoutParams(-1, dp(32)));

        box.setOnClickListener(v -> openApp(app));
        return box;
    }

    private void openApp(AppEntry app) {
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
