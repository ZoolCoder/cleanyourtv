package com.zoolcoder.tv.notes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SanitizerTest {
    private static String repeat(char c, int n) {
        return new String(new char[n]).replace('\0', c);
    }

    @Test
    public void trimsAndKeepsPlainText() {
        assertEquals("Dinner at 8", Sanitizer.text("  Dinner at 8 \n"));
        assertEquals("Mum", Sanitizer.name("  Mum "));
    }

    @Test
    public void stripsControlCharactersButKeepsNewlinesInText() {
        assertEquals("ab", Sanitizer.text("a\u0000\u0007b"));
        assertEquals("a\nb", Sanitizer.text("a\r\nb"));
        assertEquals("a\nb", Sanitizer.text("a\rb"));
        assertEquals("ab", Sanitizer.text("a\tb"));
        assertEquals("ab", Sanitizer.text("a\u001Bb"));
    }

    @Test
    public void stripsBidiOverridesAndLineSeparators() {
        assertEquals("abc", Sanitizer.text("a‮b⁦c"));
        assertEquals("ab", Sanitizer.name("a b"));
    }

    @Test
    public void stripsInvisibleFormatCharacters() {
        assertEquals("ab", Sanitizer.name("a\u200Bb"));
        assertEquals("ab", Sanitizer.name("a\u200D\u200Cb"));
        assertEquals("ab", Sanitizer.name("\uFEFFa\u061Cb"));
        assertEquals("ab", Sanitizer.text("a\u2060\u00ADb"));
        assertNull(Sanitizer.name("\u200B\u200B\u200B"));
        assertNull(Sanitizer.text("\u200E\u200F\u2066"));
    }

    @Test
    public void everyKindOfSpaceBecomesOnePlainSpace() {
        assertEquals("Mum Dad", Sanitizer.name("Mum\u00A0Dad"));
        assertEquals("Mum Dad", Sanitizer.name("Mum\u3000\u2003Dad"));
        assertEquals("Mum Dad", Sanitizer.name("  Mum      Dad  "));
        assertEquals("a b\nc", Sanitizer.text("a   b\n  c"));
        assertNull(Sanitizer.name("\u00A0\u2007\u202F"));
    }

    @Test
    public void nameIsOneLine() {
        assertEquals("Mum Dad", Sanitizer.name("Mum\nDad"));
    }

    @Test
    public void limitsLengths() {
        assertEquals(280, Sanitizer.text(repeat('x', 280)).length());
        assertNull(Sanitizer.text(repeat('x', 281)));
        assertEquals(24, Sanitizer.name(repeat('x', 24)).length());
        assertNull(Sanitizer.name(repeat('x', 25)));
    }

    @Test
    public void controlCharactersDoNotCountAgainstTheLimit() {
        assertEquals(24, Sanitizer.name(repeat('x', 24) + "\u0000\u0000").length());
    }

    @Test
    public void atMostFourLinesAndBlankLinesDoNotCount() {
        assertEquals("1\n2\n3\n4", Sanitizer.text("1\n\n2\n \n3\n4\n\n"));
        assertNull(Sanitizer.text("1\n2\n3\n4\n5"));
    }

    @Test
    public void emptyAfterCleaningIsRejected() {
        assertNull(Sanitizer.text(null));
        assertNull(Sanitizer.text("   \n \u0000"));
        assertNull(Sanitizer.name(""));
    }

    @Test
    public void markupStaysAsText() {
        assertEquals("<b>hi</b> <script>", Sanitizer.text("<b>hi</b> <script>"));
    }
}
