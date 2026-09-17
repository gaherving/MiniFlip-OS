from pathlib import Path

build_path = Path('app/build.gradle')
manifest_path = Path('app/src/main/AndroidManifest.xml')
service_path = Path('app/src/main/java/com/marilu/miniflip/IrvingPairingOverlayService.java')
setup_path = Path('app/src/main/java/com/marilu/miniflip/IrvingAdbSetupActivity.java')

build = build_path.read_text(encoding='utf-8')
build = build.replace('versionCode 26', 'versionCode 27')
build = build.replace("versionName '0.26-irving-os-easy-pairing'", "versionName '0.27-irving-os-pairing-notification'")
build_path.write_text(build, encoding='utf-8')

manifest = manifest_path.read_text(encoding='utf-8')
manifest = manifest.replace('    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />\n', '')
manifest_path.write_text(manifest, encoding='utf-8')

service_path.write_text(r'''package com.marilu.miniflip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

public final class IrvingPairingOverlayService extends Service {
    private static final String CHANNEL_ID = "irving_pairing";
    private static final int NOTIFICATION_ID = 126;
    private static final String SERVICE_TYPE = "_adb-tls-pairing._tcp.";
    private static final String ACTION_CODE = "com.marilu.miniflip.ACTION_PAIRING_CODE";
    private static final String ACTION_STOP = "com.marilu.miniflip.ACTION_STOP_PAIRING";
    private static final String KEY_CODE = "irving_pairing_code";

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private int pairingPort = -1;
    private boolean pairing = false;
    private boolean discoveryStarted = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification(
                "Esperando el puerto de emparejamiento…",
                true
        ));
        startDiscovery();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_CODE.equals(action)) {
                Bundle results = RemoteInput.getResultsFromIntent(intent);
                if (results != null) {
                    CharSequence value = results.getCharSequence(KEY_CODE);
                    if (value != null) {
                        String code = value.toString().trim();
                        if (code.length() == 6 && pairingPort > 0) {
                            pair(code);
                        } else {
                            updateNotification(
                                    pairingPort > 0
                                            ? "El código debe tener 6 dígitos. Inténtalo otra vez."
                                            : "Aún no detecto el puerto. Abre el cuadro de código en Depuración inalámbrica.",
                                    true
                            );
                        }
                    }
                }
            }
        }
        return START_STICKY;
    }

    private Notification buildNotification(String message, boolean allowInput) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setContentTitle("Irving OS · Vinculación")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(Color.rgb(80, 200, 210));

        if (allowInput) {
            RemoteInput remoteInput = new RemoteInput.Builder(KEY_CODE)
                    .setLabel("Código de 6 dígitos")
                    .build();

            Intent codeIntent = new Intent(this, IrvingPairingOverlayService.class);
            codeIntent.setAction(ACTION_CODE);
            PendingIntent codePending = PendingIntent.getService(
                    this,
                    127,
                    codeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );

            Notification.Action codeAction = new Notification.Action.Builder(
                    android.R.drawable.ic_menu_edit,
                    "Escribir código",
                    codePending
            ).addRemoteInput(remoteInput).build();
            builder.addAction(codeAction);
        }

        Intent stopIntent = new Intent(this, IrvingPairingOverlayService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                128,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        builder.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancelar",
                stopPending
        ).build());

        return builder.build();
    }

    private void updateNotification(String message, boolean allowInput) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(message, allowInput));
    }

    private void startDiscovery() {
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            updateNotification("No pude iniciar la detección automática del puerto.", true);
            return;
        }

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String regType) {
                discoveryStarted = true;
            }

            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (serviceInfo == null) return;
                String type = serviceInfo.getServiceType();
                if (type == null || !type.contains("_adb-tls-pairing")) return;

                try {
                    nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {}

                        @Override public void onServiceResolved(NsdServiceInfo info) {
                            int port = info == null ? -1 : info.getPort();
                            if (port > 0) {
                                pairingPort = port;
                                updateNotification(
                                        "Puerto detectado. Desliza la barra de notificaciones y toca “Escribir código”.",
                                        true
                                );
                            }
                        }
                    });
                } catch (Throwable ignored) {}
            }

            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {}
            @Override public void onDiscoveryStopped(String serviceType) { discoveryStarted = false; }

            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                discoveryStarted = false;
                updateNotification("No pude detectar el puerto automáticamente.", true);
            }

            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                discoveryStarted = false;
            }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Throwable e) {
            updateNotification("No pude detectar el puerto automáticamente.", true);
        }
    }

    private void pair(String code) {
        if (pairing || pairingPort <= 0) return;
        pairing = true;
        updateNotification("Vinculando Irving OS…", false);

        IrvingLocalAdb.pair(getApplicationContext(), pairingPort, code, (ok, message) ->
                getMainExecutor().execute(() -> {
                    if (ok) {
                        updateNotification("✓ Irving OS vinculado correctamente", false);
                        stopDiscovery();
                        new android.os.Handler(getMainLooper()).postDelayed(this::stopSelf, 1800L);
                    } else {
                        pairing = false;
                        updateNotification(
                                "No se pudo vincular. Genera un código nuevo y vuelve a escribirlo.",
                                true
                        );
                    }
                })
        );
    }

    private void stopDiscovery() {
        if (!discoveryStarted || nsdManager == null || discoveryListener == null) return;
        try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Throwable ignored) {}
        discoveryStarted = false;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID,
                        "Vinculación Irving OS",
                        NotificationManager.IMPORTANCE_HIGH
                );
                ch.setDescription("Introduce el código de vinculación de Depuración inalámbrica sin salir de Ajustes");
                nm.createNotificationChannel(ch);
            }
        }
    }

    @Override
    public void onDestroy() {
        stopDiscovery();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
''', encoding='utf-8')

setup_path.write_text(r'''package com.marilu.miniflip;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class IrvingAdbSetupActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 2701;
    private boolean waitingNotificationPermission = false;
    private EditText manualPort;
    private EditText manualCode;
    private TextView manualStatus;

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS && waitingNotificationPermission) {
            waitingNotificationPermission = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startNotificationPairing();
            } else {
                Toast.makeText(
                        this,
                        "Necesito permiso de notificaciones para que puedas escribir el código sin salir de Ajustes.",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(18));
        root.setBackgroundColor(Color.rgb(10, 10, 12));

        TextView title = text("Funciones avanzadas", 20, Color.WHITE, true);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(36)));

        TextView info = text(
                "Android oculta las ventanas flotantes sobre el cuadro de vinculación. Por eso Irving OS ahora usa una notificación: detecta el puerto automáticamente y tú escribes los 6 dígitos sin cerrar Ajustes.",
                11.5f, Color.rgb(205,205,210), false);
        root.addView(info, new LinearLayout.LayoutParams(-1, -2));

        TextView easy = button(
                IrvingLocalAdb.isConfigured(this)
                        ? "✓ Irving OS ya está vinculado"
                        : "Iniciar vinculación fácil"
        );
        LinearLayout.LayoutParams easyLp = new LinearLayout.LayoutParams(-1, dp(52));
        easyLp.setMargins(0, dp(14), 0, dp(8));
        root.addView(easy, easyLp);
        easy.setOnClickListener(v -> {
            if (IrvingLocalAdb.isConfigured(this)) {
                Toast.makeText(this, "Irving OS ya está vinculado", Toast.LENGTH_SHORT).show();
                return;
            }
            beginNotificationPairing();
        });

        TextView steps = text(
                "1. Pulsa “Iniciar vinculación fácil”.\n" +
                "2. En Depuración inalámbrica toca “Vincular dispositivo con un código de vinculación”.\n" +
                "3. Sin salir de esa pantalla, baja la barra de notificaciones.\n" +
                "4. En la notificación de Irving OS toca “Escribir código”, pon los 6 números y envíalos.\n" +
                "El puerto se detecta automáticamente.",
                10.5f, Color.rgb(175,175,185), false);
        root.addView(steps, new LinearLayout.LayoutParams(-1, -2));

        TextView fallback = text("Modo manual (respaldo)", 10.5f, Color.GRAY, true);
        LinearLayout.LayoutParams fallbackLp = new LinearLayout.LayoutParams(-1, dp(28));
        fallbackLp.topMargin = dp(14);
        root.addView(fallback, fallbackLp);

        manualPort = input("Puerto");
        manualPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(manualPort, new LinearLayout.LayoutParams(-1, dp(42)));

        manualCode = input("Código de 6 dígitos");
        manualCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(-1, dp(42));
        codeLp.topMargin = dp(7);
        root.addView(manualCode, codeLp);

        TextView manualButton = button("Vincular manualmente");
        LinearLayout.LayoutParams manualLp = new LinearLayout.LayoutParams(-1, dp(44));
        manualLp.topMargin = dp(8);
        root.addView(manualButton, manualLp);
        manualButton.setOnClickListener(v -> pairManual(manualButton));

        manualStatus = text("", 10.5f, Color.rgb(155,155,165), false);
        manualStatus.setGravity(Gravity.CENTER);
        root.addView(manualStatus, new LinearLayout.LayoutParams(-1, dp(34)));

        setContentView(root);
    }

    private void beginNotificationPairing() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            waitingNotificationPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        startNotificationPairing();
    }

    private void startNotificationPairing() {
        try {
            Intent helper = new Intent(this, IrvingPairingOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(helper);
            else startService(helper);
        } catch (Throwable e) {
            Toast.makeText(this, "No pude iniciar el asistente de vinculación", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(
                this,
                "Abre Depuración inalámbrica y genera el código. Después baja las notificaciones para escribirlo.",
                Toast.LENGTH_LONG
        ).show();

        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Throwable first) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Throwable ignored) {}
        }
    }

    private void pairManual(TextView button) {
        String portText = manualPort.getText().toString().trim();
        String code = manualCode.getText().toString().trim();
        int port;
        try { port = Integer.parseInt(portText); }
        catch (Throwable e) { port = -1; }

        if (port <= 0 || code.length() != 6) {
            Toast.makeText(this, "Escribe un puerto válido y el código de 6 dígitos", Toast.LENGTH_LONG).show();
            return;
        }

        button.setEnabled(false);
        manualStatus.setText("Vinculando…");
        final int finalPort = port;
        IrvingLocalAdb.pair(this, finalPort, code, (ok, message) -> runOnUiThread(() -> {
            button.setEnabled(true);
            manualStatus.setText(message);
            manualStatus.setTextColor(ok ? Color.rgb(96,220,150) : Color.rgb(255,135,135));
            if (ok) manualStatus.postDelayed(this::finish, 1000L);
        }));
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private EditText input(String hint) {
        EditText v = new EditText(this);
        v.setSingleLine(true);
        v.setHint(hint);
        v.setHintTextColor(Color.rgb(130,130,140));
        v.setTextColor(Color.WHITE);
        v.setTextSize(13);
        v.setPadding(dp(12), 0, dp(12), 0);
        v.setBackground(rounded(Color.rgb(30,30,36), 14));
        return v;
    }

    private TextView button(String label) {
        TextView v = text(label, 13, Color.WHITE, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(rounded(Color.rgb(48,48,58), 14));
        return v;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }
}
''', encoding='utf-8')

print('Applied Irving OS v0.27 notification pairing')
