package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TextView;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The next-prayer pill (config.json prayer_times): name, time and how long until it, refreshed every minute. In
 * Ramadan, with ramadan_countdown, it counts down to iftar by day and to the end of suhoor by night instead. With
 * prayer_alert it breathes for the last five minutes before the mark.
 */
final class PrayerPill {
    private static final int[] NAMES = {R.string.prayer_fajr, R.string.prayer_sunrise, R.string.prayer_dhuhr,
            R.string.prayer_asr, R.string.prayer_maghrib, R.string.prayer_isha};
    /** prayer_alert: minutes before the mark during which the pill breathes. */
    static final int ALERT_MINUTES = 5;

    private final TextView pill;
    private final double latitude;
    private final double longitude;
    private final boolean ramadan;
    private final boolean alert;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::update;
    private ObjectAnimator breathing;

    PrayerPill(TextView pill, String latitude, String longitude, boolean ramadan, boolean alert) {
        this.pill = pill;
        this.latitude = Double.parseDouble(latitude);
        this.longitude = Double.parseDouble(longitude);
        this.ramadan = ramadan;
        this.alert = alert;
    }

    void start() {
        update();
    }

    void stop() {
        handler.removeCallbacks(tick);
        breathe(false);
    }

    private void update() {
        LocalDateTime now = LocalDateTime.now();
        boolean fasting = ramadan && Hijri.ramadan(now.toLocalDate());
        PrayerTimes.Next next = fasting
                ? PrayerTimes.fasting(now, latitude, longitude, ZoneId.systemDefault())
                : PrayerTimes.next(now, latitude, longitude, ZoneId.systemDefault());
        if (next == null) {
            pill.setVisibility(View.GONE);
            breathe(false);
        } else {
            pill.setText(text(pill.getContext(), next, now, fasting));
            pill.setVisibility(View.VISIBLE);
            breathe(alert && Duration.between(now, next.at).toMinutes() < ALERT_MINUTES);
        }
        // On the next whole minute, like the clock.
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 60_000L - (System.currentTimeMillis() % 60_000L) + 50);
    }

    /** A slow alpha pulse, started once and left running until the mark passes or Home leaves the screen. */
    private void breathe(boolean on) {
        if (on == (breathing != null)) return;
        if (on) {
            breathing = ObjectAnimator.ofFloat(pill, View.ALPHA, 1f, 0.35f);
            breathing.setDuration(1100);
            breathing.setRepeatCount(ValueAnimator.INFINITE);
            breathing.setRepeatMode(ValueAnimator.REVERSE);
            breathing.start();
        } else {
            breathing.cancel();
            breathing = null;
            pill.setAlpha(1f);
        }
    }

    static CharSequence text(Context ctx, PrayerTimes.Next next, LocalDateTime now) {
        return text(ctx, next, now, false);
    }

    static CharSequence text(Context ctx, PrayerTimes.Next next, LocalDateTime now, boolean fasting) {
        Locale locale = ctx.getResources().getConfiguration().getLocales().get(0);
        String pattern = DateFormat.is24HourFormat(ctx) ? "H:mm" : "h:mm a";
        String time = DateTimeFormatter.ofPattern(pattern, locale).format(next.at);
        long minutes = Math.max(0, Duration.between(now, next.at).toMinutes());
        String left = minutes >= 60
                ? ctx.getString(R.string.countdown_hours_minutes, minutes / 60, minutes % 60)
                : ctx.getString(R.string.countdown_minutes, minutes);
        // Plain bidi text: the first strong letter (the prayer name) sets the direction, so Arabic reads right to
        // left and the numbers keep their own runs. Marks and isolates scramble the Arabic countdown.
        if (fasting) {
            int mark = next.prayer == PrayerTimes.MAGHRIB ? R.string.ramadan_iftar : R.string.ramadan_suhoor;
            return ctx.getString(R.string.ramadan_pill, ctx.getString(mark), time, left);
        }
        return ctx.getString(R.string.prayer_pill, ctx.getString(NAMES[next.prayer]), time, left);
    }
}
