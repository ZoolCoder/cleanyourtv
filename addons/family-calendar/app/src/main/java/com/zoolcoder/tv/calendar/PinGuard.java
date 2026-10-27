package com.zoolcoder.tv.calendar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The PIN check and the lockouts. Five wrong PINs in a row from one address lock that address out for five
 * minutes, right PIN or not; a right PIN before that resets its count. So one device on the Wi-Fi cannot lock
 * the whole family out. As a backstop, 50 wrong PINs within an hour from all addresses together lock everyone
 * out for five minutes and call {@code onTrip}, which makes a new PIN: someone is guessing in earnest. The
 * clock is injected so the lockouts can be tested.
 */
final class PinGuard {
    enum Result { OK, WRONG, LOCKED }

    static final int MAX_WRONG = 5;
    static final long LOCKOUT_MS = 5 * 60 * 1000L;
    static final int MAX_WRONG_ALL = 50;
    static final long WINDOW_MS = 60 * 60 * 1000L;
    static final int PIN_DIGITS = 6;
    /** Addresses remembered at once; the oldest is forgotten first. The backstop still counts every guess. */
    static final int MAX_PEERS = 64;

    /** Where the all-address count and lockout survive a service restart. */
    interface Memory {
        /** {wrong count in the window, window start, lockedUntil}; zeros when nothing is stored. */
        long[] load();

        void save(int wrong, long windowStart, long lockedUntil);
    }

    private static final class Peer {
        int wrong;
        long lockedUntil;
    }

    private final LongSupplier clock;
    private final Memory memory;
    private final Runnable onTrip;
    private final Map<String, Peer> peers = new LinkedHashMap<>(16, 0.75f, true);
    private int wrongAll;
    private long windowStart;
    private long lockedUntilAll;

    PinGuard(LongSupplier clock) {
        this(clock, null, null);
    }

    /** The clock must be wall time when a memory is given, so a lockout also outlasts a reboot. */
    PinGuard(LongSupplier clock, Memory memory, Runnable onTrip) {
        this.clock = clock;
        this.memory = memory;
        this.onTrip = onTrip;
        if (memory != null) restore(memory.load());
    }

    private void restore(long[] saved) {
        long now = clock.getAsLong();
        if (saved == null || saved.length < 3) return;
        windowStart = saved[1] > now || saved[1] <= now - WINDOW_MS ? 0 : saved[1];
        wrongAll = windowStart == 0 ? 0 : (int) Math.max(0, Math.min(saved[0], MAX_WRONG_ALL));
        lockedUntilAll = clamp(saved[2], now);
    }

    /** A lockout end no later than one lockout from now, so a clock set back cannot stretch it. */
    private static long clamp(long lockedUntil, long now) {
        return lockedUntil != 0 && lockedUntil > now ? Math.min(lockedUntil, now + LOCKOUT_MS) : 0;
    }

    private void remember() {
        if (memory != null) memory.save(wrongAll, windowStart, lockedUntilAll);
    }

    static String newPin(SecureRandom random) {
        StringBuilder pin = new StringBuilder(PIN_DIGITS);
        for (int i = 0; i < PIN_DIGITS; i++) pin.append(random.nextInt(10));
        return pin.toString();
    }

    synchronized Result check(String given, String pin) {
        return check("", given, pin);
    }

    synchronized Result check(String peerAddress, String given, String pin) {
        long now = clock.getAsLong();
        lockedUntilAll = clamp(lockedUntilAll, now);
        if (lockedUntilAll != 0) return Result.LOCKED;
        Peer peer = peers.get(peerAddress);
        if (peer != null) {
            peer.lockedUntil = clamp(peer.lockedUntil, now);
            if (peer.lockedUntil != 0) return Result.LOCKED;
            if (peer.wrong >= MAX_WRONG) peer.wrong = 0;
        }
        if (given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                pin.getBytes(StandardCharsets.UTF_8))) {
            if (peer != null) peers.remove(peerAddress);
            return Result.OK;
        }
        if (peer == null) peer = admit(peerAddress);
        if (++peer.wrong >= MAX_WRONG) peer.lockedUntil = now + LOCKOUT_MS;
        countWrong(now);
        return Result.WRONG;
    }

    private Peer admit(String address) {
        if (peers.size() >= MAX_PEERS) {
            Iterator<Map.Entry<String, Peer>> eldest = peers.entrySet().iterator();
            eldest.next();
            eldest.remove();
        }
        Peer peer = new Peer();
        peers.put(address, peer);
        return peer;
    }

    private void countWrong(long now) {
        if (windowStart == 0 || now - windowStart >= WINDOW_MS || now < windowStart) {
            windowStart = now;
            wrongAll = 0;
        }
        if (++wrongAll >= MAX_WRONG_ALL) {
            lockedUntilAll = now + LOCKOUT_MS;
            wrongAll = 0;
            windowStart = 0;
            peers.clear();
            if (onTrip != null) onTrip.run();
        }
        remember();
    }
}
