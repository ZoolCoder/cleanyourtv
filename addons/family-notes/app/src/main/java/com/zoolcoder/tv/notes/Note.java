package com.zoolcoder.tv.notes;

/** One cleaned note: who sent it, what it says and when it arrived (epoch milliseconds). */
final class Note {
    final String name;
    final String text;
    final long time;

    Note(String name, String text, long time) {
        this.name = name;
        this.text = text;
        this.time = time;
    }
}
