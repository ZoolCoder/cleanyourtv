package com.zoolcoder.tv.calendar;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;

/**
 * The reminder pop-up: a card in the top-end corner over whatever is playing. It never takes focus or
 * touches, so the remote keeps working underneath; it only says to open the app. One window and one set of
 * views are reused: reminders due together wait in a queue of 5 and show in turn. Main thread only.
 */
final class ReminderCard {
    static final int QUEUE = 5;

    private final Context context;
    private final WindowManager windows;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable advance = this::advance;
    private final ArrayDeque<Occurrence> queue = new ArrayDeque<>();
    private LinearLayout card;
    private View bar;
    private TextView when, title, date, time, who;
    private boolean attached;
    private int seconds;

    ReminderCard(Context context) {
        this.context = context;
        this.windows = context.getSystemService(WindowManager.class);
    }

    static boolean allowed(Context context) {
        return Settings.canDrawOverlays(context);
    }

    void show(Occurrence occurrence, int seconds) {
        this.seconds = seconds;
        if (queue.size() >= QUEUE) queue.pollFirst();
        queue.addLast(occurrence);
        if (!attached) advance();
    }

    private void advance() {
        main.removeCallbacks(advance);
        Occurrence next = queue.pollFirst();
        if (next == null) {
            detach();
            return;
        }
        // The permission can be revoked while the service runs: then nothing shows and nothing waits.
        if (!allowed(context)) {
            hide();
            return;
        }
        if (card == null) build();
        bind(next);
        try {
            if (attached) windows.updateViewLayout(card, params());
            else windows.addView(card, params());
            attached = true;
        } catch (RuntimeException e) {
            // BadTokenException and friends: the window cannot be had, so drop what waits too.
            hide();
            return;
        }
        main.postDelayed(advance, seconds * 1000L);
    }

    private void bind(Occurrence o) {
        Event e = o.event;
        Dates dates = new Dates(context);
        bar.setBackgroundColor(Look.WHO[e.color]);
        when.setText(until(o));
        title.setText(e.title);
        date.setText(dates.day(o.day) + "  ·  " + dates.hijri(o.day));
        if (e.allDay()) time.setText(R.string.all_day);
        else time.setText(e.end == null ? dates.time(e.start) : dates.time(e.start) + " – " + dates.time(e.end));
        who.setVisibility(e.who == null ? View.GONE : View.VISIBLE);
        if (e.who != null) who.setText(context.getString(R.string.for_who, e.who));
    }

    /** "in 10 minutes", "now", "tomorrow" from the start of the occurrence. */
    private String until(Occurrence o) {
        LocalDateTime start = o.day.atTime(o.event.allDay() ? Event.ALL_DAY_REMINDER : o.event.start);
        long minutes = Duration.between(LocalDateTime.now(), start).toMinutes();
        if (minutes <= 0) return context.getString(R.string.card_now);
        if (minutes < 60) return context.getString(R.string.card_in_minutes, (int) minutes);
        if (minutes < 20 * 60) return context.getString(R.string.card_in_hours, (int) Math.round(minutes / 60.0));
        return context.getString(R.string.card_tomorrow);
    }

    /** Dark card, 6 dp who-colour bar on the leading side, clipped to the rounded corners. */
    private void build() {
        card = new LinearLayout(context);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        Look.panel(context, card, 0xF0141522, 18);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);

        bar = new View(context);
        card.addView(bar, new LinearLayout.LayoutParams(Look.dp(context, 6), LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout column = Ui.column(context);
        int p = Look.dp(context, 16);
        column.setPadding(p, Look.dp(context, 14), p, p);
        card.addView(column, Ui.fill(1f));

        LinearLayout top = Ui.row(context);
        TextView heading = Ui.text(context, context.getString(R.string.card_heading), 14, Look.TEXT_2);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(heading, Ui.fill(1f));
        when = Look.medium(Ui.text(context, "", 14, Look.ACCENT));
        when.setPaddingRelative(Look.dp(context, 8), 0, 0, 0);
        top.addView(when);
        column.addView(top);

        title = Look.directed(Look.medium(Ui.text(context, "", 22, Look.TEXT)));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(title, Ui.spaced(context, 8, 0));
        date = Ui.text(context, "", 15, Look.TEXT_2);
        column.addView(date, Ui.spaced(context, 6, 0));
        LinearLayout meta = Ui.row(context);
        time = Ui.text(context, "", 15, Look.TEXT);
        meta.addView(time);
        who = Look.directed(Ui.text(context, "", 15, Look.TEXT_2));
        who.setSingleLine(true);
        who.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(who, Ui.start(context, 16));
        column.addView(meta, Ui.spaced(context, 4, 0));
        TextView open = Ui.text(context, context.getString(R.string.card_open), 13, Look.TEXT_3);
        column.addView(open, Ui.spaced(context, 12, 0));
    }

    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Look.dp(context, 400), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = Look.dp(context, 24);
        params.y = Look.dp(context, 24);
        return params;
    }

    /** Takes the window down and forgets every waiting reminder. */
    void hide() {
        main.removeCallbacks(advance);
        queue.clear();
        detach();
    }

    private void detach() {
        if (!attached) return;
        attached = false;
        try {
            windows.removeView(card);
        } catch (RuntimeException ignored) {
            // Already gone with its window.
        }
    }
}
