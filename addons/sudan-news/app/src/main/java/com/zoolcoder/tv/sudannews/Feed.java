package com.zoolcoder.tv.sudannews;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** One RSS feed from config.xml, and the stories read from it: headline, summary, source and time only. */
final class Feed {
    static final class Story {
        final String language, source, title, summary;
        final long time;

        Story(String language, String source, String title, String summary, long time) {
            this.language = language;
            this.source = source;
            this.title = title;
            this.summary = summary;
            this.time = time;
        }
    }

    /** The stories as JSON, for the copy kept on the TV that is shown when the feeds cannot be reached. */
    static String save(List<Story> stories) {
        JSONArray out = new JSONArray();
        try {
            for (Story s : stories) {
                out.put(new JSONObject().put("l", s.language).put("s", s.source).put("t", s.title)
                        .put("b", s.summary).put("w", s.time));
            }
        } catch (JSONException e) {
            return "[]";
        }
        return out.toString();
    }

    /** Stories from {@link #save}; an empty list when the copy is missing or damaged. */
    static List<Story> restore(String json) {
        List<Story> stories = new ArrayList<>();
        if (json == null) return stories;
        try {
            JSONArray in = new JSONArray(json);
            for (int i = 0; i < in.length() && i < MAX_KEPT; i++) {
                JSONObject o = in.getJSONObject(i);
                String title = o.optString("t", "");
                if (title.isEmpty()) continue;
                stories.add(new Story(o.optString("l", ""), o.optString("s", ""), limit(title, MAX_TITLE + 1),
                        limit(o.optString("b", ""), MAX_SUMMARY + 1), o.optLong("w", 0)));
            }
        } catch (JSONException e) {
            stories.clear();
        }
        return stories;
    }

    private static final int TIMEOUT_MS = 10000;
    private static final long TOTAL_MS = 30000;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    /** Markup read from an item before it is turned into text, so a hostile item cannot make that slow. */
    private static final int MAX_RAW_TITLE = 2000, MAX_RAW_SUMMARY = 20000, MAX_TITLE = 300;
    private static final int MAX_STORIES = 20;
    private static final int MAX_SUMMARY = 900;
    /** Stories kept on the TV for offline use: every feed's 20, a few feeds. */
    private static final int MAX_KEPT = 200;
    /** WordPress closes every summary with "The post X appeared first on Y." */
    private static final Pattern WORDPRESS_FOOTER = Pattern.compile("(?is)<p>\\s*The post .*?</p>");
    private static final Pattern TAG = Pattern.compile("<[^<>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");

    final String language, source, url, keyword;

    private Feed(String language, String source, String url, String keyword) {
        this.language = language;
        this.source = source;
        this.url = url;
        this.keyword = keyword;
    }

    /** A config.xml line, "language | source | url | keyword", or null when it is not one. */
    static Feed parse(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length < 3 || parts[2].trim().isEmpty()) return null;
        String keyword = parts.length > 3 ? parts[3].trim() : "";
        return new Feed(parts[0].trim(), parts[1].trim(), parts[2].trim(), keyword);
    }

    /** The feed's stories, newest first as the feed lists them; throws when it did not load. */
    List<Story> fetch() throws Exception {
        long deadline = System.nanoTime() + TOTAL_MS * 1000000L;
        URL target = new URL(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!"https".equals(target.getProtocol())) throw new IOException("Not https: " + target.getHost());
            HttpURLConnection connection = (HttpURLConnection) target.openConnection();
            try {
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                // Followed by hand below, so a redirect to plain http is refused.
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) SudanNews/1.0");
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    try (InputStream in = connection.getInputStream()) {
                        return read(body(in, deadline));
                    }
                }
                String location = connection.getHeaderField("Location");
                if (code >= 300 && code < 400 && location != null) {
                    target = new URL(target, location);
                    continue;
                }
                throw new IOException("HTTP " + code + " from " + target.getHost());
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects from " + url);
    }

    /** The response, at most 2 MB and 30 s; unzipped (to at most 2 MB) when the server sent gzip unasked. */
    static byte[] body(InputStream in, long deadlineNanos) throws IOException {
        byte[] bytes = capped(in, deadlineNanos);
        boolean gzip = bytes.length > 1 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
        if (!gzip) return bytes;
        try (InputStream unzipped = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return capped(unzipped, deadlineNanos);
        }
    }

    /** Reads to the end, never holding more than MAX_BYTES; throws when there is more or time runs out. */
    private static byte[] capped(InputStream in, long deadlineNanos) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        while (true) {
            if (System.nanoTime() - deadlineNanos > 0) throw new IOException("Too slow");
            int read = in.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - out.size()));
            if (read < 0) return out.toByteArray();
            out.write(buffer, 0, read);
            if (out.size() > MAX_BYTES) throw new IOException("Feed over " + MAX_BYTES + " bytes");
        }
    }

    List<Story> read(byte[] xml) throws Exception {
        // RSS needs no DTD. Refusing one stops entity-expansion bombs on Android's parser, which has no switch for it.
        if (declaresDtd(xml)) throw new IOException("Feed has a DTD");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // Android's parser has no such switch, and resolves no external entities anyway.
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(new ByteArrayInputStream(xml));
        NodeList items = document.getElementsByTagName("item");
        List<Story> stories = new ArrayList<>();
        for (int i = 0; i < items.getLength() && stories.size() < MAX_STORIES; i++) {
            Element item = (Element) items.item(i);
            String title = plainText(limit(child(item, "title"), MAX_RAW_TITLE));
            String summary = plainText(limit(child(item, "description"), MAX_RAW_SUMMARY));
            if (title.isEmpty() || !mentions(title + " " + summary)) continue;
            if (title.length() > MAX_TITLE) title = title.substring(0, MAX_TITLE).trim() + "…";
            if (summary.length() > MAX_SUMMARY) summary = summary.substring(0, MAX_SUMMARY).trim() + "…";
            stories.add(new Story(language, source, title, summary, time(child(item, "pubDate"))));
        }
        return stories;
    }

    /** True when the bytes hold a DOCTYPE or ENTITY declaration, or are UTF-16 (which would hide one). */
    static boolean declaresDtd(byte[] xml) {
        if (xml.length > 1 && ((xml[0] & 0xFF) == 0xFE && (xml[1] & 0xFF) == 0xFF
                || (xml[0] & 0xFF) == 0xFF && (xml[1] & 0xFF) == 0xFE)) return true;
        return contains(xml, "<!DOCTYPE") || contains(xml, "<!ENTITY");
    }

    private static boolean contains(byte[] haystack, String ascii) {
        byte[] needle = ascii.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    private static String limit(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    private boolean mentions(String text) {
        return keyword.isEmpty() || text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private static String child(Element item, String tag) {
        NodeList nodes = item.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    /** HTML from a feed as one line of text: no tags, no images, entities decoded. */
    static String plainText(String html) {
        if (html == null) return "";
        String text = WORDPRESS_FOOTER.matcher(html).replaceAll(" ");
        text = TAG.matcher(text).replaceAll(" ");
        text = decodeEntities(text);
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String decodeEntities(String text) {
        Matcher m = ENTITY.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            String value;
            if (name.startsWith("#")) {
                value = codePoint(name, m.group());
            } else {
                switch (name) {
                    case "amp": value = "&"; break;
                    case "lt": value = "<"; break;
                    case "gt": value = ">"; break;
                    case "quot": value = "\""; break;
                    case "apos": value = "'"; break;
                    case "nbsp": value = " "; break;
                    case "hellip": value = "…"; break;
                    default: value = m.group(); break;
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** "#8217" or "#x2019" as its character; the entity as written when it names no character. */
    private static String codePoint(String name, String entity) {
        try {
            boolean hex = name.startsWith("#x");
            int code = Integer.parseInt(name.substring(hex ? 2 : 1), hex ? 16 : 10);
            if (code < 32 && code != 9 && code != 10 && code != 13) return " ";
            if (code >= 0xD800 && code <= 0xDFFF) return entity;
            return new String(Character.toChars(code));
        } catch (IllegalArgumentException e) {
            return entity;
        }
    }

    /** SimpleDateFormat is not thread safe and the feeds are read on a pool: one per thread, made once. */
    private static final ThreadLocal<SimpleDateFormat> PUB_DATE = ThreadLocal.withInitial(
            () -> new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US));

    /** An RSS pubDate ("Mon, 26 Oct 2026 07:59:20 GMT" or "+0000") in milliseconds, 0 when unreadable. */
    static long time(String pubDate) {
        if (pubDate == null) return 0;
        try {
            return PUB_DATE.get().parse(pubDate.trim()).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }
}
