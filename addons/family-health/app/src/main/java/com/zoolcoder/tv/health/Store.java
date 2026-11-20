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
    private static Store instance;

    final Records records;
    private final AtomicFile file;
    private final List<Runnable> listeners = new ArrayList<>();

    private Store(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "health.json"));
        records = load(file);
        records.prune(LocalDate.now(), System.currentTimeMillis());
    }

    static Store get(Context context) {
        if (instance == null) instance = new Store(context.getApplicationContext());
        return instance;
    }

    private static Records load(AtomicFile file) {
        try {
            return Records.fromJson(new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return new Records();
        } catch (JSONException e) {
            Log.w(TAG, "unreadable records, starting empty");
            return new Records();
        }
    }

    void changed() {
        save();
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
    }

    private void save() {
        FileOutputStream out = null;
        try {
            byte[] bytes = records.toJson().toString().getBytes(StandardCharsets.UTF_8);
            out = file.startWrite();
            out.write(bytes);
            file.finishWrite(out);
        } catch (IOException | JSONException e) {
            if (out != null) file.failWrite(out);
            Log.w(TAG, "records not saved: " + e.getMessage());
        }
    }

    void listen(Runnable listener) {
        listeners.add(listener);
    }

    void unlisten(Runnable listener) {
        listeners.remove(listener);
    }
}
