from pathlib import Path

build_path = Path('app/build.gradle')
manifest_path = Path('app/src/main/AndroidManifest.xml')
adb_path = Path('app/src/main/java/com/marilu/miniflip/IrvingLocalAdb.java')

build = build_path.read_text(encoding='utf-8')
build = build.replace('versionCode 27', 'versionCode 28')
build = build.replace("versionName '0.27-irving-os-pairing-notification'", "versionName '0.28-irving-os-power-rotation-fix'")
build_path.write_text(build, encoding='utf-8')

manifest = manifest_path.read_text(encoding='utf-8')
overlay = '    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />\n'
if overlay not in manifest:
    marker = '    <uses-permission android:name="android.permission.WRITE_SETTINGS" />\n'
    manifest = manifest.replace(marker, marker + overlay, 1)
manifest_path.write_text(manifest, encoding='utf-8')

adb = adb_path.read_text(encoding='utf-8')

old_pair_tail = '''                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PAIRED, true).apply();
                boolean connected = false;
                try { connected = manager.autoConnect(app, 8000); } catch (Throwable ignored) {}
                callback.onResult(true, connected ? "Vinculado y conectado" : "Vinculado. Se conectará automáticamente al usarlo.");
'''
new_pair_tail = '''                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PAIRED, true).apply();

                boolean connected = ensureTlsConnected(app, manager);
                callback.onResult(true, connected
                        ? "Vinculado y conectado"
                        : "Vinculado. Mantén Depuración inalámbrica activa para usar Apagar/Reiniciar.");
'''
if old_pair_tail not in adb:
    raise SystemExit('Could not find pair connection block')
adb = adb.replace(old_pair_tail, new_pair_tail, 1)

start = adb.index('    public static void executePower(')
end = adb.index('    private static String shortMessage(', start)
new_power = r'''    public static void executePower(Context context, boolean reboot, Callback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                IrvingAdbConnectionManager manager = IrvingAdbConnectionManager.getInstance(app);

                if (!ensureTlsConnected(app, manager)) {
                    callback.onResult(false,
                            "Irving OS está vinculado, pero no puede abrir la conexión ADB. " +
                            "Comprueba que Depuración inalámbrica siga activada.");
                    return;
                }

                String command = reboot ? "svc power reboot" : "svc power shutdown";
                AdbStream stream = null;
                try {
                    stream = manager.openStream("shell:" + command);
                    try { Thread.sleep(700L); } catch (InterruptedException ignored) {}
                    callback.onResult(true, reboot ? "Reiniciando" : "Apagando");
                } catch (Throwable commandError) {
                    if (!manager.isConnected()) {
                        callback.onResult(true, reboot ? "Reiniciando" : "Apagando");
                    } else {
                        callback.onResult(false,
                                "No se pudo ejecutar el comando de energía: " + shortMessage(commandError));
                    }
                } finally {
                    if (stream != null) {
                        try { stream.close(); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable e) {
                callback.onResult(false, "No se pudo usar ADB: " + shortMessage(e));
            }
        }, reboot ? "irving-local-reboot" : "irving-local-poweroff").start();
    }

    private static boolean ensureTlsConnected(Context app, IrvingAdbConnectionManager manager) {
        if (manager.isConnected()) return true;

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                boolean result = manager.connectTls(app, 5000);
                if (result || manager.isConnected()) return true;
            } catch (Throwable ignored) {}

            if (manager.isConnected()) return true;
            try { Thread.sleep(650L); } catch (InterruptedException ignored) {}
        }
        return manager.isConnected();
    }

'''
adb = adb[:start] + new_power + adb[end:]
adb_path.write_text(adb, encoding='utf-8')

print('Applied Irving OS v0.28 power + rotation permission fix')
