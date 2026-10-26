package com.zoolcoder.tv.notes;

import java.util.ArrayDeque;

/** Notes waiting for the pop-up: at most 5, and a sixth pushes out the oldest. Main thread only. */
final class NoteQueue {
    static final int MAX = 5;

    private final ArrayDeque<Note> waiting = new ArrayDeque<>();

    void add(Note note) {
        if (waiting.size() >= MAX) waiting.pollFirst();
        waiting.addLast(note);
    }

    /** The oldest waiting note, or null. */
    Note next() {
        return waiting.pollFirst();
    }

    void clear() {
        waiting.clear();
    }

    int size() {
        return waiting.size();
    }
}
