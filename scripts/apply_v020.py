from pathlib import Path

main_path = Path('app/src/main/java/com/marilu/miniflip/MainActivity.java')
rotation_path = Path('app/src/main/java/com/marilu/miniflip/RotationController.java')
launcher_path = Path('app/src/main/java/com/marilu/miniflip/CoverAppLauncher.java')
build_path = Path('app/build.gradle')
aidl_path = Path('app/src/main/aidl/com/marilu/miniflip/IRotationShellService.aidl')
service_path = Path('app/src/main/java/com/marilu/miniflip/RotationShellService.java')
bridge_path = Path('app/src/main/java/com/marilu/miniflip/ShizukuShellBridge.java')

# AIDL bridge used by a Shizuku UserService running as shell/root.
aidl_path.parent.mkdir(parents=True, exist_ok=True)
aidl_path.write_text('''package com.marilu.miniflip;\n\ninterface IRotationShellService {\n    int exec(String command);\n}\n''', encoding='utf-8')

service_path.write_text(r'''package com.marilu.miniflip;

import android.content.Context;

public final class RotationShellService extends IRotationShellService.Stub {
    public RotationShellService() {}
    public RotationShellService(Context context) {}

    @Override
    public int exec(String command) {
        if (command == null || command.isEmpty()) return -1;
        Process process = null;
        try {
            process = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor();
        } catch (Throwable ignored) {
            return -1;
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    public void destroy() {
        System.exit(0);
    }
}
''', encoding='utf-8')

bridge_path.write_text(r'''package com.marilu.miniflip;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public final class ShizukuShellBridge {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile IRotationShellService remote;
    private static volatile boolean binding;
    private static Shizuku.UserServiceArgs args;

    private ShizukuShellBridge() {}

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            remote = IRotationShellService.Stub.asInterface(service);
            binding = false;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remote = null;
            binding = false;
        }
    };

    public static boolean isAvailable() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void bind(Context context) {
        if (context == null || !isAvailable() || remote != null || binding) return;
        binding = true;
        try {
            ComponentName component = new ComponentName(
                    context.getPackageName(),
                    RotationShellService.class.getName()
            );
            args = new Shizuku.UserServiceArgs(component)
                    .processNameSuffix("rotation")
                    .daemon(false)
                    .version(20);
            Shizuku.bindUserService(args, CONNECTION);
        } catch (Throwable ignored) {
            binding = false;
        }
    }

    public static void execAsync(Context context, String command) {
        bind(context);
        EXECUTOR.execute(() -> execBlocking(command));
    }

    public static boolean execBlocking(String command) {
        if (!isAvailable()) return false;
        IRotationShellService service = remote;
        if (service != null) {
            try {
                return service.exec(command) == 0;
            } catch (Throwable ignored) {
                remote = null;
            }
        }

        // Transitional fallback for devices where UserService takes longer to bind.
        Object process = null;
        try {
            Method newProcess = Shizuku.class.getDeclaredMethod(
                    "newProcess",
                    String[].class,
                    String[].class,
                    String.class
            );
            newProcess.setAccessible(true);
            process = newProcess.invoke(
                    null,
                    new Object[]{new String[]{"sh", "-c", command}, null, null}
            );
            if (process == null) return false;
            Method waitFor = process.getClass().getMethod("waitFor");
            Object result = waitFor.invoke(process);
            return result instanceof Integer && ((Integer) result) == 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (process != null) {
                try {
                    Method destroy = process.getClass().getMethod("destroy");
                    destroy.invoke(process);
                } catch (Throwable ignored) {}
            }
        }
    }

    public static void preparePackage(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty() || !isAvailable()) return;
        String pkg = shellQuote(packageName);
        execAsync(context,
                "am compat enable OVERRIDE_ANY_ORIENTATION_TO_USER " + pkg
                        + " >/dev/null 2>&1 || true; "
                        + "am compat enable OVERRIDE_ANY_ORIENTATION " + pkg
                        + " >/dev/null 2>&1 || true; "
                        + "am compat enable FORCE_RESIZE_APP " + pkg
                        + " >/dev/null 2>&1 || true"
        );
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
''', encoding='utf-8')

# Make RotationController use the reliable UserService bridge instead of only
# depending on Shizuku.newProcess reflection.
rotation = rotation_path.read_text(encoding='utf-8')
rotation = rotation.replace(
    '            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);',
    '            ShizukuShellBridge.bind(this);\n\n            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);',
    1
)
start_anchor = '''            prepareExternalDisplayRotation();\n            return START_STICKY;'''
rotation = rotation.replace(
    start_anchor,
    '''            ShizukuShellBridge.bind(this);\n            prepareExternalDisplayRotation();\n            return START_STICKY;''',
    1
)
old_run = '''        private boolean runShell(String command) {\n            if (!hasShizukuPermission()) return false;\n\n            Object remoteProcess = null;\n            try {\n                Method newProcess = Shizuku.class.getDeclaredMethod(\n                        "newProcess",\n                        String[].class,\n                        String[].class,\n                        String.class\n                );\n                newProcess.setAccessible(true);\n\n                remoteProcess = newProcess.invoke(\n                        null,\n                        new Object[]{\n                                new String[]{"sh", "-c", command},\n                                null,\n                                null\n                        }\n                );\n\n                if (remoteProcess == null) return false;\n\n                Method waitFor = remoteProcess.getClass().getMethod("waitFor");\n                Object result = waitFor.invoke(remoteProcess);\n                return result instanceof Integer && ((Integer) result) == 0;\n            } catch (Throwable ignored) {\n                return false;\n            } finally {\n                if (remoteProcess != null) {\n                    try {\n                        Method destroy = remoteProcess.getClass().getMethod("destroy");\n                        destroy.invoke(remoteProcess);\n                    } catch (Throwable ignored) {}\n                }\n            }\n        }\n'''
new_run = '''        private boolean runShell(String command) {\n            return ShizukuShellBridge.execBlocking(command);\n        }\n'''
if old_run not in rotation:
    raise SystemExit('Could not find RotationController runShell method')
rotation = rotation.replace(old_run, new_run, 1)
rotation_path.write_text(rotation, encoding='utf-8')

# Prepare each launched package so Android ignores fixed-orientation requests.
launcher = launcher_path.read_text(encoding='utf-8')
launcher = launcher.replace(
    '        RotationController.startRotationEnforcer(activity);\n        final int displayId = resolveDisplayId(activity);',
    '        RotationController.startRotationEnforcer(activity);\n        ShizukuShellBridge.bind(activity);\n        ShizukuShellBridge.preparePackage(activity, packageName);\n        final int displayId = resolveDisplayId(activity);',
    1
)
launcher_path.write_text(launcher, encoding='utf-8')

# One-time wallpaper migration: discard the broken/stale saved URI from earlier
# test builds so the packaged Irving landscape is visible again. Future user
# wallpaper choices remain persistent.
main = main_path.read_text(encoding='utf-8')
main = main.replace(
    '        loadLauncherApps();\n        buildUi();',
    '''        SharedPreferences migrationPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);\n        if (!migrationPrefs.getBoolean("v20_wallpaper_migrated", false)) {\n            migrationPrefs.edit()\n                    .remove(WALLPAPER_URI)\n                    .putBoolean("v20_wallpaper_migrated", true)\n                    .apply();\n        }\n\n        loadLauncherApps();\n        buildUi();''',
    1
)
main_path.write_text(main, encoding='utf-8')

build = build_path.read_text(encoding='utf-8')
build = build.replace('versionCode 15', 'versionCode 20')
build = build.replace("versionName '0.15-irving-os-visual-assets'", "versionName '0.20-irving-os-userservice-rotation'")
build_path.write_text(build, encoding='utf-8')

print('Applied Irving OS v0.20 UserService rotation and wallpaper migration patch')
