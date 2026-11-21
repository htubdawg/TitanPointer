package com.titanpointer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
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
    private Switch enabledSwitch, grabSwitch, zoneSwitch, clickFixSwitch, followSwitch;
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

        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * dp);
        root.setPadding(p, p / 2, p, p * 2);
        sv.addView(root);
        setContentView(sv);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.addListener(this);
        link.setOpen(OWNER, prefs.getString(Prefs.DEVICE, null) != null
                && !Prefs.MODE_NOROOT.equals(prefs.getString(Prefs.MODE, Prefs.MODE_NOROOT)));
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        link.setOpen(OWNER, false);
        link.removeListener(this);
        if (calibrateButton != null && testPad != null) finishCalibration(false);
    }

    // ---------------------------------------------------------------- UI

    private void build() {
        // --- Status
        section("1. Turn on the service");
        accessibilityStatus = text("");
        accessibilityButton = button("Open Accessibility settings", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        enabledSwitch = switchPref(Prefs.ENABLED, "Pointer mode", true);
        hint("Toggle with Alt + Space (change it under Keys), or the Quick Settings tile “Keyboard pointer”.");

        // --- How it works
        section("2. How it works");
        helperNote = text("Pointer mode uses the root / ADB helper (see Advanced at the bottom).");
        LinearLayout outer = root;
        noRootBox = new LinearLayout(this);
        noRootBox.setOrientation(LinearLayout.VERTICAL);
        outer.addView(noRootBox);
        root = noRootBox;
        hint("The Titan reports keyboard touches only to the app that has keyboard focus, so while pointer mode "
                + "is on, TitanPointer holds the focus. Keys can't reach the app underneath until you turn it off.");
        text("• Slide: move the pointer · Tap: click\n"
                + "• Enter: click · Alt + Enter: long press (change under Keys)\n"
                + "• Space: scroll mode on/off\n"
                + "• Alt + Space: pointer mode on/off");
        spinnerPref(Prefs.LETTERS, "Letter keys in pointer mode", new String[] {Prefs.LETTERS_NOTHING, Prefs.LETTERS_EXIT},
                new String[] {"Do nothing", "Turn pointer mode off"}, Prefs.LETTERS_NOTHING);
        button("Open keyboard input probe", v -> startActivity(new Intent(this, ProbeActivity.class)));
        root = outer;

        // --- Orientation
        section("Direction");
        switchPref(Prefs.SWAP, "Swap X and Y", false);
        switchPref(Prefs.INV_X, "Invert horizontal", false);
        switchPref(Prefs.INV_Y, "Invert vertical", false);

        // --- Pointer
        section("Pointer");
        seekPref(Prefs.SENS, "Speed", Prefs.SENS_MIN, Prefs.SENS_MAX, Prefs.SENS_DEFAULT, v -> String.format(Locale.US, "%.1f×", v / 10f));
        seekPref(Prefs.ACCEL, "Acceleration", 0, 40, 10, v -> String.format(Locale.US, "%.1f", v / 10f));
        seekPref(Prefs.YSPEED, "Vertical speed", 3, 40, 10, v -> String.format(Locale.US, "%.1f×", v / 10f));
        seekPref(Prefs.CURSOR_SIZE, "Pointer size", 6, 30, 10, v -> String.format(Locale.US, "%.1f×", v / 10f));
        seekPref(Prefs.AUTOHIDE_S, "Hide pointer when idle", 0, 30, 5, v -> v == 0 ? "never" : v + " s");

        // --- Clicking
        section("Clicking");
        switchPref(Prefs.TAP, "Tap to click", true);
        switchPref(Prefs.TAP_DRAG, "Tap, then touch and move to drag (adds a short click delay)", true);
        seekPref(Prefs.TYPING_MS, "Ignore touches after a key press", 0, 1000, 350, v -> v + " ms");
        seekPref(Prefs.IGNORE_TOP, "Ignore touches that start in the top key row", 0, 40, Prefs.DEFAULT_IGNORE_TOP,
                v -> v == 0 ? "off" : v + "% of the keyboard");
        hint("The top row (Shift, Recents, the home key, Back, Alt) is part of the touch surface. Touches that "
                + "start there don't move the pointer or click; a slide that starts lower can still run into it.");

        // --- The Titan's scroll assistant
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
        gcopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("adb", ScrollAssist.GRANT_COMMAND));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        grant.addView(gcopy);
        clickFixSwitch = switchPref(Prefs.CLICK_FIX, "Make keyboard taps click buttons", true);
        hint("After a keyboard touch the Titan refuses taps on ordinary buttons (toolbars, address bars) until "
                + "a key is pressed. This turns the scroll assistant off for a moment at each tap click, then back on.");
        followSwitch = switchPref(Prefs.ASSIST_FOLLOWS_POINTER, "Turn the scroll assistant off when pointer mode is off", false);
        hint("The keyboard stops scrolling pages while pointer mode is off. Turning pointer mode on turns it back "
                + "on, since the pointer needs it. If you turn the TitanPointer service off, the scroll assistant "
                + "stays as it was.");

        // --- Scrolling
        section("Scrolling (Space turns scroll mode on/off)");
        hint("In scroll mode, slides scroll and taps never click.");
        seekPref(Prefs.SCROLL, "Scroll speed", 2, 60, 15, v -> String.format(Locale.US, "%.1f×", v / 10f));
        switchPref(Prefs.INV_SCROLL, "Reverse scroll direction", false);
        switchPref(Prefs.FLING, "Fling: a quick flick keeps scrolling", true);
        hint("Flick and lift while still moving to keep scrolling; stop before lifting to stop dead.");

        // --- Keys
        section("Keys");
        keyBinding("Pointer mode on/off", Prefs.TOGGLE_KEY, Prefs.DEFAULT_TOGGLE_KEY, Prefs.TOGGLE_META, Prefs.DEFAULT_TOGGLE_META);
        hint("Works everywhere. The key is swallowed while it toggles pointer mode, so pick a combination "
                + "you don't type with.");
        keyBinding("Click", Prefs.CLICK_KEY, Prefs.DEFAULT_CLICK_KEY, Prefs.CLICK_META, Prefs.DEFAULT_CLICK_META);
        keyBinding("Long press", Prefs.LONG_KEY, Prefs.DEFAULT_LONG_KEY, Prefs.LONG_META, Prefs.DEFAULT_LONG_META);
        hint("Click and long press act at the pointer while pointer mode is on.");

        // --- Gestures
        section("Gestures");
        text("• Slide: move the pointer\n"
                + "• Tap: click (tap)\n"
                + "• Tap twice: double tap\n"
                + "• Tap, then touch again and move: drag\n"
                + "• Space, then slide: scroll\n"
                + "• Typing is ignored automatically");

        // --- Advanced: root / ADB
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
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("adb", adbCommand.getText()));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        box.addView(copy);

        linkStatus = text("");
        linkStatus.setTypeface(Typeface.DEFAULT_BOLD);

        // --- Device
        section("Pick the keyboard touch device");
        deviceText = text("");
        button("Find devices", v -> {
            Toast.makeText(this, "Scanning input devices…", Toast.LENGTH_SHORT).show();
            link.requestDeviceList();
        });
        grabSwitch = switchPref(Prefs.GRAB, "Exclusive access while pointer mode is on", false);
        hint("Stops Android and the Titan's own keyboard scrolling from also reacting to your swipes. "
                + "Don't use it if the chosen device is also the screen or the keys (the app warns you).");

        // --- Test
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

        // --- Diagnostics (collapsed unless capturing is on)
        final LinearLayout diagOuter = root;
        root = collapsible("Diagnostics", prefs.getBoolean(Prefs.DIAG, false));
        hint("What the pointer saw recently: focus changes, which windows opened, keys and where they came from. "
                + "If the pointer stops working somewhere, reproduce it, come back here and copy this.");
        Switch diagSwitch = switchPref(Prefs.DIAG, "Capture diagnostics", false);
        diagSwitch.setOnCheckedChangeListener((v, on) -> {
            if (prefs.getBoolean(Prefs.DIAG, false) != on) prefs.edit().putBoolean(Prefs.DIAG, on).apply();
            Diag.setEnabled(on);
            refresh();
        });
        button("Search phone software for the scroll switch", v -> {
            Diag.setEnabled(true);
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
        button("Open tap test screen", v -> startActivity(new Intent(this, TapTestActivity.class)));
        switchPref(Prefs.TAP_DELAY_TEST, "Test: taps on the keyboard click 1 s late", false);
        switchPref(Prefs.KEY_TIMER_TEST, "Test: send the click key's click from a timer", false);
        hint("For tracking down clicks that don't work. Leave off normally.");
        diagText = text("");
        diagText.setTypeface(Typeface.MONOSPACE);
        diagText.setTextSize(11);
        diagText.setTextIsSelectable(true);
        LinearLayout dr = new LinearLayout(this);
        Button copyDiag = new Button(this);
        copyDiag.setText("Copy");
        copyDiag.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("TitanPointer diagnostics",
                    "TitanPointer " + BuildConfig.VERSION_NAME + "\n" + Diag.dump()));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
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
        root = diagOuter;
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
        assistStatus.setText(granted ? "✓ TitanPointer can switch the scroll assistant (currently "
                + (ScrollAssist.isOn(this) ? "on" : "off") + ")"
                : "✗ TitanPointer isn't allowed to switch the scroll assistant yet.");
        assistGrantBox.setVisibility(granted ? View.GONE : View.VISIBLE);
        clickFixSwitch.setEnabled(granted);
        followSwitch.setEnabled(granted);

        for (Runnable r : keyLabels) r.run();
        if (diagText != null) {
            boolean on = Diag.isEnabled();
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

    private void hint(String s) {
        TextView t = text(s);
        t.setTextSize(13);
        t.setAlpha(0.7f);
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

    private void seekPref(final String key, final String label, final int min, int max, int def, final Fmt fmt) {
        final TextView t = new TextView(this);
        t.setTextSize(15);
        t.setPadding(0, (int) (8 * dp), 0, 0);
        root.addView(t);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        int cur = Math.max(min, Math.min(max, prefs.getInt(key, def)));
        sb.setProgress(cur - min);
        t.setText(label + ": " + fmt.f(cur));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                t.setText(label + ": " + fmt.f(p + min));
                if (user) prefs.edit().putInt(key, p + min).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sb);
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
