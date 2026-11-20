package com.zoolcoder.tv.notes;

/** One cleaned note: who sent it, what it says, when it arrived (epoch milliseconds), and whether from abroad or a reminder. */
final class Note {
    final String name;
    final String text;
    final long time;
    /** Came through the Family away relay rather than from a phone on the home Wi-Fi. */
    final boolean away;
    /** Handed over by Family Calendar when its reminder popped up, not sent by a phone. */
    final boolean reminder;

    Note(String name, String text, long time) {
        this(name, text, time, false);
    }

    Note(String name, String text, long time, boolean away) {
        this(name, text, time, away, false);
    }

    Note(String name, String text, long time, boolean away, boolean reminder) {
        this.name = name;
        this.text = text;
        this.time = time;
        this.away = away;
        this.reminder = reminder;
    }
}
