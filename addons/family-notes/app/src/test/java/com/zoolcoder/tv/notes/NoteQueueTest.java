package com.zoolcoder.tv.notes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class NoteQueueTest {
    private static Note note(int n) {
        return new Note("n" + n, "t", n);
    }

    @Test
    public void keepsOrder() {
        NoteQueue q = new NoteQueue();
        Note a = note(1), b = note(2);
        q.add(a);
        q.add(b);
        assertSame(a, q.next());
        assertSame(b, q.next());
        assertNull(q.next());
    }

    @Test
    public void pastTheCapTheOldestIsDropped() {
        NoteQueue q = new NoteQueue();
        for (int i = 1; i <= 100; i++) q.add(note(i));
        assertEquals(NoteQueue.MAX, q.size());
        assertEquals(96, q.next().time);
        assertEquals(97, q.next().time);
    }

    @Test
    public void clearEmptiesIt() {
        NoteQueue q = new NoteQueue();
        q.add(note(1));
        q.clear();
        assertEquals(0, q.size());
        assertNull(q.next());
    }
}
