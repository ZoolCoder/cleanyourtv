package com.zoolcoder.tv.notes;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** One of six colours per sender name, the same on every TV and after every restart, for the bar beside a note. */
final class Senders {
    /** Bright enough to read as a bar on the dark panels; none is the red kept for Clear all notes. */
    static final int[] PALETTE = {0xFF5CC8FF, 0xFFFFC542, 0xFFFF8FB1, 0xFFB65CFF, 0xFF7CE0A0, 0xFFFF9B6B};

    private Senders() {
    }

    /** FNV-1a over the trimmed, lower-cased name's UTF-8 bytes: String.hashCode would do, but this says it is fixed. */
    static int colour(String name) {
        int hash = 0x811C9DC5;
        for (byte b : name.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xFF;
            hash *= 0x01000193;
        }
        return PALETTE[Integer.remainderUnsigned(hash, PALETTE.length)];
    }
}
