package com.zoolcoder.tv.health;

/** One person in the family: a name and the colour of their initial disc. No photos. */
final class Member {
    /** As many as Look.WHO has. */
    static final int COLORS = 6;

    final String id, name;
    /** An index into Look.WHO. */
    final int color;

    Member(String id, String name, int color) {
        this.id = id;
        this.name = name;
        this.color = Math.floorMod(color, COLORS);
    }

    /** The first letter for the disc, Arabic or Latin alike; "?" for an empty name. */
    String initial() {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return "?";
        return new String(Character.toChars(trimmed.codePointAt(0))).toUpperCase(java.util.Locale.getDefault());
    }
}
