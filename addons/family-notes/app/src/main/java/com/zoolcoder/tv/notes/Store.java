package com.zoolcoder.tv.notes;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Everything the add-on keeps: the on/off switch, the PIN, the server state and the last 10 notes. */
final class Store {
    static final String FILE = "family_notes";
    static final int KEEP = 10;
    private static final String ENABLED = "enabled", PIN = "pin", NOTES = "notes", STATE = "state",
            WRONG = "wrong", LOCKED_UNTIL = "locked_until";
    private static final Object LOCK = new Object();

    private final SharedPreferences prefs;

    Store(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    SharedPreferences prefs() {
        return prefs;
    }

    /** Off until the owner switches it on: a server should not start by itself the first time. */
    boolean enabled() {
        return prefs.getBoolean(ENABLED, false);
    }

    void setEnabled(boolean on) {
        prefs.edit().putBoolean(ENABLED, on).apply();
    }

    /** The PIN, made from 6 SecureRandom digits the first time it is asked for. */
    String pin() {
        synchronized (LOCK) {
            String pin = prefs.getString(PIN, null);
            if (pin == null || !pin.matches("[0-9]{" + PinGuard.PIN_DIGITS + "}")) {
                pin = PinGuard.newPin(new SecureRandom());
                prefs.edit().putString(PIN, pin).commit();
            }
            return pin;
        }
    }

    void newPin() {
        synchronized (LOCK) {
            prefs.edit().putString(PIN, PinGuard.newPin(new SecureRandom())).commit();
        }
    }

    /** "running", "port" (could not listen) or "" while the service is off. */
    String state() {
        return prefs.getString(STATE, "");
    }

    void setState(String state) {
        prefs.edit().putString(STATE, state).apply();
    }

    /** The wrong-PIN count and lockout end, so restarting the service is no way around the lockout. */
    PinGuard.Memory lockoutMemory() {
        return new PinGuard.Memory() {
            @Override
            public long[] load() {
                try {
                    return new long[] {prefs.getInt(WRONG, 0), prefs.getLong(LOCKED_UNTIL, 0)};
                } catch (ClassCastException e) {
                    return new long[2];
                }
            }

            @Override
            public void save(int wrong, long lockedUntil) {
                prefs.edit().putInt(WRONG, wrong).putLong(LOCKED_UNTIL, lockedUntil).commit();
            }
        };
    }

    List<Note> notes() {
        String stored;
        try {
            stored = prefs.getString(NOTES, "[]");
        } catch (ClassCastException e) {
            return new ArrayList<>();
        }
        return parse(stored);
    }

    /** Keeps the readable entries (at most KEEP) of whatever is stored; garbage gives an empty list. */
    static List<Note> parse(String stored) {
        List<Note> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(stored);
            for (int i = 0; i < array.length() && list.size() < KEEP; i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String name = Sanitizer.name(o.optString("n", null));
                String text = Sanitizer.text(o.optString("t", null));
                long time = o.optLong("w", -1);
                if (name != null && text != null && time >= 0) list.add(new Note(name, text, time));
            }
        } catch (JSONException e) {
            list.clear();
        }
        return list;
    }

    /** Newest first. */
    void add(Note note) {
        synchronized (LOCK) {
            List<Note> list = notes();
            list.add(0, note);
            JSONArray array = new JSONArray();
            try {
                for (int i = 0; i < list.size() && i < KEEP; i++) {
                    Note n = list.get(i);
                    array.put(new JSONObject().put("n", n.name).put("t", n.text).put("w", n.time));
                }
            } catch (JSONException e) {
                return;
            }
            prefs.edit().putString(NOTES, array.toString()).commit();
        }
    }

    void clearNotes() {
        synchronized (LOCK) {
            prefs.edit().remove(NOTES).commit();
        }
    }
}
