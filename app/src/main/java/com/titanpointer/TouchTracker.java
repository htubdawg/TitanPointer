package com.titanpointer;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns raw evdev events (type, code, value) into touch frames.
 * Supports multitouch protocol B (slots), protocol A (SYN_MT_REPORT), and
 * plain single-touch devices (ABS_X / ABS_Y / BTN_TOUCH).
 */
public final class TouchTracker {
    public static final int EV_SYN = 0, EV_KEY = 1, EV_ABS = 3;
    public static final int SYN_REPORT = 0, SYN_MT_REPORT = 2, SYN_DROPPED = 3;
    public static final int ABS_X = 0x00, ABS_Y = 0x01;
    public static final int ABS_MT_SLOT = 0x2f, ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36,
            ABS_MT_TRACKING_ID = 0x39;
    public static final int BTN_TOUCH = 0x14a;

    /** A finger on the pad, in raw device units. */
    public static final class Contact {
        public final int id;
        public final int x, y;

        public Contact(int id, int x, int y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }
    }

    public interface Listener {
        /** Called on every SYN_REPORT with the fingers currently down. */
        void onFrame(List<Contact> contacts);
    }

    private static final int MAX_SLOTS = 16;

    private final Listener listener;
    // Protocol B state
    private final int[] slotId = new int[MAX_SLOTS];
    private final int[] slotX = new int[MAX_SLOTS];
    private final int[] slotY = new int[MAX_SLOTS];
    private int slot = 0;
    private boolean sawSlots = false;
    // Protocol A state
    private boolean protocolA = false;
    private final List<Contact> pendingA = new ArrayList<>();
    private int aX = -1, aY = -1;
    private boolean aHasPos = false;
    // Single touch state
    private int stX = 0, stY = 0;
    private boolean stTouch = false;
    private boolean sawMt = false;
    // Dropped events: ignore until next SYN_REPORT
    private boolean dropping = false;

    public TouchTracker(Listener listener) {
        this.listener = listener;
        reset();
    }

    public void reset() {
        for (int i = 0; i < MAX_SLOTS; i++) slotId[i] = -1;
        slot = 0;
        pendingA.clear();
        aHasPos = false;
        stTouch = false;
        dropping = false;
    }

    public void setSlot(int s) {
        if (s >= 0 && s < MAX_SLOTS) slot = s;
    }

    public void onEvent(int type, int code, int value) {
        if (type == EV_SYN) {
            if (code == SYN_DROPPED) {
                dropping = true;
                return;
            }
            if (code == SYN_MT_REPORT) {
                protocolA = true;
                if (aHasPos) pendingA.add(new Contact(pendingA.size(), aX, aY));
                aHasPos = false;
                return;
            }
            if (code == SYN_REPORT) {
                if (dropping) {
                    // State is unreliable after a drop: treat as all fingers lifted.
                    dropping = false;
                    reset();
                    listener.onFrame(new ArrayList<Contact>());
                    return;
                }
                listener.onFrame(buildFrame());
            }
            return;
        }
        if (dropping) return;
        if (type == EV_ABS) {
            switch (code) {
                case ABS_MT_SLOT:
                    sawSlots = true;
                    sawMt = true;
                    setSlot(value);
                    break;
                case ABS_MT_TRACKING_ID:
                    sawMt = true;
                    slotId[slot] = value;
                    break;
                case ABS_MT_POSITION_X:
                    sawMt = true;
                    slotX[slot] = value;
                    aX = value;
                    aHasPos = true;
                    break;
                case ABS_MT_POSITION_Y:
                    sawMt = true;
                    slotY[slot] = value;
                    aY = value;
                    aHasPos = true;
                    break;
                case ABS_X:
                    stX = value;
                    break;
                case ABS_Y:
                    stY = value;
                    break;
                default:
                    break;
            }
        } else if (type == EV_KEY && code == BTN_TOUCH) {
            stTouch = value != 0;
        }
    }

    private List<Contact> buildFrame() {
        List<Contact> out = new ArrayList<>();
        if (protocolA && !sawSlots) {
            out.addAll(pendingA);
            pendingA.clear();
            if (out.isEmpty() && stTouch && !sawMt) out.add(new Contact(0, stX, stY));
            return out;
        }
        if (sawMt) {
            for (int i = 0; i < MAX_SLOTS; i++) {
                if (slotId[i] >= 0) out.add(new Contact(slotId[i], slotX[i], slotY[i]));
            }
            // Some drivers send tracking IDs without slots (single slot 0); handled above.
            return out;
        }
        if (stTouch) out.add(new Contact(0, stX, stY));
        return out;
    }
}
