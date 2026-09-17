package com.marilu.miniflip;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

public final class RotationController {
    private RotationController() {}

    public static boolean canWrite(Context context) {
        return Settings.System.canWrite(context);
    }

    public static void enableSystemAutoRotate(Context context) {
        if (!canWrite(context)) return;
        try {
            Settings.System.putInt(
                    context.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION,
                    1
            );
        } catch (Exception ignored) {
        }
    }

    public static Intent permissionIntent(Context context) {
        return new Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + context.getPackageName())
        );
    }
}
