package com.titanpointer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Test screen for the "browser toolbar ignores keyboard taps" problem. It has a top toolbar built like a
 * browser's (a clickable, long-clickable address bar plus two buttons) and logs, into the diagnostics log,
 * every input event this window and each element receives, plus pressed state, clicks and long clicks.
 */
public final class TapTestActivity extends Activity {
    private float dp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        dp = getResources().getDisplayMetrics().density;
        Diag.setEnabled(true);

        LinearLayout root = new LogGroup("screen layout");
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // --- Toolbar like a browser's: address bar + tab counter + menu.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFFE0E0E0);
        int p = (int) (8 * dp);
        bar.setPadding(p, p, p, p);
        LogText url = new LogText("address bar", true);
        url.setText("test.example  (fake address bar)");
        url.setBackgroundColor(0xFFFFFFFF);
        url.setFocusable(true);
        bar.addView(url, new LinearLayout.LayoutParams(0, (int) (40 * dp), 1));
        LogText tabs = new LogText("tab counter", true);
        tabs.setText("1");
        bar.addView(tabs, new LinearLayout.LayoutParams((int) (48 * dp), (int) (40 * dp)));
        LogText menu = new LogText("menu", false);
        menu.setText("⋮");
        bar.addView(menu, new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp)));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView help = new TextView(this);
        help.setTextColor(Color.BLACK);
        help.setPadding(p * 2, p * 2, p * 2, p);
        help.setText("1. Turn pointer mode on, put the pointer on the fake address bar.\n"
                + "2. Tap the keyboard once. Wait 2 s.\n"
                + "3. Press Enter once. Wait 2 s.\n"
                + "4. Repeat steps 2 and 3 twice more on the address bar.\n"
                + "5. Turn pointer mode off and tap “Copy log” with your finger.\n\n"
                + "Each element fades for a moment when it registers a click.");
        root.addView(help);

        RawTarget raw = new RawTarget();
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (48 * dp));
        rlp.setMargins(p * 2, p, p * 2, p);
        root.addView(raw, rlp);

        LogText plain = new LogText("plain button", false);
        plain.setText("Plain button (for comparison)");
        plain.setBackgroundColor(0xFFBBDEFB);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (48 * dp));
        plp.setMargins(p * 2, p, p * 2, p);
        root.addView(plain, plp);

        ScrollView sv = new ScrollView(this);
        LogText inScroll = new LogText("button in scroll view", true);
        inScroll.setText("Button inside a scrolling area (for comparison)");
        inScroll.setBackgroundColor(0xFFC8E6C9);
        sv.addView(inScroll, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (48 * dp)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (64 * dp));
        slp.setMargins(p * 2, p, p * 2, p);
        root.addView(sv, slp);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(p, p, p, p);
        Button copy = new Button(this);
        copy.setText("Copy log");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("TitanPointer diagnostics",
                    "TitanPointer " + BuildConfig.VERSION_NAME + " (tap test)\n" + Diag.dump()));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        Button clear = new Button(this);
        clear.setText("Clear log");
        clear.setOnClickListener(v -> {
            Diag.clear();
            Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show();
        });
        buttons.addView(copy);
        buttons.addView(clear);
        root.addView(buttons);

        setContentView(root);
        root.getViewTreeObserver().addOnTouchModeChangeListener(
                inTouch -> log("window touch mode -> " + inTouch));
        log("tap test screen opened; touch mode " + root.isInTouchMode());
    }

    // ---------------------------------------------------------------- window level

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        log("WINDOW got " + describe(e));
        boolean r = super.dispatchTouchEvent(e);
        if (e.getActionMasked() != MotionEvent.ACTION_MOVE) log("WINDOW handled=" + r);
        return r;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        log("WINDOW generic " + describe(e));
        return super.dispatchGenericMotionEvent(e);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        log("WINDOW key " + KeyEvent.keyCodeToString(e.getKeyCode()) + " action=" + e.getAction());
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        log("window focus " + has);
    }

    @Override
    protected void onPause() {
        super.onPause();
        log("screen paused");
    }

    @Override
    protected void onResume() {
        super.onResume();
        log("screen resumed");
    }

    // ---------------------------------------------------------------- helpers

    static void log(String s) {
        Diag.log("[test] " + s);
    }

    static String describe(MotionEvent e) {
        long now = SystemClock.uptimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append(MotionEvent.actionToString(e.getAction()).replace("ACTION_", ""));
        sb.append(String.format(Locale.US, " at %.0f,%.0f (raw %.0f,%.0f) t+%d since down, arrived %d ms after it happened",
                e.getX(), e.getY(), e.getRawX(), e.getRawY(), e.getEventTime() - e.getDownTime(),
                now - e.getEventTime()));
        sb.append(" dev=").append(e.getDeviceId());
        sb.append(" src=0x").append(Integer.toHexString(e.getSource()));
        sb.append(" tool=").append(e.getToolType(0));
        sb.append(" flags=0x").append(Integer.toHexString(e.getFlags()));
        sb.append(" ptrs=").append(e.getPointerCount());
        if (e.getHistorySize() > 0) sb.append(" batched=").append(e.getHistorySize());
        if (e.getMetaState() != 0) sb.append(" meta=0x").append(Integer.toHexString(e.getMetaState()));
        if (e.getButtonState() != 0) sb.append(" buttons=0x").append(Integer.toHexString(e.getButtonState()));
        if (Build.VERSION.SDK_INT >= 29 && e.getClassification() != MotionEvent.CLASSIFICATION_NONE) {
            sb.append(" class=").append(e.getClassification());
        }
        return sb.toString();
    }

    private java.util.Map<String, String> lastSnap;
    private boolean lastAccepted;
    private String lastName;

    /** Logs what changed since the previous touch on a test element, labelled by whether each was accepted. */
    private void compareWithLast(java.util.Map<String, String> snap, boolean accepted, String name) {
        if (lastSnap != null) {
            java.util.List<String> d = StateProbe.diff(lastSnap, snap);
            log("STATE CHANGES from previous touch (" + lastName + ", " + (lastAccepted ? "ACCEPTED" : "REFUSED")
                    + ") to this one (" + name + ", " + (accepted ? "ACCEPTED" : "REFUSED") + "): " + d.size());
            int n = 0;
            for (String line : d) {
                if (n++ >= 150) {
                    log("    … " + (d.size() - 150) + " more");
                    break;
                }
                log("    " + line);
            }
        }
        lastSnap = snap;
        lastAccepted = accepted;
        lastName = name;
    }

    /** Every check standard Android applies before a view gets a touch, as this phone answers them. */
    @android.annotation.SuppressLint("SoonBlockedPrivateApi") // diagnostics only; caught if refused
    static void logGates(View v, String name, MotionEvent e) {
        StringBuilder sb = new StringBuilder("  " + name + " checks for DOWN:");
        sb.append(" flags=0x").append(Integer.toHexString(e.getFlags()));
        sb.append(" targetsA11yFocus=").append((e.getFlags() & 0x40000000) != 0);
        sb.append(" securityFilter=").append(v.onFilterTouchEventForSecurity(e) ? "pass" : "BLOCK");
        sb.append(" filterWhenObscured=").append(v.getFilterTouchesWhenObscured());
        sb.append(" enabled=").append(v.isEnabled());
        sb.append(" clickable=").append(v.isClickable());
        sb.append(" longClickable=").append(v.isLongClickable());
        sb.append(" attached=").append(v.isAttachedToWindow());
        sb.append(" shown=").append(v.isShown());
        try {
            java.lang.reflect.Method m = View.class.getDeclaredMethod("isAccessibilityFocusedViewOrHost");
            m.setAccessible(true);
            sb.append(" a11yFocused=").append(m.invoke(v));
        } catch (Throwable t) {
            sb.append(" a11yFocused=?");
        }
        log(sb.toString());
    }

    /** The screen's own layout: logs whether it intercepts the touch and whether a child took it. */
    private final class LogGroup extends LinearLayout {
        private final String name;

        LogGroup(String name) {
            super(TapTestActivity.this);
            this.name = name;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) logGates(this, name, e);
            boolean r = super.dispatchTouchEvent(e);
            if (e.getActionMasked() != MotionEvent.ACTION_MOVE) {
                log("  " + name + " dispatch " + MotionEvent.actionToString(e.getAction()).replace("ACTION_", "")
                        + " -> " + r);
            }
            return r;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            boolean r = super.onInterceptTouchEvent(e);
            if (e.getActionMasked() != MotionEvent.ACTION_MOVE) {
                log("  " + name + " intercept " + MotionEvent.actionToString(e.getAction())
                        .replace("ACTION_", "") + " -> " + r);
            }
            return r;
        }
    }

    /** A bare view (not a text or button) that accepts every touch, to compare with the text elements. */
    private final class RawTarget extends View {
        RawTarget() {
            super(TapTestActivity.this);
            setBackgroundColor(0xFFFFE0B2);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) logGates(this, "bare view", e);
            boolean r = super.dispatchTouchEvent(e);
            if (e.getActionMasked() != MotionEvent.ACTION_MOVE) {
                log("  bare view got " + MotionEvent.actionToString(e.getAction()).replace("ACTION_", "")
                        + " handled=" + r);
            }
            return r;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_MOVE) {
                log("  bare view onTouchEvent reached (" + MotionEvent.actionToString(e.getAction())
                        .replace("ACTION_", "") + ")");
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                setAlpha(0.4f);
                postDelayed(() -> setAlpha(1f), 300);
            }
            return true;
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            super.onDraw(c);
            android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            pt.setTextSize(14 * dp);
            c.drawText("Bare view (not a button, takes every touch)", 8 * dp, 30 * dp, pt);
        }
    }

    /** A clickable text that logs everything that happens to it. */
    private final class LogText extends TextView {
        private final String name;

        LogText(String name, boolean longClickable) {
            super(TapTestActivity.this);
            this.name = name;
            setTextColor(Color.BLACK);
            setTypeface(Typeface.DEFAULT_BOLD);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding((int) (8 * dp), 0, (int) (8 * dp), 0);
            setSingleLine();
            setClickable(true);
            setOnClickListener(v -> {
                log(name + ": CLICK listener ran");
                v.setAlpha(0.4f);
                v.postDelayed(() -> v.setAlpha(1f), 300);
            });
            if (longClickable) {
                setOnLongClickListener(v -> {
                    log(name + ": LONG CLICK listener ran");
                    return true;
                });
            }
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            boolean down = e.getActionMasked() == MotionEvent.ACTION_DOWN;
            java.util.Map<String, String> snap = null;
            if (down) {
                logGates(this, name, e);
                long t0 = SystemClock.uptimeMillis();
                snap = StateProbe.snapshot(TapTestActivity.this, this);
                log("  state snapshot: " + snap.size() + " values in " + (SystemClock.uptimeMillis() - t0) + " ms");
            }
            boolean r = super.dispatchTouchEvent(e);
            if (down) compareWithLast(snap, r, name);
            if (e.getActionMasked() != MotionEvent.ACTION_MOVE) {
                log("  " + name + " got " + MotionEvent.actionToString(e.getAction()).replace("ACTION_", "")
                        + " handled=" + r + " pressed=" + isPressed());
            }
            return r;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            boolean r = super.onTouchEvent(e);
            if (e.getActionMasked() != MotionEvent.ACTION_MOVE) {
                log("  " + name + " onTouchEvent reached (" + MotionEvent.actionToString(e.getAction())
                        .replace("ACTION_", "") + ") -> " + r);
            }
            return r;
        }

        @Override
        public void setPressed(boolean pressed) {
            if (pressed != isPressed()) log("  " + name + " pressed -> " + pressed);
            super.setPressed(pressed);
        }

        @Override
        public boolean performClick() {
            log("  " + name + " performClick");
            return super.performClick();
        }

        @Override
        public boolean performLongClick() {
            log("  " + name + " performLongClick");
            return super.performLongClick();
        }

        @Override
        protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
            super.onFocusChanged(focused, direction, previous);
            log("  " + name + " focus " + focused);
        }
    }
}
