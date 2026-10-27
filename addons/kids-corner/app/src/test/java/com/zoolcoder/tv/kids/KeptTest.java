package com.zoolcoder.tv.kids;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The saved feeds: what goes into the file comes back out, damage is dropped, and the file stays capped. */
public class KeptTest {
    private static final String MAJID = "UCrvTAKeiHkB-ZTKxVBtk48A", TOYOR = "UCozAuGJZ5MPoijz8IE_1Gtw";

    @Test
    public void restoresWhatWasWritten() {
        Map<String, List<Channel.Video>> feeds = new LinkedHashMap<>();
        feeds.put(MAJID, Arrays.asList(new Channel.Video("C-PE5WHpPug", "حلقة جديدة"),
                new Channel.Video("rv6njAHaX7Y", "Second\tvideo")));
        feeds.put(TOYOR, Arrays.asList(new Channel.Video("N2WZ6rV4l6Y", "Toyor")));
        Map<String, List<Channel.Video>> back = Kept.read(Kept.write(feeds));
        assertEquals(Arrays.asList(MAJID, TOYOR), new ArrayList<>(back.keySet()));
        assertEquals("C-PE5WHpPug", back.get(MAJID).get(0).id);
        assertEquals("حلقة جديدة", back.get(MAJID).get(0).title);
        assertEquals("Second video", back.get(MAJID).get(1).title);
        assertEquals(1, back.get(TOYOR).size());
    }

    @Test
    public void dropsDamagedLines() {
        String text = MAJID + "\tC-PE5WHpPug\tGood\n"
                + "not-a-channel\trv6njAHaX7Y\tBad channel\n"
                + MAJID + "\tbad\tBad video\n"
                + MAJID + "\tN2WZ6rV4l6Y\t   \n"
                + "garbage\n";
        Map<String, List<Channel.Video>> back = Kept.read(text);
        assertEquals(1, back.size());
        assertEquals(1, back.get(MAJID).size());
        assertEquals(0, Kept.read("").size());
    }

    @Test
    public void staysUnderTheCap() {
        Map<String, List<Channel.Video>> feeds = new LinkedHashMap<>();
        char[] longTitle = new char[Channel.MAX_TITLE];
        Arrays.fill(longTitle, 'ب');
        for (int c = 0; c < 2000; c++) {
            List<Channel.Video> videos = new ArrayList<>();
            for (int v = 0; v < Channel.MAX_VIDEOS; v++) videos.add(new Channel.Video("C-PE5WHpPug", new String(longTitle)));
            feeds.put(String.format("UC%022d", c), videos);
        }
        String text = Kept.write(feeds);
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= Kept.MAX_BYTES);
        assertTrue(text.endsWith("\n"));
        assertTrue(Kept.read(text).size() > 0);
    }

    @Test
    public void favouritesComeFirstInConfigOrder() {
        List<Channel> channels = Arrays.asList(Channel.parse("أ | A | " + MAJID), Channel.parse("ب | B | " + TOYOR),
                Channel.parse("ج | C | UC2mcPN2YDX31oaLnfZScx_g"));
        List<Channel> ordered = Channel.favouritesFirst(channels,
                new java.util.HashSet<>(Arrays.asList("UC2mcPN2YDX31oaLnfZScx_g", TOYOR)));
        assertEquals("B", ordered.get(0).englishName);
        assertEquals("C", ordered.get(1).englishName);
        assertEquals("A", ordered.get(2).englishName);
    }
}
