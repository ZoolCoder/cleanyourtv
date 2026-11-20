package com.zoolcoder.tv.quranradio;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * A mosque and a crescent drawn in faint light on the backdrop's bottom start corner, behind the list: a dome on a
 * hall, two side domes and two minarets. One path, filled once, so overlapping parts do not stack up.
 */
final class Mosque extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final int colour;
    private final float width, height;
    private final boolean rtl;

    /** {@code width} and {@code height} in pixels; {@code colour} is the light at its top, fading to nothing below. */
    Mosque(int colour, float width, float height, boolean rtl) {
        this.colour = colour;
        this.width = width;
        this.height = height;
        this.rtl = rtl;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        float left = rtl ? bounds.right - width : bounds.left, top = bounds.bottom - height;
        shape.reset();
        // The hall, the main dome with its spire, and the two side domes.
        add(left, top, 0.20f, 0.70f, 0.80f, 1f);
        oval(left, top, 0.31f, 0.40f, 0.69f, 0.84f);
        add(left, top, 0.495f, 0.30f, 0.505f, 0.42f);
        oval(left, top, 0.22f, 0.60f, 0.38f, 0.78f);
        oval(left, top, 0.62f, 0.60f, 0.78f, 0.78f);
        // The minarets: a shaft, a balcony and a pointed cap each.
        for (float x : new float[]{0.08f, 0.86f}) {
            add(left, top, x, 0.22f, x + 0.06f, 1f);
            add(left, top, x - 0.015f, 0.36f, x + 0.075f, 0.39f);
            Path cap = new Path();
            cap.moveTo(left + x * width, top + 0.22f * height);
            cap.lineTo(left + (x + 0.03f) * width, top + 0.08f * height);
            cap.lineTo(left + (x + 0.06f) * width, top + 0.22f * height);
            cap.close();
            shape.addPath(cap);
        }
        // The crescent above, a circle with a smaller one taken out of it.
        Path moon = new Path();
        float cx = left + 0.50f * width, cy = top + 0.12f * height, r = 0.07f * height;
        moon.addCircle(cx, cy, r, Path.Direction.CW);
        Path bite = new Path();
        bite.addCircle(cx + r * 0.45f, cy - r * 0.2f, r * 0.85f, Path.Direction.CW);
        moon.op(bite, Path.Op.DIFFERENCE);
        shape.op(moon, Path.Op.UNION);
        paint.setShader(new LinearGradient(0, top, 0, bounds.bottom, colour, colour & 0x00FFFFFF,
                Shader.TileMode.CLAMP));
    }

    private void add(float left, float top, float x0, float y0, float x1, float y1) {
        shape.addRect(left + x0 * width, top + y0 * height, left + x1 * width, top + y1 * height, Path.Direction.CW);
    }

    private void oval(float left, float top, float x0, float y0, float x1, float y1) {
        shape.addOval(new RectF(left + x0 * width, top + y0 * height, left + x1 * width, top + y1 * height),
                Path.Direction.CW);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawPath(shape, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
