package com.zoolcoder.tv.notes;

/** A reminder that Family Calendar hands over when it pops up, kept in the list like a note. */
final class Reminders {
    static final String ACTION = "com.zoolcoder.tv.notes.REMINDER";
    static final String TITLE = "title", WHO = "who", WHEN = "when";
    /** A long weekday and month name with a 12-hour time, in Arabic, reach 26 characters. */
    static final int MAX_WHEN = 40;

    private Reminders() {
    }

    /**
     * "Dentist · Fri 21 Nov 18:30 · Amna" under the calendar's name, cleaned like a note from a phone. When the
     * whole line is too long only the title is kept; null when there is no title.
     */
    static Note note(String title, String who, String when, String from, long now) {
        String text = Sanitizer.text(title);
        if (text == null) return null;
        StringBuilder line = new StringBuilder(text);
        for (String part : new String[] {Sanitizer.line(when, MAX_WHEN), Sanitizer.name(who)}) {
            if (part != null) line.append(" · ").append(part);
        }
        String full = Sanitizer.text(line.toString());
        return new Note(from, full != null ? full : text, now, false, true);
    }
}
