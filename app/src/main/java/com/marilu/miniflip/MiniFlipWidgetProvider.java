package com.marilu.miniflip;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.RemoteViews;

public class MiniFlipWidgetProvider extends AppWidgetProvider {
    private static final int COVER_DISPLAY_ID = 1;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_miniflip_cover);

            PendingIntent home = mainPendingIntent(context, appWidgetId * 10);
            PendingIntent settings = settingsPendingIntent(context, appWidgetId * 10 + 2);
            PendingIntent whatsapp = packagePendingIntent(context, "com.whatsapp", appWidgetId * 10 + 3, home);
            PendingIntent chrome = packagePendingIntent(context, "com.android.chrome", appWidgetId * 10 + 4, home);
            PendingIntent youtube = packagePendingIntent(context, "com.google.android.youtube", appWidgetId * 10 + 5, home);

            views.setOnClickPendingIntent(R.id.widget_root, home);
            views.setOnClickPendingIntent(R.id.widget_apps, home);
            views.setOnClickPendingIntent(R.id.widget_settings, settings);
            views.setOnClickPendingIntent(R.id.widget_slot_whatsapp, whatsapp);
            views.setOnClickPendingIntent(R.id.widget_slot_chrome, chrome);
            views.setOnClickPendingIntent(R.id.widget_slot_youtube, youtube);

            setPackageIcon(context, views, R.id.widget_icon_whatsapp, "com.whatsapp");
            setPackageIcon(context, views, R.id.widget_icon_chrome, "com.android.chrome");
            setPackageIcon(context, views, R.id.widget_icon_youtube, "com.google.android.youtube");

            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
    }

    private PendingIntent mainPendingIntent(Context context, int requestCode) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return pendingIntentFor(context, requestCode, intent);
    }

    private PendingIntent settingsPendingIntent(Context context, int requestCode) {
        Intent intent = new Intent(Settings.ACTION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return pendingIntentFor(context, requestCode, intent);
    }

    private PendingIntent packagePendingIntent(Context context, String packageName, int requestCode, PendingIntent fallback) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) return fallback;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return pendingIntentFor(context, requestCode, intent);
    }

    private PendingIntent pendingIntentFor(Context context, int requestCode, Intent intent) {
        Bundle options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(COVER_DISPLAY_ID)
                .toBundle();
        return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                options
        );
    }

    private void setPackageIcon(Context context, RemoteViews views, int viewId, String packageName) {
        try {
            Drawable drawable = context.getPackageManager().getApplicationIcon(packageName);
            views.setImageViewBitmap(viewId, drawableToBitmap(drawable));
        } catch (Exception ignored) {
        }
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
}
