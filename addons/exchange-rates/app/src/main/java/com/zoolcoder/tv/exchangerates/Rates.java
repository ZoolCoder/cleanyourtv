package com.zoolcoder.tv.exchangerates;

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    /** Whether a fresh answer is a newer update than the saved one, so the saved one becomes "the previous update". */
    static boolean isNewer(Snapshot saved, Snapshot fresh) {
        return saved != null && fresh != null && fresh.updatedMs > saved.updatedMs;
    }

    /** SDG per one unit now minus the same a snapshot earlier, or NaN when either rate is unknown. */
    static double change(Snapshot now, Snapshot before, String code) {
        if (now == null || before == null) return Double.NaN;
        double a = now.sdgPer(code), b = before.sdgPer(code);
        return Double.isNaN(a) || Double.isNaN(b) ? Double.NaN : a - b;
    }

    /** The pinned currencies first, then the rest, each group in config.xml order. */
    static List<Currency> ordered(List<Currency> all, Set<String> pinned) {
        List<Currency> result = new ArrayList<>(all.size());
        for (Currency currency : all) if (pinned.contains(currency.code)) result.add(currency);
        for (Currency currency : all) if (!pinned.contains(currency.code)) result.add(currency);
        return result;
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

        /**
         * "▲ 1.2" or "▼ 0.04" for a change of the rate {@code now}, at the rate's own precision; empty when
         * there is nothing to say (unknown, or too small to show at that precision).
         */
        String change(double diff, double now) {
            if (Double.isNaN(diff) || Double.isInfinite(diff) || Double.isNaN(now) || now <= 0) return "";
            NumberFormat format = now >= 100 ? big : small;
            String shown = format.format(Math.abs(diff));
            if (format.format(0).equals(shown)) return "";
            return (diff > 0 ? "\u25B2 " : "\u25BC ") + shown;
        }

        /** How much of the currency one SDG buys, given SDG per unit: two significant digits, "0.0022". */
        String inverse(double sdgPer) {
            if (Double.isNaN(sdgPer) || Double.isInfinite(sdgPer) || sdgPer <= 0) return "—";
            double value = 1 / sdgPer;
            if (value >= 1) return format(value);
            BigDecimal rounded = new BigDecimal(value).round(new MathContext(2));
            NumberFormat format = (NumberFormat) small.clone();
            format.setMinimumFractionDigits(Math.max(2, rounded.scale()));
            format.setMaximumFractionDigits(Math.max(2, rounded.scale()));
            return format.format(rounded);
        }
    }
}
