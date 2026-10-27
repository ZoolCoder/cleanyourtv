package com.zoolcoder.tv.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AgendaTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 26);

    private static Event event(String id, String title, LocalDate date, LocalTime start, Event.Repeat repeat,
                               Event.Reminder reminder) {
        return new Event(id, title, date, start, null, null, 0, repeat, reminder);
    }

    @Test
    public void ordersByDayThenAllDayThenTimeThenTitle() {
        List<Event> events = Arrays.asList(
                event("000000000001", "Late", DAY, LocalTime.of(18, 0), Event.Repeat.NONE, Event.Reminder.NONE),
                event("000000000002", "B early", DAY, LocalTime.of(8, 0), Event.Repeat.NONE, Event.Reminder.NONE),
                event("000000000003", "A early", DAY, LocalTime.of(8, 0), Event.Repeat.NONE, Event.Reminder.NONE),
                event("000000000004", "Holiday", DAY, null, Event.Repeat.NONE, Event.Reminder.NONE),
                event("000000000005", "Tomorrow", DAY.plusDays(1), null, Event.Repeat.NONE, Event.Reminder.NONE));
        List<Occurrence> found = Agenda.between(events, DAY, 2, 100);
        String[] titles = new String[found.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = found.get(i).event.title;
        assertEquals(Arrays.asList("Holiday", "A early", "B early", "Late", "Tomorrow"), Arrays.asList(titles));
    }

    @Test
    public void repeatsAppearOncePerDay() {
        Event weekly = event("000000000001", "Football", DAY, LocalTime.of(17, 0), Event.Repeat.WEEKLY, Event.Reminder.NONE);
        List<Occurrence> found = Agenda.between(Collections.singletonList(weekly), DAY, 14, 100);
        assertEquals(2, found.size());
        assertEquals(DAY, found.get(0).day);
        assertEquals(DAY.plusDays(7), found.get(1).day);
        assertEquals(1, Agenda.on(Collections.singletonList(weekly), DAY.plusDays(14)).size());
        assertTrue(Agenda.on(Collections.singletonList(weekly), DAY.plusDays(1)).isEmpty());
    }

    @Test
    public void betweenStopsAtMax() {
        Event weekly = event("000000000001", "x", DAY, null, Event.Repeat.WEEKLY, Event.Reminder.NONE);
        assertEquals(3, Agenda.between(Collections.singletonList(weekly), DAY, 365, 3).size());
    }

    @Test
    public void dueInsideTheGraceWindowOnly() {
        Event e = event("000000000001", "Dentist", DAY, LocalTime.of(10, 0), Event.Repeat.NONE, Event.Reminder.MIN_10);
        List<Event> events = Collections.singletonList(e);
        Set<String> none = Collections.emptySet();
        assertTrue(Agenda.due(events, DAY.atTime(9, 49), none).isEmpty());
        assertEquals(1, Agenda.due(events, DAY.atTime(9, 50), none).size());
        assertEquals(1, Agenda.due(events, DAY.atTime(9, 59), none).size());
        // Ten minutes late is too late: the TV was off, and an old reminder would only confuse.
        assertTrue(Agenda.due(events, DAY.atTime(10, 0), none).isEmpty());
    }

    @Test
    public void firedRemindersDoNotComeBack() {
        Event e = event("000000000001", "Dentist", DAY, LocalTime.of(10, 0), Event.Repeat.NONE, Event.Reminder.AT_TIME);
        Set<String> fired = new HashSet<>();
        LocalDateTime now = DAY.atTime(10, 0);
        assertEquals(1, Agenda.due(Collections.singletonList(e), now, fired).size());
        fired.add(e.key(DAY));
        assertTrue(Agenda.due(Collections.singletonList(e), now.plusMinutes(1), fired).isEmpty());
    }

    @Test
    public void aDayBeforeLooksAtTomorrow() {
        Event e = event("000000000001", "Trip", DAY.plusDays(1), LocalTime.of(7, 30), Event.Repeat.NONE, Event.Reminder.DAY_1);
        List<Occurrence> due = Agenda.due(Collections.singletonList(e), DAY.atTime(7, 30), Collections.emptySet());
        assertEquals(1, due.size());
        assertEquals(DAY.plusDays(1), due.get(0).day);
    }

    @Test
    public void allDayRemindsAtNine() {
        Event e = event("000000000001", "Eid", DAY, null, Event.Repeat.NONE, Event.Reminder.AT_TIME);
        assertTrue(Agenda.due(Collections.singletonList(e), DAY.atTime(8, 59), Collections.emptySet()).isEmpty());
        assertEquals(1, Agenda.due(Collections.singletonList(e), DAY.atTime(9, 0), Collections.emptySet()).size());
    }

    @Test
    public void eachWeeklyOccurrenceHasItsOwnKey() {
        Event e = event("000000000001", "Football", DAY, LocalTime.of(17, 0), Event.Repeat.WEEKLY, Event.Reminder.AT_TIME);
        Set<String> fired = new HashSet<>(Collections.singletonList(e.key(DAY)));
        assertEquals(1, Agenda.due(Collections.singletonList(e), DAY.plusDays(7).atTime(17, 0), fired).size());
    }
}
