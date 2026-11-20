package com.zoolcoder.tv.health;

import android.content.Context;
import android.icu.text.SimpleDateFormat;
import android.icu.util.IslamicCalendar;
import android.icu.util.ULocale;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/** How dates read on screen, in the TV's language: Gregorian first, the Hijri (Umm al-Qura) date beside it. */
final class Dates {
    final Locale locale;
    private final DateTimeFormatter day, shortDay, time;
    private final SimpleDateFormat hijriDay;

    Dates(Context context) {
        locale = context.getResources().getConfiguration().getLocales().get(0);
        day = DateTimeFormatter.ofPattern("EEE d MMMM yyyy", locale);
        shortDay = DateTimeFormatter.ofPattern("EEE d MMM", locale);
        time = DateTimeFormatter.ofPattern(
                android.text.format.DateFormat.is24HourFormat(context) ? "HH:mm" : "h:mm a", locale);
        ULocale islamic = ULocale.forLocale(locale).setKeywordValue("calendar", "islamic-umalqura");
        hijriDay = new SimpleDateFormat("d MMMM y G", islamic);
        IslamicCalendar calendar = new IslamicCalendar(islamic);
        calendar.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        hijriDay.setCalendar(calendar);
    }

    String day(LocalDate date) {
        return day.format(date);
    }

    String shortDay(LocalDate date) {
        return shortDay.format(date);
    }

    String time(LocalTime t) {
        return time.format(t);
    }

    String hijri(LocalDate date) {
        return hijriDay.format(noon(date));
    }

    private static Date noon(LocalDate date) {
        return Date.from(date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant());
    }
}
