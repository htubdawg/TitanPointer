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

    private TextView accessibilityStatus, linkStatus, deviceText, adbCommand, toggleKeyText;
    private Button accessibilityButton;
    private Switch enabledSwitch, grabSwitch, zoneSwitch;
    private View adbBox;
    private TestPadView testPad;
    private Button calibrateButton;
    private TouchTracker testTracker;
    private int screenW, screenH;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
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
        link.setOpen(OWNER, prefs.getString(Prefs.DEVICE, null) != null);
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
        section("No-root test");
        hint("Checks what the Titan's keyboard sends to ordinary apps, with no root or ADB. Copy the results and share them.");
        button("Open keyboard input probe", v -> startActivity(new Intent(this, ProbeActivity.class)));

        // --- Status
        section("1. Turn on the service");
        accessibilityStatus = text("");
        accessibilityButton = button("Open Accessibility settings", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        enabledSwitch = switchPref(Prefs.ENABLED, "Pointer mode", true);
        hint("Toggle quickly with the Quick Settings tile “Keyboard pointer” or a shortcut key (below).");

        // --- Connection
        section("2. Give it access to the keyboard sensor");
        hint("Android only lets privileged code read the raw touch sensor, so a tiny helper reads it for the app. "
                + "Use root if your Titan is rooted (Magisk), otherwise start the helper once per boot over ADB.");
        RadioGroup rg = new RadioGroup(this);
        RadioButton rRoot = new RadioButton(this);
        rRoot.setText("Root (su)");
        rRoot.setId(View.generateViewId());
        RadioButton rAdb = new RadioButton(this);
        rAdb.setText("ADB helper (no root)");
        rAdb.setId(View.generateViewId());
        rg.addView(rRoot);
        rg.addView(rAdb);
        boolean adb = "adb".equals(prefs.getString(Prefs.MODE, "root"));
        rg.check(adb ? rAdb.getId() : rRoot.getId());
        rg.setOnCheckedChangeListener((g, id) -> {
            prefs.edit().putString(Prefs.MODE, id == rAdb.getId() ? "adb" : "root").apply();
            refresh();
            link.restart();
        });
        root.addView(rg);

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
        section("3. Pick the keyboard touch device");
        deviceText = text("");
        button("Find devices", v -> {
            Toast.makeText(this, "Scanning input devices…", Toast.LENGTH_SHORT).show();
            link.requestDeviceList();
        });
        grabSwitch = switchPref(Prefs.GRAB, "Exclusive access while pointer mode is on", false);
        hint("Stops Android and the Titan's own keyboard scrolling from also reacting to your swipes. "
                + "Don't use it if the chosen device is also the screen or the keys (the app warns you).");

        // --- Test
        section("4. Test & calibrate");
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

        // --- Orientation
        section("Direction");
        switchPref(Prefs.SWAP, "Swap X and Y", false);
        switchPref(Prefs.INV_X, "Invert horizontal", false);
        switchPref(Prefs.INV_Y, "Invert vertical", false);

        // --- Pointer
        section("Pointer");
        seekPref(Prefs.SENS, "Speed", 2, 80, 20, v -> String.format(Locale.US, "%.1f×", v / 10f));
        seekPref(Prefs.ACCEL, "Acceleration", 0, 40, 10, v -> String.format(Locale.US, "%.1f", v / 10f));
        seekPref(Prefs.YSPEED, "Vertical speed", 3, 40, 10, v -> String.format(Locale.US, "%.1f×", v / 10f));
        seekPref(Prefs.CURSOR_SIZE, "Pointer size", 6, 30, 10, v -> String.format(Locale.US, "%.1f×", v / 10f));
        seekPref(Prefs.AUTOHIDE_S, "Hide pointer when idle", 0, 30, 5, v -> v == 0 ? "never" : v + " s");

        // --- Clicking
        section("Clicking");
        switchPref(Prefs.TAP, "Tap to click", true);
        switchPref(Prefs.TAP_DRAG, "Tap, then touch and move to drag (adds a short click delay)", true);
        seekPref(Prefs.TYPING_MS, "Ignore touches after a key press", 0, 1000, 350, v -> v + " ms");
        spinnerPref(Prefs.THREE_FINGER, "Three-finger tap", new String[] {"back", "home", "recents", "notifications", "none"},
                new String[] {"Back", "Home", "Recent apps", "Notifications", "Nothing"}, "back");

        // --- Scrolling
        section("Scrolling (two fingers)");
        seekPref(Prefs.SCROLL, "Scroll speed", 2, 60, 15, v -> String.format(Locale.US, "%.1f×", v / 10f));
        switchPref(Prefs.INV_SCROLL, "Reverse scroll direction", false);

        // --- Shortcut
        section("Toggle shortcut");
        toggleKeyText = text("");
        LinearLayout r2 = new LinearLayout(this);
        Button set = new Button(this);
        set.setText("Set key");
        set.setOnClickListener(v -> captureToggleKey());
        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setOnClickListener(v -> {
            prefs.edit().putInt(Prefs.TOGGLE_KEY, 0).putInt(Prefs.TOGGLE_META, 0).apply();
            refresh();
        });
        r2.addView(set);
        r2.addView(clear);
        root.addView(r2);
        hint("The key is swallowed while it toggles pointer mode, so pick one you don't type with "
                + "(or a combination such as Alt + a letter).");

        // --- Gestures
        section("Gestures");
        text("• One finger: move the pointer\n"
                + "• Tap: click (tap)\n"
                + "• Tap twice: double tap\n"
                + "• Tap, then touch again and move: drag\n"
                + "• Two fingers: scroll (lift while moving to fling)\n"
                + "• Two-finger tap: long press\n"
                + "• Three-finger tap: Back (configurable)\n"
                + "• Typing is ignored automatically");
    }

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

    private void captureToggleKey() {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Toggle shortcut")
                .setMessage("Press the key to use (hold Alt, Shift or Sym too if you want a combination).")
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        d.setOnKeyListener((di, code, ev) -> {
            if (KeyEvent.isModifierKey(code) || code == KeyEvent.KEYCODE_BACK) return false;
            if (ev.getAction() != KeyEvent.ACTION_UP) return true;
            prefs.edit().putInt(Prefs.TOGGLE_KEY, code)
                    .putInt(Prefs.TOGGLE_META, PointerService.normalizeMeta(ev.getMetaState())).apply();
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

        boolean adb = "adb".equals(prefs.getString(Prefs.MODE, "root"));
        adbBox.setVisibility(adb ? View.VISIBLE : View.GONE);
        adbCommand.setText(adbCommandText());

        String dev = prefs.getString(Prefs.DEVICE, null);
        deviceText.setText(dev == null ? "No device chosen yet. Tap “Find devices”."
                : prefs.getString(Prefs.DEVICE_NAME, "?") + "  (" + dev + ")");
        grabSwitch.setChecked(prefs.getBoolean(Prefs.GRAB, false));
        zoneSwitch.setChecked(prefs.getBoolean(Prefs.ZONE_ON, false));
        testPad.setZone(prefs.getBoolean(Prefs.ZONE_ON, false) ? new int[] {prefs.getInt(Prefs.ZONE_XMIN, 0),
                prefs.getInt(Prefs.ZONE_XMAX, 0), prefs.getInt(Prefs.ZONE_YMIN, 0), prefs.getInt(Prefs.ZONE_YMAX, 0)} : null);

        int key = prefs.getInt(Prefs.TOGGLE_KEY, 0);
        if (key == 0) {
            toggleKeyText.setText("No shortcut set");
        } else {
            int m = prefs.getInt(Prefs.TOGGLE_META, 0);
            StringBuilder sb = new StringBuilder("Shortcut: ");
            if ((m & KeyEvent.META_CTRL_ON) != 0) sb.append("Ctrl + ");
            if ((m & KeyEvent.META_ALT_ON) != 0) sb.append("Alt + ");
            if ((m & KeyEvent.META_SHIFT_ON) != 0) sb.append("Shift + ");
            if ((m & KeyEvent.META_SYM_ON) != 0) sb.append("Sym + ");
            sb.append(KeyEvent.keyCodeToString(key).replace("KEYCODE_", ""));
            toggleKeyText.setText(sb);
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
            Toast.makeText(this, "Could not reach the helper. Check step 2.", Toast.LENGTH_LONG).show();
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
        int cur = prefs.getInt(key, def);
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
