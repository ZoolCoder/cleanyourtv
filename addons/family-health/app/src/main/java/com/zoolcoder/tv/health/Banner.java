package com.zoolcoder.tv.health;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * The reminder over whatever is playing: a glass strip along the bottom of the screen that takes the remote
 * while it shows. A dose has Later and Taken, with Taken focused; an appointment has OK. Back, Later and the
 * 60-second timeout all put the dose off for 15 minutes, so nothing is lost by ignoring it. Reminders due together
 * wait in a queue and show in turn. One window, reused. Main thread only.
 */
final class Banner {
    static final int QUEUE = 6;
    private static final long SHOW_MILLIS = 60_000L, THANKS_MILLIS = 1_500L;

    private final Context context;
    private final Store store;
    private final WindowManager windows;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::timedOut;
    private final Runnable advance = this::next;
    private final ArrayDeque<Plan.Entry> queue = new ArrayDeque<>();
    /** The keys showing or waiting, so the minute tick does not queue the same reminder twice. */
    private final Set<String> pending = new HashSet<>();
    private Frame frame;
    private LinearLayout buttons, discSlot;
    private TextView who, title, sub, later, taken, thanks;
    private Plan.Entry current;
    private boolean attached;

    Banner(Context context) {
        this.context = context;
        this.store = Store.get(context);
        this.windows = context.getSystemService(WindowManager.class);
    }

    static boolean allowed(Context context) {
        return Settings.canDrawOverlays(context);
    }

    void offer(Plan.Entry entry) {
        if (pending.contains(entry.key)) return;
        if (queue.size() >= QUEUE) pending.remove(queue.pollFirst().key);
        queue.addLast(entry);
        pending.add(entry.key);
        if (!attached) next();
    }

    private void next() {
        main.removeCallbacks(timeout);
        if (current != null) pending.remove(current.key);
        current = queue.pollFirst();
        if (current == null) {
            detach();
            return;
        }
        // The permission can be revoked while the service runs: then nothing shows and nothing waits.
        if (!allowed(context)) {
            hide();
            return;
        }
        if (frame == null) build();
        bind(current);
        try {
            if (!attached) windows.addView(frame, params());
            attached = true;
        } catch (RuntimeException e) {
            // BadTokenException and friends: the window cannot be had, so drop what waits too.
            hide();
            return;
        }
        (current.dose() ? taken : later).requestFocus();
        main.postDelayed(timeout, SHOW_MILLIS);
    }

    private void bind(Plan.Entry e) {
        Dates dates = new Dates(context);
        discSlot.removeAllViews();
        discSlot.addView(Parts.disc(context, e.member, 56));
        who.setText(e.member.name);
        thanks.setVisibility(View.GONE);
        buttons.setVisibility(View.VISIBLE);
        if (e.dose()) {
            title.setText(context.getString(R.string.banner_now, e.medicine.title()));
            sub.setText(TodayPage.join(e.medicine.how, dates.time(e.time)));
            later.setText(R.string.later);
            taken.setVisibility(View.VISIBLE);
        } else {
            Visit v = e.visit;
            boolean tomorrow = v.day.equals(LocalDate.now().plusDays(1));
            String when = tomorrow ? context.getString(R.string.banner_tomorrow, dates.time(v.time))
                    : context.getString(R.string.banner_today, dates.time(v.time));
            title.setText(TodayPage.join(v.doctor, when));
            sub.setText(TodayPage.join(v.specialty, v.place));
            later.setText(R.string.ok);
            taken.setVisibility(View.GONE);
        }
        sub.setVisibility(sub.getText().length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** Dark glass with a white rim, the member's disc at the start and the buttons at the end. */
    private void build() {
        frame = new Frame(context);
        frame.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        LinearLayout strip = Ui.row(context);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        // The focused button grows a little: let it spill over the strip's padding.
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        android.graphics.drawable.GradientDrawable glass = Look.shape(context, 0xEB2A1638, 28);
        glass.setStroke(Look.dp(context, 1.5f), 0x4DFFFFFF);
        strip.setBackground(glass);
        strip.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        strip.setElevation(Look.dp(context, 12));
        int p = Look.dp(context, 18);
        strip.setPadding(p, Look.dp(context, 14), p, Look.dp(context, 14));
        frame.addView(strip, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        discSlot = Ui.row(context);
        strip.addView(discSlot);

        LinearLayout text = Ui.column(context);
        who = Look.directed(Look.medium(Ui.text(context, "", 15, Look.ACCENT)));
        who.setSingleLine(true);
        text.addView(who);
        title = Look.directed(Ui.bold(Ui.text(context, "", 22, Look.TEXT)));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title, Ui.spaced(context, 2, 0));
        sub = Look.directed(Ui.text(context, "", 15, Look.TEXT_2));
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(sub, Ui.spaced(context, 2, 0));
        LinearLayout.LayoutParams fill = Ui.fill(1f);
        fill.setMarginStart(Look.dp(context, 16));
        fill.setMarginEnd(Look.dp(context, 16));
        strip.addView(text, fill);

        buttons = Ui.row(context);
        buttons.setClipChildren(false);
        later = Ui.button(context, "", v -> putOff());
        Look.pill(context, later, Look.PILL, Look.TEXT);
        later.setMinWidth(Look.dp(context, 150));
        buttons.addView(later);
        taken = Ui.bold(Ui.button(context, "✓  " + context.getString(R.string.taken), v -> take()));
        Look.pill(context, taken, Look.GREEN, Look.TEXT);
        taken.setMinWidth(Look.dp(context, 170));
        buttons.addView(taken, Ui.start(context, 12));
        strip.addView(buttons);

        thanks = Look.medium(Ui.text(context, "", 20, Look.TAKEN));
        thanks.setVisibility(View.GONE);
        strip.addView(thanks);
    }

    private void take() {
        Plan.Entry e = current;
        if (e == null || !e.dose()) return;
        main.removeCallbacks(timeout);
        long now = System.currentTimeMillis();
        if (store.records.take(e.key, now)) store.changed();
        buttons.setVisibility(View.GONE);
        thanks.setText("✓  " + context.getString(R.string.taken_at, new Dates(context).time(LocalTime.now())));
        thanks.setVisibility(View.VISIBLE);
        frame.requestFocus();
        main.postDelayed(advance, THANKS_MILLIS);
    }

    /** Later, Back, or no answer: a dose comes back in 15 minutes; an appointment reminder is done with. */
    private void putOff() {
        Plan.Entry e = current;
        if (e == null) return;
        if (e.dose()) store.records.snooze(e.key, System.currentTimeMillis() + Plan.SNOOZE.toMillis());
        else store.records.told.add(e.key);
        store.changed();
        next();
    }

    private void timedOut() {
        putOff();
    }

    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM;
        int side = Look.dp(context, 64);
        frame.setPadding(side, 0, side, Look.dp(context, 36));
        return params;
    }

    /** Takes the window down and forgets every waiting reminder; they come back at the next tick if still due. */
    void hide() {
        main.removeCallbacks(timeout);
        main.removeCallbacks(advance);
        queue.clear();
        pending.clear();
        current = null;
        detach();
    }

    private void detach() {
        if (!attached) return;
        attached = false;
        try {
            windows.removeView(frame);
        } catch (RuntimeException ignored) {
            // Already gone with its window.
        }
    }

    /** The window's root: Back puts the reminder off instead of reaching the app underneath. */
    private final class Frame extends FrameLayout {
        Frame(Context context) {
            super(context);
            setFocusable(true);
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE) {
                if (event.getAction() == KeyEvent.ACTION_UP && buttons.getVisibility() == View.VISIBLE) putOff();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }
}
