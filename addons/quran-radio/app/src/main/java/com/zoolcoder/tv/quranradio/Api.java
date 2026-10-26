package com.zoolcoder.tv.quranradio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** One HTTPS GET of a small JSON answer from mp3quran.net. */
final class Api {
    private static final int TIMEOUT_MS = 10000;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final long DEADLINE_MS = 30000;

    private Api() {}

    static String get(String url) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("not https");
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            if (connection.getContentLengthLong() > MAX_BYTES) throw new IOException("answer too large");
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_BYTES) throw new IOException("answer too large");
                    if (System.currentTimeMillis() > deadline) throw new IOException("answer too slow");
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }
}
