package com.titanpointer;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Clicks the standard Android View under the pointer through accessibility instead of a fake touch.
 *
 * Why: the Titan's input reader (libinputreader.so, InputReader::processEventsForDeviceLocked) sets
 * debug.sf.forcescroll_view=yes on every event from the keyboard touch sensor and =no on events from any
 * other hardware device, and the Titan's View.onTouchEvent refuses touches while it's yes. Injected taps
 * never pass the reader, so a click right after a keyboard tap is always refused by standard Views. Web
 * content and Compose UIs don't use View.onTouchEvent (they're virtual nodes here) and still get the tap.
 */
final class ElementClick {
    private ElementClick() {}

    /** The element to click at (x, y), or null to send a normal tap. Never a virtual (web/Compose) node. */
    static AccessibilityNodeInfo find(AccessibilityService svc, int x, int y) {
        return find(svc, x, y, false);
    }

    /** Like {@link #find(AccessibilityService, int, int)}, but for a long press: the first long-clickable element. */
    static AccessibilityNodeInfo find(AccessibilityService svc, int x, int y, boolean longClick) {
        List<AccessibilityWindowInfo> ws;
        try {
            ws = new ArrayList<>(svc.getWindows());
        } catch (RuntimeException e) {
            return null;
        }
        Collections.sort(ws, (a, b) -> b.getLayer() - a.getLayer());
        Rect r = new Rect();
        for (AccessibilityWindowInfo w : ws) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            w.getBoundsInScreen(r);
            if (!r.contains(x, y)) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) return null; // the top window at the point can't be read: tap
            List<AccessibilityNodeInfo> path = new ArrayList<>();
            deepestPath(root, x, y, 0, path);
            // From the deepest element up: the first one that takes clicks.
            for (int i = path.size() - 1; i >= 0; i--) {
                AccessibilityNodeInfo n = path.get(i);
                if (isVirtual(n)) return null; // web page or Compose: taps work there
                if (!n.isEnabled()) return null;
                if (longClick ? n.isLongClickable() : (n.isClickable() || n.isEditable())) {
                    for (int j = i - 1; j >= 0; j--) if (isVirtual(path.get(j))) return null;
                    return n;
                }
            }
            return null;
        }
        return null;
    }

    /** Click (and for text fields, focus) the element. Returns true if the app accepted it. */
    static boolean click(AccessibilityNodeInfo n) {
        boolean ok = false;
        if (n.isEditable() || (n.isFocusable() && !n.isClickable())) {
            ok = n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        }
        if (n.isClickable()) ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK) || ok;
        return ok;
    }

    /** Long-press the element. Returns true if the app accepted it. */
    static boolean longClick(AccessibilityNodeInfo n) {
        return n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
    }

    static String describe(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
        String s = t == null ? "" : t.toString();
        if (s.length() > 40) s = s.substring(0, 40) + "…";
        return n.getClassName() + (s.isEmpty() ? "" : " \"" + s + "\"") + " " + r.toShortString();
    }

    private static boolean deepestPath(AccessibilityNodeInfo node, int x, int y, int depth, List<AccessibilityNodeInfo> path) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (!r.contains(x, y) || depth > 40) return false;
        path.add(node);
        for (int i = node.getChildCount() - 1; i >= 0; i--) { // later children are drawn on top
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null || !c.isVisibleToUser()) continue;
            if (deepestPath(c, x, y, depth + 1, path)) return true;
        }
        return true;
    }

    // A node's source id holds the View's id in the low 32 bits and, for virtual nodes (web pages, Compose,
    // custom providers), the virtual id in the high 32 bits. Real Views have UNDEFINED_ITEM_ID there.
    private static Field sourceId;
    private static boolean sourceIdTried;

    static boolean isVirtual(AccessibilityNodeInfo n) {
        if (!sourceIdTried) {
            sourceIdTried = true;
            StateProbe.exempt(); // hidden API access (Android 10)
            try {
                sourceId = AccessibilityNodeInfo.class.getDeclaredField("mSourceNodeId");
                sourceId.setAccessible(true);
            } catch (Throwable t) {
                sourceId = null;
                Diag.log("element click: can't read node ids (" + t + "), sending taps instead");
            }
        }
        if (sourceId == null) return true; // can't tell: treat as virtual so a normal tap is sent
        try {
            int v = (int) (sourceId.getLong(n) >>> 32);
            return v != Integer.MAX_VALUE && v != -1;
        } catch (Throwable t) {
            return true;
        }
    }
}
