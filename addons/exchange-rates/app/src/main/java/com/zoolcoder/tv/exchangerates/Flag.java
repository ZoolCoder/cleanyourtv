package com.zoolcoder.tv.exchangerates;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * A currency's flag, simplified and drawn in a disc with a thin light rim, like the badges in the
 * Liquid Glass mockups. Drawn in code so it stays sharp at 32 and 64 dp and adds no image to the APK.
 * {@link #of} answers null for a code it has no flag for; the caller then shows the lettered disc.
 */
final class Flag extends Drawable {
    private static final int RIM = 0x59FFFFFF;
    private static final int WHITE = 0xFFFFFFFF, BLACK = 0xFF000000;

    private interface Painter {
        void paint(Canvas canvas, float w, float h, Paint paint);
    }

    private final Painter painter;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF bounds = new RectF();

    private Flag(Painter painter) {
        this.painter = painter;
    }

    static Flag of(String code) {
        switch (code) {
            case "USD": return new Flag(Flag::usa);
            case "SAR": return new Flag(Flag::saudi);
            case "AED": return new Flag(Flag::emirates);
            case "EGP": return new Flag(Flag::egypt);
            case "EUR": return new Flag(Flag::europe);
            case "QAR": return new Flag(Flag::qatar);
            case "KWD": return new Flag(Flag::kuwait);
            case "GBP": return new Flag(Flag::britain);
            case "SDG": return new Flag(Flag::sudan);
            default: return null;
        }
    }

    @Override
    protected void onBoundsChange(Rect rect) {
        bounds.set(rect);
        clip.reset();
        clip.addOval(bounds, Path.Direction.CW);
    }

    @Override
    public void draw(Canvas canvas) {
        float w = bounds.width(), h = bounds.height();
        int saved = canvas.save();
        canvas.clipPath(clip);
        canvas.translate(bounds.left, bounds.top);
        paint.setStyle(Paint.Style.FILL);
        painter.paint(canvas, w, h, paint);
        // A soft top highlight and a hairline rim, so the flag sits in the glass rather than on it.
        paint.setColor(0x1FFFFFFF);
        canvas.drawRect(0, 0, w, h * 0.45f, paint);
        canvas.restoreToCount(saved);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, w / 32f));
        paint.setColor(RIM);
        float inset = paint.getStrokeWidth() / 2;
        canvas.drawOval(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset, paint);
    }

    private static void bands(Canvas canvas, float w, float h, Paint paint, int... colours) {
        float band = h / colours.length;
        for (int i = 0; i < colours.length; i++) {
            paint.setColor(colours[i]);
            canvas.drawRect(0, band * i, w, band * (i + 1), paint);
        }
    }

    private static void usa(Canvas canvas, float w, float h, Paint paint) {
        float stripe = h / 7;
        for (int i = 0; i < 7; i++) {
            paint.setColor(i % 2 == 0 ? 0xFFB22234 : WHITE);
            canvas.drawRect(0, stripe * i, w, stripe * (i + 1), paint);
        }
        paint.setColor(0xFF3C3B6E);
        canvas.drawRect(0, 0, w * 0.5f, stripe * 4, paint);
        paint.setColor(WHITE);
        float r = w / 40;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                canvas.drawCircle(w * (0.12f + col * 0.13f), stripe * (0.8f + row * 1.2f), r, paint);
            }
        }
    }

    private static void saudi(Canvas canvas, float w, float h, Paint paint) {
        paint.setColor(0xFF006C35);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setColor(WHITE);
        // The script as a light band, the sword as a line under it.
        canvas.drawRoundRect(w * 0.24f, h * 0.36f, w * 0.76f, h * 0.48f, h * 0.04f, h * 0.04f, paint);
        canvas.drawRect(w * 0.26f, h * 0.6f, w * 0.74f, h * 0.64f, paint);
    }

    private static void emirates(Canvas canvas, float w, float h, Paint paint) {
        bands(canvas, w, h, paint, 0xFF00732F, WHITE, BLACK);
        paint.setColor(0xFFFF0000);
        canvas.drawRect(0, 0, w * 0.3f, h, paint);
    }

    private static void egypt(Canvas canvas, float w, float h, Paint paint) {
        bands(canvas, w, h, paint, 0xFFCE1126, WHITE, BLACK);
        paint.setColor(0xFFC09300);
        canvas.drawCircle(w / 2, h / 2, w * 0.08f, paint);
    }

    private static void europe(Canvas canvas, float w, float h, Paint paint) {
        paint.setColor(0xFF003399);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setColor(0xFFFFCC00);
        float ring = w * 0.3f, r = w / 26;
        for (int i = 0; i < 12; i++) {
            double angle = Math.PI * 2 * i / 12;
            canvas.drawCircle(w / 2 + (float) Math.cos(angle) * ring, h / 2 + (float) Math.sin(angle) * ring, r, paint);
        }
    }

    private static void qatar(Canvas canvas, float w, float h, Paint paint) {
        paint.setColor(0xFF8A1538);
        canvas.drawRect(0, 0, w, h, paint);
        // The white hoist with its nine-point serrated edge.
        Path path = new Path();
        path.moveTo(0, 0);
        float edge = w * 0.36f, tooth = w * 0.1f, step = h / 9;
        path.lineTo(edge, 0);
        for (int i = 0; i < 9; i++) {
            path.lineTo(edge + tooth, step * i + step / 2);
            path.lineTo(edge, step * (i + 1));
        }
        path.lineTo(0, h);
        path.close();
        paint.setColor(WHITE);
        canvas.drawPath(path, paint);
    }

    private static void kuwait(Canvas canvas, float w, float h, Paint paint) {
        bands(canvas, w, h, paint, 0xFF007A3D, WHITE, 0xFFCE1126);
        Path path = new Path();
        path.moveTo(0, 0);
        path.lineTo(w * 0.28f, h / 3);
        path.lineTo(w * 0.28f, h * 2 / 3);
        path.lineTo(0, h);
        path.close();
        paint.setColor(BLACK);
        canvas.drawPath(path, paint);
    }

    private static void britain(Canvas canvas, float w, float h, Paint paint) {
        paint.setColor(0xFF012169);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(WHITE);
        paint.setStrokeWidth(w * 0.2f);
        canvas.drawLine(0, 0, w, h, paint);
        canvas.drawLine(w, 0, 0, h, paint);
        paint.setColor(0xFFC8102E);
        paint.setStrokeWidth(w * 0.07f);
        canvas.drawLine(0, 0, w, h, paint);
        canvas.drawLine(w, 0, 0, h, paint);
        paint.setColor(WHITE);
        paint.setStrokeWidth(w * 0.3f);
        canvas.drawLine(w / 2, 0, w / 2, h, paint);
        canvas.drawLine(0, h / 2, w, h / 2, paint);
        paint.setColor(0xFFC8102E);
        paint.setStrokeWidth(w * 0.18f);
        canvas.drawLine(w / 2, 0, w / 2, h, paint);
        canvas.drawLine(0, h / 2, w, h / 2, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private static void sudan(Canvas canvas, float w, float h, Paint paint) {
        bands(canvas, w, h, paint, 0xFFD21034, WHITE, BLACK);
        Path path = new Path();
        path.moveTo(0, 0);
        path.lineTo(w * 0.42f, h / 2);
        path.lineTo(0, h);
        path.close();
        paint.setColor(0xFF007229);
        canvas.drawPath(path, paint);
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
