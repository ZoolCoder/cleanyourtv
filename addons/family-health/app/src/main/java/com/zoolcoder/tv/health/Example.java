package com.zoolcoder.tv.health;

import android.content.Context;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * An invented family to try the app with before anything comes from a phone: four people, five medicines, two
 * appointments, and a week of doses already taken, with one missed. One dose is due right now, so the reminder
 * shows within a minute. The Today screen offers to remove it all again.
 */
final class Example {
    private Example() {
    }

    static void fill(Context c, Records r) {
        r.clear();
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate(), weekAgo = today.minusDays(7);
        Member grandma = new Member("ex-grandma", c.getString(R.string.example_grandma), 2);
        Member dad = new Member("ex-dad", c.getString(R.string.example_dad), 0);
        Member sara = new Member("ex-sara", c.getString(R.string.example_sara), 1);
        Member omar = new Member("ex-omar", c.getString(R.string.example_omar), 0);
        r.members.addAll(List.of(grandma, dad, sara, omar));

        // Due now: the time on the last whole 5 minutes, so the banner shows at the next tick.
        LocalTime nowish = now.toLocalTime().withSecond(0).withNano(0).minusMinutes(now.getMinute() % 5);
        Medicine metformin = new Medicine("ex-metformin", grandma.id, c.getString(R.string.example_metformin),
                "500 mg", c.getString(R.string.example_after_meal), List.of(nowish), weekAgo, null, 1, 24);
        Medicine amlodipine = new Medicine("ex-amlodipine", grandma.id, c.getString(R.string.example_amlodipine),
                "5 mg", c.getString(R.string.example_one_tablet), List.of(LocalTime.of(8, 0)), weekAgo, null, 1, 28);
        Medicine vitaminD = new Medicine("ex-vitamin-d", sara.id, c.getString(R.string.example_vitamin_d),
                "1000 IU", c.getString(R.string.example_one_capsule), List.of(LocalTime.of(9, 0)), weekAgo, null, 1, 3);
        Medicine omeprazole = new Medicine("ex-omeprazole", dad.id, c.getString(R.string.example_omeprazole),
                "20 mg", c.getString(R.string.example_before_meal), List.of(LocalTime.of(14, 0)), weekAgo, null, 1, 30);
        Medicine antibiotic = new Medicine("ex-antibiotic", omar.id, c.getString(R.string.example_antibiotic),
                "500 mg", c.getString(R.string.example_one_tablet),
                List.of(LocalTime.of(6, 0), LocalTime.of(18, 0)), today.minusDays(2), today.plusDays(4), 1, 12);
        r.medicines.addAll(List.of(metformin, amlodipine, vitaminD, omeprazole, antibiotic));

        r.visits.add(new Visit("ex-dentist", dad.id, c.getString(R.string.example_doctor_1),
                c.getString(R.string.example_dentist), c.getString(R.string.example_clinic_1), "",
                today.plusDays(3), LocalTime.of(16, 30)));
        r.visits.add(new Visit("ex-cardio", grandma.id, c.getString(R.string.example_doctor_2),
                c.getString(R.string.example_cardiologist), c.getString(R.string.example_clinic_2), "",
                today.plusDays(12), LocalTime.of(11, 0)));

        // The past week taken on time, except Grandma's Amlodipine the day before yesterday.
        for (Medicine m : r.medicines) {
            for (LocalDate day = m.start; !day.isAfter(today); day = day.plusDays(1)) {
                for (LocalTime t : m.times) {
                    LocalDateTime at = day.atTime(t);
                    boolean skipped = m == amlodipine && day.equals(today.minusDays(2));
                    if (skipped || !at.isBefore(now) || (day.equals(today) && m == metformin)) continue;
                    r.taken.put(Plan.key(m, day, t), at.plusMinutes(4).atZone(ZoneId.systemDefault())
                            .toInstant().toEpochMilli());
                }
            }
        }
        r.example = true;
    }
}
