package com.zoolcoder.tv.calendar;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
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

/**
 * The month: a Sunday-first grid of 6 weeks, Friday and Saturday tinted as the weekend, and on the end side
 * the agenda of the focused day. Left and Right move a day, Down a week, across month edges; Up from the
 * first week goes to the tabs. Rewind and Fast-forward change the month. OK enters the day's events.
 */
final class MonthPage {
    static final int WEEKS = 6;

    private final Context c;
    private final Dates dates;
    private final Consumer<Occurrence> open;
    private final Runnable toTabs;
    private final DayCell[] cells = new DayCell[WEEKS * 7];
    private final int[] colors = new int[DayCell.MAX_DOTS];
    private final boolean rtl;
    private final LinearLayout view;
    private TextView monthTitle, hijriRange, dayTitle, dayHijri;
    private LinearLayout agenda;
    private ScrollView agendaScroll;
    private List<Event> events = List.of();
    private YearMonth shown;
    private LocalDate selected;

    MonthPage(Context c, Dates dates, Consumer<Occurrence> open, Runnable toTabs) {
        this.c = c;
        this.dates = dates;
        this.open = open;
        this.toTabs = toTabs;
        rtl = c.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        view = Ui.row(c);
        view.setGravity(Gravity.TOP);
        view.addView(grid(), new LinearLayout.LayoutParams(Look.dp(c, 500), LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams paneParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        paneParams.setMarginStart(Look.dp(c, 24));
        view.addView(pane(), paneParams);
    }

    View view() {
        return view;
    }

    LocalDate selected() {
        return selected;
    }

    boolean inAgenda(View focus) {
        return focus != null && focus.getParent() == agenda;
    }

    boolean inGrid(View focus) {
        return focus instanceof DayCell;
    }

    /** A day whose Up goes to the tabs: the week above is not in this month. */
    boolean inTopWeek(View focus) {
        return focus instanceof DayCell && !upStaysInMonth(((DayCell) focus).date(), shown);
    }

    static boolean upStaysInMonth(LocalDate day, YearMonth month) {
        return day != null && YearMonth.from(day.minusDays(7)).equals(month);
    }

    private View grid() {
        LinearLayout grid = Ui.column(c);

        LinearLayout title = Ui.row(c);
        title.addView(arrow(rtl ? "›" : "‹"));
        LinearLayout words = Ui.column(c);
        words.setPaddingRelative(Look.dp(c, 12), 0, 0, 0);
        monthTitle = Look.medium(Ui.text(c, "", 24, Look.TEXT));
        hijriRange = Ui.text(c, "", 15, Look.TEXT_2);
        words.addView(monthTitle);
        words.addView(hijriRange);
        title.addView(words, Ui.fill(1f));
        title.addView(arrow(rtl ? "‹" : "›"));
        grid.addView(title);

        LinearLayout panel = Ui.column(c);
        int p = Look.dp(c, 6);
        panel.setPadding(p, p, p, p);
        Look.panel(c, panel, Look.SURFACE, 14);
        LinearLayout names = Ui.row(c);
        for (int i = 0; i < 7; i++) {
            DayOfWeek day = DayOfWeek.SUNDAY.plus(i);
            LinearLayout name = Ui.column(c);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            name.addView(centred(Ui.text(c, Dates.weekday(day, Locale.ENGLISH, TextStyle.SHORT), 13, Look.TEXT_2)));
            name.addView(centred(Ui.text(c, Dates.weekday(day, new Locale("ar"), TextStyle.FULL), 11, Look.TEXT_3)));
            names.addView(name, Ui.fill(1f));
        }
        panel.addView(names, Ui.spaced(c, 0, 4));
        for (int week = 0; week < WEEKS; week++) {
            LinearLayout row = Ui.row(c);
            for (int d = 0; d < 7; d++) {
                DayCell cell = cell(week * 7 + d, d >= 5);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Look.dp(c, 42), 1f);
                params.setMargins(Look.dp(c, 2), Look.dp(c, 2), Look.dp(c, 2), Look.dp(c, 2));
                row.addView(cell, params);
            }
            panel.addView(row);
        }
        grid.addView(panel, Ui.spaced(c, 10, 0));
        return grid;
    }

    /** Centred over its column in either direction; the text's own direction would push Arabic to the end. */
    private static TextView centred(TextView view) {
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        return view;
    }

    private TextView arrow(String glyph) {
        TextView arrow = Ui.text(c, glyph, 30, Look.TEXT_2);
        arrow.setPadding(Look.dp(c, 8), 0, Look.dp(c, 8), 0);
        return arrow;
    }

    private DayCell cell(int index, boolean weekend) {
        DayCell cell = new DayCell(c, weekend);
        Look.row(c, cell, weekend ? Look.WEEKEND : Look.CELL, 10, 1.06f);
        cell.setOnFocusChangeListener((v, has) -> {
            Look.lift(v, has, 1.06f);
            v.invalidate();
            if (has) select(cell.date());
        });
        cell.setOnKeyListener((v, code, event) -> event.getAction() == KeyEvent.ACTION_DOWN && key(code));
        cell.setOnClickListener(v -> enterAgenda());
        cells[index] = cell;
        return cell;
    }

    private boolean key(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return moveTo(selected.plusDays(rtl ? 1 : -1));
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return moveTo(selected.plusDays(rtl ? -1 : 1));
            case KeyEvent.KEYCODE_DPAD_UP:
                // Never the previous month: Up from the month's first week reaches the tabs, whatever the focus search finds.
                if (upStaysInMonth(selected, shown)) return moveTo(selected.minusDays(7));
                toTabs.run();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return moveTo(selected.plusDays(7));
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                return moveTo(selected.minusMonths(1));
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                return moveTo(selected.plusMonths(1));
            default:
                return false;
        }
    }

    private View pane() {
        LinearLayout pane = Ui.column(c);
        dayTitle = Look.medium(Ui.text(c, "", 20, Look.TEXT));
        dayHijri = Ui.text(c, "", 15, Look.TEXT_2);
        pane.addView(dayTitle, Ui.spaced(c, 6, 0));
        pane.addView(dayHijri);
        agenda = Ui.column(c);
        agendaScroll = new ScrollView(c);
        int p = Look.dp(c, 8);
        agendaScroll.setPadding(p, p, p, p);
        agendaScroll.setClipChildren(false);
        agendaScroll.setClipToPadding(false);
        agendaScroll.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        agendaScroll.setClipToOutline(true);
        agendaScroll.addView(agenda);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        params.topMargin = Look.dp(c, 8);
        // The outline and scale of the first and last card need the scroller's padding.
        params.setMarginStart(-p);
        params.setMarginEnd(-p);
        pane.addView(agendaScroll, params);
        return pane;
    }

    /** New events, or a new day: rebinds the cells and the agenda without moving focus. */
    void setEvents(List<Event> events) {
        this.events = events;
        if (selected == null) selected = LocalDate.now();
        show(YearMonth.from(selected));
        select(selected);
    }

    /** Puts focus on a day, changing the month first when the day is in another one. */
    boolean moveTo(LocalDate day) {
        if (!YearMonth.from(day).equals(shown)) show(YearMonth.from(day));
        DayCell cell = cellFor(day);
        if (cell != null) cell.requestFocus();
        // The same cell can hold the new day after a month change, and then no focus change says so.
        select(day);
        return true;
    }

    void focusSelected() {
        moveTo(selected == null ? LocalDate.now() : selected);
    }

    private DayCell cellFor(LocalDate day) {
        for (DayCell cell : cells) if (day.equals(cell.date())) return cell;
        return null;
    }

    private void show(YearMonth month) {
        shown = month;
        LocalDate first = month.atDay(1);
        LocalDate start = first.minusDays(first.getDayOfWeek().getValue() % 7);
        LocalDate today = LocalDate.now();
        for (int i = 0; i < cells.length; i++) {
            LocalDate day = start.plusDays(i);
            List<Occurrence> on = Agenda.on(events, day);
            boolean away = false;
            for (int k = 0; k < on.size(); k++) {
                if (k < colors.length) colors[k] = Look.WHO[on.get(k).event.color];
                away |= on.get(k).event.away;
            }
            cells[i].bind(day, !YearMonth.from(day).equals(month), day.equals(today), colors, on.size(), away);
        }
        monthTitle.setText(dates.month(first));
        hijriRange.setText(dates.hijriRange(first, month.atEndOfMonth()));
    }

    private void select(LocalDate day) {
        selected = day;
        dayTitle.setText(dates.day(day));
        dayHijri.setText(dates.hijri(day));
        agenda.removeAllViews();
        List<Occurrence> on = Agenda.on(events, day);
        if (on.isEmpty()) {
            agenda.addView(Look.directed(Ui.text(c, c.getString(R.string.no_events), 16, Look.TEXT_3)), Ui.spaced(c, 4, 0));
        }
        for (Occurrence o : on) {
            LinearLayout row = EventRow.build(c, dates, o, false, v -> open.accept(o));
            row.setOnKeyListener((v, code, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                    && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) && backToGrid());
            agenda.addView(row, Ui.spaced(c, 0, 8));
        }
        agendaScroll.scrollTo(0, 0);
    }

    /** OK on a day: focus its first event, when it has one. */
    private void enterAgenda() {
        if (agenda.getChildCount() > 0 && agenda.getChildAt(0).isFocusable()) agenda.getChildAt(0).requestFocus();
    }

    boolean backToGrid() {
        DayCell cell = cellFor(selected);
        if (cell != null) cell.requestFocus();
        return true;
    }
}
