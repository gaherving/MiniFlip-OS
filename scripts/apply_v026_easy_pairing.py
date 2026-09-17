from pathlib import Path

manifest_path = Path('app/src/main/AndroidManifest.xml')
build_path = Path('app/build.gradle')
setup_path = Path('app/src/main/java/com/marilu/miniflip/IrvingAdbSetupActivity.java')
service_path = Path('app/src/main/java/com/marilu/miniflip/IrvingPairingOverlayService.java')

build = build_path.read_text(encoding='utf-8')
build = build.replace('versionCode 25', 'versionCode 26')
build = build.replace("versionName '0.25-irving-os-local-adb-statusbar'", "versionName '0.26-irving-os-easy-pairing'")
build_path.write_text(build, encoding='utf-8')

manifest = manifest_path.read_text(encoding='utf-8')
overlay_perm = '    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />\n'
if overlay_perm not in manifest:
    marker = '    <uses-permission android:name="android.permission.WRITE_SETTINGS" />\n'
    manifest = manifest.replace(marker, marker + overlay_perm, 1)

service_decl = '''
        <service
            android:name=".IrvingPairingOverlayService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Shows the one-time Irving OS pairing helper over Android wireless debugging settings." />
        </service>

'''
if 'android:name=".IrvingPairingOverlayService"' not in manifest:
    marker = '        <service\n            android:name=".RotationController$EnforcerService"'
    manifest = manifest.replace(marker, service_decl + marker, 1)
manifest_path.write_text(manifest, encoding='utf-8')

service_path.write_text(r'''package com.marilu.miniflip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class IrvingPairingOverlayService extends Service {
    private static final String CHANNEL_ID = "irving_pairing";
    private static final int NOTIFICATION_ID = 126;
    private static final String SERVICE_TYPE = "_adb-tls-pairing._tcp.";

    private WindowManager windowManager;
    private LinearLayout overlay;
    private TextView status;
    private EditText codeInput;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private int pairingPort = -1;
    private boolean pairing = false;
    private boolean discoveryStarted = false;

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle("Irving OS")
                .setContentText("Asistente de vinculación activo")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        showOverlay();
        startDiscovery();
    }

    private void showOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            stopSelf();
            return;
        }

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(14), dp(10), dp(14), dp(10));
        overlay.setBackground(rounded(Color.rgb(25, 25, 31), 18));

        TextView title = new TextView(this);
        title.setText("Irving OS · Vinculación");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        overlay.addView(title, new LinearLayout.LayoutParams(-1, dp(28)));

        status = new TextView(this);
        status.setText("Abre “Vincular dispositivo con un código”. Detectaré el puerto automáticamente.");
        status.setTextColor(Color.rgb(205, 205, 215));
        status.setTextSize(10.5f);
        overlay.addView(status, new LinearLayout.LayoutParams(-1, -2));

        codeInput = new EditText(this);
        codeInput.setSingleLine(true);
        codeInput.setHint("Escribe sólo el código de 6 dígitos");
        codeInput.setHintTextColor(Color.rgb(135, 135, 145));
        codeInput.setTextColor(Color.WHITE);
        codeInput.setTextSize(15);
        codeInput.setGravity(Gravity.CENTER);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setPadding(dp(10), 0, dp(10), 0);
        codeInput.setBackground(rounded(Color.rgb(45, 45, 54), 14));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, dp(46));
        inputLp.topMargin = dp(8);
        overlay.addView(codeInput, inputLp);

        TextView hint = new TextView(this);
        hint.setText("No salgas de Ajustes. Al completar los 6 números Irving OS vinculará automáticamente.");
        hint.setTextColor(Color.rgb(155, 155, 165));
        hint.setTextSize(9.5f);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(6);
        overlay.addView(hint, hintLp);

        codeInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s != null && s.length() > 6) {
                    codeInput.setText(s.subSequence(0, 6));
                    codeInput.setSelection(codeInput.length());
                    return;
                }
                tryPair();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.min(dp(350), getResources().getDisplayMetrics().widthPixels - dp(24)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(46);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        try {
            windowManager.addView(overlay, lp);
            codeInput.requestFocus();
            codeInput.postDelayed(() -> {
                try {
                    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(codeInput, InputMethodManager.SHOW_IMPLICIT);
                } catch (Throwable ignored) {}
            }, 350L);
        } catch (Throwable e) {
            stopSelf();
        }
    }

    private void startDiscovery() {
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            updateStatus("No pude iniciar la búsqueda del puerto. Usa el modo manual de Irving OS.");
            return;
        }

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String regType) {
                discoveryStarted = true;
                updateStatus("Esperando el puerto de emparejamiento…");
            }

            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (serviceInfo == null) return;
                String type = serviceInfo.getServiceType();
                if (type == null || !type.contains("_adb-tls-pairing")) return;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    try {
                        nsdManager.registerServiceInfoCallback(
                                serviceInfo,
                                getMainExecutor(),
                                new NsdManager.ServiceInfoCallback() {
                                    @Override public void onServiceInfoCallbackRegistrationFailed(int errorCode) {}
                                    @Override public void onServiceUpdated(NsdServiceInfo info) {
                                        acceptService(info);
                                    }
                                    @Override public void onServiceLost() {}
                                    @Override public void onServiceInfoCallbackUnregistered() {}
                                }
                        );
                        return;
                    } catch (Throwable ignored) {}
                }

                try {
                    nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {}
                        @Override public void onServiceResolved(NsdServiceInfo info) {
                            acceptService(info);
                        }
                    });
                } catch (Throwable ignored) {}
            }

            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {}
            @Override public void onDiscoveryStopped(String serviceType) { discoveryStarted = false; }
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                discoveryStarted = false;
                updateStatus("No pude detectar el puerto automáticamente. Puedes usar el modo manual.");
            }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                discoveryStarted = false;
            }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Throwable e) {
            updateStatus("No pude detectar el puerto automáticamente. Puedes usar el modo manual.");
        }
    }

    private void acceptService(NsdServiceInfo info) {
        if (info == null) return;
        int port = info.getPort();
        if (port <= 0) return;
        pairingPort = port;
        updateStatus("Puerto detectado automáticamente. Escribe el código de 6 dígitos.");
        tryPair();
    }

    private void tryPair() {
        if (pairing || pairingPort <= 0 || codeInput == null) return;
        String code = codeInput.getText().toString().trim();
        if (code.length() != 6) return;

        pairing = true;
        codeInput.setEnabled(false);
        updateStatus("Vinculando Irving OS…");
        IrvingLocalAdb.pair(getApplicationContext(), pairingPort, code, (ok, message) ->
                getMainExecutor().execute(() -> {
                    if (ok) {
                        updateStatus("✓ Vinculado correctamente");
                        if (codeInput != null) codeInput.setVisibility(android.view.View.GONE);
                        stopDiscovery();
                        if (overlay != null) overlay.postDelayed(this::stopSelf, 1200L);
                    } else {
                        pairing = false;
                        if (codeInput != null) {
                            codeInput.setEnabled(true);
                            codeInput.setText("");
                            codeInput.requestFocus();
                        }
                        updateStatus("No se pudo vincular. Genera un código nuevo e inténtalo otra vez.");
                    }
                })
        );
    }

    private void updateStatus(String value) {
        if (status == null) return;
        status.post(() -> {
            if (status != null) status.setText(value);
        });
    }

    private void stopDiscovery() {
        if (!discoveryStarted || nsdManager == null || discoveryListener == null) return;
        try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Throwable ignored) {}
        discoveryStarted = false;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID,
                        "Vinculación Irving OS",
                        NotificationManager.IMPORTANCE_MIN
                );
                ch.setDescription("Asistente temporal para la vinculación de Depuración inalámbrica");
                nm.createNotificationChannel(ch);
            }
        }
    }

    @Override
    public void onDestroy() {
        stopDiscovery();
        try {
            if (windowManager != null && overlay != null) windowManager.removeView(overlay);
        } catch (Throwable ignored) {}
        overlay = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
''', encoding='utf-8')

setup_path.write_text(r'''package com.marilu.miniflip;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
    private boolean waitingOverlayPermission = false;
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

    @Override protected void onResume() {
        super.onResume();
        if (waitingOverlayPermission && Settings.canDrawOverlays(this)) {
            waitingOverlayPermission = false;
            startEasyPairing();
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
                "La vinculación ahora es más sencilla: Irving OS detectará automáticamente el puerto. Tú sólo escribirás el código de 6 dígitos sin salir de Ajustes.",
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
            beginEasyPairing();
        });

        TextView steps = text(
                "1. Activa Depuración inalámbrica.\n" +
                "2. Toca “Vincular dispositivo con un código de vinculación”.\n" +
                "3. En el cuadro flotante de Irving OS escribe únicamente los 6 números.\n" +
                "El puerto se detecta solo y la vinculación comienza al escribir el sexto número.",
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

    private void beginEasyPairing() {
        if (!Settings.canDrawOverlays(this)) {
            waitingOverlayPermission = true;
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivity(intent);
                Toast.makeText(
                        this,
                        "Permite que Irving OS aparezca encima de otras aplicaciones. Sólo se usará durante esta vinculación.",
                        Toast.LENGTH_LONG
                ).show();
            } catch (Throwable e) {
                waitingOverlayPermission = false;
                Toast.makeText(this, "No pude abrir el permiso de ventana flotante", Toast.LENGTH_LONG).show();
            }
            return;
        }
        startEasyPairing();
    }

    private void startEasyPairing() {
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
                "Activa Depuración inalámbrica y toca “Vincular dispositivo con un código”.",
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

print('Applied Irving OS v0.26 easy wireless pairing')
