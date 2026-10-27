package com.zoolcoder.tv.calendar;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.YearMonth;

public class MonthPageTest {
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    @Test
    public void upFromTheFirstWeekNeverReachesThePreviousMonth() {
        for (int d = 1; d <= 7; d++) assertFalse(MonthPage.upStaysInMonth(LocalDate.of(2026, 10, d), OCTOBER));
    }

    @Test
    public void upFromLaterWeeksMovesAWeek() {
        assertTrue(MonthPage.upStaysInMonth(LocalDate.of(2026, 10, 8), OCTOBER));
        assertTrue(MonthPage.upStaysInMonth(LocalDate.of(2026, 10, 27), OCTOBER));
    }

    @Test
    public void aCellWithoutADayGoesToTheTabs() {
        assertFalse(MonthPage.upStaysInMonth(null, OCTOBER));
    }
}
