package com.zoolcoder.tv.notes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
        long[] saved = new long[2];

        @Override
        public long[] load() {
            return saved.clone();
        }

        @Override
        public void save(int wrong, long lockedUntil) {
            saved = new long[] {wrong, lockedUntil};
        }
    }

    @Test
    public void oneLockedOutPhoneDoesNotLockOutTheOthers() {
        for (int i = 0; i < 5; i++) assertEquals(PinGuard.Result.WRONG, guard.check("10.0.0.9", "000000", "123456"));
        assertEquals(PinGuard.Result.LOCKED, guard.check("10.0.0.9", "123456", "123456"));
        assertEquals(PinGuard.Result.OK, guard.check("10.0.0.2", "123456", "123456"));
        assertEquals(0, guard.lockedUntil());
    }

    @Test
    public void wrongCountsArePerPhone() {
        for (int i = 0; i < 4; i++) guard.check("10.0.0.9", "000000", "123456");
        for (int i = 0; i < 4; i++) assertEquals(PinGuard.Result.WRONG, guard.check("10.0.0.2", "000000", "123456"));
        assertEquals(PinGuard.Result.WRONG, guard.check("10.0.0.9", "000000", "123456"));
        assertEquals(PinGuard.Result.LOCKED, guard.check("10.0.0.9", "123456", "123456"));
        assertEquals(PinGuard.Result.OK, guard.check("10.0.0.2", "123456", "123456"));
    }

    @Test
    public void fiftyWrongPinsInAnHourLockEveryone() {
        for (int i = 0; i < PinGuard.GLOBAL_MAX_WRONG; i++) {
            String peer = "10.0.1." + (i / 4);
            assertEquals(PinGuard.Result.WRONG, guard.check(peer, "000000", "123456"));
            now.addAndGet(60_000);
        }
        assertEquals(PinGuard.Result.LOCKED, guard.check("10.0.0.200", "123456", "123456"));
        assertTrue(guard.lockedUntil() > 0);
        // The hour began with the first wrong PIN, at 1_000; the lockout lasts until it is over.
        now.set(1_000 + PinGuard.GLOBAL_WINDOW_MS - 1);
        assertEquals(PinGuard.Result.LOCKED, guard.check("10.0.0.200", "123456", "123456"));
        now.addAndGet(1);
        assertEquals(PinGuard.Result.OK, guard.check("10.0.0.200", "123456", "123456"));
        // A wrong PIN afterwards opens a fresh hour instead of locking everyone out again.
        assertEquals(PinGuard.Result.WRONG, guard.check("10.0.0.201", "000000", "123456"));
        assertEquals(0, guard.lockedUntil());
    }

    @Test
    public void theEveryoneLockoutLastsAtLeastFiveMinutes() {
        for (int i = 0; i < PinGuard.GLOBAL_MAX_WRONG - 1; i++) guard.check("10.0.1." + (i / 4), "000000", "123456");
        now.addAndGet(PinGuard.GLOBAL_WINDOW_MS - 2 * 60_000);
        guard.check("10.0.9.1", "000000", "123456");
        // Two minutes were left of the hour: the lockout still lasts its five.
        now.addAndGet(PinGuard.LOCKOUT_MS - 1);
        assertEquals(PinGuard.Result.LOCKED, guard.check("10.0.0.200", "123456", "123456"));
        now.addAndGet(1);
        assertEquals(PinGuard.Result.OK, guard.check("10.0.0.200", "123456", "123456"));
    }

    @Test
    public void manyPhonesGetFiftyGuessesAnHourNotEveryFiveMinutes() {
        int wrong = 0;
        for (int minute = 0; minute < 60; minute++, now.addAndGet(60_000)) {
            for (int i = 0; i < 20; i++) {
                if (guard.check("10." + minute + ".0." + i, "000000", "123456") == PinGuard.Result.WRONG) wrong++;
            }
        }
        assertEquals(PinGuard.GLOBAL_MAX_WRONG, wrong);
    }

    @Test
    public void theGlobalCountStartsAgainEachHour() {
        for (int i = 0; i < PinGuard.GLOBAL_MAX_WRONG - 1; i++) guard.check("10.0.1." + (i / 4), "000000", "123456");
        now.addAndGet(PinGuard.GLOBAL_WINDOW_MS);
        assertEquals(PinGuard.Result.WRONG, guard.check("10.0.2.1", "000000", "123456"));
        assertEquals(PinGuard.Result.OK, guard.check("10.0.2.2", "123456", "123456"));
    }

    @Test
    public void manyPhonesStayWithinTheCap() {
        for (int i = 0; i < 3 * PinGuard.MAX_PEERS; i++) {
            // A new hour now and then keeps the everyone-lockout out of this test.
            if (i % 40 == 0) now.addAndGet(PinGuard.GLOBAL_WINDOW_MS);
            guard.check("p" + i, "000000", "123456");
        }
        // The newest phone is remembered; a locked one is not evicted by idle ones.
        for (int i = 0; i < 4; i++) guard.check("p" + (3 * PinGuard.MAX_PEERS - 1), "000000", "123456");
        assertEquals(PinGuard.Result.LOCKED, guard.check("p" + (3 * PinGuard.MAX_PEERS - 1), "123456", "123456"));
    }

    @Test
    public void onePhoneLockingItselfOutKeepsThePin() {
        AtomicInteger trips = new AtomicInteger();
        guard.setOnTrip(trips::incrementAndGet);
        for (int i = 0; i < 6; i++) guard.check("a", "000000", "123456");
        for (int i = 0; i < 5; i++) guard.check("b", "000000", "123456");
        assertEquals(0, trips.get());
    }

    @Test
    public void theEveryoneLockoutCallsTheTripListenerOnce() {
        AtomicInteger trips = new AtomicInteger();
        guard.setOnTrip(trips::incrementAndGet);
        for (int i = 0; i < PinGuard.GLOBAL_MAX_WRONG - 1; i++) guard.check("10.0.1." + (i / 4), "000000", "123456");
        assertEquals(0, trips.get());
        guard.check("10.0.9.1", "000000", "123456");
        assertEquals(1, trips.get());
        guard.check("10.0.9.2", "000000", "123456");
        assertEquals(1, trips.get());
    }

    @Test
    public void theTripListenerCanChangeThePin() {
        AtomicReference<String> pin = new AtomicReference<>("123456");
        guard.setOnTrip(() -> pin.set("654321"));
        for (int i = 0; i < PinGuard.GLOBAL_MAX_WRONG; i++) guard.check("10.0.1." + (i / 4), "000000", pin.get());
        now.addAndGet(PinGuard.GLOBAL_WINDOW_MS);
        assertEquals(PinGuard.Result.WRONG, guard.check("a", "123456", pin.get()));
        assertEquals(PinGuard.Result.OK, guard.check("b", "654321", pin.get()));
    }

    @Test
    public void globalLockoutSurvivesARestart() {
        Mem mem = new Mem();
        PinGuard first = new PinGuard(now::get, mem);
        for (int i = 0; i < PinGuard.GLOBAL_MAX_WRONG; i++) first.check("10.0.1." + (i / 4), "000000", "123456");
        PinGuard restarted = new PinGuard(now::get, mem);
        assertEquals(PinGuard.Result.LOCKED, restarted.check("10.0.0.7", "123456", "123456"));
        now.addAndGet(PinGuard.GLOBAL_WINDOW_MS);
        assertEquals(PinGuard.Result.OK, new PinGuard(now::get, mem).check("10.0.0.7", "123456", "123456"));
    }

    @Test
    public void globalCountSurvivesARestart() {
        Mem mem = new Mem();
        PinGuard first = new PinGuard(now::get, mem);
        for (int i = 0; i < PinGuard.GLOBAL_MAX_WRONG - 1; i++) first.check("10.0.1." + (i / 4), "000000", "123456");
        assertEquals(PinGuard.GLOBAL_MAX_WRONG - 1, mem.saved[0]);
        PinGuard restarted = new PinGuard(now::get, mem);
        assertEquals(PinGuard.Result.WRONG, restarted.check("10.0.0.7", "000000", "123456"));
        assertEquals(PinGuard.Result.LOCKED, restarted.check("10.0.0.8", "123456", "123456"));
    }

    @Test
    public void expiryClearsTheStoredLockout() {
        Mem mem = new Mem();
        PinGuard guard = new PinGuard(now::get, mem);
        for (int i = 0; i < PinGuard.GLOBAL_MAX_WRONG; i++) guard.check("10.0.1." + (i / 4), "000000", "123456");
        assertTrue(mem.saved[1] > 0);
        now.addAndGet(PinGuard.GLOBAL_WINDOW_MS);
        guard.check("x", "123456", "123456");
        assertEquals(0, mem.saved[1]);
        assertEquals(0, mem.saved[0]);
    }

    @Test
    public void storedLockoutBeyondItsLengthIsClamped() {
        Mem mem = new Mem();
        mem.saved = new long[] {50, now.get() + 10 * PinGuard.GLOBAL_WINDOW_MS};
        PinGuard guard = new PinGuard(now::get, mem);
        assertEquals(PinGuard.Result.LOCKED, guard.check("123456", "123456"));
        now.addAndGet(PinGuard.GLOBAL_WINDOW_MS);
        assertEquals(PinGuard.Result.OK, guard.check("123456", "123456"));
    }

    @Test
    public void aClockSetBackCannotStretchARunningLockout() {
        for (int i = 0; i < 5; i++) guard.check("a", "000000", "123456");
        now.addAndGet(-10 * PinGuard.LOCKOUT_MS);
        assertEquals(PinGuard.Result.LOCKED, guard.check("a", "123456", "123456"));
        now.addAndGet(PinGuard.LOCKOUT_MS);
        assertEquals(PinGuard.Result.OK, guard.check("a", "123456", "123456"));
    }

    @Test
    public void junkStoredStateIsHarmless() {
        Mem mem = new Mem();
        mem.saved = new long[] {-7, -1};
        assertEquals(PinGuard.Result.OK, new PinGuard(now::get, mem).check("123456", "123456"));
        mem.saved = new long[] {999, 0};
        assertEquals(PinGuard.Result.OK, new PinGuard(now::get, mem).check("123456", "123456"));
    }

    @Test
    public void minutesLeftRoundsUpAndEnds() {
        long now = 1_000_000;
        assertEquals(0, PinGuard.minutesLeft(0, now));
        assertEquals(0, PinGuard.minutesLeft(now, now));
        assertEquals(1, PinGuard.minutesLeft(now + 1, now));
        assertEquals(1, PinGuard.minutesLeft(now + 60_000, now));
        assertEquals(2, PinGuard.minutesLeft(now + 60_001, now));
        assertEquals(5, PinGuard.minutesLeft(now + PinGuard.LOCKOUT_MS, now));
        assertEquals(60, PinGuard.minutesLeft(now + PinGuard.GLOBAL_WINDOW_MS, now));
        // A clock set back must not show a lockout longer than the hour it can last.
        assertEquals(60, PinGuard.minutesLeft(now + 10 * PinGuard.GLOBAL_WINDOW_MS, now));
    }
}
