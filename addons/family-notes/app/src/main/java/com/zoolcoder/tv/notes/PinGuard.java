package com.zoolcoder.tv.notes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.function.LongSupplier;

/**
 * The PIN check and the lockout. Five wrong PINs in a row lock every note out for five minutes, right PIN
 * or not; a right PIN before that resets the count. The clock is injected so the lockout can be tested.
 */
final class PinGuard {
    enum Result { OK, WRONG, LOCKED }

    static final int MAX_WRONG = 5;
    static final long LOCKOUT_MS = 5 * 60 * 1000L;
    static final int PIN_DIGITS = 6;

    /** Where the count and the lockout end survive a service restart. */
    interface Memory {
        /** {wrong count, lockedUntil}; zeros when nothing is stored. */
        long[] load();

        void save(int wrong, long lockedUntil);
    }

    private final LongSupplier clock;
    private final Memory memory;
    private int wrong;
    private long lockedUntil;

    PinGuard(LongSupplier clock) {
        this(clock, null);
    }

    /** The clock must be wall time when a memory is given, so a lockout also outlasts a reboot. */
    PinGuard(LongSupplier clock, Memory memory) {
        this.clock = clock;
        this.memory = memory;
        if (memory != null) restore(memory.load());
    }

    private void restore(long[] saved) {
        long now = clock.getAsLong();
        wrong = (int) Math.max(0, Math.min(saved[0], MAX_WRONG));
        // A clock set back must not stretch the lockout past its length.
        lockedUntil = saved[1] > now ? Math.min(saved[1], now + LOCKOUT_MS) : 0;
        if (lockedUntil == 0 && wrong >= MAX_WRONG) wrong = 0;
    }

    private void remember() {
        if (memory != null) memory.save(wrong, lockedUntil);
    }

    static String newPin(SecureRandom random) {
        StringBuilder pin = new StringBuilder(PIN_DIGITS);
        for (int i = 0; i < PIN_DIGITS; i++) pin.append(random.nextInt(10));
        return pin.toString();
    }

    synchronized Result check(String given, String pin) {
        long now = clock.getAsLong();
        if (lockedUntil != 0) {
            if (now < lockedUntil) return Result.LOCKED;
            lockedUntil = 0;
            wrong = 0;
            remember();
        }
        if (given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                pin.getBytes(StandardCharsets.UTF_8))) {
            if (wrong != 0) {
                wrong = 0;
                remember();
            }
            return Result.OK;
        }
        if (++wrong >= MAX_WRONG) lockedUntil = now + LOCKOUT_MS;
        remember();
        return Result.WRONG;
    }
}
