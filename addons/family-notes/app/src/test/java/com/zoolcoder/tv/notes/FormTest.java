package com.zoolcoder.tv.notes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class FormTest {
    @Test
    public void decodesFieldsPlusAndPercent() {
        Map<String, String> f = Form.parse("name=Mum&text=Dinner+at+8%21&pin=123456");
        assertEquals("Mum", f.get("name"));
        assertEquals("Dinner at 8!", f.get("text"));
        assertEquals("123456", f.get("pin"));
    }

    @Test
    public void decodesUtf8Arabic() {
        Map<String, String> f = Form.parse("text=%D8%B9%D8%B4%D8%A7%D8%A1");
        assertEquals("عشاء", f.get("text"));
    }

    @Test
    public void firstValueWinsWhenAFieldRepeats() {
        assertEquals("a", Form.parse("pin=a&pin=b").get("pin"));
    }

    @Test
    public void badEscapesAndBadUtf8GiveNull() {
        assertNull(Form.parse("a=%"));
        assertNull(Form.parse("a=%4"));
        assertNull(Form.parse("a=%zz"));
        assertNull(Form.parse("a=%FF%FE"));
        assertNull(Form.parse("a=%１１"));
    }

    @Test
    public void emptyBodyIsNoFields() {
        assertTrue(Form.parse("").isEmpty());
    }

    @Test
    public void fieldWithoutEqualsIsEmptyValue() {
        assertEquals("", Form.parse("flag").get("flag"));
    }
}
