package com.zoolcoder.tv.notes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class SendersTest {
    @Test
    public void sameNameSameColour() {
        assertEquals(Senders.colour("Mama"), Senders.colour("Mama"));
        assertEquals(Senders.colour("Mama"), Senders.colour("  mama "));
        assertEquals(Senders.colour("أمي"), Senders.colour("أمي"));
    }

    /** Pinned values: a change to the hash would repaint every family member. */
    @Test
    public void colourIsFixedAcrossVersions() {
        assertEquals(Senders.PALETTE[Integer.remainderUnsigned(0x811C9DC5, 6)], Senders.colour(""));
        assertEquals(expected("Ali"), Senders.colour("Ali"));
    }

    @Test
    public void namesSpreadOverThePalette() {
        Set<Integer> seen = new HashSet<>();
        String[] names = {"Ali", "Mama", "Baba", "Sara", "Omar", "Huda", "Ahmed", "Fatima", "Yousif", "Amna",
                "أمي", "أبي", "جدتي", "خالي"};
        for (String name : names) {
            int colour = Senders.colour(name);
            boolean inPalette = false;
            for (int c : Senders.PALETTE) inPalette |= c == colour;
            assertTrue(name, inPalette);
            seen.add(colour);
        }
        assertTrue("only " + seen.size() + " colours", seen.size() >= 4);
    }

    /** FNV-1a written out again, independently of Senders. */
    private static int expected(String name) {
        long hash = 2166136261L;
        for (byte b : name.toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hash = ((hash ^ (b & 0xFF)) * 16777619L) & 0xFFFFFFFFL;
        }
        return Senders.PALETTE[(int) (hash % 6)];
    }
}
