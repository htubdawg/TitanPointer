package com.titanpointer;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * Draws the pointer and turns touchpad gestures into injected touches. Being an accessibility service
 * gives us an overlay window type that needs no extra permission, gesture injection, and a view of
 * hardware key presses (to tell typing apart from touchpad use, and for the toggle shortcut).
 */
public final class PointerService extends AccessibilityService
        implements PadLink.Listener, GestureEngine.Actions, SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String OWNER = "service";
    private static PointerService running;

    public static boolean isRunning() {
        return running != null;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private PadLink link;
    private WindowManager wm;
    private CursorView cursor;
    private WindowManager.LayoutParams lp;
    private GestureInjector injector;
    private GestureEngine engine;
    private TouchTracker tracker;

    private boolean active = false;
    private boolean cursorShown = false;
    private float px, py; // pointer position in screen pixels
    private int screenW, screenH;
    private float scrollX, scrollY; // injected finger position during a scroll
    private boolean dragging = false;

    private final Runnable autoHide = () -> setCursorVisible(false);

    @Override
    protected void onServiceConnected() {
        running = this;
        prefs = Prefs.get(this);
        link = PadLink.get(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        injector = new GestureInjector(this);
        engine = new GestureEngine(this, SystemClock::uptimeMillis, new GestureEngine.Scheduler() {
            @Override
            public void post(Runnable r, long delayMs) {
                main.postDelayed(r, delayMs);
            }

            @Override
            public void cancel(Runnable r) {
                main.removeCallbacks(r);
            }
        });
        tracker = new TouchTracker(engine);
        updateScreen();
        px = screenW / 2f;
        py = screenH / 2f;
        createCursor();
        prefs.registerOnSharedPreferenceChangeListener(this);
        link.addListener(this);
        if (prefs.getBoolean(Prefs.ENABLED, true)) activate();
        PointerTileService.requestUpdate(this);
    }

    @Override
    public void onDestroy() {
        deactivate();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (link != null) link.removeListener(this);
        if (cursor != null) {
            try {
                wm.removeView(cursor);
            } catch (RuntimeException ignored) {
            }
        }
        running = null;
        PointerTileService.requestUpdate(this);
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        running = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        updateScreen();
        px = Math.min(px, screenW - 1);
        py = Math.min(py, screenH - 1);
        placeCursor();
    }

    // ---- Activation ----

    private void activate() {
        if (active) return;
        active = true;
        tracker.reset();
        engine.setConfig(Prefs.engineConfig(this, screenW));
        link.setGrab(prefs.getBoolean(Prefs.GRAB, false));
        link.setOpen(OWNER, true);
        setCursorVisible(true);
    }

    private void deactivate() {
        if (!active) return;
        active = false;
        engine.reset();
        injector.endStream();
        link.setGrab(false);
        link.setOpen(OWNER, false);
        setCursorVisible(false);
    }

    private void toggle() {
        boolean on = !prefs.getBoolean(Prefs.ENABLED, true);
        prefs.edit().putBoolean(Prefs.ENABLED, on).apply();
        Toast.makeText(this, on ? R.string.pointer_on : R.string.pointer_off, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (key == null) return;
        switch (key) {
            case Prefs.ENABLED:
                if (p.getBoolean(Prefs.ENABLED, true)) activate();
                else deactivate();
                PointerTileService.requestUpdate(this);
                break;
            case Prefs.GRAB:
                if (active) link.setGrab(p.getBoolean(Prefs.GRAB, false));
                break;
            case Prefs.CURSOR_SIZE:
                cursor.setScale(p.getInt(Prefs.CURSOR_SIZE, 10) / 10f);
                placeCursor();
                break;
            case Prefs.MODE:
            case Prefs.DEVICE:
                if (active) link.restart();
                break;
            default:
                engine.setConfig(Prefs.engineConfig(this, screenW));
        }
    }

    // ---- PadLink ----

    @Override
    public void onEvents(int[] ev, int count) {
        if (!active) return;
        for (int i = 0; i < count; i++) tracker.onEvent(ev[i * 3], ev[i * 3 + 1], ev[i * 3 + 2]);
    }

    @Override
    public void onSlot(int slot) {
        tracker.setSlot(slot);
    }

    @Override
    public void onDeviceOpened(int xMin, int xMax, int yMin, int yMax, int slots) {
        tracker.reset();
        SharedPreferences.Editor e = prefs.edit();
        if (prefs.getInt(Prefs.RAW_XMAX, -1) != xMax || prefs.getInt(Prefs.RAW_YMAX, -1) != yMax
                || prefs.getInt(Prefs.RAW_XMIN, -1) != xMin || prefs.getInt(Prefs.RAW_YMIN, -1) != yMin) {
            e.putInt(Prefs.RAW_XMIN, xMin).putInt(Prefs.RAW_XMAX, xMax)
                    .putInt(Prefs.RAW_YMIN, yMin).putInt(Prefs.RAW_YMAX, yMax).apply();
        }
        engine.setConfig(Prefs.engineConfig(this, screenW));
    }

    // ---- Keys ----

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int toggleKey = prefs.getInt(Prefs.TOGGLE_KEY, 0);
        if (toggleKey != 0 && event.getKeyCode() == toggleKey) {
            int want = prefs.getInt(Prefs.TOGGLE_META, 0);
            int meta = event.getMetaState() & (KeyEvent.META_ALT_ON | KeyEvent.META_SHIFT_ON | KeyEvent.META_CTRL_ON
                    | KeyEvent.META_SYM_ON);
            if (normalizeMeta(meta) == normalizeMeta(want)) {
                if (event.getAction() == KeyEvent.ACTION_UP) toggle();
                return true;
            }
        }
        if (active && event.getAction() == KeyEvent.ACTION_DOWN && !isNavKey(event.getKeyCode())) {
            engine.onKeyActivity();
        }
        return false;
    }

    static int normalizeMeta(int m) {
        int r = 0;
        if ((m & KeyEvent.META_ALT_ON) != 0) r |= KeyEvent.META_ALT_ON;
        if ((m & KeyEvent.META_SHIFT_ON) != 0) r |= KeyEvent.META_SHIFT_ON;
        if ((m & KeyEvent.META_CTRL_ON) != 0) r |= KeyEvent.META_CTRL_ON;
        if ((m & KeyEvent.META_SYM_ON) != 0) r |= KeyEvent.META_SYM_ON;
        return r;
    }

    private static boolean isNavKey(int k) {
        return k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_POWER
                || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_HOME || k == KeyEvent.KEYCODE_APP_SWITCH;
    }

    // ---- Gesture actions ----

    @Override
    public void onPointerMove(float dx, float dy) {
        px = clamp(px + dx, 0, screenW - 1);
        py = clamp(py + dy, 0, screenH - 1);
        setCursorVisible(true);
        placeCursor();
        if (dragging) injector.streamTo(px, py);
    }

    @Override
    public void onClick() {
        setCursorVisible(true);
        flash();
        injector.tap(px, py);
    }

    @Override
    public void onSecondaryClick() {
        setCursorVisible(true);
        flash();
        injector.longPress(px, py);
    }

    @Override
    public void onTertiaryTap() {
        switch (prefs.getString(Prefs.THREE_FINGER, "back")) {
            case "back":
                performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case "home":
                performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case "recents":
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            case "notifications":
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                break;
            default:
                break;
        }
    }

    @Override
    public void onScrollBegin() {
        setCursorVisible(true);
        cursor.setState(CursorView.STATE_SCROLL);
        scrollX = px;
        scrollY = py;
        injector.beginStream(scrollX, scrollY);
    }

    @Override
    public void onScroll(float dx, float dy) {
        scrollX += dx;
        scrollY += dy;
        if (!injector.streamTo(scrollX, scrollY)) {
            // The injected finger hit the screen edge: lift it and start again from the pointer.
            injector.endStream();
            scrollX = px;
            scrollY = py;
            injector.beginStream(scrollX, scrollY);
        }
        keepCursor();
    }

    @Override
    public void onScrollEnd() {
        injector.endStream();
        cursor.setState(CursorView.STATE_NORMAL);
    }

    @Override
    public void onDragBegin() {
        dragging = true;
        cursor.setState(CursorView.STATE_PRESSED);
        injector.beginStream(px, py);
    }

    @Override
    public void onDragEnd() {
        dragging = false;
        injector.endStream();
        cursor.setState(CursorView.STATE_NORMAL);
    }

    // ---- Cursor overlay ----

    private void createCursor() {
        cursor = new CursorView(this);
        cursor.setScale(prefs.getInt(Prefs.CURSOR_SIZE, 10) / 10f);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        cursor.setVisibility(View.GONE);
        placeCursorParams();
        wm.addView(cursor, lp);
    }

    private void placeCursorParams() {
        lp.x = Math.round(px) - cursor.hotspotPx();
        lp.y = Math.round(py) - cursor.hotspotPx();
    }

    private void placeCursor() {
        if (cursor == null || !cursorShown) return;
        placeCursorParams();
        try {
            wm.updateViewLayout(cursor, lp);
        } catch (RuntimeException ignored) {
        }
    }

    private void setCursorVisible(boolean v) {
        if (cursor == null) return;
        v = v && active;
        main.removeCallbacks(autoHide);
        if (v != cursorShown) {
            cursorShown = v;
            cursor.setVisibility(v ? View.VISIBLE : View.GONE);
            if (v) placeCursor();
        }
        if (v) keepCursor();
    }

    /** Restart the auto-hide countdown. */
    private void keepCursor() {
        main.removeCallbacks(autoHide);
        int s = prefs.getInt(Prefs.AUTOHIDE_S, 5);
        if (s > 0 && active) main.postDelayed(autoHide, s * 1000L);
    }

    private void flash() {
        cursor.setState(CursorView.STATE_PRESSED);
        main.postDelayed(() -> {
            if (!dragging && !injector.isStreaming()) cursor.setState(CursorView.STATE_NORMAL);
        }, 150);
    }

    private void updateScreen() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        if (injector != null) injector.setScreenSize(screenW, screenH);
        if (engine != null) engine.setConfig(Prefs.engineConfig(this, screenW));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
