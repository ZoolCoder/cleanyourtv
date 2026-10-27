package com.zoolcoder.tv.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;

public class ResponseTest {
    private static String head(Response r) {
        String all = new String(r.toBytes(), StandardCharsets.UTF_8);
        return all.substring(0, all.indexOf("\r\n\r\n"));
    }

    @Test
    public void everyResponseCarriesTheSecurityHeaders() {
        for (Response r : new Response[] {Response.text(200, "ok"), Response.text(403, "no"), Response.text(503, "busy"),
                Response.page("<html></html>")}) {
            String head = head(r);
            assertTrue(head, head.contains("X-Content-Type-Options: nosniff"));
            assertTrue(head, head.contains("X-Frame-Options: DENY"));
            assertTrue(head, head.contains("Referrer-Policy: no-referrer"));
            assertTrue(head, head.contains("Cache-Control: no-store"));
        }
    }

    @Test
    public void onlyThePageGetsAPolicy() {
        assertFalse(head(Response.text(200, "ok")).contains("Content-Security-Policy"));
        assertTrue(head(Response.page("<html></html>")).contains("Content-Security-Policy: default-src 'none'"));
    }

    @Test
    public void policyListsHashesAndNoUnsafeInline() throws Exception {
        String html = "<style>a{}</style><script>var x=1;</script>";
        String policy = Response.policy(html);
        assertFalse(policy.contains("unsafe-inline"));
        assertTrue(policy.contains("script-src " + sha("var x=1;")));
        assertTrue(policy.contains("style-src " + sha("a{}")));
        assertEquals("'none'", Response.hashes("<p>no code</p>", java.util.regex.Pattern.compile("<script>(.*?)</script>")));
    }

    @Test
    public void shippedPageIsCoveredByItsOwnPolicy() throws IOException {
        String page = new String(Files.readAllBytes(Paths.get("src/main/res/raw/page.html")), StandardCharsets.UTF_8);
        String policy = Response.policy(page);
        assertFalse(policy.contains("'none'; style") && policy.contains("style-src 'none'"));
        assertFalse(policy.contains("script-src 'none'"));
        // Anything the hashes do not cover would be blocked: no inline handlers or style attributes.
        assertFalse(page.matches("(?s).*\\son[a-z]+=.*"));
        assertFalse(page.contains(" style=\""));
        assertEquals(1, page.split("<script", -1).length - 1);
    }

    private static String sha(String text) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return "'sha256-" + Base64.getEncoder().encodeToString(d) + "'";
    }
}
