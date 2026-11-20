package com.zoolcoder.tv.health;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What is due and when, worked out from the Records and the clock alone. A dose is "now" from 30 minutes before
 * its time until 3 hours after it, and missed once those 3 hours pass untaken; an appointment is "now" from 2
 * hours before it until an hour after. No Android here, so the rules are tested on the JVM.
 */
final class Plan {
    static final Duration EARLY = Duration.ofMinutes(30), LATE = Duration.ofHours(3),
            VISIT_SOON = Duration.ofHours(2), VISIT_OVER = Duration.ofHours(1), SNOOZE = Duration.ofMinutes(15);
    /** The evening before an appointment, when its first reminder shows. */
    static final LocalTime EVE = LocalTime.of(19, 0);
    static final String EVE_KEY = "#eve", SOON_KEY = "#soon";

    enum State { DONE, MISSED, NOW, LATER }

    /** The dots of the "This week" row. */
    enum Dot { NONE, TAKEN, MISSED, UPCOMING }

    /** One line of the Today screen: a dose of a medicine, or an appointment. */
    static final class Entry {
        final Member member;
        final Medicine medicine;
        final Visit visit;
        final LocalDateTime at;
        final LocalTime time;
        final String key;
        final State state;
        /** When the dose was marked taken, in epoch milliseconds; 0 if not. */
        final long takenAt;

        Entry(Member member, Medicine medicine, Visit visit, LocalDateTime at, String key, State state, long takenAt) {
            this.member = member;
            this.medicine = medicine;
            this.visit = visit;
            this.at = at;
            this.time = at.toLocalTime();
            this.key = key;
            this.state = state;
            this.takenAt = takenAt;
        }

        boolean dose() {
            return medicine != null;
        }
    }

    private Plan() {
    }

    /** "med-1@2026-11-20T08:00": which medicine, which day, which time. */
    static String key(Medicine m, LocalDate day, LocalTime time) {
        return m.id + "@" + day + "T" + time;
    }

    static String medicineOf(String key) {
        int at = key.lastIndexOf('@');
        return at < 0 ? key : key.substring(0, at);
    }

    /** The day in a dose key, or null if it has none. */
    static LocalDate dayOf(String key) {
        int at = key.lastIndexOf('@'), t = key.lastIndexOf('T');
        if (at < 0 || t < at) return null;
        try {
            return LocalDate.parse(key.substring(at + 1, t));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static State doseState(LocalDateTime at, LocalDateTime now, boolean taken) {
        if (taken) return State.DONE;
        if (now.isBefore(at.minus(EARLY))) return State.LATER;
        if (now.isBefore(at.plus(LATE))) return State.NOW;
        return State.MISSED;
    }

    static State visitState(LocalDateTime at, LocalDateTime now) {
        if (now.isBefore(at.minus(VISIT_SOON))) return State.LATER;
        if (now.isBefore(at.plus(VISIT_OVER))) return State.NOW;
        return State.DONE;
    }

    /**
     * Today's doses and appointments, for one member or (null) for everyone, in time order. A dose of
     * yesterday whose window is still open after midnight, or of tomorrow whose window opens before it, is
     * here too while it is due.
     */
    static List<Entry> day(Records r, LocalDateTime now, String memberId) {
        LocalDate today = now.toLocalDate();
        List<Entry> entries = new ArrayList<>();
        for (Medicine m : r.medicines) {
            Member who = r.member(m.member);
            if (who == null || (memberId != null && !memberId.equals(m.member))) continue;
            for (LocalDate d = today.minusDays(1); !d.isAfter(today.plusDays(1)); d = d.plusDays(1)) {
                if (!m.on(d)) continue;
                for (LocalTime t : m.times) {
                    String key = key(m, d, t);
                    Long takenAt = r.taken.get(key);
                    State state = doseState(d.atTime(t), now, takenAt != null);
                    if (!d.equals(today) && state != State.NOW) continue;
                    entries.add(new Entry(who, m, null, d.atTime(t), key, state, takenAt == null ? 0 : takenAt));
                }
            }
        }
        for (Visit v : r.visits) {
            Member who = r.member(v.member);
            if (who == null || (memberId != null && !memberId.equals(v.member)) || !v.day.equals(today)) continue;
            entries.add(new Entry(who, null, v, v.at(), v.id + "#visit", visitState(v.at(), now), 0));
        }
        entries.sort(Comparator.comparing((Entry e) -> e.at).thenComparing(e -> e.member.name));
        return entries;
    }

    /** The doses whose reminder should show now: due, not taken, not missed and not put off with "Later". */
    static List<Entry> ring(Records r, LocalDateTime now, long nowMillis) {
        List<Entry> due = new ArrayList<>();
        for (Entry e : day(r, now, null)) {
            if (!e.dose() || e.state != State.NOW || now.isBefore(e.at)) continue;
            Long until = r.snoozed.get(e.key);
            if (until == null || until <= nowMillis) due.add(e);
        }
        return due;
    }

    /**
     * The appointment reminders to show now and not yet shown: one from 7 PM the evening before, one in the
     * 2 hours before. Each comes back with the key that Records.told keeps.
     */
    static List<Entry> visitAlerts(Records r, LocalDateTime now) {
        List<Entry> alerts = new ArrayList<>();
        for (Visit v : r.visits) {
            Member who = r.member(v.member);
            LocalDateTime at = v.at(), soon = at.minus(VISIT_SOON), eve = v.day.minusDays(1).atTime(EVE);
            if (who == null || !now.isBefore(at)) continue;
            String key = null;
            if (!now.isBefore(soon)) key = v.id + SOON_KEY;
            else if (!now.isBefore(eve)) key = v.id + EVE_KEY;
            if (key != null && !r.told.contains(key)) {
                alerts.add(new Entry(who, null, v, at, key, State.LATER, 0));
            }
        }
        return alerts;
    }

    /** One dot per day of the week holding {@code today}, starting on {@code first}. */
    static Dot[] week(Records r, String memberId, LocalDateTime now, DayOfWeek first) {
        LocalDate today = now.toLocalDate();
        LocalDate start = today.with(TemporalAdjusters.previousOrSame(first));
        Dot[] dots = new Dot[7];
        for (int i = 0; i < 7; i++) {
            LocalDate day = start.plusDays(i);
            int doses = 0, takenCount = 0;
            boolean missed = false;
            for (Medicine m : r.medicines) {
                if (!m.member.equals(memberId) || !m.on(day)) continue;
                for (LocalTime t : m.times) {
                    doses++;
                    boolean taken = r.taken.containsKey(key(m, day, t));
                    if (taken) takenCount++;
                    else if (!day.isAfter(today) && doseState(day.atTime(t), now, false) == State.MISSED) missed = true;
                }
            }
            if (doses == 0) dots[i] = Dot.NONE;
            else if (missed) dots[i] = Dot.MISSED;
            else if (takenCount == doses) dots[i] = Dot.TAKEN;
            else dots[i] = Dot.UPCOMING;
        }
        return dots;
    }

    /** The member's appointments from today on, soonest first. */
    static List<Visit> upcoming(Records r, String memberId, LocalDateTime now) {
        List<Visit> list = new ArrayList<>();
        for (Visit v : r.visits) {
            if (v.member.equals(memberId) && now.isBefore(v.at().plus(VISIT_OVER))) list.add(v);
        }
        list.sort(Comparator.comparing(Visit::at));
        return list;
    }
}
