package com.titanpointer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;

/**
 * Injects touches through {@link AccessibilityService#dispatchGesture}.
 *
 * Taps and long presses are single gestures. Scrolls and drags are "streams": one continued stroke
 * (willContinue = true) whose segments are dispatched one after another as the fingers move, so the
 * target app sees a single smooth finger drag, including a natural fling when it ends.
 */
public final class GestureInjector {
    private static final String TAG = "TitanPointer";

    private final AccessibilityService svc;
    private int screenW = 1440, screenH = 1440;

    // Stream state (main thread only)
    private boolean streaming = false;
    private boolean inFlight = false;
    private boolean ending = false;
    private GestureDescription.StrokeDescription lastStroke;
    private float lastX, lastY; // end of the last dispatched segment
    private float targetX, targetY;
    private long lastSegmentAt;
    /** Fling velocity (px/s) for the final segment when the stream ends with {@link #flingEnd}. */
    private float flingVx, flingVy;
    /** Discrete gestures that arrive while a stream is finishing. */
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    /** While false, nothing new is injected (pointer mode is off). */
    private boolean enabled = true;

    public GestureInjector(AccessibilityService svc) {
        this.svc = svc;
    }

    public void setScreenSize(int w, int h) {
        screenW = w;
        screenH = h;
    }

    /** Turning off drops queued taps and lifts any injected finger; later gestures are ignored. */
    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            queue.clear();
            endStream();
        }
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void tap(float x, float y) {
        discrete(x, y, 40);
    }

    public void longPress(float x, float y) {
        discrete(x, y, 650);
    }

    private void discrete(final float x, final float y, final long duration) {
        if (!enabled) return;
        if (streaming || inFlight) {
            queue.add(() -> discrete(x, y, duration));
            return;
        }
        Path p = new Path();
        p.moveTo(cx(x), cy(y));
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, duration)).build();
        inFlight = true;
        boolean ok = svc.dispatchGesture(g, new GestureResult(), null);
        if (!ok) {
            inFlight = false;
            Log.w(TAG, "dispatchGesture refused");
            drainQueue();
        }
    }

    /** Put a finger down at (x, y). */
    public void beginStream(float x, float y) {
        if (!enabled) return;
        if (streaming) endStream();
        if (inFlight) {
            final float fx = x, fy = y;
            queue.add(() -> beginStream(fx, fy));
            return;
        }
        streaming = true;
        ending = false;
        lastStroke = null;
        lastX = targetX = cx(x);
        lastY = targetY = cy(y);
        lastSegmentAt = SystemClock.uptimeMillis();
        pump();
    }

    /** Move the injected finger to (x, y). Returns false if the point had to be clamped to the screen. */
    public boolean streamTo(float x, float y) {
        if (!streaming || ending) return true;
        targetX = cx(x);
        targetY = cy(y);
        pump();
        return targetX == x && targetY == y;
    }

    /** Lift the injected finger. */
    public void endStream() {
        if (!streaming) return;
        ending = true;
        pump();
    }

    /** Duration of the final, still-moving segment of a fling. */
    private static final long FLING_SEGMENT_MS = 60;
    /** Cap on the injected fling speed (px/s); Android's own fling cap is about 8000 dp/s. */
    private static final float MAX_FLING = 12000f;

    /**
     * Lift the injected finger while it's still moving at (vx, vy) px/s, so the app under it flings
     * (keeps scrolling and slows down) exactly as it does for a real flick.
     */
    public void flingEnd(float vx, float vy) {
        if (!streaming || ending) return;
        float speed = (float) Math.hypot(vx, vy);
        float k = speed > MAX_FLING ? MAX_FLING / speed : 1f;
        flingVx = vx * k;
        flingVy = vy * k;
        endStream();
    }

    private void pump() {
        if (!streaming || inFlight) return;
        long now = SystemClock.uptimeMillis();
        boolean first = lastStroke == null;
        boolean moving = targetX != lastX || targetY != lastY;
        if (!first && !moving && !ending) return; // finger is held; wait for movement

        long dur = first ? 10 : Math.max(8, Math.min(80, now - lastSegmentAt));
        if (ending && !first && (flingVx != 0 || flingVy != 0)) {
            // Keep moving at the fling speed for one last segment and lift at its end. Near the screen
            // edge the segment is shortened, keeping the speed, so the finger stays on screen.
            float t = FLING_SEGMENT_MS / 1000f;
            float ex = cx(targetX + flingVx * t), ey = cy(targetY + flingVy * t);
            float sx = flingVx == 0 ? 1f : (ex - targetX) / (flingVx * t);
            float sy = flingVy == 0 ? 1f : (ey - targetY) / (flingVy * t);
            float s = Math.max(0f, Math.min(sx, sy));
            if (s > 0.15f) {
                targetX += flingVx * t * s;
                targetY += flingVy * t * s;
                dur = Math.max(8, Math.round(FLING_SEGMENT_MS * s));
                moving = true;
            }
            flingVx = flingVy = 0;
        }
        Path p = new Path();
        p.moveTo(lastX, lastY);
        if (moving && !first) p.lineTo(targetX, targetY);
        boolean cont = !ending;
        GestureDescription.StrokeDescription s;
        try {
            s = first ? new GestureDescription.StrokeDescription(p, 0, dur, cont)
                    : lastStroke.continueStroke(p, 0, dur, cont);
        } catch (RuntimeException e) {
            Log.w(TAG, "stroke failed: " + e);
            resetStream();
            return;
        }
        if (!first && moving) {
            lastX = targetX;
            lastY = targetY;
        }
        lastStroke = s;
        lastSegmentAt = now;
        inFlight = true;
        final boolean last = !cont;
        GestureDescription g = new GestureDescription.Builder().addStroke(s).build();
        boolean ok = svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                inFlight = false;
                if (last) {
                    resetStream();
                } else {
                    pump();
                }
            }

            @Override
            public void onCancelled(GestureDescription d) {
                inFlight = false;
                resetStream();
            }
        }, null);
        if (!ok) {
            inFlight = false;
            resetStream();
        }
    }

    private void resetStream() {
        streaming = false;
        ending = false;
        flingVx = flingVy = 0;
        lastStroke = null;
        drainQueue();
    }

    private void drainQueue() {
        while (!queue.isEmpty() && !streaming && !inFlight) queue.poll().run();
    }

    private final class GestureResult extends AccessibilityService.GestureResultCallback {
        @Override
        public void onCompleted(GestureDescription d) {
            inFlight = false;
            drainQueue();
        }

        @Override
        public void onCancelled(GestureDescription d) {
            inFlight = false;
            drainQueue();
        }
    }

    private float cx(float x) {
        return Math.max(0, Math.min(screenW - 1, x));
    }

    private float cy(float y) {
        return Math.max(0, Math.min(screenH - 1, y));
    }
}
