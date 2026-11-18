package com.titanpointer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Laptop-touchpad style gesture recognition on top of {@link TouchTracker} frames.
 *
 * <ul>
 *   <li>1 finger move: move the pointer (with acceleration)</li>
 *   <li>1 finger tap: click</li>
 *   <li>tap, then touch again and move: drag (press-and-hold drag)</li>
 *   <li>tap twice: double click</li>
 *   <li>2 finger move: scroll</li>
 *   <li>2 finger tap: long press (secondary click)</li>
 *   <li>3 finger tap: configurable (default: Back)</li>
 * </ul>
 *
 * Pure Java with no Android dependencies so it can be unit tested on the JVM.
 */
public final class GestureEngine implements TouchTracker.Listener {

    public interface Actions {
        void onPointerMove(float dx, float dy);
        void onClick();
        void onSecondaryClick();
        void onTertiaryTap();
        void onScrollBegin();
        void onScroll(float dx, float dy);
        void onScrollEnd();
        void onDragBegin();
        void onDragEnd();
    }

    public interface Clock {
        long now();
    }

    public interface Scheduler {
        void post(Runnable r, long delayMs);
        void cancel(Runnable r);
    }

    /** Tunables. All distances on the pad are in fractions of the pad width. */
    public static final class Config {
        public int xMin = 0, xMax = 1000, yMin = 0, yMax = 1000;
        public boolean swapXY = false, invertX = false, invertY = false;
        public float screenWidthPx = 1440f;
        public float sensitivity = 2.0f;
        public float acceleration = 1.0f;
        public float ySpeed = 1.0f;
        public float scrollSpeed = 1.5f;
        public boolean invertScroll = false;
        public boolean tapToClick = true;
        public boolean tapDrag = true;
        public long tapTimeoutMs = 220;
        public long doubleTapWindowMs = 260;
        public float tapSlop = 0.035f;
        public long typingSuppressMs = 350;
    }

    private static final int MODE_NONE = 0, MODE_POINTER = 1, MODE_SCROLL = 2, MODE_DRAG = 3;

    private final Actions actions;
    private final Clock clock;
    private final Scheduler scheduler;
    private Config cfg = new Config();

    private boolean touching = false;
    private long touchStart;
    private long lastFrameTime;
    private int maxFingers;
    private boolean moved;
    private float travel;
    private boolean keyDuringTouch;
    private int mode = MODE_NONE;
    private boolean scrollActive = false;
    /** When on, one-finger slides scroll instead of moving the pointer (for single-touch pads). */
    private boolean scrollLock = false;
    private boolean dragCandidate = false;
    private boolean clickOwed = false;
    private long lastTapUp = -1;
    private long lastKeyTime = Long.MIN_VALUE / 2;

    // Previous frame, normalized coordinates by tracking id.
    private final Map<Integer, float[]> prev = new HashMap<>();
    private final Map<Integer, float[]> cur = new HashMap<>();

    private final Runnable pendingClick = new Runnable() {
        @Override
        public void run() {
            clickPending = false;
            actions.onClick();
        }
    };
    private boolean clickPending = false;

    public GestureEngine(Actions actions, Clock clock, Scheduler scheduler) {
        this.actions = actions;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    public void setConfig(Config c) {
        this.cfg = c;
    }

    public Config getConfig() {
        return cfg;
    }

    /** Call for every physical key press so typing on the keyboard is not mistaken for touchpad use. */
    public void onKeyActivity() {
        lastKeyTime = clock.now();
        if (touching) keyDuringTouch = true;
        if (clickPending) {
            scheduler.cancel(pendingClick);
            clickPending = false;
        }
        dragCandidate = false;
        clickOwed = false;
    }

    public void setScrollLock(boolean on) {
        if (scrollLock == on) return;
        scrollLock = on;
        if (!on && scrollActive && mode != MODE_SCROLL) {
            scrollActive = false;
            actions.onScrollEnd();
        }
    }

    public boolean isScrollLock() {
        return scrollLock;
    }

    /** Abandon any gesture in progress (e.g. when pointer mode is switched off). */
    public void reset() {
        if (scrollActive) actions.onScrollEnd();
        if (mode == MODE_DRAG) actions.onDragEnd();
        if (clickPending) scheduler.cancel(pendingClick);
        clickPending = false;
        touching = false;
        scrollActive = false;
        mode = MODE_NONE;
        dragCandidate = false;
        clickOwed = false;
        prev.clear();
    }

    @Override
    public void onFrame(List<TouchTracker.Contact> contacts) {
        long now = clock.now();
        cur.clear();
        for (TouchTracker.Contact c : contacts) {
            float[] p = normalize(c.x, c.y);
            if (p != null) cur.put(c.id, p);
        }
        int n = cur.size();

        if (n > 0 && !touching) begin(now, n);

        if (touching) {
            if (n > maxFingers) maxFingers = n;
            float dt = Math.max(5f, now - lastFrameTime) / 1000f;
            if (n == 1 && (maxFingers == 1 || mode == MODE_DRAG)) {
                oneFinger(now, dt);
            } else if (n >= 2 && mode != MODE_DRAG) {
                multiFinger();
            }
            if (n == 0) end(now);
        }

        prev.clear();
        for (Map.Entry<Integer, float[]> e : cur.entrySet()) prev.put(e.getKey(), e.getValue());
        lastFrameTime = now;
    }

    private void begin(long now, int n) {
        touching = true;
        touchStart = now;
        lastFrameTime = now;
        maxFingers = n;
        moved = false;
        travel = 0;
        keyDuringTouch = now - lastKeyTime < cfg.typingSuppressMs;
        scrollActive = false;
        mode = n >= 2 ? MODE_SCROLL : MODE_POINTER;
        prev.clear();
        if (clickPending && cfg.tapDrag && now - lastTapUp <= cfg.doubleTapWindowMs) {
            scheduler.cancel(pendingClick);
            clickPending = false;
            dragCandidate = true;
            clickOwed = true;
        } else {
            dragCandidate = false;
            clickOwed = false;
        }
    }

    private void oneFinger(long now, float dt) {
        Map.Entry<Integer, float[]> e = cur.entrySet().iterator().next();
        float[] p = e.getValue();
        float[] q = prev.get(e.getKey());
        if (q == null) return; // new finger: no delta yet
        float du = p[0] - q[0], dv = p[1] - q[1];
        float dist = (float) Math.hypot(du, dv);
        travel += dist;
        if (travel > cfg.tapSlop) moved = true;

        if (scrollLock && mode != MODE_DRAG) {
            if (!moved || keyDuringTouch) return;
            if (!scrollActive) {
                scrollActive = true;
                actions.onScrollBegin();
            }
            float k = cfg.screenWidthPx * cfg.scrollSpeed * (cfg.invertScroll ? -1f : 1f);
            actions.onScroll(du * k, dv * k);
            return;
        }
        if (dragCandidate && moved && mode != MODE_DRAG && !keyDuringTouch) {
            mode = MODE_DRAG;
            clickOwed = false;
            actions.onDragBegin();
        }
        if (keyDuringTouch || now - lastKeyTime < cfg.typingSuppressMs) return;
        if (mode == MODE_SCROLL) return;

        float speed = dist / dt; // pad widths per second
        float accel = 1f + cfg.acceleration * clamp((speed - 0.15f) / 0.8f, 0f, 3f);
        float k = cfg.screenWidthPx * cfg.sensitivity * accel;
        actions.onPointerMove(du * k, dv * k);
    }

    private void multiFinger() {
        mode = MODE_SCROLL;
        float cu = 0, cv = 0, pu = 0, pv = 0;
        int matched = 0;
        for (Map.Entry<Integer, float[]> e : cur.entrySet()) {
            float[] q = prev.get(e.getKey());
            if (q == null) continue;
            float[] p = e.getValue();
            cu += p[0];
            cv += p[1];
            pu += q[0];
            pv += q[1];
            matched++;
        }
        if (matched == 0) return;
        float du = (cu - pu) / matched, dv = (cv - pv) / matched;
        travel += (float) Math.hypot(du, dv);
        if (travel > cfg.tapSlop) moved = true;
        if (!moved || keyDuringTouch) return;
        if (!scrollActive) {
            scrollActive = true;
            actions.onScrollBegin();
        }
        float k = cfg.screenWidthPx * cfg.scrollSpeed * (cfg.invertScroll ? -1f : 1f);
        actions.onScroll(du * k, dv * k);
    }

    private void end(long now) {
        touching = false;
        long dur = now - touchStart;
        if (scrollActive) {
            scrollActive = false;
            actions.onScrollEnd();
        }
        boolean wasDrag = mode == MODE_DRAG;
        mode = MODE_NONE;
        if (wasDrag) {
            actions.onDragEnd();
        } else if (!moved && dur <= cfg.tapTimeoutMs && !keyDuringTouch) {
            if (maxFingers == 1 && cfg.tapToClick) {
                if (clickOwed) {
                    actions.onClick();
                    actions.onClick();
                    lastTapUp = -1;
                } else if (cfg.tapDrag) {
                    clickPending = true;
                    lastTapUp = now;
                    scheduler.post(pendingClick, cfg.doubleTapWindowMs);
                } else {
                    actions.onClick();
                }
            } else if (maxFingers == 2) {
                actions.onSecondaryClick();
            } else if (maxFingers >= 3) {
                actions.onTertiaryTap();
            }
        } else if (clickOwed && !keyDuringTouch) {
            // Second touch after a tap was held too long without moving: still honour the first tap.
            actions.onClick();
        }
        dragCandidate = false;
        clickOwed = false;
    }

    /**
     * Raw device coordinates -> pad coordinates, or null if outside the active zone.
     * Both axes are expressed in units of the pad's horizontal extent (raw units are assumed square;
     * {@link Config#ySpeed} corrects pads where they are not).
     */
    float[] normalize(int x, int y) {
        if (x < cfg.xMin || x > cfg.xMax || y < cfg.yMin || y > cfg.yMax) return null;
        float rx = x - cfg.xMin, ry = y - cfg.yMin;
        float w = Math.max(1, cfg.xMax - cfg.xMin);
        float h = Math.max(1, cfg.yMax - cfg.yMin);
        if (cfg.swapXY) {
            float t = rx; rx = ry; ry = t;
            t = w; w = h; h = t;
        }
        if (cfg.invertX) rx = w - rx;
        if (cfg.invertY) ry = h - ry;
        return new float[] {rx / w, ry / w * cfg.ySpeed};
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
