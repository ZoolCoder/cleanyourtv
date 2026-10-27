package com.zoolcoder.tv.notes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** The routes, in order: page, note, 404; the PIN check before the field rules; nothing echoed back. */
public class NoteEndpointTest {
    private static final String PAGE = "<html>static page</html>";
    private final List<Note> received = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000);
    private final NoteEndpoint endpoint = new NoteEndpoint(PAGE, () -> "123456", new PinGuard(clock::get),
            () -> 42L, received::add);

    private static HttpRequest request(String method, String path, String type, String body) {
        Map<String, String> headers = new HashMap<>();
        if (type != null) headers.put("content-type", type);
        return new HttpRequest(method, path, headers, body);
    }

    private Response post(String body) {
        return endpoint.handle(request("POST", "/note", "application/x-www-form-urlencoded", body));
    }

    @Test
    public void getRootIsTheStaticPage() {
        Response r = endpoint.handle(request("GET", "/", null, ""));
        assertEquals(200, r.status);
        assertEquals(PAGE, r.body);
        assertTrue(new String(r.toBytes()).contains("Content-Security-Policy"));
    }

    @Test
    public void everythingElseIs404() {
        assertEquals(404, endpoint.handle(request("GET", "/note", null, "")).status);
        assertEquals(404, endpoint.handle(request("POST", "/", "application/x-www-form-urlencoded", "")).status);
        assertEquals(404, endpoint.handle(request("GET", "/etc/passwd", null, "")).status);
        assertEquals(404, endpoint.handle(request("GET", "/index.html", null, "")).status);
        assertEquals(404, endpoint.handle(request("PUT", "/note", null, "")).status);
        assertEquals(404, endpoint.handle(request("HEAD", "/", null, "")).status);
    }

    @Test
    public void goodNoteIsAcceptedAndCleaned() {
        Response r = post("name=%20Mum%20&text=Dinner+at+8&pin=123456");
        assertEquals(200, r.status);
        assertEquals(1, received.size());
        assertEquals("Mum", received.get(0).name);
        assertEquals("Dinner at 8", received.get(0).text);
        assertEquals(42L, received.get(0).time);
    }

    @Test
    public void wrongPinIs403AndNothingIsShown() {
        assertEquals(403, post("name=a&text=b&pin=000000").status);
        assertEquals(403, post("name=a&text=b").status);
        assertTrue(received.isEmpty());
    }

    @Test
    public void fiveWrongPinsAnswer429EvenForTheRightPin() {
        for (int i = 0; i < 5; i++) assertEquals(403, post("name=a&text=b&pin=000000").status);
        assertEquals(429, post("name=a&text=b&pin=123456").status);
        assertTrue(received.isEmpty());
        clock.addAndGet(PinGuard.LOCKOUT_MS);
        assertEquals(200, post("name=a&text=b&pin=123456").status);
    }

    @Test
    public void wrongPinsAreCountedPerPhone() {
        for (int i = 0; i < 5; i++) assertEquals(403, endpoint.handle(from("10.0.0.9", "name=a&text=b&pin=000000")).status);
        assertEquals(429, endpoint.handle(from("10.0.0.9", "name=a&text=b&pin=123456")).status);
        assertEquals(200, endpoint.handle(from("10.0.0.2", "name=a&text=b&pin=123456")).status);
    }

    private HttpRequest from(String peer, String body) {
        HttpRequest base = request("POST", "/note", "application/x-www-form-urlencoded", body);
        return new HttpRequest(base.method, base.path, base.headers, base.body, peer);
    }

    @Test
    public void badFieldsAre400AfterThePinPasses() {
        assertEquals(400, post("name=&text=b&pin=123456").status);
        assertEquals(400, post("name=a&text=&pin=123456").status);
        assertEquals(400, post("name=a&text=" + new String(new char[281]).replace('\0', 'x') + "&pin=123456").status);
        assertEquals(400, post("%zz").status);
        assertTrue(received.isEmpty());
    }

    @Test
    public void onlyFormDataIsAccepted() {
        assertEquals(415, endpoint.handle(request("POST", "/note", "application/json", "{}")).status);
        assertEquals(415, endpoint.handle(request("POST", "/note", null, "name=a")).status);
    }

    @Test
    public void answersNeverEchoUserInput() {
        Response r = post("name=%3Cscript%3E&text=%3Cb%3Eboom&pin=000000");
        assertFalse(r.body.contains("script"));
        Response ok = post("name=%3Cscript%3E&text=%3Cb%3Eboom&pin=123456");
        assertEquals("ok", ok.body);
    }

    private Response postFrom(Map<String, String> extra) {
        HttpRequest base = request("POST", "/note", "application/x-www-form-urlencoded", "name=a&text=b&pin=000000");
        base.headers.putAll(extra);
        return endpoint.handle(base);
    }

    private static Map<String, String> headers(String... pairs) {
        Map<String, String> h = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) h.put(pairs[i], pairs[i + 1]);
        return h;
    }

    @Test
    public void sameOriginMatchesHostAndPort() {
        assertTrue(NoteEndpoint.sameOrigin(headers("host", "192.168.1.5:8765", "origin", "http://192.168.1.5:8765")));
        assertTrue(NoteEndpoint.sameOrigin(headers("host", "tv.local", "origin", "http://tv.local")));
        assertTrue(NoteEndpoint.sameOrigin(headers("host", "TV.local:80", "origin", "http://tv.LOCAL:80")));
        assertTrue(NoteEndpoint.sameOrigin(headers("host", "a:1", "origin", "http://a:1", "sec-fetch-site", "same-origin")));
    }

    @Test
    public void originThatDiffersIsRefused() {
        assertFalse(NoteEndpoint.sameOrigin(headers("host", "192.168.1.5:8765", "origin", "http://evil.example")));
        assertFalse(NoteEndpoint.sameOrigin(headers("host", "192.168.1.5:8765", "origin", "http://192.168.1.5")));
        assertFalse(NoteEndpoint.sameOrigin(headers("host", "192.168.1.5:8765", "origin", "http://192.168.1.5:8766")));
        assertFalse(NoteEndpoint.sameOrigin(headers("host", "192.168.1.5:8765", "origin", "https://192.168.1.5:8765")));
        assertFalse(NoteEndpoint.sameOrigin(headers("host", "a:1", "origin", "null")));
        assertFalse(NoteEndpoint.sameOrigin(headers("origin", "http://a:1")));
        assertFalse(NoteEndpoint.sameOrigin(headers("host", "a:1", "origin", "")));
    }

    @Test
    public void crossSiteFetchMetadataIsRefused() {
        assertFalse(NoteEndpoint.sameOrigin(headers("sec-fetch-site", "cross-site")));
        assertFalse(NoteEndpoint.sameOrigin(headers("sec-fetch-site", "Cross-Site")));
        assertTrue(NoteEndpoint.sameOrigin(headers("sec-fetch-site", "same-origin")));
        assertTrue(NoteEndpoint.sameOrigin(headers("sec-fetch-site", "none")));
    }

    @Test
    public void onlyAnIpHostIsServed() {
        assertTrue(NoteEndpoint.ipHost("192.168.1.50:8765"));
        assertTrue(NoteEndpoint.ipHost("192.168.1.50"));
        assertTrue(NoteEndpoint.ipHost("[fe80::1]:8765"));
        assertTrue(NoteEndpoint.ipHost(null));
        assertFalse(NoteEndpoint.ipHost("evil.example:8765"));
        assertFalse(NoteEndpoint.ipHost("192.168.1.50.evil.example"));
        assertFalse(NoteEndpoint.ipHost(""));
    }

    @Test
    public void aRebindingNameIsRefusedBeforeAnything() {
        Map<String, String> h = headers("host", "evil.example:8765", "origin", "http://evil.example:8765",
                "content-type", "application/x-www-form-urlencoded");
        Response response = endpoint.handle(new HttpRequest("POST", "/note", h, "pin=000000&name=a&text=b"));
        assertEquals(403, response.status);
        assertTrue(received.isEmpty());
        assertEquals(403, endpoint.handle(new HttpRequest("GET", "/", headers("host", "evil.example"), "")).status);
    }

    @Test
    public void noOriginHeadersPass() {
        assertTrue(NoteEndpoint.sameOrigin(headers()));
        assertTrue(NoteEndpoint.sameOrigin(headers("host", "a:1")));
    }

    @Test
    public void crossSitePostIsRefusedBeforeThePinIsCounted() {
        for (int i = 0; i < 10; i++) {
            assertEquals(403, postFrom(headers("host", "a:1", "origin", "http://evil.example")).status);
            assertEquals(403, postFrom(headers("sec-fetch-site", "cross-site")).status);
        }
        // No wrong PIN was counted, so the right one still works.
        assertEquals(200, post("name=a&text=b&pin=123456").status);
    }
}
