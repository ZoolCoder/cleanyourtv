package com.zoolcoder.tv.health;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** The phone page's settings: the on/off switch, the PIN, the server state and the wrong-PIN lockout. */
final class Phone {
    static final String FILE = "family_health_phone";
    private static final String ENABLED = "enabled", PIN = "pin", STATE = "state",
            WRONG = "wrong", WINDOW_START = "window_start", LOCKED_UNTIL = "locked_until";
    private static final Object LOCK = new Object();

    private final SharedPreferences prefs;

    Phone(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Off until the owner switches it on: a server should not start by itself the first time. */
    boolean enabled() {
        return prefs.getBoolean(ENABLED, false);
    }

    /**
     * Switching on makes a new PIN, so a PIN seen on the network or kept in a phone's scan history works only
     * until the page is switched off, and starts the wrong-PIN count afresh: the owner chose to open it again.
     */
    void setEnabled(boolean on) {
        synchronized (LOCK) {
            SharedPreferences.Editor edit = prefs.edit().putBoolean(ENABLED, on);
            if (on && !enabled()) {
                edit.putString(PIN, PinGuard.newPin(new SecureRandom()))
                        .remove(WRONG).remove(WINDOW_START).remove(LOCKED_UNTIL);
            }
            edit.commit();
        }
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

    /** "running", "port" (could not listen) or "" while the server is off. */
    String state() {
        return prefs.getString(STATE, "");
    }

    void setState(String state) {
        prefs.edit().putString(STATE, state).apply();
    }

    /** Calls {@code listener} on the main thread when the switch, PIN or state changes. Keep a strong reference. */
    void listen(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    void unlisten(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    /** The all-address wrong-PIN count and lockout end, so restarting the service is no way around the lockout. */
    PinGuard.Memory lockoutMemory() {
        return new PinGuard.Memory() {
            @Override
            public long[] load() {
                try {
                    return new long[] {prefs.getInt(WRONG, 0), prefs.getLong(WINDOW_START, 0),
                            prefs.getLong(LOCKED_UNTIL, 0)};
                } catch (ClassCastException e) {
                    return new long[3];
                }
            }

            @Override
            public void save(int wrong, long windowStart, long lockedUntil) {
                prefs.edit().putInt(WRONG, wrong).putLong(WINDOW_START, windowStart)
                        .putLong(LOCKED_UNTIL, lockedUntil).commit();
            }
        };
    }
}
