package com.zoolcoder.tv.notes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class StoreParseTest {
    @Test
    public void goodHistoryIsRead() {
        List<Note> notes = Store.parse("[{\"n\":\"Mum\",\"t\":\"Dinner\",\"w\":5}]");
        assertEquals(1, notes.size());
        assertEquals("Mum", notes.get(0).name);
        assertEquals(5, notes.get(0).time);
    }

    @Test
    public void theFromAbroadTagIsKept() {
        List<Note> notes = Store.parse("[{\"n\":\"Amna\",\"t\":\"Hi\",\"w\":5,\"a\":true},{\"n\":\"Mum\",\"t\":\"x\",\"w\":4}]");
        assertTrue(notes.get(0).away);
        assertFalse(notes.get(1).away);
    }

    @Test
    public void garbageGivesAnEmptyList() {
        for (String bad : new String[] {"", "{", "null", "{}", "\"x\"", "[[[", "42", "[1,2,null]"}) {
            assertTrue(bad, Store.parse(bad).isEmpty());
        }
    }

    @Test
    public void badEntriesAreSkippedAndGoodOnesKept() {
        List<Note> notes = Store.parse("[{\"n\":\"A\",\"t\":\"x\",\"w\":1},null,5,{\"n\":\"B\"},"
                + "{\"n\":\"C\",\"t\":\"y\",\"w\":\"zz\"},{\"n\":\"D\",\"t\":\"z\",\"w\":4}]");
        assertEquals(2, notes.size());
        assertEquals("A", notes.get(0).name);
        assertEquals("D", notes.get(1).name);
    }

    @Test
    public void hostileValuesAreCleanedOrDropped() {
        String longText = new String(new char[5000]).replace('\0', 'x');
        List<Note> notes = Store.parse("[{\"n\":\"a\\u202eb\",\"t\":\"hi\",\"w\":1},{\"n\":\"n\",\"t\":\"" + longText + "\",\"w\":2},"
                + "{\"n\":\"n\",\"t\":\"t\",\"w\":-9}]");
        assertEquals(1, notes.size());
        assertEquals("ab", notes.get(0).name);
    }

    @Test
    public void onlyTheLastTenAreRead() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 50; i++) json.append(i > 0 ? "," : "").append("{\"n\":\"a\",\"t\":\"b\",\"w\":").append(i).append('}');
        assertEquals(Store.KEEP, Store.parse(json.append(']').toString()).size());
    }
}
