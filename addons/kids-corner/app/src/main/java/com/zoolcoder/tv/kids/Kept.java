package com.zoolcoder.tv.kids;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The last good feed of each channel, as plain text for a file in filesDir: one "channel id, video id, title"
 * line per video, tab separated. Read back, every line is checked again, so a damaged file loses lines, not the app.
 */
final class Kept {
    static final int MAX_BYTES = 256 * 1024;

    private Kept() {
    }

    /** The feeds in the map's order, stopping before the text would pass MAX_BYTES. */
    static String write(Map<String, List<Channel.Video>> feeds) {
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (Map.Entry<String, List<Channel.Video>> feed : feeds.entrySet()) {
            for (Channel.Video video : feed.getValue()) {
                String line = feed.getKey() + '\t' + video.id + '\t' + oneLine(video.title) + '\n';
                int size = line.getBytes(StandardCharsets.UTF_8).length;
                if (bytes + size > MAX_BYTES) return out.toString();
                out.append(line);
                bytes += size;
            }
        }
        return out.toString();
    }

    /** The feeds in the text, in file order; lines with a bad channel or video id are dropped. */
    static Map<String, List<Channel.Video>> read(String text) {
        Map<String, List<Channel.Video>> feeds = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String[] parts = line.split("\t", 3);
            if (parts.length < 3 || !Channel.isChannelId(parts[0]) || !Channel.isVideoId(parts[1])) continue;
            String title = oneLine(parts[2]);
            if (title.isEmpty()) continue;
            List<Channel.Video> videos = feeds.computeIfAbsent(parts[0], key -> new ArrayList<>());
            if (videos.size() < Channel.MAX_VIDEOS) videos.add(new Channel.Video(parts[1], clip(title)));
        }
        return feeds;
    }

    static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    static String clip(String title) {
        return title.length() > Channel.MAX_TITLE ? title.substring(0, Channel.MAX_TITLE) : title;
    }
}
