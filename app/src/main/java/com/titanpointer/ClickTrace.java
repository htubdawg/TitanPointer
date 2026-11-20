package com.titanpointer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Diagnostics for clicks: where each click came from, its timing against the keyboard touch, what is
 * under the pointer, whether Android accepted the injected tap, and what the app under it reported for
 * the next two seconds (a handled click shows up as VIEW_CLICKED, a new screen as WINDOW_STATE_CHANGED).
 * Only active while diagnostics capturing is on.
 */
final class ClickTrace {
    private static final long WATCH_MS = 2000;
    private static final int MAX_EVENTS = 25;

    private final AccessibilityService svc;
    private final Handler main;

    private long padDownAt = -1, padUpAt = -1;
    private int clickNo = 0;
    private long watchStart = -1;
    private int watchClick;
    private int eventsLogged, contentChanges;
    private int savedEventTypes = -1;

    private final Runnable endWatch = this::stopWatching;

    ClickTrace(AccessibilityService svc, Handler main) {
        this.svc = svc;
        this.main = main;
    }

    void padDown() {
        padDownAt = SystemClock.uptimeMillis();
    }

    void padUp() {
        padUpAt = SystemClock.uptimeMillis();
    }

    /** Call just before a click is injected. Returns the click number for {@link #tapResult}. */
    int click(String source, boolean fromPad, float x, float y) {
        int n = ++clickNo;
        if (!Diag.isEnabled()) return n;
        long now = SystemClock.uptimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "click #%d (%s) at %.0f, %.0f", n, source, x, y));
        if (fromPad && padDownAt >= 0) {
            if (padUpAt >= padDownAt) {
                sb.append(String.format(Locale.US, "; keyboard touch %d ms, finger lifted %d ms before",
                        padUpAt - padDownAt, now - padUpAt));
            } else {
                sb.append(String.format(Locale.US, "; finger still on keyboard (%d ms)", now - padDownAt));
            }
        }
        Diag.log(sb.toString());
        logUnderPointer(n, (int) x, (int) y);
        startWatching(n);
        return n;
    }

    void tapResult(int n, String result) {
        Diag.log(String.format(Locale.US, "  #%d tap %s (+%d ms)", n, result, sinceWatch()));
    }

    void onEvent(AccessibilityEvent e) {
        if (watchStart < 0) return;
        CharSequence pkg = e.getPackageName();
        if (pkg != null && pkg.toString().equals(svc.getPackageName())) return;
        int type = e.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            contentChanges++;
            return;
        }
        if (eventsLogged >= MAX_EVENTS) return;
        eventsLogged++;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "  #%d +%d ms %s %s %s", watchClick, sinceWatch(),
                AccessibilityEvent.eventTypeToString(type).replace("TYPE_", ""), pkg, e.getClassName()));
        String what = describe(e.getContentDescription(), e.getText().isEmpty() ? null : e.getText().get(0));
        if (!what.isEmpty()) sb.append(' ').append(what);
        Diag.log(sb.toString());
    }

    void reset() {
        stopWatching();
    }

    // ---------------------------------------------------------------- internals

    private long sinceWatch() {
        return watchStart < 0 ? 0 : SystemClock.uptimeMillis() - watchStart;
    }

    private void startWatching(int n) {
        if (watchStart >= 0) summarize();
        watchStart = SystemClock.uptimeMillis();
        watchClick = n;
        eventsLogged = 0;
        contentChanges = 0;
        // Receive every event type while watching, then go back to the configured ones.
        try {
            AccessibilityServiceInfo info = svc.getServiceInfo();
            if (info != null) {
                if (savedEventTypes < 0) savedEventTypes = info.eventTypes;
                info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
                svc.setServiceInfo(info);
            }
        } catch (RuntimeException e) {
            Diag.log("  could not watch events: " + e);
        }
        main.removeCallbacks(endWatch);
        main.postDelayed(endWatch, WATCH_MS);
    }

    private void stopWatching() {
        main.removeCallbacks(endWatch);
        if (watchStart < 0) return;
        summarize();
        watchStart = -1;
        if (savedEventTypes >= 0) {
            try {
                AccessibilityServiceInfo info = svc.getServiceInfo();
                if (info != null) {
                    info.eventTypes = savedEventTypes;
                    svc.setServiceInfo(info);
                }
            } catch (RuntimeException ignored) {
            }
            savedEventTypes = -1;
        }
    }

    private void summarize() {
        Diag.log(String.format(Locale.US, "  #%d end of watch: %d events%s", watchClick, eventsLogged,
                contentChanges > 0 ? ", " + contentChanges + " content changes" : ""));
    }

    /** Every window at the point (top first) and the deepest element there in the top app window. */
    private void logUnderPointer(int n, int x, int y) {
        try {
            List<AccessibilityWindowInfo> ws = new ArrayList<>(svc.getWindows());
            Collections.sort(ws, (a, b) -> b.getLayer() - a.getLayer());
            Rect r = new Rect();
            boolean described = false;
            for (AccessibilityWindowInfo w : ws) {
                w.getBoundsInScreen(r);
                if (!r.contains(x, y)) continue;
                AccessibilityNodeInfo root = w.getRoot();
                String pkg = root == null ? "?" : String.valueOf(root.getPackageName());
                Diag.log(String.format(Locale.US, "  #%d window: %s layer %d %s \"%s\" %s%s", n, windowType(w.getType()),
                        w.getLayer(), pkg, w.getTitle(), r.toShortString(), w.isFocused() ? " focused" : ""));
                if (described || w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY || root == null) continue;
                described = true;
                AccessibilityNodeInfo node = deepest(root, x, y, 0);
                if (node != null) {
                    node.getBoundsInScreen(r);
                    Diag.log(String.format(Locale.US, "  #%d element: %s id=%s %s %s%s%s", n, node.getClassName(),
                            node.getViewIdResourceName(), describe(node.getContentDescription(), node.getText()),
                            r.toShortString(), node.isClickable() ? " clickable" : "",
                            node.isLongClickable() ? " long-clickable" : ""));
                }
            }
        } catch (RuntimeException e) {
            Diag.log("  #" + n + " could not read what's under the pointer: " + e);
        }
    }

    private static AccessibilityNodeInfo deepest(AccessibilityNodeInfo node, int x, int y, int depth) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (!r.contains(x, y) || depth > 40) return null;
        for (int i = node.getChildCount() - 1; i >= 0; i--) { // later children are drawn on top
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null || !c.isVisibleToUser()) continue;
            AccessibilityNodeInfo d = deepest(c, x, y, depth + 1);
            if (d != null) return d;
        }
        return node;
    }

    private static String describe(CharSequence desc, CharSequence text) {
        StringBuilder sb = new StringBuilder();
        if (desc != null && desc.length() > 0) sb.append("desc=\"").append(clip(desc)).append('"');
        if (text != null && text.length() > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("text=\"").append(clip(text)).append('"');
        }
        return sb.toString();
    }

    private static String clip(CharSequence s) {
        String t = s.toString().replace('\n', ' ');
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    private static String windowType(int t) {
        switch (t) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "app";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "keyboard";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "system";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "overlay";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "divider";
            default: return "type" + t;
        }
    }
}
