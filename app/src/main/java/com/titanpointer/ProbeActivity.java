package com.titanpointer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * No-root probe: listens on every path Android offers a normal app (touch, generic motion, hover,
 * pointer capture) and draws whatever arrives, to find out how the Titan's touch keyboard reports
 * itself to apps. Results can be copied and shared.
 */
public final class ProbeActivity extends Activity {
    private static final int MAX_LOG = 3000;

    private PadView pad;
    private TextView logView, status;
    private Button captureButton;
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean logDirty = false;
    private long t0 = SystemClock.uptimeMillis();

    private final Map<String, Integer> pathCounts = new HashMap<>();
    private final TreeSet<String> sourcesSeen = new TreeSet<>();
    private final TreeSet<String> axesSeen = new TreeSet<>();
    private int maxPointers = 0;
    private int keyCount = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (8 * dp);
        root.setPadding(p, p, p, p);

        LinearLayout bar = new LinearLayout(this);
        captureButton = new Button(this);
        captureButton.setText("Pointer capture: off");
        captureButton.setOnClickListener(v -> toggleCapture());
        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setOnClickListener(v -> clearAll());
        Button copy = new Button(this);
        copy.setText("Copy results");
        copy.setOnClickListener(v -> copyResults());
        bar.addView(captureButton);
        bar.addView(clear);
        bar.addView(copy);
        root.addView(bar);

        status = new TextView(this);
        status.setTextSize(13);
        status.setText("Slide on the keyboard without pressing keys. Then turn on pointer capture and try again.");
        root.addView(status);

        pad = new PadView(this);
        root.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView sv = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(10);
        logView.setTextIsSelectable(true);
        sv.addView(logView);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f));
        setContentView(root);

        pad.setFocusable(true);
        pad.setFocusableInTouchMode(true);
        pad.setOnCapturedPointerListener((v, e) -> {
            record("captured", e);
            return true;
        });
        logDevices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        pad.requestFocus();
    }

    // ---- Every input path an ordinary app can see ----

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        // Screen touches on the buttons and log are ordinary UI: don't record them as keyboard input.
        boolean onPad = e.getY() >= pad.getTop() && e.getY() <= pad.getBottom();
        if (onPad || !e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) record("touch", e);
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        record("generic", e);
        super.dispatchGenericMotionEvent(e);
        return true; // consume, so nothing scrolls away under us
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            keyCount++;
            add(String.format(Locale.US, "key      %s dev=%s", KeyEvent.keyCodeToString(e.getKeyCode()), devName(e.getDeviceId())));
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && pad.hasPointerCapture()) {
            pad.releasePointerCapture();
            updateCaptureButton();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        updateCaptureButton();
    }

    private void toggleCapture() {
        if (pad.hasPointerCapture()) {
            pad.releasePointerCapture();
        } else {
            pad.requestFocus();
            pad.requestPointerCapture();
        }
        main.postDelayed(this::updateCaptureButton, 150);
        add("--- pointer capture " + (pad.hasPointerCapture() ? "requested off" : "requested on") + " ---");
    }

    private void updateCaptureButton() {
        captureButton.setText("Pointer capture: " + (pad.hasPointerCapture() ? "ON" : "off"));
    }

    // ---- Recording ----

    private void record(String path, MotionEvent e) {
        pathCounts.put(path, pathCounts.getOrDefault(path, 0) + 1);
        sourcesSeen.add(path + ":" + sourceName(e.getSource()));
        maxPointers = Math.max(maxPointers, e.getPointerCount());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%-8s %-12s src=%s dev=%s n=%d",
                path, MotionEvent.actionToString(e.getActionMasked()), sourceName(e.getSource()),
                devName(e.getDeviceId()), e.getPointerCount()));
        for (int i = 0; i < e.getPointerCount(); i++) {
            sb.append(" | id").append(e.getPointerId(i)).append(" tool=").append(e.getToolType(i));
            for (int axis = 0; axis < 48; axis++) {
                float v = e.getAxisValue(axis, i);
                if (v != 0f) {
                    String name = MotionEvent.axisToString(axis).replace("AXIS_", "");
                    axesSeen.add(name);
                    sb.append(' ').append(name).append('=').append(fmt(v));
                }
            }
        }
        add(sb.toString());
        pad.onEvent(path, e);
        updateStatus();
    }

    private void add(String line) {
        long t = SystemClock.uptimeMillis() - t0;
        log.addLast(String.format(Locale.US, "%6d %s", t, line));
        while (log.size() > MAX_LOG) log.removeFirst();
        if (!logDirty) {
            logDirty = true;
            main.postDelayed(() -> {
                logDirty = false;
                StringBuilder sb = new StringBuilder();
                int skip = Math.max(0, log.size() - 150);
                int i = 0;
                for (String s : log) if (i++ >= skip) sb.append(s).append('\n');
                logView.setText(sb);
            }, 120);
        }
    }

    private void updateStatus() {
        status.setText(String.format(Locale.US, "Paths: %s · max fingers %d · axes: %s · keys %d",
                pathCounts, maxPointers, axesSeen, keyCount));
    }

    private void clearAll() {
        log.clear();
        pathCounts.clear();
        sourcesSeen.clear();
        axesSeen.clear();
        maxPointers = 0;
        keyCount = 0;
        t0 = SystemClock.uptimeMillis();
        pad.clear();
        logView.setText("");
        logDevices();
    }

    private void copyResults() {
        StringBuilder sb = new StringBuilder("TitanPointer input probe\n");
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" / ").append(Build.MODEL).append('\n');
        sb.append("Paths: ").append(pathCounts).append('\n');
        sb.append("Sources: ").append(sourcesSeen).append('\n');
        sb.append("Axes: ").append(axesSeen).append('\n');
        sb.append("Max fingers: ").append(maxPointers).append(", keys: ").append(keyCount).append('\n');
        sb.append("--- log ---\n");
        int i = 0;
        for (String s : log) {
            if (i++ > 1500) {
                sb.append("(truncated)\n");
                break;
            }
            sb.append(s).append('\n');
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("probe", sb));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void logDevices() {
        add("--- input devices ---");
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null) continue;
            StringBuilder sb = new StringBuilder();
            sb.append("dev ").append(id).append(" \"").append(d.getName()).append("\" src=").append(sourceName(d.getSources()));
            if (d.isVirtual()) sb.append(" virtual");
            for (InputDevice.MotionRange r : d.getMotionRanges()) {
                sb.append(String.format(Locale.US, " [%s %s %s..%s]",
                        MotionEvent.axisToString(r.getAxis()).replace("AXIS_", ""),
                        sourceName(r.getSource()), fmt(r.getMin()), fmt(r.getMax())));
            }
            add(sb.toString());
        }
        add("--- events ---");
    }

    private static String devName(int id) {
        InputDevice d = InputDevice.getDevice(id);
        return d == null ? String.valueOf(id) : id + ":" + d.getName();
    }

    static String sourceName(int s) {
        List<String> n = new ArrayList<>();
        int[] v = {InputDevice.SOURCE_TOUCHSCREEN, InputDevice.SOURCE_MOUSE, InputDevice.SOURCE_STYLUS,
                InputDevice.SOURCE_TRACKBALL, InputDevice.SOURCE_MOUSE_RELATIVE, InputDevice.SOURCE_TOUCHPAD,
                InputDevice.SOURCE_TOUCH_NAVIGATION, InputDevice.SOURCE_ROTARY_ENCODER, InputDevice.SOURCE_JOYSTICK,
                InputDevice.SOURCE_GAMEPAD, InputDevice.SOURCE_DPAD, InputDevice.SOURCE_KEYBOARD};
        String[] names = {"touchscreen", "mouse", "stylus", "trackball", "mouse_rel", "touchpad", "touch_nav",
                "rotary", "joystick", "gamepad", "dpad", "keyboard"};
        int rest = s;
        for (int i = 0; i < v.length; i++) {
            if ((s & v[i]) == v[i]) {
                n.add(names[i]);
                rest &= ~v[i];
            }
        }
        rest &= ~0xff; // class bits
        if (rest != 0) n.add(String.format("0x%x", rest));
        return n.isEmpty() ? String.format("0x%x", s) : String.join("+", n);
    }

    private static String fmt(float v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.format(Locale.US, "%.2f", v);
    }

    /**
     * Drawing area. Draws positions from any event that has them (mapped through the device's motion
     * ranges when they are not screen coordinates) and, separately, a "scroll pen" that integrates
     * scroll axes, so both kinds of data can be judged by eye.
     */
    private static final class PadView extends View {
        private static final int[] COLORS = {0xFF2196F3, 0xFFE91E63, 0xFF4CAF50, 0xFFFF9800, 0xFF9C27B0};
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scrollInk = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Map<String, Path> paths = new HashMap<>();
        private final Map<String, Integer> pathColor = new HashMap<>();
        private final Path scrollPath = new Path();
        private float sx = Float.NaN, sy;
        private final int[] loc = new int[2];

        PadView(Context c) {
            super(c);
            float dp = c.getResources().getDisplayMetrics().density;
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(3 * dp);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            scrollInk.set(ink);
            scrollInk.setColor(0xFF795548);
            scrollInk.setPathEffect(new android.graphics.DashPathEffect(new float[] {8 * dp, 5 * dp}, 0));
            text.setTextSize(11 * dp);
            text.setColor(Color.GRAY);
            setBackgroundColor(0x11000000);
        }

        void clear() {
            paths.clear();
            pathColor.clear();
            scrollPath.reset();
            sx = Float.NaN;
            invalidate();
        }

        void onEvent(String route, MotionEvent e) {
            int action = e.getActionMasked();
            // Scroll pen: integrate scroll axes.
            float hs = e.getAxisValue(MotionEvent.AXIS_HSCROLL), vs = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (action == MotionEvent.ACTION_SCROLL && (hs != 0 || vs != 0)) {
                if (Float.isNaN(sx)) {
                    sx = getWidth() / 2f;
                    sy = getHeight() / 2f;
                    scrollPath.moveTo(sx, sy);
                }
                float k = 25 * getResources().getDisplayMetrics().density;
                sx = Math.max(0, Math.min(getWidth(), sx - hs * k));
                sy = Math.max(0, Math.min(getHeight(), sy - vs * k));
                scrollPath.lineTo(sx, sy);
            }
            // Position ink, one path per (route, device, pointer id) stroke.
            if (action == MotionEvent.ACTION_SCROLL) {
                invalidate();
                return;
            }
            boolean screen = e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && !e.isFromSource(InputDevice.SOURCE_TOUCHPAD)
                    && !"captured".equals(route);
            getLocationOnScreen(loc);
            for (int i = 0; i < e.getPointerCount(); i++) {
                float x, y;
                if (screen) {
                    x = e.getRawX() + (e.getX(i) - e.getX()) - loc[0];
                    y = e.getRawY() + (e.getY(i) - e.getY()) - loc[1];
                } else {
                    InputDevice d = e.getDevice();
                    InputDevice.MotionRange rx = d == null ? null : d.getMotionRange(MotionEvent.AXIS_X, e.getSource());
                    InputDevice.MotionRange ry = d == null ? null : d.getMotionRange(MotionEvent.AXIS_Y, e.getSource());
                    if (rx == null || ry == null || rx.getRange() <= 0 || ry.getRange() <= 0) continue;
                    x = (e.getX(i) - rx.getMin()) / rx.getRange() * getWidth();
                    y = (e.getY(i) - ry.getMin()) / ry.getRange() * getHeight();
                }
                String key = route + e.getDeviceId() + ":" + e.getPointerId(i);
                Path p = paths.get(key);
                boolean down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_HOVER_ENTER
                        || (action == MotionEvent.ACTION_POINTER_DOWN && e.getActionIndex() == i);
                if (p == null) {
                    p = new Path();
                    paths.put(key, p);
                    pathColor.put(key, COLORS[pathColor.size() % COLORS.length]);
                    p.moveTo(x, y);
                } else if (down) {
                    p.moveTo(x, y);
                } else {
                    p.lineTo(x, y);
                }
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            for (Map.Entry<String, Path> en : paths.entrySet()) {
                ink.setColor(pathColor.get(en.getKey()));
                c.drawPath(en.getValue(), ink);
            }
            c.drawPath(scrollPath, scrollInk);
            c.drawText("solid = finger positions · dashed brown = scroll amounts added up", 8, getHeight() - 8, text);
        }
    }
}
