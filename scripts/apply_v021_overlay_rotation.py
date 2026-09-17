from pathlib import Path

main_path = Path('app/src/main/java/com/marilu/miniflip/MainActivity.java')
launcher_path = Path('app/src/main/java/com/marilu/miniflip/CoverAppLauncher.java')
manifest_path = Path('app/src/main/AndroidManifest.xml')
build_path = Path('app/build.gradle')
overlay_path = Path('app/src/main/java/com/marilu/miniflip/CoverRotationOverlayActivity.java')

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

import java.lang.ref.WeakReference;

/**
 * Forces the external display to follow the physical device orientation by
 * keeping an invisible TYPE_APPLICATION_OVERLAY window on the cover display.
 *
 * This intentionally uses the same Android window-orientation mechanism proven
 * by open-source cover-screen rotation utilities rather than only relying on
 * global wm/user_rotation shell commands, which One UI may ignore for Flex
 * Window application tasks.
 */
public final class CoverRotationOverlayActivity extends Activity {
    private static WeakReference<View> overlayRef;
    private static WindowManager overlayWindowManager;
    private static int overlayDisplayId = -1;

    public static boolean hasOverlayPermission(Context context) {
        return Settings.canDrawOverlays(context);
    }

    public static Intent permissionIntent(Context context) {
        return new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.getPackageName())
        );
    }

    public static void ensure(Activity source) {
        if (source == null || !hasOverlayPermission(source)) return;

        int displayId = 1;
        try {
            Display display = source.getDisplay();
            if (display != null) displayId = display.getDisplayId();
        } catch (Throwable ignored) {}

        if (overlayRef != null && overlayRef.get() != null && overlayDisplayId == displayId) {
            return;
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

        try {
            Display display = getDisplay();
            if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
                finishNoAnimation();
                return;
            }

            if (!Settings.canDrawOverlays(this)) {
                finishNoAnimation();
                return;
            }

            installOrRefreshOverlay(display.getDisplayId());
        } catch (Throwable ignored) {
        }

        finishNoAnimation();
    }

    private void installOrRefreshOverlay(int displayId) {
        View existing = overlayRef == null ? null : overlayRef.get();
        if (existing != null && overlayWindowManager != null && overlayDisplayId == displayId) {
            try {
                WindowManager.LayoutParams lp =
                        (WindowManager.LayoutParams) existing.getLayoutParams();
                lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
                overlayWindowManager.updateViewLayout(existing, lp);
                return;
            } catch (Throwable ignored) {}
        }

        removeOverlayQuietly();

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        View overlay = new View(getApplicationContext());
        overlay.setBackgroundColor(0x00000000);

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;

        wm.addView(overlay, lp);
        overlayWindowManager = wm;
        overlayRef = new WeakReference<>(overlay);
        overlayDisplayId = displayId;
    }

    private static void removeOverlayQuietly() {
        View old = overlayRef == null ? null : overlayRef.get();
        if (old != null && overlayWindowManager != null) {
            try {
                overlayWindowManager.removeViewImmediate(old);
            } catch (Throwable ignored) {}
        }
        overlayRef = null;
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
if 'android.permission.SYSTEM_ALERT_WINDOW' not in manifest:
    manifest = manifest.replace(
        '    <uses-permission android:name="android.permission.WRITE_SETTINGS" />\n',
        '    <uses-permission android:name="android.permission.WRITE_SETTINGS" />\n'
        '    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />\n',
        1
    )

activity_block = '''        <activity\n            android:name=".CoverRotationOverlayActivity"\n            android:exported="false"\n            android:excludeFromRecents="true"\n            android:noHistory="true"\n            android:theme="@android:style/Theme.Translucent.NoTitleBar" />\n\n'''
if '.CoverRotationOverlayActivity' not in manifest:
    manifest = manifest.replace(
        '        <activity\n            android:name=".MainActivity"',
        activity_block + '        <activity\n            android:name=".MainActivity"',
        1
    )
manifest_path.write_text(manifest, encoding='utf-8')

launcher = launcher_path.read_text(encoding='utf-8')
anchor = '''        RotationController.startRotationEnforcer(activity);\n        ShizukuShellBridge.bind(activity);'''
if anchor not in launcher:
    raise SystemExit('Could not find CoverAppLauncher launch anchor')
launcher = launcher.replace(
    anchor,
    '''        CoverRotationOverlayActivity.ensure(activity);\n        RotationController.startRotationEnforcer(activity);\n        ShizukuShellBridge.bind(activity);''',
    1
)

settings_anchor = '''        RotationController.startRotationEnforcer(activity);\n        int displayId = resolveDisplayId(activity);'''
if settings_anchor in launcher:
    launcher = launcher.replace(
        settings_anchor,
        '''        CoverRotationOverlayActivity.ensure(activity);\n        RotationController.startRotationEnforcer(activity);\n        int displayId = resolveDisplayId(activity);''',
        1
    )
launcher_path.write_text(launcher, encoding='utf-8')

main = main_path.read_text(encoding='utf-8')
resume_anchor = '''        applyImmersiveMode();\n        RotationController.startRotationEnforcer(this);'''
if resume_anchor not in main:
    raise SystemExit('Could not find MainActivity onResume anchor')
main = main.replace(
    resume_anchor,
    '''        applyImmersiveMode();\n        CoverRotationOverlayActivity.ensure(this);\n        RotationController.startRotationEnforcer(this);''',
    1
)

old_method = '''    private void enableForcedRotation() {\n        if (!RotationController.isShizukuRunning()) {\n            Toast.makeText(this,\n                    "Para girar también todas las aplicaciones, inicia Shizuku y vuelve a pulsar esta opción.",\n                    Toast.LENGTH_LONG).show();\n            return;\n        }\n\n        if (!RotationController.hasShizukuPermission()) {\n            try {\n                Shizuku.requestPermission(REQUEST_SHIZUKU);\n            } catch (Throwable e) {\n                Toast.makeText(this,\n                        "No se pudo solicitar el permiso de Shizuku",\n                        Toast.LENGTH_LONG).show();\n            }\n            return;\n        }\n\n        RotationController.startRotationEnforcer(this);\n        Toast.makeText(this,\n                "Rotación completa activada. Ahora Irving OS también ignora la orientación fija de otras apps en la pantalla externa.",\n                Toast.LENGTH_LONG).show();\n    }\n'''
new_method = '''    private void enableForcedRotation() {\n        if (!CoverRotationOverlayActivity.hasOverlayPermission(this)) {\n            try {\n                startActivity(CoverRotationOverlayActivity.permissionIntent(this));\n                Toast.makeText(this,\n                        "Activa ‘Aparecer encima’ para Irving OS y vuelve. El giro se iniciará automáticamente.",\n                        Toast.LENGTH_LONG).show();\n            } catch (Throwable e) {\n                Toast.makeText(this,\n                        "No se pudo abrir el permiso de superposición",\n                        Toast.LENGTH_LONG).show();\n            }\n            return;\n        }\n\n        CoverRotationOverlayActivity.ensure(this);\n\n        // Keep the Shizuku path only as an additional compatibility layer. The\n        // overlay is now the primary mechanism for rotating third-party apps.\n        if (RotationController.hasShizukuPermission()) {\n            RotationController.startRotationEnforcer(this);\n        }\n\n        Toast.makeText(this,\n                "Giro automático de aplicaciones activado en la pantalla externa.",\n                Toast.LENGTH_LONG).show();\n    }\n'''
if old_method not in main:
    raise SystemExit('Could not find enableForcedRotation method')
main = main.replace(old_method, new_method, 1)
main_path.write_text(main, encoding='utf-8')

build = build_path.read_text(encoding='utf-8')
build = build.replace('versionCode 20', 'versionCode 21')
build = build.replace(
    "versionName '0.20-irving-os-userservice-rotation'",
    "versionName '0.21-irving-os-overlay-autorotate'"
)
build_path.write_text(build, encoding='utf-8')

print('Applied Irving OS v0.21 cover-overlay autorotation patch')
