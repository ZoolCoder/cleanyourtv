package com.zoolcoder.tv.notes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * One HTTP/1.1 request read from a stream with hard limits: the request line and headers together at most
 * 8 KB, a body at most 2 KB. A POST needs a Content-Length; chunked bodies are refused. Only what the server
 * needs is kept: method, path (without the query), lower-cased headers and the body as text.
 */
final class HttpRequest {
    static final int MAX_HEAD_BYTES = 8 * 1024;
    static final int MAX_BODY_BYTES = 2 * 1024;

    final String method;
    final String path;
    final Map<String, String> headers;
    final String body;

    HttpRequest(String method, String path, Map<String, String> headers, String body) {
        this.method = method;
        this.path = path;
        this.headers = headers;
        this.body = body;
    }

    static HttpRequest read(InputStream in) throws IOException, HttpException {
        String head = readHead(in);
        String[] lines = head.split("\r\n", -1);
        String[] start = lines[0].split(" ", -1);
        if (start.length != 3 || start[0].isEmpty() || start[1].isEmpty() || !start[2].startsWith("HTTP/1.")) {
            throw new HttpException(400, "bad request line");
        }
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) throw new HttpException(400, "bad header");
            String name = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
            if (name.isEmpty() || headers.put(name, line.substring(colon + 1).trim()) != null) {
                throw new HttpException(400, "bad header");
            }
        }
        if (headers.containsKey("transfer-encoding")) throw new HttpException(400, "chunked bodies are not accepted");
        String path = start[1];
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        String body = "POST".equals(start[0]) ? readBody(in, headers.get("content-length")) : "";
        return new HttpRequest(start[0], path, headers, body);
    }

    /** Reads up to the blank line that ends the headers, one byte at a time so the body is left unread. */
    private static String readHead(InputStream in) throws IOException, HttpException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) throw new HttpException(400, "request ends early");
            if (head.size() >= MAX_HEAD_BYTES) throw new HttpException(431, "headers too large");
            head.write(b);
            boolean next = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3));
            matched = next ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        byte[] bytes = head.toByteArray();
        return new String(bytes, 0, bytes.length - 4, StandardCharsets.ISO_8859_1);
    }

    private static String readBody(InputStream in, String contentLength) throws IOException, HttpException {
        if (contentLength == null) throw new HttpException(411, "Content-Length required");
        if (!contentLength.matches("[0-9]{1,9}")) throw new HttpException(400, "bad Content-Length");
        int length = Integer.parseInt(contentLength);
        if (length > MAX_BODY_BYTES) throw new HttpException(413, "body too large");
        byte[] body = new byte[length];
        int done = 0;
        while (done < length) {
            int n = in.read(body, done, length - done);
            if (n < 0) throw new HttpException(400, "body ends early");
            done += n;
        }
        return new String(body, StandardCharsets.ISO_8859_1);
    }
}
