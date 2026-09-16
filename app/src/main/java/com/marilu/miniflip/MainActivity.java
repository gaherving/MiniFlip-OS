package com.marilu.miniflip;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.setBackgroundColor(Color.rgb(10, 10, 12));

        TextView title = new TextView(this);
        title.setText("MiniFlip OS");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(38)));

        TextView subtitle = new TextView(this);
        subtitle.setText("Prueba de pantalla externa");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setTextSize(11);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(26)));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setRowCount(2);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(true);

        addButton(grid, "WhatsApp", "com.whatsapp");
        addButton(grid, "Chrome", "com.android.chrome");
        addButton(grid, "YouTube", "com.google.android.youtube");
        addSettingsButton(grid, "Ajustes");

        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView note = new TextView(this);
        note.setText("Si esta pantalla aparece con el Flip cerrado, la base funciona.");
        note.setTextColor(Color.GRAY);
        note.setTextSize(10);
        note.setGravity(Gravity.CENTER);
        root.addView(note, new LinearLayout.LayoutParams(-1, dp(34)));

        setContentView(root);
    }

    private TextView makeButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(15);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Color.rgb(35, 35, 42));
        v.setPadding(dp(6), dp(6), dp(6), dp(6));
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = 0;
        p.height = 0;
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        p.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        p.setMargins(dp(5), dp(5), dp(5), dp(5));
        v.setLayoutParams(p);
        return v;
    }

    private void addButton(GridLayout grid, String label, String packageName) {
        TextView v = makeButton(label);
        v.setOnClickListener(view -> openPackage(packageName));
        grid.addView(v);
    }

    private void addSettingsButton(GridLayout grid, String label) {
        TextView v = makeButton(label);
        v.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "No se pudieron abrir Ajustes", Toast.LENGTH_SHORT).show();
            }
        });
        grid.addView(v);
    }

    private void openPackage(String packageName) {
        Intent i = getPackageManager().getLaunchIntentForPackage(packageName);
        if (i == null) {
            Toast.makeText(this, "La app no está instalada", Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir la app", Toast.LENGTH_SHORT).show();
        }
    }
}
