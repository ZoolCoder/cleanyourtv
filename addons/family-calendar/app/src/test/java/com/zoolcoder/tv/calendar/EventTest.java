package com.zoolcoder.tv.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

public class EventTest {
    static final String ID = "0123456789ab";

    static Map<String, String> form(String... pairs) {
        Map<String, String> form = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) form.put(pairs[i], pairs[i + 1]);
        return form;
    }

    static Event event(LocalDate date, LocalTime start, Event.Repeat repeat, Event.Reminder reminder) {
        return new Event(ID, "Test", date, start, null, null, 0, repeat, reminder);
    }

    private static void invalid(Map<String, String> form) {
        try {
            Event.fromForm(ID, form);
            fail("accepted " + form);
        } catch (Event.Invalid expected) {
            // refused, as it should be
        }
    }

    @Test
    public void readsAFullForm() throws Event.Invalid {
        Event e = Event.fromForm(ID, form("title", " Dentist ", "date", "2026-10-27", "start", "10:00", "end", "11:30",
                "who", "Mom", "color", "2", "repeat", "weekly", "reminder", "1h"));
        assertEquals("Dentist", e.title);
        assertEquals(LocalDate.of(2026, 10, 27), e.date);
        assertEquals(LocalTime.of(10, 0), e.start);
        assertEquals(LocalTime.of(11, 30), e.end);
        assertEquals("Mom", e.who);
        assertEquals(2, e.color);
        assertEquals(Event.Repeat.WEEKLY, e.repeat);
        assertEquals(Event.Reminder.HOUR_1, e.reminder);
        assertFalse(e.allDay());
    }

    @Test
    public void allDayDropsTheTimes() throws Event.Invalid {
        Event e = Event.fromForm(ID, form("title", "Eid", "date", "2026-03-20", "allday", "1", "start", "10:00",
                "end", "11:00"));
        assertTrue(e.allDay());
        assertNull(e.start);
        assertNull(e.end);
        assertTrue(Event.fromForm(ID, form("title", "Eid", "date", "2026-03-20")).allDay());
    }

    @Test
    public void defaultsAreNoRepeatNoReminder() throws Event.Invalid {
        Event e = Event.fromForm(ID, form("title", "x", "date", "2026-01-01"));
        assertEquals(Event.Repeat.NONE, e.repeat);
        assertEquals(Event.Reminder.NONE, e.reminder);
        assertNull(e.who);
    }

    @Test
    public void colourComesFromTheNameUnlessGiven() throws Event.Invalid {
        Event dad = Event.fromForm(ID, form("title", "x", "date", "2026-01-01", "who", "Dad"));
        assertEquals(Event.colorFor("Dad"), dad.color);
        assertEquals(Event.colorFor("dad"), Event.colorFor("DAD"));
        assertEquals(Event.colorFor("Dad"), Event.fromForm(ID, form("title", "x", "date", "2026-01-01", "who", "Dad",
                "color", "9")).color);
        for (String name : new String[]{"a", "Grandma", "جدتي", "Kids", "Me"}) {
            int c = Event.colorFor(name);
            assertTrue(c >= 0 && c < Event.COLORS);
        }
    }

    @Test
    public void refusesBadFields() {
        invalid(form("date", "2026-01-01"));
        invalid(form("title", "x"));
        invalid(form("title", "x", "date", "2026-02-30"));
        invalid(form("title", "x", "date", "26-01-01"));
        invalid(form("title", "x", "date", "1999-12-31"));
        invalid(form("title", "x", "date", "2101-01-01"));
        invalid(form("title", "x", "date", "2026-01-01", "start", "25:00"));
        invalid(form("title", "x", "date", "2026-01-01", "start", "9:00"));
        invalid(form("title", "x", "date", "2026-01-01", "start", "10:00", "end", "09:00"));
        invalid(form("title", "x", "date", "2026-01-01", "start", "10:00", "end", "10:00"));
        invalid(form("title", "x", "date", "2026-01-01", "start", "10:00", "end", "late"));
        invalid(form("title", "x", "date", "2026-01-01", "repeat", "daily"));
        invalid(form("title", "x", "date", "2026-01-01", "reminder", "15m"));
        invalid(form("title", "x", "date", "2026-01-01", "who", "a very long name for a person here"));
        invalid(form("title", new String(new char[61]).replace('\0', 'x'), "date", "2026-01-01"));
    }

    @Test
    public void codesRoundTrip() {
        for (Event.Repeat r : Event.Repeat.values()) assertEquals(r, Event.repeat(Event.code(r)));
        for (Event.Reminder r : Event.Reminder.values()) assertEquals(r, Event.reminder(Event.code(r)));
    }

    @Test
    public void oneOffOccursOnItsDayOnly() {
        Event e = event(LocalDate.of(2026, 10, 26), null, Event.Repeat.NONE, Event.Reminder.NONE);
        assertTrue(e.occursOn(LocalDate.of(2026, 10, 26)));
        assertFalse(e.occursOn(LocalDate.of(2026, 10, 27)));
        assertFalse(e.occursOn(LocalDate.of(2027, 10, 26)));
    }

    @Test
    public void weeklyRepeatsOnTheSameWeekdayFromItsStart() {
        Event e = event(LocalDate.of(2026, 10, 26), null, Event.Repeat.WEEKLY, Event.Reminder.NONE);
        assertFalse(e.occursOn(LocalDate.of(2026, 10, 19)));
        assertTrue(e.occursOn(LocalDate.of(2026, 10, 26)));
        assertTrue(e.occursOn(LocalDate.of(2026, 11, 2)));
        assertTrue(e.occursOn(LocalDate.of(2027, 1, 4)));
        assertFalse(e.occursOn(LocalDate.of(2026, 11, 3)));
    }

    @Test
    public void yearlyRepeatsOnTheSameDate() {
        Event e = event(LocalDate.of(2020, 5, 14), null, Event.Repeat.YEARLY, Event.Reminder.NONE);
        assertTrue(e.occursOn(LocalDate.of(2026, 5, 14)));
        assertFalse(e.occursOn(LocalDate.of(2026, 5, 15)));
        assertFalse(e.occursOn(LocalDate.of(2019, 5, 14)));
    }

    @Test
    public void feb29FallsOnFeb28InOtherYears() {
        Event e = event(LocalDate.of(2024, 2, 29), null, Event.Repeat.YEARLY, Event.Reminder.NONE);
        assertTrue(e.occursOn(LocalDate.of(2025, 2, 28)));
        assertFalse(e.occursOn(LocalDate.of(2025, 3, 1)));
        assertTrue(e.occursOn(LocalDate.of(2028, 2, 29)));
        assertFalse(e.occursOn(LocalDate.of(2028, 2, 28)));
        Event feb28 = event(LocalDate.of(2024, 2, 28), null, Event.Repeat.YEARLY, Event.Reminder.NONE);
        assertTrue(feb28.occursOn(LocalDate.of(2025, 2, 28)));
        assertFalse(feb28.occursOn(LocalDate.of(2028, 2, 29)));
    }

    @Test
    public void reminderTimes() {
        LocalDate day = LocalDate.of(2026, 10, 26);
        LocalTime ten = LocalTime.of(10, 0);
        assertNull(event(day, ten, Event.Repeat.NONE, Event.Reminder.NONE).reminderAt(day));
        assertEquals(LocalDateTime.of(2026, 10, 26, 10, 0), event(day, ten, Event.Repeat.NONE, Event.Reminder.AT_TIME).reminderAt(day));
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 50), event(day, ten, Event.Repeat.NONE, Event.Reminder.MIN_10).reminderAt(day));
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0), event(day, ten, Event.Repeat.NONE, Event.Reminder.HOUR_1).reminderAt(day));
        assertEquals(LocalDateTime.of(2026, 10, 25, 10, 0), event(day, ten, Event.Repeat.NONE, Event.Reminder.DAY_1).reminderAt(day));
        // All day: the reminder uses 09:00.
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0), event(day, null, Event.Repeat.NONE, Event.Reminder.AT_TIME).reminderAt(day));
    }

    @Test
    public void keyNamesTheOccurrence() {
        Event e = event(LocalDate.of(2026, 10, 26), null, Event.Repeat.WEEKLY, Event.Reminder.NONE);
        assertEquals(ID + "@2026-11-02", e.key(LocalDate.of(2026, 11, 2)));
    }
}
