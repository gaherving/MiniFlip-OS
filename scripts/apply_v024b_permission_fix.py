from pathlib import Path

main_path = Path('app/src/main/java/com/marilu/miniflip/MainActivity.java')
dialer_path = Path('app/src/main/java/com/marilu/miniflip/IrvingDialerActivity.java')
build_path = Path('app/build.gradle')

# Compact the v0.23 dialer while keeping its proven call flow.
dialer = dialer_path.read_text(encoding='utf-8')
if 'import android.view.MotionEvent;' not in dialer:
    dialer = dialer.replace('import android.view.Gravity;\n', 'import android.view.Gravity;\nimport android.view.MotionEvent;\n', 1)

dialer = dialer.replace('    private String pendingNumber = "";\n',
                        '    private String pendingNumber = "";\n'
                        '    private float gestureDownX;\n'
                        '    private float gestureDownY;\n', 1)

anchor = '    private void buildUi() {\n'
if 'public boolean dispatchTouchEvent(MotionEvent event)' not in dialer:
    gesture = r'''    @Override
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

'''
    if anchor not in dialer:
        raise SystemExit('Could not find dialer buildUi anchor')
    dialer = dialer.replace(anchor, gesture + anchor, 1)

repls = {
    'root.setPadding(dp(13), dp(8), dp(13), dp(9));': 'root.setPadding(dp(12), dp(5), dp(12), dp(28));',
    'title.setText("Teléfono · Irving OS");': 'title.setText("Teléfono");',
    'title.setTextSize(11);': 'title.setTextSize(10);',
    'root.addView(title, new LinearLayout.LayoutParams(-1, dp(23)));': 'root.addView(title, new LinearLayout.LayoutParams(-1, dp(18)));',
    'numberView.setTextSize(25);': 'numberView.setTextSize(20);',
    'new LinearLayout.LayoutParams(0, dp(48), 1f)': 'new LinearLayout.LayoutParams(0, dp(40), 1f)',
    'displayRow.addView(back, new LinearLayout.LayoutParams(dp(38), dp(42)));': 'displayRow.addView(back, new LinearLayout.LayoutParams(dp(32), dp(36)));',
    'displayRow.addView(erase, new LinearLayout.LayoutParams(dp(42), dp(42)));': 'displayRow.addView(erase, new LinearLayout.LayoutParams(dp(36), dp(36)));',
    'root.addView(displayRow, new LinearLayout.LayoutParams(-1, dp(54)));': 'root.addView(displayRow, new LinearLayout.LayoutParams(-1, dp(45)));',
    'keypadLp.setMargins(dp(18), dp(4), dp(18), dp(3));': 'keypadLp.setMargins(dp(25), dp(2), dp(25), dp(2));',
    'keyLp.setMargins(dp(5), dp(3), dp(5), dp(3));': 'keyLp.setMargins(dp(6), dp(2), dp(6), dp(2));',
    'v.setTextSize(25);': 'v.setTextSize(21);',
    'v.setBackground(rounded(Color.rgb(31, 31, 35), 23));': 'v.setBackgroundColor(Color.TRANSPARENT);',
    'actionRow.addView(recent, new LinearLayout.LayoutParams(0, dp(46), 1f));': 'actionRow.addView(recent, new LinearLayout.LayoutParams(0, dp(38), 1f));',
    'call.setTextSize(27);': 'call.setTextSize(22);',
    'new LinearLayout.LayoutParams(dp(82), dp(46))': 'new LinearLayout.LayoutParams(dp(56), dp(38))',
    'actionRow.addView(contacts, new LinearLayout.LayoutParams(0, dp(46), 1f));': 'actionRow.addView(contacts, new LinearLayout.LayoutParams(0, dp(38), 1f));',
    'root.addView(actionRow, new LinearLayout.LayoutParams(-1, dp(50)));': 'root.addView(actionRow, new LinearLayout.LayoutParams(-1, dp(42)));',
}
for old, new in repls.items():
    dialer = dialer.replace(old, new, 1)

dialer_path.write_text(dialer, encoding='utf-8')

# One-time Shizuku permission entry. It disappears after a successful grant.
main = main_path.read_text(encoding='utf-8')
settings_start = main.index('    private void showIrvingSettings() {')
settings_end = main.index('    private void showDockSizeDialog() {', settings_start)
new_block = r'''    private void showIrvingSettings() {
        List<String> items = new ArrayList<>();
        items.add("Editar pantalla principal");
        items.add("Editar Dock");
        items.add("Tamaño de iconos del Dock");
        items.add("Ampliación del Dock al tocar");
        items.add("Cambiar imagen de fondo");
        items.add("Restaurar fondo predeterminado");
        items.add("Activar rotación completa de pantalla externa");
        if (!hasRememberedPowerPermission()) {
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

    private boolean hasRememberedPowerPermission() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean remembered = prefs.getBoolean("shizuku_power_permission_granted", false);
        try {
            if (Shizuku.pingBinder()) {
                boolean granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                prefs.edit().putBoolean("shizuku_power_permission_granted", granted).apply();
                return granted;
            }
        } catch (Throwable ignored) {}
        return remembered;
    }

    private boolean isShizukuRunningForPower() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void requestPowerPermission() {
        if (hasRememberedPowerPermission()) {
            ShizukuShellBridge.bind(this);
            Toast.makeText(this, "El permiso ya está concedido", Toast.LENGTH_SHORT).show();
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
                    "Inicia Shizuku. Después vuelve a Irving OS y pulsa de nuevo el permiso.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Shizuku.requestPermission(REQUEST_SHIZUKU);
        } catch (Throwable e) {
            Toast.makeText(this,
                    "No se pudo abrir el permiso de Shizuku",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmPowerAction(boolean reboot) {
        if (!hasRememberedPowerPermission() || !isShizukuRunningForPower()) {
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
        if (!hasRememberedPowerPermission() || !isShizukuRunningForPower()) {
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
                        reboot ? "No se pudo reiniciar el teléfono" : "No se pudo apagar el teléfono",
                        Toast.LENGTH_LONG
                ).show());
            }
        }, reboot ? "irving-reboot" : "irving-poweroff").start();
    }

'''
main = main[:settings_start] + new_block + main[settings_end:]
main_path.write_text(main, encoding='utf-8')

build = build_path.read_text(encoding='utf-8')
build = build.replace('versionCode 23', 'versionCode 24')
build = build.replace("versionName '0.23-irving-os-dialer-power'", "versionName '0.24-irving-os-dialer-permission'")
build_path.write_text(build, encoding='utf-8')

print('Applied Irving OS v0.24b compact dialer and one-time Shizuku power permission')
