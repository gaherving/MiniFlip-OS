package com.marilu.miniflip;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RemoteViews;

public class MiniFlipWidgetProvider extends AppWidgetProvider {
    private static final int COVER_DISPLAY_ID = 1;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            RemoteViews views = new RemoteViews(
                    context.getPackageName(),
                    R.layout.widget_miniflip_cover
            );

            Intent homeService = new Intent(context, MiniFlipWidgetService.class);
            homeService.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            homeService.putExtra(MiniFlipWidgetService.EXTRA_MODE, MiniFlipWidgetService.MODE_HOME);
            homeService.setData(android.net.Uri.parse(homeService.toUri(Intent.URI_INTENT_SCHEME)));
            views.setRemoteAdapter(R.id.widget_home_grid, homeService);

            Intent dockService = new Intent(context, MiniFlipWidgetService.class);
            dockService.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            dockService.putExtra(MiniFlipWidgetService.EXTRA_MODE, MiniFlipWidgetService.MODE_DOCK);
            dockService.setData(android.net.Uri.parse(dockService.toUri(Intent.URI_INTENT_SCHEME)));
            views.setRemoteAdapter(R.id.widget_dock_grid, dockService);

            PendingIntent homeTemplate = appLaunchTemplate(context, appWidgetId * 100 + 10);
            PendingIntent dockTemplate = appLaunchTemplate(context, appWidgetId * 100 + 20);
            views.setPendingIntentTemplate(R.id.widget_home_grid, homeTemplate);
            views.setPendingIntentTemplate(R.id.widget_dock_grid, dockTemplate);

            PendingIntent openHome = mainPendingIntent(context, appWidgetId * 100 + 1, false);
            PendingIntent openSettings = mainPendingIntent(context, appWidgetId * 100 + 2, true);
            views.setOnClickPendingIntent(R.id.widget_root, openHome);
            views.setOnClickPendingIntent(R.id.widget_settings, openSettings);

            appWidgetManager.updateAppWidget(appWidgetId, views);
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_home_grid);
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_dock_grid);
        }
    }

    private PendingIntent appLaunchTemplate(Context context, int requestCode) {
        Intent intent = new Intent(context, WidgetLaunchActivity.class);
        intent.setAction("com.marilu.miniflip.OPEN_WIDGET_APP");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Bundle options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(COVER_DISPLAY_ID)
                .toBundle();
        return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE,
                options
        );
    }

    private PendingIntent mainPendingIntent(Context context, int requestCode, boolean openSettings) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_PAGE, 0);
        intent.putExtra(MainActivity.EXTRA_OPEN_SETTINGS, openSettings);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

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
