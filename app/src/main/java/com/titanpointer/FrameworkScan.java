package com.titanpointer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Diagnostics: searches the phone's own software (system jars, apps, native libraries, boot images) for the
 * Titan's keyboard-scroll switch ("forcescroll") and logs which code uses it, with the strings and methods
 * that code refers to, so we can see how the phone decides when to turn it on.
 */
final class FrameworkScan {
    interface Progress {
        void on(String s);
    }

    private static final String[] DIRS = {
            "/system/framework", "/system/app", "/system/priv-app", "/system/lib64", "/system/lib",
            "/system/bin", "/system/etc", "/system_ext", "/product", "/vendor/app", "/vendor/framework",
            "/vendor/lib64", "/vendor/etc", "/odm",
    };
    private static final String[] EXT = {".jar", ".apk", ".so", ".vdex", ".odex", ".oat", ".art", ".xml",
            ".prop", ".rc", ".json", ".conf", ".cfg", ""};
    private static final byte[][] NEEDLES = {ascii("forcescroll"), ascii("force_scroll"), ascii("forceScroll")};

    private int filesScanned, hits;
    /** "Class.method" of every method found loading the switch name, to find their callers too. */
    private final Set<String> found = new LinkedHashSet<>();

    /** For tests: report one dex file's uses of the switch name into the log. */
    static void reportDex(byte[] dex) {
        new FrameworkScan().new Dex(dex, 0).report();
    }

    static void run(Progress p) {
        new FrameworkScan().scan(p);
    }

    private void scan(Progress p) {
        long t0 = System.currentTimeMillis();
        log("SCAN: searching the phone's software for the keyboard-scroll switch (forcescroll)…");
        List<File> files = new ArrayList<>();
        for (String d : DIRS) collect(new File(d), files, 0);
        log("SCAN: " + files.size() + " files to check");
        int i = 0;
        for (File f : files) {
            if (++i % 200 == 0) p.on("Checked " + i + " of " + files.size() + " files…");
            try {
                if (!containsNeedle(f)) continue;
                hits++;
                log("SCAN HIT: " + f.getPath() + " (" + f.length() / 1024 + " KB)");
                inspect(f);
            } catch (Throwable t) {
                // unreadable file: skip
            }
        }
        log(String.format(Locale.US, "SCAN done: %d files read, %d contain the switch name, %d s",
                filesScanned, hits, (System.currentTimeMillis() - t0) / 1000));
        p.on("Search done: " + hits + " files mention it. Copy the log.");
    }

    private static void collect(File dir, List<File> out, int depth) {
        if (depth > 6) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) {
                collect(f, out, depth + 1);
            } else if (f.canRead() && f.length() > 0 && f.length() < 400L * 1024 * 1024) {
                String n = f.getName().toLowerCase(Locale.US);
                for (String e : EXT) {
                    if (e.isEmpty() ? n.indexOf('.') < 0 : n.endsWith(e)) {
                        out.add(f);
                        break;
                    }
                }
            }
        }
    }

    /** Streams the file looking for the switch name (case-sensitive variants). */
    private boolean containsNeedle(File f) throws Exception {
        filesScanned++;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 20];
            int keep = 0, n;
            while ((n = in.read(buf, keep, buf.length - keep)) > 0) {
                int len = keep + n;
                for (byte[] nd : NEEDLES) if (indexOf(buf, len, nd, 0) >= 0) return true;
                keep = Math.min(16, len);
                System.arraycopy(buf, len - keep, buf, 0, keep);
            }
        }
        // Compressed zip entries hide the text: check inside jars/apks too.
        String name = f.getName();
        if (name.endsWith(".jar") || name.endsWith(".apk")) {
            try (ZipFile z = new ZipFile(f)) {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory() || e.getSize() > 64L * 1024 * 1024) continue;
                    String en2 = e.getName();
                    if (!(en2.endsWith(".dex") || en2.endsWith(".xml") || en2.endsWith(".arsc"))) continue;
                    byte[] b = readAll(z.getInputStream(e));
                    for (byte[] nd : NEEDLES) if (indexOf(b, b.length, nd, 0) >= 0) return true;
                }
            }
        }
        return false;
    }

    private void inspect(File f) throws Exception {
        String name = f.getName();
        if (name.endsWith(".jar") || name.endsWith(".apk")) {
            try (ZipFile z = new ZipFile(f)) {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory() || e.getSize() > 64L * 1024 * 1024) continue;
                    byte[] b = readAll(z.getInputStream(e));
                    if (!hasNeedle(b)) continue;
                    log("  in " + e.getName());
                    if (e.getName().endsWith(".dex")) new Dex(b, 0).report();
                    else context(b);
                }
            }
            return;
        }
        if (f.length() > 200L * 1024 * 1024) {
            log("  (too big to inspect)");
            return;
        }
        byte[] b = new byte[(int) f.length()];
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            r.readFully(b);
        }
        // Embedded dex files (vdex/odex/oat): find "dex\n03x\0" headers.
        boolean anyDex = false;
        byte[] magic = ascii("dex\n03");
        for (int at = indexOf(b, b.length, magic, 0); at >= 0; at = indexOf(b, b.length, magic, at + 8)) {
            if (at + 0x70 > b.length || b[at + 7] != 0) continue;
            int size = u32(b, at + 0x20);
            if (size <= 0x70 || at + size > b.length) continue;
            if (indexOfAny(b, at, at + size) < 0) continue;
            anyDex = true;
            log("  embedded dex at offset " + at);
            try {
                new Dex(b, at).report();
            } catch (Throwable t) {
                log("  (could not read that dex: " + t + ")");
            }
        }
        if (!anyDex) context(b);
    }

    /** Printable strings around each occurrence (for native libraries and config files). */
    private void context(byte[] b) {
        int shown = 0;
        for (byte[] nd : NEEDLES) {
            for (int at = indexOf(b, b.length, nd, 0); at >= 0 && shown < 6; at = indexOf(b, b.length, nd, at + 1)) {
                shown++;
                Set<String> strs = new LinkedHashSet<>();
                int from = Math.max(0, at - 1500), to = Math.min(b.length, at + 1500);
                StringBuilder cur = new StringBuilder();
                for (int i = from; i < to; i++) {
                    int c = b[i] & 0xff;
                    if (c >= 0x20 && c < 0x7f) {
                        cur.append((char) c);
                    } else {
                        if (cur.length() >= 4) strs.add(cur.toString());
                        cur.setLength(0);
                    }
                }
                log("  nearby text: " + String.join(" | ", strs));
            }
        }
    }

    // ---------------------------------------------------------------- dex reading

    /** Minimal dex reader: finds methods that load a string containing the switch name. */
    private final class Dex {
        private final byte[] b;
        private final int base;
        private final int stringIdsOff, stringIdsSize, typeIdsOff, methodIdsOff, methodIdsSize, fieldIdsOff,
                fieldIdsSize, classDefsOff, classDefsSize;

        Dex(byte[] b, int base) {
            this.b = b;
            this.base = base;
            stringIdsSize = u32(b, base + 0x38);
            stringIdsOff = u32(b, base + 0x3C);
            typeIdsOff = u32(b, base + 0x44);
            fieldIdsSize = u32(b, base + 0x50);
            fieldIdsOff = u32(b, base + 0x54);
            methodIdsSize = u32(b, base + 0x58);
            methodIdsOff = u32(b, base + 0x5C);
            classDefsSize = u32(b, base + 0x60);
            classDefsOff = u32(b, base + 0x64);
        }

        String string(int idx) {
            if (idx < 0 || idx >= stringIdsSize) return "?";
            int off = base + u32(b, base + stringIdsOff + idx * 4);
            int[] p = {off};
            uleb(p); // utf16 length
            int s = p[0], e = s;
            while (e < b.length && b[e] != 0) e++;
            return new String(b, s, e - s, StandardCharsets.UTF_8);
        }

        String type(int idx) {
            String d = string(u32(b, base + typeIdsOff + idx * 4));
            if (d.startsWith("L") && d.endsWith(";")) d = d.substring(1, d.length() - 1).replace('/', '.');
            return d;
        }

        String method(int idx) {
            if (idx < 0 || idx >= methodIdsSize) return "?";
            int o = base + methodIdsOff + idx * 8;
            return type(u16(b, o)) + "." + string(u32(b, o + 4));
        }

        String field(int idx) {
            if (idx < 0 || idx >= fieldIdsSize) return "?";
            int o = base + fieldIdsOff + idx * 8;
            return type(u16(b, o)) + "." + string(u32(b, o + 4));
        }

        void report() {
            Set<Integer> targets = new LinkedHashSet<>();
            for (int i = 0; i < stringIdsSize; i++) {
                String s = string(i);
                String low = s.toLowerCase(Locale.US);
                if (low.contains("forcescroll") || low.contains("force_scroll")) {
                    targets.add(i);
                    log("    string: \"" + s + "\"");
                }
            }
            if (targets.isEmpty()) return;
            int found = 0;
            for (int c = 0; c < classDefsSize && found < 40; c++) {
                int cd = base + classDefsOff + c * 32;
                int classDataOff = u32(b, cd + 24);
                if (classDataOff == 0) continue;
                String cls = type(u32(b, cd));
                int[] p = {base + classDataOff};
                int sf = uleb(p), inf = uleb(p), dm = uleb(p), vm = uleb(p);
                for (int i = 0; i < sf + inf; i++) {
                    uleb(p);
                    uleb(p);
                }
                int midx = 0;
                for (int i = 0; i < dm + vm; i++) {
                    if (i == dm) midx = 0;
                    midx += uleb(p);
                    uleb(p);
                    int codeOff = uleb(p);
                    if (codeOff == 0) continue;
                    if (methodUses(base + codeOff, targets)) {
                        found++;
                        FrameworkScan.this.found.add(method(midx));
                        describeMethod(cls, midx, base + codeOff);
                    }
                }
            }
            if (found == 0) log("    (no method code loads it here)");
            callers();
        }

        /** Methods in this dex that call any method found so far (one level up). */
        private void callers() {
            if (FrameworkScan.this.found.isEmpty()) return;
            int shown = 0;
            for (int c = 0; c < classDefsSize && shown < 40; c++) {
                int cd = base + classDefsOff + c * 32;
                int classDataOff = u32(b, cd + 24);
                if (classDataOff == 0) continue;
                String cls = type(u32(b, cd));
                int[] p = {base + classDataOff};
                int sf = uleb(p), inf = uleb(p), dm = uleb(p), vm = uleb(p);
                for (int i = 0; i < sf + inf; i++) {
                    uleb(p);
                    uleb(p);
                }
                int midx = 0;
                for (int i = 0; i < dm + vm; i++) {
                    if (i == dm) midx = 0;
                    midx += uleb(p);
                    uleb(p);
                    int codeOff = uleb(p);
                    if (codeOff == 0 || FrameworkScan.this.found.contains(method(midx))) continue;
                    if (callsFound(base + codeOff)) {
                        shown++;
                        log("    CALLER of the above:");
                        describeMethod(cls, midx, base + codeOff);
                    }
                }
            }
        }

        private boolean callsFound(int code) {
            int n = u32(b, code + 12), insns = code + 16;
            for (int pc = 0; pc < n; ) {
                int op = u16(b, insns + pc * 2) & 0xff;
                if (((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78))
                        && FrameworkScan.this.found.contains(method(u16(b, insns + pc * 2 + 2)))) {
                    return true;
                }
                pc += width(insns, pc);
            }
            return false;
        }

        private boolean methodUses(int code, Set<Integer> targets) {
            int n = u32(b, code + 12), insns = code + 16;
            for (int pc = 0; pc < n; ) {
                int op = u16(b, insns + pc * 2) & 0xff;
                if (op == 0x1a && targets.contains(u16(b, insns + pc * 2 + 2))) return true;
                if (op == 0x1b && targets.contains(u32(b, insns + pc * 2 + 2))) return true;
                pc += width(insns, pc);
            }
            return false;
        }

        /** The method's name, plus every string, field and method it refers to. */
        private void describeMethod(String cls, int midx, int code) {
            Set<String> strs = new LinkedHashSet<>(), calls = new LinkedHashSet<>(), fields = new LinkedHashSet<>();
            int n = u32(b, code + 12), insns = code + 16;
            for (int pc = 0; pc < n; ) {
                int unit = u16(b, insns + pc * 2), op = unit & 0xff;
                if (op == 0x1a) strs.add(string(u16(b, insns + pc * 2 + 2)));
                else if (op == 0x1b) strs.add(string(u32(b, insns + pc * 2 + 2)));
                else if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)) {
                    calls.add(method(u16(b, insns + pc * 2 + 2)));
                } else if (op >= 0x52 && op <= 0x6d) {
                    fields.add(field(u16(b, insns + pc * 2 + 2)));
                }
                pc += width(insns, pc);
            }
            log("    METHOD " + method(midx) + "  (in " + cls + ", " + n + " code units)");
            log("      strings: " + clip(String.join(" | ", strs)));
            log("      fields: " + clip(String.join(", ", fields)));
            log("      calls: " + clip(String.join(", ", calls)));
        }

        /** Reads an unsigned LEB128 value at p[0] and advances p[0]. */
        int uleb(int[] p) {
            int r = 0, shift = 0, x;
            do {
                x = b[p[0]++] & 0xff;
                r |= (x & 0x7f) << shift;
                shift += 7;
            } while ((x & 0x80) != 0 && shift < 35);
            return r;
        }

        /** Instruction width in 16-bit code units. */
        private int width(int insns, int pc) {
            int unit = u16(b, insns + pc * 2), op = unit & 0xff;
            if (op == 0x00) {
                if (unit == 0x0100) return 4 + u16(b, insns + pc * 2 + 2) * 2;
                if (unit == 0x0200) return 2 + u16(b, insns + pc * 2 + 2) * 4;
                if (unit == 0x0300) {
                    int w = u16(b, insns + pc * 2 + 2);
                    long size = u32(b, insns + pc * 2 + 4) & 0xffffffffL;
                    return 4 + (int) ((size * w + 1) / 2);
                }
                return 1;
            }
            return WIDTH[op];
        }
    }

    private static final int[] WIDTH = new int[256];

    static {
        java.util.Arrays.fill(WIDTH, 1);
        set(0x02, 2); set(0x03, 3); set(0x05, 2); set(0x06, 3); set(0x08, 2); set(0x09, 3);
        set(0x13, 2); set(0x14, 3); set(0x15, 2); set(0x16, 2); set(0x17, 3); set(0x18, 5); set(0x19, 2);
        set(0x1a, 2); set(0x1b, 3); set(0x1c, 2); set(0x1f, 2); set(0x20, 2); set(0x22, 2); set(0x23, 2);
        set(0x24, 3); set(0x25, 3); set(0x26, 3); set(0x29, 2); set(0x2a, 3); set(0x2b, 3); set(0x2c, 3);
        for (int i = 0x2d; i <= 0x3d; i++) set(i, 2);
        for (int i = 0x44; i <= 0x6d; i++) set(i, 2);
        for (int i = 0x6e; i <= 0x72; i++) set(i, 3);
        for (int i = 0x74; i <= 0x78; i++) set(i, 3);
        for (int i = 0x90; i <= 0xaf; i++) set(i, 2);
        for (int i = 0xd0; i <= 0xe2; i++) set(i, 2);
        set(0xfa, 4); set(0xfb, 4); set(0xfc, 3); set(0xfd, 3); set(0xfe, 2); set(0xff, 2);
    }

    private static void set(int op, int w) {
        WIDTH[op] = w;
    }

    // ---------------------------------------------------------------- helpers

    private static void log(String s) {
        Diag.log(s);
    }

    private static String clip(String s) {
        return s.length() > 1500 ? s.substring(0, 1500) + "…" : s;
    }

    private boolean hasNeedle(byte[] b) {
        return indexOfAny(b, 0, b.length) >= 0;
    }

    private static int indexOfAny(byte[] b, int from, int to) {
        for (byte[] nd : NEEDLES) {
            int i = indexOf(b, to, nd, from);
            if (i >= 0) return i;
        }
        return -1;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static int indexOf(byte[] hay, int len, byte[] nd, int from) {
        outer:
        for (int i = Math.max(0, from); i <= len - nd.length; i++) {
            for (int j = 0; j < nd.length; j++) if (hay[i + j] != nd[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
    }

    private static int u32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

}
