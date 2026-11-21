package com.zoolcoder.tv.health;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PhoneTest {
    private static final long PERIOD = 30 * 60_000L;
    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void onUntilItsTime() {
        assertFalse(Phone.expired(true, NOW + PERIOD, NOW, PERIOD));
        assertFalse(Phone.expired(true, NOW + 1, NOW, PERIOD));
        assertTrue(Phone.expired(true, NOW, NOW, PERIOD));
        assertTrue(Phone.expired(true, NOW - 1, NOW, PERIOD));
    }

    @Test
    public void aPageOnBeforeTheClockExistedIsPastIt() {
        assertTrue(Phone.expired(true, 0, NOW, PERIOD));
    }

    @Test
    public void aClockSetBackDoesNotKeepItOn() {
        assertTrue(Phone.expired(true, NOW + PERIOD + 1, NOW, PERIOD));
        assertTrue(Phone.expired(true, NOW + 365 * 86_400_000L, NOW, PERIOD));
    }

    @Test
    public void offIsNeverExpired() {
        assertFalse(Phone.expired(false, 0, NOW, PERIOD));
    }

    @Test
    public void thePinIsShownInTwoGroups() {
        assertEquals("1234 5678", PhonePage.grouped("12345678"));
    }
}
