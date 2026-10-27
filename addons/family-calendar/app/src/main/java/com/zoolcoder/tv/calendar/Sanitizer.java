package com.zoolcoder.tv.calendar;

/** Cleans what a phone sends before it reaches the screen. The result is shown only as plain text. */
final class Sanitizer {
    static final int MAX_NAME = 24;
    static final int MAX_TITLE = 60;

    private Sanitizer() {
    }

    /** Who the event is for: one line, at most 24 chars after cleaning; null when empty or too long. */
    static String name(String raw) {
        return clean(raw, MAX_NAME);
    }

    /** The event title: one line, at most 60 chars after cleaning; null when empty or too long. */
    static String title(String raw) {
        return clean(raw, MAX_TITLE);
    }

    private static String clean(String raw, int maxChars) {
        if (raw == null) return null;
        StringBuilder out = new StringBuilder(raw.length());
        raw.codePoints().forEach(c -> {
            if (c == '\r' || c == '\n' || isOddSpace(c)) out.append(' ');
            else if (!isStripped(c)) out.appendCodePoint(c);
        });
        String trimmed = out.toString().trim().replaceAll(" {2,}", " ");
        if (trimmed.isEmpty() || trimmed.length() > maxChars) return null;
        return trimmed;
    }

    /** Spaces other than the plain one (no-break, thin, ideographic...), which become a plain space. */
    private static boolean isOddSpace(int c) {
        return c != ' ' && Character.getType(c) == Character.SPACE_SEPARATOR;
    }

    /**
     * Control characters, plus the invisible ones that reorder, hide or fake text: every format character
     * (bidi marks and overrides, zero-width spaces and joiners, the Arabic letter mark, the byte order mark)
     * and the line/paragraph separators. An all-invisible title is then empty and refused.
     */
    private static boolean isStripped(int c) {
        int type = Character.getType(c);
        return Character.isISOControl(c) || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }
}
