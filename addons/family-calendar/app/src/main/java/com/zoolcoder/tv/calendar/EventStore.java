package com.zoolcoder.tv.calendar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The events and the reminders already shown, in one JSON file. At most 500 events. Every write goes to a
 * temporary file that then replaces the old one, so a power cut leaves the old calendar or the new one.
 * The events are copied under the lock and written outside it, one writer at a time, so a slow flash never
 * holds up the screen or a phone. A file that cannot be read is kept aside as events.json.bad, not
 * overwritten. Plain Java with org.json, so it is tested on the JVM. Safe to use from the server and the UI thread.
 */
final class EventStore {
    /** What became of an added event. NOT_SAVED: the file could not be written, and the event was dropped. */
    enum Added { OK, FULL, NOT_SAVED }

    static final int MAX_EVENTS = 500;
    static final String FILE = "events.json";
    /** Fired reminders are kept this many days after their occurrence, then forgotten. */
    static final int FIRED_DAYS = 3;

    private static EventStore shared;

    private final File file;
    private final List<Event> events = new ArrayList<>();
    private final Set<String> fired = new HashSet<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Object writer = new Object();
    private final Executor background;
    /** The number of the latest copy taken under the lock, and of the latest one on disk. */
    private long version, written;

    EventStore(File file) {
        this(file, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "family-calendar-save");
            t.setDaemon(true);
            return t;
        }));
    }

    /** {@code background} runs the writes nobody waits for (shown reminders); tests pass a direct one. */
    EventStore(File file, Executor background) {
        this.file = file;
        this.background = background;
        load();
    }

    /** One store per process, so the service and the screen see the same events. */
    static synchronized EventStore in(File dir) {
        if (shared == null) shared = new EventStore(new File(dir, FILE));
        return shared;
    }

    /** Called after every change, on the thread that made it. */
    void listen(Runnable listener) {
        listeners.add(listener);
    }

    void unlisten(Runnable listener) {
        listeners.remove(listener);
    }

    synchronized List<Event> events() {
        return new ArrayList<>(events);
    }

    synchronized Set<String> fired() {
        return new HashSet<>(fired);
    }

    /** Adds the event and writes the file before answering, so "saved" on a phone means saved. */
    Added add(Event event) {
        Snapshot snapshot;
        synchronized (this) {
            if (events.size() >= MAX_EVENTS) return Added.FULL;
            events.add(event);
            snapshot = snapshot();
        }
        if (snapshot == null || !write(snapshot)) {
            synchronized (this) {
                events.remove(event);
            }
            return Added.NOT_SAVED;
        }
        changed();
        return Added.OK;
    }

    /** False when no event has this id. A repeating event goes on every day. */
    boolean delete(String id) {
        Snapshot snapshot;
        synchronized (this) {
            boolean removed = events.removeIf(e -> e.id.equals(id));
            if (!removed) return false;
            snapshot = snapshot();
        }
        // A failed write keeps the event gone in memory; the next change writes the file again.
        if (snapshot != null) write(snapshot);
        changed();
        return true;
    }

    /** Remembers shown reminders, and forgets those for occurrences more than FIRED_DAYS ago. Written in the background. */
    void markFired(Iterable<String> keys, LocalDate today) {
        Snapshot snapshot;
        synchronized (this) {
            for (String key : keys) fired.add(key);
            LocalDate oldest = today.minusDays(FIRED_DAYS);
            fired.removeIf(key -> {
                LocalDate day = Event.date(key.substring(key.lastIndexOf('@') + 1));
                return day == null || day.isBefore(oldest);
            });
            snapshot = snapshot();
        }
        if (snapshot != null) background.execute(() -> write(snapshot));
    }

    private void changed() {
        for (Runnable listener : listeners) listener.run();
    }

    private void load() {
        if (!file.exists()) return;
        boolean readable;
        try {
            readable = parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), events, fired);
        } catch (IOException e) {
            readable = false;
        }
        if (!readable) {
            events.clear();
            fired.clear();
            // Keep what could not be read for a person to look at, instead of writing over it with the next change.
            File bad = new File(file.getPath() + ".bad");
            if (!bad.delete() && bad.exists()) return;
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(bad);
        }
    }

    /**
     * Keeps the readable events (at most MAX_EVENTS) of whatever is stored, and returns true. Garbage gives an
     * empty calendar and false.
     */
    static boolean parse(String stored, List<Event> events, Set<String> fired) {
        try {
            JSONObject root = new JSONObject(stored);
            JSONArray array = root.optJSONArray("events");
            for (int i = 0; array != null && i < array.length() && events.size() < MAX_EVENTS; i++) {
                Event e = fromJson(array.optJSONObject(i));
                if (e != null) events.add(e);
            }
            JSONArray keys = root.optJSONArray("fired");
            for (int i = 0; keys != null && i < keys.length(); i++) {
                String key = keys.optString(i, null);
                if (key != null && key.length() <= 64) fired.add(key);
            }
        } catch (JSONException e) {
            events.clear();
            fired.clear();
            return false;
        }
        return true;
    }

    static Event fromJson(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "");
        if (!id.matches("[0-9a-f]{12}")) return null;
        String title = Sanitizer.title(o.optString("title", null));
        LocalDate date = Event.date(o.optString("date", null));
        String who = o.has("who") ? Sanitizer.name(o.optString("who", null)) : null;
        Event.Repeat repeat = Event.repeat(o.optString("repeat", "none"));
        Event.Reminder reminder = Event.reminder(o.optString("reminder", "none"));
        if (title == null || date == null || repeat == null || reminder == null) return null;
        java.time.LocalTime start = Event.time(o.optString("start", null));
        java.time.LocalTime end = start == null ? null : Event.time(o.optString("end", null));
        if (end != null && !end.isAfter(start)) end = null;
        int color = o.optInt("color", Event.colorFor(who));
        if (color < 0 || color >= Event.COLORS) color = Event.colorFor(who);
        return new Event(id, title, date, start, end, who, color, repeat, reminder, o.optBoolean("away", false));
    }

    static JSONObject toJson(Event e) throws JSONException {
        JSONObject o = new JSONObject()
                .put("id", e.id)
                .put("title", e.title)
                .put("date", e.date.toString())
                .put("color", e.color)
                .put("repeat", Event.code(e.repeat))
                .put("reminder", Event.code(e.reminder));
        if (e.start != null) o.put("start", e.start.toString());
        if (e.end != null) o.put("end", e.end.toString());
        if (e.who != null) o.put("who", e.who);
        if (e.away) o.put("away", true);
        return o;
    }

    private static final class Snapshot {
        final long version;
        final byte[] bytes;

        Snapshot(long version, byte[] bytes) {
            this.version = version;
            this.bytes = bytes;
        }
    }

    /** A copy of the events as file bytes, taken under the lock; null when they cannot be written as JSON. */
    private Snapshot snapshot() {
        try {
            JSONArray array = new JSONArray();
            for (Event e : events) array.put(toJson(e));
            JSONArray keys = new JSONArray();
            for (String key : fired) keys.put(key);
            byte[] bytes = new JSONObject().put("events", array).put("fired", keys).toString()
                    .getBytes(StandardCharsets.UTF_8);
            return new Snapshot(++version, bytes);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Writes a copy unless a newer one is already on disk; true when the file holds this copy or a newer one. */
    private boolean write(Snapshot snapshot) {
        synchronized (writer) {
            if (snapshot.version <= written) return true;
            try {
                File temp = new File(file.getPath() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    out.write(snapshot.bytes);
                    out.getFD().sync();
                }
                if (!temp.renameTo(file)) throw new IOException("rename failed");
                written = snapshot.version;
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }
}
