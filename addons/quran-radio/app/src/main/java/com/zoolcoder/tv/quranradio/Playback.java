package com.zoolcoder.tv.quranradio;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The rules of the player that need no Android: retries, favourites, the sleep timer. The JVM tests reach it. */
final class Playback {
    /** "Connecting…" shows only when a stream has not started within this time. */
    static final long CONNECTING_DELAY_MS = 500;
    /** A stream that has not started by then counts as failed. */
    static final long TIMEOUT_MS = 10000;
    /** The one automatic retry waits this long. */
    static final long RETRY_DELAY_MS = 2000;
    /** The sleep timer fades the sound out over its last ten seconds. */
    static final long FADE_MS = 10000;

    private static final int[] SLEEP_STEPS = {0, 15, 30, 60, 90};

    private Playback() {}

    /** One automatic retry per start: a stream that played once gets its retry back. */
    static final class Retry {
        private int failures;

        /** The stream started, or a new one was chosen. */
        void reset() {
            failures = 0;
        }

        /** A start failed: true to try once more after RETRY_DELAY_MS, false to give up and say so. */
        boolean retryAfterFailure() {
            return ++failures <= 1;
        }
    }

    /** Favourite stations first, each group in the order of the list. */
    static List<Catalog.Station> favouritesFirst(List<Catalog.Station> stations, Set<String> favourites) {
        List<Catalog.Station> sorted = new ArrayList<>(stations.size());
        for (Catalog.Station station : stations) {
            if (favourites.contains(station.url)) sorted.add(station);
        }
        for (Catalog.Station station : stations) {
            if (!favourites.contains(station.url)) sorted.add(station);
        }
        return sorted;
    }

    /** Off, 15, 30, 60, 90 minutes, then off again. A value off the steps starts over at off. */
    static int nextSleepStep(int minutes) {
        for (int i = 0; i < SLEEP_STEPS.length - 1; i++) {
            if (SLEEP_STEPS[i] == minutes) return SLEEP_STEPS[i + 1];
        }
        return 0;
    }

    /** Whole minutes left, rounded up so the last minute shows 1 until the sound stops. */
    static int minutesLeft(long remainingMs) {
        return remainingMs <= 0 ? 0 : (int) ((remainingMs + 59999) / 60000);
    }

    /** Volume during the fade: full until the last FADE_MS, then down in a straight line to silence. */
    static float fadeVolume(long remainingMs) {
        if (remainingMs >= FADE_MS) return 1f;
        if (remainingMs <= 0) return 0f;
        return (float) remainingMs / FADE_MS;
    }
}
