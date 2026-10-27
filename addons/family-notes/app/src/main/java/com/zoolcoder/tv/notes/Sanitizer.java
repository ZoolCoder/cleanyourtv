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
            } else if (Character.getType(c) == Character.SPACE_SEPARATOR) {
                // Every kind of space becomes a plain one, so no-break and wide spaces cannot fake a gap.
                out.append(' ');
            } else if (!isStripped(c)) {
                out.append(c);
            }
        }
        // Trim each line and collapse its spaces, then drop empty ones: "max 4 lines" counts lines with something on them.
        StringBuilder lines = new StringBuilder();
        int count = 0;
        for (String line : out.toString().split("\n", -1)) {
            String trimmed = collapse(line);
            if (trimmed.isEmpty()) continue;
            if (++count > maxLines) return null;
            if (lines.length() > 0) lines.append('\n');
            lines.append(trimmed);
        }
        if (count == 0 || lines.length() > maxChars) return null;
        return lines.toString();
    }

    /** Trims, and turns each run of spaces into one, so a name cannot pad itself to look like another. */
    private static String collapse(String line) {
        StringBuilder out = new StringBuilder(line.length());
        boolean space = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                space = out.length() > 0;
            } else {
                if (space) out.append(' ');
                space = false;
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Control characters, plus every invisible format character: bidi overrides and isolates, zero-width
     * spaces and joiners, the Arabic letter mark (U+061C) and the byte-order mark; and the line and paragraph
     * separators. Without them a name of only zero-width spaces would pass as non-empty and show as nothing.
     */
    private static boolean isStripped(char c) {
        int type = Character.getType(c);
        return Character.isISOControl(c) || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }
}
