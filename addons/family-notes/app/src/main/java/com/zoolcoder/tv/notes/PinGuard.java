package com.zoolcoder.tv.notes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The PIN check and the lockouts. Wrong PINs are counted per phone (peer address): five in a row lock that
 * phone out for five minutes, right PIN or not, so one device on the Wi-Fi cannot lock the family out. A
 * right PIN before that resets its count. As a backstop, 50 wrong PINs within an hour from all phones together
 * lock everyone out for five minutes. Every lockout calls the trip listener, which makes a new PIN. The
 * clock is injected so the lockouts can be tested.
 */
final class PinGuard {
    enum Result { OK, WRONG, LOCKED }

    static final int MAX_WRONG = 5;
    static final long LOCKOUT_MS = 5 * 60 * 1000L;
    static final int GLOBAL_MAX_WRONG = 50;
    static final long GLOBAL_WINDOW_MS = 60 * 60 * 1000L;
    static final int MAX_PEERS = 64;
    static final int PIN_DIGITS = 6;

    /** Where the everyone-lockout survives a service restart. Per-phone counts live in memory only. */
    interface Memory {
        /** {wrong count in the current hour, lockedUntil}; zeros when nothing is stored. */
        long[] load();

        void save(int wrong, long lockedUntil);
    }

    private static final class Peer {
        int wrong;
        long lockedUntil;
    }

    private final LongSupplier clock;
    private final Memory memory;
    /** Oldest-used first, so the cap drops the phone not heard from longest. */
    private final Map<String, Peer> peers = new LinkedHashMap<>(16, 0.75f, true);
    private int globalWrong;
    private long windowStart;
    private long lockedUntil;
    private Runnable onTrip;

    PinGuard(LongSupplier clock) {
        this(clock, null);
    }

    /** The clock must be wall time when a memory is given, so a lockout also outlasts a reboot. */
    PinGuard(LongSupplier clock, Memory memory) {
        this.clock = clock;
        this.memory = memory;
        windowStart = clock.getAsLong();
        if (memory != null) restore(memory.load());
    }

    /** Runs on the connection thread each time a phone or everyone gets locked out. */
    synchronized void setOnTrip(Runnable onTrip) {
        this.onTrip = onTrip;
    }

    private void restore(long[] saved) {
        long now = clock.getAsLong();
        globalWrong = (int) Math.max(0, Math.min(saved[0], GLOBAL_MAX_WRONG));
        lockedUntil = clamp(saved[1], now);
        if (lockedUntil == 0 && globalWrong >= GLOBAL_MAX_WRONG) globalWrong = 0;
    }

    /** A clock set back must not stretch a lockout past its length; one already over is 0. */
    private static long clamp(long until, long now) {
        return until > now ? Math.min(until, now + LOCKOUT_MS) : 0;
    }

    private void remember() {
        if (memory != null) memory.save(globalWrong, lockedUntil);
    }

    static String newPin(SecureRandom random) {
        StringBuilder pin = new StringBuilder(PIN_DIGITS);
        for (int i = 0; i < PIN_DIGITS; i++) pin.append(random.nextInt(10));
        return pin.toString();
    }

    /** Whole minutes left in a lockout ending at lockedUntil, rounded up; 0 when none runs. Never above the length. */
    static int minutesLeft(long lockedUntil, long now) {
        if (lockedUntil <= now) return 0;
        long left = Math.min(lockedUntil - now, LOCKOUT_MS);
        return (int) ((left + 59_999) / 60_000);
    }

    /** For callers with no peer address: they all count as one phone. */
    Result check(String given, String pin) {
        return check("", given, pin);
    }

    Result check(String peer, String given, String pin) {
        Runnable trip;
        Result result;
        synchronized (this) {
            result = decide(peer == null ? "" : peer, given, pin);
            trip = tripped ? onTrip : null;
            tripped = false;
        }
        if (trip != null) trip.run();
        return result;
    }

    /** Set by decide when this try started a lockout; read and cleared under the same lock. */
    private boolean tripped;

    private Result decide(String address, String given, String pin) {
        long now = clock.getAsLong();
        if (now - windowStart >= GLOBAL_WINDOW_MS || now < windowStart) {
            windowStart = now;
            if (globalWrong != 0) {
                globalWrong = 0;
                remember();
            }
        }
        if (lockedUntil != 0) {
            lockedUntil = clamp(lockedUntil, now);
            if (lockedUntil != 0) return Result.LOCKED;
            globalWrong = 0;
            remember();
        }
        Peer state = peers.get(address);
        if (state != null && state.lockedUntil != 0) {
            state.lockedUntil = clamp(state.lockedUntil, now);
            if (state.lockedUntil != 0) return Result.LOCKED;
            state.wrong = 0;
        }
        if (given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                pin.getBytes(StandardCharsets.UTF_8))) {
            if (state != null) peers.remove(address);
            return Result.OK;
        }
        if (state == null) {
            state = new Peer();
            peers.put(address, state);
            trim(now);
        }
        // The try that starts a lockout still hears "wrong"; only later tries hear "locked".
        if (++state.wrong >= MAX_WRONG) {
            state.lockedUntil = now + LOCKOUT_MS;
            tripped = true;
        }
        if (++globalWrong >= GLOBAL_MAX_WRONG) {
            lockedUntil = now + LOCKOUT_MS;
            tripped = true;
        }
        remember();
        return Result.WRONG;
    }

    /** Keeps at most MAX_PEERS phones, dropping first those with nothing running, then the oldest. */
    private void trim(long now) {
        if (peers.size() <= MAX_PEERS) return;
        for (Iterator<Peer> it = peers.values().iterator(); it.hasNext() && peers.size() > MAX_PEERS; ) {
            if (clamp(it.next().lockedUntil, now) == 0) it.remove();
        }
        for (Iterator<Peer> it = peers.values().iterator(); it.hasNext() && peers.size() > MAX_PEERS; ) {
            it.next();
            it.remove();
        }
    }

    /** When the everyone-lockout ends, or 0. */
    synchronized long lockedUntil() {
        return clamp(lockedUntil, clock.getAsLong());
    }
}
