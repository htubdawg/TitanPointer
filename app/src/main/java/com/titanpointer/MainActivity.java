package com.titanpointer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Setup and settings screen. */
public final class MainActivity extends Activity implements PadLink.Listener {
    private static final String OWNER = "test";

    private SharedPreferences prefs;
    private PadLink link;
    private LinearLayout root;
    private float dp;

    private TextView accessibilityStatus, linkStatus, deviceText, adbCommand;
    private Button accessibilityButton;
    private Switch enabledSwitch, grabSwitch, zoneSwitch, followSwitch;
    private TextView assistStatus;
    private View assistGrantBox;
    private View adbBox;
    private LinearLayout noRootBox, rootBox;
    private TextView helperNote;
    private TestPadView testPad;
    private Button calibrateButton;
    private TouchTracker testTracker;
    private int screenW, screenH;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        Diag.setEnabled(prefs.getBoolean(Prefs.DIAG, false));
        link = PadLink.get(this);
        dp = getResources().getDisplayMetrics().density;
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        if (getActionBar() != null) getActionBar().setSubtitle("Version " + BuildConfig.VERSION_NAME);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        tabBar = new LinearLayout(this);
        tabBar.setBackgroundColor(themeColor(android.R.attr.colorPrimary, 0xFF37474F));
        tabBar.setElevation(4 * dp);
        outer.addView(tabBar);
        FrameLayout pagesBox = new FrameLayout(this);
        outer.addView(pagesBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        int p = (int) (16 * dp);
        for (int i = 0; i < TABS.length; i++) {
            ScrollView sv = new ScrollView(this);
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(p, p / 2, p, p * 2);
            sv.addView(page);
            pagesBox.addView(sv);
            pages[i] = sv;
            pageBodies[i] = page;
            addTab(i);
        }
        setContentView(outer);
        build();
        showTab(Math.max(0, Math.min(TABS.length - 1, prefs.getInt(Prefs.UI_TAB, 0))));
    }

    // ---------------------------------------------------------------- tabs

    private static final String[] TABS = {"Home", "Pointer", "Controls", "Tools", "About"};
    private static final int HOME = 0, POINTER = 1, CONTROLS = 2, TOOLS = 3, ABOUT = 4;
    private final ScrollView[] pages = new ScrollView[TABS.length];
    private final LinearLayout[] pageBodies = new LinearLayout[TABS.length];
    private final TextView[] tabLabels = new TextView[TABS.length];
    private final View[] tabLines = new View[TABS.length];
    private LinearLayout tabBar;

    private void addTab(final int i) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setClickable(true);
        tab.setFocusable(true);
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            tab.setBackgroundResource(tv.resourceId);
        }
        tab.setOnClickListener(v -> showTab(i));
        TextView t = new TextView(this);
        t.setText(TABS[i].toUpperCase(Locale.US));
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(0, (int) (13 * dp), 0, (int) (10 * dp));
        tab.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(themeColor(android.R.attr.colorAccent, 0xFF64B5F6));
        tab.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (3 * dp)));
        tabLabels[i] = t;
        tabLines[i] = line;
        tabBar.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void showTab(int i) {
        for (int k = 0; k < TABS.length; k++) {
            boolean on = k == i;
            pages[k].setVisibility(on ? View.VISIBLE : View.GONE);
            tabLabels[k].setAlpha(on ? 1f : 0.65f);
            tabLines[k].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        }
        if (prefs.getInt(Prefs.UI_TAB, 0) != i) prefs.edit().putInt(Prefs.UI_TAB, i).apply();
    }

    private int themeColor(int attr, int fallback) {
        TypedValue tv = new TypedValue();
        return getTheme().resolveAttribute(attr, tv, true) && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && tv.type <= TypedValue.TYPE_LAST_COLOR_INT ? tv.data : fallback;
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.addListener(this);
        prefs.registerOnSharedPreferenceChangeListener(lookListener);
        link.setOpen(OWNER, prefs.getString(Prefs.DEVICE, null) != null
                && !Prefs.MODE_NOROOT.equals(prefs.getString(Prefs.MODE, Prefs.MODE_NOROOT)));
        refresh();
        ui.postDelayed(grantWatch, 1000);
    }

    /** The permission is granted from a computer while the app may be open: notice it without a restart. */
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean lastGranted, lastAssistOn;
    private final Runnable grantWatch = new Runnable() {
        @Override
        public void run() {
            boolean g = ScrollAssist.canWrite(MainActivity.this);
            if (g != lastGranted || ScrollAssist.isOn(MainActivity.this) != lastAssistOn) refresh();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(grantWatch);
        prefs.unregisterOnSharedPreferenceChangeListener(lookListener);
        link.setOpen(OWNER, false);
        link.removeListener(this);
        if (calibrateButton != null && testPad != null) finishCalibration(false);
    }

    // ---------------------------------------------------------------- UI

    private void build() {
        buildHome();
        buildPointer();
        buildControls();
        buildTools();
        buildAbout();
    }

    private void buildHome() {
        root = pageBodies[HOME];
        section("Turn on the service");
        accessibilityStatus = text("");
        accessibilityButton = button("Open Accessibility settings", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        enabledSwitch = switchPref(Prefs.ENABLED, "Pointer mode", true);
        toggleHint = hint("");
        homeAssistStatus = text("");
        homeAssistButton = button("Open the Titan's scroll assistant setting", v -> {
            try {
                startActivity(ScrollAssist.settingsIntent(this));
            } catch (RuntimeException e) {
                Toast.makeText(this, "Couldn't open it: look for the scroll assistant in Settings", Toast.LENGTH_LONG).show();
            }
        });

        section("Gestures and keys");
        cheatSheet = text("");

        section("How it works");
        helperNote = text("Pointer mode uses the root / ADB helper (see Tools → Advanced).");
        LinearLayout outer = root;
        noRootBox = new LinearLayout(this);
        noRootBox.setOrientation(LinearLayout.VERTICAL);
        outer.addView(noRootBox);
        root = noRootBox;
        hint("The Titan reports keyboard touches only to the app that has keyboard focus, so while pointer mode "
                + "is on, TitanPointer holds the focus. Keys can't reach the app underneath until you turn it off. "
                + "The Titan's scroll assistant has to stay on: without it no keyboard touches reach apps at all.");
        root = outer;
    }

    private void buildPointer() {
        root = pageBodies[POINTER];
        section("Movement");
        seekPref(Prefs.SENS, "Speed", Prefs.SENS_MIN, Prefs.SENS_MAX, Prefs.SENS_DEFAULT, 1, v -> String.format(Locale.US, "%.1f×", v / 10f));
        seekPref(Prefs.ACCEL, "Acceleration", 0, 40, 10, 1, v -> String.format(Locale.US, "%.1f", v / 10f));
        seekPref(Prefs.YSPEED, "Vertical speed", 3, 40, 10, 1, v -> String.format(Locale.US, "%.1f×", v / 10f));
        seekPref(Prefs.AUTOHIDE_S, "Hide pointer when idle", 0, 30, 5, 1, v -> v == 0 ? "never" : v + " s");

        section("Direction");
        switchPref(Prefs.SWAP, "Swap X and Y", false);
        switchPref(Prefs.INV_X, "Invert horizontal", false);
        switchPref(Prefs.INV_Y, "Invert vertical", false);

        // --- Look, at the bottom
        section("Pointer look");
        customImage = PointerShape.loadCustom(this);
        lookPreview = new LookView(true);
        root.addView(lookPreview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (72 * dp)));
        lookName = text("");
        lookName.setGravity(Gravity.CENTER);
        hint("All " + RetroCursors.ALL.length + " cursors of the X Window System's cursor font (the original Unix/Linux "
                + "pointers), or your own picture at the end. Scroll the box to see them all.");

        // A fixed-height box that scrolls by itself inside the page.
        lookBox = new ScrollView(this);
        lookBox.setNestedScrollingEnabled(true);
        lookBox.setVerticalScrollBarEnabled(true);
        lookBox.setScrollbarFadingEnabled(false);
        android.graphics.drawable.GradientDrawable frame = new android.graphics.drawable.GradientDrawable();
        frame.setCornerRadius(6 * dp);
        frame.setStroke((int) (1 * dp), 0x61808080);
        lookBox.setBackground(frame);
        lookBox.setPadding((int) (4 * dp), (int) (4 * dp), (int) (4 * dp), (int) (4 * dp));
        lookBox.setClipToPadding(false);
        lookBox.setOnTouchListener((v, e) -> {
            // Drags inside the box scroll the box, not the page.
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        lookBox.addView(grid);
        root.addView(lookBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (200 * dp)));
        int count = RetroCursors.ALL.length + 1;
        final int perRow = 7;
        LinearLayout row = null;
        for (int i = 0; i < count; i++) {
            final boolean isCustom = i == RetroCursors.ALL.length;
            final String style = isCustom ? PointerShape.CUSTOM : RetroCursors.ALL[i].id;
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                grid.addView(row);
            }
            LookView v = new LookView(false);
            v.style = style;
            v.setClickable(true);
            v.setFocusable(true);
            v.setContentDescription((isCustom ? "Custom picture" : RetroCursors.ALL[i].label) + " pointer");
            v.setOnClickListener(x -> {
                if (isCustom && customImage == null) {
                    pickImage();
                    return;
                }
                prefs.edit().putString(Prefs.CURSOR_STYLE, style).apply();
            });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, (int) (52 * dp), 1f);
            tlp.setMargins((int) (2 * dp), (int) (2 * dp), (int) (2 * dp), (int) (2 * dp));
            row.addView(v, tlp);
            lookTiles.add(v);
        }
        for (int i = count; i % perRow != 0; i++) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        switchPref(Prefs.CURSOR_MIRROR, "Mirror (flip left to right)", false);
        switchPref(Prefs.CURSOR_INVERT, "Invert colours (swap fill and outline)", false);

        colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.VERTICAL);
        TextView cl = new TextView(this);
        cl.setText("Colour");
        cl.setTextSize(15);
        cl.setPadding(0, (int) (10 * dp), 0, (int) (4 * dp));
        colorRow.addView(cl);
        LinearLayout swatches = new LinearLayout(this);
        for (final int color : PointerShape.COLORS) {
            Swatch sw = new Swatch(color);
            sw.setOnClickListener(x -> prefs.edit().putInt(Prefs.CURSOR_COLOR, color).apply());
            swatches.addView(sw, new LinearLayout.LayoutParams(0, (int) (44 * dp), 1f));
            this.swatches.add(sw);
        }
        colorRow.addView(swatches);
        root.addView(colorRow);

        customBox = new LinearLayout(this);
        customBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(customBox);
        LinearLayout outer = root;
        root = customBox;
        button("Choose another picture", x -> pickImage());
        TextView hl = text("Click point");
        hl.setPadding(0, (int) (8 * dp), 0, 0);
        hotspotGroup = new RadioGroup(this);
        RadioButton rTl = new RadioButton(this);
        rTl.setText("Top-left corner (for arrows)");
        rTl.setId(View.generateViewId());
        RadioButton rC = new RadioButton(this);
        rC.setText("Centre (for dots, rings and crosshairs)");
        rC.setId(View.generateViewId());
        hotspotGroup.addView(rTl);
        hotspotGroup.addView(rC);
        hotspotGroup.check(PointerShape.HOT_CENTER.equals(prefs.getString(Prefs.CURSOR_HOTSPOT, PointerShape.HOT_TOP_LEFT))
                ? rC.getId() : rTl.getId());
        hotspotGroup.setOnCheckedChangeListener((g, id) -> prefs.edit().putString(Prefs.CURSOR_HOTSPOT,
                id == rC.getId() ? PointerShape.HOT_CENTER : PointerShape.HOT_TOP_LEFT).apply());
        root.addView(hotspotGroup);
        hint("Any PNG, JPG, WebP or GIF picture; transparent PNGs look best. With Mirror the click point flips too.");
        root = outer;

        seekPref(Prefs.CURSOR_SIZE, "Pointer size", 6, 30, 10, 1, v -> String.format(Locale.US, "%.1f×", v / 10f));

        updateLook();
        // Show the chosen cursor's row in the box.
        lookBox.post(() -> {
            for (LookView v : lookTiles) {
                if (v.isSelected()) {
                    lookBox.scrollTo(0, Math.max(0, ((View) v.getParent()).getTop() - (int) (4 * dp)));
                    break;
                }
            }
        });
    }

    // ---------------------------------------------------------------- pointer look

    private static final int PICK_IMAGE = 42;
    private LookView lookPreview;
    private ScrollView lookBox;
    private TextView lookName;
    private final List<LookView> lookTiles = new ArrayList<>();
    private final List<Swatch> swatches = new ArrayList<>();
    private LinearLayout colorRow, customBox;
    private RadioGroup hotspotGroup;
    private android.graphics.Bitmap customImage;

    private final SharedPreferences.OnSharedPreferenceChangeListener lookListener = (p, key) -> {
        if (key != null && key.startsWith("cursor_")) updateLook();
        if (Prefs.JOYSTICK.equals(key) && this.cheatSheet != null) updateKeyTexts();
    };

    private void updateLook() {
        String style = prefs.getString(Prefs.CURSOR_STYLE, PointerShape.DEFAULT);
        if (PointerShape.CUSTOM.equals(style) ? customImage == null : RetroCursors.find(style) == null) {
            style = PointerShape.DEFAULT;
        }
        boolean custom = PointerShape.CUSTOM.equals(style);
        int color = prefs.getInt(Prefs.CURSOR_COLOR, PointerShape.DEFAULT_COLOR);
        boolean centered = PointerShape.HOT_CENTER.equals(prefs.getString(Prefs.CURSOR_HOTSPOT, PointerShape.HOT_TOP_LEFT));
        boolean mirror = prefs.getBoolean(Prefs.CURSOR_MIRROR, false);
        boolean invert = prefs.getBoolean(Prefs.CURSOR_INVERT, false);
        lookPreview.style = style;
        lookPreview.update(color, prefs.getInt(Prefs.CURSOR_SIZE, 10) / 10f, centered, mirror, invert);
        RetroCursors.Glyph g = RetroCursors.find(style);
        lookName.setText(custom ? "Your picture" : g.label);
        for (LookView v : lookTiles) {
            v.update(color, 1f, centered, mirror, invert);
            boolean sel = v.style.equals(style);
            v.setBackground(sel ? selectedBackground() : null);
            v.setSelected(sel);
        }
        for (Swatch s : swatches) s.setChosen(s.color == color);
        colorRow.setVisibility(custom ? View.GONE : View.VISIBLE);
        customBox.setVisibility(custom ? View.VISIBLE : View.GONE);
    }

    private android.graphics.drawable.Drawable selectedBackground() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(6 * dp);
        d.setStroke((int) (2 * dp), themeColor(android.R.attr.colorAccent, 0xFF2196F3));
        return d;
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, PICK_IMAGE);
        } catch (RuntimeException e) {
            Toast.makeText(this, "No file picker found", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_IMAGE || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(() -> {
            android.graphics.Bitmap b = importImage(uri);
            runOnUiThread(() -> {
                if (b == null) {
                    Toast.makeText(this, "Couldn't read that image", Toast.LENGTH_LONG).show();
                    return;
                }
                customImage = b;
                prefs.edit().putString(Prefs.CURSOR_STYLE, PointerShape.CUSTOM)
                        .putLong(Prefs.CURSOR_IMAGE_VER, System.currentTimeMillis()).apply();
                updateLook();
            });
        }, "pointer-image").start();
    }

    /** Decodes the picked image, shrinks it to at most CUSTOM_MAX_PX and saves it as the custom pointer. */
    private android.graphics.Bitmap importImage(Uri uri) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= PointerShape.CUSTOM_MAX_PX) sample *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            android.graphics.Bitmap b;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                b = android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
            if (b == null) return null;
            int longest = Math.max(b.getWidth(), b.getHeight());
            if (longest > PointerShape.CUSTOM_MAX_PX) {
                float k = PointerShape.CUSTOM_MAX_PX / (float) longest;
                b = android.graphics.Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)),
                        Math.max(1, Math.round(b.getHeight() * k)), true);
            }
            java.io.File f = new java.io.File(getFilesDir(), PointerShape.CUSTOM_FILE);
            java.io.File tmp = new java.io.File(getFilesDir(), PointerShape.CUSTOM_FILE + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
            if (!tmp.renameTo(f)) return null;
            return b;
        } catch (java.io.IOException | RuntimeException e) {
            Diag.log("pointer image import failed: " + e);
            return null;
        }
    }

    /** Draws one pointer look, centred. The big preview shows it on a light and a dark half. */
    private final class LookView extends View {
        final boolean twoTone;
        String style = PointerShape.DEFAULT;
        final PointerShape shape = new PointerShape(MainActivity.this);
        final android.graphics.Paint bg = new android.graphics.Paint();
        final android.graphics.Paint plus = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        LookView(boolean twoTone) {
            super(MainActivity.this);
            this.twoTone = twoTone;
            plus.setTextAlign(android.graphics.Paint.Align.CENTER);
            plus.setTextSize(28 * dp);
            plus.setColor(0x8A808080);
        }

        void update(int color, float scale, boolean centered, boolean mirror, boolean invert) {
            boolean custom = PointerShape.CUSTOM.equals(style);
            shape.set(style, color, scale, custom ? customImage : null, centered, mirror, invert);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (PointerShape.CUSTOM.equals(style) && customImage == null) {
                c.drawText("+", getWidth() / 2f, getHeight() / 2f + 10 * dp, plus);
                return;
            }
            if (!twoTone) {
                drawCentered(c, getWidth() / 2f, getHeight() / 2f, Math.min(1f, getHeight() / shape.height()));
                return;
            }
            float half = getWidth() / 2f;
            bg.setColor(0xFFF2F2F2);
            c.drawRect(0, 0, half, getHeight(), bg);
            bg.setColor(0xFF303030);
            c.drawRect(half, 0, getWidth(), getHeight(), bg);
            float fit = Math.min(1f, getHeight() / shape.height());
            drawCentered(c, half / 2, getHeight() / 2f, fit);
            drawCentered(c, half * 1.5f, getHeight() / 2f, fit);
        }

        private void drawCentered(Canvas c, float cx, float cy, float k) {
            c.save();
            c.translate(cx, cy);
            c.scale(k, k);
            c.translate(-shape.width() / 2, -shape.height() / 2);
            shape.draw(c, CursorView.STATE_NORMAL);
            c.restore();
        }
    }

    /** A round colour swatch; the chosen one gets a ring. */
    private final class Swatch extends View {
        final int color;
        private boolean chosen;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Swatch(int color) {
            super(MainActivity.this);
            this.color = color;
            setClickable(true);
            setFocusable(true);
            setContentDescription(String.format(Locale.US, "Colour #%06X", color & 0xFFFFFF));
        }

        void setChosen(boolean c) {
            chosen = c;
            setSelected(c);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - 6 * dp;
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(color);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(1 * dp);
            p.setColor(0x61808080);
            c.drawCircle(cx, cy, r, p);
            if (chosen) {
                p.setStrokeWidth(2.5f * dp);
                p.setColor(themeColor(android.R.attr.colorAccent, 0xFF2196F3));
                c.drawCircle(cx, cy, r + 4 * dp, p);
            }
        }
    }

    private void buildControls() {
        root = pageBodies[CONTROLS];
        section("Joystick mode");
        switchPref(Prefs.JOYSTICK, "Joystick mode", false);
        hint("Splits the keyboard into thirds. Middle: a joystick. Touch, then hold your finger away from where it "
                + "landed; the further away, the faster the pointer moves (your finger may leave the middle). Left: "
                + "tap to click, tap twice to hold a drag (steer with the stick, tap either side to let go). Right: "
                + "tap to long press. Taps in the middle do nothing. In scroll mode (Space) the stick scrolls.");
        seekPref(Prefs.JOY_ZONE, "Click zone width (each side)", 15, 45, Prefs.JOY_ZONE_DEFAULT, 1,
                v -> v + "% of the keyboard");
        seekPref(Prefs.JOY_DEAD, "Stick dead zone", 0, 60, Prefs.JOY_DEAD_DEFAULT, 1,
                v -> v == 0 ? "none" : String.format(Locale.US, "≈ %.1f mm", v / 1000f * PAD_WIDTH_MM));
        seekPref(Prefs.JOY_REACH, "Distance for top speed", 4, 25, Prefs.JOY_REACH_DEFAULT, 1,
                v -> String.format(Locale.US, "≈ %.0f mm", v / 100f * PAD_WIDTH_MM));
        seekPref(Prefs.JOY_SPEED, "Stick top speed", 2, 40, Prefs.JOY_SPEED_DEFAULT, 1,
                v -> String.format(Locale.US, "%.1f screen widths/s", v / 10f));
        seekPref(Prefs.JOY_CURVE, "Stick response curve", 10, 40, Prefs.JOY_CURVE_DEFAULT, 1,
                v -> v == 10 ? "linear" : String.format(Locale.US, "%.1f (finer near the centre)", v / 10f));
        hint("Speed, acceleration, tap to click and tap-then-drag are for touchpad mode; the stick has its own "
                + "settings above.");

        section("Clicking");
        switchPref(Prefs.TAP, "Tap to click", true);
        switchPref(Prefs.TAP_DRAG, "Tap, then touch and move to drag (adds a short click delay)", true);
        seekPref(Prefs.TYPING_MS, "Ignore touches after a key press", 0, 1000, 350, 10, v -> v + " ms");
        seekPref(Prefs.IGNORE_TOP, "Ignore touches that start in the top key row", 0, 40, Prefs.DEFAULT_IGNORE_TOP, 1,
                v -> v == 0 ? "off" : v + "% of the keyboard");
        hint("The top row (Shift, Recents, the home key, Back, Alt) is part of the touch surface. Touches that "
                + "start there don't move the pointer or click; a slide that starts lower can still run into it.");
        switchPref(Prefs.ELEMENT_CLICK, "Click standard buttons directly", true);
        hint("After a keyboard touch the Titan refuses taps on ordinary Android buttons (toolbars, address bars) "
                + "until a key is pressed. While it does, a tap on the keyboard clicks the button under the pointer "
                + "through accessibility instead. Web pages and newer app screens still get a normal tap.");

        section("Scrolling");
        scrollHint = hint("");
        seekPref(Prefs.SCROLL, "Scroll speed", 2, 60, 15, 1, v -> String.format(Locale.US, "%.1f×", v / 10f));
        switchPref(Prefs.INV_SCROLL, "Reverse scroll direction", false);
        switchPref(Prefs.FLING, "Fling: a quick flick keeps scrolling", true);
        hint("Flick and lift while still moving to keep scrolling; stop before lifting to stop dead.");

        section("Titan scroll assistant");
        assistStatus = text("");
        LinearLayout grant = new LinearLayout(this);
        grant.setOrientation(LinearLayout.VERTICAL);
        assistGrantBox = grant;
        root.addView(grant);
        TextView gt = new TextView(this);
        gt.setText("To let TitanPointer switch it, enable USB debugging, connect a computer and run this once "
                + "(it stays granted through updates):");
        grant.addView(gt);
        TextView gc = new TextView(this);
        gc.setTypeface(Typeface.MONOSPACE);
        gc.setTextIsSelectable(true);
        gc.setTextSize(12);
        gc.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
        gc.setText(ScrollAssist.GRANT_COMMAND);
        grant.addView(gc);
        Button gcopy = new Button(this);
        gcopy.setText("Copy command");
        gcopy.setOnClickListener(v -> copy("adb", ScrollAssist.GRANT_COMMAND));
        grant.addView(gcopy);
        followSwitch = switchPref(Prefs.ASSIST_FOLLOWS_POINTER, "Turn the scroll assistant off when pointer mode is off", false);
        hint("The keyboard stops scrolling pages while pointer mode is off. Turning pointer mode on turns it back "
                + "on, since the pointer needs it. If you turn the TitanPointer service off, the scroll assistant "
                + "stays as it was.");

        section("Keys");
        keyBinding("Pointer mode on/off", Prefs.TOGGLE_KEY, Prefs.DEFAULT_TOGGLE_KEY, Prefs.TOGGLE_META, Prefs.DEFAULT_TOGGLE_META);
        hint("Works everywhere. The key is swallowed while it toggles pointer mode, so pick a combination "
                + "you don't type with.");
        keyBinding("Click", Prefs.CLICK_KEY, Prefs.DEFAULT_CLICK_KEY, Prefs.CLICK_META, Prefs.DEFAULT_CLICK_META);
        keyBinding("Long press", Prefs.LONG_KEY, Prefs.DEFAULT_LONG_KEY, Prefs.LONG_META, Prefs.DEFAULT_LONG_META);
        hint("Click and long press act at the pointer while pointer mode is on.");
        spinnerPref(Prefs.LETTERS, "Letter keys in pointer mode", new String[] {Prefs.LETTERS_NOTHING, Prefs.LETTERS_EXIT},
                new String[] {"Do nothing", "Turn pointer mode off"}, Prefs.LETTERS_NOTHING);
    }

    private void buildTools() {
        root = pageBodies[TOOLS];
        section("Keyboard input probe");
        hint("Shows everything the keyboard and screen send to an ordinary app. Useful to check that keyboard "
                + "touches arrive at all.");
        button("Open keyboard input probe", v -> startActivity(new Intent(this, ProbeActivity.class)));

        // --- Advanced: root / ADB (collapsed unless a helper mode is on)
        boolean helperMode = !Prefs.MODE_NOROOT.equals(prefs.getString(Prefs.MODE, Prefs.MODE_NOROOT));
        final LinearLayout advOuter = root;
        root = collapsible("Advanced: root / ADB (untested)", helperMode);
        hint("Not tested on a real Titan yet. Leave this on “No root” unless you want to experiment.");
        RadioGroup rg = new RadioGroup(this);
        RadioButton rNo = new RadioButton(this);
        rNo.setText("No root (recommended)");
        rNo.setId(View.generateViewId());
        RadioButton rRoot = new RadioButton(this);
        rRoot.setText("Root (su): typing and pointer at the same time");
        rRoot.setId(View.generateViewId());
        RadioButton rAdb = new RadioButton(this);
        rAdb.setText("ADB helper: like root, started from a computer");
        rAdb.setId(View.generateViewId());
        rg.addView(rNo);
        rg.addView(rRoot);
        rg.addView(rAdb);
        String mode = prefs.getString(Prefs.MODE, Prefs.MODE_NOROOT);
        rg.check("adb".equals(mode) ? rAdb.getId() : "root".equals(mode) ? rRoot.getId() : rNo.getId());
        rg.setOnCheckedChangeListener((g, id) -> {
            prefs.edit().putString(Prefs.MODE, id == rAdb.getId() ? "adb" : id == rRoot.getId() ? "root" : Prefs.MODE_NOROOT).apply();
            refresh();
            boolean helper = id != rNo.getId();
            link.setOpen(OWNER, helper && prefs.getString(Prefs.DEVICE, null) != null);
            link.restart();
        });
        root.addView(rg);

        rootBox = new LinearLayout(this);
        rootBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(rootBox);
        root = rootBox;
        hint("A tiny helper reads the raw sensor directly, so typing keeps working while the pointer is on. "
                + "Root asks for su; the ADB helper is started from a computer once per boot.");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        adbBox = box;
        root.addView(box);
        TextView t = new TextView(this);
        t.setText("Enable USB debugging, connect a computer and run this once after every reboot:");
        box.addView(t);
        adbCommand = new TextView(this);
        adbCommand.setTypeface(Typeface.MONOSPACE);
        adbCommand.setTextIsSelectable(true);
        adbCommand.setTextSize(12);
        adbCommand.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
        box.addView(adbCommand);
        Button copy = new Button(this);
        copy.setText("Copy command");
        copy.setOnClickListener(v -> copy("adb", adbCommand.getText()));
        box.addView(copy);

        linkStatus = text("");
        linkStatus.setTypeface(Typeface.DEFAULT_BOLD);

        section("Pick the keyboard touch device");
        deviceText = text("");
        button("Find devices", v -> {
            Toast.makeText(this, "Scanning input devices…", Toast.LENGTH_SHORT).show();
            link.requestDeviceList();
        });
        grabSwitch = switchPref(Prefs.GRAB, "Exclusive access while pointer mode is on", false);
        hint("Stops Android and the Titan's own keyboard scrolling from also reacting to your swipes. "
                + "Don't use it if the chosen device is also the screen or the keys (the app warns you).");

        section("Test & calibrate");
        hint("Touch the keyboard: dots should follow your fingers. If the device reports more than the keyboard "
                + "(for example it is the main touchscreen), calibrate to limit it to the keyboard area.");
        testPad = new TestPadView(this);
        testTracker = new TouchTracker(testPad);
        root.addView(testPad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(this);
        calibrateButton = new Button(this);
        calibrateButton.setText("Calibrate area");
        calibrateButton.setOnClickListener(v -> {
            if (calibrateButton.getTag() == null) {
                calibrateButton.setTag("on");
                calibrateButton.setText("Finish calibration");
                testPad.startCalibration();
            } else {
                finishCalibration(true);
            }
        });
        row.addView(calibrateButton);
        root.addView(row);
        zoneSwitch = switchPref(Prefs.ZONE_ON, "Only use the calibrated area", false);
        root = advOuter;

        // --- Diagnostics
        section("Diagnostics");
        hint("What the pointer saw recently: focus changes, which windows opened, keys and where they came from. "
                + "If the pointer stops working somewhere, reproduce it, come back here and copy this.");
        diagSwitch = switchPref(Prefs.DIAG, "Capture diagnostics", false);
        diagSwitch.setOnCheckedChangeListener((v, on) -> {
            if (prefs.getBoolean(Prefs.DIAG, false) != on) prefs.edit().putBoolean(Prefs.DIAG, on).apply();
            Diag.setEnabled(on);
            refresh();
        });
        button("Open tap test screen", v -> startActivity(new Intent(this, TapTestActivity.class)));
        button("Search phone software for the scroll switch", v -> {
            diagSwitch.setChecked(true); // the search logs into diagnostics: turn capturing on visibly
            v.setEnabled(false);
            Toast.makeText(this, "Searching… this can take a minute", Toast.LENGTH_LONG).show();
            new Thread(() -> FrameworkScan.run(msg -> runOnUiThread(() -> {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                if (msg.startsWith("Search done")) {
                    v.setEnabled(true);
                    refresh();
                }
            })), "framework-scan").start();
        });
        switchPref(Prefs.TAP_DELAY_TEST, "Test: taps on the keyboard click 1 s late", false);
        switchPref(Prefs.KEY_TIMER_TEST, "Test: send the click key's click from a timer", false);
        hint("For tracking down clicks that don't work. Leave off normally.");
        LinearLayout dr = new LinearLayout(this);
        Button copyDiag = new Button(this);
        copyDiag.setText("Copy");
        copyDiag.setOnClickListener(v -> copy("TitanPointer diagnostics",
                "TitanPointer " + BuildConfig.VERSION_NAME + "\n" + Diag.dump()));
        Button clearDiag = new Button(this);
        clearDiag.setText("Clear");
        clearDiag.setOnClickListener(v -> {
            Diag.clear();
            refresh();
        });
        dr.addView(copyDiag);
        dr.addView(clearDiag);
        root.addView(dr);
        diagButtons = dr;
        diagText = text("");
        diagText.setTypeface(Typeface.MONOSPACE);
        diagText.setTextSize(11);
        diagText.setTextIsSelectable(true);
    }

    private static final String REPO_URL = "https://github.com/htubdawg/TitanPointer";

    private void buildAbout() {
        root = pageBodies[ABOUT];
        section("TitanPointer");
        text("Version " + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")");
        hint("Turns the original Unihertz Titan's touch-sensitive keyboard into a touchpad with an on-screen "
                + "pointer. Nothing is collected or sent anywhere.");

        section("Source code and updates");
        TextView url = text(REPO_URL);
        url.setTextIsSelectable(true);
        button("Open on GitHub", v -> openUrl(REPO_URL));
        button("Download the latest release", v -> openUrl(REPO_URL + "/releases/latest"));
        hint("Updates install over the old version: download the APK from the latest release and open it.");
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            copy("link", url);
            Toast.makeText(this, "No browser found: link copied", Toast.LENGTH_LONG).show();
        }
    }

    private void copy(String label, CharSequence text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    /** The Titan's keyboard area is about 78 mm wide (docs/titan-hardware.md). */
    private static final float PAD_WIDTH_MM = 78f;

    private TextView toggleHint, homeAssistStatus, cheatSheet, scrollHint;
    private Button homeAssistButton;

    /** Text that names the current key bindings. */
    private void updateKeyTexts() {
        String toggle = keyName(Prefs.TOGGLE_KEY, Prefs.DEFAULT_TOGGLE_KEY, Prefs.TOGGLE_META, Prefs.DEFAULT_TOGGLE_META);
        String click = keyName(Prefs.CLICK_KEY, Prefs.DEFAULT_CLICK_KEY, Prefs.CLICK_META, Prefs.DEFAULT_CLICK_META);
        String lng = keyName(Prefs.LONG_KEY, Prefs.DEFAULT_LONG_KEY, Prefs.LONG_META, Prefs.DEFAULT_LONG_META);
        toggleHint.setText("Toggle with " + toggle + " or the Quick Settings tile “Keyboard pointer”. "
                + "Keys are set under Controls.");
        boolean joy = prefs.getBoolean(Prefs.JOYSTICK, false);
        StringBuilder sb = new StringBuilder();
        if (joy) {
            sb.append("Joystick mode (keyboard in thirds):\n");
            sb.append("• Middle: hold away from where you touched to move; further = faster\n");
            sb.append("• Left: tap to click · tap twice: hold a drag, tap again to let go\n");
            sb.append("• Right: tap to long press\n");
        } else {
            sb.append("• Slide: move the pointer\n");
            sb.append("• Tap: click · tap twice: double tap\n");
            sb.append("• Tap, then touch again and move: drag\n");
        }
        if (!"none".equals(click)) sb.append("• ").append(click).append(": click\n");
        if (!"none".equals(lng)) sb.append("• ").append(lng).append(": long press\n");
        sb.append(joy ? "• Space: scroll mode on/off (the stick scrolls)\n"
                : "• Space: scroll mode on/off (slides scroll, taps don't click)\n");
        if (!"none".equals(toggle)) sb.append("• ").append(toggle).append(": pointer mode on/off\n");
        sb.append("• Typing is ignored automatically");
        cheatSheet.setText(sb);
        scrollHint.setText("Space turns scroll mode on and off. In scroll mode, slides scroll and taps never click.");
    }

    private String keyName(String keyPref, int defKey, String metaPref, int defMeta) {
        return describeKey(prefs.getInt(keyPref, defKey), prefs.getInt(metaPref, defMeta));
    }

    /**
     * A section with a tap-to-expand header. Adds the header and an empty box to the current layout and
     * returns the box; the caller fills it and then restores {@code root}.
     */
    private LinearLayout collapsible(final String title, boolean expanded) {
        final TextView header = new TextView(this);
        header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, (int) (20 * dp), 0, (int) (6 * dp));
        root.addView(header);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        root.addView(box);
        final Runnable label = () -> header.setText((box.getVisibility() == View.VISIBLE ? "▾ " : "▸ ") + title);
        box.setVisibility(expanded ? View.VISIBLE : View.GONE);
        label.run();
        header.setOnClickListener(v -> {
            box.setVisibility(box.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            label.run();
        });
        return box;
    }

    private TextView diagText;
    private Switch diagSwitch;
    private View diagButtons;

    private void finishCalibration(boolean save) {
        if (calibrateButton.getTag() == null) return;
        calibrateButton.setTag(null);
        calibrateButton.setText("Calibrate area");
        int[] z = testPad.stopCalibration();
        if (!save) return;
        if (z == null || z[1] - z[0] < 10 || z[3] - z[2] < 10) {
            Toast.makeText(this, "Not enough touches recorded", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putInt(Prefs.ZONE_XMIN, z[0]).putInt(Prefs.ZONE_XMAX, z[1])
                .putInt(Prefs.ZONE_YMIN, z[2]).putInt(Prefs.ZONE_YMAX, z[3])
                .putBoolean(Prefs.ZONE_ON, true).apply();
        zoneSwitch.setChecked(true);
        refresh();
    }

    private final List<Runnable> keyLabels = new ArrayList<>();

    /** A row showing a key binding, with Set and Clear buttons. */
    private void keyBinding(final String label, final String keyPref, final int defKey, final String metaPref,
            final int defMeta) {
        final TextView t = text("");
        Runnable update = () -> t.setText(label + ": " + describeKey(prefs.getInt(keyPref, defKey),
                prefs.getInt(metaPref, defMeta)));
        keyLabels.add(update);
        update.run();
        LinearLayout r = new LinearLayout(this);
        Button set = new Button(this);
        set.setText("Set");
        set.setOnClickListener(v -> captureKey(label, keyPref, metaPref));
        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setOnClickListener(v -> {
            prefs.edit().putInt(keyPref, 0).putInt(metaPref, 0).apply();
            refresh();
        });
        Button reset = new Button(this);
        reset.setText("Default");
        reset.setOnClickListener(v -> {
            prefs.edit().putInt(keyPref, defKey).putInt(metaPref, defMeta).apply();
            refresh();
        });
        r.addView(set);
        r.addView(clear);
        r.addView(reset);
        root.addView(r);
    }

    private static String describeKey(int key, int m) {
        if (key == 0) return "none";
        StringBuilder sb = new StringBuilder();
        if ((m & KeyEvent.META_CTRL_ON) != 0) sb.append("Ctrl + ");
        if ((m & KeyEvent.META_ALT_ON) != 0) sb.append("Alt + ");
        if ((m & KeyEvent.META_SHIFT_ON) != 0) sb.append("Shift + ");
        if ((m & KeyEvent.META_SYM_ON) != 0) sb.append("Sym + ");
        String name = KeyEvent.keyCodeToString(key).replace("KEYCODE_", "").replace('_', ' ');
        sb.append(name.charAt(0)).append(name.substring(1).toLowerCase(Locale.US));
        return sb.toString();
    }

    private void captureKey(String label, final String keyPref, final String metaPref) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(label)
                .setMessage("Press the key to use. Hold Alt, Shift or Sym too for a combination.")
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        d.setOnKeyListener((di, code, ev) -> {
            if (KeyEvent.isModifierKey(code) || code == KeyEvent.KEYCODE_BACK) return false;
            if (ev.getAction() != KeyEvent.ACTION_UP) return true;
            prefs.edit().putInt(keyPref, code)
                    .putInt(metaPref, PointerService.normalizeMeta(ev.getMetaState())).apply();
            di.dismiss();
            refresh();
            return true;
        });
        d.show();
    }

    private void refresh() {
        boolean svc = PointerService.isRunning() || isAccessibilityEnabled();
        accessibilityStatus.setText(svc ? "✓ Accessibility service is on"
                : "✗ Turn on “TitanPointer” under Accessibility → Downloaded/Installed services.");
        accessibilityButton.setVisibility(svc ? View.GONE : View.VISIBLE);
        enabledSwitch.setChecked(prefs.getBoolean(Prefs.ENABLED, true));

        String mode = prefs.getString(Prefs.MODE, Prefs.MODE_NOROOT);
        boolean noRoot = Prefs.MODE_NOROOT.equals(mode);
        noRootBox.setVisibility(noRoot ? View.VISIBLE : View.GONE);
        rootBox.setVisibility(noRoot ? View.GONE : View.VISIBLE);
        helperNote.setVisibility(noRoot ? View.GONE : View.VISIBLE);
        adbBox.setVisibility("adb".equals(mode) ? View.VISIBLE : View.GONE);
        adbCommand.setText(adbCommandText());

        String dev = prefs.getString(Prefs.DEVICE, null);
        deviceText.setText(dev == null ? "No device chosen yet. Tap “Find devices”."
                : prefs.getString(Prefs.DEVICE_NAME, "?") + "  (" + dev + ")");
        grabSwitch.setChecked(prefs.getBoolean(Prefs.GRAB, false));
        zoneSwitch.setChecked(prefs.getBoolean(Prefs.ZONE_ON, false));
        testPad.setZone(prefs.getBoolean(Prefs.ZONE_ON, false) ? new int[] {prefs.getInt(Prefs.ZONE_XMIN, 0),
                prefs.getInt(Prefs.ZONE_XMAX, 0), prefs.getInt(Prefs.ZONE_YMIN, 0), prefs.getInt(Prefs.ZONE_YMAX, 0)} : null);

        boolean granted = ScrollAssist.canWrite(this);
        lastGranted = granted;
        assistStatus.setText(granted ? "✓ TitanPointer can switch the scroll assistant (currently "
                + (ScrollAssist.isOn(this) ? "on" : "off") + ")"
                : "✗ TitanPointer isn't allowed to switch the scroll assistant yet.");
        assistGrantBox.setVisibility(granted ? View.GONE : View.VISIBLE);
        followSwitch.setEnabled(granted);
        boolean assistOn = ScrollAssist.isOn(this);
        lastAssistOn = assistOn;
        homeAssistStatus.setText(assistOn ? "✓ Titan scroll assistant is on"
                : "✗ The Titan's scroll assistant is off, so no keyboard touches reach the pointer."
                + (granted ? " Turning pointer mode on switches it on." : " Turn it on:"));
        homeAssistButton.setVisibility(assistOn || granted ? View.GONE : View.VISIBLE);
        updateKeyTexts();

        for (Runnable r : keyLabels) r.run();
        if (diagText != null) {
            boolean on = Diag.isEnabled();
            diagSwitch.setChecked(prefs.getBoolean(Prefs.DIAG, false));
            diagText.setText(Diag.dump());
            diagText.setVisibility(on ? View.VISIBLE : View.GONE);
            diagButtons.setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    private String adbCommandText() {
        String lib = link.helperPath();
        return "adb shell \"pkill -f 'titanpa[d] serve'; cp " + lib + " /data/local/tmp/titanpad && chmod 755 /data/local/tmp/titanpad"
                + " && setsid nohup /data/local/tmp/titanpad serve " + Prefs.adbPort(this) + " "
                + Prefs.adbToken(this) + " >/dev/null 2>&1 < /dev/null &\"";
    }

    private boolean isAccessibilityEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
        sp.setString(s);
        while (sp.hasNext()) {
            String c = sp.next();
            if (c.startsWith(getPackageName() + "/")) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- PadLink

    @Override
    public void onStatus(String status, boolean ok) {
        linkStatus.setText((ok ? "● " : "○ ") + status);
        linkStatus.setTextColor(ok ? 0xFF2E7D32 : 0xFFC62828);
    }

    @Override
    public void onDeviceOpened(int xMin, int xMax, int yMin, int yMax, int slots) {
        testTracker.reset();
        testPad.setRange(xMin, xMax, yMin, yMax);
    }

    @Override
    public void onSlot(int slot) {
        testTracker.setSlot(slot);
    }

    @Override
    public void onEvents(int[] ev, int count) {
        for (int i = 0; i < count; i++) testTracker.onEvent(ev[i * 3], ev[i * 3 + 1], ev[i * 3 + 2]);
    }

    @Override
    public void onDeviceList(List<InputDeviceInfo> devices) {
        if (devices == null) {
            Toast.makeText(this, "Could not reach the helper. Check Advanced: root / ADB.", Toast.LENGTH_LONG).show();
            return;
        }
        final List<InputDeviceInfo> c = new ArrayList<>();
        for (InputDeviceInfo d : devices) if (d.score(screenW, screenH) >= 0) c.add(d);
        Collections.sort(c, (a, b) -> b.score(screenW, screenH) - a.score(screenW, screenH));
        if (c.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("No touch devices found")
                    .setMessage(devices.size() + " input devices were found, but none reports touch positions.")
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        String[] items = new String[c.size()];
        for (int i = 0; i < c.size(); i++) items[i] = (i == 0 ? "★ " : "") + c.get(i).describe();
        new AlertDialog.Builder(this)
                .setTitle("Which one is the keyboard? (★ = best guess)")
                .setItems(items, (d, which) -> chooseDevice(c.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void chooseDevice(InputDeviceInfo d) {
        boolean screenLike = d.isDirect() && Math.abs(d.xMax - d.xMin + 1 - screenW) <= 2;
        boolean safeGrab = !d.hasKeyboard() && !screenLike;
        prefs.edit()
                .putString(Prefs.DEVICE, d.path)
                .putString(Prefs.DEVICE_NAME, d.name)
                .putInt(Prefs.RAW_XMIN, d.xMin).putInt(Prefs.RAW_XMAX, d.xMax)
                .putInt(Prefs.RAW_YMIN, d.yMin).putInt(Prefs.RAW_YMAX, d.yMax)
                .putBoolean(Prefs.ZONE_ON, false)
                .putBoolean(Prefs.GRAB, safeGrab)
                .apply();
        link.setOpen(OWNER, true);
        link.restart();
        refresh();
        if (!safeGrab) {
            new AlertDialog.Builder(this).setTitle("Heads up")
                    .setMessage(d.hasKeyboard()
                            ? "This device also produces key presses, so exclusive access has been turned off (it would block typing)."
                            : "This looks like the main touchscreen, so exclusive access has been turned off (it would block the screen). "
                            + "Use “Calibrate area” to limit the pointer to the keyboard region.")
                    .setPositiveButton(android.R.string.ok, null).show();
        }
    }

    // ---------------------------------------------------------------- widget helpers

    private void section(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, (int) (20 * dp), 0, (int) (6 * dp));
        root.addView(t);
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        root.addView(t);
        return t;
    }

    private TextView hint(String s) {
        TextView t = text(s);
        t.setTextSize(13);
        t.setAlpha(0.7f);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(b, lp);
        return b;
    }

    private Switch switchPref(final String key, String label, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(15);
        s.setChecked(prefs.getBoolean(key, def));
        s.setPadding(0, (int) (8 * dp), 0, (int) (8 * dp));
        s.setOnCheckedChangeListener((v, on) -> {
            if (prefs.getBoolean(key, def) != on) prefs.edit().putBoolean(key, on).apply();
        });
        root.addView(s);
        return s;
    }

    interface Fmt {
        String f(int v);
    }

    /**
     * A labelled slider with − and + buttons either side. Each button tap moves one {@code step}; holding a
     * button repeats, faster after a moment.
     */
    private void seekPref(final String key, final String label, final int min, final int max, int def, final int step,
            final Fmt fmt) {
        final TextView t = new TextView(this);
        t.setTextSize(15);
        t.setPadding(0, (int) (8 * dp), 0, 0);
        root.addView(t);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        int cur = Math.max(min, Math.min(max, prefs.getInt(key, def)));
        sb.setProgress(cur - min);
        t.setText(label + ": " + fmt.f(cur));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                t.setText(label + ": " + fmt.f(p + min));
                if (prefs.getInt(key, Integer.MIN_VALUE) != p + min) prefs.edit().putInt(key, p + min).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        // Setting the progress from code also saves (onProgressChanged), so the buttons only move the slider.
        row.addView(stepButton("−", "Decrease " + label, () -> {
            int v = sb.getProgress() + min;
            int down = (v % step == 0 ? v - step : v - Math.floorMod(v, step));
            sb.setProgress(Math.max(min, down) - min);
        }));
        row.addView(sb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(stepButton("+", "Increase " + label, () -> {
            int v = sb.getProgress() + min;
            int up = v - Math.floorMod(v, step) + step;
            sb.setProgress(Math.min(max, up) - min);
        }));
        root.addView(row);
    }

    /** A small square button that runs {@code action} once per tap and repeatedly while held. */
    private Button stepButton(String label, String description, final Runnable action) {
        final Button b = new Button(this);
        b.setText(label);
        b.setTextSize(20);
        b.setContentDescription(description);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        int size = (int) (48 * dp);
        b.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        b.setOnClickListener(v -> action.run());
        final Runnable repeat = new Runnable() {
            int count;

            @Override
            public void run() {
                if (!b.isPressed()) {
                    count = 0;
                    return;
                }
                action.run();
                count++;
                ui.postDelayed(this, count < 10 ? 120 : 40);
            }
        };
        b.setOnLongClickListener(v -> {
            ui.removeCallbacks(repeat);
            repeat.run();
            return true; // no click on release
        });
        return b;
    }

    private void spinnerPref(final String key, String label, final String[] values, String[] labels, String def) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        String cur = prefs.getString(key, def);
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) sp.setSelection(i);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putString(key, values[pos]).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {}
        });
        row.addView(sp);
        root.addView(row);
    }
}
