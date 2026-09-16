package com.marilu.miniflip;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public class WidgetLaunchActivity extends Activity {
    private static final int COVER_DISPLAY_ID = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launchTarget(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        launchTarget(intent);
    }

    private void launchTarget(Intent source) {
        String packageName = source == null ? null : source.getStringExtra("packageName");
        String activityName = source == null ? null : source.getStringExtra("activityName");

        if (packageName == null || packageName.isEmpty()) {
            finish();
            return;
        }

        try {
            Intent target;
            if (activityName != null && !activityName.isEmpty()) {
                target = new Intent(Intent.ACTION_MAIN);
                target.addCategory(Intent.CATEGORY_LAUNCHER);
                target.setClassName(packageName, activityName);
            } else {
                target = getPackageManager().getLaunchIntentForPackage(packageName);
            }

            if (target == null) throw new IllegalStateException("No launch intent");
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(COVER_DISPLAY_ID)
                    .toBundle();
            startActivity(target, options);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir la aplicación", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
