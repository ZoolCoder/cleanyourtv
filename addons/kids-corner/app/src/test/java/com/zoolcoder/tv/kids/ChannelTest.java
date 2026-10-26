package com.zoolcoder.tv.kids;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Reading a channel's uploads feed the way YouTube writes it, and the config.xml lines. */
public class ChannelTest {
    private static final String ATOM = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<feed xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\" xmlns:media=\"http://search.yahoo.com/mrss/\""
            + " xmlns=\"http://www.w3.org/2005/Atom\">"
            + "<link rel=\"self\" href=\"http://www.youtube.com/feeds/videos.xml?channel_id=UCrvTAKeiHkB-ZTKxVBtk48A\"/>"
            + "<title>Majid Kids</title>"
            + entry("C-PE5WHpPug", "watch", "حلقة &quot;جديدة&quot; &amp; ممتعة")
            + entry("N2WZ6rV4l6Y", "shorts", "A short")
            + entry("rv6njAHaX7Y", "watch", "Second video")
            + "<entry><yt:videoId>bad</yt:videoId><title>Not an id</title></entry>"
            + "</feed>";

    private static String entry(String id, String kind, String title) {
        return "<entry><id>yt:video:" + id + "</id><yt:videoId>" + id + "</yt:videoId>"
                + "<title>" + title + "</title>"
                + "<link rel=\"alternate\" href=\"https://www.youtube.com/" + kind + (kind.equals("watch") ? "?v=" : "/") + id + "\"/>"
                + "<author><name>Majid Kids</name></author>"
                + "<media:group><media:title>" + title + "</media:title>"
                + "<media:thumbnail url=\"https://i2.ytimg.com/vi/" + id + "/hqdefault.jpg\" width=\"480\" height=\"360\"/>"
                + "</media:group></entry>";
    }

    private static List<Channel.Video> read(String xml) throws Exception {
        return Channel.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void readsVideosAndSkipsShortsAndBadIds() throws Exception {
        List<Channel.Video> videos = read(ATOM);
        assertEquals(2, videos.size());
        assertEquals("C-PE5WHpPug", videos.get(0).id);
        assertEquals("حلقة \"جديدة\" & ممتعة", videos.get(0).title);
        assertEquals("rv6njAHaX7Y", videos.get(1).id);
    }

    @Test
    public void buildsWatchAndThumbnailLinks() throws Exception {
        Channel.Video video = read(ATOM).get(0);
        assertEquals("https://www.youtube.com/watch?v=C-PE5WHpPug", video.watchUrl());
        assertEquals("https://i.ytimg.com/vi/C-PE5WHpPug/mqdefault.jpg", video.thumbnailUrl());
    }

    @Test
    public void keepsAtMostTwelveVideos() throws Exception {
        StringBuilder xml = new StringBuilder("<feed xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\">");
        for (int i = 0; i < 20; i++) xml.append(entry(String.format("video%06d", i).substring(0, 11), "watch", "T" + i));
        assertEquals(Channel.MAX_VIDEOS, read(xml + "</feed>").size());
    }

    @Test
    public void emptyFeedGivesNoVideos() throws Exception {
        assertEquals(0, read("<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>x</title></feed>").size());
    }

    @Test
    public void parsesConfigLines() {
        Channel channel = Channel.parse(" ماجد | Majid Kids | UCrvTAKeiHkB-ZTKxVBtk48A ");
        assertEquals("ماجد", channel.arabicName);
        assertEquals("Majid Kids", channel.englishName);
        assertEquals("https://www.youtube.com/feeds/videos.xml?channel_id=UCrvTAKeiHkB-ZTKxVBtk48A", channel.feedUrl());
    }

    @Test
    public void refusesLinesWithoutAChannelId() {
        assertNull(Channel.parse("ماجد | Majid Kids"));
        assertNull(Channel.parse("ماجد | Majid Kids | majidkids"));
        assertNull(Channel.parse("ماجد | Majid Kids | UCshort"));
    }

    @Test
    public void refusesAFeedWithADoctype() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE feed [<!ENTITY a \"aaaa\">]><feed><entry/></feed>";
        try {
            read(xml);
            fail("DTD accepted");
        } catch (Exception expected) {
            // refused
        }
    }

    @Test
    public void refusesAFeedOverTheSizeCap() {
        try {
            read("<feed>" + new String(new char[1024 * 1024]).replace('\0', 'a') + "</feed>");
            fail("huge feed accepted");
        } catch (Exception expected) {
            // refused
        }
    }

    @Test
    public void refusesAGzipBomb() throws Exception {
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(raw)) {
            byte[] chunk = new byte[65536];
            java.util.Arrays.fill(chunk, (byte) 'a');
            for (int i = 0; i < 40; i++) gz.write(chunk);
        }
        try {
            Channel.read(new ByteArrayInputStream(raw.toByteArray()));
            fail("bomb accepted");
        } catch (Exception expected) {
            // refused
        }
    }

    @Test
    public void malformedFeedThrows() {
        try {
            read("<feed><entry>");
            fail("malformed feed accepted");
        } catch (Exception expected) {
            // refused
        }
    }

    @Test
    public void cutsVeryLongTitles() throws Exception {
        String title = new String(new char[5000]).replace('\0', 'x');
        assertEquals(Channel.MAX_TITLE, read("<feed>" + entry("C-PE5WHpPug", "watch", title) + "</feed>").get(0).title.length());
    }

    @Test
    public void validatesVideoIds() {
        assertTrue(Channel.isVideoId("C-PE5WHpPug"));
        assertFalse(Channel.isVideoId("short"));
        assertFalse(Channel.isVideoId("C-PE5WHpPug&x=1"));
        assertFalse(Channel.isVideoId("../../etc/p"));
        assertFalse(Channel.isVideoId(""));
    }
}
