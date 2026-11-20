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
 * Diagnostics for clicks (only while diagnostics capturing is on).
 *
 * A "watch" starts when a finger touches the keyboard or a click is sent, and lasts until 2 s after the
 * last click (3 s if no click). During a watch every accessibility event from other apps is logged with
 * its time since the watch started (event types are widened to all and batching is turned off), along
 * with the keyboard touch itself (down/up position, moves), our focus window's touch mode, which element
 * has input focus, what is under the pointer at the click, and what happened to the injected tap.
 */
final class ClickTrace {
    private static final long WATCH_AFTER_CLICK_MS = 2000, WATCH_TOUCH_MS = 3000;
    private static final int MAX_EVENTS = 60, MAX_CONTENT_EVENTS = 10;

    private final AccessibilityService svc;
    private final Handler main;

    private long padDownAt = -1, padUpAt = -1;
    private int downX, downY, lastX, lastY, moves;
    private int clickNo = 0, watchNo = 0;
    private long watchStart = -1;
    private int eventsLogged, contentLogged, contentSkipped, eventsSkipped;
    private int savedEventTypes = -1, savedTimeout = -1;

    private final Runnable endWatch = this::stopWatching;

    ClickTrace(AccessibilityService svc, Handler main) {
        this.svc = svc;
        this.main = main;
    }

    /** The window list ("what's under the pointer") needs this flag; set it at runtime as well as in XML. */
    void enableWindowAccess() {
        try {
            AccessibilityServiceInfo info = svc.getServiceInfo();
            if (info == null) {
                Diag.log("window access: no service info");
                return;
            }
            if ((info.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) == 0) {
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                svc.setServiceInfo(info);
                Diag.log("window access turned on at runtime");
            }
        } catch (RuntimeException e) {
            Diag.log("window access failed: " + e);
        }
    }

    // ---------------------------------------------------------------- keyboard touches

    void padDown(int x, int y, boolean focusWindowTouchMode) {
        padDownAt = SystemClock.uptimeMillis();
        downX = lastX = x;
        downY = lastY = y;
        moves = 0;
        if (!Diag.isEnabled()) return;
        startWatching(WATCH_TOUCH_MS);
        log(String.format(Locale.US, "keyboard touch DOWN at %d, %d; focus window touch mode: %s; input focus: %s",
                x, y, focusWindowTouchMode, inputFocus()));
    }

    void padMove(int x, int y) {
        lastX = x;
        lastY = y;
        moves++;
    }

    void padUp(boolean focusWindowTouchMode) {
        padUpAt = SystemClock.uptimeMillis();
        if (!Diag.isEnabled() || padDownAt < 0) return;
        log(String.format(Locale.US, "keyboard touch UP after %d ms, %d moves, ended at %d, %d (moved %d, %d); "
                        + "focus window touch mode: %s", padUpAt - padDownAt, moves, lastX, lastY, lastX - downX,
                lastY - downY, focusWindowTouchMode));
    }

    // ---------------------------------------------------------------- clicks

    /** Call just before a click is injected. Returns the click number for {@link #tapResult}. */
    int click(String source, boolean fromPad, float x, float y, boolean focusWindowTouchMode) {
        int n = ++clickNo;
        if (!Diag.isEnabled()) return n;
        startWatching(WATCH_AFTER_CLICK_MS);
        long now = SystemClock.uptimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "CLICK #%d (%s) at %.0f, %.0f", n, source, x, y));
        if (padDownAt >= 0) {
            if (padUpAt >= padDownAt) {
                sb.append(String.format(Locale.US, "; last keyboard touch ended %d ms ago", now - padUpAt));
            } else {
                sb.append(String.format(Locale.US, "; finger on keyboard for %d ms", now - padDownAt));
            }
        }
        sb.append("; focus window touch mode: ").append(focusWindowTouchMode);
        log(sb.toString());
        log("  input focus: " + inputFocus());
        logUnderPointer(n, (int) x, (int) y);
        return n;
    }

    void tapResult(int n, String result) {
        log(String.format(Locale.US, "  tap #%d %s", n, result));
    }

    // ---------------------------------------------------------------- events from other apps

    void onEvent(AccessibilityEvent e) {
        if (watchStart < 0) return;
        CharSequence pkg = e.getPackageName();
        if (pkg != null && pkg.toString().equals(svc.getPackageName())) return;
        int type = e.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (contentLogged >= MAX_CONTENT_EVENTS) {
                contentSkipped++;
                return;
            }
            contentLogged++;
        }
        if (eventsLogged >= MAX_EVENTS) {
            eventsSkipped++;
            return;
        }
        eventsLogged++;
        StringBuilder sb = new StringBuilder();
        sb.append("  event ").append(AccessibilityEvent.eventTypeToString(type).replace("TYPE_", ""))
                .append(' ').append(pkg).append(' ').append(e.getClassName());
        String what = describe(e.getContentDescription(), e.getText().isEmpty() ? null : e.getText().get(0));
        if (!what.isEmpty()) sb.append(' ').append(what);
        try {
            AccessibilityNodeInfo s = e.getSource();
            if (s != null && s.getViewIdResourceName() != null) sb.append(" id=").append(s.getViewIdResourceName());
        } catch (RuntimeException ignored) {
        }
        log(sb.toString());
    }

    void reset() {
        stopWatching();
    }

    // ---------------------------------------------------------------- internals

    private void log(String s) {
        Diag.log(watchStart < 0 ? s : String.format(Locale.US, "[w%d +%d] %s", watchNo,
                SystemClock.uptimeMillis() - watchStart, s));
    }

    private void startWatching(long forMs) {
        if (watchStart < 0) {
            watchStart = SystemClock.uptimeMillis();
            watchNo++;
            eventsLogged = contentLogged = contentSkipped = eventsSkipped = 0;
            // Every event type, with no batching delay, while watching; restored afterwards.
            try {
                AccessibilityServiceInfo info = svc.getServiceInfo();
                if (info != null) {
                    savedEventTypes = info.eventTypes;
                    savedTimeout = (int) info.notificationTimeout;
                    info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
                    info.notificationTimeout = 0;
                    svc.setServiceInfo(info);
                }
            } catch (RuntimeException e) {
                log("could not watch events: " + e);
            }
            log("watch started");
        }
        main.removeCallbacks(endWatch);
        main.postDelayed(endWatch, forMs);
    }

    private void stopWatching() {
        main.removeCallbacks(endWatch);
        if (watchStart < 0) return;
        log(String.format(Locale.US, "watch ended: %d events logged%s%s", eventsLogged,
                contentSkipped > 0 ? ", " + contentSkipped + " more content changes" : "",
                eventsSkipped > 0 ? ", " + eventsSkipped + " more events not logged" : ""));
        watchStart = -1;
        if (savedEventTypes >= 0) {
            try {
                AccessibilityServiceInfo info = svc.getServiceInfo();
                if (info != null) {
                    info.eventTypes = savedEventTypes;
                    info.notificationTimeout = savedTimeout;
                    svc.setServiceInfo(info);
                }
            } catch (RuntimeException ignored) {
            }
            savedEventTypes = -1;
        }
    }

    private String inputFocus() {
        try {
            AccessibilityNodeInfo f = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            return f == null ? "none" : node(f);
        } catch (RuntimeException e) {
            return "? (" + e + ")";
        }
    }

    /** Every window at the point (top first) and the deepest element there in the top app window. */
    private void logUnderPointer(int n, int x, int y) {
        try {
            List<AccessibilityWindowInfo> ws = new ArrayList<>(svc.getWindows());
            Collections.sort(ws, (a, b) -> b.getLayer() - a.getLayer());
            Rect r = new Rect();
            boolean described = false;
            int hits = 0;
            for (AccessibilityWindowInfo w : ws) {
                w.getBoundsInScreen(r);
                if (!r.contains(x, y)) continue;
                hits++;
                AccessibilityNodeInfo root = w.getRoot();
                String pkg = root == null ? "?" : String.valueOf(root.getPackageName());
                log(String.format(Locale.US, "  window: %s layer %d %s \"%s\" %s%s%s", windowType(w.getType()),
                        w.getLayer(), pkg, w.getTitle(), r.toShortString(), w.isFocused() ? " focused" : "",
                        w.isActive() ? " active" : ""));
                if (described || w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                if (root == null) {
                    log("  (no content readable in that window)");
                    continue;
                }
                described = true;
                AccessibilityNodeInfo d = deepest(root, x, y, 0);
                if (d != null) log("  element: " + node(d));
            }
            if (hits == 0) log("  no window at the pointer (Android listed " + ws.size() + " windows)");
        } catch (RuntimeException e) {
            log("  could not read what's under the pointer: " + e);
        }
    }

    private static String node(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        String what = describe(n.getContentDescription(), n.getText());
        return n.getPackageName() + " " + n.getClassName() + " id=" + n.getViewIdResourceName()
                + (what.isEmpty() ? "" : " " + what) + " " + r.toShortString()
                + (n.isClickable() ? " clickable" : "") + (n.isLongClickable() ? " long-clickable" : "")
                + (n.isFocusable() ? " focusable" : "") + (n.isFocused() ? " FOCUSED" : "")
                + (n.isEditable() ? " editable" : "");
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
