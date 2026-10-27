package com.zoolcoder.tv.calendar;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Today: the date and a small month on the start side, and today plus the next 7 days as one list. */
final class TodayPage {
    static final int DAYS = 8;
    static final int MAX = 60;

    private final Context c;
    private final Dates dates;
    private final Consumer<Occurrence> open;
    private final LinearLayout view;
    private TextView date, hijri, miniTitle;
    private LinearLayout mini, list;
    private ScrollView scroll;

    TodayPage(Context c, Dates dates, Consumer<Occurrence> open) {
        this.c = c;
        this.dates = dates;
        this.open = open;
        view = Ui.row(c);
        view.setGravity(Gravity.TOP);
        view.addView(side(), new LinearLayout.LayoutParams(Look.dp(c, 280), LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        listParams.setMarginStart(Look.dp(c, 16));
        view.addView(list(), listParams);
    }

    View view() {
        return view;
    }

    boolean contains(View focus) {
        return focus != null && focus.getParent() == list;
    }

    private View side() {
        LinearLayout side = Ui.column(c);
        side.addView(Ui.bold(Ui.text(c, c.getString(R.string.today), 30, Look.TEXT)), Ui.spaced(c, 8, 0));
        date = Ui.text(c, "", 18, Look.TEXT);
        hijri = Ui.text(c, "", 15, Look.TEXT_2);
        side.addView(date, Ui.spaced(c, 4, 0));
        side.addView(hijri, Ui.spaced(c, 4, 0));

        LinearLayout panel = Ui.column(c);
        int p = Look.dp(c, 12);
        panel.setPadding(p, p, p, p);
        Look.panel(c, panel, Look.SURFACE, 14);
        miniTitle = Look.medium(Ui.text(c, "", 15, Look.TEXT));
        panel.addView(miniTitle, Ui.spaced(c, 0, 6));
        LinearLayout names = Ui.row(c);
        for (int i = 0; i < 7; i++) {
            TextView name = Ui.text(c, Dates.weekday(DayOfWeek.SUNDAY.plus(i), Locale.ENGLISH, TextStyle.NARROW),
                    11, Look.TEXT_3);
            name.setGravity(Gravity.CENTER);
            names.addView(name, Ui.fill(1f));
        }
        panel.addView(names);
        mini = Ui.column(c);
        panel.addView(mini);
        side.addView(panel, Ui.spaced(c, 20, 0));
        return side;
    }

    private View list() {
        list = Ui.column(c);
        scroll = new ScrollView(c);
        int p = Look.dp(c, 8);
        scroll.setPadding(p, p, p, p);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        scroll.setClipToOutline(true);
        scroll.addView(list);
        return scroll;
    }

    void setEvents(List<Event> events) {
        LocalDate today = LocalDate.now();
        date.setText(dates.day(today));
        hijri.setText(dates.hijri(today));
        miniMonth(today);
        list.removeAllViews();
        List<Occurrence> next = Agenda.between(events, today, DAYS, MAX);
        if (next.isEmpty()) list.addView(Look.directed(Ui.text(c, c.getString(R.string.no_events_week), 17, Look.TEXT_3)));
        for (Occurrence o : next) {
            list.addView(EventRow.build(c, dates, o, !o.day.equals(today), v -> open.accept(o)), Ui.spaced(c, 0, 8));
        }
    }

    /** Focuses the first event, or reports there is none. */
    boolean focusFirst() {
        View first = list.getChildCount() > 0 ? list.getChildAt(0) : null;
        if (first == null || !first.isFocusable()) return false;
        scroll.scrollTo(0, 0);
        first.requestFocus();
        return true;
    }

    private void miniMonth(LocalDate today) {
        mini.removeAllViews();
        YearMonth month = YearMonth.from(today);
        miniTitle.setText(dates.month(today));
        LocalDate first = month.atDay(1);
        LocalDate start = first.minusDays(first.getDayOfWeek().getValue() % 7);
        for (int week = 0; week < 6; week++) {
            LinearLayout row = Ui.row(c);
            for (int d = 0; d < 7; d++) {
                LocalDate day = start.plusDays(week * 7 + d);
                boolean inMonth = YearMonth.from(day).equals(month);
                TextView n = Ui.text(c, Integer.toString(day.getDayOfMonth()), 12,
                        day.equals(today) ? Look.TEXT : inMonth ? Look.TEXT_2 : 0x40FFFFFF);
                n.setGravity(Gravity.CENTER);
                if (day.equals(today)) {
                    Ui.bold(n);
                    Look.panel(c, n, Look.ACCENT, 11);
                }
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Look.dp(c, 22), 1f);
                params.setMargins(Look.dp(c, 2), Look.dp(c, 1), Look.dp(c, 2), Look.dp(c, 1));
                row.addView(n, params);
            }
            mini.addView(row);
        }
    }
}
