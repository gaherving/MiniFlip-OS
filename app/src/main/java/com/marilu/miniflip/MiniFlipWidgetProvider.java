package com.marilu.miniflip;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.RemoteViews;

public class MiniFlipWidgetProvider extends AppWidgetProvider {
    private static final int COVER_DISPLAY_ID = 1;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_miniflip_cover);

            Intent serviceIntent = new Intent(context, MiniFlipWidgetService.class);
            serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            serviceIntent.setData(android.net.Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME)));
            views.setRemoteAdapter(R.id.widget_app_grid, serviceIntent);

            PendingIntent template = appLaunchTemplate(context, appWidgetId * 100);
            views.setPendingIntentTemplate(R.id.widget_app_grid, template);

            views.setOnClickPendingIntent(R.id.widget_home, mainPendingIntent(context, appWidgetId * 100 + 1, 0));
            views.setOnClickPendingIntent(R.id.widget_apps, mainPendingIntent(context, appWidgetId * 100 + 2, 1));
            views.setOnClickPendingIntent(R.id.widget_settings, settingsPendingIntent(context, appWidgetId * 100 + 3));

            appWidgetManager.updateAppWidget(appWidgetId, views);
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_app_grid);
        }
    }

    private PendingIntent appLaunchTemplate(Context context, int requestCode) {
        Intent intent = new Intent(context, WidgetLaunchActivity.class);
        intent.setAction("com.marilu.miniflip.OPEN_WIDGET_APP");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Bundle options = ActivityOptions.makeBasic().setLaunchDisplayId(COVER_DISPLAY_ID).toBundle();
        return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE,
                options
        );
    }

    private PendingIntent mainPendingIntent(Context context, int requestCode, int page) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_PAGE, page);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return pendingIntentFor(context, requestCode, intent);
    }

    private PendingIntent settingsPendingIntent(Context context, int requestCode) {
        Intent intent = new Intent(Settings.ACTION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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
}
