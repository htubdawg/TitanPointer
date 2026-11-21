package com.titanpointer;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * The Titan's "scroll assistant" (keyboard scrolling) switch. Its settings screen stores it in
 * Settings.Global {@code touchpad_switch_value} (1 on, 0 off) and the phone mirrors that into the
 * property {@code persist.sys.touchpad_switch} that the input system reads (both measured on the user's
 * Titan: writing the setting with ADB turned keyboard scrolling off). Writing Settings.Global needs
 * WRITE_SECURE_SETTINGS, which the user grants once with
 * {@code adb shell pm grant com.titanpointer android.permission.WRITE_SECURE_SETTINGS}.
 */
final class ScrollAssist {
    static final String SETTING = "touchpad_switch_value";
    static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    static final String GRANT_COMMAND = "adb shell pm grant com.titanpointer " + PERMISSION;

    private ScrollAssist() {}

    static boolean canWrite(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** True unless the setting reads 0. Missing (not a Titan) counts as on. */
    static boolean isOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), SETTING, 1) != 0;
    }

    /** Returns true if the setting was written. */
    static boolean set(Context c, boolean on) {
        if (!canWrite(c)) return false;
        try {
            return Settings.Global.putInt(c.getContentResolver(), SETTING, on ? 1 : 0);
        } catch (RuntimeException e) {
            Diag.log("writing " + SETTING + " failed: " + e);
            return false;
        }
    }

    /**
     * Intent for the Titan's own scroll assistant screen (TouchpadActivity in the Agui settings app, found on
     * the user's phone by the 1.21 scan; package name unknown, so it is looked up), else the Settings app.
     */
    static Intent settingsIntent(Context c) {
        PackageManager pm = c.getPackageManager();
        try {
            for (PackageInfo p : pm.getInstalledPackages(0)) {
                String n = p.packageName.toLowerCase(java.util.Locale.US);
                if (!n.contains("agui") && !n.contains("led")) continue;
                PackageInfo full = pm.getPackageInfo(p.packageName, PackageManager.GET_ACTIVITIES);
                if (full.activities == null) continue;
                for (ActivityInfo a : full.activities) {
                    if (a.name.endsWith("TouchpadActivity") && a.exported && a.enabled) {
                        return new Intent().setClassName(a.packageName, a.name);
                    }
                }
            }
        } catch (RuntimeException | PackageManager.NameNotFoundException e) {
            Diag.log("looking for the scroll assistant screen failed: " + e);
        }
        return new Intent(Settings.ACTION_SETTINGS);
    }

    /** The property the input system reads, for the log. */
    static String prop() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class).invoke(null, "persist.sys.touchpad_switch", "");
        } catch (Throwable t) {
            return "?";
        }
    }
}
