package com.zoolcoder.tv.exchangerates;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure part of the add-on: one currency line from config.xml, the ExchangeRate-API response, the
 * conversion to "1 X = N SDG" and the number format. No Android classes, so the JVM tests cover it.
 */
final class Rates {
    /** One tile: ISO code and the name in both languages. */
    static final class Currency {
        final String code, arabic, english;

        Currency(String code, String arabic, String english) {
            this.code = code;
            this.arabic = arabic;
            this.english = english;
        }
    }

    /** The rates of one response: units of each currency per US dollar, and when the source updated them. */
    static final class Snapshot {
        final long updatedMs;
        private final Map<String, Double> perUsd;

        Snapshot(long updatedMs, Map<String, Double> perUsd) {
            this.updatedMs = updatedMs;
            this.perUsd = perUsd;
        }

        /** Sudanese pounds for one unit of the currency, or NaN when the response has no such rate. */
        double sdgPer(String code) {
            Double sdg = perUsd.get("SDG");
            Double other = perUsd.get(code);
            if (sdg == null || other == null) return Double.NaN;
            double value = sdg / other;
            return value > 0 && value < MAX_SHOWN ? value : Double.NaN;
        }
    }

    /** Above this a rate is nonsense (a near-zero divisor), and shows a dash. */
    private static final double MAX_SHOWN = 1e12;
    private static final long CLOCK_SKEW_MS = 24L * 3600 * 1000;

    private static final Pattern UPDATED = Pattern.compile("\"time_last_update_unix\"\\s*:\\s*(\\d{1,12})\\b");
    private static final Pattern SUCCESS = Pattern.compile("\"result\"\\s*:\\s*\"success\"");
    private static final Pattern RATE = Pattern.compile(
            "\"([A-Z]{3})\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)");

    private Rates() {
    }

    /** A config.xml line, "CODE | Arabic name | English name", or null when it is not one. */
    static Currency parseCurrency(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length < 3) return null;
        String code = parts[0].trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{3}") || code.equals("SDG")) return null;
        String arabic = parts[1].trim(), english = parts[2].trim();
        if (arabic.isEmpty() && english.isEmpty()) return null;
        return new Currency(code, arabic, english);
    }

    /** The response of open.er-api.com/v6/latest/USD, or null when it is not a good one with a Sudanese pound rate. */
    static Snapshot parse(String json) {
        if (json == null || !SUCCESS.matcher(json).find()) return null;
        Matcher updated = UPDATED.matcher(json);
        if (!updated.find()) return null;
        int start = json.indexOf("\"rates\"");
        int open = start < 0 ? -1 : json.indexOf('{', start);
        int close = open < 0 ? -1 : json.indexOf('}', open);
        if (close < 0) return null;
        Map<String, Double> perUsd = new HashMap<>();
        Matcher rate = RATE.matcher(json.substring(open, close));
        while (rate.find()) {
            double value = Double.parseDouble(rate.group(2));
            // 0, NaN and Infinity (a "1e999") are not rates; the currency then shows a dash.
            if (value > 0 && !Double.isInfinite(value)) perUsd.put(rate.group(1), value);
        }
        if (!perUsd.containsKey("SDG")) return null;
        return new Snapshot(Long.parseLong(updated.group(1)) * 1000L, perUsd);
    }

    /** Whole days since the source updated, or -1 when that is not known (a time in the future: the TV clock is off). */
    static int ageDays(long updatedMs, long nowMs) {
        if (updatedMs > nowMs + CLOCK_SKEW_MS) return -1;
        return (int) Math.max(0, (nowMs - updatedMs) / CLOCK_SKEW_MS);
    }

    /** The number format of the tiles, built once: "458.6" or "1,480.3" for a big rate, "12.41" for a small one. */
    static final class Formatter {
        private final NumberFormat big, small;

        Formatter(Locale locale) {
            big = fixed(locale, 1);
            small = fixed(locale, 2);
        }

        private static NumberFormat fixed(Locale locale, int decimals) {
            NumberFormat format = NumberFormat.getNumberInstance(locale);
            format.setMinimumFractionDigits(decimals);
            format.setMaximumFractionDigits(decimals);
            return format;
        }

        /** A dash when there is no usable rate. */
        String format(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0) return "—";
            return (value >= 100 ? big : small).format(value);
        }
    }
}
