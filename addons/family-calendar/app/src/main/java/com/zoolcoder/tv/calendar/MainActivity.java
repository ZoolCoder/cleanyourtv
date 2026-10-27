package com.zoolcoder.tv.calendar;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;

/**
 * The card opens this screen: a header with the Month, Today and Connect tabs (and Away when the build sets a
 * relay), the page of the selected tab, and
 * a hint bar that names what OK and Back do where the focus is. Events arrive from phones through
 * CalendarService; the screen redraws when the store changes.
 */
public class MainActivity extends Activity {
    private static final int MONTH = 0, TODAY = 1, CONNECT = 2, AWAY = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable redraw = this::showEvents;
    private final Runnable changed = () -> main.post(redraw);
    /** Kept in a field: SharedPreferences holds its listeners weakly. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsChanged = (p, key) -> refreshConnect();
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusChanged = (old, now) -> hints(now);

    private Store store;
    private EventStore events;
    private MonthPage month;
    private TodayPage today;
    private ConnectPage connect;
    /** Null when no relay is set. */
    private AwayPage away;
    private SharedPreferences awayPrefs;
    private EventDialog dialog;
    private TextView[] tabs;
    private View[] pages;
    private int current = -1;
    private LinearLayout hintBar;
    private LocalDate shownDay;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new Store(this);
        events = EventStore.in(getFilesDir());
        Dates dates = new Dates(this);
        dialog = new EventDialog(this, events);
        month = new MonthPage(this, dates, dialog::show, () -> tabs[current].requestFocus());
        today = new TodayPage(this, dates, dialog::show);
        connect = new ConnectPage(this, store);
        AwayPoller poller = CalendarService.away(this, events);
        if (poller != null) {
            awayPrefs = getSharedPreferences(AwayBox.FILE, MODE_PRIVATE);
            away = new AwayPage(this, store, new AwayBox(awayPrefs), poller, dialog);
        }
        tabs = new TextView[away == null ? 3 : 4];
        pages = new View[tabs.length];
        if (away != null) pages[AWAY] = away.view();
        pages[MONTH] = month.view();
        pages[TODAY] = today.view();
        pages[CONNECT] = connect.view();

        LinearLayout page = Ui.column(this);
        page.setBackground(Look.backdrop(this));
        page.setPadding(dp(48), dp(20), dp(48), dp(24));
        page.setClipChildren(false);
        // The lifted end tab reaches into the 48 dp side padding; clipping cut its outline.
        page.setClipToPadding(false);
        page.addView(header());

        FrameLayout content = new FrameLayout(this);
        content.setClipChildren(false);
        for (View v : pages) {
            v.setVisibility(View.GONE);
            content.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        contentParams.topMargin = dp(16);
        page.addView(content, contentParams);

        hintBar = Ui.row(this);
        hintBar.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        page.addView(hintBar, Ui.spaced(this, 12, 0));
        setContentView(page);

        showEvents();
        select(MONTH);
        month.focusSelected();
    }

    private View header() {
        LinearLayout header = Ui.row(this);
        header.setClipChildren(false);
        View marker = new View(this);
        marker.setBackground(Look.shape(this, Look.ACCENT, 2));
        header.addView(marker, new LinearLayout.LayoutParams(dp(4), dp(26)));
        // Each script in its own view, so the Arabic gets its own size on the same line.
        TextView en = Ui.bold(Ui.text(this, getString(R.string.title_en) + " · ", 26, Look.TEXT));
        en.setPaddingRelative(dp(12), 0, 0, 0);
        header.addView(en);
        TextView ar = Ui.bold(Ui.text(this, getString(R.string.title_ar), 25, Look.TEXT));
        // Beside the English, not pushed to the far end of its stretched view by its RTL alignment.
        ar.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        header.addView(ar, Ui.fill(1f));
        int[] labels = {R.string.tab_month, R.string.tab_today, R.string.tab_connect, R.string.tab_away};
        for (int i = 0; i < tabs.length; i++) {
            int index = i;
            TextView tab = Ui.button(this, getString(labels[i]), v -> enter(index));
            tab.setTextSize(16);
            tab.setMinHeight(dp(38));
            Look.tab(this, tab);
            tab.setOnFocusChangeListener((v, has) -> {
                Look.lift(v, has, 1.04f);
                if (has) select(index);
            });
            tab.setOnKeyListener((v, code, e) -> e.getAction() == KeyEvent.ACTION_DOWN
                    && code == KeyEvent.KEYCODE_DPAD_DOWN && enter(index));
            tabs[i] = tab;
            header.addView(tab, i == 0 ? Ui.start(this, 16) : Ui.start(this, 10));
        }
        return header;
    }

    private void select(int index) {
        if (index == current) return;
        current = index;
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            tabs[i].setSelected(i == index);
        }
        if (index == CONNECT) connect.refresh();
        if (index == AWAY) away.refresh();
    }

    /** OK or Down on a tab: into its page. */
    private boolean enter(int index) {
        select(index);
        if (index == MONTH) month.focusSelected();
        else if (index == TODAY) {
            if (!today.focusFirst()) return true;
        } else if (index == CONNECT) connect.focus();
        else away.focus();
        return true;
    }

    private void showEvents() {
        shownDay = LocalDate.now();
        month.setEvents(events.events());
        today.setEvents(events.events());
    }

    @Override
    public void onBackPressed() {
        View focus = getCurrentFocus();
        if (current == MONTH && month.inAgenda(focus)) {
            month.backToGrid();
        } else if (current == MONTH && month.inGrid(focus) && !LocalDate.now().equals(month.selected())) {
            month.moveTo(LocalDate.now());
        } else if (current == TODAY && today.contains(focus)) {
            select(MONTH);
            month.moveTo(LocalDate.now());
        } else {
            super.onBackPressed();
        }
    }

    private void hints(View focus) {
        int ok, back;
        boolean seek = false, up = false;
        if (current == MONTH && month.inAgenda(focus)) {
            ok = R.string.hint_open_event;
            back = R.string.hint_days;
        } else if (current == MONTH && month.inGrid(focus)) {
            ok = R.string.hint_open_day;
            back = LocalDate.now().equals(month.selected()) ? R.string.hint_home : R.string.hint_go_today;
            seek = true;
            up = month.inTopWeek(focus);
        } else if (current == TODAY && today.contains(focus)) {
            ok = R.string.hint_open_event;
            back = R.string.hint_month;
        } else {
            ok = R.string.hint_select;
            back = R.string.hint_home;
        }
        hintBar.removeAllViews();
        hint(R.string.key_ok, ok, 0);
        hint(R.string.key_back, back, 24);
        if (seek) hint(R.string.key_seek, R.string.hint_switch_month, 24);
        if (up) hint(R.string.key_up, R.string.hint_tabs, 24);
    }

    private void hint(int key, int label, int start) {
        hintBar.addView(Ui.chip(this, getString(key)), Ui.start(this, start));
        TextView view = Ui.text(this, getString(label), 14, Look.TEXT_2);
        view.setPaddingRelative(dp(8), 0, 0, 0);
        hintBar.addView(view);
    }

    @Override
    protected void onStart() {
        super.onStart();
        store.prefs().registerOnSharedPreferenceChangeListener(prefsChanged);
        if (awayPrefs != null) awayPrefs.registerOnSharedPreferenceChangeListener(prefsChanged);
        events.listen(changed);
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(focusChanged);
        store.pin();
        // Starts the server again if the system dropped it.
        CalendarService.sync(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A new day since the screen was drawn: today's ring and the Today list move with it.
        if (!LocalDate.now().equals(shownDay)) showEvents();
        refreshConnect();
        hints(getCurrentFocus());
    }

    @Override
    protected void onStop() {
        dialog.dismiss();
        connect.stop();
        main.removeCallbacks(redraw);
        events.unlisten(changed);
        store.prefs().unregisterOnSharedPreferenceChangeListener(prefsChanged);
        if (awayPrefs != null) awayPrefs.unregisterOnSharedPreferenceChangeListener(prefsChanged);
        if (away != null) away.stop();
        getWindow().getDecorView().getViewTreeObserver().removeOnGlobalFocusChangeListener(focusChanged);
        super.onStop();
    }

    private void refreshConnect() {
        connect.refresh();
        if (away != null) away.refresh();
    }

    private int dp(float v) {
        return Look.dp(this, v);
    }
}
