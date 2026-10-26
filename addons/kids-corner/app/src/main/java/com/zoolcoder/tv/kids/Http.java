package com.zoolcoder.tv.kids;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Bounded HTTPS reads: timeouts, a byte cap enforced while reading, and no redirect to plain http. */
final class Http {
    private static final int MAX_REDIRECTS = 3;

    private Http() {
    }

    /** The body at an https url, or an IOException on any other scheme, status, redirect to http, or size over maxBytes. */
    static byte[] get(String url, int maxBytes, int timeoutMs) throws IOException {
        String next = url;
        for (int hops = 0; hops <= MAX_REDIRECTS; hops++) {
            URL target = new URL(next);
            if (!"https".equals(target.getProtocol())) throw new IOException("Not https: " + target.getProtocol());
            HttpURLConnection connection = (HttpURLConnection) target.openConnection();
            try {
                connection.setConnectTimeout(timeoutMs);
                connection.setReadTimeout(timeoutMs);
                connection.setInstanceFollowRedirects(false);
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without Location");
                    next = new URL(target, location).toString();
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
                if (connection.getContentLength() > maxBytes) throw new IOException("Too large");
                try (InputStream in = connection.getInputStream()) {
                    return readCapped(in, maxBytes);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    /** All of the stream, or an IOException as soon as it holds more than maxBytes. */
    static byte[] readCapped(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > maxBytes) throw new IOException("Too large");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
