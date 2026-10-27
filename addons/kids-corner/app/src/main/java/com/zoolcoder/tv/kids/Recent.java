package com.zoolcoder.tv.kids;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The videos opened last, newest first, at most MAX; opening one again moves it to the front. */
final class Recent {
    static final int MAX = 8;

    static final class Entry {
        final Channel.Video video;
        final String channelId;

        Entry(Channel.Video video, String channelId) {
            this.video = video;
            this.channelId = channelId;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    void add(Channel.Video video, String channelId) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).video.id.equals(video.id)) entries.remove(i);
        }
        entries.add(0, new Entry(video, channelId));
        while (entries.size() > MAX) entries.remove(entries.size() - 1);
    }

    /** One "video id, channel id, title" line per entry, tab separated; the thumbnail follows from the id. */
    String write() {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            out.append(entry.video.id).append('\t').append(entry.channelId).append('\t')
                    .append(Kept.oneLine(entry.video.title)).append('\n');
        }
        return out.toString();
    }

    /** The list in the text, oldest line last; bad lines and repeats are dropped. */
    static Recent read(String text) {
        Recent recent = new Recent();
        if (text == null) return recent;
        String[] lines = text.split("\n");
        for (int i = Math.min(lines.length, MAX * 4) - 1; i >= 0; i--) {
            String[] parts = lines[i].split("\t", 3);
            if (parts.length < 3 || !Channel.isVideoId(parts[0]) || !Channel.isChannelId(parts[1])) continue;
            String title = Kept.oneLine(parts[2]);
            if (!title.isEmpty()) recent.add(new Channel.Video(parts[0], Kept.clip(title)), parts[1]);
        }
        return recent;
    }
}
