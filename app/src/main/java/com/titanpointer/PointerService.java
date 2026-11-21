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
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

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
    private ClickTrace trace;
    private GestureEngine engine;
    private TouchTracker tracker;

    private boolean active = false;
    private boolean cursorShown = false;
    private float px, py; // pointer position in screen pixels
    private int screenW, screenH;
    private float scrollX, scrollY; // injected finger position during a scroll
    private boolean dragging = false;
    private boolean toggleDown = false;

    // No-root mode: an invisible focused window receives the keyboard's touchpad events.
    private View catcher;
    private boolean usingCatcher = false;
    private int catcherDevice = -1;
    private final List<TouchTracker.Contact> contacts = new ArrayList<>();

    private final Runnable autoHide = () -> setCursorVisible(false);

    @Override
    protected void onServiceConnected() {
        if (cursor != null) teardown(); // connected again: don't leave the old cursor/windows behind
        running = this;
        prefs = Prefs.get(this);
        link = PadLink.get(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        injector = new GestureInjector(this);
        trace = new ClickTrace(this, main);
        trace.enableWindowAccess();
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
        Diag.setEnabled(prefs.getBoolean(Prefs.DIAG, false));
        prefs.registerOnSharedPreferenceChangeListener(this);
        link.addListener(this);
        repairAssist();
        if (prefs.getBoolean(Prefs.ENABLED, true)) activate();
        PointerTileService.requestUpdate(this);
        startKeepAlive();
    }

    /**
     * Run as a foreground service. Swiping the app away from Recents kills every process of the app
     * that has no foreground service, and Android 10 doesn't restart an accessibility service whose
     * process died until it's switched on again. The notification uses the quietest channel and is hidden from the lock screen.
     */
    private void startKeepAlive() {
        try {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            android.app.NotificationChannel ch = new android.app.NotificationChannel(KEEPALIVE_CHANNEL,
                    getString(R.string.keepalive_channel), android.app.NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_SECRET);
            nm.deleteNotificationChannel("keepalive"); // v1.4-1.7 channel: settings can't change once created
            nm.createNotificationChannel(ch);
            android.content.Intent open = new android.content.Intent(this, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.Notification n = new android.app.Notification.Builder(this, KEEPALIVE_CHANNEL)
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle(getString(R.string.keepalive_title))
                    .setContentText(getString(R.string.keepalive_text))
                    .setContentIntent(android.app.PendingIntent.getActivity(this, 0, open,
                            android.app.PendingIntent.FLAG_IMMUTABLE))
                    .setOngoing(true)
                    .setVisibility(android.app.Notification.VISIBILITY_SECRET) // never on the lock screen
                    .build();
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(KEEPALIVE_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(KEEPALIVE_ID, n);
            }
        } catch (RuntimeException e) {
            android.util.Log.w("TitanPointer", "foreground failed: " + e);
        }
    }

    private static final String KEEPALIVE_CHANNEL = "keepalive_hidden";
    private static final int KEEPALIVE_ID = 1;

    @Override
    public void onDestroy() {
        teardown();
        running = null;
        PointerTileService.requestUpdate(this);
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        teardown();
        running = null;
        return super.onUnbind(intent);
    }

    /** Remove every window and stop all input handling. Safe to call more than once. */
    private void teardown() {
        deactivate();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (link != null) link.removeListener(this);
        removeCursorWindow();
        cursor = null;
        try {
            stopForeground(true);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (trace != null) trace.onEvent(event);
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String w = event.getPackageName() + "/" + event.getClassName();
        if (w.equals(foreground)) return;
        foreground = w;
        if (active) Diag.log("window: " + w);
    }

    /** Last window reported by accessibility events, for the diagnostics log. */
    private String foreground = "?";

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

    static boolean isNoRoot(SharedPreferences p) {
        return Prefs.MODE_NOROOT.equals(p.getString(Prefs.MODE, Prefs.MODE_NOROOT));
    }

    private void activate() {
        if (active) return;
        active = true;
        Diag.log("pointer mode ON (" + (isNoRoot(prefs) ? "no root" : "helper") + "), foreground: " + foreground);
        assistFollowPointer(true); // the pointer needs the scroll assistant to get keyboard touches
        tracker.reset();
        engine.setScrollLock(false);
        engine.setConfig(Prefs.engineConfig(this, screenW));
        injector.setEnabled(true);
        addCursorWindow();
        if (isNoRoot(prefs)) {
            pushImeAway();
            addCatcher();
        } else {
            link.setGrab(prefs.getBoolean(Prefs.GRAB, false));
            link.setOpen(OWNER, true);
        }
        setCursorVisible(true);
    }

    private void deactivate() {
        if (!active) return;
        active = false;
        Diag.log("pointer mode OFF");
        engine.reset();
        engine.setScrollLock(false);
        dragging = false;
        // Drop queued taps and lift any injected finger; nothing new is injected until reactivated.
        injector.setEnabled(false);
        removeCatcher();
        restoreIme();
        link.setGrab(false);
        link.setOpen(OWNER, false);
        main.removeCallbacks(autoHide);
        main.removeCallbacks(liftScroll);
        main.removeCallbacks(delayedPadClick);
        cancelScrollWait();
        main.removeCallbacks(timerKeyClick);
        assistFollowPointer(false);
        trace.reset();
        scrollHeld = false;
        if (cursor != null) cursor.setState(CursorView.STATE_NORMAL);
        setCursorVisible(false);
        removeCursorWindow(); // no window at all while pointer mode is off
    }

    /** Turn pointer mode on or off right away, and save it. */
    private void setPointerMode(boolean on) {
        if (on) activate();
        else deactivate();
        if (prefs.getBoolean(Prefs.ENABLED, true) != on) prefs.edit().putBoolean(Prefs.ENABLED, on).apply();
        PointerTileService.requestUpdate(this);
    }

    // ---- No-root input: focused invisible window ----

    private void addCatcher() {
        if (usingCatcher) return;
        catcher = new View(this) {
            @Override
            public boolean onGenericMotionEvent(MotionEvent e) {
                if (stale()) return false;
                if (e.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
                    Diag.logEvery("pad", 2000, "touchpad event reached the focus window");
                    onTouchpad(e);
                    return true;
                }
                Diag.logEvery("motion" + e.getSource(), 2000, "other motion event: source=0x"
                        + Integer.toHexString(e.getSource()) + " device=" + deviceName(e.getDevice()));
                return super.onGenericMotionEvent(e);
            }

            @Override
            public void onWindowFocusChanged(boolean hasFocus) {
                super.onWindowFocusChanged(hasFocus);
                if (stale()) return;
                onCatcherFocus(hasFocus);
            }

            @Override
            public boolean onKeyDown(int keyCode, KeyEvent event) {
                if (stale()) return false;
                return !isSystemKey(keyCode); // let volume etc. fall back to the system
            }

            @Override
            public boolean onKeyUp(int keyCode, KeyEvent event) {
                if (stale()) return false;
                return !isSystemKey(keyCode);
            }

            /** A catcher that outlived pointer mode removes itself instead of eating input. */
            private boolean stale() {
                if (active && catcher == this) return false;
                try {
                    wm.removeViewImmediate(this);
                } catch (RuntimeException ignored) {
                }
                return true;
            }
        };
        catcher.setFocusable(true);
        catcher.setFocusableInTouchMode(true);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSPARENT);
        p.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(catcher, p);
            usingCatcher = true;
            catcher.requestFocus();
            Diag.log("focus window added");
        } catch (RuntimeException e) {
            catcher = null;
            Diag.log("focus window could not be added: " + e);
            Toast.makeText(this, "TitanPointer could not take keyboard focus", Toast.LENGTH_LONG).show();
        }
    }

    // ---- Keyboard app (input method) ----

    /** The text field we took focus from when pointer mode came on; focused again when it goes off. */
    private android.view.accessibility.AccessibilityNodeInfo clearedField;

    /**
     * While a keyboard app (e.g. BlackBerry Keyboard) is up for a text field, the Titan sends keyboard
     * touches to it instead of to our focus window (seen in the diagnostics log). Hide it and take focus
     * off the text field while pointer mode is on.
     */
    private void pushImeAway() {
        try {
            boolean ok = getSoftKeyboardController().setShowMode(SHOW_MODE_HIDDEN);
            Diag.log("keyboard app hidden for pointer mode: " + (ok ? "ok" : "refused"));
        } catch (RuntimeException e) {
            Diag.log("hiding keyboard app failed: " + e);
        }
        clearedField = null;
        try {
            android.view.accessibility.AccessibilityNodeInfo f =
                    findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) {
                boolean ok = f.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_FOCUS);
                Diag.log("text field focus cleared (" + f.getPackageName() + "): " + (ok ? "ok" : "refused"));
                if (ok) clearedField = f;
            } else {
                Diag.log("no text field focused" + (f == null ? "" : " (focus: " + f.getClassName() + ")"));
            }
        } catch (RuntimeException e) {
            Diag.log("clearing text field focus failed: " + e);
        }
    }

    private void restoreIme() {
        try {
            getSoftKeyboardController().setShowMode(SHOW_MODE_AUTO);
        } catch (RuntimeException ignored) {
        }
        android.view.accessibility.AccessibilityNodeInfo f = clearedField;
        clearedField = null;
        if (f == null) return;
        try {
            android.view.accessibility.AccessibilityNodeInfo now =
                    findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            if (now != null && now.isEditable()) return; // you clicked into a field meanwhile: leave it
            boolean ok = f.refresh() && f.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS);
            Diag.log("text field focus restored: " + (ok ? "ok" : "failed"));
        } catch (RuntimeException e) {
            Diag.log("restoring text field focus failed: " + e);
        }
    }

    // ---- Focus recovery ----

    private boolean catcherFocused = false;
    private long regainWindowStart = 0;
    private int regainAttempts = 0;
    private static final int MAX_REGAIN = 3;
    private static final long REGAIN_WINDOW_MS = 10000, REGAIN_DELAY_MS = 400;

    private final Runnable regainFocus = () -> {
        if (!active || !usingCatcher || catcherFocused) return;
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) {
            Diag.log("screen locked: not taking focus back");
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - regainWindowStart > REGAIN_WINDOW_MS) {
            regainWindowStart = now;
            regainAttempts = 0;
        }
        if (regainAttempts >= MAX_REGAIN) {
            if (regainAttempts++ > MAX_REGAIN) return; // already told the user
            Diag.log("giving up on getting focus back (another window keeps it): " + foreground);
            Toast.makeText(this, R.string.focus_lost, Toast.LENGTH_LONG).show();
            return;
        }
        regainAttempts++;
        Diag.log("re-adding focus window to get focus back (attempt " + regainAttempts + ")");
        removeCatcher();
        addCatcher();
    };

    private void onCatcherFocus(boolean has) {
        catcherFocused = has;
        Diag.log(has ? "focus window HAS keyboard focus" : "focus window LOST keyboard focus (foreground: " + foreground + ")");
        if (cursor != null) cursor.setDimmed(!has);
        main.removeCallbacks(regainFocus);
        if (!has && active) main.postDelayed(regainFocus, REGAIN_DELAY_MS);
    }

    private static String deviceName(InputDevice d) {
        return d == null ? "?" : d.getName();
    }

    private void removeCatcher() {
        if (!usingCatcher) return;
        usingCatcher = false;
        catcherFocused = false;
        main.removeCallbacks(regainFocus);
        if (cursor != null) cursor.setDimmed(false);
        try {
            wm.removeViewImmediate(catcher);
        } catch (RuntimeException ignored) {
        }
        catcher = null;
        contacts.clear();
        catcherDevice = -1;
    }

    /** Touchpad MotionEvent (raw device units) -> contacts for the gesture engine. */
    private void onTouchpad(MotionEvent e) {
        if (!active) return;
        if (e.getDeviceId() != catcherDevice) {
            catcherDevice = e.getDeviceId();
            InputDevice d = e.getDevice();
            InputDevice.MotionRange rx = d == null ? null : d.getMotionRange(MotionEvent.AXIS_X, e.getSource());
            InputDevice.MotionRange ry = d == null ? null : d.getMotionRange(MotionEvent.AXIS_Y, e.getSource());
            if (rx != null && ry != null) {
                prefs.edit().putString(Prefs.DEVICE_NAME, d.getName()).apply();
                onDeviceOpened((int) rx.getMin(), (int) rx.getMax(), (int) ry.getMin(), (int) ry.getMax(), 1);
            }
        }
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            trace.padDown(Math.round(e.getX()), Math.round(e.getY()), focusWindowTouchMode());
        } else if (action == MotionEvent.ACTION_MOVE) {
            trace.padMove(Math.round(e.getX()), Math.round(e.getY()));
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            trace.padUp(focusWindowTouchMode());
        }
        contacts.clear();
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            int skip = action == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (i == skip) continue;
                contacts.add(new TouchTracker.Contact(e.getPointerId(i), Math.round(e.getX(i)), Math.round(e.getY(i))));
            }
        }
        engine.onFrame(contacts);
    }

    /** Keys while the no-root focus window is active. Returns true if consumed. */
    private boolean onPointerModeKey(KeyEvent event) {
        int k = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (KeyEvent.isModifierKey(k)) return false; // let Alt/Shift/Sym through for combinations
        // Long press first: with the defaults it's the click key plus Alt.
        if (matches(event, Prefs.LONG_KEY, Prefs.DEFAULT_LONG_KEY, Prefs.LONG_META, Prefs.DEFAULT_LONG_META)) {
            if (down && event.getRepeatCount() == 0) onLongPress();
            return true;
        }
        if (matches(event, Prefs.CLICK_KEY, Prefs.DEFAULT_CLICK_KEY, Prefs.CLICK_META, Prefs.DEFAULT_CLICK_META)) {
            if (down && event.getRepeatCount() == 0) {
                // Test: leave Android's key callback first and click from the main thread's queue, the
                // way a keyboard tap's click is sent. No added delay.
                if (prefs.getBoolean(Prefs.KEY_TIMER_TEST, false)) main.post(timerKeyClick);
                else click("click key", false);
            }
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_SPACE:
                if (down && event.getRepeatCount() == 0) {
                    boolean on = !engine.isScrollLock();
                    engine.setScrollLock(on);
                    if (!on && scrollHeld) liftScroll.run();
                    main.removeCallbacks(liftScroll);
                    cursor.setState(on ? CursorView.STATE_SCROLL : CursorView.STATE_NORMAL);
                    setCursorVisible(true);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                // Our focus window would swallow the keyboard's Back key, so pass it on as a normal Back.
                if (!down) performGlobalAction(GLOBAL_ACTION_BACK);
                return true;
            default:
                if (isSystemKey(k)) return false;
                // Other keys can't reach the app underneath. By default they do nothing; optionally
                // a key press leaves pointer mode (that key press is used up).
                if (down && event.getRepeatCount() == 0
                        && Prefs.LETTERS_EXIT.equals(prefs.getString(Prefs.LETTERS, Prefs.LETTERS_NOTHING))) {
                    setPointerMode(false);
                    Toast.makeText(this, R.string.typing_mode, Toast.LENGTH_SHORT).show();
                }
                return true;
        }
    }

    private void toggle() {
        boolean on = !active;
        setPointerMode(on);
        Toast.makeText(this, on ? R.string.pointer_on : R.string.pointer_off, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (key == null) return;
        switch (key) {
            case Prefs.DIAG:
                Diag.setEnabled(p.getBoolean(Prefs.DIAG, false));
                break;
            case Prefs.ENABLED: // from the app's switch or the Quick Settings tile
                if (p.getBoolean(Prefs.ENABLED, true)) activate();
                else deactivate();
                PointerTileService.requestUpdate(this);
                break;
            case Prefs.ASSIST_FOLLOWS_POINTER:
                if (!ScrollAssist.canWrite(this)) break;
                // Turned on: match pointer mode now. Turned off: leave the keyboard scrolling on, as before.
                boolean follow = p.getBoolean(Prefs.ASSIST_FOLLOWS_POINTER, false);
                boolean want = !follow || active;
                if (ScrollAssist.isOn(this) != want) {
                    boolean ok = ScrollAssist.set(this, want);
                    Diag.log("scroll assistant " + (want ? "on" : "off") + " (setting changed)" + (ok ? "" : " FAILED"));
                }
                break;
            case Prefs.ASSIST_OFF_FOR_CLICK:
            case Prefs.ELEMENT_CLICK:
            case Prefs.UI_TAB:
                break;
            case Prefs.GRAB:
                if (active) link.setGrab(p.getBoolean(Prefs.GRAB, false));
                break;
            case Prefs.CURSOR_SIZE:
            case Prefs.CURSOR_STYLE:
            case Prefs.CURSOR_COLOR:
            case Prefs.CURSOR_HOTSPOT:
            case Prefs.CURSOR_IMAGE_VER:
            case Prefs.CURSOR_MIRROR:
            case Prefs.CURSOR_INVERT:
                cursor.loadLook(this, p);
                lp.x = Integer.MIN_VALUE; // the hotspot may have moved: force a relayout
                placeCursor();
                break;
            case Prefs.MODE:
                if (active) {
                    deactivate();
                    activate();
                }
                break;
            case Prefs.DEVICE:
                if (active && !isNoRoot(p)) link.restart();
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
        if (active && event.getAction() == KeyEvent.ACTION_DOWN) {
            Diag.logEvery("key" + event.getKeyCode(), 1000, "key " + KeyEvent.keyCodeToString(event.getKeyCode())
                    + " meta=0x" + Integer.toHexString(event.getMetaState())
                    + " from " + deviceName(event.getDevice()) + (usingCatcher && !catcherFocused ? " (no focus)" : ""));
        }
        int toggleKey = prefs.getInt(Prefs.TOGGLE_KEY, Prefs.DEFAULT_TOGGLE_KEY);
        if (toggleKey != 0 && event.getKeyCode() == toggleKey) {
            int want = prefs.getInt(Prefs.TOGGLE_META, Prefs.DEFAULT_TOGGLE_META);
            int meta = event.getMetaState() & (KeyEvent.META_ALT_ON | KeyEvent.META_SHIFT_ON | KeyEvent.META_CTRL_ON
                    | KeyEvent.META_SYM_ON);
            if (event.getAction() == KeyEvent.ACTION_DOWN && normalizeMeta(meta) == normalizeMeta(want)) {
                if (event.getRepeatCount() == 0) {
                    toggleDown = true;
                    toggle();
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && toggleDown) {
                toggleDown = false; // Alt may already be released by now
                return true;
            }
        }
        if (active && event.getAction() == KeyEvent.ACTION_DOWN) {
            // Every key (Back, Recents, Shift, Alt, letters) sits on the touch surface: the touch that
            // came just before this press must not click or move.
            engine.onKeyActivity();
        }
        if (active && usingCatcher) return onPointerModeKey(event);
        if (active && event.getAction() == KeyEvent.ACTION_DOWN && !isNavKey(event.getKeyCode())) {
            engine.onKeyActivity();
        }
        return false;
    }

    /** True if the event is the key + modifiers saved under the given preferences. */
    private boolean matches(KeyEvent e, String keyPref, int defKey, String metaPref, int defMeta) {
        int key = prefs.getInt(keyPref, defKey);
        if (key == 0 || e.getKeyCode() != key) return false;
        return normalizeMeta(e.getMetaState()) == normalizeMeta(prefs.getInt(metaPref, defMeta));
    }

    static int normalizeMeta(int m) {
        int r = 0;
        if ((m & KeyEvent.META_ALT_ON) != 0) r |= KeyEvent.META_ALT_ON;
        if ((m & KeyEvent.META_SHIFT_ON) != 0) r |= KeyEvent.META_SHIFT_ON;
        if ((m & KeyEvent.META_CTRL_ON) != 0) r |= KeyEvent.META_CTRL_ON;
        if ((m & KeyEvent.META_SYM_ON) != 0) r |= KeyEvent.META_SYM_ON;
        return r;
    }

    private static boolean isSystemKey(int k) {
        return k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE
                || k == KeyEvent.KEYCODE_POWER || k == KeyEvent.KEYCODE_HOME || k == KeyEvent.KEYCODE_APP_SWITCH
                || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY
                || k == KeyEvent.KEYCODE_MEDIA_PAUSE || k == KeyEvent.KEYCODE_MEDIA_NEXT
                || k == KeyEvent.KEYCODE_MEDIA_PREVIOUS || k == KeyEvent.KEYCODE_HEADSETHOOK
                || k == KeyEvent.KEYCODE_CAMERA || k == KeyEvent.KEYCODE_CALL || k == KeyEvent.KEYCODE_ENDCALL;
    }

    private static boolean isNavKey(int k) {
        return k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_POWER
                || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_HOME || k == KeyEvent.KEYCODE_APP_SWITCH;
    }

    // ---- Gesture actions ----

    @Override
    public void onPointerMove(float dx, float dy) {
        if (!active) return;
        px = clamp(px + dx, 0, screenW - 1);
        py = clamp(py + dy, 0, screenH - 1);
        setCursorVisible(true);
        placeCursor();
        if (dragging) injector.streamTo(px, py);
    }

    /** Click from the touchpad (tap). */
    @Override
    public void onClick() {
        if (!active) return;
        if (prefs.getBoolean(Prefs.TAP_DELAY_TEST, false)) {
            main.postDelayed(delayedPadClick, 1000);
            return;
        }
        clickWhenTitanScrollOff("tap on keyboard");
    }

    // ---- The Titan's keyboard-scroll switch ----
    // The Titan's scroll assistant (needed: without it the keyboard touches don't reach us) sets the system
    // property debug.sf.forcescroll_view to "yes" when the keyboard surface is touched and back to "no" on a
    // key press. While it is "yes", the phone's modified View.onTouchEvent refuses taps on standard buttons,
    // so a keyboard-tap click is held until it reads "no". Apps can read but not set the property.

    private static final String TITAN_SCROLL_PROP = "debug.sf.forcescroll_view";
    private static final long SCROLL_WAIT_POLL_MS = 20, SCROLL_WAIT_MAX_MS = 3000;
    private Runnable pendingScrollWait;

    static String titanScrollProp() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class).invoke(null, TITAN_SCROLL_PROP, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private void clickWhenTitanScrollOff(final String source) {
        // 1.19 held the click until the switch read "no", but it never resets without a key press, so
        // taps did nothing. Click right away (the log still records the switch's value).
        click(source, true);
    }

    @SuppressWarnings("unused")
    private void clickWhenTitanScrollOffHeld(final String source) {
        cancelScrollWait();
        if (!"yes".equals(titanScrollProp())) {
            click(source, true);
            return;
        }
        final float x = px, y = py;
        final long start = SystemClock.uptimeMillis();
        Diag.log("keyboard tap click held: " + TITAN_SCROLL_PROP + " is yes");
        setCursorVisible(true);
        flash();
        pendingScrollWait = new Runnable() {
            @Override
            public void run() {
                long waited = SystemClock.uptimeMillis() - start;
                boolean off = !"yes".equals(titanScrollProp());
                if (!off && waited < SCROLL_WAIT_MAX_MS) {
                    main.postDelayed(this, SCROLL_WAIT_POLL_MS);
                    return;
                }
                pendingScrollWait = null;
                Diag.log(off ? TITAN_SCROLL_PROP + " went back to no after " + waited + " ms: clicking"
                        : TITAN_SCROLL_PROP + " still yes after " + waited + " ms: clicking anyway");
                clickAt(source + (off ? ", waited " + waited + " ms" : ", scroll switch stayed on"), true, x, y);
            }
        };
        main.postDelayed(pendingScrollWait, SCROLL_WAIT_POLL_MS);
    }

    private void cancelScrollWait() {
        if (pendingScrollWait != null) main.removeCallbacks(pendingScrollWait);
        pendingScrollWait = null;
    }

    /** Android's touch mode as our focus window sees it (diagnostics). */
    private boolean focusWindowTouchMode() {
        View c = catcher;
        return c != null && c.isInTouchMode();
    }

    private final Runnable delayedPadClick = () -> clickWhenTitanScrollOff("tap on keyboard, delayed 1 s for testing");
    private final Runnable timerKeyClick = () -> click("click key, sent from a timer for testing", false);

    private void click(String source, boolean fromPad) {
        clickAt(source, fromPad, px, py);
    }

    private void clickAt(String source, boolean fromPad, float x, float y) {
        if (!active) return;
        setCursorVisible(true);
        flash();
        if (fromPad && elementClick(source, x, y)) return;
        sendTap(source, fromPad, x, y);
    }

    private void sendTap(String source, boolean fromPad, float x, float y) {
        final int n = trace.click(source, fromPad, x, y, focusWindowTouchMode());
        if (Diag.isEnabled()) Diag.log("  " + TITAN_SCROLL_PROP + " = " + titanScrollProp());
        injector.tap(x, y, Diag.isEnabled() ? what -> trace.tapResult(n, what) : null);
    }

    // ---- Element click ----
    // The Titan's input reader sets debug.sf.forcescroll_view=yes on every keyboard-sensor event and =no only on
    // events from another hardware device (keys, the screen). Injected taps never pass the reader, so after a
    // keyboard tap the switch is always yes and the phone's View.onTouchEvent refuses the tap on standard Views.
    // So while it's yes, a standard View under the pointer is clicked through accessibility instead.

    /** Returns true if the click was handled by clicking the element under the pointer. */
    private boolean elementClick(String source, float x, float y) {
        if (!prefs.getBoolean(Prefs.ELEMENT_CLICK, true) || !"yes".equals(titanScrollProp())) return false;
        android.view.accessibility.AccessibilityNodeInfo n = ElementClick.find(this, Math.round(x), Math.round(y));
        if (n == null) {
            if (Diag.isEnabled()) Diag.log("element click: nothing standard to click at the pointer, tapping");
            return false;
        }
        boolean ok = ElementClick.click(n);
        Diag.log("element click (" + source + "): " + ElementClick.describe(n) + (ok ? "" : " REFUSED, tapping"));
        return ok;
    }

    /** 1.23's click fix left the scroll assistant off (the app died mid-click): turn it back on. */
    private void repairAssist() {
        if (!prefs.getBoolean(Prefs.ASSIST_OFF_FOR_CLICK, false)) return;
        boolean ok = ScrollAssist.set(this, true);
        prefs.edit().putBoolean(Prefs.ASSIST_OFF_FOR_CLICK, false).commit();
        Diag.log("scroll assistant was left off by an interrupted click: turned back on" + (ok ? "" : " FAILED"));
    }

    /** "Scroll assistant off when pointer mode is off": follow pointer mode. */
    private void assistFollowPointer(boolean pointerOn) {
        if (pointerOn && !ScrollAssist.isOn(this) && isNoRoot(prefs)) {
            // Without the assistant the keyboard touches never reach the focus window: turn it on, or say so.
            if (ScrollAssist.set(this, true)) {
                Diag.log("scroll assistant was off: turned on for pointer mode");
            } else {
                Diag.log("scroll assistant is off and can't be switched: pointer gets no keyboard touches");
                Toast.makeText(this, R.string.assist_off_warning, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (!prefs.getBoolean(Prefs.ASSIST_FOLLOWS_POINTER, false) || !ScrollAssist.canWrite(this)) return;
        if (ScrollAssist.isOn(this) == pointerOn) return;
        boolean ok = ScrollAssist.set(this, pointerOn);
        Diag.log("scroll assistant " + (pointerOn ? "on" : "off") + " with pointer mode" + (ok ? "" : " FAILED"));
    }

    private void onLongPress() {
        if (!active) return;
        setCursorVisible(true);
        flash();
        injector.longPress(px, py);
    }

    @Override
    public void onScrollBegin() {
        if (!active) return;
        setCursorVisible(true);
        cursor.setState(CursorView.STATE_SCROLL);
        if (scrollHeld) {
            // A new swipe soon after the last one: keep the same injected finger down. Lifting and
            // pressing again in quick succession looks like a double tap and zooms many apps.
            main.removeCallbacks(liftScroll);
            scrollHeld = false;
            if (injector.isStreaming()) return;
        }
        scrollX = px;
        scrollY = py;
        injector.beginStream(scrollX, scrollY);
    }

    /** After a swipe ends, the injected finger stays down this long in case another swipe follows. */
    private static final long SCROLL_HOLD_MS = 300;
    private boolean scrollHeld = false;
    private final Runnable liftScroll = () -> {
        scrollHeld = false;
        injector.endStream();
    };

    @Override
    public void onScroll(float dx, float dy) {
        if (!active) return;
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
    public void onScrollEnd(float vx, float vy) {
        if (cursor != null) cursor.setState(engine.isScrollLock() ? CursorView.STATE_SCROLL : CursorView.STATE_NORMAL);
        if (active && engine.isScrollLock() && (vx != 0 || vy != 0) && injector.isStreaming()) {
            // A flick: lift the injected finger while it's still moving so the app flings. The 300 ms hold
            // below would stop it dead. A lift after a fast move is never taken as a tap, so the next
            // swipe can't make a double tap (the zoom the hold prevents).
            main.removeCallbacks(liftScroll);
            scrollHeld = false;
            Diag.log(String.format(java.util.Locale.US, "scroll fling %.0f, %.0f px/s", vx, vy));
            injector.flingEnd(vx, vy);
            return;
        }
        if (!active || !engine.isScrollLock()) {
            main.removeCallbacks(liftScroll);
            scrollHeld = false;
            injector.endStream();
            return;
        }
        scrollHeld = true;
        main.removeCallbacks(liftScroll);
        main.postDelayed(liftScroll, SCROLL_HOLD_MS);
    }

    @Override
    public void onDragBegin() {
        if (!active) return;
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
        cursor.loadLook(this, prefs);
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
    }

    private boolean cursorAttached = false;

    private void addCursorWindow() {
        if (cursor == null || cursorAttached) return;
        placeCursorParams();
        try {
            wm.addView(cursor, lp);
            cursorAttached = true;
        } catch (RuntimeException ignored) {
        }
    }

    private void removeCursorWindow() {
        if (cursor == null || !cursorAttached) return;
        cursorAttached = false;
        cursorShown = false;
        cursor.setVisibility(View.GONE);
        try {
            wm.removeViewImmediate(cursor);
        } catch (RuntimeException ignored) {
        }
    }

    private void placeCursorParams() {
        lp.x = Math.round(px) - cursor.hotspotX();
        lp.y = Math.round(py) - cursor.hotspotY();
    }

    private void placeCursor() {
        if (cursor == null || !cursorShown || !cursorAttached) return;
        int oldX = lp.x, oldY = lp.y;
        placeCursorParams();
        // lp always holds the position last given to the window manager (addView or updateViewLayout),
        // so an unchanged position needs no relayout. Taps on the keyboard report many zero-length
        // moves; an Enter click relayouts nothing, and keyboard taps now don't either.
        if (lp.x == oldX && lp.y == oldY) return;
        try {
            wm.updateViewLayout(cursor, lp);
        } catch (RuntimeException ignored) {
        }
    }

    private void setCursorVisible(boolean v) {
        if (cursor == null) return;
        v = v && active && cursorAttached;
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
            if (cursor != null && !dragging && !injector.isStreaming()) cursor.setState(CursorView.STATE_NORMAL);
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
