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

        LinearLayout root = new LinearLayout(this);
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
                + "4. Turn pointer mode off and tap “Copy log” with your finger.\n\n"
                + "Each element fades for a moment when it registers a click.");
        root.addView(help);

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
            boolean r = super.dispatchTouchEvent(e);
            if (e.getActionMasked() != MotionEvent.ACTION_MOVE) {
                log("  " + name + " got " + MotionEvent.actionToString(e.getAction()).replace("ACTION_", "")
                        + " handled=" + r + " pressed=" + isPressed());
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
