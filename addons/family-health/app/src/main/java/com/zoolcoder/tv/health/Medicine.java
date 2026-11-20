package com.zoolcoder.tv.health;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * A medicine one member takes: "Metformin" "500 mg", "1 tablet after breakfast", at the given times every day
 * from {@code start} until {@code end} (null: no end). {@code left} counts the units still in the pack, and each
 * dose taken uses {@code perDose} of them; -1 means the pack is not counted.
 */
final class Medicine {
    static final int LOW_DAYS = 3;

    final String id, member, name, dose, how;
    final List<LocalTime> times;
    final LocalDate start, end;
    final int perDose;
    int left;

    Medicine(String id, String member, String name, String dose, String how, List<LocalTime> times,
             LocalDate start, LocalDate end, int perDose, int left) {
        this.id = id;
        this.member = member;
        this.name = name;
        this.dose = dose;
        this.how = how;
        this.times = List.copyOf(times);
        this.start = start;
        this.end = end;
        this.perDose = Math.max(1, perDose);
        this.left = left;
    }

    boolean on(LocalDate day) {
        return !day.isBefore(start) && (end == null || !day.isAfter(end));
    }

    /** "Metformin 500 mg". */
    String title() {
        return dose.isEmpty() ? name : name + " " + dose;
    }

    /** The whole days the pack still covers, or -1 when it is not counted. */
    int daysLeft() {
        int perDay = times.size() * perDose;
        return left < 0 || perDay == 0 ? -1 : left / perDay;
    }

    boolean low() {
        int days = daysLeft();
        return days >= 0 && days <= LOW_DAYS;
    }
}
