from pathlib import Path

main_path = Path('app/src/main/java/com/marilu/miniflip/MainActivity.java')
launcher_path = Path('app/src/main/java/com/marilu/miniflip/CoverAppLauncher.java')
manifest_path = Path('app/src/main/AndroidManifest.xml')
build_path = Path('app/build.gradle')
overlay_path = Path('app/src/main/java/com/marilu/miniflip/CoverRotationOverlayActivity.java')

# Rebuild the overlay activity to mirror the working cover-screen approach more
# closely. The critical differences from v0.21 are: keep a strong overlay
# reference, allow display 0 when One UI exposes Flex Window there, keep the
# engine in its own singleInstance task, and never run the older rotation-lock
# service at the same time as the overlay.
overlay_path.write_text(r'''package com.marilu.miniflip;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

public final class CoverRotationOverlayActivity extends Activity {
    private static View overlayView;
    private static WindowManager overlayWindowManager;
    private static int overlayDisplayId = -1;

    public static boolean hasOverlayPermission(Context context) {
        return context != null && Settings.canDrawOverlays(context);
    }

    public static Intent permissionIntent(Context context) {
        return new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.getPackageName())
        );
    }

    public static void ensure(Activity source) {
        if (source == null || !hasOverlayPermission(source)) return;

        int displayId = Display.DEFAULT_DISPLAY;
        try {
            Display display = source.getDisplay();
            if (display != null) displayId = display.getDisplayId();
        } catch (Throwable ignored) {}

        if (overlayView != null && overlayWindowManager != null
                && overlayDisplayId == displayId) {
            try {
                WindowManager.LayoutParams lp =
                        (WindowManager.LayoutParams) overlayView.getLayoutParams();
                if (lp.screenOrientation != ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR) {
                    lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
                    overlayWindowManager.updateViewLayout(overlayView, lp);
                }
                return;
            } catch (Throwable ignored) {
                removeOverlayQuietly();
            }
        }

        try {
            Intent intent = new Intent(source, CoverRotationOverlayActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(displayId)
                    .toBundle();
            source.startActivity(intent, options);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Settings.canDrawOverlays(this)) {
            finishNoAnimation();
            return;
        }

        try {
            Display display = getDisplay();
            int displayId = display == null
                    ? Display.DEFAULT_DISPLAY
                    : display.getDisplayId();
            installOrRefreshOverlay(displayId);
        } catch (Throwable ignored) {}

        finishNoAnimation();
    }

    private void installOrRefreshOverlay(int displayId) {
        if (overlayView != null && overlayWindowManager != null
                && overlayDisplayId == displayId) {
            try {
                WindowManager.LayoutParams lp =
                        (WindowManager.LayoutParams) overlayView.getLayoutParams();
                lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
                overlayWindowManager.updateViewLayout(overlayView, lp);
                return;
            } catch (Throwable ignored) {
                removeOverlayQuietly();
            }
        }

        removeOverlayQuietly();

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        View view = new View(getApplicationContext());
        view.setBackgroundColor(0x00000000);

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                0,
                0,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;

        wm.addView(view, lp);
        overlayWindowManager = wm;
        overlayView = view;
        overlayDisplayId = displayId;
    }

    public static void removeOverlayQuietly() {
        if (overlayView != null && overlayWindowManager != null) {
            try {
                overlayWindowManager.removeViewImmediate(overlayView);
            } catch (Throwable ignored) {}
        }
        overlayView = null;
        overlayWindowManager = null;
        overlayDisplayId = -1;
    }

    private void finishNoAnimation() {
        finish();
        try {
            overridePendingTransition(0, 0);
        } catch (Throwable ignored) {}
    }
}
''', encoding='utf-8')

manifest = manifest_path.read_text(encoding='utf-8')

if 'android:resizeableActivity="true"' not in manifest:
    manifest = manifest.replace(
        '        android:label="Irving OS"\n',
        '        android:label="Irving OS"\n        android:resizeableActivity="true"\n',
        1
    )

if 'android.max_aspect' not in manifest:
    app_anchor = '        android:theme="@android:style/Theme.Material.NoActionBar">\n\n'
    manifest = manifest.replace(
        app_anchor,
        app_anchor
        + '        <meta-data android:name="android.max_aspect" android:value="2.4" />\n'
        + '        <meta-data android:name="android.min_aspect" android:value="1.0" />\n'
        + '        <meta-data android:name="com.samsung.android.icon_on_cover_screen" android:value="true" />\n\n',
        1
    )

old_overlay = '''        <activity\n            android:name=".CoverRotationOverlayActivity"\n            android:exported="false"\n            android:excludeFromRecents="true"\n            android:noHistory="true"\n            android:theme="@android:style/Theme.Translucent.NoTitleBar" />'''
new_overlay = '''        <activity\n            android:name=".CoverRotationOverlayActivity"\n            android:exported="false"\n            android:taskAffinity=".rotationengine"\n            android:launchMode="singleInstance"\n            android:excludeFromRecents="true"\n            android:noHistory="false"\n            android:configChanges="orientation|screenLayout|screenSize|smallestScreenSize|keyboardHidden|uiMode"\n            android:theme="@android:style/Theme.Translucent.NoTitleBar" />'''
if old_overlay in manifest:
    manifest = manifest.replace(old_overlay, new_overlay, 1)

main_activity_anchor = '''        <activity\n            android:name=".MainActivity"\n            android:exported="true"\n            android:screenOrientation="fullSensor">\n'''
if main_activity_anchor in manifest and 'com.samsung.android.activity.rule' not in manifest:
    manifest = manifest.replace(
        main_activity_anchor,
        main_activity_anchor
        + '            <meta-data\n'
        + '                android:name="com.samsung.android.activity.rule"\n'
        + '                android:value="LAUNCH_COVER" />\n',
        1
    )

manifest_path.write_text(manifest, encoding='utf-8')

launcher = launcher_path.read_text(encoding='utf-8')
launcher = launcher.replace(
    '''        CoverRotationOverlayActivity.ensure(activity);\n        RotationController.startRotationEnforcer(activity);\n        ShizukuShellBridge.bind(activity);''',
    '''        CoverRotationOverlayActivity.ensure(activity);\n        ShizukuShellBridge.bind(activity);'''
)
launcher = launcher.replace(
    '''        CoverRotationOverlayActivity.ensure(activity);\n        RotationController.startRotationEnforcer(activity);\n        int displayId = resolveDisplayId(activity);''',
    '''        CoverRotationOverlayActivity.ensure(activity);\n        int displayId = resolveDisplayId(activity);'''
)
launcher_path.write_text(launcher, encoding='utf-8')

main = main_path.read_text(encoding='utf-8')

# Stop the older global rotation service because its wm/user_rotation locks can
# fight the cover overlay and leave third-party apps stuck in one orientation.
main = main.replace(
    '''        buildUi();\n        RotationController.startRotationEnforcer(this);\n        handleIntent(getIntent());''',
    '''        buildUi();\n        try { stopService(new Intent(this, RotationController.EnforcerService.class)); }\n        catch (Throwable ignored) {}\n        maybeEnableCoverRotation();\n        handleIntent(getIntent());''',
    1
)

main = main.replace(
    '''        applyImmersiveMode();\n        CoverRotationOverlayActivity.ensure(this);\n        RotationController.startRotationEnforcer(this);''',
    '''        applyImmersiveMode();\n        try { stopService(new Intent(this, RotationController.EnforcerService.class)); }\n        catch (Throwable ignored) {}\n        maybeEnableCoverRotation();''',
    1
)

main = main.replace(
    '''                        RotationController.startRotationEnforcer(MainActivity.this);\n                        Toast.makeText(MainActivity.this,\n                                "Rotación completa de la pantalla externa activada",''',
    '''                        CoverRotationOverlayActivity.ensure(MainActivity.this);\n                        Toast.makeText(MainActivity.this,\n                                "Compatibilidad adicional de rotación activada",''',
    1
)

old_enable = '''    private void enableForcedRotation() {\n        if (!CoverRotationOverlayActivity.hasOverlayPermission(this)) {\n            try {\n                startActivity(CoverRotationOverlayActivity.permissionIntent(this));\n                Toast.makeText(this,\n                        "Activa ‘Aparecer encima’ para Irving OS y vuelve. El giro se iniciará automáticamente.",\n                        Toast.LENGTH_LONG).show();\n            } catch (Throwable e) {\n                Toast.makeText(this,\n                        "No se pudo abrir el permiso de superposición",\n                        Toast.LENGTH_LONG).show();\n            }\n            return;\n        }\n\n        CoverRotationOverlayActivity.ensure(this);\n\n        // Keep the Shizuku path only as an additional compatibility layer. The\n        // overlay is now the primary mechanism for rotating third-party apps.\n        if (RotationController.hasShizukuPermission()) {\n            RotationController.startRotationEnforcer(this);\n        }\n\n        Toast.makeText(this,\n                "Giro automático de aplicaciones activado en la pantalla externa.",\n                Toast.LENGTH_LONG).show();\n    }\n'''
new_enable = '''    private void enableForcedRotation() {\n        if (!CoverRotationOverlayActivity.hasOverlayPermission(this)) {\n            getSharedPreferences(PREFS, MODE_PRIVATE)\n                    .edit()\n                    .putBoolean("v22_overlay_permission_prompted", true)\n                    .apply();\n            try {\n                startActivity(CoverRotationOverlayActivity.permissionIntent(this));\n                Toast.makeText(this,\n                        "Activa ‘Aparecer encima de otras aplicaciones’ para Irving OS y vuelve.",\n                        Toast.LENGTH_LONG).show();\n            } catch (Throwable e) {\n                Toast.makeText(this,\n                        "No se pudo abrir el permiso de superposición",\n                        Toast.LENGTH_LONG).show();\n            }\n            return;\n        }\n\n        try { stopService(new Intent(this, RotationController.EnforcerService.class)); }\n        catch (Throwable ignored) {}\n        CoverRotationOverlayActivity.ensure(this);\n        Toast.makeText(this,\n                "Giro automático del cover activado.",\n                Toast.LENGTH_LONG).show();\n    }\n'''
if old_enable in main:
    main = main.replace(old_enable, new_enable, 1)

insert_anchor = '''    private void enableForcedRotation() {'''
helper = '''    private void maybeEnableCoverRotation() {\n        try {\n            if (CoverRotationOverlayActivity.hasOverlayPermission(this)) {\n                CoverRotationOverlayActivity.ensure(this);\n                return;\n            }\n\n            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);\n            if (!prefs.getBoolean("v22_overlay_permission_prompted", false)) {\n                prefs.edit()\n                        .putBoolean("v22_overlay_permission_prompted", true)\n                        .apply();\n                startActivity(CoverRotationOverlayActivity.permissionIntent(this));\n                Toast.makeText(this,\n                        "Activa ‘Aparecer encima de otras aplicaciones’ para permitir que también giren las apps del cover.",\n                        Toast.LENGTH_LONG).show();\n            }\n        } catch (Throwable ignored) {}\n    }\n\n'''
if helper.strip() not in main and insert_anchor in main:
    main = main.replace(insert_anchor, helper + insert_anchor, 1)

main_path.write_text(main, encoding='utf-8')

build = build_path.read_text(encoding='utf-8')
build = build.replace('versionCode 21', 'versionCode 22')
build = build.replace(
    "versionName '0.21-irving-os-overlay-autorotate'",
    "versionName '0.22-irving-os-cover-rotation-display-fix'"
)
build_path.write_text(build, encoding='utf-8')

print('Applied Irving OS v0.22 cover rotation display fix')
