from pathlib import Path

main_path = Path('app/src/main/java/com/marilu/miniflip/MainActivity.java')
launcher_path = Path('app/src/main/java/com/marilu/miniflip/CoverAppLauncher.java')
manifest_path = Path('app/src/main/AndroidManifest.xml')
build_path = Path('app/build.gradle')
dialer_path = Path('app/src/main/java/com/marilu/miniflip/IrvingDialerActivity.java')

# ---------------------------------------------------------------------------
# Compact Irving OS dialer designed for the Galaxy Z Flip cover display.
# ---------------------------------------------------------------------------
dialer_path.write_text(r'''package com.marilu.miniflip;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class IrvingDialerActivity extends Activity {
    private static final int REQUEST_CALL_PHONE = 9231;
    private TextView numberView;
    private String pendingNumber = "";

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(13), dp(8), dp(13), dp(9));
        root.setBackgroundColor(Color.rgb(13, 13, 15));

        TextView title = new TextView(this);
        title.setText("Teléfono · Irving OS");
        title.setTextColor(Color.LTGRAY);
        title.setTextSize(11);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(23)));

        LinearLayout displayRow = new LinearLayout(this);
        displayRow.setOrientation(LinearLayout.HORIZONTAL);
        displayRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = smallControl("‹");
        back.setOnClickListener(v -> finish());
        displayRow.addView(back, new LinearLayout.LayoutParams(dp(38), dp(42)));

        numberView = new TextView(this);
        numberView.setText("");
        numberView.setHint("Número");
        numberView.setHintTextColor(Color.rgb(120, 120, 126));
        numberView.setTextColor(Color.WHITE);
        numberView.setTextSize(25);
        numberView.setGravity(Gravity.CENTER);
        numberView.setSingleLine(true);
        numberView.setPadding(dp(6), 0, dp(6), 0);
        numberView.setBackground(rounded(Color.rgb(28, 28, 32), 17));
        LinearLayout.LayoutParams numberLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        numberLp.setMargins(dp(6), 0, dp(6), 0);
        displayRow.addView(numberView, numberLp);

        TextView erase = smallControl("⌫");
        erase.setOnClickListener(v -> eraseOne());
        erase.setOnLongClickListener(v -> {
            setNumber("");
            return true;
        });
        displayRow.addView(erase, new LinearLayout.LayoutParams(dp(42), dp(42)));
        root.addView(displayRow, new LinearLayout.LayoutParams(-1, dp(54)));

        LinearLayout keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        keypad.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams keypadLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        keypadLp.setMargins(dp(18), dp(4), dp(18), dp(3));
        root.addView(keypad, keypadLp);

        String[][] keys = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"*", "0", "#"}
        };

        for (String[] rowKeys : keys) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, 0, 1f);
            keypad.addView(row, rowLp);

            for (String key : rowKeys) {
                TextView button = digitButton(key);
                LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(0, -1, 1f);
                keyLp.setMargins(dp(5), dp(3), dp(5), dp(3));
                row.addView(button, keyLp);
                button.setOnClickListener(v -> append(((TextView) v).getText().toString()));
                if ("0".equals(key)) {
                    button.setOnLongClickListener(v -> {
                        append("+");
                        return true;
                    });
                }
            }
        }

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);

        TextView recent = actionButton("Recientes");
        recent.setOnClickListener(v -> openRecentCalls());
        actionRow.addView(recent, new LinearLayout.LayoutParams(0, dp(46), 1f));

        TextView call = actionButton("☎");
        call.setTextSize(27);
        call.setBackground(rounded(Color.rgb(15, 176, 91), 23));
        LinearLayout.LayoutParams callLp = new LinearLayout.LayoutParams(dp(82), dp(46));
        callLp.setMargins(dp(8), 0, dp(8), 0);
        actionRow.addView(call, callLp);
        call.setOnClickListener(v -> requestOrPlaceCall());

        TextView contacts = actionButton("Contactos");
        contacts.setOnClickListener(v -> openContacts());
        actionRow.addView(contacts, new LinearLayout.LayoutParams(0, dp(46), 1f));

        root.addView(actionRow, new LinearLayout.LayoutParams(-1, dp(50)));
        setContentView(root);
    }

    private TextView digitButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(25);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT);
        v.setBackground(rounded(Color.rgb(31, 31, 35), 23));
        return v;
    }

    private TextView smallControl(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(19);
        v.setGravity(Gravity.CENTER);
        v.setBackground(rounded(Color.rgb(31, 31, 35), 14));
        return v;
    }

    private TextView actionButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(10);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setBackground(rounded(Color.rgb(35, 35, 40), 18));
        return v;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void append(String value) {
        setNumber(numberView.getText().toString() + value);
    }

    private void eraseOne() {
        String current = numberView.getText().toString();
        if (!current.isEmpty()) setNumber(current.substring(0, current.length() - 1));
    }

    private void setNumber(String value) {
        numberView.setText(value == null ? "" : value);
    }

    private void requestOrPlaceCall() {
        String number = numberView.getText().toString().trim();
        if (number.isEmpty()) {
            Toast.makeText(this, "Escribe un número", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingNumber = number;
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQUEST_CALL_PHONE);
            return;
        }
        placeCall(number);
    }

    private void placeCall(String number) {
        try {
            Intent call = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
            call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(currentDisplayId())
                    .toBundle();
            startActivity(call, options);
        } catch (Throwable e) {
            Toast.makeText(this, "No se pudo iniciar la llamada", Toast.LENGTH_SHORT).show();
        }
    }

    private void openRecentCalls() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI);
            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(currentDisplayId())
                    .toBundle();
            startActivity(intent, options);
        } catch (Throwable e) {
            Toast.makeText(this, "No se pudieron abrir Recientes", Toast.LENGTH_SHORT).show();
        }
    }

    private void openContacts() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI);
            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(currentDisplayId())
                    .toBundle();
            startActivity(intent, options);
        } catch (Throwable e) {
            Toast.makeText(this, "No se pudieron abrir Contactos", Toast.LENGTH_SHORT).show();
        }
    }

    private int currentDisplayId() {
        try {
            android.view.Display display = getDisplay();
            if (display != null) return display.getDisplayId();
        } catch (Throwable ignored) {}
        return 1;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CALL_PHONE
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && !pendingNumber.isEmpty()) {
            placeCall(pendingNumber);
        }
    }
}
''', encoding='utf-8')

# ---------------------------------------------------------------------------
# Manifest: CALL_PHONE permission + compact dialer activity.
# ---------------------------------------------------------------------------
manifest = manifest_path.read_text(encoding='utf-8')
if 'android.permission.CALL_PHONE' not in manifest:
    manifest = manifest.replace(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n',
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <uses-permission android:name="android.permission.CALL_PHONE" />\n',
        1
    )

if '.IrvingDialerActivity' not in manifest:
    manifest = manifest.replace(
        '    </application>',
        '''        <activity\n            android:name=".IrvingDialerActivity"\n            android:exported="false"\n            android:excludeFromRecents="false"\n            android:screenOrientation="fullSensor"\n            android:configChanges="orientation|screenLayout|screenSize|smallestScreenSize|keyboardHidden|uiMode"\n            android:theme="@android:style/Theme.Material.NoActionBar" />\n\n    </application>''',
        1
    )
manifest_path.write_text(manifest, encoding='utf-8')

# ---------------------------------------------------------------------------
# Phone shortcuts now open the Irving OS dialer instead of Samsung's oversized
# cover layout. Other app launch behavior and rotation are left untouched.
# ---------------------------------------------------------------------------
launcher = launcher_path.read_text(encoding='utf-8')
needle = '        if (activity == null || packageName == null || packageName.isEmpty()) return;\n'
insert = needle + '''\n        if (isPhonePackage(packageName)) {\n            launchIrvingDialer(activity);\n            return;\n        }\n'''
if needle not in launcher:
    raise SystemExit('Could not find CoverAppLauncher launch guard')
launcher = launcher.replace(needle, insert, 1)

helper_anchor = '    public static void launchSystemSettings(Activity activity) {'
helper = r'''    private static void launchIrvingDialer(Activity activity) {
        int displayId = resolveDisplayId(activity);
        try {
            Intent intent = new Intent(activity, IrvingDialerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(displayId)
                    .toBundle();
            activity.startActivity(intent, options);
        } catch (Throwable e) {
            Toast.makeText(activity,
                    "No se pudo abrir Teléfono Irving OS",
                    Toast.LENGTH_SHORT).show();
        }
    }

'''
if helper_anchor not in launcher:
    raise SystemExit('Could not find CoverAppLauncher helper anchor')
launcher = launcher.replace(helper_anchor, helper + helper_anchor, 1)
launcher_path.write_text(launcher, encoding='utf-8')

# ---------------------------------------------------------------------------
# Settings menu: add safe, confirmed shutdown and restart actions through the
# existing Shizuku shell bridge. Rotation code is intentionally untouched.
# ---------------------------------------------------------------------------
main = main_path.read_text(encoding='utf-8')
old_items = '''                "Restaurar fondo predeterminado",\n                "Activar rotación completa de pantalla externa",\n                "Ajustes del teléfono"\n'''
new_items = '''                "Restaurar fondo predeterminado",\n                "Activar rotación completa de pantalla externa",\n                "Apagar teléfono",\n                "Reiniciar teléfono",\n                "Ajustes del teléfono"\n'''
if old_items not in main:
    raise SystemExit('Could not find Irving settings items')
main = main.replace(old_items, new_items, 1)

old_handlers = '''                    else if (which == 5) clearWallpaper();\n                    else if (which == 6) enableForcedRotation();\n                    else if (which == 7) openSettings();\n'''
new_handlers = '''                    else if (which == 5) clearWallpaper();\n                    else if (which == 6) enableForcedRotation();\n                    else if (which == 7) confirmPowerAction(false);\n                    else if (which == 8) confirmPowerAction(true);\n                    else if (which == 9) openSettings();\n'''
if old_handlers not in main:
    raise SystemExit('Could not find Irving settings handlers')
main = main.replace(old_handlers, new_handlers, 1)

method_anchor = '    private void showDockSizeDialog() {'
power_methods = r'''    private void confirmPowerAction(boolean reboot) {
        String title = reboot ? "Reiniciar teléfono" : "Apagar teléfono";
        String message = reboot
                ? "¿Quieres reiniciar el teléfono ahora?"
                : "¿Quieres apagar el teléfono ahora?";

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton(reboot ? "Reiniciar" : "Apagar", (dialog, which) ->
                        executePowerAction(reboot))
                .show();
    }

    private void executePowerAction(boolean reboot) {
        if (!ShizukuShellBridge.isAvailable()) {
            Toast.makeText(this,
                    "Inicia Shizuku y concede permiso a Irving OS para usar esta opción.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        final String command = reboot ? "reboot" : "reboot -p";
        new Thread(() -> {
            boolean ok = ShizukuShellBridge.execBlocking(command);
            if (!ok) {
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        reboot
                                ? "No se pudo reiniciar el teléfono"
                                : "No se pudo apagar el teléfono",
                        Toast.LENGTH_LONG
                ).show());
            }
        }, reboot ? "irving-reboot" : "irving-poweroff").start();
    }

'''
if method_anchor not in main:
    raise SystemExit('Could not find settings method anchor')
main = main.replace(method_anchor, power_methods + method_anchor, 1)
main_path.write_text(main, encoding='utf-8')

# ---------------------------------------------------------------------------
# Version bump. v0.23 deliberately does not touch wallpaper or rotation logic.
# ---------------------------------------------------------------------------
build = build_path.read_text(encoding='utf-8')
build = build.replace('versionCode 22', 'versionCode 23')
build = build.replace(
    "versionName '0.22-irving-os-cover-rotation-display-fix'",
    "versionName '0.23-irving-os-dialer-power'"
)
build_path.write_text(build, encoding='utf-8')

print('Applied Irving OS v0.23 compact dialer and power menu patch')
