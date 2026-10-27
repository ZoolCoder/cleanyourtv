package com.zoolcoder.tv.kids;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** One channel from config.xml, and its latest videos read from the channel's public uploads feed. */
final class Channel {
    static final class Video {
        final String id, title;

        Video(String id, String title) {
            this.id = id;
            this.title = title;
        }

        String watchUrl() {
            return "https://www.youtube.com/watch?v=" + id;
        }

        String thumbnailUrl() {
            return "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg";
        }
    }

    private static final int TIMEOUT_MS = 10000;
    private static final int MAX_BYTES = 1024 * 1024;
    static final int MAX_VIDEOS = 12, MAX_TITLE = 200;
    private static final Pattern CHANNEL_ID = Pattern.compile("UC[A-Za-z0-9_-]{22}");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    final String arabicName, englishName, id;

    private Channel(String arabicName, String englishName, String id) {
        this.arabicName = arabicName;
        this.englishName = englishName;
        this.id = id;
    }

    /** A config.xml line, "Arabic name | English name | channel id", or null when it is not one. */
    static Channel parse(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length < 3 || !isChannelId(parts[2].trim())) return null;
        return new Channel(parts[0].trim(), parts[1].trim(), parts[2].trim());
    }

    String feedUrl() {
        return "https://www.youtube.com/feeds/videos.xml?channel_id=" + id;
    }

    /** The latest uploads, newest first as the feed lists them; throws when the feed did not load. */
    List<Video> fetch() throws Exception {
        return read(new ByteArrayInputStream(Http.get(feedUrl(), MAX_BYTES, TIMEOUT_MS)));
    }

    static boolean isVideoId(String id) {
        return VIDEO_ID.matcher(id).matches();
    }

    static boolean isChannelId(String id) {
        return CHANNEL_ID.matcher(id).matches();
    }

    /** Favourite channels first, each group in config.xml order. */
    static List<Channel> favouritesFirst(List<Channel> channels, Set<String> favourites) {
        List<Channel> ordered = new ArrayList<>();
        for (Channel channel : channels) if (favourites.contains(channel.id)) ordered.add(channel);
        for (Channel channel : channels) if (!favourites.contains(channel.id)) ordered.add(channel);
        return ordered;
    }

    /** Up to 1 MB of the response, unzipped (also up to 1 MB) when the server sent gzip without being asked. */
    private static byte[] body(InputStream in) throws IOException {
        byte[] bytes = Http.readCapped(in, MAX_BYTES);
        boolean gzip = bytes.length > 1 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
        if (!gzip) return bytes;
        try (InputStream unzipped = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return Http.readCapped(unzipped, MAX_BYTES);
        }
    }

    /** The Atom feed's videos; Shorts are skipped, they carry a /shorts/ link in the feed. */
    static List<Video> read(InputStream xml) throws Exception {
        byte[] bytes = body(xml);
        // No feed has a DTD; refusing one rules out entity tricks whatever the parser supports.
        String head = new String(bytes, StandardCharsets.ISO_8859_1);
        if (head.contains("<!DOCTYPE") || head.contains("<!ENTITY")) throw new IOException("DTD in feed");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // Android's parser has no such switch, and resolves no external entities anyway.
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(new ByteArrayInputStream(bytes));
        NodeList entries = document.getElementsByTagName("entry");
        List<Video> videos = new ArrayList<>();
        for (int i = 0; i < entries.getLength() && videos.size() < MAX_VIDEOS; i++) {
            Element entry = (Element) entries.item(i);
            String id = text(entry, "yt:videoId");
            String title = text(entry, "title");
            if (!isVideoId(id) || title.isEmpty() || isShort(entry)) continue;
            videos.add(new Video(id, title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE) : title));
        }
        return videos;
    }

    private static boolean isShort(Element entry) {
        NodeList links = entry.getElementsByTagName("link");
        for (int i = 0; i < links.getLength(); i++) {
            if (((Element) links.item(i)).getAttribute("href").contains("/shorts/")) return true;
        }
        return false;
    }

    /** The first descendant with this tag, as one line of text. */
    private static String text(Element entry, String tag) {
        NodeList nodes = entry.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().replaceAll("\\s+", " ").trim();
    }
}
