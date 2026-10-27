package com.zoolcoder.tv.calendar;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** What is on which day, and which reminders are due. Plain Java, so it is tested on the JVM. */
final class Agenda {
    /** All-day events first, then by start time, then by title. */
    static final Comparator<Occurrence> ORDER = Comparator
            .comparing((Occurrence o) -> o.day)
            .thenComparing(o -> o.event.allDay() ? -1 : o.event.start.toSecondOfDay())
            .thenComparing(o -> o.event.title);

    /** Late reminders still show when they are at most this old, e.g. after the service was restarted. */
    static final int GRACE_MINUTES = 10;

    private Agenda() {
    }

    static List<Occurrence> on(Collection<Event> events, LocalDate day) {
        return between(events, day, 1, Integer.MAX_VALUE);
    }

    /** The occurrences in {@code days} days from {@code from}, in order, at most {@code max}. */
    static List<Occurrence> between(Collection<Event> events, LocalDate from, int days, int max) {
        List<Occurrence> found = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate day = from.plusDays(i);
            for (Event e : events) if (e.occursOn(day)) found.add(new Occurrence(e, day));
        }
        found.sort(ORDER);
        return found.size() > max ? new ArrayList<>(found.subList(0, max)) : found;
    }

    /**
     * Reminders whose time has come, at most GRACE_MINUTES ago, and that have not popped up yet. A reminder a
     * day before falls on the day before its occurrence, so tomorrow's occurrences are checked too.
     */
    static List<Occurrence> due(Collection<Event> events, LocalDateTime now, Set<String> fired) {
        LocalDateTime since = now.minusMinutes(GRACE_MINUTES);
        List<Occurrence> due = new ArrayList<>();
        for (int i = 0; i <= 1; i++) {
            LocalDate day = now.toLocalDate().plusDays(i);
            for (Event e : events) {
                if (!e.occursOn(day)) continue;
                LocalDateTime at = e.reminderAt(day);
                if (at != null && at.isAfter(since) && !at.isAfter(now) && !fired.contains(e.key(day))) {
                    due.add(new Occurrence(e, day));
                }
            }
        }
        due.sort(ORDER);
        return due;
    }
}
