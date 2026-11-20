package com.zoolcoder.tv.health;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;

/**
 * The card opens this screen: a header with the date, then Today (everyone's doses and appointments) or one
 * member's page, and a hint bar that names what OK and Back do where the focus is. The screen redraws when the
 * records change and once a minute, as doses fall due.
 */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable minute = this::minute;
    private final Runnable changed = this::redraw;
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusChanged = (old, now) -> hints(now);

    private Store store;
    private Dates dates;
    private TodayPage today;
    private MemberPage member;
    private PhonePage phone;
    private TextView date, hijri, removeExample, addFromPhone, overlayOff;
    private LinearLayout hintBar;
    private boolean onMember, onPhone;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = Store.get(this);
        dates = new Dates(this);
        today = new TodayPage(this, store, dates, this::openMember, this::openPhone);
        member = new MemberPage(this, store, dates);
        phone = new PhonePage(this, new Phone(this));

        LinearLayout page = Ui.column(this);
        page.setBackground(Look.backdrop(this));
        page.setPadding(dp(48), dp(20), dp(48), dp(22));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.addView(header());

        FrameLayout content = new FrameLayout(this);
        content.setClipChildren(false);
        for (View v : new View[]{today.view(), member.view(), phone.view()}) {
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

        showToday();
    }

    private View header() {
        LinearLayout header = Ui.row(this);
        header.setClipChildren(false);
        header.addView(Look.appIcon(this), new LinearLayout.LayoutParams(dp(36), dp(36)));
        // Each script in its own view, so the Arabic gets its own size on the same line.
        TextView en = Ui.bold(Ui.text(this, getString(R.string.title_en) + " · ", 24, Look.TEXT));
        en.setPaddingRelative(dp(12), 0, 0, 0);
        header.addView(en);
        TextView ar = Ui.bold(Ui.text(this, getString(R.string.title_ar), 23, Look.TEXT));
        ar.setSingleLine(true);
        ar.setEllipsize(TextUtils.TruncateAt.END);
        ar.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        header.addView(ar, Ui.fill(1f));

        overlayOff = Ui.text(this, getString(R.string.overlay_off), 14, Look.DUE);
        header.addView(overlayOff, Ui.start(this, 12));
        removeExample = Ui.button(this, getString(R.string.remove_example), v -> {
            store.records.clear();
            store.changed();
            showToday();
        });
        Look.pill(this, removeExample, Look.PILL, Look.TEXT);
        removeExample.setTextSize(14);
        header.addView(removeExample, Ui.start(this, 16));
        addFromPhone = Ui.button(this, getString(R.string.add_from_phone), v -> openPhone());
        Look.pill(this, addFromPhone, Look.PILL, Look.TEXT);
        addFromPhone.setTextSize(14);
        addFromPhone.setTag(R.id.hint, TodayPage.HINT_PHONE);
        header.addView(addFromPhone, Ui.start(this, 12));

        LinearLayout when = Ui.column(this);
        when.setGravity(android.view.Gravity.END);
        date = Look.medium(Ui.text(this, "", 17, Look.TEXT));
        hijri = Ui.text(this, "", 14, Look.TEXT_2);
        when.addView(date);
        when.addView(hijri);
        header.addView(when, Ui.start(this, 20));
        return header;
    }

    private void showToday() {
        leavePhone();
        onMember = false;
        today.view().setVisibility(View.VISIBLE);
        member.view().setVisibility(View.GONE);
        redraw();
        today.focusFirst();
    }

    private void openMember(Member m) {
        onMember = true;
        today.view().setVisibility(View.GONE);
        member.view().setVisibility(View.VISIBLE);
        member.show(m);
        hints(getCurrentFocus());
    }

    private void openPhone() {
        onMember = false;
        onPhone = true;
        today.view().setVisibility(View.GONE);
        member.view().setVisibility(View.GONE);
        phone.view().setVisibility(View.VISIBLE);
        addFromPhone.setVisibility(View.GONE);
        phone.start();
        phone.focus();
        hints(getCurrentFocus());
    }

    private void leavePhone() {
        if (onPhone) phone.stop();
        onPhone = false;
        phone.view().setVisibility(View.GONE);
        addFromPhone.setVisibility(View.VISIBLE);
    }

    private void redraw() {
        Records r = store.records;
        LocalDate day = LocalDate.now();
        date.setText(dates.day(day));
        hijri.setText(dates.hijri(day));
        removeExample.setVisibility(r.example ? View.VISIBLE : View.GONE);
        overlayOff.setVisibility(r.empty() || Banner.allowed(this) ? View.GONE : View.VISIBLE);
        if (onMember && member.member() != null && r.member(member.member().id) == null) {
            showToday();
            return;
        }
        if (onPhone) phone.refresh();
        else if (onMember) member.refresh();
        else today.refresh();
        HealthService.sync(this);
        hints(getCurrentFocus());
    }

    private void minute() {
        redraw();
        long intoMinute = System.currentTimeMillis() % 60_000L;
        main.postDelayed(minute, 60_000L - intoMinute + 1000L);
    }

    /** On a member's page, Left or Right with nowhere further to go moves to the next member. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (onMember && event.getAction() == KeyEvent.ACTION_DOWN
                && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            View focus = getCurrentFocus();
            int direction = code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
            if (focus == null || focus.focusSearch(direction) == null) {
                boolean rtl = getWindow().getDecorView().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                boolean forward = (code == KeyEvent.KEYCODE_DPAD_RIGHT) != rtl;
                Member next = member.step(forward ? 1 : -1);
                if (next != null) member.show(next);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (onPhone) {
            showToday();
            return;
        }
        if (!onMember) {
            super.onBackPressed();
            return;
        }
        Member left = member.member();
        showToday();
        if (left != null) today.focusTab(left.id);
    }

    private void hints(View focus) {
        Object tag = focus == null ? null : focus.getTag(R.id.hint);
        int what = tag instanceof Integer ? (Integer) tag : 0;
        hintBar.removeAllViews();
        if (what == TodayPage.HINT_UNDO) hint(R.string.key_hold_ok, R.string.hint_undo, 0);
        else hint(R.string.key_ok, what == TodayPage.HINT_TAKE ? R.string.hint_take
                : what == TodayPage.HINT_OPEN ? R.string.hint_open
                : what == TodayPage.HINT_EXAMPLE ? R.string.hint_example
                : what == TodayPage.HINT_PHONE ? R.string.hint_phone : R.string.hint_select, 0);
        hint(R.string.key_back, onMember || onPhone ? R.string.hint_today : R.string.hint_exit, 24);
        if (onMember && store.records.members.size() > 1) hint(R.string.key_sides, R.string.hint_change_member, 24);
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
        store.listen(changed);
        if (onPhone) phone.start();
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(focusChanged);
    }

    @Override
    protected void onResume() {
        super.onResume();
        HealthService.screenOpen = true;
        minute();
    }

    @Override
    protected void onPause() {
        HealthService.screenOpen = false;
        main.removeCallbacks(minute);
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (onPhone) phone.stop();
        store.unlisten(changed);
        getWindow().getDecorView().getViewTreeObserver().removeOnGlobalFocusChangeListener(focusChanged);
        super.onStop();
    }

    private int dp(float v) {
        return Look.dp(this, v);
    }
}
