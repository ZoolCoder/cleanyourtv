package com.zoolcoder.tv.exchangerates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Reading the response of open.er-api.com the way it is written, and the numbers the tiles show. */
public class RatesTest {
    private static final String BODY = "{\"result\":\"success\",\"provider\":\"https://www.exchangerate-api.com\","
            + "\"time_last_update_unix\":1792972951,\"time_last_update_utc\":\"Mon, 26 Oct 2026 00:02:31 +0000\","
            + "\"base_code\":\"USD\",\"rates\":{\"USD\":1,\"AED\":3.6725,\"EGP\":55.676694,\"EUR\":0.89213,"
            + "\"KWD\":0.309841,\"SAR\":3.75,\"SDG\":458.564782,\"ZZZ\":1.5e-3}}";

    @Test
    public void readsTimeAndRates() {
        Rates.Snapshot snapshot = Rates.parse(BODY);
        assertNotNull(snapshot);
        assertEquals(1792972951000L, snapshot.updatedMs);
        assertEquals(458.564782, snapshot.sdgPer("USD"), 1e-9);
        assertEquals(458.564782 / 3.75, snapshot.sdgPer("SAR"), 1e-9);
        assertEquals(458.564782 / 0.89213, snapshot.sdgPer("EUR"), 1e-9);
        assertEquals(458.564782 / 1.5e-3, snapshot.sdgPer("ZZZ"), 1e-6);
    }

    @Test
    public void missingCurrencyIsNotANumber() {
        assertTrue(Double.isNaN(Rates.parse(BODY).sdgPer("GBP")));
    }

    @Test
    public void refusesAnErrorAnswerOrOneWithoutSdg() {
        assertNull(Rates.parse("{\"result\":\"error\",\"error-type\":\"quota-reached\"}"));
        assertNull(Rates.parse(BODY.replace("\"SDG\":458.564782,", "")));
        assertNull(Rates.parse(BODY.replace("\"time_last_update_unix\":1792972951,", "")));
        assertNull(Rates.parse("<html>captive portal</html>"));
        assertNull(Rates.parse(""));
        assertNull(Rates.parse(null));
    }

    @Test
    public void sdgRateOfZeroIsRefused() {
        assertNull(Rates.parse(BODY.replace("458.564782", "0")));
    }

    @Test
    public void formatsBigAndSmallRates() {
        Rates.Formatter formatter = new Rates.Formatter(java.util.Locale.US);
        assertEquals("458.6", formatter.format(458.564782));
        assertEquals("1,480.3", formatter.format(1480.29));
        assertEquals("12.41", formatter.format(12.4107));
        assertEquals("—", formatter.format(Double.NaN));
        assertEquals("—", formatter.format(Double.POSITIVE_INFINITY));
        assertEquals("—", formatter.format(-3));
        assertEquals("—", formatter.format(0));
    }

    @Test
    public void formatterFollowsTheLocale() {
        assertEquals("458,6", new Rates.Formatter(java.util.Locale.GERMANY).format(458.564782));
        assertNotNull(new Rates.Formatter(new java.util.Locale("ar", "EG")).format(458.5));
    }

    @Test
    public void badRatesAreDroppedNotShown() {
        assertTrue(Double.isNaN(Rates.parse(BODY.replace("\"EUR\":0.89213", "\"EUR\":0")).sdgPer("EUR")));
        assertTrue(Double.isNaN(Rates.parse(BODY.replace("\"EUR\":0.89213", "\"EUR\":1e999")).sdgPer("EUR")));
        assertTrue(Double.isNaN(Rates.parse(BODY.replace("\"EUR\":0.89213", "\"EUR\":-2")).sdgPer("EUR")));
        assertTrue(Double.isNaN(Rates.parse(BODY.replace("\"EUR\":0.89213", "\"EUR\":1e-300")).sdgPer("EUR")));
        assertNull(Rates.parse(BODY.replace("458.564782", "1e999")));
        assertNull(Rates.parse(BODY.replace("458.564782", "-5")));
    }

    @Test
    public void corruptOrHostileBodiesReturnNull() {
        assertNull(Rates.parse(BODY.replace("1792972951", "99999999999999999999999")));
        assertNull(Rates.parse(BODY.substring(0, 120)));
        assertNull(Rates.parse("{\"result\":\"success\"}"));
        assertNull(Rates.parse("\u0000\u0001{{{{\"rates\""));
        assertNull(Rates.parse(BODY.replace("\"result\":\"success\"", "\"result\":\"error\"")));
    }

    @Test
    public void ageIsHonest() {
        long day = 24L * 3600 * 1000, now = 1_800_000_000_000L;
        assertEquals(0, Rates.ageDays(now - 3600_000, now));
        assertEquals(3, Rates.ageDays(now - 3 * day - 1000, now));
        assertEquals(-1, Rates.ageDays(now + 3 * day, now));
        assertEquals(0, Rates.ageDays(now + 3600_000, now));
    }

    @Test
    public void readsACurrencyLine() {
        Rates.Currency currency = Rates.parseCurrency(" sar | الريال السعودي | Saudi riyal ");
        assertEquals("SAR", currency.code);
        assertEquals("الريال السعودي", currency.arabic);
        assertEquals("Saudi riyal", currency.english);
    }

    @Test
    public void skipsBadCurrencyLines() {
        assertNull(Rates.parseCurrency("USD | الدولار"));
        assertNull(Rates.parseCurrency("US | x | y"));
        assertNull(Rates.parseCurrency("SDG | الجنيه السوداني | Sudanese pound"));
        assertNull(Rates.parseCurrency("USD | |"));
    }

    private static Rates.Snapshot snapshot(long updatedMs, double sdg, double eur) {
        java.util.Map<String, Double> perUsd = new java.util.HashMap<>();
        perUsd.put("USD", 1.0);
        perUsd.put("SDG", sdg);
        if (eur > 0) perUsd.put("EUR", eur);
        return new Rates.Snapshot(updatedMs, perUsd);
    }

    @Test
    public void changeAgainstThePreviousUpdate() {
        Rates.Snapshot before = snapshot(1000, 450, 0.9), now = snapshot(2000, 458.6, 0.9);
        assertEquals(8.6, Rates.change(now, before, "USD"), 1e-9);
        assertTrue(Double.isNaN(Rates.change(now, null, "USD")));
        assertTrue(Double.isNaN(Rates.change(now, snapshot(1000, 450, 0), "EUR")));
        assertTrue(Rates.isNewer(before, now));
        assertTrue(!Rates.isNewer(now, now));
        assertTrue(!Rates.isNewer(null, now));
    }

    @Test
    public void changeIsShownAtTheRatePrecisionAndHiddenWhenNothingMoved() {
        Rates.Formatter formatter = new Rates.Formatter(java.util.Locale.US);
        assertEquals("\u25B2 8.6", formatter.change(8.6, 458.6));
        assertEquals("\u25BC 0.04", formatter.change(-0.04, 12.41));
        assertEquals("", formatter.change(0.02, 458.6));
        assertEquals("", formatter.change(0, 458.6));
        assertEquals("", formatter.change(Double.NaN, 458.6));
        assertEquals("", formatter.change(1, Double.NaN));
    }

    @Test
    public void inverseRateHasTwoSignificantDigits() {
        Rates.Formatter formatter = new Rates.Formatter(java.util.Locale.US);
        assertEquals("0.0022", formatter.inverse(458.564782));
        assertEquals("0.00068", formatter.inverse(1480.3));
        assertEquals("0.081", formatter.inverse(12.41));
        assertEquals("0.50", formatter.inverse(2));
        assertEquals("2.00", formatter.inverse(0.5));
        assertEquals("—", formatter.inverse(Double.NaN));
        assertEquals("—", formatter.inverse(0));
    }

    @Test
    public void pinnedCurrenciesComeFirstInConfigOrder() {
        java.util.List<Rates.Currency> all = java.util.Arrays.asList(
                new Rates.Currency("USD", "", ""), new Rates.Currency("SAR", "", ""),
                new Rates.Currency("AED", "", ""), new Rates.Currency("EGP", "", ""));
        java.util.List<Rates.Currency> ordered = Rates.ordered(all,
                new java.util.HashSet<>(java.util.Arrays.asList("EGP", "SAR", "GBP")));
        StringBuilder codes = new StringBuilder();
        for (Rates.Currency currency : ordered) codes.append(currency.code).append(' ');
        assertEquals("SAR EGP USD AED ", codes.toString());
        assertEquals(all, Rates.ordered(all, java.util.Collections.emptySet()));
    }
}
