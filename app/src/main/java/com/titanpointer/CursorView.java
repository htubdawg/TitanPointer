package com.titanpointer;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.view.View;

/** The on-screen pointer. Its look comes from {@link PointerShape}; the hotspot is where it clicks. */
public final class CursorView extends View {
    public static final int STATE_NORMAL = 0, STATE_PRESSED = 1, STATE_SCROLL = 2;

    private final PointerShape shape;
    private int state = STATE_NORMAL;

    public CursorView(Context c) {
        super(c);
        shape = new PointerShape(c);
    }

    /** Re-reads style, colour, size and the custom image from the settings. */
    public void loadLook(Context c, SharedPreferences p) {
        shape.load(c, p);
        requestLayout();
        invalidate();
    }

    /** Faded while the pointer can't receive touches (another window has keyboard focus). */
    public void setDimmed(boolean d) {
        setAlpha(d ? 0.35f : 1f);
    }

    public void setState(int s) {
        if (s != state) {
            state = s;
            invalidate();
        }
    }

    public int hotspotX() {
        return Math.round(shape.hotX());
    }

    public int hotspotY() {
        return Math.round(shape.hotY());
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension((int) Math.ceil(shape.width()), (int) Math.ceil(shape.height()));
    }

    @Override
    protected void onDraw(Canvas c) {
        shape.draw(c, state);
    }
}
