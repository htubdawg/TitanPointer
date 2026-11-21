package com.titanpointer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class GestureEngineTest {
    private long now = 1000;
    private final List<String> log = new ArrayList<>();
    private final List<Object[]> scheduled = new ArrayList<>();
    private float moveX, moveY, scrollX, scrollY, flingX, flingY;
    private GestureEngine engine;
    private TouchTracker tracker;

    @Before
    public void setUp() {
        engine = new GestureEngine(new GestureEngine.Actions() {
            @Override public void onPointerMove(float dx, float dy) { moveX += dx; moveY += dy; if (!log.contains("move")) log.add("move"); }
            @Override public void onClick() { log.add("click"); }
            @Override public void onScrollBegin() { log.add("scrollBegin"); }
            @Override public void onScroll(float dx, float dy) { scrollX += dx; scrollY += dy; }
            @Override public void onScrollEnd(float vx, float vy) { log.add("scrollEnd"); flingX = vx; flingY = vy; }
            @Override public void onDragBegin() { log.add("dragBegin"); }
            @Override public void onDragEnd() { log.add("dragEnd"); }
            @Override public void onLongClick() { log.add("longClick"); }
        }, () -> now, new GestureEngine.Scheduler() {
            @Override public void post(Runnable r, long d) { scheduled.add(new Object[] {r, now + d}); }
            @Override public void cancel(Runnable r) { scheduled.removeIf(o -> o[0] == r); }
        });
        GestureEngine.Config c = new GestureEngine.Config();
        c.xMin = 0; c.xMax = 1000; c.yMin = 0; c.yMax = 400;
        c.screenWidthPx = 1000; c.sensitivity = 1; c.acceleration = 0;
        engine.setConfig(c);
        tracker = new TouchTracker(engine);
    }

    private void advance(long ms) {
        now += ms;
        List<Object[]> due = new ArrayList<>();
        for (Object[] o : scheduled) if ((long) o[1] <= now) due.add(o);
        scheduled.removeAll(due);
        for (Object[] o : due) ((Runnable) o[0]).run();
    }

    /** Protocol B frame: pairs of (slot, trackingId, x, y); trackingId -1 lifts. */
    private void frame(int... v) {
        for (int i = 0; i < v.length; i += 4) {
            tracker.onEvent(3, TouchTracker.ABS_MT_SLOT, v[i]);
            tracker.onEvent(3, TouchTracker.ABS_MT_TRACKING_ID, v[i + 1]);
            if (v[i + 1] >= 0) {
                tracker.onEvent(3, TouchTracker.ABS_MT_POSITION_X, v[i + 2]);
                tracker.onEvent(3, TouchTracker.ABS_MT_POSITION_Y, v[i + 3]);
            }
        }
        tracker.onEvent(0, 0, 0);
    }

    @Test
    public void moveMovesPointer() {
        frame(0, 1, 100, 100);
        for (int i = 1; i <= 10; i++) { advance(10); frame(0, 1, 100 + i * 20, 100); }
        advance(10); frame(0, -1, 0, 0);
        advance(1000);
        assertEquals(200f, moveX, 0.5f); // 200 units of 1000 -> 20% of 1000px
        assertEquals(0f, moveY, 0.01f);
        assertEquals(List.of("move"), log);
    }

    @Test
    public void tapClicksAfterDoubleTapWindow() {
        frame(0, 1, 500, 200);
        advance(80); frame(0, -1, 0, 0);
        assertTrue(log.isEmpty());
        advance(300);
        assertEquals(List.of("click"), log);
    }

    @Test
    public void tapWithoutTapDragClicksImmediately() {
        engine.getConfig().tapDrag = false;
        frame(0, 1, 500, 200);
        advance(80); frame(0, -1, 0, 0);
        assertEquals(List.of("click"), log);
    }

    @Test
    public void doubleTap() {
        frame(0, 1, 500, 200);
        advance(80); frame(0, -1, 0, 0);
        advance(100); frame(0, 2, 500, 200);
        advance(80); frame(0, -1, 0, 0);
        advance(1000);
        assertEquals(List.of("click", "click"), log);
    }

    @Test
    public void tapThenMoveDrags() {
        frame(0, 1, 500, 200);
        advance(80); frame(0, -1, 0, 0);
        advance(100); frame(0, 2, 500, 200);
        for (int i = 1; i <= 5; i++) { advance(10); frame(0, 2, 500 + i * 30, 200); }
        advance(10); frame(0, -1, 0, 0);
        advance(1000);
        assertEquals(List.of("move", "dragBegin", "dragEnd"), log);
    }

    @Test
    public void secondFingerIsIgnored() {
        frame(0, 1, 400, 200, 1, 2, 600, 200);
        for (int i = 1; i <= 5; i++) { advance(10); frame(0, 1, 400 + i * 20, 200, 1, 2, 600, 200 + i * 40); }
        assertEquals(100f, moveX, 0.5f);
        assertEquals(0f, moveY, 0.01f);
    }

    @Test
    public void touchStartingInTopRowIsIgnored() {
        engine.getConfig().ignoreTopFrac = 0.2f; // y < 80 of 0..400
        engine.getConfig().tapDrag = false;
        frame(0, 1, 500, 40);
        advance(60); frame(0, -1, 0, 0);
        frame(0, 2, 500, 40);
        for (int i = 1; i <= 5; i++) { advance(10); frame(0, 2, 500 + i * 20, 40 + i * 20); }
        advance(10); frame(0, -1, 0, 0);
        advance(1000);
        assertTrue(log.isEmpty());
    }

    @Test
    public void touchInTopRowCancelsPendingClick() {
        engine.getConfig().ignoreTopFrac = 0.2f;
        frame(0, 1, 500, 200);
        advance(60); frame(0, -1, 0, 0); // tap: click pending
        advance(50); frame(0, 2, 500, 40); // then the finger lands on Back
        advance(60); frame(0, -1, 0, 0);
        advance(1000);
        assertTrue(log.isEmpty());
    }

    @Test
    public void slidingIntoTopRowKeepsMoving() {
        engine.getConfig().ignoreTopFrac = 0.2f;
        frame(0, 1, 500, 200);
        for (int i = 1; i <= 5; i++) { advance(10); frame(0, 1, 500, 200 - i * 35); }
        assertTrue(moveY < -100);
    }

    @Test
    public void keyPressDuringTouchSuppressesClick() {
        frame(0, 1, 500, 200);
        advance(40);
        engine.onKeyActivity();
        advance(40); frame(0, -1, 0, 0);
        advance(1000);
        assertTrue(log.isEmpty());
    }

    @Test
    public void typingSuppressesMovement() {
        engine.onKeyActivity();
        advance(50);
        frame(0, 1, 100, 100);
        for (int i = 1; i <= 5; i++) { advance(10); frame(0, 1, 100 + i * 20, 100); }
        assertEquals(0f, moveX, 0.01f);
    }

    @Test
    public void zoneFiltersContacts() {
        GestureEngine.Config c = engine.getConfig();
        c.yMin = 200; // only lower half counts
        frame(0, 1, 500, 100);
        advance(80); frame(0, -1, 0, 0);
        advance(1000);
        assertTrue(log.isEmpty());
    }

    @Test
    public void swapAndInvert() {
        GestureEngine.Config c = engine.getConfig();
        c.swapXY = true; c.invertX = true;
        // Moving +y on the device becomes -x on screen after swap+invert.
        frame(0, 1, 100, 100);
        for (int i = 1; i <= 5; i++) { advance(10); frame(0, 1, 100, 100 + i * 20); }
        assertTrue(moveX < 0);
        assertEquals(0f, moveY, 0.01f);
    }

    @Test
    public void protocolA() {
        // Two separate taps reported with SYN_MT_REPORT
        engine.getConfig().tapDrag = false;
        tracker.onEvent(3, TouchTracker.ABS_MT_POSITION_X, 500);
        tracker.onEvent(3, TouchTracker.ABS_MT_POSITION_Y, 200);
        tracker.onEvent(0, TouchTracker.SYN_MT_REPORT, 0);
        tracker.onEvent(0, 0, 0);
        advance(50);
        tracker.onEvent(0, TouchTracker.SYN_MT_REPORT, 0);
        tracker.onEvent(0, 0, 0);
        assertEquals(List.of("click"), log);
    }

    @Test
    public void singleTouchDevice() {
        engine.getConfig().tapDrag = false;
        tracker.onEvent(1, TouchTracker.BTN_TOUCH, 1);
        tracker.onEvent(3, TouchTracker.ABS_X, 500);
        tracker.onEvent(3, TouchTracker.ABS_Y, 200);
        tracker.onEvent(0, 0, 0);
        advance(50);
        tracker.onEvent(1, TouchTracker.BTN_TOUCH, 0);
        tracker.onEvent(0, 0, 0);
        assertEquals(List.of("click"), log);
    }

    @Test
    public void scrollLockMakesOneFingerScroll() {
        engine.setScrollLock(true);
        frame(0, 1, 500, 100);
        for (int i = 1; i <= 5; i++) { advance(10); frame(0, 1, 500, 100 + i * 20); }
        advance(10); frame(0, -1, 0, 0);
        advance(1000);
        assertEquals(List.of("scrollBegin", "scrollEnd"), log);
        assertTrue(scrollY > 20);
        assertEquals(0f, moveY, 0.01f);
    }

    @Test
    public void scrollModeTapsNeverClick() {
        engine.setScrollLock(true);
        frame(0, 1, 500, 200);
        advance(60); frame(0, -1, 0, 0);
        advance(50); frame(0, 2, 500, 200);
        advance(60); frame(0, -1, 0, 0);
        advance(1000);
        assertTrue(log.isEmpty());
    }

    @Test
    public void scrollNeedsAClearSlide() {
        engine.setScrollLock(true);
        frame(0, 1, 500, 200);
        for (int i = 1; i <= 5; i++) { advance(10); frame(0, 1, 500, 200 + i * 10); } // 50 of 1000 < 6%
        advance(10); frame(0, -1, 0, 0);
        advance(1000);
        assertTrue(log.isEmpty());
        assertEquals(0f, scrollY, 0.01f);
    }

    /** Scroll swipe down: {@code frames} moves of {@code step} raw units every 10 ms. */
    private void scrollSwipe(int frames, int step) {
        frame(0, 1, 500, 50);
        for (int i = 1; i <= frames; i++) { advance(10); frame(0, 1, 500, 50 + i * step); }
    }

    @Test
    public void flickFlings() {
        engine.setScrollLock(true);
        scrollSwipe(6, 20); // 0.02 pad widths per 10 ms = 2 pad widths/s
        advance(10); frame(0, -1, 0, 0);
        assertEquals(List.of("scrollBegin", "scrollEnd"), log);
        // 2 pad widths/s (the lift 10 ms after the last move counts as slowing a little) x 1000 px x 1.5
        assertTrue("fling " + flingY, flingY > 2000 && flingY <= 3000);
        assertEquals(0f, flingX, 0.01f);
    }

    @Test
    public void stoppingBeforeLiftDoesNotFling() {
        engine.setScrollLock(true);
        scrollSwipe(6, 20);
        advance(150); frame(0, -1, 0, 0);
        assertEquals(List.of("scrollBegin", "scrollEnd"), log);
        assertEquals(0f, flingY, 0.01f);
    }

    @Test
    public void slowSwipeDoesNotFling() {
        engine.setScrollLock(true);
        scrollSwipe(40, 3); // 0.3 pad widths/s
        advance(10); frame(0, -1, 0, 0);
        assertEquals(List.of("scrollBegin", "scrollEnd"), log);
        assertEquals(0f, flingY, 0.01f);
    }

    @Test
    public void flingFollowsReverseScrollAndCanBeTurnedOff() {
        engine.setScrollLock(true);
        engine.getConfig().invertScroll = true;
        scrollSwipe(6, 20);
        advance(10); frame(0, -1, 0, 0);
        assertTrue(flingY < -2000);

        engine.getConfig().fling = false;
        flingY = 123;
        advance(500);
        scrollSwipe(6, 20);
        advance(10); frame(0, -1, 0, 0);
        assertEquals(0f, flingY, 0.01f);
    }

    @Test
    public void leavingScrollModeMidSwipeDoesNotFling() {
        engine.setScrollLock(true);
        scrollSwipe(6, 20);
        flingY = 123;
        engine.setScrollLock(false);
        assertEquals(0f, flingY, 0.01f);
    }

    @Test
    public void parsesDeviceLine() {
        InputDeviceInfo d = InputDeviceInfo.parse("D /dev/input/event3\tmtk-kpd-touch\t0\t2\t0 1079 0 399\t10\t0");
        assertEquals("/dev/input/event3", d.path);
        assertEquals("mtk-kpd-touch", d.name);
        assertEquals(1079, d.xMax);
        assertEquals(10, d.slots);
        InputDeviceInfo screen = InputDeviceInfo.parse("D /dev/input/event2\tmtk-tpd\t2\t2\t0 1439 0 1439\t10\t0");
        assertTrue(d.score(1440, 1440) > screen.score(1440, 1440));
    }

    // ---- Joystick mode ----

    private void joystick() {
        GestureEngine.Config c = engine.getConfig();
        c.joystick = true;
        c.joyClickZone = 1f / 3f;
        c.joyDeadZone = 0.01f;
        c.joyReach = 0.1f;
        c.joyMaxSpeed = 1f; // 1000 px/s at full deflection
        c.joyCurve = 1f;
        engine.setConfig(c);
    }

    /** Hold a single finger at (x, y) for ms, in 16 ms steps (the pad may not report while still). */
    private void hold(long ms) {
        for (long t = 0; t < ms; t += 16) advance(16);
    }

    @Test
    public void joystickHoldMovesPointerWithoutPadEvents() {
        joystick();
        frame(0, 1, 500, 200);          // middle zone: centre here
        advance(16); frame(0, 1, 600, 200); // 0.1 pad widths right: full speed
        hold(1000);                      // finger still, no more reports
        assertTrue("moved " + moveX, moveX > 900 && moveX < 1100);
        assertEquals(0f, moveY, 0.01f);
        float before = moveX;
        frame(0, -1, 0, 0);
        hold(500);
        assertEquals(before, moveX, 0.01f); // stops on lift
        assertEquals(List.of("move"), log); // no click from the stick
    }

    @Test
    public void joystickDeadZoneAndSpeedCurve() {
        joystick();
        frame(0, 1, 500, 200);
        advance(16); frame(0, 1, 508, 200); // inside the dead zone
        hold(500);
        assertEquals(0f, moveX, 0.01f);
        frame(0, 1, 555, 200); // halfway to full: half speed with a linear curve
        hold(1000);
        assertTrue("moved " + moveX, moveX > 400 && moveX < 600);
    }

    @Test
    public void joystickCentreFloatsAndFingerMayLeaveMiddle() {
        joystick();
        frame(0, 1, 400, 200);           // lands near the left edge of the middle
        advance(16); frame(0, 1, 300, 200); // dragged into the left third: still the stick
        hold(500);
        assertTrue(moveX < -400);
        frame(0, -1, 0, 0);
        hold(500);
        assertEquals(List.of("move"), log);
    }

    @Test
    public void joystickMiddleTapDoesNothing() {
        joystick();
        frame(0, 1, 500, 200);
        advance(60); frame(0, -1, 0, 0);
        hold(500);
        assertTrue(log.isEmpty());
    }

    @Test
    public void joystickLeftTapClicksRightTapLongPresses() {
        joystick();
        frame(0, 1, 100, 200);
        advance(80); frame(0, -1, 0, 0);
        advance(300);
        assertEquals(List.of("click"), log);
        frame(0, 1, 900, 200);
        advance(80); frame(0, -1, 0, 0);
        assertEquals(List.of("click", "longClick"), log);
    }

    @Test
    public void joystickDoubleTapLeftLocksDragUntilNextTap() {
        joystick();
        frame(0, 1, 100, 200);
        advance(60); frame(0, -1, 0, 0);
        advance(100);
        frame(0, 1, 110, 200);
        advance(60); frame(0, -1, 0, 0);
        assertEquals(List.of("dragBegin"), log);
        assertTrue(engine.isDragLocked());
        frame(0, 1, 500, 200);           // steer while dragging
        advance(16); frame(0, 1, 600, 200);
        hold(200);
        frame(0, -1, 0, 0);
        assertTrue(engine.isDragLocked()); // lifting the stick keeps the drag
        hold(500);
        frame(0, 1, 900, 200);            // a tap on either side lets go (no long press)
        advance(60); frame(0, -1, 0, 0);
        assertEquals(List.of("dragBegin", "move", "dragEnd"), log);
        assertTrue(!engine.isDragLocked());
    }

    @Test
    public void joystickPendingClickFiresBeforeStickMoves() {
        joystick();
        frame(0, 1, 100, 200);
        advance(60); frame(0, -1, 0, 0);
        advance(50);
        frame(0, 1, 500, 200); // stick touched within the double-tap window
        assertEquals(List.of("click"), log);
        advance(16); frame(0, 1, 600, 200);
        hold(100);
        frame(0, -1, 0, 0);
        hold(500);
        assertEquals(List.of("click", "move"), log);
    }

    @Test
    public void joystickKeyPressTouchesDontClickOrMove() {
        joystick();
        frame(0, 1, 900, 200);    // Enter's touch on the right
        advance(10); engine.onKeyActivity();
        advance(60); frame(0, -1, 0, 0);
        advance(400);
        frame(0, 1, 500, 200);    // a letter in the middle
        engine.onKeyActivity();
        advance(16); frame(0, 1, 600, 200);
        hold(300);
        frame(0, -1, 0, 0);
        assertTrue(log.toString(), log.isEmpty());
    }

    @Test
    public void joystickScrollModeScrolls() {
        joystick();
        engine.setScrollLock(true);
        frame(0, 1, 500, 200);
        advance(16); frame(0, 1, 500, 300);
        hold(500);
        frame(0, -1, 0, 0);
        assertEquals(0f, moveY, 0.01f);
        assertTrue("scrolled " + scrollY, scrollY > 300);
        assertEquals(List.of("scrollBegin", "scrollEnd"), log);
    }

    @Test
    public void joystickTopRowIgnored() {
        joystick();
        engine.getConfig().ignoreTopFrac = 0.22f;
        frame(0, 1, 100, 20);
        advance(60); frame(0, -1, 0, 0);
        frame(0, 1, 500, 20);
        advance(16); frame(0, 1, 600, 20);
        hold(300); frame(0, -1, 0, 0);
        advance(500);
        assertTrue(log.isEmpty());
    }
}
