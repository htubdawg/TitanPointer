package com.titanpointer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shows live raw touches from the selected device, the active zone, and records calibration bounds. */
public final class TestPadView extends View implements TouchTracker.Listener {
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private int xMin = 0, xMax = 1000, yMin = 0, yMax = 1000;
    private boolean haveRange = false;
    private int[] zoneRect; // xmin xmax ymin ymax, or null
    private List<TouchTracker.Contact> contacts = new ArrayList<>();
    private int frames = 0;

    private boolean calibrating = false;
    private int cxMin, cxMax, cyMin, cyMax;
    private boolean calibSeen = false;

    public TestPadView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(density);
        frame.setColor(0xFF888888);
        zone.setStyle(Paint.Style.STROKE);
        zone.setStrokeWidth(2 * density);
        zone.setColor(0xFF4CAF50);
        zone.setPathEffect(new DashPathEffect(new float[] {6 * density, 4 * density}, 0));
        dot.setColor(0xFF2196F3);
        text.setColor(0xFF888888);
        text.setTextSize(12 * density);
    }

    public void setRange(int xMin, int xMax, int yMin, int yMax) {
        this.xMin = xMin;
        this.xMax = Math.max(xMin + 1, xMax);
        this.yMin = yMin;
        this.yMax = Math.max(yMin + 1, yMax);
        haveRange = true;
        requestLayout();
        invalidate();
    }

    public void setZone(int[] z) {
        zoneRect = z;
        invalidate();
    }

    public void startCalibration() {
        calibrating = true;
        calibSeen = false;
        invalidate();
    }

    /** Stops calibrating and returns {xmin, xmax, ymin, ymax}, or null if nothing was touched. */
    public int[] stopCalibration() {
        calibrating = false;
        invalidate();
        return calibSeen ? new int[] {cxMin, cxMax, cyMin, cyMax} : null;
    }

    @Override
    public void onFrame(List<TouchTracker.Contact> c) {
        contacts = new ArrayList<>(c);
        frames++;
        if (calibrating) {
            for (TouchTracker.Contact t : c) {
                if (!calibSeen) {
                    cxMin = cxMax = t.x;
                    cyMin = cyMax = t.y;
                    calibSeen = true;
                }
                cxMin = Math.min(cxMin, t.x);
                cxMax = Math.max(cxMax, t.x);
                cyMin = Math.min(cyMin, t.y);
                cyMax = Math.max(cyMax, t.y);
            }
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        float aspect = (float) (yMax - yMin) / (xMax - xMin);
        int h = (int) Math.max(120 * density, Math.min(320 * density, w * aspect)) + (int) (22 * density);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        float pad = 4 * density;
        RectF r = new RectF(pad, pad, getWidth() - pad, getHeight() - 22 * density);
        frame.setColor(calibrating ? 0xFFFF9800 : 0xFF888888);
        c.drawRect(r, frame);
        if (zoneRect != null) {
            c.drawRect(mapX(r, zoneRect[0]), mapY(r, zoneRect[2]), mapX(r, zoneRect[1]), mapY(r, zoneRect[3]), zone);
        }
        if (calibrating && calibSeen) {
            zone.setColor(0xFFFF9800);
            c.drawRect(mapX(r, cxMin), mapY(r, cyMin), mapX(r, cxMax), mapY(r, cyMax), zone);
            zone.setColor(0xFF4CAF50);
        }
        StringBuilder sb = new StringBuilder();
        for (TouchTracker.Contact t : contacts) {
            c.drawCircle(mapX(r, t.x), mapY(r, t.y), 9 * density, dot);
            sb.append(String.format(Locale.US, "#%d (%d, %d)  ", t.id, t.x, t.y));
        }
        String s;
        if (!haveRange) s = "No device open";
        else if (calibrating) s = "Calibrating: rub the whole keyboard surface…";
        else if (contacts.isEmpty()) s = frames == 0 ? "Touch the keyboard surface…" : "No touches";
        else s = sb.toString();
        c.drawText(s, pad, getHeight() - 6 * density, text);
    }

    private float mapX(RectF r, int x) {
        return r.left + (x - xMin) * r.width() / (xMax - xMin);
    }

    private float mapY(RectF r, int y) {
        return r.top + (y - yMin) * r.height() / (yMax - yMin);
    }
}
