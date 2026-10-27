package com.zoolcoder.tv.notes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * Talks to the Family away relay (relay/ in this repo): creates a box, reads and acknowledges its sealed
 * messages, rotates the send token and deletes the box. HTTPS only, short timeouts, small capped answers,
 * strict parsing. Only ciphertext ever passes through here; RelayCrypto opens it. Shared with Family Calendar:
 * keep this file free of anything notes-specific.
 */
final class RelayClient {
    static final int TIMEOUT_MS = 10_000;
    static final int MAX_RESPONSE = 64 * 1024;
    static final Pattern BOX_ID = Pattern.compile("[A-Za-z0-9_-]{22}");
    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    static final Pattern WIRE = Pattern.compile("[A-Za-z0-9_-]{38,4000}");

    /** One HTTP exchange. The default sends it over HTTPS; tests put a fake relay here. */
    interface Transport {
        Response send(String method, String url, String bearer, String jsonBody) throws IOException;
    }

    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    static final class RelayException extends Exception {
        enum Kind { UNAUTHORIZED, NOT_FOUND, CONFLICT, RATE_LIMITED, BAD_RESPONSE, SERVER, NETWORK }

        final Kind kind;

        RelayException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        /** The box is deleted, expired or its read token no longer works: polling it again cannot help. */
        boolean gone() {
            return kind == Kind.UNAUTHORIZED || kind == Kind.NOT_FOUND;
        }
    }

    static final class Message {
        final long seq;
        final String sealed;
        final long at;

        Message(long seq, String sealed, long at) {
            this.seq = seq;
            this.sealed = sealed;
            this.at = at;
        }
    }

    static final class Batch {
        final List<Message> messages;
        final long next;

        Batch(List<Message> messages, long next) {
            this.messages = messages;
            this.next = next;
        }
    }

    private final String base;
    private final Transport transport;

    RelayClient(String base) {
        this(base, RelayClient::https);
    }

    RelayClient(String base, Transport transport) {
        if (!usable(base)) throw new IllegalArgumentException("relay must be https");
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.transport = transport;
    }

    /** A relay address the app will talk to: https, a host, nothing else odd. */
    static boolean usable(String base) {
        if (base == null || !base.startsWith("https://")) return false;
        try {
            URL url = new URL(base);
            return !url.getHost().isEmpty() && url.getQuery() == null && url.getRef() == null && url.getUserInfo() == null;
        } catch (IOException e) {
            return false;
        }
    }

    void createBox(String boxId, String readHash, String sendHash) throws RelayException {
        requireBox(boxId);
        if (!HASH.matcher(readHash).matches() || !HASH.matcher(sendHash).matches()) throw new IllegalArgumentException("hash");
        JSONObject body = new JSONObject();
        try {
            body.put("boxId", boxId).put("readHash", readHash).put("sendHash", sendHash);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        call("POST", "/v1/box", null, body.toString());
    }

    void rotateSend(String boxId, String readToken, String sendHash) throws RelayException {
        if (!HASH.matcher(sendHash).matches()) throw new IllegalArgumentException("hash");
        JSONObject body = new JSONObject();
        try {
            body.put("sendHash", sendHash);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        call("PUT", boxPath(boxId) + "/send", token(readToken), body.toString());
    }

    Batch fetch(String boxId, String readToken, long after) throws RelayException {
        String body = call("GET", boxPath(boxId) + "/msg?after=" + checkSeq(after), token(readToken), null);
        try {
            JSONObject root = new JSONObject(body);
            JSONArray list = root.getJSONArray("msgs");
            long next = whole(root, "next");
            List<Message> messages = new ArrayList<>(list.length());
            long last = after;
            for (int i = 0; i < list.length(); i++) {
                JSONObject m = list.getJSONObject(i);
                long seq = whole(m, "seq");
                String sealed = m.getString("c");
                if (seq <= last || !WIRE.matcher(sealed).matches()) throw new RelayException(RelayException.Kind.BAD_RESPONSE, "message");
                messages.add(new Message(seq, sealed, whole(m, "at")));
                last = seq;
            }
            if (next < last) throw new RelayException(RelayException.Kind.BAD_RESPONSE, "next");
            return new Batch(Collections.unmodifiableList(messages), next);
        } catch (JSONException e) {
            throw new RelayException(RelayException.Kind.BAD_RESPONSE, "json");
        }
    }

    void ack(String boxId, String readToken, long upto) throws RelayException {
        call("DELETE", boxPath(boxId) + "/msg?upto=" + checkSeq(upto), token(readToken), null);
    }

    void deleteBox(String boxId, String readToken) throws RelayException {
        call("DELETE", boxPath(boxId), token(readToken), null);
    }

    private String call(String method, String path, String bearer, String body) throws RelayException {
        Response response;
        try {
            response = transport.send(method, base + path, bearer, body);
        } catch (IOException e) {
            throw new RelayException(RelayException.Kind.NETWORK, e.getClass().getSimpleName());
        }
        int status = response.status;
        if (status >= 200 && status < 300) return response.body == null ? "" : response.body;
        if (status == 401) throw new RelayException(RelayException.Kind.UNAUTHORIZED, "401");
        if (status == 404) throw new RelayException(RelayException.Kind.NOT_FOUND, "404");
        if (status == 409) throw new RelayException(RelayException.Kind.CONFLICT, "409");
        if (status == 429) throw new RelayException(RelayException.Kind.RATE_LIMITED, "429");
        throw new RelayException(status >= 500 ? RelayException.Kind.SERVER : RelayException.Kind.BAD_RESPONSE,
                String.valueOf(status));
    }

    private static String boxPath(String boxId) {
        requireBox(boxId);
        return "/v1/box/" + boxId;
    }

    private static void requireBox(String boxId) {
        if (boxId == null || !BOX_ID.matcher(boxId).matches()) throw new IllegalArgumentException("box id");
    }

    private static String token(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) throw new IllegalArgumentException("token");
        return token;
    }

    /** The relay takes 1 to 15 digits. */
    private static long checkSeq(long seq) {
        if (seq < 0 || seq > 999_999_999_999_999L) throw new IllegalArgumentException("seq");
        return seq;
    }

    /** A non-negative whole number, refusing strings, fractions and anything past 2^53. */
    private static long whole(JSONObject o, String key) throws JSONException, RelayException {
        Object value = o.get(key);
        if (!(value instanceof Integer || value instanceof Long)) throw new RelayException(RelayException.Kind.BAD_RESPONSE, key);
        long n = ((Number) value).longValue();
        if (n < 0 || n > (1L << 53)) throw new RelayException(RelayException.Kind.BAD_RESPONSE, key);
        return n;
    }

    private static Response https(String method, String address, String bearer, String jsonBody) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        if (!(c instanceof HttpsURLConnection)) throw new IOException("not https");
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (jsonBody != null) {
                byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(bytes.length);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(status, in == null ? "" : readCapped(in));
        } finally {
            c.disconnect();
        }
    }

    static String readCapped(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = stream.read(buffer)) != -1; ) {
                if (out.size() + n > MAX_RESPONSE) throw new IOException("answer too large");
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
