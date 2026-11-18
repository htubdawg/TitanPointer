package com.titanpointer;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Typed access to the app's settings. */
public final class Prefs {
    public static final String FILE = "titanpointer";

    public static final String MODE = "mode"; // "noroot", "root" or "adb"
    public static final String MODE_NOROOT = "noroot";
    public static final String DEVICE = "device"; // /dev/input/eventN
    public static final String DEVICE_NAME = "device_name";
    public static final String RAW_XMIN = "raw_xmin", RAW_XMAX = "raw_xmax", RAW_YMIN = "raw_ymin", RAW_YMAX = "raw_ymax";
    public static final String ZONE_ON = "zone_on";
    public static final String ZONE_XMIN = "zone_xmin", ZONE_XMAX = "zone_xmax", ZONE_YMIN = "zone_ymin", ZONE_YMAX = "zone_ymax";
    public static final String GRAB = "grab";
    public static final String SWAP = "swap", INV_X = "inv_x", INV_Y = "inv_y";
    public static final String SENS = "sens", ACCEL = "accel", YSPEED = "yspeed", SCROLL = "scroll", INV_SCROLL = "inv_scroll";
    public static final String TAP = "tap", TAP_DRAG = "tap_drag", TYPING_MS = "typing_ms";
    public static final String THREE_FINGER = "three_finger"; // back, home, recents, none
    public static final String AUTOHIDE_S = "autohide_s";
    public static final String CURSOR_SIZE = "cursor_size";
    public static final String ENABLED = "enabled"; // pointer mode on/off
    public static final String TOGGLE_KEY = "toggle_key", TOGGLE_META = "toggle_meta";
    /** Alt + Space toggles pointer mode unless the user picks something else. */
    public static final int DEFAULT_TOGGLE_KEY = android.view.KeyEvent.KEYCODE_SPACE;
    public static final int DEFAULT_TOGGLE_META = android.view.KeyEvent.META_ALT_ON;
    public static final String ADB_PORT = "adb_port", ADB_TOKEN = "adb_token";

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String adbToken(Context c) {
        SharedPreferences p = get(c);
        String t = p.getString(ADB_TOKEN, null);
        if (t == null) {
            byte[] b = new byte[12];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            t = sb.toString();
            p.edit().putString(ADB_TOKEN, t).apply();
        }
        return t;
    }

    public static int adbPort(Context c) {
        return get(c).getInt(ADB_PORT, 47321);
    }

    /** Build an engine config from the saved settings. */
    public static GestureEngine.Config engineConfig(Context c, float screenWidthPx) {
        SharedPreferences p = get(c);
        GestureEngine.Config g = new GestureEngine.Config();
        boolean zone = p.getBoolean(ZONE_ON, false);
        g.xMin = p.getInt(zone ? ZONE_XMIN : RAW_XMIN, 0);
        g.xMax = p.getInt(zone ? ZONE_XMAX : RAW_XMAX, 1000);
        g.yMin = p.getInt(zone ? ZONE_YMIN : RAW_YMIN, 0);
        g.yMax = p.getInt(zone ? ZONE_YMAX : RAW_YMAX, 1000);
        if (g.xMax <= g.xMin) g.xMax = g.xMin + 1;
        if (g.yMax <= g.yMin) g.yMax = g.yMin + 1;
        g.swapXY = p.getBoolean(SWAP, false);
        g.invertX = p.getBoolean(INV_X, false);
        g.invertY = p.getBoolean(INV_Y, false);
        g.screenWidthPx = screenWidthPx;
        g.sensitivity = p.getInt(SENS, 20) / 10f;
        g.acceleration = p.getInt(ACCEL, 10) / 10f;
        g.ySpeed = p.getInt(YSPEED, 10) / 10f;
        g.scrollSpeed = p.getInt(SCROLL, 15) / 10f;
        g.invertScroll = p.getBoolean(INV_SCROLL, false);
        g.tapToClick = p.getBoolean(TAP, true);
        g.tapDrag = p.getBoolean(TAP_DRAG, true);
        g.typingSuppressMs = p.getInt(TYPING_MS, 350);
        return g;
    }
}
