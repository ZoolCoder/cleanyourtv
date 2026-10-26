package com.zoolcoder.tv.quranradio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** The config.xml lines and the mp3quran.net answers, as plain values. No Android, so the JVM tests reach it. */
final class Catalog {
    private static final int MAX_NAME = 120;
    private static final int MAX_SURAH_LIST = 1024;

    private Catalog() {}

    /** A live radio stream from the stations list. */
    static final class Station {
        final String name, url;

        Station(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    /** A reciters line: which reciter, and which of his recitations (0 = the first one). */
    static final class Ref {
        final int reciterId, moshafId;

        Ref(int reciterId, int moshafId) {
            this.reciterId = reciterId;
            this.moshafId = moshafId;
        }
    }

    /** One recitation of the whole Quran, or part of it, by one reciter. */
    static final class Reciter {
        final int id;
        final String name, recitation, server;
        final int[] surahs;

        Reciter(int id, String name, String recitation, String server, int[] surahs) {
            this.id = id;
            this.name = name;
            this.recitation = recitation;
            this.server = server;
            this.surahs = surahs;
        }
    }

    /** A stations line, "name | https stream URL", or null when it is not one. */
    static Station parseStation(String line) {
        int bar = line.indexOf('|');
        if (bar < 0) return null;
        String name = line.substring(0, bar).trim();
        String url = line.substring(bar + 1).trim();
        if (name.isEmpty() || hostOf(url) == null) return null;
        return new Station(name, url);
    }

    /** A reciters line, "id", "id:moshaf" or either followed by "| note", or null when it is not one. */
    static Ref parseRef(String line) {
        int bar = line.indexOf('|');
        String ids = (bar < 0 ? line : line.substring(0, bar)).trim();
        String[] parts = ids.split(":", -1);
        if (parts.length > 2) return null;
        try {
            int reciter = Integer.parseInt(parts[0].trim());
            int moshaf = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : 0;
            return reciter > 0 && moshaf >= 0 ? new Ref(reciter, moshaf) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The reciter in a /reciters?reciter=ID answer, with the wanted recitation, or null when it is not there. */
    static Reciter parseReciter(String json, Ref ref) {
        try {
            JSONArray reciters = new JSONObject(json).getJSONArray("reciters");
            if (reciters.length() == 0) return null;
            JSONObject reciter = reciters.getJSONObject(0);
            JSONArray moshafs = reciter.getJSONArray("moshaf");
            for (int i = 0; i < moshafs.length(); i++) {
                JSONObject moshaf = moshafs.getJSONObject(i);
                if (ref.moshafId != 0 && moshaf.getInt("id") != ref.moshafId) continue;
                String server = moshaf.getString("server").trim();
                String list = moshaf.getString("surah_list");
                int[] surahs = list.length() > MAX_SURAH_LIST ? new int[0] : parseSurahList(list);
                if (!isTrustedServer(server) || surahs.length == 0) return null;
                if (!server.endsWith("/")) server += "/";
                String name = clean(reciter.getString("name"));
                if (name.isEmpty()) return null;
                return new Reciter(reciter.getInt("id"), name, clean(moshaf.getString("name")), server, surahs);
            }
            return null;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Text from the network: control characters become spaces, the ends are trimmed, the length is capped. */
    static String clean(String text) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_NAME));
        for (int i = 0; i < text.length() && out.length() < MAX_NAME; i++) {
            char c = text.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString().trim();
    }

    /** The host of an https URL without user info, or null when the text is anything else. */
    static String hostOf(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || host.isEmpty() || uri.getUserInfo() != null) {
                return null;
            }
            return host.toLowerCase(Locale.US);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Whether an audio folder from the API is https on mp3quran.net or qurango.net (or one of their subdomains). */
    static boolean isTrustedServer(String url) {
        String host = hostOf(url);
        return host != null && (isHostOrSubdomain(host, "mp3quran.net") || isHostOrSubdomain(host, "qurango.net"));
    }

    private static boolean isHostOrSubdomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /** "1,2,3" as numbers; anything outside 1 to 114 is dropped. */
    static int[] parseSurahList(String list) {
        String[] parts = list.split(",");
        int[] found = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            try {
                int surah = Integer.parseInt(part.trim());
                if (surah >= 1 && surah <= 114) found[count++] = surah;
            } catch (NumberFormatException ignored) {
                // A stray entry is skipped.
            }
        }
        return java.util.Arrays.copyOf(found, count);
    }

    /** The surah names from /suwar, indexed by surah number; entry 0 and any missing name are empty. */
    static String[] parseSuwar(String json) {
        String[] names = new String[115];
        java.util.Arrays.fill(names, "");
        try {
            JSONArray suwar = new JSONObject(json).getJSONArray("suwar");
            for (int i = 0; i < suwar.length(); i++) {
                JSONObject surah = suwar.getJSONObject(i);
                int id = surah.getInt("id");
                if (id >= 1 && id <= 114) names[id] = clean(surah.getString("name"));
            }
        } catch (JSONException ignored) {
            // Numbers alone are shown then.
        }
        return names;
    }

    /** The file of one surah: the recitation's folder plus the three-digit number. */
    static String surahUrl(Reciter reciter, int surah) {
        return reciter.server + String.format(Locale.US, "%03d", surah) + ".mp3";
    }

    static String reciterUrl(Ref ref, String language) {
        return "https://www.mp3quran.net/api/v3/reciters?language=" + language + "&reciter=" + ref.reciterId;
    }

    static String suwarUrl(String language) {
        return "https://www.mp3quran.net/api/v3/suwar?language=" + language;
    }
}
