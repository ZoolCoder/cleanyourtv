package com.zoolcoder.tv.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SanitizerTest {
    private static String repeat(char c, int n) {
        return new String(new char[n]).replace('\0', c);
    }

    @Test
    public void trimsAndKeepsPlainText() {
        assertEquals("Dentist", Sanitizer.title("  Dentist \n"));
        assertEquals("Mum", Sanitizer.name("  Mum "));
        assertEquals("عيد ميلاد سارة", Sanitizer.title("عيد ميلاد سارة"));
    }

    @Test
    public void bothAreOneLine() {
        assertEquals("Mum Dad", Sanitizer.name("Mum\nDad"));
        assertEquals("School trip", Sanitizer.title("School\r\n\r\ntrip"));
        assertEquals("a b", Sanitizer.title("a    b"));
    }

    @Test
    public void stripsControlCharacters() {
        assertEquals("ab", Sanitizer.title("a\u0000\u0007b"));
        assertEquals("ab", Sanitizer.title("a\u001Bb"));
    }

    @Test
    public void stripsBidiOverridesAndLineSeparators() {
        assertEquals("abc", Sanitizer.title("a‮b⁦c"));
        assertEquals("ab", Sanitizer.name("a b"));
        assertEquals("ab", Sanitizer.name("a b"));
    }

    @Test
    public void limitsLengths() {
        assertEquals(60, Sanitizer.title(repeat('x', 60)).length());
        assertNull(Sanitizer.title(repeat('x', 61)));
        assertEquals(24, Sanitizer.name(repeat('x', 24)).length());
        assertNull(Sanitizer.name(repeat('x', 25)));
    }

    @Test
    public void controlCharactersDoNotCountAgainstTheLimit() {
        assertEquals(24, Sanitizer.name(repeat('x', 24) + "\u0000\u0000").length());
    }

    @Test
    public void emptyOrNullIsNull() {
        assertNull(Sanitizer.title(null));
        assertNull(Sanitizer.title("   "));
        assertNull(Sanitizer.name("\u0000‮"));
    }

    @Test
    public void stripsZeroWidthAndOtherFormatCharacters() {
        assertEquals("ab", Sanitizer.title("a\u200Bb"));
        assertEquals("ab", Sanitizer.title("a\u200C\u200D\u200E\u200Fb"));
        assertEquals("ab", Sanitizer.title("\uFEFFa\u2060b\u061C"));
        assertEquals("ab", Sanitizer.name("a\uDB40\uDC41b"));
    }

    @Test
    public void allInvisibleIsEmpty() {
        assertNull(Sanitizer.name("\u200B\u200B\u200B"));
        assertNull(Sanitizer.title("\uFEFF\u061C\u00A0"));
    }

    @Test
    public void oddSpacesBecomePlainOnes() {
        assertEquals("a b", Sanitizer.title("a\u00A0b"));
        assertEquals("a b", Sanitizer.title("a\u3000\u2009b"));
    }
}
