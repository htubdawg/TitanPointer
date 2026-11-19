package com.titanpointer;

import java.util.List;

/**
 * Touchpad gestures for a single-touch pad (the Titan's keyboard only reports one finger).
 *
 * <ul>
 *   <li>slide: move the pointer (with acceleration)</li>
 *   <li>tap: click</li>
 *   <li>tap twice: double click</li>
 *   <li>tap, then touch again and slide: drag (optional)</li>
 *   <li>scroll mode on: slides scroll instead, and taps never click</li>
 * </ul>
 *
 * Touches that start in the keyboard's top row (Back, Recents, home) are ignored entirely.
 * Pure Java with no Android dependencies so it can be unit tested on the JVM.
 */
public final class GestureEngine implements TouchTracker.Listener {

    public interface Actions {
        void onPointerMove(float dx, float dy);
        void onClick();
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
        public float sensitivity = 0.7f;
        public float acceleration = 1.0f;
        public float ySpeed = 1.0f;
        public float scrollSpeed = 1.5f;
        public boolean invertScroll = false;
        public boolean tapToClick = true;
        public boolean tapDrag = true;
        public long tapTimeoutMs = 220;
        public long doubleTapWindowMs = 260;
        public float tapSlop = 0.035f;
        /** A slide must travel this far before scrolling starts, so small wobbles never scroll. */
        public float scrollSlop = 0.06f;
        public long typingSuppressMs = 350;
        /**
         * Touches that start in this top fraction of the device's raw Y range are ignored (the Titan's
         * top keyboard row: Shift, Recents, home, Back, Alt). Measured on raw device Y, before swap/invert.
         */
        public float ignoreTopFrac = 0f;
    }

    private static final int MODE_NONE = 0, MODE_POINTER = 1, MODE_SCROLL = 2, MODE_DRAG = 3;

    private final Actions actions;
    private final Clock clock;
    private final Scheduler scheduler;
    private Config cfg = new Config();

    private boolean touching = false;
    private boolean ignored = false; // this touch started in the ignored zone
    private long touchStart;
    private long lastFrameTime;
    private boolean moved;
    private float travel;
    private boolean keyDuringTouch;
    private int mode = MODE_NONE;
    private boolean scrollActive = false;
    private boolean scrollLock = false;
    private boolean dragCandidate = false;
    private boolean clickOwed = false;
    private long lastTapUp = -1;
    private long lastKeyTime = Long.MIN_VALUE / 2;

    private int trackedId;
    private float[] prev; // previous normalized position of the tracked finger

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

    /** Call for every physical key press so typing (or a key on the pad) isn't taken as touchpad use. */
    public void onKeyActivity() {
        lastKeyTime = clock.now();
        if (touching) keyDuringTouch = true;
        cancelPendingClick();
        dragCandidate = false;
        clickOwed = false;
    }

    public void setScrollLock(boolean on) {
        if (scrollLock == on) return;
        scrollLock = on;
        cancelPendingClick();
        if (!on && scrollActive) {
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
        cancelPendingClick();
        touching = false;
        ignored = false;
        scrollActive = false;
        mode = MODE_NONE;
        dragCandidate = false;
        clickOwed = false;
        prev = null;
    }

    private void cancelPendingClick() {
        if (clickPending) scheduler.cancel(pendingClick);
        clickPending = false;
    }

    @Override
    public void onFrame(List<TouchTracker.Contact> contacts) {
        long now = clock.now();
        // Only contacts inside the active zone count; prefer the finger we're already tracking.
        TouchTracker.Contact c = null;
        for (TouchTracker.Contact x : contacts) {
            if (normalize(x.x, x.y) == null) continue;
            if (c == null || (touching && x.id == trackedId)) c = x;
        }

        if (c == null) {
            if (touching) end(now);
            prev = null;
            lastFrameTime = now;
            return;
        }
        if (!touching) {
            begin(now, c);
        } else if (c.id != trackedId) {
            trackedId = c.id; // a different finger took over: no jump
            prev = null;
        }

        float[] p = normalize(c.x, c.y);
        if (!ignored && p != null && prev != null) {
            float dt = Math.max(5f, now - lastFrameTime) / 1000f;
            slide(now, p[0] - prev[0], p[1] - prev[1], dt);
        }
        prev = p;
        lastFrameTime = now;
    }

    private void begin(long now, TouchTracker.Contact c) {
        touching = true;
        trackedId = c.id;
        touchStart = now;
        lastFrameTime = now;
        moved = false;
        travel = 0;
        prev = null;
        keyDuringTouch = now - lastKeyTime < cfg.typingSuppressMs;
        scrollActive = false;
        mode = MODE_POINTER;
        float yRange = Math.max(1, cfg.yMax - cfg.yMin);
        ignored = cfg.ignoreTopFrac > 0 && (c.y - cfg.yMin) / yRange < cfg.ignoreTopFrac;
        if (ignored) {
            // Most likely Back/Recents: don't let it complete an earlier tap or start a drag.
            cancelPendingClick();
            dragCandidate = false;
            clickOwed = false;
            return;
        }
        if (clickPending && cfg.tapDrag && !scrollLock && now - lastTapUp <= cfg.doubleTapWindowMs) {
            cancelPendingClick();
            dragCandidate = true;
            clickOwed = true;
        } else {
            dragCandidate = false;
            clickOwed = false;
        }
    }

    private void slide(long now, float du, float dv, float dt) {
        float dist = (float) Math.hypot(du, dv);
        travel += dist;
        if (travel > cfg.tapSlop) moved = true;

        if (scrollLock && mode != MODE_DRAG) {
            if (keyDuringTouch) return;
            if (!scrollActive) {
                if (travel < cfg.scrollSlop) return;
                scrollActive = true;
                mode = MODE_SCROLL;
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

        float speed = dist / dt; // pad widths per second
        float accel = 1f + cfg.acceleration * clamp((speed - 0.15f) / 0.8f, 0f, 3f);
        float k = cfg.screenWidthPx * cfg.sensitivity * accel;
        actions.onPointerMove(du * k, dv * k);
    }

    private void end(long now) {
        touching = false;
        if (ignored) {
            ignored = false;
            mode = MODE_NONE;
            return;
        }
        long dur = now - touchStart;
        if (scrollActive) {
            scrollActive = false;
            actions.onScrollEnd();
        }
        boolean wasDrag = mode == MODE_DRAG;
        mode = MODE_NONE;
        if (wasDrag) {
            actions.onDragEnd();
        } else if (scrollLock) {
            // Scroll mode: touches never click.
        } else if (!moved && dur <= cfg.tapTimeoutMs && !keyDuringTouch && cfg.tapToClick) {
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
