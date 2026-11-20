package com.zoolcoder.tv.health;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The main screen: a strip of family members to filter by, then today's doses and appointments in three
 * columns, Now, Later today and Done. A due dose has a big Taken button; OK on any other dose takes it early or
 * late, and holding OK on a taken one undoes it. OK on a member opens their page.
 */
final class TodayPage {
    /** What OK does on the focused view, for the hint bar. */
    static final int HINT_TAKE = 1, HINT_UNDO = 2, HINT_OPEN = 3, HINT_EXAMPLE = 4, HINT_PHONE = 5;

    private final Activity a;
    private final Store store;
    private final Dates dates;
    private final Consumer<Member> open;
    private final Runnable phone;
    private View addFromPhone;
    private final LinearLayout root, people, now, later, done;
    private final View empty, columns;
    private String filter;
    /** The first due dose's Taken button, where the screen opens. */
    private View firstTaken;
    private List<String> shownPeople = new ArrayList<>();

    TodayPage(Activity a, Store store, Dates dates, Consumer<Member> open, Runnable phone) {
        this.a = a;
        this.store = store;
        this.dates = dates;
        this.open = open;
        this.phone = phone;
        root = Ui.column(a);
        people = Ui.row(a);
        root.addView(people, Ui.spaced(a, 0, 0));

        LinearLayout row = Ui.row(a);
        row.setGravity(android.view.Gravity.TOP);
        columns = row;
        // Now is wider: its card carries two buttons.
        now = column(row, a.getString(R.string.heading_now), R.drawable.ic_bell, Look.DUE, 1.3f);
        later = column(row, a.getString(R.string.heading_later), R.drawable.ic_clock, Look.DUE, 0.9f);
        done = column(row, a.getString(R.string.heading_done), R.drawable.ic_check, Look.TAKEN, 1.1f);
        empty = emptyState();

        FrameLayout body = new FrameLayout(a);
        body.setClipChildren(false);
        body.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        grow.topMargin = Look.dp(a, 16);
        root.addView(body, grow);
    }

    View view() {
        return root;
    }

    /** A glass panel with its heading and a scrolling list, added to {@code row}; returns the list. */
    private LinearLayout column(LinearLayout row, String label, int icon, int color, float share) {
        LinearLayout panel = Ui.column(a);
        Look.panel(a, panel, Look.SURFACE, 24);
        panel.setPadding(Look.dp(a, 14), Look.dp(a, 14), Look.dp(a, 14), Look.dp(a, 6));
        panel.addView(Parts.heading(a, label, icon, color));
        LinearLayout list = Parts.scrollList(a, panel);
        LinearLayout.LayoutParams weight = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, share);
        if (row.getChildCount() > 0) weight.setMarginStart(Look.dp(a, 14));
        row.addView(panel, weight);
        return list;
    }

    private View emptyState() {
        LinearLayout box = Ui.column(a);
        box.setGravity(android.view.Gravity.CENTER);
        Look.panel(a, box, Look.SURFACE, 28);
        box.addView(Parts.icon(a, R.drawable.ic_heart, Look.ACCENT, 56));
        TextView title = Look.medium(Ui.text(a, a.getString(R.string.empty_title), 24, Look.TEXT));
        box.addView(title, Ui.start(a, 0));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).topMargin = Look.dp(a, 14);
        TextView body = Ui.text(a, a.getString(R.string.empty_body), 16, Look.TEXT_2);
        body.setGravity(android.view.Gravity.CENTER);
        body.setMaxWidth(Look.dp(a, 520));
        box.addView(body, Ui.start(a, 0));
        ((LinearLayout.LayoutParams) body.getLayoutParams()).topMargin = Look.dp(a, 8);
        LinearLayout buttons = Ui.row(a);
        TextView fromPhone = Ui.button(a, a.getString(R.string.add_from_phone), v -> phone.run());
        Look.pill(a, fromPhone, Look.ACCENT, Look.TEXT);
        fromPhone.setTag(R.id.hint, HINT_PHONE);
        buttons.addView(fromPhone);
        addFromPhone = fromPhone;
        TextView example = Ui.button(a, a.getString(R.string.show_example), v -> {
            Example.fill(a, store.records);
            store.changed();
        });
        Look.pill(a, example, Look.PILL, Look.TEXT);
        example.setTag(R.id.hint, HINT_EXAMPLE);
        buttons.addView(example, Ui.start(a, 16));
        box.addView(buttons, Ui.start(a, 0));
        ((LinearLayout.LayoutParams) buttons.getLayoutParams()).topMargin = Look.dp(a, 22);
        return box;
    }

    /** Draws everything again from the store, keeping the focus on the same item when it is still there. */
    void refresh() {
        Object focusedKey = focusedKey();
        boolean hadFocus = root.findFocus() != null;
        Records r = store.records;
        boolean nothing = r.empty();
        empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
        people.setVisibility(nothing ? View.GONE : View.VISIBLE);
        columns.setVisibility(nothing ? View.GONE : View.VISIBLE);
        if (filter != null && r.member(filter) == null) filter = null;
        drawPeople(r);

        firstTaken = null;
        now.removeAllViews();
        later.removeAllViews();
        done.removeAllViews();
        LocalDateTime clock = LocalDateTime.now();
        for (Plan.Entry e : Plan.day(r, clock, filter)) {
            switch (e.state) {
                case NOW:
                    now.addView(e.dose() ? dueCard(e) : visitCard(e), gap(now));
                    break;
                case LATER:
                    later.addView(e.dose() ? doseCard(e) : visitCard(e), gap(later));
                    break;
                default:
                    done.addView(e.dose() ? doseCard(e) : visitCard(e), gap(done));
            }
        }
        if (now.getChildCount() == 0) now.addView(Parts.quiet(a, a.getString(R.string.nothing_now)));
        if (later.getChildCount() == 0) later.addView(Parts.quiet(a, a.getString(R.string.nothing_later)));
        if (done.getChildCount() == 0) done.addView(Parts.quiet(a, a.getString(R.string.nothing_done)));
        if (focusedKey != null) {
            View same = root.findViewWithTag(focusedKey);
            // A dose just taken or put off left its due card: follow it to its new card.
            if (same == null && focusedKey.toString().contains(":")) {
                same = root.findViewWithTag(focusedKey.toString().substring(0, focusedKey.toString().lastIndexOf(':')));
            }
            if (same != null) same.requestFocus();
        }
        // The focused view went away with the redraw (the example button, a removed card): start again.
        if (root.isShown() && root.findFocus() == null && (hadFocus || root.getRootView().findFocus() == null)) {
            focusFirst();
        }
    }

    /** Where the focus goes when the screen opens: the due dose's Taken button, else the first card, else a tab. */
    void focusFirst() {
        if (store.records.empty()) {
            addFromPhone.requestFocus();
            return;
        }
        if (firstTaken != null && firstTaken.requestFocus()) return;
        for (LinearLayout list : new LinearLayout[]{now, later, done}) {
            View first = list.getChildAt(0);
            if (first != null && first.isFocusable() && first.requestFocus()) return;
        }
        if (people.getChildCount() > 0) people.getChildAt(0).requestFocus();
    }

    /** Back from a member's page lands on their tab, where OK opened it. */
    void focusTab(String memberId) {
        View tab = people.findViewWithTag("tab:" + memberId);
        if (tab == null || !tab.requestFocus()) focusFirst();
    }

    private Object focusedKey() {
        View focused = root.findFocus();
        return focused == null ? null : focused.getTag();
    }

    private LinearLayout.LayoutParams gap(LinearLayout list) {
        return Ui.spaced(a, list.getChildCount() == 0 ? 0 : 10, 0);
    }

    /** The member strip is rebuilt only when the family changes, so moving along it keeps its focus. */
    private void drawPeople(Records r) {
        List<String> ids = new ArrayList<>();
        for (Member m : r.members) ids.add(m.id + "\n" + m.name + "\n" + m.color);
        if (!ids.equals(shownPeople)) {
            shownPeople = ids;
            people.removeAllViews();
            addTab(null, a.getString(R.string.all));
            for (Member m : r.members) addTab(m, m.name);
        }
        for (int i = 0; i < people.getChildCount(); i++) {
            View tab = people.getChildAt(i);
            tab.setSelected(Objects.equals(tab.getTag(R.id.member), filter));
        }
    }

    private void addTab(Member m, String label) {
        LinearLayout tab = Parts.tab(a, m, label);
        String id = m == null ? null : m.id;
        tab.setTag("tab:" + id);
        tab.setTag(R.id.member, id);
        tab.setTag(R.id.hint, m == null ? 0 : HINT_OPEN);
        View.OnFocusChangeListener lift = tab.getOnFocusChangeListener();
        tab.setOnFocusChangeListener((v, has) -> {
            lift.onFocusChange(v, has);
            if (has && !Objects.equals(filter, id)) {
                filter = id;
                refresh();
            }
        });
        if (m != null) tab.setOnClickListener(v -> open.accept(m));
        LinearLayout.LayoutParams params = Ui.start(a, people.getChildCount() == 0 ? 0 : 12);
        people.addView(tab, params);
    }

    /** A dose that is due: the card with Later and Taken; Taken holds the focus. */
    private View dueCard(Plan.Entry e) {
        LinearLayout card = Ui.column(a);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setPadding(Look.dp(a, 14), Look.dp(a, 12), Look.dp(a, 14), Look.dp(a, 14));
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable lit = Look.lit(a, 0x29FFFFFF, 22);
        lit.setStroke(Look.dp(a, 2), 0xD9FFFFFF);
        states.addState(new int[]{android.R.attr.state_selected}, lit);
        states.addState(new int[0], Look.shape(a, Look.SURFACE, 22));
        card.setBackground(states);

        LinearLayout top = Ui.row(a);
        top.addView(Parts.disc(a, e.member, 40));
        // After Later the card stays here, still due, and says when the reminder comes back instead of how to take it.
        Long until = store.records.snoozed.get(e.key);
        String sub = until == null || until <= System.currentTimeMillis() ? e.medicine.how
                : a.getString(R.string.reminds_at, dates.time(java.time.Instant.ofEpochMilli(until)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalTime()));
        LinearLayout lines = Parts.lines(a, e.medicine.title(), sub, 18);
        if (until != null && until > System.currentTimeMillis()) {
            ((TextView) lines.getChildAt(1)).setTextColor(Look.DUE);
            ((TextView) lines.getChildAt(1)).setTextSize(13);
        }
        LinearLayout.LayoutParams fill = Ui.fill(1f);
        fill.setMarginStart(Look.dp(a, 12));
        top.addView(lines, fill);
        top.addView(Parts.badge(a, R.drawable.ic_pill, Look.TEXT, false, 34));
        card.addView(top);

        LinearLayout buttons = Ui.row(a);
        buttons.setClipChildren(false);
        TextView laterButton = Ui.button(a, a.getString(R.string.later), v -> {
            store.records.snooze(e.key, System.currentTimeMillis() + Plan.SNOOZE.toMillis());
            store.changed();
        });
        Look.pill(a, laterButton, Look.PILL, Look.TEXT);
        laterButton.setTag(e.key + ":later");
        TextView taken = Ui.button(a, "✓  " + a.getString(R.string.taken), v -> take(e));
        Look.pill(a, taken, Look.GREEN, Look.TEXT);
        Ui.bold(taken);
        taken.setTag(e.key + ":taken");
        taken.setTag(R.id.hint, HINT_TAKE);
        if (firstTaken == null) firstTaken = taken;
        for (TextView b : new TextView[]{laterButton, taken}) {
            b.setSingleLine(true);
            b.setTextSize(17);
            b.setPadding(Look.dp(a, 12), 0, Look.dp(a, 12), 0);
            b.setMinHeight(Look.dp(a, 46)); // after setSingleLine, which resets it to one line
            b.setOnFocusChangeListener((v, has) -> {
                Look.lift(v, has, 1.05f);
                card.setSelected(laterButton.hasFocus() || taken.hasFocus());
            });
        }
        buttons.addView(laterButton, Ui.fill(1f));
        LinearLayout.LayoutParams second = Ui.fill(1f);
        second.setMarginStart(Look.dp(a, 10));
        buttons.addView(taken, second);
        card.addView(buttons, Ui.spaced(a, 12, 0));
        return card;
    }

    /** A dose before or after its time: OK takes it (early or late), holding OK on a taken one undoes it. */
    private View doseCard(Plan.Entry e) {
        String time = dates.time(e.time);
        String sub;
        View end;
        int hint;
        if (e.state == Plan.State.DONE) {
            sub = a.getString(R.string.taken_at, dates.time(java.time.Instant.ofEpochMilli(e.takenAt)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalTime()));
            end = Parts.badge(a, R.drawable.ic_check, Look.TAKEN, true, 30);
            hint = HINT_UNDO;
        } else if (e.state == Plan.State.MISSED) {
            sub = a.getString(R.string.missed_at, time);
            end = Parts.badge(a, R.drawable.ic_close, Look.MISSED, true, 30);
            hint = HINT_TAKE;
        } else {
            sub = join(e.medicine.how, time);
            end = Parts.badge(a, R.drawable.ic_pill, Look.TEXT_2, false, 30);
            hint = HINT_TAKE;
        }
        LinearLayout card = Parts.card(a, e.member, e.medicine.title(), sub, end);
        card.setTag(e.key);
        card.setTag(R.id.hint, hint);
        if (e.state == Plan.State.DONE) {
            // The key's own long-press flag, not the view's timer: it also comes with keys sent over ADB, and the
            // repeats and release that follow land on the redrawn card without pressing it.
            card.setOnKeyListener((v, code, event) -> {
                if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                        && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0
                        && store.records.undo(e.key)) {
                    store.changed();
                }
                return event.getRepeatCount() > 0;
            });
        } else {
            card.setOnClickListener(v -> take(e));
        }
        return card;
    }

    private View visitCard(Plan.Entry e) {
        Visit v = e.visit;
        int color = e.state == Plan.State.NOW ? Look.DUE : e.state == Plan.State.DONE ? Look.TEXT_3 : Look.UPCOMING;
        String title = join(v.doctor, v.specialty);
        String sub = join(dates.time(v.time), v.place);
        LinearLayout card = Parts.card(a, e.member, title, sub, Parts.badge(a, R.drawable.ic_heart, color, false, 30));
        card.setTag(e.key);
        card.setTag(R.id.hint, HINT_OPEN);
        card.setOnClickListener(x -> open.accept(e.member));
        return card;
    }

    private void take(Plan.Entry e) {
        if (store.records.take(e.key, System.currentTimeMillis())) store.changed();
    }

    static String join(String first, String second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty()) return first;
        return first + "  ·  " + second;
    }
}
