from pathlib import Path

main_path = Path('app/src/main/java/com/marilu/miniflip/MainActivity.java')
app_path = Path('app/src/main/java/com/marilu/miniflip/IrvingApplication.java')
manifest_path = Path('app/src/main/AndroidManifest.xml')
build_path = Path('app/build.gradle')
settings_path = Path('settings.gradle')

# ---------------------------------------------------------------------------
# Gradle repositories/dependencies for embedded local ADB pairing.
# libadb-android is dual GPL-3.0-or-later / Apache-2.0; Irving OS uses it
# under Apache-2.0. sun-security-android is used to generate the ADB TLS cert.
# ---------------------------------------------------------------------------
settings = settings_path.read_text(encoding='utf-8')
if 'https://jitpack.io' not in settings:
    settings = settings.replace(
        '        mavenCentral()\n    }\n}\nrootProject.name',
        '        mavenCentral()\n        maven { url "https://jitpack.io" }\n    }\n}\nrootProject.name',
        1
    )
settings_path.write_text(settings, encoding='utf-8')

build = build_path.read_text(encoding='utf-8')
if "com.github.MuntashirAkon:libadb-android:3.1.1" not in build:
    build = build.replace(
        "    implementation 'dev.rikka.shizuku:provider:13.1.5'\n",
        "    implementation 'dev.rikka.shizuku:provider:13.1.5'\n"
        "    implementation 'com.github.MuntashirAkon:libadb-android:3.1.1'\n"
        "    implementation 'com.github.MuntashirAkon:sun-security-android:1.1'\n"
        "    implementation 'org.conscrypt:conscrypt-android:2.5.3'\n",
        1
    )
build = build.replace('versionCode 24', 'versionCode 25')
build = build.replace("versionName '0.24-irving-os-dialer-permission'", "versionName '0.25-irving-os-local-adb-statusbar'")
build_path.write_text(build, encoding='utf-8')

# ---------------------------------------------------------------------------
# Manifest: local network access for Wireless Debugging and setup activity.
# ---------------------------------------------------------------------------
manifest = manifest_path.read_text(encoding='utf-8')
for permission in [
    'android.permission.INTERNET',
    'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.ACCESS_WIFI_STATE',
    'android.permission.CHANGE_WIFI_MULTICAST_STATE',
    'android.permission.NFC',
]:
    line = f'    <uses-permission android:name="{permission}" />\n'
    if line not in manifest:
        marker = '    <uses-permission android:name="android.permission.WRITE_SETTINGS" />\n'
        manifest = manifest.replace(marker, marker + line, 1)

activity = '''\n        <activity\n            android:name=".IrvingAdbSetupActivity"\n            android:exported="false"\n            android:screenOrientation="fullSensor"\n            android:theme="@android:style/Theme.Material.NoActionBar" />\n'''
if 'android:name=".IrvingAdbSetupActivity"' not in manifest:
    insert_at = manifest.index('        <activity\n            android:name=".WidgetLaunchActivity"')
    manifest = manifest[:insert_at] + activity + '\n' + manifest[insert_at:]
manifest_path.write_text(manifest, encoding='utf-8')

# ---------------------------------------------------------------------------
# Application: apply libadb PRNG fix once.
# ---------------------------------------------------------------------------
app = app_path.read_text(encoding='utf-8')
if 'io.github.muntashirakon.adb.PRNGFixes' not in app:
    app = app.replace('import java.util.Map;\n', 'import java.util.Map;\n\nimport io.github.muntashirakon.adb.PRNGFixes;\n', 1)
if 'PRNGFixes.apply();' not in app:
    app = app.replace('        super.onCreate();\n        initializeDefaultLayout();',
                      '        super.onCreate();\n        try { PRNGFixes.apply(); } catch (Throwable ignored) {}\n        initializeDefaultLayout();', 1)
app_path.write_text(app, encoding='utf-8')

# ---------------------------------------------------------------------------
# Embedded ADB connection manager. Based on the Apache-2.0 option of
# MuntashirAkon/libadb-android's sample connection manager.
# ---------------------------------------------------------------------------
Path('app/src/main/java/com/marilu/miniflip/IrvingAdbConnectionManager.java').write_text(r'''package com.marilu.miniflip;

import android.content.Context;
import android.os.Build;
import android.sun.misc.BASE64Encoder;
import android.sun.security.provider.X509Factory;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

final class IrvingAdbConnectionManager extends AbsAdbConnectionManager {
    private static IrvingAdbConnectionManager instance;
    private PrivateKey privateKey;
    private Certificate certificate;

    static synchronized IrvingAdbConnectionManager getInstance(Context context) throws Exception {
        if (instance == null) instance = new IrvingAdbConnectionManager(context.getApplicationContext());
        return instance;
    }

    private IrvingAdbConnectionManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        privateKey = readPrivateKey(context);
        certificate = readCertificate(context);
        if (privateKey == null || certificate == null) generateIdentity(context);
    }

    private void generateIdentity(Context context) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
        KeyPair pair = generator.generateKeyPair();
        PublicKey publicKey = pair.getPublic();
        privateKey = pair.getPrivate();

        String algorithm = "SHA512withRSA";
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 60_000L);
        Date notAfter = new Date(now + 10L * 365L * 24L * 60L * 60L * 1000L);
        X500Name name = new X500Name("CN=Irving OS");
        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(
                new KeyIdentifier(publicKey).getIdentifier()));
        extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));

        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
        info.set("subject", new CertificateSubjectName(name));
        info.set("issuer", new CertificateIssuerName(name));
        info.set("key", new CertificateX509Key(publicKey));
        info.set("validity", new CertificateValidity(notBefore, notAfter));
        info.set("extensions", extensions);
        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(privateKey, algorithm);
        certificate = cert;

        writePrivateKey(context, privateKey);
        writeCertificate(context, certificate);
    }

    @Override protected PrivateKey getPrivateKey() { return privateKey; }
    @Override protected Certificate getCertificate() { return certificate; }
    @Override protected String getDeviceName() { return "Irving OS"; }

    private static File keyFile(Context context) { return new File(context.getFilesDir(), "irving_adb_private.key"); }
    private static File certFile(Context context) { return new File(context.getFilesDir(), "irving_adb_cert.pem"); }

    private static PrivateKey readPrivateKey(Context context) {
        File file = keyFile(context);
        if (!file.exists()) return null;
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int total = 0;
            while (total < bytes.length) {
                int n = input.read(bytes, total, bytes.length - total);
                if (n < 0) break;
                total += n;
            }
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Throwable ignored) { return null; }
    }

    private static Certificate readCertificate(Context context) {
        File file = certFile(context);
        if (!file.exists()) return null;
        try (InputStream input = new FileInputStream(file)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(input);
        } catch (Throwable ignored) { return null; }
    }

    private static void writePrivateKey(Context context, PrivateKey key) throws IOException {
        try (OutputStream output = new FileOutputStream(keyFile(context))) {
            output.write(key.getEncoded());
        }
    }

    private static void writeCertificate(Context context, Certificate cert) throws Exception {
        BASE64Encoder encoder = new BASE64Encoder();
        try (OutputStream output = new FileOutputStream(certFile(context))) {
            output.write(X509Factory.BEGIN_CERT.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            encoder.encode(cert.getEncoded(), output);
            output.write('\n');
            output.write(X509Factory.END_CERT.getBytes(StandardCharsets.UTF_8));
        }
    }
}
''', encoding='utf-8')

# ---------------------------------------------------------------------------
# Local ADB helper: pair once, reconnect automatically, run privileged shell.
# ---------------------------------------------------------------------------
Path('app/src/main/java/com/marilu/miniflip/IrvingLocalAdb.java').write_text(r'''package com.marilu.miniflip;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.android.AndroidUtils;

public final class IrvingLocalAdb {
    private static final String PREFS = "miniflip_prefs";
    private static final String PAIRED = "irving_local_adb_paired";

    public interface Callback { void onResult(boolean ok, String message); }

    private IrvingLocalAdb() {}

    public static boolean isConfigured(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PAIRED, false);
    }

    public static void clearConfiguration(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PAIRED).apply();
    }

    public static void pair(Context context, int port, String code, Callback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    callback.onResult(false, "Se requiere Android 11 o posterior");
                    return;
                }
                IrvingAdbConnectionManager manager = IrvingAdbConnectionManager.getInstance(app);
                String host = AndroidUtils.getHostIpAddress(app);
                boolean paired = manager.pair(host, port, code);
                if (!paired) {
                    callback.onResult(false, "No se pudo vincular. Revisa el código y el puerto.");
                    return;
                }
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PAIRED, true).apply();
                boolean connected = false;
                try { connected = manager.autoConnect(app, 8000); } catch (Throwable ignored) {}
                callback.onResult(true, connected ? "Vinculado y conectado" : "Vinculado. Se conectará automáticamente al usarlo.");
            } catch (Throwable e) {
                callback.onResult(false, "Error de vinculación: " + shortMessage(e));
            }
        }, "irving-adb-pair").start();
    }

    public static void executePower(Context context, boolean reboot, Callback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                IrvingAdbConnectionManager manager = IrvingAdbConnectionManager.getInstance(app);
                boolean connected = false;
                try { connected = manager.autoConnect(app, 9000); } catch (Throwable ignored) {}
                if (!connected) {
                    callback.onResult(false, "Activa Depuración inalámbrica para usar esta función.");
                    return;
                }
                String command = reboot ? "reboot" : "reboot -p";
                try {
                    AdbStream stream = manager.openStream("shell:" + command);
                    try {
                        InputStream input = stream.openInputStream();
                        byte[] buffer = new byte[256];
                        input.read(buffer);
                    } catch (Throwable ignored) {}
                    try { stream.close(); } catch (Throwable ignored) {}
                    callback.onResult(true, reboot ? "Reiniciando" : "Apagando");
                } catch (Throwable commandError) {
                    // A reboot commonly drops the ADB socket before a normal response.
                    callback.onResult(true, reboot ? "Reiniciando" : "Apagando");
                }
            } catch (Throwable e) {
                callback.onResult(false, "No se pudo conectar por ADB: " + shortMessage(e));
            }
        }, reboot ? "irving-local-reboot" : "irving-local-poweroff").start();
    }

    private static String shortMessage(Throwable e) {
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        return s.length() > 90 ? s.substring(0, 90) : s;
    }
}
''', encoding='utf-8')

# ---------------------------------------------------------------------------
# One-time pairing UI. This replaces the need to install Shizuku for power
# controls. Android still requires Wireless Debugging to be enabled and paired.
# ---------------------------------------------------------------------------
Path('app/src/main/java/com/marilu/miniflip/IrvingAdbSetupActivity.java').write_text(r'''package com.marilu.miniflip;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class IrvingAdbSetupActivity extends Activity {
    private EditText portInput;
    private EditText codeInput;
    private TextView status;

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(18));
        root.setBackgroundColor(Color.rgb(10, 10, 12));

        TextView title = text("Funciones avanzadas", 20, Color.WHITE, true);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(36)));

        TextView info = text(
                "Irving OS puede vincularse directamente con la Depuración inalámbrica de Android. Esto sustituye a Shizuku para Apagar y Reiniciar. La vinculación se hace una sola vez.",
                12, Color.rgb(205,205,210), false);
        root.addView(info, new LinearLayout.LayoutParams(-1, -2));

        TextView open = button("1. Abrir Depuración inalámbrica");
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(-1, dp(44));
        openLp.setMargins(0, dp(12), 0, dp(10));
        root.addView(open, openLp);
        open.setOnClickListener(v -> {
            Toast.makeText(this,
                    "Activa Depuración inalámbrica y elige ‘Vincular dispositivo con código’. Anota el puerto y el código.",
                    Toast.LENGTH_LONG).show();
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (Throwable ignored) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });

        portInput = input("Puerto de emparejamiento");
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(portInput, new LinearLayout.LayoutParams(-1, dp(44)));

        codeInput = input("Código de 6 dígitos");
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(-1, dp(44));
        codeLp.setMargins(0, dp(8), 0, 0);
        root.addView(codeInput, codeLp);

        TextView pair = button("2. Vincular Irving OS");
        LinearLayout.LayoutParams pairLp = new LinearLayout.LayoutParams(-1, dp(46));
        pairLp.setMargins(0, dp(12), 0, 0);
        root.addView(pair, pairLp);
        pair.setOnClickListener(v -> beginPairing(pair));

        status = text(IrvingLocalAdb.isConfigured(this) ? "Ya existe una vinculación guardada." : "Sin vincular", 11,
                Color.rgb(155,155,165), false);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, dp(38));
        statusLp.setMargins(0, dp(7), 0, 0);
        root.addView(status, statusLp);

        TextView note = text(
                "Si el código cambia al volver a Irving OS, haz la primera vinculación con el teléfono abierto usando pantalla dividida o ventana emergente. Después ya no tendrás que repetirla.",
                10, Color.rgb(135,135,145), false);
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private void beginPairing(TextView pairButton) {
        String p = portInput.getText().toString().trim();
        String code = codeInput.getText().toString().trim();
        int port;
        try { port = Integer.parseInt(p); } catch (Throwable e) { port = -1; }
        if (port <= 0 || code.length() < 6) {
            Toast.makeText(this, "Escribe el puerto y el código de emparejamiento", Toast.LENGTH_LONG).show();
            return;
        }
        pairButton.setEnabled(false);
        pairButton.setAlpha(0.55f);
        status.setText("Vinculando…");
        final int finalPort = port;
        IrvingLocalAdb.pair(this, finalPort, code, (ok, message) -> runOnUiThread(() -> {
            pairButton.setEnabled(true);
            pairButton.setAlpha(1f);
            status.setText(message);
            status.setTextColor(ok ? Color.rgb(96, 220, 150) : Color.rgb(255, 135, 135));
            if (ok) {
                Toast.makeText(this, "Funciones avanzadas activadas", Toast.LENGTH_LONG).show();
                status.postDelayed(this::finish, 1000);
            }
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
        v.setTextSize(14);
        v.setPadding(dp(13), 0, dp(13), 0);
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

# ---------------------------------------------------------------------------
# MainActivity: fixed Samsung-like status strip on Home + local ADB power flow.
# ---------------------------------------------------------------------------
main = main_path.read_text(encoding='utf-8')

# Add status views.
field_anchor = '    private TextView homeSettingsButton;\n'
if 'private TextView homeStatusTime;' not in main:
    main = main.replace(field_anchor,
                        field_anchor + '    private TextView homeStatusTime;\n    private TextView homeStatusInfo;\n', 1)

# Add status runnable before dp().
dp_anchor = '    private int dp(float v) {\n'
if 'private final Runnable homeStatusRunnable' not in main:
    status_code = r'''    private final Runnable homeStatusRunnable = new Runnable() {
        @Override public void run() {
            updateHomeStatusBar();
            gestureHandler.postDelayed(this, 30000L);
        }
    };

    private void updateHomeStatusBar() {
        if (homeStatusTime == null || homeStatusInfo == null) return;
        java.util.Calendar c = java.util.Calendar.getInstance();
        int hour = c.get(java.util.Calendar.HOUR);
        if (hour == 0) hour = 12;
        homeStatusTime.setText(String.format(Locale.getDefault(), "%d:%02d", hour, c.get(java.util.Calendar.MINUTE)));

        int battery = -1;
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm != null) battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Throwable ignored) {}
        boolean wifi = false;
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifi = wm != null && wm.isWifiEnabled();
        } catch (Throwable ignored) {}
        boolean bluetooth = false;
        try { bluetooth = Settings.Global.getInt(getContentResolver(), "bluetooth_on", 0) == 1; }
        catch (Throwable ignored) {}
        boolean nfc = false;
        try {
            android.nfc.NfcAdapter n = android.nfc.NfcAdapter.getDefaultAdapter(this);
            nfc = n != null && n.isEnabled();
        } catch (Throwable ignored) {}
        boolean silent = false;
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            silent = am != null && am.getRingerMode() != android.media.AudioManager.RINGER_MODE_NORMAL;
        } catch (Throwable ignored) {}

        StringBuilder s = new StringBuilder();
        if (bluetooth) s.append("BT  ");
        if (nfc) s.append("N  ");
        if (silent) s.append("M  ");
        if (wifi) s.append("Wi‑Fi  ");
        s.append("▮▮  ");
        if (battery >= 0) s.append(battery).append('%');
        homeStatusInfo.setText(s.toString());
    }

'''
    main = main.replace(dp_anchor, status_code + dp_anchor, 1)

# Insert status strip at the top of Home, then shift title/grid downward slightly.
home_anchor = '        page.setClickable(true);\n\n        TextView title = new TextView(this);\n'
if 'homeStatusTime = new TextView(this);' not in main:
    strip = r'''        page.setClickable(true);

        LinearLayout statusStrip = new LinearLayout(this);
        statusStrip.setOrientation(LinearLayout.HORIZONTAL);
        statusStrip.setGravity(Gravity.CENTER_VERTICAL);
        statusStrip.setPadding(dp(8), 0, dp(8), 0);

        homeStatusTime = new TextView(this);
        homeStatusTime.setTextColor(Color.WHITE);
        homeStatusTime.setTextSize(9.5f);
        homeStatusTime.setTypeface(Typeface.DEFAULT_BOLD);
        homeStatusTime.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        homeStatusTime.setShadowLayer(3f, 0f, 1f, Color.BLACK);
        statusStrip.addView(homeStatusTime, new LinearLayout.LayoutParams(0, dp(20), 1f));

        homeStatusInfo = new TextView(this);
        homeStatusInfo.setTextColor(Color.WHITE);
        homeStatusInfo.setTextSize(8.3f);
        homeStatusInfo.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        homeStatusInfo.setSingleLine(true);
        homeStatusInfo.setShadowLayer(3f, 0f, 1f, Color.BLACK);
        statusStrip.addView(homeStatusInfo, new LinearLayout.LayoutParams(-2, dp(20)));

        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(-1, dp(20), Gravity.TOP);
        statusLp.leftMargin = dp(4);
        statusLp.rightMargin = dp(4);
        statusLp.topMargin = dp(1);
        page.addView(statusStrip, statusLp);
        updateHomeStatusBar();
        gestureHandler.removeCallbacks(homeStatusRunnable);
        gestureHandler.postDelayed(homeStatusRunnable, 30000L);

        TextView title = new TextView(this);
'''
    main = main.replace(home_anchor, strip, 1)
    main = main.replace('        titleLp.topMargin = dp(5);', '        titleLp.topMargin = dp(20);', 1)
    main = main.replace('        gridLp.topMargin = dp(42);', '        gridLp.topMargin = dp(54);', 1)

# Stop status timer on destroy.
if 'gestureHandler.removeCallbacks(homeStatusRunnable);' not in main[main.index('protected void onDestroy()'):main.index('protected void onDestroy()')+600]:
    main = main.replace('        gestureHandler.removeCallbacks(dockLongPressRunnable);\n',
                        '        gestureHandler.removeCallbacks(dockLongPressRunnable);\n        gestureHandler.removeCallbacks(homeStatusRunnable);\n', 1)

# Replace v0.24 settings/power block with local ADB flow.
settings_start = main.index('    private void showIrvingSettings() {')
settings_end = main.index('    private void showDockSizeDialog() {', settings_start)
new_settings = r'''    private void showIrvingSettings() {
        List<String> items = new ArrayList<>();
        items.add("Editar pantalla principal");
        items.add("Editar Dock");
        items.add("Tamaño de iconos del Dock");
        items.add("Ampliación del Dock al tocar");
        items.add("Cambiar imagen de fondo");
        items.add("Restaurar fondo predeterminado");
        items.add("Activar rotación completa de pantalla externa");
        if (!IrvingLocalAdb.isConfigured(this)) {
            items.add("Activar funciones avanzadas");
        }
        items.add("Apagar teléfono");
        items.add("Reiniciar teléfono");
        items.add("Ajustes del teléfono");

        final String[] menu = items.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Irving OS")
                .setItems(menu, (dialog, which) -> {
                    String selected = menu[which];
                    if ("Editar pantalla principal".equals(selected)) showHomeManager();
                    else if ("Editar Dock".equals(selected)) showDockManager();
                    else if ("Tamaño de iconos del Dock".equals(selected)) showDockSizeDialog();
                    else if ("Ampliación del Dock al tocar".equals(selected)) showDockMagnifyDialog();
                    else if ("Cambiar imagen de fondo".equals(selected)) pickWallpaper();
                    else if ("Restaurar fondo predeterminado".equals(selected)) clearWallpaper();
                    else if ("Activar rotación completa de pantalla externa".equals(selected)) enableForcedRotation();
                    else if ("Activar funciones avanzadas".equals(selected)) openLocalAdbSetup();
                    else if ("Apagar teléfono".equals(selected)) confirmPowerAction(false);
                    else if ("Reiniciar teléfono".equals(selected)) confirmPowerAction(true);
                    else if ("Ajustes del teléfono".equals(selected)) openSettings();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void openLocalAdbSetup() {
        try {
            Intent intent = new Intent(this, IrvingAdbSetupActivity.class);
            Bundle options = ActivityOptions.makeBasic().setLaunchDisplayId(currentDisplayId()).toBundle();
            startActivity(intent, options);
        } catch (Throwable e) {
            startActivity(new Intent(this, IrvingAdbSetupActivity.class));
        }
    }

    private void confirmPowerAction(boolean reboot) {
        if (!IrvingLocalAdb.isConfigured(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Activar funciones avanzadas")
                    .setMessage("Primero hay que vincular Irving OS con la Depuración inalámbrica de Android. Ya no necesitas instalar Shizuku.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Configurar", (d, w) -> openLocalAdbSetup())
                    .show();
            return;
        }

        String title = reboot ? "Reiniciar teléfono" : "Apagar teléfono";
        String message = reboot ? "¿Quieres reiniciar el teléfono ahora?" : "¿Quieres apagar el teléfono ahora?";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton(reboot ? "Reiniciar" : "Apagar", (dialog, which) -> executeLocalPowerAction(reboot))
                .show();
    }

    private void executeLocalPowerAction(boolean reboot) {
        Toast.makeText(this, reboot ? "Preparando reinicio…" : "Preparando apagado…", Toast.LENGTH_SHORT).show();
        IrvingLocalAdb.executePower(this, reboot, (ok, message) -> runOnUiThread(() -> {
            if (!ok) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Funciones avanzadas")
                        .setMessage(message + "\n\nAbre la configuración para revisar la Depuración inalámbrica.")
                        .setNegativeButton("Cerrar", null)
                        .setPositiveButton("Configurar", (d, w) -> openLocalAdbSetup())
                        .show();
            }
        }));
    }

'''
main = main[:settings_start] + new_settings + main[settings_end:]
main_path.write_text(main, encoding='utf-8')

print('Applied Irving OS v0.25 embedded local ADB + fixed Home status bar')
