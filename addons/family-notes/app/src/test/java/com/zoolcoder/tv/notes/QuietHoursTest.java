package com.zoolcoder.tv.notes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QuietHoursTest {
    private static int at(int hour, int minute) {
        return hour * 60 + minute;
    }

    @Test
    public void offIsNeverQuiet() {
        for (int minute = 0; minute < 24 * 60; minute++) assertFalse(QuietHours.isQuiet(0, minute));
        assertNull(QuietHours.label(0));
    }

    @Test
    public void windowRunsAcrossMidnight() {
        assertFalse(QuietHours.isQuiet(1, at(21, 59)));
        assertTrue(QuietHours.isQuiet(1, at(22, 0)));
        assertTrue(QuietHours.isQuiet(1, at(23, 59)));
        assertTrue(QuietHours.isQuiet(1, at(0, 0)));
        assertTrue(QuietHours.isQuiet(1, at(6, 59)));
        assertFalse(QuietHours.isQuiet(1, at(7, 0)));
        assertFalse(QuietHours.isQuiet(1, at(12, 0)));
    }

    @Test
    public void everyWindowHasItsOwnHours() {
        assertTrue(QuietHours.isQuiet(2, at(7, 30)));
        assertFalse(QuietHours.isQuiet(2, at(22, 30)));
        assertTrue(QuietHours.isQuiet(3, at(21, 0)));
        assertFalse(QuietHours.isQuiet(3, at(6, 0)));
        assertEquals("22:00–07:00", QuietHours.label(1));
        assertEquals("23:00–08:00", QuietHours.label(2));
        assertEquals("21:00–06:00", QuietHours.label(3));
    }

    @Test
    public void cyclesBackToOff() {
        assertEquals(1, QuietHours.next(0));
        assertEquals(3, QuietHours.next(2));
        assertEquals(0, QuietHours.next(3));
    }

    @Test
    public void unknownChoiceReadsAsOff() {
        assertEquals(0, QuietHours.valid(-1));
        assertEquals(0, QuietHours.valid(9));
        assertFalse(QuietHours.isQuiet(9, at(23, 0)));
        assertEquals(1, QuietHours.next(9));
    }
}
