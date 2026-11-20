package com.zoolcoder.tv.health;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The one copy of the Records for the whole app, kept in files/health.json. Main thread only: the screen, the
 * reminder service and the banner all run there, so nothing needs a lock. {@link #changed()} saves and tells
 * whoever listens.
 */
final class Store {
    private static final String TAG = "FamilyHealth";
    private static final String BAD = "health.json.bad";
    private static Store instance;

    final Records records;
    private final AtomicFile file;
    private final List<Runnable> listeners = new ArrayList<>();
    /** True when an unreadable file could not be kept aside: then nothing writes over it. */
    private boolean keepOff;

    private Store(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "health.json"));
        records = load();
        records.prune(LocalDate.now(), System.currentTimeMillis());
    }

    static Store get(Context context) {
        if (instance == null) instance = new Store(context.getApplicationContext());
        return instance;
    }

    private Records load() {
        byte[] bytes;
        try {
            bytes = file.readFully();
        } catch (IOException e) {
            return new Records();
        }
        try {
            return Records.fromJson(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
        } catch (JSONException e) {
            // Kept aside, so the first save does not write over records someone may still recover.
            Log.w(TAG, "unreadable records, kept as " + BAD + ", starting empty");
            try (FileOutputStream bad = new FileOutputStream(new File(file.getBaseFile().getParentFile(), BAD))) {
                bad.write(bytes);
            } catch (IOException e2) {
                Log.w(TAG, "could not keep the unreadable records aside, not saving over them");
                keepOff = true;
            }
            return new Records();
        }
    }

    /** Saves the records and tells the listeners. False when the file could not be written. */
    boolean changed() {
        boolean saved = save();
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
        return saved;
    }

    private boolean save() {
        if (keepOff) return false;
        FileOutputStream out = null;
        try {
            byte[] bytes = records.toJson().toString().getBytes(StandardCharsets.UTF_8);
            out = file.startWrite();
            out.write(bytes);
            file.finishWrite(out);
            return true;
        } catch (IOException | JSONException e) {
            if (out != null) file.failWrite(out);
            Log.w(TAG, "records not saved: " + e.getMessage());
            return false;
        }
    }

    void listen(Runnable listener) {
        listeners.add(listener);
    }

    void unlisten(Runnable listener) {
        listeners.remove(listener);
    }
}
