package com.zoolcoder.tv.notes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;
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
    public void lockoutSurvivesARestart() {
        Mem mem = new Mem();
        PinGuard first = new PinGuard(now::get, mem);
        for (int i = 0; i < 5; i++) first.check("000000", "123456");
        PinGuard restarted = new PinGuard(now::get, mem);
        assertEquals(PinGuard.Result.LOCKED, restarted.check("123456", "123456"));
        now.addAndGet(PinGuard.LOCKOUT_MS);
        assertEquals(PinGuard.Result.OK, new PinGuard(now::get, mem).check("123456", "123456"));
    }

    @Test
    public void wrongCountSurvivesARestart() {
        Mem mem = new Mem();
        PinGuard first = new PinGuard(now::get, mem);
        for (int i = 0; i < 4; i++) first.check("000000", "123456");
        PinGuard restarted = new PinGuard(now::get, mem);
        assertEquals(PinGuard.Result.WRONG, restarted.check("000000", "123456"));
        assertEquals(PinGuard.Result.LOCKED, restarted.check("123456", "123456"));
    }

    @Test
    public void rightPinAndExpiryClearTheStoredState() {
        Mem mem = new Mem();
        PinGuard guard = new PinGuard(now::get, mem);
        guard.check("000000", "123456");
        guard.check("123456", "123456");
        assertEquals(0, mem.saved[0]);
        for (int i = 0; i < 5; i++) guard.check("000000", "123456");
        assertTrue(mem.saved[1] > 0);
        now.addAndGet(PinGuard.LOCKOUT_MS);
        guard.check("123456", "123456");
        assertEquals(0, mem.saved[1]);
    }

    @Test
    public void storedLockoutBeyondItsLengthIsClamped() {
        Mem mem = new Mem();
        mem.saved = new long[] {5, now.get() + 10 * PinGuard.LOCKOUT_MS};
        PinGuard guard = new PinGuard(now::get, mem);
        assertEquals(PinGuard.Result.LOCKED, guard.check("123456", "123456"));
        now.addAndGet(PinGuard.LOCKOUT_MS);
        assertEquals(PinGuard.Result.OK, guard.check("123456", "123456"));
    }

    @Test
    public void junkStoredStateIsHarmless() {
        Mem mem = new Mem();
        mem.saved = new long[] {-7, -1};
        assertEquals(PinGuard.Result.OK, new PinGuard(now::get, mem).check("123456", "123456"));
        mem.saved = new long[] {999, 0};
        assertEquals(PinGuard.Result.OK, new PinGuard(now::get, mem).check("123456", "123456"));
    }
}
