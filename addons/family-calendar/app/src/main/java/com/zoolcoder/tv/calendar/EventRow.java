package com.zoolcoder.tv.calendar;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One event in a list: the time column, a bar in the who colour, the title with who under it, and a chevron.
 * With {@code withDay}, the time column names the day first ("Tue 27 Oct" over "10:00 AM"). An event sent from
 * abroad says so under the title.
 */
final class EventRow {
    private EventRow() {
    }

    static LinearLayout build(Context c, Dates dates, Occurrence o, boolean withDay, View.OnClickListener onClick) {
        Event e = o.event;
        LinearLayout row = Ui.row(c);
        row.setPaddingRelative(Look.dp(c, 14), Look.dp(c, 10), Look.dp(c, 12), Look.dp(c, 10));
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnClickListener(onClick);
        Look.row(c, row, Look.SURFACE, 14, 1.02f);

        LinearLayout when = Ui.column(c);
        String first = withDay ? dates.shortDay(o.day) : e.allDay() ? c.getString(R.string.all_day) : dates.time(e.start);
        String second = withDay ? (e.allDay() ? c.getString(R.string.all_day) : dates.time(e.start))
                : e.end == null ? null : dates.time(e.end);
        when.addView(Ui.text(c, first, 15, Look.TEXT));
        if (second != null) when.addView(Ui.text(c, second, 13, Look.TEXT_2));
        row.addView(when, new LinearLayout.LayoutParams(Look.dp(c, withDay ? 88 : 72),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        View bar = new View(c);
        bar.setBackground(Look.shape(c, Look.WHO[e.color], 2));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(Look.dp(c, 4), Look.dp(c, 40));
        barParams.setMarginStart(Look.dp(c, 8));
        barParams.setMarginEnd(Look.dp(c, 14));
        row.addView(bar, barParams);

        LinearLayout words = Ui.column(c);
        TextView title = Look.directed(Look.medium(Ui.text(c, e.title, 18, Look.TEXT)));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        if (e.who != null) {
            TextView who = Look.directed(Ui.text(c, e.who, 14, Look.TEXT_2));
            who.setSingleLine(true);
            who.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(who);
        }
        if (e.away) words.addView(Ui.text(c, c.getString(R.string.from_abroad), 13, Look.ACCENT));
        row.addView(words, Ui.fill(1f));

        boolean rtl = c.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        TextView chevron = Ui.text(c, rtl ? "‹" : "›", 22, Look.TEXT_3);
        chevron.setGravity(Gravity.CENTER);
        chevron.setPaddingRelative(Look.dp(c, 8), 0, 0, 0);
        row.addView(chevron);
        return row;
    }
}
