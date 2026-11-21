package com.titanpointer;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.File;

/**
 * Draws a pointer look: a built-in shape in a chosen colour (with a contrasting outline) or the user's
 * own image. Used by the on-screen {@link CursorView} and by the previews in the settings screen.
 *
 * <p>Built-in shapes live in a 24 x 24 unit box (1 unit = 1 dp x size), drawn with a margin around it so the
 * outline and the click ring fit. The hotspot is the point that clicks: the tip for the arrow and the hand,
 * the centre for the others; for a custom image the user picks top-left or centre.
 */
final class PointerShape {
    static final String ARROW = "arrow", HAND = "hand", CROSSHAIR = "crosshair", DOT = "dot", RING = "ring",
            CUSTOM = "custom";
    static final String[] STYLES = {ARROW, HAND, CROSSHAIR, DOT, RING, CUSTOM};
    static final String[] STYLE_NAMES = {"Arrow", "Hand", "Crosshair", "Dot", "Ring", "Custom image"};
    static final int[] COLORS = {Color.WHITE, Color.BLACK, 0xFFFFEB3B, 0xFFFF9800, 0xFFF44336, 0xFFE91E63,
            0xFF4CAF50, 0xFF2196F3};
    static final String HOT_TOP_LEFT = "top_left", HOT_CENTER = "center";
    static final String CUSTOM_FILE = "cursor_custom.png";
    /** Custom images are stored at most this many pixels on their longer side. */
    static final int CUSTOM_MAX_PX = 256;

    private static final float BOX = 24f;
    private static final float MARGIN_DP = 3f;

    private final float density;
    private String style = ARROW;
    private int color = Color.WHITE;
    private float scale = 1f;
    private Bitmap custom;
    private boolean customCentered;

    private final Path path = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private float w, h, hotX, hotY;

    PointerShape(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        rebuild();
    }

    /** Reads the look from the settings (and the custom image file, if that's the style). */
    void load(Context c, SharedPreferences p) {
        String s = p.getString(Prefs.CURSOR_STYLE, ARROW);
        Bitmap b = null;
        if (CUSTOM.equals(s)) {
            b = loadCustom(c);
            if (b == null) s = ARROW; // file missing: fall back
        }
        set(s, p.getInt(Prefs.CURSOR_COLOR, Color.WHITE), p.getInt(Prefs.CURSOR_SIZE, 10) / 10f, b,
                HOT_CENTER.equals(p.getString(Prefs.CURSOR_HOTSPOT, HOT_TOP_LEFT)));
    }

    static Bitmap loadCustom(Context c) {
        File f = new File(c.getFilesDir(), CUSTOM_FILE);
        return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    void set(String style, int color, float scale, Bitmap custom, boolean customCentered) {
        this.style = style;
        this.color = color;
        this.scale = scale;
        this.custom = custom;
        this.customCentered = customCentered;
        rebuild();
    }

    float width() {
        return w;
    }

    float height() {
        return h;
    }

    float hotX() {
        return hotX;
    }

    float hotY() {
        return hotY;
    }

    private boolean centered() {
        return CUSTOM.equals(style) ? customCentered : !(ARROW.equals(style) || HAND.equals(style));
    }

    private void rebuild() {
        float u = density * scale;
        float m = MARGIN_DP * density;
        fill.setColor(color);
        stroke.setColor(isLight(color) ? Color.BLACK : Color.WHITE);
        stroke.setStrokeWidth(1.4f * density);
        ring.setStrokeWidth(2f * density);
        path.reset();

        if (CUSTOM.equals(style) && custom != null) {
            float longest = Math.max(custom.getWidth(), custom.getHeight());
            float k = BOX * u / longest;
            float iw = custom.getWidth() * k, ih = custom.getHeight() * k;
            dst.set(m, m, m + iw, m + ih);
            w = iw + 2 * m;
            h = ih + 2 * m;
            hotX = customCentered ? m + iw / 2 : m;
            hotY = customCentered ? m + ih / 2 : m;
            return;
        }

        w = h = BOX * u + 2 * m;
        switch (style) {
            case HAND:
                handPath(u, m);
                hotX = m + 5 * u;
                hotY = m;
                break;
            case CROSSHAIR: {
                float t = 1.1f; // half the bar thickness
                addRect(1, 12 - t, 9, 12 + t, u, m);
                addRect(15, 12 - t, 23, 12 + t, u, m);
                addRect(12 - t, 1, 12 + t, 9, u, m);
                addRect(12 - t, 15, 12 + t, 23, u, m);
                path.addCircle(m + 12 * u, m + 12 * u, 1.6f * u, Path.Direction.CW);
                hotX = hotY = m + 12 * u;
                break;
            }
            case DOT:
                path.addCircle(m + 12 * u, m + 12 * u, 5.5f * u, Path.Direction.CW);
                hotX = hotY = m + 12 * u;
                break;
            case RING: {
                Path inner = new Path();
                path.addCircle(m + 12 * u, m + 12 * u, 9f * u, Path.Direction.CW);
                inner.addCircle(m + 12 * u, m + 12 * u, 6.2f * u, Path.Direction.CW);
                path.op(inner, Path.Op.DIFFERENCE);
                hotX = hotY = m + 12 * u;
                break;
            }
            default: { // classic arrow, tip at the top left
                float[][] pts = {{0, 0}, {0, 17}, {4.2f, 13.2f}, {7, 19.5f}, {9.6f, 18.4f}, {6.9f, 12.2f}, {12.3f, 12.2f}};
                path.moveTo(m + pts[0][0] * u, m + pts[0][1] * u);
                for (int i = 1; i < pts.length; i++) path.lineTo(m + pts[i][0] * u, m + pts[i][1] * u);
                path.close();
                hotX = hotY = m;
            }
        }
    }

    private void addRect(float l, float t, float r, float b, float u, float m) {
        path.addRect(m + l * u, m + t * u, m + r * u, m + b * u, Path.Direction.CW);
    }

    /** A pointing hand: index finger up (tip at 5,0), three folded fingers, palm and thumb, merged. */
    private void handPath(float u, float m) {
        float[][] parts = {
                {3.5f, 0, 6.5f, 12, 1.5f},    // index finger
                {6.5f, 7, 9.2f, 13, 1.3f},    // folded fingers
                {9.2f, 8, 11.9f, 13, 1.3f},
                {11.9f, 9, 14.6f, 13.5f, 1.3f},
                {2.5f, 10.5f, 14.6f, 21.5f, 3f}, // palm
                {0, 11.5f, 4.5f, 16.5f, 2f},   // thumb
        };
        Path part = new Path();
        for (float[] p : parts) {
            part.reset();
            part.addRoundRect(m + p[0] * u, m + p[1] * u, m + p[2] * u, m + p[3] * u, p[4] * u, p[4] * u,
                    Path.Direction.CW);
            path.op(part, Path.Op.UNION);
        }
    }

    static boolean isLight(int c) {
        return 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c) > 140;
    }

    /** Draws at (0,0); {@code state} is a {@link CursorView} STATE_* (pressed/scroll adds a coloured ring). */
    void draw(Canvas c, int state) {
        boolean centered = centered();
        boolean showRing = state != CursorView.STATE_NORMAL;
        if (showRing) ring.setColor(state == CursorView.STATE_PRESSED ? 0xCC2196F3 : 0xCC4CAF50);
        // Tip shapes: ring behind the tip. Centred shapes: a small ring on top, at the click point.
        if (showRing && !centered) c.drawCircle(hotX, hotY, 2.5f * density * scale, ring);
        if (CUSTOM.equals(style) && custom != null) {
            c.drawBitmap(custom, null, dst, bitmapPaint);
        } else {
            c.drawPath(path, fill);
            c.drawPath(path, stroke);
        }
        if (showRing && centered) c.drawCircle(hotX, hotY, 3.5f * density * scale, ring);
    }
}
