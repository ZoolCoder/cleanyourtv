package com.zoolcoder.tv.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** The request reader and its limits: 8 KB of head, 2 KB of body, Content-Length required. */
public class HttpRequestTest {
    private static HttpRequest read(String raw) throws Exception {
        return HttpRequest.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static int statusOf(String raw) throws Exception {
        try {
            read(raw);
        } catch (HttpException e) {
            return e.status;
        }
        return 200;
    }

    @Test
    public void readsAGet() throws Exception {
        HttpRequest r = read("GET /?x=1 HTTP/1.1\r\nHost: tv\r\nAccept: */*\r\n\r\n");
        assertEquals("GET", r.method);
        assertEquals("/", r.path);
        assertEquals("tv", r.headers.get("host"));
        assertEquals("", r.body);
    }

    @Test
    public void readsAPostBodyOfExactlyContentLength() throws Exception {
        HttpRequest r = read("POST /note HTTP/1.1\r\nContent-Length: 11\r\n\r\nname=a&b=c&EXTRA");
        assertEquals("name=a&b=c", r.body.substring(0, 10));
        assertEquals(11, r.body.length());
    }

    @Test
    public void postWithoutContentLengthIs411() throws Exception {
        assertEquals(411, statusOf("POST /note HTTP/1.1\r\nHost: tv\r\n\r\n"));
    }

    @Test
    public void bodyOver2KbIs413() throws Exception {
        assertEquals(413, statusOf("POST /note HTTP/1.1\r\nContent-Length: 2049\r\n\r\n"));
        assertEquals(200, statusOf("POST /note HTTP/1.1\r\nContent-Length: 2048\r\n\r\n" + repeat('a', 2048)));
    }

    @Test
    public void badContentLengthIs400() throws Exception {
        assertEquals(400, statusOf("POST /note HTTP/1.1\r\nContent-Length: -1\r\n\r\n"));
        assertEquals(400, statusOf("POST /note HTTP/1.1\r\nContent-Length: 1x\r\n\r\n"));
        assertEquals(400, statusOf("POST /note HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\na"));
    }

    @Test
    public void shortBodyIs400() throws Exception {
        assertEquals(400, statusOf("POST /note HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc"));
    }

    @Test
    public void headOver8KbIs431() throws Exception {
        String big = "GET / HTTP/1.1\r\nX: " + repeat('a', HttpRequest.MAX_HEAD_BYTES) + "\r\n\r\n";
        assertEquals(431, statusOf(big));
    }

    @Test
    public void headJustUnder8KbPasses() throws Exception {
        String start = "GET / HTTP/1.1\r\nX: ";
        String end = "\r\n\r\n";
        int fill = HttpRequest.MAX_HEAD_BYTES - start.length() - end.length();
        assertEquals(200, statusOf(start + repeat('a', fill) + end));
        assertEquals(431, statusOf(start + repeat('a', fill + 1) + end));
    }

    @Test
    public void chunkedBodiesAreRefused() throws Exception {
        assertEquals(400, statusOf("POST /note HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"));
    }

    @Test
    public void malformedRequestsAre400() throws Exception {
        assertEquals(400, statusOf("GET /\r\n\r\n"));
        assertEquals(400, statusOf("GET / FTP/1.1\r\n\r\n"));
        assertEquals(400, statusOf("GET / HTTP/1.1\r\nno colon here\r\n\r\n"));
        assertEquals(400, statusOf("GET / HTTP/1.1\r\n"));
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    public void responseAlwaysClosesTheConnection() {
        String wire = new String(Response.text(404, "not found").toBytes(), StandardCharsets.UTF_8);
        assertTrue(wire.startsWith("HTTP/1.1 404 Not Found\r\n"));
        assertTrue(wire.contains("Connection: close\r\n"));
        assertTrue(wire.contains("Content-Length: 9\r\n"));
        if (wire.contains("Content-Security-Policy")) fail("only the page carries a CSP");
    }

    @Test
    public void keepsThePeerAddress() throws Exception {
        HttpRequest r = HttpRequest.read(new ByteArrayInputStream(
                "GET / HTTP/1.1\r\nHost: tv\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)), "192.168.1.66");
        assertEquals("192.168.1.66", r.peer);
        assertEquals("", read("GET / HTTP/1.1\r\nHost: tv\r\n\r\n").peer);
    }
}
