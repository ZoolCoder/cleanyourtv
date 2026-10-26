package com.gathin.tvhome;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The prayer times against the Egyptian General Authority of Survey's published figures for Cairo (as the Aladhan
 * API returns them for method 5), to the minute or one off, on a winter-time and a summer-time day.
 */
public class PrayerTimesTest {

    private static final double CAIRO_LAT = 30.0444, CAIRO_LON = 31.2357;
    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    @Test
    public void cairoInMarchMatchesThePublishedTimes() {
        LocalTime[] t = PrayerTimes.of(LocalDate.of(2026, 3, 15), CAIRO_LAT, CAIRO_LON, CAIRO);
        assertClose("fajr", LocalTime.of(4, 39), t[PrayerTimes.FAJR]);
        assertClose("sunrise", LocalTime.of(6, 5), t[PrayerTimes.SUNRISE]);
        assertClose("dhuhr", LocalTime.of(12, 4), t[PrayerTimes.DHUHR]);
        assertClose("asr", LocalTime.of(15, 28), t[PrayerTimes.ASR]);
        assertClose("maghrib", LocalTime.of(18, 3), t[PrayerTimes.MAGHRIB]);
        assertClose("isha", LocalTime.of(19, 20), t[PrayerTimes.ISHA]);
    }

    @Test
    public void cairoInJuneIsInSummerTime() {
        LocalTime[] t = PrayerTimes.of(LocalDate.of(2025, 6, 21), CAIRO_LAT, CAIRO_LON, CAIRO);
        assertClose("fajr", LocalTime.of(4, 8), t[PrayerTimes.FAJR]);
        assertClose("sunrise", LocalTime.of(5, 54), t[PrayerTimes.SUNRISE]);
        assertClose("dhuhr", LocalTime.of(12, 57), t[PrayerTimes.DHUHR]);
        assertClose("asr", LocalTime.of(16, 32), t[PrayerTimes.ASR]);
        assertClose("maghrib", LocalTime.of(19, 59), t[PrayerTimes.MAGHRIB]);
        assertClose("isha", LocalTime.of(21, 33), t[PrayerTimes.ISHA]);
    }

    @Test
    public void theTimesComeInOrder() {
        LocalTime[] t = PrayerTimes.of(LocalDate.of(2026, 10, 26), CAIRO_LAT, CAIRO_LON, CAIRO);
        for (int i = 1; i < t.length; i++) assertTrue(t[i - 1] + " before " + t[i], t[i - 1].isBefore(t[i]));
    }

    @Test
    public void nextSkipsSunriseAndRollsOverMidnight() {
        LocalDate day = LocalDate.of(2026, 3, 15);
        PrayerTimes.Next afterFajr = PrayerTimes.next(day.atTime(5, 0), CAIRO_LAT, CAIRO_LON, CAIRO);
        assertNotNull(afterFajr);
        assertEquals(PrayerTimes.DHUHR, afterFajr.prayer);
        assertEquals(day, afterFajr.at.toLocalDate());

        PrayerTimes.Next afterIsha = PrayerTimes.next(day.atTime(23, 30), CAIRO_LAT, CAIRO_LON, CAIRO);
        assertNotNull(afterIsha);
        assertEquals(PrayerTimes.FAJR, afterIsha.prayer);
        assertEquals(day.plusDays(1), afterIsha.at.toLocalDate());
    }

    @Test
    public void fastingCountsDownToIftarByDayAndSuhoorByNight() {
        LocalDate day = LocalDate.of(2026, 3, 15);
        PrayerTimes.Next noon = PrayerTimes.fasting(day.atTime(12, 0), CAIRO_LAT, CAIRO_LON, CAIRO);
        assertEquals(PrayerTimes.MAGHRIB, noon.prayer);
        assertEquals(day, noon.at.toLocalDate());

        PrayerTimes.Next evening = PrayerTimes.fasting(day.atTime(20, 0), CAIRO_LAT, CAIRO_LON, CAIRO);
        assertEquals(PrayerTimes.FAJR, evening.prayer);
        assertEquals(day.plusDays(1), evening.at.toLocalDate());

        PrayerTimes.Next beforeDawn = PrayerTimes.fasting(day.atTime(3, 0), CAIRO_LAT, CAIRO_LON, CAIRO);
        assertEquals(PrayerTimes.FAJR, beforeDawn.prayer);
        assertEquals(day, beforeDawn.at.toLocalDate());
    }

    @Test
    public void ramadanIsTheNinthHijriMonth() {
        assertTrue(Hijri.ramadan(LocalDate.of(2026, 3, 15)));    // 26 Ramadan 1447
        assertTrue(!Hijri.ramadan(LocalDate.of(2026, 10, 26)));  // Jumada I 1448
    }

    private static void assertClose(String name, LocalTime published, LocalTime computed) {
        long off = Math.abs(java.time.Duration.between(published, computed).toMinutes());
        assertTrue(name + ": published " + published + ", computed " + computed, off <= 1);
    }
}
