package com.zoolcoder.tv.calendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

import java.time.LocalDate;

/**
 * One day in the month grid: the day number and up to 3 who-colour dots, then "+N", and a small "✈" in the top
 * end corner when an event that day came from abroad. Today has an accent ring
 * (the white focus outline is drawn by the background). Everything is drawn with paints made once.
 */
final class DayCell extends View {
    static final int MAX_DOTS = 3;

    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint more = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plane = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final int[] colors = new int[MAX_DOTS];
    private final float dotRadius, dotGap, inset;
    private LocalDate date;
    private String label = "";
    private int dots, extra;
    private final boolean weekend;
    private boolean outside, today, away;

    /** {@code weekend}: a Friday or Saturday column, its numbers one step quieter. */
    DayCell(Context context, boolean weekend) {
        super(context);
        this.weekend = weekend;
        number.setTextAlign(Paint.Align.CENTER);
        number.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18, getResources().getDisplayMetrics()));
        more.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
        more.setColor(Look.TEXT_2);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Look.dp(context, 2));
        ring.setColor(Look.ACCENT);
        plane.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, getResources().getDisplayMetrics()));
        plane.setColor(Look.TEXT_2);
        plane.setTextAlign(Paint.Align.CENTER);
        dotRadius = Look.dp(context, 3);
        dotGap = Look.dp(context, 4);
        inset = Look.dp(context, 1);
        setFocusable(true);
        setClickable(true);
    }

    LocalDate date() {
        return date;
    }

    /** {@code outside}: a day of the month before or after, shown dimmed. */
    void bind(LocalDate date, boolean outside, boolean today, int[] eventColors, int count, boolean away) {
        this.date = date;
        this.away = away;
        this.label = Integer.toString(date.getDayOfMonth());
        this.outside = outside;
        this.today = today;
        dots = Math.min(count, MAX_DOTS);
        System.arraycopy(eventColors, 0, colors, 0, dots);
        extra = count - dots;
        number.setColor(today ? Look.ACCENT : outside ? 0x59FFFFFF : weekend ? Look.TEXT_2 : Look.TEXT);
        number.setTypeface(today ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        setContentDescription(date.toString());
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (today && !isFocused()) {
            bounds.set(inset * 2, inset * 2, w - inset * 2, h - inset * 2);
            float r = Look.dp(getContext(), 10);
            canvas.drawRoundRect(bounds, r, r, ring);
        }
        float baseline = h * 0.52f;
        canvas.drawText(label, w / 2f, baseline, number);
        if (away) {
            float size = plane.getTextSize();
            float px = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? size : w - size;
            canvas.drawText("✈", px, size * 1.2f, plane);
        }
        if (dots == 0) return;
        float y = h * 0.76f;
        float plus = extra > 0 ? more.measureText("+" + extra) + dotGap : 0;
        float width = dots * dotRadius * 2 + (dots - 1) * dotGap + plus;
        float x = (w - width) / 2f + dotRadius;
        for (int i = 0; i < dots; i++) {
            dot.setColor(outside ? (colors[i] & 0x00FFFFFF) | 0x66000000 : colors[i]);
            canvas.drawCircle(x, y, dotRadius, dot);
            x += dotRadius * 2 + dotGap;
        }
        if (extra > 0) canvas.drawText("+" + extra, x - dotRadius, y + dotRadius + inset, more);
    }
}
