package com.zoolcoder.tv.health;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** A doctor's appointment: who, which doctor and specialty, when and where, and a short note. */
final class Visit {
    final String id, member, doctor, specialty, place, note;
    final LocalDate day;
    final LocalTime time;

    Visit(String id, String member, String doctor, String specialty, String place, String note,
          LocalDate day, LocalTime time) {
        this.id = id;
        this.member = member;
        this.doctor = doctor;
        this.specialty = specialty;
        this.place = place;
        this.note = note;
        this.day = day;
        this.time = time;
    }

    LocalDateTime at() {
        return day.atTime(time);
    }
}
