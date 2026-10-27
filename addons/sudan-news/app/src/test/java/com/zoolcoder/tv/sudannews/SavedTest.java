package com.zoolcoder.tv.sudannews;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The stories saved for later: toggling, the cap, and the copy kept on the TV. */
public class SavedTest {
    private static Feed.Story story(String title) {
        return new Feed.Story("en", "Dabanga", title, "Summary of " + title, 1761400000000L);
    }

    @Test
    public void toggleSavesThenRemoves() {
        Saved saved = new Saved();
        assertTrue(saved.toggle(story("A")));
        assertTrue(saved.contains(story("A")));
        assertFalse(saved.toggle(story("A")));
        assertFalse(saved.contains(story("A")));
        assertTrue(saved.list().isEmpty());
    }

    @Test
    public void theNewestComesFirstAndTheOldestMakesRoom() {
        Saved saved = new Saved();
        for (int i = 0; i <= Saved.MAX; i++) saved.toggle(story("S" + i));
        assertEquals(Saved.MAX, saved.list().size());
        assertEquals("S" + Saved.MAX, saved.list().get(0).title);
        assertFalse(saved.contains(story("S0")));
        assertTrue(saved.contains(story("S1")));
    }

    @Test
    public void sameSourceAndHeadlineIsTheSameStory() {
        Saved saved = new Saved();
        saved.toggle(story("A"));
        Feed.Story again = new Feed.Story("en", "Dabanga", "A", "Another summary", 0);
        assertTrue(saved.contains(again));
        assertFalse(saved.contains(new Feed.Story("en", "UN News", "A", "", 0)));
    }

    @Test
    public void theKeptCopyComesBackInOrder() {
        Saved saved = new Saved();
        saved.toggle(story("Old"));
        saved.toggle(story("New"));
        Saved back = Saved.restore(saved.save());
        assertEquals(2, back.list().size());
        assertEquals("New", back.list().get(0).title);
        assertEquals("Summary of Old", back.list().get(1).summary);
    }

    @Test
    public void aDamagedCopyGivesAnEmptyList() {
        assertTrue(Saved.restore(null).list().isEmpty());
        assertTrue(Saved.restore("{").list().isEmpty());
    }

    @Test
    public void restoreDropsDuplicatesAndKeepsTheCap() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < Saved.MAX + 10; i++) {
            if (i > 0) json.append(',');
            json.append("{\"l\":\"en\",\"s\":\"X\",\"t\":\"T").append(i % (Saved.MAX + 5)).append("\"}");
        }
        Saved back = Saved.restore(json.append(']').toString());
        assertEquals(Saved.MAX, back.list().size());
    }
}
