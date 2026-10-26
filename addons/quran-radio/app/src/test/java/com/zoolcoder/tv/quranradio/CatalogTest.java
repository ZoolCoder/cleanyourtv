package com.zoolcoder.tv.quranradio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The config lines, and the answers of mp3quran.net as they are written today. */
public class CatalogTest {
    private static final String RECITER = "{\"reciters\":[{\"id\":123,\"name\":\"\\u0645\\u0634\\u0627\\u0631\\u064a \\u0627\\u0644\\u0639\\u0641\\u0627\\u0633\\u064a\","
            + "\"letter\":\"\\u0645\",\"moshaf\":["
            + "{\"id\":124,\"name\":\"Douri - Murattal\",\"rewaya_id\":12,\"server\":\"https:\\/\\/cdn.mp3quran.net\\/audio\\/mishary-alafasy\\/r2\\/\",\"surah_total\":3,\"surah_list\":\"1,2,114\"},"
            + "{\"id\":123,\"name\":\"Hafs - Murattal\",\"rewaya_id\":1,\"server\":\"https:\\/\\/cdn.mp3quran.net\\/audio\\/mishary-alafasy\\/r1\",\"surah_total\":4,\"surah_list\":\"1,2,3,114\"}]}]}";

    @Test
    public void readsAStationLine() {
        Catalog.Station station = Catalog.parseStation("إذاعة · Mix | https://backup.qurango.net/radio/mix");
        assertEquals("إذاعة · Mix", station.name);
        assertEquals("https://backup.qurango.net/radio/mix", station.url);
    }

    @Test
    public void refusesStationLinesThatCannotPlay() {
        assertNull(Catalog.parseStation("No url"));
        assertNull(Catalog.parseStation(" | https://example.org/live"));
        assertNull(Catalog.parseStation("Plain http | http://example.org/live"));
    }

    @Test
    public void readsReciterLines() {
        assertEquals(138, Catalog.parseRef("138").reciterId);
        assertEquals(0, Catalog.parseRef("138").moshafId);
        Catalog.Ref picked = Catalog.parseRef(" 118:269 | Husary, Al-Douri ");
        assertEquals(118, picked.reciterId);
        assertEquals(269, picked.moshafId);
        assertEquals(13, Catalog.parseRef("13 | Al-Zain").reciterId);
    }

    @Test
    public void refusesReciterLinesThatAreNotIds() {
        assertNull(Catalog.parseRef("Husary"));
        assertNull(Catalog.parseRef("1:2:3"));
        assertNull(Catalog.parseRef("0"));
        assertNull(Catalog.parseRef("12:x"));
        assertNull(Catalog.parseRef(""));
    }

    @Test
    public void picksTheNamedRecitation() {
        Catalog.Reciter reciter = Catalog.parseReciter(RECITER, new Catalog.Ref(123, 123));
        assertEquals("مشاري العفاسي", reciter.name);
        assertEquals("Hafs - Murattal", reciter.recitation);
        assertArrayEquals(new int[] {1, 2, 3, 114}, reciter.surahs);
        assertEquals("https://cdn.mp3quran.net/audio/mishary-alafasy/r1/", reciter.server);
    }

    @Test
    public void takesTheFirstRecitationWhenNoneIsNamed() {
        assertEquals("Douri - Murattal", Catalog.parseReciter(RECITER, new Catalog.Ref(123, 0)).recitation);
    }

    @Test
    public void returnsNullForAnUnknownRecitationOrBadJson() {
        assertNull(Catalog.parseReciter(RECITER, new Catalog.Ref(123, 999)));
        assertNull(Catalog.parseReciter("{\"reciters\":[]}", new Catalog.Ref(1, 0)));
        assertNull(Catalog.parseReciter("not json", new Catalog.Ref(1, 0)));
    }

    @Test
    public void refusesAServerThatIsNotHttps() {
        String json = RECITER.replace("https:\\/\\/cdn.mp3quran.net\\/audio\\/mishary-alafasy\\/r1", "http:\\/\\/example.org\\/r1");
        assertNull(Catalog.parseReciter(json, new Catalog.Ref(123, 123)));
    }

    @Test
    public void surahListKeepsOnlyRealSurahs() {
        assertArrayEquals(new int[] {1, 2, 114}, Catalog.parseSurahList("1, 2,0,x,115,114"));
        assertEquals(0, Catalog.parseSurahList("").length);
    }

    @Test
    public void buildsTheSurahFileName() {
        Catalog.Reciter reciter = Catalog.parseReciter(RECITER, new Catalog.Ref(123, 123));
        assertEquals("https://cdn.mp3quran.net/audio/mishary-alafasy/r1/002.mp3", Catalog.surahUrl(reciter, 2));
        assertEquals("https://cdn.mp3quran.net/audio/mishary-alafasy/r1/114.mp3", Catalog.surahUrl(reciter, 114));
    }

    @Test
    public void readsSurahNamesByNumber() {
        String[] names = Catalog.parseSuwar("{\"suwar\":[{\"id\":1,\"name\":\"Al-Fatihah \"},{\"id\":2,\"name\":\"Al-Baqarah\"}]}");
        assertEquals("Al-Fatihah", names[1]);
        assertEquals("Al-Baqarah", names[2]);
        assertEquals("", names[3]);
        assertEquals("", Catalog.parseSuwar("oops")[1]);
    }

    @Test
    public void buildsTheApiUrls() {
        assertEquals("https://www.mp3quran.net/api/v3/reciters?language=ar&reciter=138",
                Catalog.reciterUrl(new Catalog.Ref(138, 0), "ar"));
        assertEquals("https://www.mp3quran.net/api/v3/suwar?language=eng", Catalog.suwarUrl("eng"));
    }

    @Test
    public void trustsOnlyHttpsOnTheKnownHosts() {
        assertTrue(Catalog.isTrustedServer("https://server7.mp3quran.net/shatri/"));
        assertTrue(Catalog.isTrustedServer("https://MP3QURAN.net/a/"));
        assertTrue(Catalog.isTrustedServer("https://backup.qurango.net/x/"));
        assertFalse(Catalog.isTrustedServer("http://server7.mp3quran.net/a/"));
        assertFalse(Catalog.isTrustedServer("https://evilmp3quran.net/a/"));
        assertFalse(Catalog.isTrustedServer("https://mp3quran.net.evil.org/a/"));
        assertFalse(Catalog.isTrustedServer("https://mp3quran.net@evil.org/a/"));
        assertFalse(Catalog.isTrustedServer("https://example.org/a/"));
        assertFalse(Catalog.isTrustedServer("not a url"));
        assertFalse(Catalog.isTrustedServer(""));
    }

    @Test
    public void refusesAServerOnAnotherHost() {
        String json = RECITER.replace("cdn.mp3quran.net", "example.org");
        assertNull(Catalog.parseReciter(json, new Catalog.Ref(123, 123)));
    }

    @Test
    public void refusesStationUrlsWithoutAHost() {
        assertNull(Catalog.parseStation("X | https://"));
        assertNull(Catalog.parseStation("X | https://user@example.org/live"));
        assertNull(Catalog.parseStation("X | https://exa mple.org/live"));
    }

    @Test
    public void cleansTextFromTheNetwork() {
        assertEquals("a b", Catalog.clean("  a\nb\u0000 "));
        assertEquals(120, Catalog.clean(new String(new char[5000]).replace('\0', 'x')).length());
        assertEquals("", Catalog.clean(" \t "));
    }

    @Test
    public void refusesAnEmptyReciterNameOrAHugeSurahList() {
        assertNull(Catalog.parseReciter(RECITER.replace("\\u0645\\u0634\\u0627\\u0631\\u064a \\u0627\\u0644\\u0639\\u0641\\u0627\\u0633\\u064a", " "), new Catalog.Ref(123, 123)));
        String huge = new String(new char[3000]).replace("\0", "1,");
        assertNull(Catalog.parseReciter(RECITER.replace("1,2,3,114", huge), new Catalog.Ref(123, 123)));
    }
}
