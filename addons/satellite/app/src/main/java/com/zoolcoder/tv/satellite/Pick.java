package com.zoolcoder.tv.satellite;

import java.util.List;
import java.util.Locale;

/**
 * Chooses the input the tile opens, with no Android types so the JVM tests cover it. The TV lists each HDMI
 * port as a passthrough input, and a device that speaks HDMI-CEC as a child input of its port, named after
 * itself.
 */
final class Pick {
    /** One passthrough input, reduced to what the choice needs. */
    static final class Input {
        final String id;
        final String parentId;
        final String label;
        /** The name the owner gave it in the TV's settings; empty when none. */
        final String customLabel;
        final boolean hdmi;
        final boolean connected;

        Input(String id, String parentId, String label, String customLabel, boolean hdmi, boolean connected) {
            this.id = id;
            this.parentId = parentId;
            this.label = label == null ? "" : label;
            this.customLabel = customLabel == null ? "" : customLabel;
            this.hdmi = hdmi;
            this.connected = connected;
        }
    }

    private Pick() {
    }

    /**
     * The input id to open, or null when nothing fits. An empty rule takes the first connected HDMI port in
     * port order, else HDMI 1 (the first HDMI port). A rule names a port number ("2"), a label ("HDMI 2"),
     * the end of an input id ("HW2"), or the name of a device or port, case-insensitively; a device behind a
     * port wins over the port, so opening it also wakes it over CEC.
     */
    static String choose(List<Input> inputs, String rule) {
        String want = rule == null ? "" : rule.trim();
        if (want.isEmpty()) {
            Input best = null;
            for (Input i : inputs) {
                if (!i.hdmi || i.parentId != null) continue;
                if (best == null || (i.connected && !best.connected)
                        || (i.connected == best.connected && port(i.label) < port(best.label))) best = i;
            }
            return best == null ? null : best.id;
        }
        String lower = want.toLowerCase(Locale.ROOT);
        Integer number = null;
        try {
            number = Integer.parseInt(lower.replaceFirst("^(hdmi|hw)\\s*", ""));
        } catch (NumberFormatException ignored) {
        }
        // Devices first: a receiver named over CEC or in Settings > Inputs is the most exact match.
        for (Input i : inputs) {
            if (i.parentId != null && names(i, lower)) return i.id;
        }
        for (Input i : inputs) {
            if (i.parentId != null) continue;
            if (names(i, lower) || i.id.toLowerCase(Locale.ROOT).endsWith("/" + lower)
                    || (number != null && i.hdmi && port(i.label) == number)
                    || (number != null && i.id.matches("(?i).*/HW" + number + "$"))) {
                return i.id;
            }
        }
        return null;
    }

    private static boolean names(Input i, String lower) {
        return i.label.toLowerCase(Locale.ROOT).equals(lower) || i.customLabel.toLowerCase(Locale.ROOT).equals(lower);
    }

    /** The number in "HDMI 2"; a high value when there is none, so named ports sort last. */
    static int port(String label) {
        String digits = label.replaceAll("\\D", "");
        if (digits.isEmpty()) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
