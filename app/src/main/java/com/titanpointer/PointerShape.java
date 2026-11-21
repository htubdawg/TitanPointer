package com.titanpointer;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.File;

/**
 * Draws the pointer: one of the {@link RetroCursors} pixel glyphs in a chosen colour (its outline in the
 * contrasting colour), or the user's own image. Used by the on-screen {@link CursorView} and the previews in
 * the settings screen.
 *
 * <p>Glyph pixels are drawn as whole screen-pixel blocks with no smoothing, so they stay crisp. At size 1.0×
 * one glyph pixel is about 1.5 dp (a 16-pixel cursor is about 24 dp). A margin around the image leaves room
 * for the click ring.
 */
final class PointerShape {
    static final String DEFAULT = "left_ptr", CUSTOM = "custom";
    static final int DEFAULT_COLOR = Color.BLACK;
    static final int[] COLORS = {Color.BLACK, Color.WHITE, 0xFFFFEB3B, 0xFFFF9800, 0xFFF44336, 0xFFE91E63,
            0xFF4CAF50, 0xFF2196F3};
    static final String HOT_TOP_LEFT = "top_left", HOT_CENTER = "center";
    static final String CUSTOM_FILE = "cursor_custom.png";
    /** Custom images are stored at most this many pixels on their longer side. */
    static final int CUSTOM_MAX_PX = 256;

    private static final float MARGIN_DP = 3f;
    private static final float GLYPH_PIXEL_DP = 1.5f;
    private static final float CUSTOM_DP = 24f;

    private final float density;
    private String style = DEFAULT;
    private float scale = 1f;
    private Bitmap image; // glyph rendered at 1 px per glyph pixel, or the custom image
    private boolean pixelArt;
    private float hotFracX, hotFracY; // hotspot inside the image, in image pixels

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smooth = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint sharp = new Paint();
    private final RectF dst = new RectF();
    private float w, h, hotX, hotY;
    private boolean ringOnTop;

    PointerShape(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        sharp.setFilterBitmap(false);
        sharp.setAntiAlias(false);
        set(DEFAULT, DEFAULT_COLOR, 1f, null, false, false, false);
    }

    /** Reads the look from the settings (and the custom image file, if that's the style). */
    void load(Context c, SharedPreferences p) {
        String s = p.getString(Prefs.CURSOR_STYLE, DEFAULT);
        Bitmap b = CUSTOM.equals(s) ? loadCustom(c) : null;
        set(s, p.getInt(Prefs.CURSOR_COLOR, DEFAULT_COLOR), p.getInt(Prefs.CURSOR_SIZE, 10) / 10f, b,
                HOT_CENTER.equals(p.getString(Prefs.CURSOR_HOTSPOT, HOT_TOP_LEFT)),
                p.getBoolean(Prefs.CURSOR_MIRROR, false), p.getBoolean(Prefs.CURSOR_INVERT, false));
    }

    static Bitmap loadCustom(Context c) {
        File f = new File(c.getFilesDir(), CUSTOM_FILE);
        return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    /**
     * {@code custom} and {@code customCentered} only matter for the custom style; a missing image or unknown
     * style falls back to the default glyph. {@code mirror} flips it left to right (hotspot too); {@code invert}
     * swaps a glyph's fill and outline colours, or inverts a custom image's colours.
     */
    void set(String style, int color, float scale, Bitmap custom, boolean customCentered, boolean mirror,
            boolean invert) {
        this.scale = scale;
        RetroCursors.Glyph g = RetroCursors.find(style);
        if (CUSTOM.equals(style) && custom != null) {
            this.style = CUSTOM;
            image = invert ? invertColors(custom) : custom;
            pixelArt = false;
            hotFracX = customCentered ? custom.getWidth() / 2f : 0;
            hotFracY = customCentered ? custom.getHeight() / 2f : 0;
        } else {
            if (g == null) g = RetroCursors.find(DEFAULT);
            this.style = g.id;
            image = render(g, color, invert);
            pixelArt = true;
            hotFracX = g.hotX + 0.5f; // the middle of the hotspot pixel
            hotFracY = g.hotY + 0.5f;
        }
        if (mirror) {
            android.graphics.Matrix flip = new android.graphics.Matrix();
            flip.setScale(-1, 1);
            image = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), flip, false);
            hotFracX = image.getWidth() - hotFracX;
        }
        layout();
    }

    String style() {
        return style;
    }

    private static Bitmap render(RetroCursors.Glyph g, int color, boolean invert) {
        int outline = isLight(color) ? Color.BLACK : Color.WHITE;
        if (invert) {
            int t = color;
            color = outline;
            outline = t;
        }
        Bitmap b = Bitmap.createBitmap(g.width(), g.height(), Bitmap.Config.ARGB_8888);
        for (int y = 0; y < g.height(); y++) {
            String r = g.rows[y];
            for (int x = 0; x < r.length(); x++) {
                char ch = r.charAt(x);
                if (ch != '.') b.setPixel(x, y, ch == '#' ? color : outline);
            }
        }
        return b;
    }

    /** The same picture with red, green and blue inverted (transparency kept). */
    private static Bitmap invertColors(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) px[i] = (px[i] & 0xFF000000) | (~px[i] & 0x00FFFFFF);
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private void layout() {
        float m = MARGIN_DP * density;
        float k; // screen pixels per image pixel
        if (pixelArt) {
            k = Math.max(1, Math.round(GLYPH_PIXEL_DP * density * scale));
        } else {
            k = CUSTOM_DP * density * scale / Math.max(image.getWidth(), image.getHeight());
            // Small pictures blown up a lot are probably pixel art: keep them crisp too.
            if (k >= 2 && Math.max(image.getWidth(), image.getHeight()) <= 64) k = Math.round(k);
        }
        float iw = image.getWidth() * k, ih = image.getHeight() * k;
        dst.set(m, m, m + iw, m + ih);
        w = iw + 2 * m;
        h = ih + 2 * m;
        hotX = m + hotFracX * k;
        hotY = m + hotFracY * k;
        // Hotspot well inside the picture (not at a tip or edge): draw the click ring on top, small.
        int iwPx = image.getWidth(), ihPx = image.getHeight();
        ringOnTop = Math.min(hotFracX, iwPx - hotFracX) > 3 && Math.min(hotFracY, ihPx - hotFracY) > 3;
        ring.setStrokeWidth(2f * density);
    }

    private boolean crisp() {
        return pixelArt || dst.width() / image.getWidth() >= 2;
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

    static boolean isLight(int c) {
        return 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c) > 140;
    }

    /** Draws at (0,0); {@code state} is a {@link CursorView} STATE_* (pressed/scroll adds a coloured ring). */
    void draw(Canvas c, int state) {
        boolean showRing = state != CursorView.STATE_NORMAL;
        float r = (ringOnTop ? 3.5f : 2.5f) * density * scale;
        if (showRing) ring.setColor(state == CursorView.STATE_PRESSED ? 0xCC2196F3 : 0xCC4CAF50);
        if (showRing && !ringOnTop) c.drawCircle(hotX, hotY, r, ring);
        c.drawBitmap(image, null, dst, crisp() ? sharp : smooth);
        if (showRing && ringOnTop) c.drawCircle(hotX, hotY, r, ring);
    }
}
