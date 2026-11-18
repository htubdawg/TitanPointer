package com.titanpointer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** The on-screen arrow. Its hotspot (the arrow tip) sits at ({@link #MARGIN}, {@link #MARGIN}) dp. */
public final class CursorView extends View {
    public static final int MARGIN = 3; // dp
    public static final int STATE_NORMAL = 0, STATE_PRESSED = 1, STATE_SCROLL = 2;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final float density;
    private float scale = 1f;
    private int state = STATE_NORMAL;

    public CursorView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        fill.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        setScale(1f);
    }

    public void setScale(float s) {
        scale = s;
        float u = density * scale;
        float m = MARGIN * density;
        arrow.reset();
        // Classic arrow, tip at (m, m); coordinates in "arrow units" of 1dp * scale.
        float[][] pts = {{0, 0}, {0, 17}, {4.2f, 13.2f}, {7, 19.5f}, {9.6f, 18.4f}, {6.9f, 12.2f}, {12.3f, 12.2f}};
        arrow.moveTo(m + pts[0][0] * u, m + pts[0][1] * u);
        for (int i = 1; i < pts.length; i++) arrow.lineTo(m + pts[i][0] * u, m + pts[i][1] * u);
        arrow.close();
        stroke.setStrokeWidth(1.4f * density);
        ring.setStrokeWidth(2f * density);
        requestLayout();
        invalidate();
    }

    public void setState(int s) {
        if (s != state) {
            state = s;
            invalidate();
        }
    }

    public int sizePx() {
        return (int) Math.ceil((MARGIN * 2 + 24 * scale) * density);
    }

    public int hotspotPx() {
        return (int) (MARGIN * density);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = sizePx();
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (state != STATE_NORMAL) {
            float m = MARGIN * density;
            ring.setColor(state == STATE_PRESSED ? 0xCC2196F3 : 0xCC4CAF50);
            c.drawCircle(m, m, 2.5f * density * scale, ring);
        }
        c.drawPath(arrow, fill);
        c.drawPath(arrow, stroke);
    }
}
