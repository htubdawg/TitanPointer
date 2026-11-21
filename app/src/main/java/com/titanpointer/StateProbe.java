package com.titanpointer;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Diagnostics for the tap test: a snapshot of every simple value that could decide whether a view accepts
 * a touch (the view's and its window's internal fields, static fields of the input/view classes, system
 * properties and settings), so two touches can be compared: one the view refused and one it accepted.
 */
final class StateProbe {
    private static boolean exempted = false;

    private static final String[] STATIC_CLASSES = {
            "android.view.View", "android.view.ViewGroup", "android.view.ViewRootImpl", "android.widget.TextView",
            "android.view.MotionEvent", "android.view.KeyEvent", "android.view.ViewConfiguration",
            "android.view.InputEventReceiver", "android.view.InputDevice", "android.hardware.input.InputManager",
            "android.view.WindowManagerGlobal", "android.view.inputmethod.InputMethodManager",
            "android.view.Choreographer", "android.view.ViewRootImpl$ViewPostImeInputStage",
            "android.view.ViewRootImpl$EarlyPostImeInputStage", "android.app.Activity", "android.view.Window",
            "com.android.internal.policy.PhoneWindow", "com.android.internal.policy.DecorView",
    };

    private StateProbe() {}

    /** Allows reading internal fields (diagnostics only; works on Android 10). */
    static void exempt() {
        if (exempted) return;
        exempted = true;
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntime, "getRuntime", null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(vmRuntime, "setHiddenApiExemptions",
                    new Class[] {String[].class});
            setExemptions.invoke(getRuntime.invoke(null), new Object[] {new String[] {"L"}});
            TapTestActivity.log("state probe: internal fields readable");
        } catch (Throwable t) {
            TapTestActivity.log("state probe: internal fields may be hidden (" + t + ")");
        }
    }

    static Map<String, String> snapshot(Context c, View v) {
        exempt();
        Map<String, String> m = new TreeMap<>();
        instanceFields(m, "view.", v, View.class.getSuperclass());
        Object attachInfo = field(v, View.class, "mAttachInfo");
        instanceFields(m, "attachInfo.", attachInfo, Object.class);
        Object vri = attachInfo == null ? null : field(attachInfo, attachInfo.getClass(), "mViewRootImpl");
        instanceFields(m, "viewRoot.", vri, Object.class);
        View decor = v.getRootView();
        instanceFields(m, "decor.", decor, View.class.getSuperclass());
        for (String name : STATIC_CLASSES) {
            try {
                staticFields(m, "static." + name.substring(name.lastIndexOf('.') + 1) + ".", Class.forName(name));
            } catch (Throwable ignored) {
            }
        }
        try {
            Object imm = c.getSystemService(Context.INPUT_METHOD_SERVICE);
            instanceFields(m, "imm.", imm, Object.class);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> im = Class.forName("android.hardware.input.InputManager");
            Object inst = im.getDeclaredMethod("getInstance").invoke(null);
            instanceFields(m, "inputManager.", inst, Object.class);
        } catch (Throwable ignored) {
        }
        props(m);
        settings(c, m, "settings.system.", Settings.System.CONTENT_URI);
        settings(c, m, "settings.global.", Settings.Global.CONTENT_URI);
        settings(c, m, "settings.secure.", Settings.Secure.CONTENT_URI);
        return m;
    }

    /** Lines describing what differs between two snapshots. */
    static List<String> diff(Map<String, String> a, Map<String, String> b) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : b.entrySet()) {
            String old = a.get(e.getKey());
            if (old == null) out.add(e.getKey() + ": (new) " + e.getValue());
            else if (!old.equals(e.getValue())) out.add(e.getKey() + ": " + old + " -> " + e.getValue());
        }
        for (String k : a.keySet()) if (!b.containsKey(k)) out.add(k + ": " + a.get(k) + " -> (gone)");
        return out;
    }

    // ---------------------------------------------------------------- internals

    private static Object field(Object o, Class<?> cls, String name) {
        if (o == null) return null;
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void instanceFields(Map<String, String> m, String prefix, Object o, Class<?> stopAt) {
        if (o == null) return;
        for (Class<?> k = o.getClass(); k != null && k != stopAt && k != Object.class; k = k.getSuperclass()) {
            Field[] fs;
            try {
                fs = k.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fs) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                put(m, prefix + k.getSimpleName() + "." + f.getName(), f, o);
            }
        }
    }

    private static void staticFields(Map<String, String> m, String prefix, Class<?> k) {
        Field[] fs;
        try {
            fs = k.getDeclaredFields();
        } catch (Throwable t) {
            return;
        }
        for (Field f : fs) {
            int mod = f.getModifiers();
            if (!Modifier.isStatic(mod) || Modifier.isFinal(mod) && f.getType().isPrimitive()) continue;
            put(m, prefix + f.getName(), f, null);
        }
    }

    private static void put(Map<String, String> m, String key, Field f, Object o) {
        Class<?> t = f.getType();
        if (!(t.isPrimitive() || t == String.class || t == Boolean.class || t == Integer.class || t == Long.class
                || t == Float.class || t.isEnum())) {
            return;
        }
        try {
            f.setAccessible(true);
            m.put(key, String.valueOf(f.get(o)));
        } catch (Throwable ignored) {
        }
    }

    private static void props(Map<String, String> m) {
        try {
            Process p = Runtime.getRuntime().exec("getprop");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.indexOf("]: [");
                if (line.startsWith("[") && i > 0 && line.endsWith("]")) {
                    m.put("prop." + line.substring(1, i), line.substring(i + 4, line.length() - 1));
                }
            }
            r.close();
        } catch (Throwable ignored) {
        }
    }

    private static void settings(Context c, Map<String, String> m, String prefix, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[] {"name", "value"}, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) m.put(prefix + cur.getString(0), String.valueOf(cur.getString(1)));
        } catch (Throwable ignored) {
        }
    }
}
