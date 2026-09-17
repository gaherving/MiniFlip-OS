package com.marilu.miniflip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public final class RotationController {
    private static final String CHANNEL_ID = "irving_rotation";

    private RotationController() {}

    public static boolean canWrite(Context context) {
        return Settings.System.canWrite(context);
    }

    public static boolean isShizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasShizukuPermission() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void startRotationEnforcer(Context context) {
        try {
            Intent service = new Intent(context, EnforcerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
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
        private final ExecutorService shellExecutor = Executors.newSingleThreadExecutor();

        private int lastRotation = -1;
        private long lastChangeAt = 0L;
        private boolean externalRotationPrepared = false;

        @Override
        public void onCreate() {
            super.onCreate();
            createChannel();

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);

            Notification notification = builder
                    .setContentTitle("Irving OS")
                    .setContentText("Rotación automática de pantalla externa activa")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setOngoing(true)
                    .build();

            startForeground(87, notification);

            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
            displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);

            if (sensorManager != null) {
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (accelerometer != null) {
                    sensorManager.registerListener(
                            this,
                            accelerometer,
                            SensorManager.SENSOR_DELAY_UI
                    );
                }
            }

            prepareExternalDisplayRotation();
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            prepareExternalDisplayRotation();
            return START_STICKY;
        }

        private void createChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

                if (nm != null) {
                    NotificationChannel ch = new NotificationChannel(
                            CHANNEL_ID,
                            "Rotación Irving OS",
                            NotificationManager.IMPORTANCE_MIN
                    );
                    ch.setDescription(
                            "Gira la pantalla externa y las aplicaciones abiertas en ella"
                    );
                    nm.createNotificationChannel(ch);
                }
            }
        }

        private boolean coverDisplayIsOn() {
            if (displayManager == null) return true;
            Display cover = displayManager.getDisplay(1);
            return cover != null && cover.getState() != Display.STATE_OFF;
        }

        private void prepareExternalDisplayRotation() {
            if (!hasShizukuPermission() || externalRotationPrepared) return;

            externalRotationPrepared = true;
            shellExecutor.execute(() -> {
                boolean ok = runShell(
                        "wm fixed-to-user-rotation -d 1 enabled"
                );
                if (!ok) externalRotationPrepared = false;
            });
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.values.length < 2) return;

            if (!coverDisplayIsOn()) {
                lastRotation = -1;
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

            if (hasShizukuPermission()) {
                prepareExternalDisplayRotation();
                final int targetRotation = rotation;
                shellExecutor.execute(() -> runShell(
                        "wm user-rotation -d 1 lock " + targetRotation
                ));
                return;
            }

            if (Settings.System.canWrite(this)) {
                try {
                    Settings.System.putInt(
                            getContentResolver(),
                            Settings.System.ACCELEROMETER_ROTATION,
                            0
                    );
                    Settings.System.putInt(
                            getContentResolver(),
                            Settings.System.USER_ROTATION,
                            rotation
                    );
                } catch (Exception ignored) {}
            }
        }

        private boolean runShell(String command) {
            if (!hasShizukuPermission()) return false;

            Object remoteProcess = null;
            try {
                Method newProcess = Shizuku.class.getDeclaredMethod(
                        "newProcess",
                        String[].class,
                        String[].class,
                        String.class
                );
                newProcess.setAccessible(true);

                remoteProcess = newProcess.invoke(
                        null,
                        new Object[]{
                                new String[]{"sh", "-c", command},
                                null,
                                null
                        }
                );

                if (remoteProcess == null) return false;

                Method waitFor = remoteProcess.getClass().getMethod("waitFor");
                Object result = waitFor.invoke(remoteProcess);
                return result instanceof Integer && ((Integer) result) == 0;
            } catch (Throwable ignored) {
                return false;
            } finally {
                if (remoteProcess != null) {
                    try {
                        Method destroy = remoteProcess.getClass().getMethod("destroy");
                        destroy.invoke(remoteProcess);
                    } catch (Throwable ignored) {}
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        @Override
        public void onDestroy() {
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }

            if (hasShizukuPermission()) {
                shellExecutor.execute(() -> {
                    runShell("wm user-rotation -d 1 free");
                    runShell("wm fixed-to-user-rotation -d 1 disabled");
                });
            }

            shellExecutor.shutdown();

            if (Settings.System.canWrite(this)) {
                try {
                    Settings.System.putInt(
                            getContentResolver(),
                            Settings.System.ACCELEROMETER_ROTATION,
                            1
                    );
                } catch (Exception ignored) {}
            }

            super.onDestroy();
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
    }
}
