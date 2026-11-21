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
    /** Flick to keep scrolling (momentum). */
    public static final String FLING = "fling";
    public static final String TAP = "tap", TAP_DRAG = "tap_drag", TYPING_MS = "typing_ms";
    /** Touches starting in this top part of the pad (percent of its height) are ignored: the key row with Back/Recents. */
    public static final String IGNORE_TOP = "ignore_top";
    /**
     * Default cut-off, measured from a front photo of the Titan (153.6 x 92.5 mm): the keyboard spans
     * about 33 mm top to bottom and the top row (Shift, Recents, home, Back, Alt) about the first 7 mm.
     * See docs/titan-hardware.md.
     */
    public static final int DEFAULT_IGNORE_TOP = 22;
    /** What letter keys do while pointer mode is on (no-root mode). */
    public static final String LETTERS = "letters";
    public static final String LETTERS_NOTHING = "nothing", LETTERS_EXIT = "exit";
    /** Speed slider: 0.1x to 2.0x. */
    public static final int SENS_MIN = 1, SENS_MAX = 20, SENS_DEFAULT = 7;
    public static final String AUTOHIDE_S = "autohide_s";
    public static final String CURSOR_SIZE = "cursor_size";
    /** Pointer look: a {@link PointerShape} style, its fill colour, and the custom image's click point. */
    public static final String CURSOR_STYLE = "cursor_style", CURSOR_COLOR = "cursor_color",
            CURSOR_HOTSPOT = "cursor_hotspot";
    /** Bumped whenever a new custom pointer image is saved, so the service reloads it. */
    public static final String CURSOR_IMAGE_VER = "cursor_image_ver";
    public static final String ENABLED = "enabled"; // pointer mode on/off
    public static final String TOGGLE_KEY = "toggle_key", TOGGLE_META = "toggle_meta";
    /** Alt + Space toggles pointer mode unless the user picks something else. */
    public static final int DEFAULT_TOGGLE_KEY = android.view.KeyEvent.KEYCODE_SPACE;
    public static final int DEFAULT_TOGGLE_META = android.view.KeyEvent.META_ALT_ON;
    /** Keys that click / long-press at the pointer while pointer mode is on (no-root mode). 0 = none. */
    public static final String CLICK_KEY = "click_key", CLICK_META = "click_meta";
    public static final String LONG_KEY = "long_key", LONG_META = "long_meta";
    public static final int DEFAULT_CLICK_KEY = android.view.KeyEvent.KEYCODE_ENTER, DEFAULT_CLICK_META = 0;
    public static final int DEFAULT_LONG_KEY = android.view.KeyEvent.KEYCODE_ENTER;
    public static final int DEFAULT_LONG_META = android.view.KeyEvent.META_ALT_ON;
    /** Diagnostics test: taps on the keyboard click one second late (off by default). */
    public static final String TAP_DELAY_TEST = "tap_delay_test";
    /** Diagnostics test: the click key's click is sent from a timer instead of the key callback (off by default). */
    public static final String KEY_TIMER_TEST = "key_timer_test";
    /** While the Titan blocks taps, keyboard-tap clicks click the standard View under the pointer via accessibility (on by default). */
    public static final String ELEMENT_CLICK = "element_click";
    /** Scroll assistant off whenever pointer mode is off, on while it's on (needs WRITE_SECURE_SETTINGS; off by default). */
    public static final String ASSIST_FOLLOWS_POINTER = "assist_follows_pointer";
    /** Set by 1.23's click fix while the scroll assistant was off; still set at start-up = repaired (back on). */
    public static final String ASSIST_OFF_FOR_CLICK = "assist_off_for_click";
    /** Capture the in-memory diagnostics log (off by default). */
    public static final String DIAG = "diag";
    /** The settings screen's last open tab (UI only). */
    public static final String UI_TAB = "ui_tab";
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
        g.sensitivity = Math.max(SENS_MIN, Math.min(SENS_MAX, p.getInt(SENS, SENS_DEFAULT))) / 10f;
        g.ignoreTopFrac = p.getInt(IGNORE_TOP, DEFAULT_IGNORE_TOP) / 100f;
        g.acceleration = p.getInt(ACCEL, 10) / 10f;
        g.ySpeed = p.getInt(YSPEED, 10) / 10f;
        g.scrollSpeed = p.getInt(SCROLL, 15) / 10f;
        g.invertScroll = p.getBoolean(INV_SCROLL, false);
        g.fling = p.getBoolean(FLING, true);
        g.tapToClick = p.getBoolean(TAP, true);
        g.tapDrag = p.getBoolean(TAP_DRAG, true);
        g.typingSuppressMs = p.getInt(TYPING_MS, 350);
        return g;
    }
}
