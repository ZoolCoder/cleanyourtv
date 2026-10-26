package com.gathin.tvhome;

import android.content.res.Resources;

import java.time.LocalDate;
import java.time.chrono.HijrahDate;
import java.time.temporal.ChronoField;

/** Today's Hijri date (config.json hijri_date), Umm al-Qura from java.time, e.g. "14 Jumada I 1448 AH". */
final class Hijri {

    private Hijri() {
    }

    static String today(Resources res) {
        return format(res, HijrahDate.from(LocalDate.now()));
    }

    /** Whether {@code day} falls in Ramadan, the ninth month (config.json ramadan_countdown). */
    static boolean ramadan(LocalDate day) {
        return HijrahDate.from(day).get(ChronoField.MONTH_OF_YEAR) == 9;
    }

    static String format(Resources res, HijrahDate date) {
        String[] months = res.getStringArray(R.array.hijri_months);
        int month = date.get(ChronoField.MONTH_OF_YEAR);
        return res.getString(R.string.hijri_date, date.get(ChronoField.DAY_OF_MONTH), months[month - 1],
                date.get(ChronoField.YEAR));
    }
}
