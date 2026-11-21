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
 *   <li>scroll mode on: slides scroll instead, and taps never click; a quick flick keeps scrolling (fling)</li>
 * </ul>
 *
 * Joystick mode ({@link Config#joystick}) splits the pad into three zones, chosen where a touch starts:
 * <ul>
 *   <li>middle: a joystick with a floating centre (where the finger landed; the finger may then leave the
 *       middle zone). While held, the pointer moves on a timer at a speed set by the distance from the centre,
 *       or scrolls in scroll mode. Taps here do nothing.</li>
 *   <li>left: tap = click; tap twice = drag lock (the stick then drags) until the next tap on either side</li>
 *   <li>right: tap = long press ("right click")</li>
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
        /**
         * The scroll swipe ended. (vx, vy) is the fling velocity in screen pixels per second, already scaled
         * and inverted like {@link #onScroll}; both are 0 when the finger stopped before lifting (no fling).
         */
        void onScrollEnd(float vx, float vy);
        void onDragBegin();
        void onDragEnd();
        /** Joystick mode's right zone: long press at the pointer. */
        void onLongClick();
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
        /** Flick to keep scrolling after the finger lifts. */
        public boolean fling = true;
        /** Pad speed (pad widths per second) over the last {@link #flingWindowMs} needed for a fling. */
        public float flingMinSpeed = 0.6f;
        /** How much of the swipe's end is used to measure its speed. */
        public long flingWindowMs = 80;
        /**
         * Touches that start in this top fraction of the device's raw Y range are ignored (the Titan's
         * top keyboard row: Shift, Recents, home, Back, Alt). Measured on raw device Y, before swap/invert.
         */
        public float ignoreTopFrac = 0f;

        /** Joystick mode instead of touchpad mode. */
        public boolean joystick = false;
        /** Width of each click zone (left and right), as a fraction of the pad width. The middle is the stick. */
        public float joyClickZone = 1f / 3f;
        /** Distance from the stick's centre (pad widths) where the pointer starts to move. */
        public float joyDeadZone = 0.015f;
        /** Distance from the centre (pad widths) that gives full speed. */
        public float joyReach = 0.10f;
        /** Pointer speed at full deflection, in screen widths per second. */
        public float joyMaxSpeed = 1.2f;
        /** Response curve: speed = top speed × deflection^curve (deflection 0..1 past the dead zone). */
        public float joyCurve = 2.0f;
        /** A touch on a click zone held longer than this isn't a tap. */
        public long joyClickMaxMs = 500;
    }

    /** Joystick timer interval. */
    static final long JOY_TICK_MS = 16;
    private static final int ZONE_NONE = 0, ZONE_LEFT = 1, ZONE_MID = 2, ZONE_RIGHT = 3;

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

    // Recent scroll movement (pad units) for measuring fling speed: ring buffer of (time, du, dv).
    private static final int SAMPLES = 16;
    private final long[] sampleT = new long[SAMPLES];
    private final long[] samplePrevT = new long[SAMPLES];
    private final float[] sampleU = new float[SAMPLES], sampleV = new float[SAMPLES];
    private int sampleCount = 0, sampleHead = 0;

    private final Runnable pendingClick = new Runnable() {
        @Override
        public void run() {
            clickPending = false;
            actions.onClick();
        }
    };
    private boolean clickPending = false;

    // Joystick mode state.
    private int joyZone = ZONE_NONE;
    private float[] joyCenter, joyPos;
    private boolean joyMoved;
    private boolean dragLock = false;
    private long lastTick;
    private boolean ticking = false;
    private final Runnable joyTick = new Runnable() {
        @Override
        public void run() {
            ticking = false;
            tick(clock.now());
        }
    };

    public GestureEngine(Actions actions, Clock clock, Scheduler scheduler) {
        this.actions = actions;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    public void setConfig(Config c) {
        boolean switched = c.joystick != cfg.joystick;
        if (switched) reset();
        this.cfg = c;
    }

    /** True while joystick mode's drag lock holds the injected finger down. */
    public boolean isDragLocked() {
        return dragLock;
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
            actions.onScrollEnd(0, 0);
        }
    }

    public boolean isScrollLock() {
        return scrollLock;
    }

    /** Abandon any gesture in progress (e.g. when pointer mode is switched off). */
    public void reset() {
        if (scrollActive) actions.onScrollEnd(0, 0);
        if (mode == MODE_DRAG || dragLock) actions.onDragEnd();
        cancelPendingClick();
        stopTicking();
        dragLock = false;
        joyZone = ZONE_NONE;
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

    private void stopTicking() {
        if (ticking) scheduler.cancel(joyTick);
        ticking = false;
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
        if (cfg.joystick) {
            joyFrame(now, c);
            return;
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
            slide(now, p[0] - prev[0], p[1] - prev[1], dt, lastFrameTime);
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

    private void slide(long now, float du, float dv, float dt, long prevTime) {
        float dist = (float) Math.hypot(du, dv);
        travel += dist;
        if (travel > cfg.tapSlop) moved = true;

        if (scrollLock && mode != MODE_DRAG) {
            if (keyDuringTouch) return;
            if (!scrollActive) {
                if (travel < cfg.scrollSlop) return;
                scrollActive = true;
                mode = MODE_SCROLL;
                sampleCount = 0;
                actions.onScrollBegin();
            }
            addSample(now, prevTime, du, dv);
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
            float vx = 0, vy = 0;
            float[] v = cfg.fling && !keyDuringTouch ? padVelocity(now) : null;
            if (v != null && Math.hypot(v[0], v[1]) >= cfg.flingMinSpeed) {
                float k = cfg.screenWidthPx * cfg.scrollSpeed * (cfg.invertScroll ? -1f : 1f);
                vx = v[0] * k;
                vy = v[1] * k;
            }
            actions.onScrollEnd(vx, vy);
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

    // ---- Joystick mode ----

    private void joyFrame(long now, TouchTracker.Contact c) {
        if (c == null) {
            if (touching) joyEnd(now);
            prev = null;
            lastFrameTime = now;
            return;
        }
        float[] p = normalize(c.x, c.y);
        if (!touching) joyBegin(now, c, p);
        trackedId = c.id;
        joyPos = p;
        if (joyCenter != null && Math.hypot(p[0] - joyCenter[0], p[1] - joyCenter[1]) > cfg.tapSlop) joyMoved = true;
        lastFrameTime = now;
    }

    private void joyBegin(long now, TouchTracker.Contact c, float[] p) {
        touching = true;
        trackedId = c.id;
        touchStart = now;
        lastFrameTime = now;
        keyDuringTouch = now - lastKeyTime < cfg.typingSuppressMs;
        scrollActive = false;
        joyMoved = false;
        joyCenter = p;
        joyPos = p;
        dragCandidate = false;
        float yRange = Math.max(1, cfg.yMax - cfg.yMin);
        ignored = cfg.ignoreTopFrac > 0 && (c.y - cfg.yMin) / yRange < cfg.ignoreTopFrac;
        if (ignored) {
            joyZone = ZONE_NONE;
            return;
        }
        float u = p[0];
        joyZone = u < cfg.joyClickZone ? ZONE_LEFT : (u > 1f - cfg.joyClickZone ? ZONE_RIGHT : ZONE_MID);
        if (joyZone == ZONE_MID) {
            // A click still waiting for a possible second tap goes out now, where the pointer is, before it moves.
            if (clickPending) {
                cancelPendingClick();
                actions.onClick();
            }
            lastTick = now;
            stopTicking();
            ticking = true;
            scheduler.post(joyTick, JOY_TICK_MS);
        } else if (joyZone == ZONE_LEFT && clickPending && !dragLock
                && now - lastTapUp <= cfg.doubleTapWindowMs) {
            cancelPendingClick();
            dragCandidate = true; // second tap: drag lock if it's a tap too
        }
    }

    private void tick(long now) {
        if (!touching || joyZone != ZONE_MID || !cfg.joystick) return;
        long dt = Math.min(50, Math.max(0, now - lastTick));
        lastTick = now;
        if (!keyDuringTouch && now - lastKeyTime >= cfg.typingSuppressMs && joyPos != null) {
            float ox = joyPos[0] - joyCenter[0], oy = joyPos[1] - joyCenter[1];
            float d = (float) Math.hypot(ox, oy);
            float v = joySpeed(d); // px per second
            if (v > 0) {
                float step = v * dt / 1000f;
                float dx = ox / d * step, dy = oy / d * step;
                if (scrollLock && !dragLock) {
                    if (!scrollActive) {
                        scrollActive = true;
                        mode = MODE_SCROLL;
                        actions.onScrollBegin();
                    }
                    // Scroll speed 1.5× (the default) scrolls as fast as the pointer would move.
                    float k = cfg.scrollSpeed / 1.5f * (cfg.invertScroll ? -1f : 1f);
                    actions.onScroll(dx * k, dy * k);
                } else {
                    actions.onPointerMove(dx, dy);
                }
            }
        }
        ticking = true;
        scheduler.post(joyTick, JOY_TICK_MS);
    }

    /** Pointer speed in px/s for a stick deflection of {@code d} pad widths from the centre. */
    float joySpeed(float d) {
        if (d <= cfg.joyDeadZone) return 0f;
        float t = clamp((d - cfg.joyDeadZone) / Math.max(0.001f, cfg.joyReach - cfg.joyDeadZone), 0f, 1f);
        return (float) Math.pow(t, cfg.joyCurve) * cfg.joyMaxSpeed * cfg.screenWidthPx;
    }

    private void joyEnd(long now) {
        touching = false;
        stopTicking();
        int zone = joyZone;
        joyZone = ZONE_NONE;
        boolean second = dragCandidate;
        dragCandidate = false;
        if (ignored) {
            ignored = false;
            return;
        }
        if (scrollActive) {
            scrollActive = false;
            actions.onScrollEnd(0, 0);
        }
        mode = dragLock ? MODE_DRAG : MODE_NONE;
        if (zone != ZONE_LEFT && zone != ZONE_RIGHT) return; // the stick: taps do nothing
        boolean tap = !keyDuringTouch && !joyMoved && now - touchStart <= cfg.joyClickMaxMs;
        if (!tap) {
            if (second && !keyDuringTouch) actions.onClick(); // the first tap still counts
            return;
        }
        if (dragLock) {
            dragLock = false;
            mode = MODE_NONE;
            actions.onDragEnd();
        } else if (zone == ZONE_RIGHT) {
            actions.onLongClick();
        } else if (second) {
            dragLock = true;
            mode = MODE_DRAG;
            actions.onDragBegin();
        } else {
            clickPending = true;
            lastTapUp = now;
            scheduler.post(pendingClick, cfg.doubleTapWindowMs);
        }
    }

    private void addSample(long t, long prevT, float du, float dv) {
        sampleT[sampleHead] = t;
        samplePrevT[sampleHead] = prevT;
        sampleU[sampleHead] = du;
        sampleV[sampleHead] = dv;
        sampleHead = (sampleHead + 1) % SAMPLES;
        if (sampleCount < SAMPLES) sampleCount++;
    }

    /**
     * Speed of the scroll swipe's last {@link Config#flingWindowMs} up to the lift at {@code now}, in pad
     * units per second, or null if the finger didn't move in that time. A pause before lifting counts as
     * slowing down, so a swipe that stops and then lifts doesn't fling.
     */
    float[] padVelocity(long now) {
        long from = now - cfg.flingWindowMs;
        float su = 0, sv = 0;
        long start = now;
        int n = 0;
        for (int i = 0; i < sampleCount; i++) {
            int j = (sampleHead - 1 - i + SAMPLES) % SAMPLES;
            if (sampleT[j] <= from) break;
            su += sampleU[j];
            sv += sampleV[j];
            start = Math.max(from, samplePrevT[j]);
            n++;
        }
        if (n == 0) return null;
        float dt = Math.max(16, now - start) / 1000f;
        return new float[] {su / dt, sv / dt};
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
