package com.zoolcoder.tv.sudannews;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The stories saved for later, newest first, kept on the TV so they stay readable offline. */
final class Saved {
    static final int MAX = 50;

    private final List<Feed.Story> stories = new ArrayList<>();

    /** The list from {@link #save}; empty when the copy is missing or damaged. */
    static Saved restore(String json) {
        Saved saved = new Saved();
        for (Feed.Story story : Feed.restore(json)) {
            if (saved.stories.size() == MAX) break;
            if (!saved.contains(story)) saved.stories.add(story);
        }
        return saved;
    }

    String save() {
        return Feed.save(stories);
    }

    boolean contains(Feed.Story story) {
        return indexOf(story) >= 0;
    }

    /** Saves the story, or removes it when it is saved already; true when it is saved now. */
    boolean toggle(Feed.Story story) {
        int at = indexOf(story);
        if (at >= 0) {
            stories.remove(at);
            return false;
        }
        stories.add(0, story);
        // The oldest saved story makes room.
        while (stories.size() > MAX) stories.remove(stories.size() - 1);
        return true;
    }

    List<Feed.Story> list() {
        return Collections.unmodifiableList(stories);
    }

    /** A feed sends a story again on every load: the same source and headline are the same story. */
    private int indexOf(Feed.Story story) {
        for (int i = 0; i < stories.size(); i++) {
            Feed.Story s = stories.get(i);
            if (s.source.equals(story.source) && s.title.equals(story.title)) return i;
        }
        return -1;
    }
}
