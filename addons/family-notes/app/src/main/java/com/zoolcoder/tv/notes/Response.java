package com.zoolcoder.tv.notes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A short HTTP/1.1 answer. Always closes the connection. */
final class Response {
    final int status;
    final String contentType;
    final String body;

    Response(int status, String contentType, String body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    static Response text(int status, String body) {
        return new Response(status, "text/plain; charset=utf-8", body);
    }

    static Response page(String html) {
        return new Response(200, "text/html; charset=utf-8", html);
    }

    private static final Pattern SCRIPT = Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern STYLE = Pattern.compile("<style>(.*?)</style>", Pattern.DOTALL);

    /** 'sha256-...' sources for every inline block matched, or 'none' when the page has none. */
    static String hashes(String html, Pattern block) {
        StringBuilder sources = new StringBuilder();
        Matcher m = block.matcher(html);
        while (m.find()) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(m.group(1).getBytes(StandardCharsets.UTF_8));
                sources.append(sources.length() > 0 ? " " : "").append("'sha256-")
                        .append(Base64.getEncoder().encodeToString(digest)).append('\'');
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return sources.length() == 0 ? "'none'" : sources.toString();
    }

    /** No 'unsafe-inline': the page's own inline script and style run only because their hashes are listed. */
    static String policy(String html) {
        return "default-src 'none'; style-src " + hashes(html, STYLE) + "; script-src " + hashes(html, SCRIPT)
                + "; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 429: return "Too Many Requests";
            case 431: return "Request Header Fields Too Large";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    byte[] toBytes() {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder()
                .append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(payload.length).append("\r\n")
                .append("Connection: close\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("X-Frame-Options: DENY\r\n")
                .append("Referrer-Policy: no-referrer\r\n");
        if (contentType.startsWith("text/html")) {
            head.append("Content-Security-Policy: ").append(policy(body)).append("\r\n");
        }
        head.append("\r\n");
        byte[] header = head.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] all = new byte[header.length + payload.length];
        System.arraycopy(header, 0, all, 0, header.length);
        System.arraycopy(payload, 0, all, header.length, payload.length);
        return all;
    }
}
