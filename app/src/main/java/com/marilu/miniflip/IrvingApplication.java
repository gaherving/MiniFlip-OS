package com.marilu.miniflip;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class IrvingApplication extends Application {
    private static final String PREFS = "miniflip_prefs";
    private static final String HOME_PREFIX = "home_";
    private static final String DOCK_PREFIX = "dock_";
    private static final int HOME_SLOTS = 8;
    private static final int DOCK_SLOTS = 5;
    private static final String DEFAULTS_V11 = "defaults_v11_initialized";

    @Override
    public void onCreate() {
        super.onCreate();
        initializeDefaultLayout();
    }

    private void initializeDefaultLayout() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(DEFAULTS_V11, false)) return;

        Map<String, String> launchers = loadLauncherActivities();
        SharedPreferences.Editor editor = prefs.edit();

        // Fresh installs receive the same four Home icons visible in the test video.
        String[] homePackages = {
                "com.whatsapp",
                "com.android.chrome",
                "com.google.android.youtube",
                "com.sec.android.gallery3d"
        };

        // Dock shown in the video: Phone, WhatsApp, TikTok, YouTube and Facebook.
        String[][] dockCandidates = {
                {"com.samsung.android.dialer", "com.android.dialer", "com.google.android.dialer"},
                {"com.whatsapp"},
                {"com.zhiliaoapp.musically", "com.ss.android.ugc.trill"},
                {"com.google.android.youtube"},
                {"com.facebook.katana"}
        };

        boolean hasAnyHome = hasAnySlot(prefs, HOME_PREFIX, HOME_SLOTS);
        boolean hasAnyDock = hasAnySlot(prefs, DOCK_PREFIX, DOCK_SLOTS);

        if (!hasAnyHome) {
            int slot = 0;
            for (String pkg : homePackages) {
                String activity = launchers.get(pkg);
                if (activity == null || slot >= HOME_SLOTS) continue;
                editor.putString(HOME_PREFIX + slot, pkg + "\n" + activity);
                slot++;
            }
            while (slot < HOME_SLOTS) {
                editor.putString(HOME_PREFIX + slot, "");
                slot++;
            }
        } else {
            appendIfMissing(prefs, editor, launchers, HOME_PREFIX, HOME_SLOTS,
                    new String[]{"com.sec.android.gallery3d"});
        }

        if (!hasAnyDock) {
            int slot = 0;
            for (String[] candidates : dockCandidates) {
                String pkg = firstInstalled(launchers, candidates);
                if (pkg == null || slot >= DOCK_SLOTS) continue;
                editor.putString(DOCK_PREFIX + slot, pkg + "\n" + launchers.get(pkg));
                slot++;
            }
            while (slot < DOCK_SLOTS) {
                editor.putString(DOCK_PREFIX + slot, "");
                slot++;
            }
        } else {
            // Existing users keep their layout. Only fill free spaces with missing video defaults.
            for (String[] candidates : dockCandidates) {
                appendIfMissing(prefs, editor, launchers, DOCK_PREFIX, DOCK_SLOTS, candidates);
            }
        }

        editor.putBoolean(DEFAULTS_V11, true).apply();
    }

    private boolean hasAnySlot(SharedPreferences prefs, String prefix, int slots) {
        for (int i = 0; i < slots; i++) {
            String value = prefs.getString(prefix + i, "");
            if (value != null && !value.isEmpty()) return true;
        }
        return false;
    }

    private void appendIfMissing(SharedPreferences prefs,
                                 SharedPreferences.Editor editor,
                                 Map<String, String> launchers,
                                 String prefix,
                                 int slots,
                                 String[] candidates) {
        String pkg = firstInstalled(launchers, candidates);
        if (pkg == null || containsPackage(prefs, prefix, slots, pkg)) return;

        int free = firstFreeSlot(prefs, prefix, slots);
        if (free < 0) return;
        editor.putString(prefix + free, pkg + "\n" + launchers.get(pkg));
    }

    private boolean containsPackage(SharedPreferences prefs, String prefix, int slots, String pkg) {
        for (int i = 0; i < slots; i++) {
            String value = prefs.getString(prefix + i, "");
            if (value != null && (value.equals(pkg) || value.startsWith(pkg + "\n"))) {
                return true;
            }
        }
        return false;
    }

    private int firstFreeSlot(SharedPreferences prefs, String prefix, int slots) {
        for (int i = 0; i < slots; i++) {
            String value = prefs.getString(prefix + i, "");
            if (value == null || value.isEmpty()) return i;
        }
        return -1;
    }

    private String firstInstalled(Map<String, String> launchers, String[] candidates) {
        for (String pkg : candidates) {
            if (launchers.containsKey(pkg)) return pkg;
        }
        return null;
    }

    private Map<String, String> loadLauncherActivities() {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            PackageManager pm = getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN, null);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(intent, 0);
            for (ResolveInfo info : infos) {
                if (info.activityInfo == null || info.activityInfo.packageName == null
                        || info.activityInfo.name == null) continue;
                if (!result.containsKey(info.activityInfo.packageName)) {
                    result.put(info.activityInfo.packageName, info.activityInfo.name);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}
