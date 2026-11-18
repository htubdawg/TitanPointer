package com.titanpointer;

import java.util.Locale;

/** One line of the helper's "list" output. */
public final class InputDeviceInfo {
    public static final int INPUT_PROP_POINTER = 0, INPUT_PROP_DIRECT = 1, INPUT_PROP_BUTTONPAD = 2;

    public final String path;
    public final String name;
    public final long props;
    /** 0 = no position axes, 1 = single touch, 2 = multitouch. */
    public final int touchKind;
    public final int xMin, xMax, yMin, yMax, slots;
    /** How many letter keys (Q..M) the device can emit. */
    public final int letterKeys;

    public InputDeviceInfo(String path, String name, long props, int touchKind, int xMin, int xMax, int yMin, int yMax,
                           int slots, int letterKeys) {
        this.path = path;
        this.name = name;
        this.props = props;
        this.touchKind = touchKind;
        this.xMin = xMin;
        this.xMax = xMax;
        this.yMin = yMin;
        this.yMax = yMax;
        this.slots = slots;
        this.letterKeys = letterKeys;
    }

    /** Parses "D path\tname\tprops\tmt\txmin xmax ymin ymax\tslots\tletters". */
    public static InputDeviceInfo parse(String line) {
        if (!line.startsWith("D ")) return null;
        String[] f = line.substring(2).split("\t");
        if (f.length < 7) return null;
        try {
            String[] r = f[4].trim().split(" +");
            return new InputDeviceInfo(f[0], f[1], Long.parseLong(f[2].trim()), Integer.parseInt(f[3].trim()),
                    Integer.parseInt(r[0]), Integer.parseInt(r[1]), Integer.parseInt(r[2]), Integer.parseInt(r[3]),
                    Integer.parseInt(f[5].trim()), Integer.parseInt(f[6].trim()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public boolean isDirect() {
        return (props & (1L << INPUT_PROP_DIRECT)) != 0;
    }

    /** True if grabbing this device exclusively would also block typing. */
    public boolean hasKeyboard() {
        return letterKeys >= 10;
    }

    /**
     * Heuristic score for "this is the Titan keyboard's touch surface". Higher is better, negative means
     * "not a candidate".
     */
    public int score(int screenW, int screenH) {
        if (touchKind == 0) return -1;
        int s = 10;
        if (touchKind == 2) s += 10;
        String n = name.toLowerCase(Locale.US);
        if (n.contains("kpd") || n.contains("keyboard") || n.contains("kbd") || n.contains("pad") || n.contains("key"))
            s += 30;
        if (n.contains("tpd") || n.contains("touch")) s += 5;
        if (!isDirect()) s += 10;
        // The main touchscreen usually reports ranges equal to the display resolution.
        boolean screenSized = Math.abs((xMax - xMin + 1) - screenW) <= 2 && Math.abs((yMax - yMin + 1) - screenH) <= 2;
        if (screenSized && isDirect()) s -= 25;
        if (hasKeyboard()) s -= 5;
        return s;
    }

    public String describe() {
        return String.format(Locale.US, "%s  (%s)\n%s, x %d..%d, y %d..%d%s%s", name, path,
                touchKind == 2 ? "multitouch " + slots + " slots" : touchKind == 1 ? "single touch" : "no touch axes",
                xMin, xMax, yMin, yMax, isDirect() ? ", direct" : "", hasKeyboard() ? ", has keys" : "");
    }
}
