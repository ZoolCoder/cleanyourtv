package com.gathin.tvhome;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.Assert.assertEquals;

/** config.xml's latitude and longitude reach the pills and the weather request only as finite, in-range decimals. */
public class CoordinateTest {

    @Test
    public void aDecimalInRangeIsKept() {
        assertEquals("15.5007", Config.coordinate(" 15.5007 ", 90));
        assertEquals("-32.5599", Config.coordinate("-32.5599", 180));
        assertEquals("90", Config.coordinate("90", 90));
    }

    @Test
    public void anythingElseTurnsThePillsOff() {
        assertEquals("", Config.coordinate("", 90));
        assertEquals("", Config.coordinate("north", 90));
        assertEquals("", Config.coordinate("15,5", 90));
        assertEquals("", Config.coordinate("91", 90));
        assertEquals("", Config.coordinate("-180.5", 180));
        assertEquals("", Config.coordinate("NaN", 90));
        assertEquals("", Config.coordinate("Infinity", 180));
        assertEquals("", Config.coordinate("1&current=x", 90));
    }

    @Test
    public void exponentsComeOutAsPlainDigits() {
        assertEquals("0.00001", Config.coordinate("1e-5", 90));
    }

    @Test
    public void aWeatherAnswerUnderTheCapIsRead() throws IOException {
        assertEquals("{\"current\":{}}", Weather.readCapped(new StringReader("{\"current\":{}}"), 64));
        assertEquals(64, Weather.readCapped(new StringReader("x".repeat(64)), 64).length());
    }

    @Test(expected = IOException.class)
    public void aWeatherAnswerOverTheCapIsRefused() throws IOException {
        Weather.readCapped(new StringReader("x".repeat(65)), 64);
    }
}
