package com.gathin.tvhome;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Names for a wallpaper collection (wallpaper_captions): {@code wallpapers/captions.txt}, one
 * "filename<TAB>name" per line. Blank lines, lines starting with # and lines without a tab are skipped; a name
 * is cut to {@link #MAX_LENGTH} characters. No Android types, so it is tested on the JVM.
 */
final class WallpaperCaptions {
    static final String FILE_NAME = "captions.txt";
    /** wallpaper_credits: the same format, with the picture's author and licence. */
    static final String CREDITS_FILE_NAME = "credits.txt";
    static final int MAX_LENGTH = 80;

    private WallpaperCaptions() { }

    static Map<String, String> parse(List<String> lines) {
        Map<String, String> names = new HashMap<>();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty() || line.trim().startsWith("#")) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String file = line.substring(0, tab).trim();
            String name = line.substring(tab + 1).trim();
            if (file.isEmpty() || name.isEmpty()) continue;
            if (name.length() > MAX_LENGTH) name = name.substring(0, MAX_LENGTH).trim();
            names.put(file, name);
        }
        return names;
    }

    /** The names in {@code folder}/captions.txt, or none when the file is missing or unreadable. */
    static Map<String, String> read(File folder) {
        return read(folder, FILE_NAME);
    }

    /** The caption's text: the name, the credit in a second line, either alone, or null for none. */
    static String caption(String name, String credit) {
        if (name == null) return credit;
        return credit == null ? name : name + "\n" + credit;
    }

    static Map<String, String> read(File folder, String fileName) {
        File file = folder == null ? null : new File(folder, fileName);
        if (file == null || !file.isFile() || file.length() > 256 * 1024) return Collections.emptyMap();
        try {
            return parse(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
