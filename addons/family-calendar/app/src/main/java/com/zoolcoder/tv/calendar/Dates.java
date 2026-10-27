package com.zoolcoder.tv.calendar;

import android.content.Context;
import android.icu.text.SimpleDateFormat;
import android.icu.util.IslamicCalendar;
import android.icu.util.ULocale;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Date;
import java.util.Locale;

/** How dates read on screen, in the TV's language: Gregorian first, the Hijri (Umm al-Qura) date beside it. */
final class Dates {
    final Locale locale;
    private final DateTimeFormatter month, day, shortDay, time;
    private final SimpleDateFormat hijriDay, hijriMonth, hijriMonthOnly;

    Dates(Context context) {
        locale = context.getResources().getConfiguration().getLocales().get(0);
        month = DateTimeFormatter.ofPattern("LLLL yyyy", locale);
        day = DateTimeFormatter.ofPattern("EEE d MMMM yyyy", locale);
        shortDay = DateTimeFormatter.ofPattern("EEE d MMM", locale);
        time = DateTimeFormatter.ofPattern(
                android.text.format.DateFormat.is24HourFormat(context) ? "HH:mm" : "h:mm a", locale);
        ULocale islamic = ULocale.forLocale(locale).setKeywordValue("calendar", "islamic-umalqura");
        hijriDay = new SimpleDateFormat("d MMMM y G", islamic);
        hijriMonth = new SimpleDateFormat("MMMM y G", islamic);
        hijriMonthOnly = new SimpleDateFormat("MMMM", islamic);
        for (SimpleDateFormat f : new SimpleDateFormat[]{hijriDay, hijriMonth, hijriMonthOnly}) {
            IslamicCalendar calendar = new IslamicCalendar(islamic);
            calendar.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
            f.setCalendar(calendar);
        }
    }

    String month(LocalDate date) {
        return month.format(date);
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

    /** "Jumada I – Jumada II 1448 AH" for the Hijri months a Gregorian month overlaps. */
    String hijriRange(LocalDate first, LocalDate last) {
        String end = hijriMonth.format(noon(last));
        String start = hijriMonth.format(noon(first));
        if (start.equals(end)) return end;
        String startMonth = hijriMonthOnly.format(noon(first));
        // Same Hijri year: name the year once.
        return (end.contains(start.substring(startMonth.length())) ? startMonth : start) + " – " + end;
    }

    static String weekday(DayOfWeek day, Locale locale, TextStyle style) {
        return day.getDisplayName(style, locale);
    }

    private static Date noon(LocalDate date) {
        return Date.from(date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant());
    }
}
