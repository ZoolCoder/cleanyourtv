package com.zoolcoder.tv.kids;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Continue watching: newest first, no repeats, at most eight, and the same list after a restart. */
public class RecentTest {
    private static final String MAJID = "UCrvTAKeiHkB-ZTKxVBtk48A";

    private static String id(int n) {
        return String.format("video%06d", n);
    }

    @Test
    public void newestFirstAndCapped() {
        Recent recent = new Recent();
        for (int i = 0; i < 12; i++) recent.add(new Channel.Video(id(i), "Video " + i), MAJID);
        assertEquals(Recent.MAX, recent.entries().size());
        assertEquals(id(11), recent.entries().get(0).video.id);
        assertEquals(id(4), recent.entries().get(Recent.MAX - 1).video.id);
    }

    @Test
    public void openingAgainMovesToTheFront() {
        Recent recent = new Recent();
        recent.add(new Channel.Video(id(1), "One"), MAJID);
        recent.add(new Channel.Video(id(2), "Two"), MAJID);
        recent.add(new Channel.Video(id(1), "One again"), MAJID);
        assertEquals(2, recent.entries().size());
        assertEquals(id(1), recent.entries().get(0).video.id);
        assertEquals("One again", recent.entries().get(0).video.title);
        assertEquals(id(2), recent.entries().get(1).video.id);
    }

    @Test
    public void survivesARestart() {
        Recent recent = new Recent();
        for (int i = 0; i < 5; i++) recent.add(new Channel.Video(id(i), "عنوان " + i), MAJID);
        Recent back = Recent.read(recent.write());
        assertEquals(5, back.entries().size());
        for (int i = 0; i < 5; i++) {
            assertEquals(recent.entries().get(i).video.id, back.entries().get(i).video.id);
            assertEquals(recent.entries().get(i).video.title, back.entries().get(i).video.title);
            assertEquals(MAJID, back.entries().get(i).channelId);
        }
    }

    @Test
    public void dropsBadLinesAndRepeats() {
        String text = id(1) + "\t" + MAJID + "\tNewest\n"
                + "bad\t" + MAJID + "\tBad id\n"
                + id(2) + "\tnot-a-channel\tBad channel\n"
                + id(1) + "\t" + MAJID + "\tOlder copy\n";
        Recent back = Recent.read(text);
        assertEquals(1, back.entries().size());
        assertEquals("Newest", back.entries().get(0).video.title);
        assertEquals(0, Recent.read(null).entries().size());
    }
}
