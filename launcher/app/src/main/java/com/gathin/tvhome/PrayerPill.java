package com.gathin.tvhome;

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

/** The next-prayer pill (config.json prayer_times): name, time and how long until it, refreshed every minute. */
final class PrayerPill {
    private static final int[] NAMES = {R.string.prayer_fajr, R.string.prayer_sunrise, R.string.prayer_dhuhr,
            R.string.prayer_asr, R.string.prayer_maghrib, R.string.prayer_isha};

    private final TextView pill;
    private final double latitude;
    private final double longitude;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::update;

    PrayerPill(TextView pill, String latitude, String longitude) {
        this.pill = pill;
        this.latitude = Double.parseDouble(latitude);
        this.longitude = Double.parseDouble(longitude);
    }

    void start() {
        update();
    }

    void stop() {
        handler.removeCallbacks(tick);
    }

    private void update() {
        LocalDateTime now = LocalDateTime.now();
        PrayerTimes.Next next = PrayerTimes.next(now, latitude, longitude, ZoneId.systemDefault());
        if (next == null) {
            pill.setVisibility(View.GONE);
        } else {
            pill.setText(text(pill.getContext(), next, now));
            pill.setVisibility(View.VISIBLE);
        }
        // On the next whole minute, like the clock.
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 60_000L - (System.currentTimeMillis() % 60_000L) + 50);
    }

    static CharSequence text(Context ctx, PrayerTimes.Next next, LocalDateTime now) {
        Locale locale = ctx.getResources().getConfiguration().getLocales().get(0);
        String pattern = DateFormat.is24HourFormat(ctx) ? "H:mm" : "h:mm a";
        String time = DateTimeFormatter.ofPattern(pattern, locale).format(next.at);
        long minutes = Math.max(0, Duration.between(now, next.at).toMinutes());
        String left = minutes >= 60
                ? ctx.getString(R.string.countdown_hours_minutes, minutes / 60, minutes % 60)
                : ctx.getString(R.string.countdown_minutes, minutes);
        // Plain bidi text: the first strong letter (the prayer name) sets the direction, so Arabic reads right to
        // left and the numbers keep their own runs. Marks and isolates scramble the Arabic countdown.
        return ctx.getString(R.string.prayer_pill, ctx.getString(NAMES[next.prayer]), time, left);
    }
}
