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

            Intent intent = new Intent(context, WidgetLaunchActivity.class);
            intent.setAction("com.marilu.miniflip.OPEN_IRVING_HOME");
            intent.putExtra("openIrvingHome", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(COVER_DISPLAY_ID)
                    .toBundle();

            PendingIntent openHome = PendingIntent.getActivity(
                    context,
                    appWidgetId * 100 + 1,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    options
            );

            views.setOnClickPendingIntent(R.id.widget_root, openHome);
            views.setOnClickPendingIntent(R.id.widget_open, openHome);
            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
    }
}
