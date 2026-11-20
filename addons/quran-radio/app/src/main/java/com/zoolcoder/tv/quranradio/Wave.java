package com.zoolcoder.tv.quranradio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * A still sound wave of rounded bars under the reciter's name, taller in the middle. It does not move: an animated
 * one would redraw the panel many times a second on a TV with 1 GB of RAM. Dimmed while nothing plays.
 */
final class Wave extends View {
    private static final float[] HEIGHTS = {0.25f, 0.4f, 0.3f, 0.55f, 0.45f, 0.7f, 0.5f, 0.85f, 0.6f, 1f, 0.75f,
            0.9f, 0.55f, 0.8f, 0.65f, 0.95f, 0.5f, 0.7f, 0.4f, 0.6f, 0.35f, 0.5f, 0.3f, 0.4f, 0.2f};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    Wave(Context context, int colour) {
        super(context);
        paint.setColor(colour);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float step = getWidth() / (float) HEIGHTS.length, mid = getHeight() / 2f;
        paint.setStrokeWidth(step * 0.45f);
        for (int i = 0; i < HEIGHTS.length; i++) {
            float x = step * (i + 0.5f), half = Math.max(paint.getStrokeWidth(), HEIGHTS[i] * mid) - paint.getStrokeWidth() / 2;
            canvas.drawLine(x, mid - half, x, mid + half, paint);
        }
    }
}
