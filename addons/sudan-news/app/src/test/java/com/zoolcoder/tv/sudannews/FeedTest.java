package com.zoolcoder.tv.sudannews;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Reading the feeds' RSS the way Dabanga, Sudan Tribune and UN News write it. */
public class FeedTest {
    private static final String RSS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>"
            + "<title>Feed</title>"
            + "<item><title>Rights activist: Ceasefire top priority for Sudan&#8217;s women</title>"
            + "<pubDate>Sun, 25 Oct 2026 16:15:00 +0000</pubDate>"
            + "<description><![CDATA[<div><img src=\"a.jpg\" /></div>\n<p>El Obeid &amp; Kordofan &#8211; talks.</p>\n"
            + "<p>The post <a href=\"https://example.org\">Rights activist</a> appeared first on Dabanga.</p>]]></description></item>"
            + "<item><title>Soil mapping aims to boost agricultural production in Kenya</title>"
            + "<pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate><description>Kenya only.</description></item>"
            + "<item><title></title><description>No headline.</description></item>"
            + "</channel></rss>";

    private static List<Feed.Story> read(String line) throws Exception {
        return Feed.parse(line).read(RSS.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void readsHeadlineSummaryAndTime() throws Exception {
        List<Feed.Story> stories = read("en | Radio Dabanga | https://example.org/feed |");
        assertEquals(2, stories.size());
        Feed.Story first = stories.get(0);
        assertEquals("Rights activist: Ceasefire top priority for Sudan’s women", first.title);
        assertEquals("El Obeid & Kordofan – talks.", first.summary);
        assertEquals("Radio Dabanga", first.source);
        assertEquals("en", first.language);
        assertEquals(1792944900000L, first.time);
    }

    @Test
    public void keywordKeepsOnlyMatchingStories() throws Exception {
        List<Feed.Story> stories = read("en | UN News | https://example.org/feed | sudan");
        assertEquals(1, stories.size());
        assertEquals("Radio Dabanga", read("en | Radio Dabanga | https://example.org/feed").get(0).source);
    }

    @Test
    public void parsesConfigLines() {
        Feed feed = Feed.parse(" ar | سودان تربيون | https://sudantribune.net/feed/ | السودان ");
        assertEquals("ar", feed.language);
        assertEquals("سودان تربيون", feed.source);
        assertEquals("https://sudantribune.net/feed/", feed.url);
        assertEquals("السودان", feed.keyword);
        assertNull(Feed.parse("ar | no url"));
        assertNull(Feed.parse("ar | empty url |  | x"));
    }

    @Test
    public void readsBothTimeZoneForms() {
        assertEquals(1792756800000L, Feed.time("Fri, 23 Oct 2026 12:00:00 GMT"));
        assertEquals(1792756800000L, Feed.time("Fri, 23 Oct 2026 12:00:00 +0000"));
        assertEquals(0, Feed.time("yesterday"));
    }

    @Test
    public void plainTextDropsTagsAndKeepsUnknownEntities() {
        assertEquals("a & b &bogus; &#99999999;", Feed.plainText("<b>a</b> &amp; b &bogus; &#99999999;"));
        assertEquals("", Feed.plainText(null));
    }

    private static final long FAR = System.nanoTime() + 60_000_000_000L;

    @Test
    public void refusesDtdsAndUtf16() {
        assertTrue(Feed.declaresDtd("<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY a \"x\">]><rss/>".getBytes(StandardCharsets.UTF_8)));
        assertTrue(Feed.declaresDtd(new byte[] {(byte) 0xFF, (byte) 0xFE, '<', 0}));
        assertFalse(Feed.declaresDtd(RSS.getBytes(StandardCharsets.UTF_8)));
        try {
            read("en | x | https://example.org/f |", "<!DOCTYPE rss [<!ENTITY a \"x\">]><rss/>");
            fail();
        } catch (Exception expected) {
            // refused
        }
    }

    private static List<Feed.Story> read(String line, String xml) throws Exception {
        return Feed.parse(line).read(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void bodyStopsAtTheCap() throws Exception {
        byte[] exact = new byte[2 * 1024 * 1024];
        assertEquals(exact.length, Feed.body(new ByteArrayInputStream(exact), FAR).length);
        try {
            Feed.body(new ByteArrayInputStream(new byte[exact.length + 1]), FAR);
            fail();
        } catch (IOException expected) {
            // over the cap
        }
    }

    @Test
    public void bodyUnzipsButCapsTheUnzippedSize() throws Exception {
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), Feed.body(new ByteArrayInputStream(gzip(5)), FAR));
        try {
            Feed.body(new ByteArrayInputStream(gzip(3 * 1024 * 1024)), FAR);
            fail();
        } catch (IOException expected) {
            // a small gzip that grows past the cap
        }
    }

    @Test(expected = IOException.class)
    public void bodyGivesUpPastTheDeadline() throws Exception {
        Feed.body(new ByteArrayInputStream(new byte[10]), System.nanoTime() - 1);
    }

    private static byte[] gzip(int size) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            if (size == 5) zip.write("hello".getBytes(StandardCharsets.UTF_8));
            else zip.write(new byte[size]);
        }
        return out.toByteArray();
    }

    @Test
    public void hostileItemsStayShort() throws Exception {
        StringBuilder angle = new StringBuilder();
        for (int i = 0; i < 400000; i++) angle.append('<');
        String xml = "<rss><channel><item><title>" + new String(new char[5000]).replace('\0', 'a') + "</title>"
                + "<description>" + angle.toString().replace("<", "&lt;") + "</description></item></channel></rss>";
        long start = System.nanoTime();
        List<Feed.Story> stories = read("en | x | https://example.org/f |", xml);
        assertTrue((System.nanoTime() - start) < 2_000_000_000L);
        assertEquals(1, stories.size());
        assertTrue(stories.get(0).title.length() <= 301);
        assertTrue(stories.get(0).summary.length() <= 901);
    }

    @Test
    public void controlCharacterEntitiesBecomeSpaces() {
        assertEquals("a b", Feed.plainText("a&#0;b"));
        assertEquals("a &#55357; b", Feed.plainText("a &#55357; b"));
    }
}
