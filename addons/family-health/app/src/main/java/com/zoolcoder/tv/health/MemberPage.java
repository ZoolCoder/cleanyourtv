package com.zoolcoder.tv.health;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;

/**
 * One person's page: their disc and name, seven dots for this week's doses (taken, missed, still to come), their
 * medicines with the days left in each pack, and their coming appointments. Left and Right past the edge move
 * to the next member.
 */
final class MemberPage {
    private final Activity a;
    private final Store store;
    private final Dates dates;
    private final LinearLayout root, header, medicines, visits;
    private Member member;

    MemberPage(Activity a, Store store, Dates dates) {
        this.a = a;
        this.store = store;
        this.dates = dates;
        root = Ui.column(a);
        header = Ui.row(a);
        Look.panel(a, header, Look.SURFACE, 28);
        header.setPadding(Look.dp(a, 20), Look.dp(a, 14), Look.dp(a, 20), Look.dp(a, 14));
        root.addView(header);

        LinearLayout panels = Ui.row(a);
        panels.setGravity(Gravity.TOP);
        medicines = panel(panels, a.getString(R.string.heading_medicines), R.drawable.ic_pill);
        visits = panel(panels, a.getString(R.string.heading_visits), R.drawable.ic_calendar);
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        grow.topMargin = Look.dp(a, 16);
        root.addView(panels, grow);
    }

    View view() {
        return root;
    }

    Member member() {
        return member;
    }

    private LinearLayout panel(LinearLayout row, String label, int icon) {
        LinearLayout panel = Ui.column(a);
        Look.panel(a, panel, Look.SURFACE, 24);
        panel.setPadding(Look.dp(a, 14), Look.dp(a, 14), Look.dp(a, 14), Look.dp(a, 6));
        panel.addView(Parts.heading(a, label, icon, Look.ACCENT));
        LinearLayout list = Parts.scrollList(a, panel);
        LinearLayout.LayoutParams weight = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (row.getChildCount() > 0) weight.setMarginStart(Look.dp(a, 14));
        row.addView(panel, weight);
        return list;
    }

    void show(Member m) {
        member = m;
        refresh();
        focusFirst();
    }

    /** The member before or after this one, round the family; null when there is only one. */
    Member step(int by) {
        List<Member> all = store.records.members;
        int at = all.indexOf(member);
        if (at < 0 || all.size() < 2) return null;
        return all.get(Math.floorMod(at + by, all.size()));
    }

    void focusFirst() {
        for (LinearLayout list : new LinearLayout[]{medicines, visits}) {
            View first = list.getChildAt(0);
            if (first != null && first.isFocusable() && first.requestFocus()) return;
        }
        root.requestFocus();
    }

    void refresh() {
        Records r = store.records;
        if (member == null || r.member(member.id) == null) return;
        member = r.member(member.id);
        Object focused = root.findFocus() == null ? null : root.findFocus().getTag();
        LocalDateTime now = LocalDateTime.now();
        drawHeader(r, now);

        medicines.removeAllViews();
        for (Medicine m : r.medicines) {
            if (m.member.equals(member.id) && (m.end == null || !m.end.isBefore(now.toLocalDate()))) {
                medicines.addView(medicineRow(m), Ui.spaced(a, medicines.getChildCount() == 0 ? 0 : 10, 0));
            }
        }
        if (medicines.getChildCount() == 0) medicines.addView(Parts.quiet(a, a.getString(R.string.no_medicines)));

        visits.removeAllViews();
        for (Visit v : Plan.upcoming(r, member.id, now)) {
            visits.addView(visitRow(v), Ui.spaced(a, visits.getChildCount() == 0 ? 0 : 10, 0));
        }
        if (visits.getChildCount() == 0) visits.addView(Parts.quiet(a, a.getString(R.string.no_visits)));

        if (focused != null) {
            View same = root.findViewWithTag(focused);
            if (same != null) same.requestFocus();
        }
    }

    private void drawHeader(Records r, LocalDateTime now) {
        header.removeAllViews();
        header.addView(Parts.disc(a, member, 64));
        TextView name = Look.directed(Ui.bold(Ui.text(a, member.name, 30, Look.TEXT)));
        name.setSingleLine(true);
        LinearLayout.LayoutParams fill = Ui.fill(1f);
        fill.setMarginStart(Look.dp(a, 18));
        header.addView(name, fill);

        View rule = new View(a);
        rule.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(Look.dp(a, 1), Look.dp(a, 64));
        line.setMarginEnd(Look.dp(a, 20));
        header.addView(rule, line);

        LinearLayout week = Ui.column(a);
        week.addView(Look.medium(Ui.text(a, a.getString(R.string.heading_week), 15, Look.TEXT)));
        LinearLayout days = Ui.row(a);
        DayOfWeek first = WeekFields.of(dates.locale).getFirstDayOfWeek();
        Plan.Dot[] dots = Plan.week(r, member.id, now, first);
        LocalDate start = now.toLocalDate().with(TemporalAdjusters.previousOrSame(first));
        for (int i = 0; i < 7; i++) {
            LinearLayout day = Ui.column(a);
            day.setGravity(Gravity.CENTER_HORIZONTAL);
            day.addView(dot(dots[i]));
            TextView label = Ui.text(a, start.plusDays(i).getDayOfWeek().getDisplayName(TextStyle.SHORT, dates.locale),
                    12, start.plusDays(i).equals(now.toLocalDate()) ? Look.TEXT : Look.TEXT_3);
            day.addView(label, Ui.start(a, 0));
            ((LinearLayout.LayoutParams) label.getLayoutParams()).topMargin = Look.dp(a, 4);
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(Look.dp(a, 44),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            days.addView(day, cell);
        }
        week.addView(days, Ui.spaced(a, 8, 0));
        header.addView(week);
    }

    private View dot(Plan.Dot dot) {
        switch (dot) {
            case TAKEN:
                return Parts.badge(a, R.drawable.ic_check, Look.TAKEN, true, 26);
            case MISSED:
                return Parts.badge(a, R.drawable.ic_close, Look.MISSED, true, 26);
            case UPCOMING:
                return Parts.ring(a, 0x80, 26);
            default:
                return Parts.ring(a, 0x26, 26);
        }
    }

    private View medicineRow(Medicine m) {
        List<String> times = new ArrayList<>();
        for (LocalTime t : m.times) times.add(dates.time(t));
        String sub = TodayPage.join(m.how, String.join(", ", times));
        LinearLayout end = Ui.column(a);
        end.setGravity(Gravity.END);
        int days = m.daysLeft();
        if (days >= 0) {
            LinearLayout line = Ui.row(a);
            if (m.low()) line.addView(Parts.icon(a, R.drawable.ic_warning, Look.LOW, 16));
            TextView left = Look.medium(Ui.text(a, a.getResources().getQuantityString(R.plurals.days_left, days, days),
                    14, m.low() ? Look.LOW : Look.TEXT_2));
            line.addView(left, Ui.start(a, m.low() ? 6 : 0));
            end.addView(line);
        }
        LinearLayout row = Parts.card(a, null, m.title(), sub, end);
        row.addView(tile(R.drawable.ic_pill), 0);
        ((LinearLayout.LayoutParams) row.getChildAt(1).getLayoutParams()).setMarginStart(Look.dp(a, 12));
        row.setTag("med:" + m.id);
        return row;
    }

    private View visitRow(Visit v) {
        TextView when = Look.medium(Ui.text(a, dates.shortDay(v.day) + "  " + dates.time(v.time), 14, Look.TEXT_2));
        String sub = TodayPage.join(v.specialty, v.place);
        LinearLayout row = Parts.card(a, null, v.doctor, sub, when);
        row.addView(tile(R.drawable.ic_heart), 0);
        ((LinearLayout.LayoutParams) row.getChildAt(1).getLayoutParams()).setMarginStart(Look.dp(a, 12));
        row.setTag("visit:" + v.id);
        return row;
    }

    private View tile(int icon) {
        View view = Parts.icon(a, icon, Look.TEXT, 40);
        int pad = Look.dp(a, 9);
        view.setPadding(pad, pad, pad, pad);
        view.setBackground(Look.tile(a, Look.ACCENT, 12));
        return view;
    }
}
