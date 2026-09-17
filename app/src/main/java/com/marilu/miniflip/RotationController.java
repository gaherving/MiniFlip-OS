package com.marilu.miniflip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Display;
import android.view.Surface;

public final class RotationController {
    private static final String CHANNEL_ID = "irving_rotation";
    private RotationController() {}

    public static boolean canWrite(Context context) {
        return Settings.System.canWrite(context);
    }

    public static void startRotationEnforcer(Context context) {
        if (!canWrite(context)) return;
        try {
            Intent service = new Intent(context, EnforcerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
            else context.startService(service);
        } catch (Exception ignored) {}
    }

    public static void enableSystemAutoRotate(Context context) {
        if (!canWrite(context)) return;
        try {
            Settings.System.putInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1);
        } catch (Exception ignored) {}
    }

    public static Intent permissionIntent(Context context) {
        return new Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + context.getPackageName())
        );
    }

    public static class EnforcerService extends Service implements SensorEventListener {
        private SensorManager sensorManager;
        private Sensor accelerometer;
        private DisplayManager displayManager;
        private int lastRotation = -1;
        private long lastChangeAt = 0L;
        private boolean forcing = false;

        @Override
        public void onCreate() {
            super.onCreate();
            createChannel();
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            Notification notification = builder
                    .setContentTitle("Irving OS")
                    .setContentText("Rotación de pantalla externa activa")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setOngoing(true)
                    .build();
            startForeground(87, notification);

            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
            displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (sensorManager != null) {
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            }
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            return START_STICKY;
        }

        private void createChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Rotación Irving OS", NotificationManager.IMPORTANCE_MIN);
                    ch.setDescription("Mantiene la rotación de la pantalla externa mientras Irving OS está activo");
                    nm.createNotificationChannel(ch);
                }
            }
        }

        private boolean coverDisplayIsOn() {
            if (displayManager == null) return true;
            Display cover = displayManager.getDisplay(1);
            return cover != null && cover.getState() != Display.STATE_OFF;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!Settings.System.canWrite(this) || event == null || event.values.length < 2) return;

            if (!coverDisplayIsOn()) {
                if (forcing) {
                    try { Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1); }
                    catch (Exception ignored) {}
                    forcing = false;
                    lastRotation = -1;
                }
                return;
            }

            float x = event.values[0];
            float y = event.values[1];
            int rotation;
            if (Math.abs(x) > Math.abs(y)) {
                rotation = x > 0 ? Surface.ROTATION_270 : Surface.ROTATION_90;
            } else {
                rotation = y > 0 ? Surface.ROTATION_0 : Surface.ROTATION_180;
            }

            long now = System.currentTimeMillis();
            if (rotation == lastRotation || now - lastChangeAt < 350) return;
            lastChangeAt = now;
            lastRotation = rotation;

            try {
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, rotation);
                forcing = true;
            } catch (Exception ignored) {}
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        @Override
        public void onDestroy() {
            if (sensorManager != null) sensorManager.unregisterListener(this);
            try { Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1); }
            catch (Exception ignored) {}
            super.onDestroy();
        }

        @Override public IBinder onBind(Intent intent) { return null; }
    }
}
