package com.zoolcoder.tv.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Draws a QrCode as dark modules on white, with the 4-module quiet zone scanners need. */
final class QrView extends View {
    private static final int QUIET = 4;
    private final Paint dark = new Paint(), light = new Paint();
    private QrCode code;

    QrView(Context context) {
        super(context);
        dark.setColor(Color.BLACK);
        light.setColor(Color.WHITE);
    }

    void show(QrCode code) {
        this.code = code;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (code == null) return;
        int modules = code.size + QUIET * 2;
        float cell = Math.min(getWidth(), getHeight()) / (float) modules;
        // A bounded rect, not drawColor: parents that do not clip would let the white spill over them.
        canvas.drawRect(0, 0, cell * modules, cell * modules, light);
        for (int y = 0; y < code.size; y++) {
            for (int x = 0; x < code.size; x++) {
                if (!code.dark(x, y)) continue;
                float left = (x + QUIET) * cell, top = (y + QUIET) * cell;
                canvas.drawRect(left, top, left + cell, top + cell, dark);
            }
        }
    }
}
