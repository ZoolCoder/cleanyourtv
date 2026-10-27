package com.zoolcoder.tv.calendar;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Decodes an application/x-www-form-urlencoded body. Malformed escapes or invalid UTF-8 give null. */
final class Form {
    private Form() {
    }

    /** The first value wins when a field repeats. The body's chars are bytes (see HttpRequest). */
    static Map<String, String> parse(String body) {
        Map<String, String> fields = new HashMap<>();
        if (body.isEmpty()) return fields;
        for (String pair : body.split("&", -1)) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = decode(eq < 0 ? "" : pair.substring(eq + 1));
            if (name == null || value == null) return null;
            if (!fields.containsKey(name)) fields.put(name, value);
        }
        return fields;
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static String decode(String s) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+') {
                bytes.write(' ');
            } else if (c == '%') {
                if (i + 2 >= s.length()) return null;
                int hi = hex(s.charAt(i + 1));
                int lo = hex(s.charAt(i + 2));
                if (hi < 0 || lo < 0) return null;
                bytes.write(hi * 16 + lo);
                i += 2;
            } else if (c < 256) {
                bytes.write(c);
            } else {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
