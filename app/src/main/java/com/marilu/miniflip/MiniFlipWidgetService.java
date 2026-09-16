package com.marilu.miniflip;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MiniFlipWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new AppGridFactory(getApplicationContext());
    }

    private static class AppGridFactory implements RemoteViewsFactory {
        private final Context context;
        private final List<AppEntry> apps = new ArrayList<>();

        AppGridFactory(Context context) {
            this.context = context;
        }

        @Override public void onCreate() { loadApps(); }
        @Override public void onDataSetChanged() { loadApps(); }
        @Override public void onDestroy() { apps.clear(); }
        @Override public int getCount() { return apps.size(); }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= apps.size()) return null;
            AppEntry app = apps.get(position);
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_app_item);
            row.setTextViewText(R.id.widget_app_label, app.label);
            row.setImageViewBitmap(R.id.widget_app_icon, drawableToBitmap(app.icon));

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
                String label = labelCs == null ? r.activityInfo.packageName : labelCs.toString();
                Drawable icon = r.loadIcon(pm);
                apps.add(new AppEntry(label, r.activityInfo.packageName, r.activityInfo.name, icon));
            }

            final Collator collator = Collator.getInstance(new Locale("es", "MX"));
            Collections.sort(apps, new Comparator<AppEntry>() {
                @Override public int compare(AppEntry a, AppEntry b) {
                    return collator.compare(a.label, b.label);
                }
            });
        }

        private Bitmap drawableToBitmap(Drawable drawable) {
            if (drawable instanceof BitmapDrawable) {
                Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
                if (bitmap != null) return bitmap;
            }
            int width = Math.max(1, drawable.getIntrinsicWidth());
            int height = Math.max(1, drawable.getIntrinsicHeight());
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            return bitmap;
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
}
