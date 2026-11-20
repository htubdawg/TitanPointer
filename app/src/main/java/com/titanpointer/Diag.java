package com.titanpointer;

import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Small in-memory log of what the pointer service sees, shown (and copyable) in the app. */
public final class Diag {
    private static final int MAX = 200;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final Map<String, Long> lastByKey = new HashMap<>();
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile boolean enabled = true;

    private Diag() {}

    /** Turn capturing on or off. Turning it off also drops what was logged. */
    public static synchronized void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        if (!on) clear();
        else log("diagnostics capturing turned on");
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static synchronized void log(String s) {
        if (!enabled) return;
        lines.addLast(fmt.format(new Date()) + "  " + s);
        while (lines.size() > MAX) lines.removeFirst();
    }

    /** Log at most once per {@code intervalMs} for the same key (for high-rate events). */
    public static synchronized void logEvery(String key, long intervalMs, String s) {
        if (!enabled) return;
        long now = SystemClock.uptimeMillis();
        Long last = lastByKey.get(key);
        if (last != null && now - last < intervalMs) return;
        lastByKey.put(key, now);
        log(s);
    }

    public static synchronized String dump() {
        if (!enabled) return "(capturing is off)";
        if (lines.isEmpty()) return "(nothing logged yet: is the accessibility service on?)";
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() {
        lines.clear();
        lastByKey.clear();
    }
}
