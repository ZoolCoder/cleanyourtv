package com.zoolcoder.tv.calendar;

import java.time.LocalDate;

/** An event on one day: a repeating event has one occurrence per day it falls on. */
final class Occurrence {
    final Event event;
    final LocalDate day;

    Occurrence(Event event, LocalDate day) {
        this.event = event;
        this.day = day;
    }
}
