from pathlib import Path

main_path = Path('app/src/main/java/com/marilu/miniflip/MainActivity.java')
dialer_path = Path('app/src/main/java/com/marilu/miniflip/IrvingDialerActivity.java')
build_path = Path('app/build.gradle')

# ---------------------------------------------------------------------------
# Refine the cover dialer: more Samsung-like proportions, compact number field,
# smaller keypad, safe bottom area, and horizontal swipe-to-return gesture.
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
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class IrvingDialerActivity extends Activity {
    private static final int REQUEST_CALL_PHONE = 9231;
    private TextView numberView;
    private String pendingNumber = "";
    private float gestureDownX;
    private float gestureDownY;

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

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event != null) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                gestureDownX = event.getX();
                gestureDownY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float dx = event.getX() - gestureDownX;
                float dy = event.getY() - gestureDownY;
                if (Math.abs(dx) > dp(55)
                        && Math.abs(dx) > Math.abs(dy) * 1.35f) {
                    finish();
                    try { overridePendingTransition(0, 0); } catch (Throwable ignored) {}
                    return true;
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        finish();
        try { overridePendingTransition(0, 0); } catch (Throwable ignored) {}
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(12), dp(5), dp(12), dp(29));
        root.setBackgroundColor(Color.rgb(10, 10, 12));

        TextView title = new TextView(this);
        title.setText("Teléfono");
        title.setTextColor(Color.rgb(220, 220, 224));
        title.setTextSize(10);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(18)));

        LinearLayout displayRow = new LinearLayout(this);
        displayRow.setOrientation(LinearLayout.HORIZONTAL);
        displayRow.setGravity(Gravity.CENTER_VERTICAL);
        displayRow.setPadding(dp(8), 0, dp(8), 0);

        numberView = new TextView(this);
        numberView.setText("");
        numberView.setHint("Número de teléfono");
        numberView.setHintTextColor(Color.rgb(110, 110, 116));
        numberView.setTextColor(Color.WHITE);
        numberView.setTextSize(20);
        numberView.setGravity(Gravity.CENTER);
        numberView.setSingleLine(true);
        numberView.setPadding(dp(7), 0, dp(7), 0);
        numberView.setBackground(rounded(Color.rgb(24, 24, 28), 15));
        LinearLayout.LayoutParams numberLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        numberLp.setMargins(0, 0, dp(6), 0);
        displayRow.addView(numberView, numberLp);

        TextView erase = smallControl("⌫");
        erase.setOnClickListener(v -> eraseOne());
        erase.setOnLongClickListener(v -> {
            setNumber("");
            return true;
        });
        displayRow.addView(erase, new LinearLayout.LayoutParams(dp(37), dp(37)));
        root.addView(displayRow, new LinearLayout.LayoutParams(-1, dp(43)));

        LinearLayout keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        keypad.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams keypadLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        keypadLp.setMargins(dp(23), dp(2), dp(23), dp(2));
        root.addView(keypad, keypadLp);

        String[][] keys = {
                {"1", ""}, {"2", "ABC"}, {"3", "DEF"},
                {"4", "GHI"}, {"5", "JKL"}, {"6", "MNO"},
                {"7", "PQRS"}, {"8", "TUV"}, {"9", "WXYZ"},
                {"*", ""}, {"0", "+"}, {"#", ""}
        };

        for (int rowIndex = 0; rowIndex < 4; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            keypad.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));

            for (int col = 0; col < 3; col++) {
                final String digit = keys[rowIndex * 3 + col][0];
                final String letters = keys[rowIndex * 3 + col][1];
                View button = digitButton(digit, letters);
                LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(0, -1, 1f);
                keyLp.setMargins(dp(6), dp(2), dp(6), dp(2));
                row.addView(button, keyLp);
                button.setOnClickListener(v -> append(digit));
                if ("0".equals(digit)) {
                    button.setOnLongClickListener(v -> {
                        append("+");
                        return true;
                    });
                }
            }
        }

        LinearLayout callRow = new LinearLayout(this);
        callRow.setGravity(Gravity.CENTER);
        TextView call = actionButton("☎");
        call.setTextSize(23);
        call.setBackground(rounded(Color.rgb(18, 180, 92), 24));
        call.setOnClickListener(v -> requestOrPlaceCall());
        callRow.addView(call, new LinearLayout.LayoutParams(dp(56), dp(40)));
        root.addView(callRow, new LinearLayout.LayoutParams(-1, dp(43)));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);

        TextView keyboard = navButton("Teclado");
        keyboard.setTextColor(Color.WHITE);
        nav.addView(keyboard, new LinearLayout.LayoutParams(0, dp(31), 1f));

        TextView recent = navButton("Recientes");
        recent.setOnClickListener(v -> openRecentCalls());
        nav.addView(recent, new LinearLayout.LayoutParams(0, dp(31), 1f));

        TextView contacts = navButton("Contactos");
        contacts.setOnClickListener(v -> openContacts());
        nav.addView(contacts, new LinearLayout.LayoutParams(0, dp(31), 1f));

        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(33)));
        setContentView(root);
    }

    private View digitButton(String digit, String letters) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(rounded(Color.rgb(27, 27, 31), 24));

        TextView number = new TextView(this);
        number.setText(digit);
        number.setTextColor(Color.WHITE);
        number.setTextSize(22);
        number.setGravity(Gravity.CENTER);
        number.setTypeface(Typeface.DEFAULT);
        box.addView(number, new LinearLayout.LayoutParams(-1, 0, letters.isEmpty() ? 1f : 0.72f));

        if (!letters.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(letters);
            sub.setTextColor(Color.rgb(150, 150, 156));
            sub.setTextSize(6.5f);
            sub.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            box.addView(sub, new LinearLayout.LayoutParams(-1, 0, 0.28f));
        }
        return box;
    }

    private TextView smallControl(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(17);
        v.setGravity(Gravity.CENTER);
        v.setBackground(rounded(Color.rgb(30, 30, 34), 13));
        return v;
    }

    private TextView actionButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private TextView navButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.rgb(185, 185, 190));
        v.setTextSize(8.5f);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT_BOLD);
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
# Shizuku permission flow for power controls. The permission option disappears
# after grant and power actions automatically open the grant dialog if needed.
# ---------------------------------------------------------------------------
main = main_path.read_text(encoding='utf-8')

main = main.replace(
    '    private static final int REQUEST_SHIZUKU = 7101;\n',
    '    private static final int REQUEST_SHIZUKU = 7101;\n'
    '    private static final int REQUEST_SHIZUKU_POWER = 7102;\n',
    1
)

listener_old = '''                public void onRequestPermissionResult(int requestCode, int grantResult) {\n                    if (requestCode != REQUEST_SHIZUKU) return;\n                    if (grantResult == PackageManager.PERMISSION_GRANTED) {\n                        RotationController.startRotationEnforcer(MainActivity.this);\n                        Toast.makeText(MainActivity.this,\n                                "Rotación completa de la pantalla externa activada",\n                                Toast.LENGTH_SHORT).show();\n                    } else {\n                        Toast.makeText(MainActivity.this,\n                                "Se necesita permiso de Shizuku para girar también las otras aplicaciones",\n                                Toast.LENGTH_LONG).show();\n                    }\n                }\n'''
listener_new = '''                public void onRequestPermissionResult(int requestCode, int grantResult) {\n                    if (requestCode == REQUEST_SHIZUKU_POWER) {\n                        if (grantResult == PackageManager.PERMISSION_GRANTED) {\n                            ShizukuShellBridge.bind(MainActivity.this);\n                            Toast.makeText(MainActivity.this,\n                                    "Permiso concedido. Apagar y Reiniciar ya están disponibles.",\n                                    Toast.LENGTH_LONG).show();\n                        } else {\n                            Toast.makeText(MainActivity.this,\n                                    "Permiso no concedido",\n                                    Toast.LENGTH_SHORT).show();\n                        }\n                        return;\n                    }\n\n                    if (requestCode != REQUEST_SHIZUKU) return;\n                    if (grantResult == PackageManager.PERMISSION_GRANTED) {\n                        RotationController.startRotationEnforcer(MainActivity.this);\n                        Toast.makeText(MainActivity.this,\n                                "Rotación completa de la pantalla externa activada",\n                                Toast.LENGTH_SHORT).show();\n                    } else {\n                        Toast.makeText(MainActivity.this,\n                                "Se necesita permiso de Shizuku para girar también las otras aplicaciones",\n                                Toast.LENGTH_LONG).show();\n                    }\n                }\n'''
if listener_old not in main:
    raise SystemExit('Could not patch Shizuku permission listener')
main = main.replace(listener_old, listener_new, 1)

settings_start = main.index('    private void showIrvingSettings() {')
settings_end = main.index('    private void showDockSizeDialog() {', settings_start)
old_settings_block = main[settings_start:settings_end]
new_settings_block = r'''    private void showIrvingSettings() {
        List<String> items = new ArrayList<>();
        items.add("Editar pantalla principal");
        items.add("Editar Dock");
        items.add("Tamaño de iconos del Dock");
        items.add("Ampliación del Dock al tocar");
        items.add("Cambiar imagen de fondo");
        items.add("Restaurar fondo predeterminado");
        items.add("Activar rotación completa de pantalla externa");
        if (!hasShizukuPowerPermission()) {
            items.add("Conceder permiso para apagar y reiniciar");
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
                    else if ("Conceder permiso para apagar y reiniciar".equals(selected)) requestPowerPermission();
                    else if ("Apagar teléfono".equals(selected)) confirmPowerAction(false);
                    else if ("Reiniciar teléfono".equals(selected)) confirmPowerAction(true);
                    else if ("Ajustes del teléfono".equals(selected)) openSettings();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private boolean hasShizukuPowerPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isShizukuRunningForPower() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void requestPowerPermission() {
        if (hasShizukuPowerPermission()) {
            ShizukuShellBridge.bind(this);
            Toast.makeText(this,
                    "El permiso ya está concedido",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isShizukuRunningForPower()) {
            try {
                Intent shizuku = getPackageManager()
                        .getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (shizuku != null) {
                    shizuku.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(shizuku);
                }
            } catch (Throwable ignored) {}
            Toast.makeText(this,
                    "Inicia Shizuku. Después vuelve a Irving OS y pulsa nuevamente el permiso.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Shizuku.requestPermission(REQUEST_SHIZUKU_POWER);
        } catch (Throwable e) {
            Toast.makeText(this,
                    "No se pudo abrir el permiso de Shizuku",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmPowerAction(boolean reboot) {
        if (!hasShizukuPowerPermission() || !isShizukuRunningForPower()) {
            requestPowerPermission();
            return;
        }

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
        if (!hasShizukuPowerPermission() || !isShizukuRunningForPower()) {
            requestPowerPermission();
            return;
        }

        ShizukuShellBridge.bind(this);
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
main = main[:settings_start] + new_settings_block + main[settings_end:]
main_path.write_text(main, encoding='utf-8')

build = build_path.read_text(encoding='utf-8')
build = build.replace('versionCode 23', 'versionCode 24')
build = build.replace("versionName '0.23-irving-os-dialer-power'", "versionName '0.24-irving-os-dialer-permission'")
build_path.write_text(build, encoding='utf-8')

print('Applied Irving OS v0.24 dialer layout, swipe back, and Shizuku power permission flow')
