package com.zoolcoder.tv.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PinGuardTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final PinGuard guard = new PinGuard(now::get);

    @Test
    public void rightPinPasses() {
        assertEquals(PinGuard.Result.OK, guard.check("123456", "123456"));
    }

    @Test
    public void wrongOrMissingPinFails() {
        assertEquals(PinGuard.Result.WRONG, guard.check("123457", "123456"));
        assertEquals(PinGuard.Result.WRONG, guard.check(null, "123456"));
        assertEquals(PinGuard.Result.WRONG, guard.check("", "123456"));
        assertEquals(PinGuard.Result.WRONG, guard.check("1234567", "123456"));
    }

    @Test
    public void fiveWrongPinsLockEvenTheRightOneForFiveMinutes() {
        for (int i = 0; i < 5; i++) assertEquals(PinGuard.Result.WRONG, guard.check("000000", "123456"));
        assertEquals(PinGuard.Result.LOCKED, guard.check("123456", "123456"));
        now.addAndGet(PinGuard.LOCKOUT_MS - 1);
        assertEquals(PinGuard.Result.LOCKED, guard.check("123456", "123456"));
        now.addAndGet(1);
        assertEquals(PinGuard.Result.OK, guard.check("123456", "123456"));
    }

    @Test
    public void fourWrongThenRightResetsTheCount() {
        for (int i = 0; i < 4; i++) guard.check("000000", "123456");
        assertEquals(PinGuard.Result.OK, guard.check("123456", "123456"));
        for (int i = 0; i < 4; i++) assertEquals(PinGuard.Result.WRONG, guard.check("000000", "123456"));
        assertEquals(PinGuard.Result.OK, guard.check("123456", "123456"));
    }

    @Test
    public void lockoutEndsWithAFreshCount() {
        for (int i = 0; i < 5; i++) guard.check("000000", "123456");
        now.addAndGet(PinGuard.LOCKOUT_MS);
        for (int i = 0; i < 4; i++) assertEquals(PinGuard.Result.WRONG, guard.check("000000", "123456"));
        assertEquals(PinGuard.Result.WRONG, guard.check("000000", "123456"));
        assertEquals(PinGuard.Result.LOCKED, guard.check("123456", "123456"));
    }

    @Test
    public void newPinIsSixDigits() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 200; i++) assertTrue(PinGuard.newPin(random).matches("[0-9]{6}"));
    }

    private static final class Mem implements PinGuard.Memory {
        long[] saved = new long[3];

        @Override
        public long[] load() {
            return saved.clone();
        }

        @Override
        public void save(int wrong, long windowStart, long lockedUntil) {
            saved = new long[] {wrong, windowStart, lockedUntil};
        }
    }

    /** Wrong guesses from many addresses, each under its own lockout, so only the backstop counts. */
    private static void spray(PinGuard guard, int guesses) {
        for (int i = 0; i < guesses; i++) guard.check("10.0.0." + (i / 4), "000000", "123456");
    }

    @Test
    public void oneLockedAddressDoesNotLockAnother() {
        for (int i = 0; i < 5; i++) guard.check("10.0.0.9", "000000", "123456");
        assertEquals(PinGuard.Result.LOCKED, guard.check("10.0.0.9", "123456", "123456"));
        assertEquals(PinGuard.Result.OK, guard.check("10.0.0.2", "123456", "123456"));
    }

    @Test
    public void fiftyWrongFromAllAddressesLockEveryoneAndMakeANewPin() {
        AtomicInteger trips = new AtomicInteger();
        PinGuard guard = new PinGuard(now::get, null, trips::incrementAndGet);
        spray(guard, PinGuard.MAX_WRONG_ALL - 1);
        assertEquals(0, trips.get());
        assertEquals(PinGuard.Result.OK, guard.check("10.0.0.200", "123456", "123456"));
        spray(guard, 1);
        assertEquals(1, trips.get());
        assertEquals(PinGuard.Result.LOCKED, guard.check("10.0.0.200", "123456", "123456"));
        now.addAndGet(PinGuard.LOCKOUT_MS);
        assertEquals(PinGuard.Result.OK, guard.check("10.0.0.200", "123456", "123456"));
    }

    @Test
    public void theBackstopCountsOnlyWithinTheHour() {
        AtomicInteger trips = new AtomicInteger();
        PinGuard guard = new PinGuard(now::get, null, trips::incrementAndGet);
        spray(guard, PinGuard.MAX_WRONG_ALL - 1);
        now.addAndGet(PinGuard.WINDOW_MS);
        spray(guard, PinGuard.MAX_WRONG_ALL - 1);
        assertEquals(0, trips.get());
    }

    @Test
    public void aClockSetBackCannotStretchALockout() {
        for (int i = 0; i < 5; i++) guard.check("10.0.0.9", "000000", "123456");
        now.addAndGet(-10 * PinGuard.LOCKOUT_MS);
        assertEquals(PinGuard.Result.LOCKED, guard.check("10.0.0.9", "123456", "123456"));
        now.addAndGet(PinGuard.LOCKOUT_MS);
        assertEquals(PinGuard.Result.OK, guard.check("10.0.0.9", "123456", "123456"));
    }

    @Test
    public void forgettingOldAddressesKeepsTheBackstop() {
        AtomicInteger trips = new AtomicInteger();
        PinGuard guard = new PinGuard(now::get, null, trips::incrementAndGet);
        for (int i = 0; i < PinGuard.MAX_WRONG_ALL; i++) guard.check("10.0." + i + ".1", "000000", "123456");
        assertEquals(1, trips.get());
    }

    @Test
    public void theAllAddressLockoutSurvivesARestart() {
        Mem mem = new Mem();
        spray(new PinGuard(now::get, mem, null), PinGuard.MAX_WRONG_ALL);
        PinGuard restarted = new PinGuard(now::get, mem, null);
        assertEquals(PinGuard.Result.LOCKED, restarted.check("10.0.0.200", "123456", "123456"));
        now.addAndGet(PinGuard.LOCKOUT_MS);
        assertEquals(PinGuard.Result.OK, new PinGuard(now::get, mem, null).check("10.0.0.200", "123456", "123456"));
    }

    @Test
    public void theAllAddressCountSurvivesARestart() {
        Mem mem = new Mem();
        AtomicInteger trips = new AtomicInteger();
        spray(new PinGuard(now::get, mem, null), PinGuard.MAX_WRONG_ALL - 1);
        PinGuard restarted = new PinGuard(now::get, mem, trips::incrementAndGet);
        restarted.check("10.0.0.200", "000000", "123456");
        assertEquals(1, trips.get());
    }

    @Test
    public void storedLockoutBeyondItsLengthIsClamped() {
        Mem mem = new Mem();
        mem.saved = new long[] {0, 0, now.get() + 10 * PinGuard.LOCKOUT_MS};
        PinGuard guard = new PinGuard(now::get, mem, null);
        assertEquals(PinGuard.Result.LOCKED, guard.check("123456", "123456"));
        now.addAndGet(PinGuard.LOCKOUT_MS);
        assertEquals(PinGuard.Result.OK, guard.check("123456", "123456"));
    }

    @Test
    public void junkStoredStateIsHarmless() {
        Mem mem = new Mem();
        mem.saved = new long[] {-7, -1, -1};
        assertEquals(PinGuard.Result.OK, new PinGuard(now::get, mem, null).check("123456", "123456"));
        mem.saved = new long[] {999, now.get() + 1, 0};
        assertEquals(PinGuard.Result.OK, new PinGuard(now::get, mem, null).check("123456", "123456"));
        mem.saved = new long[] {1};
        assertEquals(PinGuard.Result.OK, new PinGuard(now::get, mem, null).check("123456", "123456"));
    }
}
