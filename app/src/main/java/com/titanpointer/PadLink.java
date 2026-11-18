package com.titanpointer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-wide connection to the privileged titanpad helper.
 *
 * Root mode: runs "su -c &lt;nativeLibDir&gt;/libtitanpad.so stdio" and talks over its stdin/stdout.
 * ADB mode: connects to a helper started from a computer with "titanpad serve &lt;port&gt; &lt;token&gt;".
 *
 * Owners (the accessibility service, the test screen) declare whether they want the device open;
 * the link connects, opens, grabs and reconnects as needed. All callbacks arrive on the main thread.
 */
public final class PadLink {
    private static final String TAG = "TitanPointer";

    public interface Listener {
        default void onStatus(String status, boolean ok) {}
        default void onDeviceOpened(int xMin, int xMax, int yMin, int yMax, int slots) {}
        default void onDeviceList(List<InputDeviceInfo> devices) {}
        /** A batch of raw events ending with SYN_REPORT; triples of (type, code, value). */
        default void onEvents(int[] events, int count) {}
        default void onSlot(int slot) {}
    }

    private static PadLink instance;

    public static synchronized PadLink get(Context c) {
        if (instance == null) instance = new PadLink(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> openOwners = new HashSet<>();

    // Main-thread state
    private boolean wantGrab = false;
    private boolean listRequested = false;
    private String status = "Not connected";
    private boolean statusOk = false;
    private int[] lastRange; // xMin, xMax, yMin, yMax, slots

    // Connection (guarded by this)
    private Conn conn;
    private int generation = 0;

    private PadLink(Context app) {
        this.app = app;
    }

    public void addListener(Listener l) {
        listeners.add(l);
        l.onStatus(status, statusOk);
        if (lastRange != null) l.onDeviceOpened(lastRange[0], lastRange[1], lastRange[2], lastRange[3], lastRange[4]);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public String helperPath() {
        return app.getApplicationInfo().nativeLibraryDir + "/libtitanpad.so";
    }

    /** Declare whether {@code owner} needs the touch device open. */
    public void setOpen(String owner, boolean open) {
        boolean changed = open ? openOwners.add(owner) : openOwners.remove(owner);
        if (changed) sync();
    }

    /** Exclusive grab: stops Android (and the Titan's own keyboard scrolling) from seeing the touches. */
    public void setGrab(boolean grab) {
        if (wantGrab == grab) return;
        wantGrab = grab;
        Conn c = conn;
        if (c != null && c.deviceOpen) c.send("grab " + (grab ? 1 : 0));
    }

    /** Ask the helper for the list of input devices; result via {@link Listener#onDeviceList}. */
    public void requestDeviceList() {
        listRequested = true;
        Conn c = conn;
        if (c != null && c.ready) {
            if (!c.listSent && !c.resolving) {
                c.listSent = true;
                c.send("list");
            }
        } else {
            sync();
        }
    }

    /** Re-apply settings (mode, device) by reconnecting. */
    public void restart() {
        disconnect();
        sync();
    }

    private void sync() {
        boolean need = !openOwners.isEmpty() || listRequested;
        if (need && conn == null) {
            connect();
        } else if (!need && conn != null) {
            disconnect();
        } else if (conn != null && conn.ready) {
            applyDeviceState(conn);
        }
    }

    private void applyDeviceState(Conn c) {
        String dev = Prefs.get(app).getString(Prefs.DEVICE, null);
        boolean wantOpen = !openOwners.isEmpty() && dev != null;
        if (wantOpen && !c.resolved) {
            // eventN numbers can change between boots: look the device up by name first.
            if (!c.resolving) {
                c.resolving = true;
                c.send("list");
            }
            return;
        }
        if (c.resolvedPath != null) dev = c.resolvedPath;
        if (wantOpen && !c.deviceOpen && !c.openPending) {
            c.openPending = true;
            c.send("open " + dev);
        } else if (!wantOpen && (c.deviceOpen || c.openPending)) {
            c.deviceOpen = false;
            c.openPending = false;
            c.send("close");
        }
        if (listRequested && !c.listSent) {
            c.listSent = true;
            c.send("list");
        }
        if (!wantOpen && dev == null && !openOwners.isEmpty()) setStatus("Connected. Pick the keyboard touch device.", true);
    }

    private void connect() {
        final int gen = ++generation;
        final Conn c = new Conn(gen);
        conn = c;
        final String mode = Prefs.get(app).getString(Prefs.MODE, "root");
        setStatus(mode.equals("adb") ? "Connecting to ADB helper…" : "Requesting root…", false);
        Thread t = new Thread(() -> c.run(mode), "titanpad-link");
        t.setDaemon(true);
        t.start();
    }

    private void disconnect() {
        Conn c = conn;
        conn = null;
        generation++;
        lastRange = null;
        if (c != null) c.close();
        setStatus("Not connected", false);
    }

    private void setStatus(String s, boolean ok) {
        status = s;
        statusOk = ok;
        for (Listener l : listeners) l.onStatus(s, ok);
    }

    // ---- Called on main thread from the reader ----

    private void onLine(Conn c, String line) {
        if (c != conn) return;
        char k = line.isEmpty() ? '?' : line.charAt(0);
        switch (k) {
            case 'H':
                if (c.isAdb) {
                    c.send("auth " + Prefs.adbToken(app));
                } else {
                    onReady(c, line);
                }
                break;
            case 'A':
                if (line.startsWith("A ok")) onReady(c, line);
                else setStatus("ADB helper rejected the token. Re-run the command shown in the app.", false);
                break;
            case 'D':
                InputDeviceInfo d = InputDeviceInfo.parse(line);
                if (d != null) c.list.add(d);
                break;
            case 'L':
                List<InputDeviceInfo> l = new ArrayList<>(c.list);
                c.list.clear();
                if (c.resolving) {
                    c.resolving = false;
                    c.resolved = true;
                    c.resolvedPath = resolve(l);
                    if (listRequested) c.listSent = true; // this reply also answers the user's request
                    applyDeviceState(c);
                    if (!listRequested) break;
                }
                listRequested = false;
                c.listSent = false;
                for (Listener x : listeners) x.onDeviceList(l);
                if (openOwners.isEmpty()) disconnect();
                break;
            case 'O':
                c.openPending = false;
                if (line.startsWith("O ok")) {
                    String[] f = line.split(" ");
                    try {
                        int[] r = new int[] {Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]),
                                Integer.parseInt(f[5]), Integer.parseInt(f[6])};
                        lastRange = r;
                        c.deviceOpen = true;
                        String name = Prefs.get(app).getString(Prefs.DEVICE_NAME, "device");
                        setStatus("Reading " + name + (c.isAdb ? " (ADB)" : " (root)"), true);
                        if (wantGrab) c.send("grab 1");
                        for (Listener x : listeners) x.onDeviceOpened(r[0], r[1], r[2], r[3], r[4]);
                    } catch (RuntimeException e) {
                        setStatus("Bad reply from helper: " + line, false);
                    }
                } else {
                    c.deviceOpen = false;
                    lastRange = null;
                    setStatus("Could not open device: " + line.substring(Math.min(line.length(), 6)), false);
                }
                break;
            case 'S':
                try {
                    int s = Integer.parseInt(line.substring(2).trim());
                    for (Listener x : listeners) x.onSlot(s);
                } catch (RuntimeException ignored) {
                }
                break;
            case 'G':
                if (line.startsWith("G err")) setStatus("Grab failed: " + line.substring(6), true);
                break;
            default:
                Log.d(TAG, "helper: " + line);
        }
    }

    /** Path of the saved device, found by name (falls back to the saved path). */
    private String resolve(List<InputDeviceInfo> l) {
        String path = Prefs.get(app).getString(Prefs.DEVICE, null);
        String name = Prefs.get(app).getString(Prefs.DEVICE_NAME, null);
        if (name == null) return path;
        for (InputDeviceInfo d : l) if (d.path.equals(path) && d.name.equals(name)) return path;
        for (InputDeviceInfo d : l) if (d.name.equals(name)) return d.path;
        return path;
    }

    private void onReady(Conn c, String hello) {
        c.ready = true;
        setStatus("Connected (" + hello.substring(2) + ")", true);
        applyDeviceState(c);
    }

    private void onClosed(Conn c, String why) {
        if (c != conn) return;
        conn = null;
        lastRange = null;
        setStatus(why, false);
        if (!openOwners.isEmpty()) {
            final int gen = generation;
            main.postDelayed(() -> {
                if (gen == generation && conn == null) sync();
            }, 3000);
        } else {
            listRequested = false;
            for (Listener x : listeners) x.onDeviceList(null);
        }
    }

    /** One live connection. */
    private final class Conn {
        final int gen;
        boolean isAdb;
        volatile boolean ready, closed;
        boolean deviceOpen, openPending, resolving, resolved, listSent;
        String resolvedPath;
        final List<InputDeviceInfo> list = new ArrayList<>();
        private Process proc;
        private Socket sock;
        private OutputStream out;

        Conn(int gen) {
            this.gen = gen;
        }

        void run(String mode) {
            isAdb = mode.equals("adb");
            String why;
            try {
                InputStream in;
                if (isAdb) {
                    sock = new Socket();
                    sock.connect(new InetSocketAddress("127.0.0.1", Prefs.adbPort(app)), 2000);
                    sock.setTcpNoDelay(true);
                    in = sock.getInputStream();
                    out = sock.getOutputStream();
                } else {
                    proc = new ProcessBuilder("su", "-c", helperPath() + " stdio").start();
                    in = proc.getInputStream();
                    out = proc.getOutputStream();
                    drainQuietly(proc.getErrorStream());
                }
                if (closed) return;
                readLoop(in);
                why = isAdb ? "ADB helper disconnected" : "Root helper exited (root denied?)";
                if (!isAdb && !ready) why = "Root access was denied or su is missing.";
            } catch (IOException e) {
                why = isAdb ? "ADB helper not running. Start it from your computer (see Setup)."
                        : "Root not available: " + e.getMessage();
            }
            final String w = why;
            close();
            main.post(() -> onClosed(this, w));
        }

        private void readLoop(InputStream in) throws IOException {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 8192);
            int[] batch = new int[3 * 64];
            int n = 0;
            String line;
            while (!closed && (line = r.readLine()) != null) {
                if (line.startsWith("E ")) {
                    int a = 2, b = line.indexOf(' ', a), cc = line.indexOf(' ', b + 1);
                    if (b < 0 || cc < 0) continue;
                    int type, code, val;
                    try {
                        type = Integer.parseInt(line.substring(a, b));
                        code = Integer.parseInt(line.substring(b + 1, cc));
                        val = Integer.parseInt(line.substring(cc + 1));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (n + 3 > batch.length) batch = java.util.Arrays.copyOf(batch, batch.length * 2);
                    batch[n++] = type;
                    batch[n++] = code;
                    batch[n++] = val;
                    if (type == TouchTracker.EV_SYN && code == TouchTracker.SYN_REPORT) {
                        final int[] ev = java.util.Arrays.copyOf(batch, n);
                        final int count = n / 3;
                        n = 0;
                        main.post(() -> {
                            if (this != conn) return;
                            for (Listener x : listeners) x.onEvents(ev, count);
                        });
                    }
                } else {
                    final String l = line;
                    main.post(() -> onLine(this, l));
                }
            }
        }

        void send(final String cmd) {
            writer.execute(() -> {
                try {
                    OutputStream o = out;
                    if (o == null || closed) return;
                    o.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                    o.flush();
                } catch (IOException e) {
                    Log.w(TAG, "send failed: " + e);
                }
            });
        }

        void close() {
            if (closed) return;
            closed = true;
            ready = false;
            // Closing stdin / the socket makes the helper release the grab and close the device.
            closeQuietly(out);
            closeQuietly(sock);
            if (proc != null) proc.destroy();
        }
    }

    private static void drainQuietly(final InputStream err) {
        Thread t = new Thread(() -> {
            byte[] b = new byte[512];
            try {
                while (err.read(b) >= 0) { /* discard */ }
            } catch (IOException ignored) {
            }
        }, "titanpad-stderr");
        t.setDaemon(true);
        t.start();
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
