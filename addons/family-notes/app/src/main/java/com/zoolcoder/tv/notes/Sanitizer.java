package com.zoolcoder.tv.notes;

/** Cleans what a phone sends before it reaches the screen. The result is shown only as plain text. */
final class Sanitizer {
    static final int MAX_NAME = 24;
    static final int MAX_TEXT = 280;
    static final int MAX_LINES = 4;

    private Sanitizer() {
    }

    /** One line, at most 24 chars after cleaning; null when empty or too long. */
    static String name(String raw) {
        return clean(raw, MAX_NAME, 1, true);
    }

    /** At most 280 chars and 4 lines after cleaning; null when empty or too long. */
    static String text(String raw) {
        return clean(raw, MAX_TEXT, MAX_LINES, false);
    }

    private static String clean(String raw, int maxChars, int maxLines, boolean singleLine) {
        if (raw == null) return null;
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\r' && i + 1 < raw.length() && raw.charAt(i + 1) == '\n') continue;
            if (c == '\r' || c == '\n') {
                out.append(singleLine ? ' ' : '\n');
            } else if (!isStripped(c)) {
                out.append(c);
            }
        }
        // Trim each line, then drop empty ones: "max 4 lines" counts lines with something on them.
        StringBuilder lines = new StringBuilder();
        int count = 0;
        for (String line : out.toString().split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (++count > maxLines) return null;
            if (lines.length() > 0) lines.append('\n');
            lines.append(trimmed);
        }
        if (count == 0 || lines.length() > maxChars) return null;
        return lines.toString();
    }

    /** Control characters, plus the invisible ones that reorder or fake text: bidi overrides and line/paragraph separators. */
    private static boolean isStripped(char c) {
        return Character.isISOControl(c)
                || (c >= '‪' && c <= '‮')
                || (c >= '⁦' && c <= '⁩')
                || c == ' ' || c == ' ';
    }
}
