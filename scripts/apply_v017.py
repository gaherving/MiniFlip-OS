from pathlib import Path

main_path = Path('app/src/main/java/com/marilu/miniflip/MainActivity.java')
build_path = Path('app/build.gradle')
launcher_path = Path('app/src/main/java/com/marilu/miniflip/CoverAppLauncher.java')
widget_path = Path('app/src/main/java/com/marilu/miniflip/WidgetLaunchActivity.java')

text = main_path.read_text(encoding='utf-8')

old = '''    private void openApp(AppEntry app) {\n        RotationController.startRotationEnforcer(this);\n\n        try {\n            Intent i = new Intent(Intent.ACTION_MAIN);\n            i.addCategory(Intent.CATEGORY_LAUNCHER);\n            i.setClassName(app.packageName, app.activityName);\n            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK\n                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);\n\n            Bundle options = ActivityOptions.makeBasic()\n                    .setLaunchDisplayId(COVER_DISPLAY_ID)\n                    .toBundle();\n\n            startActivity(i, options);\n        } catch (Exception e) {\n            try {\n                Intent fallback = getPackageManager().getLaunchIntentForPackage(app.packageName);\n                if (fallback != null) {\n                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK\n                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);\n\n                    Bundle options = ActivityOptions.makeBasic()\n                            .setLaunchDisplayId(COVER_DISPLAY_ID)\n                            .toBundle();\n\n                    startActivity(fallback, options);\n                    return;\n                }\n            } catch (Exception ignored) {}\n\n            Toast.makeText(this,\n                    "No se pudo abrir " + app.label,\n                    Toast.LENGTH_SHORT).show();\n        }\n    }\n'''
new = '''    private void openApp(AppEntry app) {\n        CoverAppLauncher.launch(\n                this,\n                app.packageName,\n                app.activityName,\n                app.label\n        );\n    }\n'''
if old not in text:
    raise SystemExit('Could not find openApp method for v0.17 patch')
text = text.replace(old, new, 1)

old = '''    private void openSettings() {\n        RotationController.startRotationEnforcer(this);\n        try {\n            Bundle options = ActivityOptions.makeBasic()\n                    .setLaunchDisplayId(COVER_DISPLAY_ID)\n                    .toBundle();\n            startActivity(new Intent(Settings.ACTION_SETTINGS), options);\n        } catch (Exception e) {\n            Toast.makeText(this,\n                    "No se pudieron abrir Ajustes",\n                    Toast.LENGTH_SHORT).show();\n        }\n    }\n'''
new = '''    private void openSettings() {\n        CoverAppLauncher.launchSystemSettings(this);\n    }\n'''
if old not in text:
    raise SystemExit('Could not find openSettings method for v0.17 patch')
text = text.replace(old, new, 1)
main_path.write_text(text, encoding='utf-8')

launcher_path.write_text(r'''package com.marilu.miniflip;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Display;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public final class CoverAppLauncher {
    private static final int FALLBACK_COVER_DISPLAY_ID = 1;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private CoverAppLauncher() {}

    public static void launch(Activity activity,
                              String packageName,
                              String activityName,
                              String label) {
        if (activity == null || packageName == null || packageName.isEmpty()) return;

        RotationController.startRotationEnforcer(activity);
        final int displayId = resolveDisplayId(activity);

        // First use the exact launcher component on the external display. This is
        // the same launch path used by the earlier working Irving OS versions and
        // avoids Samsung Phone's "Open phone to continue" gate that ACTION_DIAL
        // can trigger on the cover screen.
        if (launchExplicit(activity, packageName, activityName, displayId)) return;

        // Then try the package's normal launcher intent.
        if (launchPackageIntent(activity, packageName, displayId)) return;

        // Only use shell/Shizuku as a fallback. A successful shell command can
        // still land on Samsung's restriction screen, so it must not be the first
        // route for Phone or apps that already launch normally on Flex Window.
        if (RotationController.hasShizukuPermission()) {
            EXECUTOR.execute(() -> {
                boolean started = launchWithShell(packageName, activityName, displayId);
                if (!started) {
                    activity.runOnUiThread(() -> {
                        if (!launchDialFallback(activity, packageName, displayId)) {
                            Toast.makeText(activity,
                                    "No se pudo abrir " + (label == null ? packageName : label),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
            return;
        }

        if (!launchDialFallback(activity, packageName, displayId)) {
            Toast.makeText(activity,
                    "No se pudo abrir " + (label == null ? packageName : label),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public static void launchSystemSettings(Activity activity) {
        if (activity == null) return;
        RotationController.startRotationEnforcer(activity);
        int displayId = resolveDisplayId(activity);
        try {
            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(displayId)
                    .toBundle();
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS), options);
        } catch (Exception e) {
            Toast.makeText(activity,
                    "No se pudieron abrir Ajustes",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static int resolveDisplayId(Activity activity) {
        try {
            Display display = activity.getDisplay();
            if (display != null && display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                return display.getDisplayId();
            }
        } catch (Throwable ignored) {}
        return FALLBACK_COVER_DISPLAY_ID;
    }

    private static boolean isPhonePackage(String packageName) {
        if (packageName == null) return false;
        String p = packageName.toLowerCase();
        return "com.samsung.android.dialer".equals(packageName)
                || "com.android.dialer".equals(packageName)
                || "com.google.android.dialer".equals(packageName)
                || p.contains("dialer")
                || p.contains("telephony");
    }

    private static boolean launchExplicit(Activity activity,
                                          String packageName,
                                          String activityName,
                                          int displayId) {
        if (activityName == null || activityName.isEmpty()) return false;
        try {
            Intent target = new Intent(Intent.ACTION_MAIN);
            target.addCategory(Intent.CATEGORY_LAUNCHER);
            target.setClassName(packageName, activityName);
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(displayId)
                    .toBundle();
            activity.startActivity(target, options);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean launchPackageIntent(Activity activity,
                                               String packageName,
                                               int displayId) {
        try {
            Intent target = activity.getPackageManager().getLaunchIntentForPackage(packageName);
            if (target == null) return false;
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(displayId)
                    .toBundle();
            activity.startActivity(target, options);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean launchDialFallback(Activity activity,
                                              String packageName,
                                              int displayId) {
        if (!isPhonePackage(packageName)) return false;
        try {
            Intent dial = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:"));
            dial.setPackage(packageName);
            dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(displayId)
                    .toBundle();
            activity.startActivity(dial, options);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean launchWithShell(String packageName,
                                           String activityName,
                                           int displayId) {
        if (!RotationController.hasShizukuPermission()) return false;

        // Match the normal explicit-component launch before trying DIAL.
        if (activityName != null && !activityName.isEmpty()) {
            String component = packageName + "/" + activityName;
            String main = "am start --display " + displayId
                    + " -a android.intent.action.MAIN"
                    + " -c android.intent.category.LAUNCHER"
                    + " -n " + shellQuote(component);
            if (runShell(main)) return true;

            String direct = "am start --display " + displayId
                    + " -n " + shellQuote(component);
            if (runShell(direct)) return true;
        }

        if (isPhonePackage(packageName)) {
            String dial = "am start --display " + displayId
                    + " -a android.intent.action.DIAL -p " + shellQuote(packageName);
            if (runShell(dial)) return true;
        }

        String monkey = "monkey --pct-syskeys 0 -p " + shellQuote(packageName)
                + " -c android.intent.category.LAUNCHER 1";
        return runShell(monkey);
    }

    private static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean runShell(String command) {
        if (!RotationController.hasShizukuPermission()) return false;
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
}
''', encoding='utf-8')

widget = widget_path.read_text(encoding='utf-8')
widget = widget.replace('''    private static final int COVER_DISPLAY_ID = 1;\n''', '''    private static final int COVER_DISPLAY_ID = 1;\n\n    private int currentDisplayId() {\n        try {\n            android.view.Display display = getDisplay();\n            if (display != null && display.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) {\n                return display.getDisplayId();\n            }\n        } catch (Throwable ignored) {}\n        return COVER_DISPLAY_ID;\n    }\n''', 1)
widget = widget.replace('.setLaunchDisplayId(COVER_DISPLAY_ID)', '.setLaunchDisplayId(currentDisplayId())')
widget_path.write_text(widget, encoding='utf-8')

build = build_path.read_text(encoding='utf-8')
build = build.replace('versionCode 16', 'versionCode 17')
build = build.replace("versionName '0.16-irving-os-fluid-dock-swipe-back'", "versionName '0.17-irving-os-cover-launch'")
build_path.write_text(build, encoding='utf-8')

print('Applied Irving OS v0.17 cover app launch patch')
