package com.zoolcoder.tv.calendar;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

/**
 * One family event: plain Java, no Android, so the rules can be tested on the JVM. An event without a start
 * time lasts all day. A repeating event occurs on its date and then every week or every year after it.
 */
final class Event {
    enum Repeat { NONE, WEEKLY, YEARLY }

    /** When the pop-up comes, relative to the start; an all-day event "starts" at 09:00 for this. */
    enum Reminder {
        NONE(-1), AT_TIME(0), MIN_10(10), HOUR_1(60), DAY_1(24 * 60);

        final int minutesBefore;

        Reminder(int minutesBefore) {
            this.minutesBefore = minutesBefore;
        }
    }

    static final LocalTime ALL_DAY_REMINDER = LocalTime.of(9, 0);
    static final int COLORS = 6;
    static final LocalDate FIRST = LocalDate.of(2000, 1, 1), LAST = LocalDate.of(2100, 12, 31);

    final String id, title, who;
    final LocalDate date;
    /** Null for an all-day event. */
    final LocalTime start, end;
    final Repeat repeat;
    final Reminder reminder;
    /** 0 to 5, an index into the who palette. */
    final int color;
    /** Sent by a relative outside the home, through the Family away relay. */
    final boolean away;

    Event(String id, String title, LocalDate date, LocalTime start, LocalTime end, String who, int color,
          Repeat repeat, Reminder reminder) {
        this(id, title, date, start, end, who, color, repeat, reminder, false);
    }

    Event(String id, String title, LocalDate date, LocalTime start, LocalTime end, String who, int color,
          Repeat repeat, Reminder reminder, boolean away) {
        this.away = away;
        this.id = id;
        this.title = title;
        this.date = date;
        this.start = start;
        this.end = start == null ? null : end;
        this.who = who;
        this.color = color;
        this.repeat = repeat;
        this.reminder = reminder;
    }

    boolean allDay() {
        return start == null;
    }

    /** A stable colour for a name, so "Dad" is the same colour on every event and every TV. */
    static int colorFor(String who) {
        if (who == null) return 0;
        return Math.floorMod(who.toLowerCase(Locale.ROOT).hashCode(), COLORS);
    }

    /** True when the event happens on {@code day}. A yearly event from 29 February falls on 28 February in other years. */
    boolean occursOn(LocalDate day) {
        if (day.isBefore(date)) return false;
        switch (repeat) {
            case WEEKLY:
                return ChronoUnit.DAYS.between(date, day) % 7 == 0;
            case YEARLY:
                if (day.getMonthValue() != date.getMonthValue()) return false;
                if (day.getDayOfMonth() == date.getDayOfMonth()) return true;
                return date.getMonthValue() == 2 && date.getDayOfMonth() == 29
                        && day.getDayOfMonth() == 28 && !day.isLeapYear();
            default:
                return day.equals(date);
        }
    }

    /** When the reminder for the occurrence on {@code day} is due, or null when the event has none. */
    LocalDateTime reminderAt(LocalDate day) {
        if (reminder == Reminder.NONE) return null;
        return day.atTime(allDay() ? ALL_DAY_REMINDER : start).minusMinutes(reminder.minutesBefore);
    }

    /** Identifies one occurrence, so its reminder pops up once. */
    String key(LocalDate day) {
        return id + "@" + day;
    }

    /** Thrown with a short reason when a field from a phone is not acceptable. */
    static final class Invalid extends Exception {
        Invalid(String reason) {
            super(reason);
        }
    }

    /**
     * Reads the fields a phone sends: title, date (yyyy-MM-dd), start and end (HH:mm, or empty for all day),
     * who, color (0-5, else from the name), repeat (none, weekly, yearly) and reminder (none, at, 10m, 1h, 1d).
     */
    static Event fromForm(String id, Map<String, String> form) throws Invalid {
        String title = Sanitizer.title(form.get("title"));
        if (title == null) throw new Invalid("title");
        LocalDate date = date(form.get("date"));
        if (date == null) throw new Invalid("date");
        boolean allDay = "1".equals(form.get("allday")) || blank(form.get("start"));
        LocalTime start = allDay ? null : time(form.get("start"));
        if (!allDay && start == null) throw new Invalid("start");
        LocalTime end = allDay || blank(form.get("end")) ? null : time(form.get("end"));
        if (!allDay && !blank(form.get("end")) && (end == null || !end.isAfter(start))) throw new Invalid("end");
        String who = blank(form.get("who")) ? null : Sanitizer.name(form.get("who"));
        if (!blank(form.get("who")) && who == null) throw new Invalid("who");
        int color = color(form.get("color"), who);
        Repeat repeat = repeat(form.get("repeat"));
        Reminder reminder = reminder(form.get("reminder"));
        if (repeat == null || reminder == null) throw new Invalid("choice");
        return new Event(id, title, date, start, end, who, color, repeat, reminder);
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    static LocalDate date(String s) {
        if (s == null || !s.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try {
            LocalDate d = LocalDate.parse(s);
            return d.isBefore(FIRST) || d.isAfter(LAST) ? null : d;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static LocalTime time(String s) {
        if (s == null || !s.matches("\\d{2}:\\d{2}")) return null;
        try {
            return LocalTime.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int color(String s, String who) {
        if (s != null && s.matches("[0-5]")) return s.charAt(0) - '0';
        return colorFor(who);
    }

    static Repeat repeat(String s) {
        if (s == null || s.isEmpty() || "none".equals(s)) return Repeat.NONE;
        if ("weekly".equals(s)) return Repeat.WEEKLY;
        if ("yearly".equals(s)) return Repeat.YEARLY;
        return null;
    }

    static Reminder reminder(String s) {
        if (s == null || s.isEmpty() || "none".equals(s)) return Reminder.NONE;
        switch (s) {
            case "at": return Reminder.AT_TIME;
            case "10m": return Reminder.MIN_10;
            case "1h": return Reminder.HOUR_1;
            case "1d": return Reminder.DAY_1;
            default: return null;
        }
    }

    static String code(Repeat r) {
        return r.name().toLowerCase(Locale.ROOT);
    }

    static String code(Reminder r) {
        switch (r) {
            case AT_TIME: return "at";
            case MIN_10: return "10m";
            case HOUR_1: return "1h";
            case DAY_1: return "1d";
            default: return "none";
        }
    }
}
