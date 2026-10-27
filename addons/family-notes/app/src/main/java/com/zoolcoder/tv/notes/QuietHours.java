package com.zoolcoder.tv.notes;

/**
 * Night windows when notes are kept and listed but do not pop up. The row cycles Off, 22:00–07:00, 23:00–08:00,
 * 21:00–06:00; each window starts in the evening and ends the next morning.
 */
final class QuietHours {
    /** {start hour, end hour}; index 0 is Off. */
    private static final int[][] WINDOWS = {null, {22, 7}, {23, 8}, {21, 6}};

    private QuietHours() {
    }

    static int count() {
        return WINDOWS.length;
    }

    /** A stored choice that is no longer valid reads as Off. */
    static int valid(int choice) {
        return choice >= 0 && choice < WINDOWS.length ? choice : 0;
    }

    static int next(int choice) {
        return (valid(choice) + 1) % WINDOWS.length;
    }

    /** "22:00–07:00", or null for Off. */
    static String label(int choice) {
        int[] window = WINDOWS[valid(choice)];
        return window == null ? null : String.format(java.util.Locale.ROOT, "%02d:00–%02d:00", window[0], window[1]);
    }

    /** Whether minuteOfDay (0 to 1439, local time) falls inside the window; the start is in, the end is out. */
    static boolean isQuiet(int choice, int minuteOfDay) {
        int[] window = WINDOWS[valid(choice)];
        if (window == null) return false;
        int start = window[0] * 60, end = window[1] * 60;
        return start <= end ? minuteOfDay >= start && minuteOfDay < end
                : minuteOfDay >= start || minuteOfDay < end;
    }
}
