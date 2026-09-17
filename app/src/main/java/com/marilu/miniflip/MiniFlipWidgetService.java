package com.marilu.miniflip;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MiniFlipWidgetService extends RemoteViewsService {
    public static final String EXTRA_MODE = "widget_mode";
    public static final String MODE_HOME = "home";
    public static final String MODE_DOCK = "dock";

    private static final String PREFS = "miniflip_prefs";
    private static final String HOME_PREFIX = "home_";
    private static final String DOCK_PREFIX = "dock_";

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        String mode = intent == null ? MODE_HOME : intent.getStringExtra(EXTRA_MODE);
        if (!MODE_DOCK.equals(mode)) mode = MODE_HOME;
        return new SelectedAppsFactory(getApplicationContext(), mode);
    }

    private static class SelectedAppsFactory implements RemoteViewsFactory {
        private final Context context;
        private final String mode;
        private final List<AppEntry> apps = new ArrayList<>();
        private final List<AppEntry> launchableApps = new ArrayList<>();

        SelectedAppsFactory(Context context, String mode) {
            this.context = context;
            this.mode = mode;
        }

        @Override public void onCreate() { loadApps(); }
        @Override public void onDataSetChanged() { loadApps(); }
        @Override public void onDestroy() { apps.clear(); launchableApps.clear(); }
        @Override public int getCount() { return apps.size(); }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= apps.size()) return null;
            AppEntry app = apps.get(position);
            int layout = MODE_DOCK.equals(mode)
                    ? R.layout.widget_dock_item
                    : R.layout.widget_app_item;

            RemoteViews row = new RemoteViews(context.getPackageName(), layout);
            row.setTextViewText(R.id.widget_app_label, app.label);

            try {
                Drawable icon = context.getPackageManager().getActivityIcon(
                        new android.content.ComponentName(app.packageName, app.activityName)
                );
                row.setImageViewBitmap(
                        R.id.widget_app_icon,
                        drawableToBitmap(icon, MODE_DOCK.equals(mode) ? 52 : 64)
                );
            } catch (Exception ignored) {
                try {
                    Drawable icon = context.getPackageManager().getApplicationIcon(app.packageName);
                    row.setImageViewBitmap(
                            R.id.widget_app_icon,
                            drawableToBitmap(icon, MODE_DOCK.equals(mode) ? 52 : 64)
                    );
                } catch (Exception ignoredAgain) {
                }
            }

            Intent fillIn = new Intent();
            fillIn.putExtra("packageName", app.packageName);
            fillIn.putExtra("activityName", app.activityName);
            row.setOnClickFillInIntent(R.id.widget_app_item_root, fillIn);
            return row;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean hasStableIds() { return false; }

        private void loadApps() {
            apps.clear();
            launchableApps.clear();
            loadLaunchableApps();

            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String prefix = MODE_DOCK.equals(mode) ? DOCK_PREFIX : HOME_PREFIX;
            int slots = MODE_DOCK.equals(mode) ? 5 : 8;

            for (int slot = 0; slot < slots; slot++) {
                String raw;
                if (prefs.contains(prefix + slot)) {
                    raw = prefs.getString(prefix + slot, "");
                } else {
                    String defaultPkg = slot == 0 ? "com.whatsapp"
                            : slot == 1 ? "com.android.chrome"
                            : slot == 2 ? "com.google.android.youtube"
                            : "";
                    AppEntry defaultApp = findByPackage(defaultPkg);
                    if (defaultApp == null) continue;
                    raw = defaultApp.packageName + "\n" + defaultApp.activityName;
                }

                AppEntry app = resolve(raw);
                if (app != null) apps.add(app);
            }
        }

        private void loadLaunchableApps() {
            try {
                PackageManager pm = context.getPackageManager();
                Intent intent = new Intent(Intent.ACTION_MAIN, null);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> results = pm.queryIntentActivities(intent, 0);
                Set<String> seen = new LinkedHashSet<>();

                for (ResolveInfo r : results) {
                    if (r.activityInfo == null || r.activityInfo.packageName == null) continue;
                    if (context.getPackageName().equals(r.activityInfo.packageName)) continue;
                    String key = r.activityInfo.packageName + "/" + r.activityInfo.name;
                    if (!seen.add(key)) continue;

                    CharSequence labelCs = r.loadLabel(pm);
                    String label = labelCs == null
                            ? r.activityInfo.packageName
                            : labelCs.toString();
                    launchableApps.add(new AppEntry(
                            label,
                            r.activityInfo.packageName,
                            r.activityInfo.name
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        private AppEntry resolve(String raw) {
            if (raw == null || raw.isEmpty()) return null;
            String[] parts = raw.split("\n", 2);
            if (parts.length == 2) {
                for (AppEntry app : launchableApps) {
                    if (app.packageName.equals(parts[0])
                            && app.activityName.equals(parts[1])) {
                        return app;
                    }
                }
            }
            return findByPackage(parts[0]);
        }

        private AppEntry findByPackage(String pkg) {
            if (pkg == null || pkg.isEmpty()) return null;
            for (AppEntry app : launchableApps) {
                if (app.packageName.equals(pkg)) return app;
            }
            return null;
        }

        private Bitmap drawableToBitmap(Drawable drawable, int maxPx) {
            if (drawable == null) return null;
            Bitmap source;
            if (drawable instanceof BitmapDrawable
                    && ((BitmapDrawable) drawable).getBitmap() != null) {
                source = ((BitmapDrawable) drawable).getBitmap();
            } else {
                int width = Math.max(1, drawable.getIntrinsicWidth());
                int height = Math.max(1, drawable.getIntrinsicHeight());
                width = Math.min(width, 256);
                height = Math.min(height, 256);
                source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(source);
                drawable.setBounds(0, 0, width, height);
                drawable.draw(canvas);
            }

            int width = source.getWidth();
            int height = source.getHeight();
            if (width <= maxPx && height <= maxPx) return source;
            float scale = Math.min((float) maxPx / width, (float) maxPx / height);
            int newWidth = Math.max(1, Math.round(width * scale));
            int newHeight = Math.max(1, Math.round(height * scale));
            return Bitmap.createScaledBitmap(source, newWidth, newHeight, true);
        }

        private static class AppEntry {
            final String label;
            final String packageName;
            final String activityName;

            AppEntry(String label, String packageName, String activityName) {
                this.label = label;
                this.packageName = packageName;
                this.activityName = activityName;
            }
        }
    }
}
