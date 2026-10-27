package com.zoolcoder.tv.quranradio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Retries, favourite order and the sleep timer. */
public class PlaybackTest {
    private static Catalog.Station station(String name) {
        return new Catalog.Station(name, "https://backup.qurango.net/radio/" + name);
    }

    @Test
    public void retriesOnceThenGivesUp() {
        Playback.Retry retry = new Playback.Retry();
        assertTrue(retry.retryAfterFailure());
        assertFalse(retry.retryAfterFailure());
        assertFalse(retry.retryAfterFailure());
    }

    @Test
    public void aStreamThatStartedGetsItsRetryBack() {
        Playback.Retry retry = new Playback.Retry();
        assertTrue(retry.retryAfterFailure());
        retry.reset();
        assertTrue(retry.retryAfterFailure());
        assertFalse(retry.retryAfterFailure());
    }

    @Test
    public void favouritesComeFirstAndKeepTheirOrder() {
        List<Catalog.Station> stations = Arrays.asList(station("a"), station("b"), station("c"), station("d"));
        List<Catalog.Station> sorted = Playback.favouritesFirst(stations,
                new HashSet<>(Arrays.asList(station("d").url, station("b").url)));
        assertEquals("b", sorted.get(0).name);
        assertEquals("d", sorted.get(1).name);
        assertEquals("a", sorted.get(2).name);
        assertEquals("c", sorted.get(3).name);
    }

    @Test
    public void noFavouritesKeepsTheListAsWritten() {
        List<Catalog.Station> stations = Arrays.asList(station("a"), station("b"));
        List<Catalog.Station> sorted = Playback.favouritesFirst(stations,
                Collections.singleton("https://example.org/gone"));
        assertEquals("a", sorted.get(0).name);
        assertEquals("b", sorted.get(1).name);
    }

    @Test
    public void sleepStepsCycleBackToOff() {
        assertEquals(15, Playback.nextSleepStep(0));
        assertEquals(30, Playback.nextSleepStep(15));
        assertEquals(60, Playback.nextSleepStep(30));
        assertEquals(90, Playback.nextSleepStep(60));
        assertEquals(0, Playback.nextSleepStep(90));
        assertEquals(0, Playback.nextSleepStep(45));
    }

    @Test
    public void minutesLeftRoundUp() {
        assertEquals(30, Playback.minutesLeft(30 * 60000));
        assertEquals(30, Playback.minutesLeft(29 * 60000 + 1));
        assertEquals(1, Playback.minutesLeft(1));
        assertEquals(0, Playback.minutesLeft(0));
        assertEquals(0, Playback.minutesLeft(-5));
    }

    @Test
    public void fadeRunsOverTheLastTenSeconds() {
        assertEquals(1f, Playback.fadeVolume(60000), 0f);
        assertEquals(1f, Playback.fadeVolume(10000), 0f);
        assertEquals(0.5f, Playback.fadeVolume(5000), 0.001f);
        assertEquals(0f, Playback.fadeVolume(0), 0f);
    }
}
