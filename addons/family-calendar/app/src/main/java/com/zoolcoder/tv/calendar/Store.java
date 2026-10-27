package com.zoolcoder.tv.calendar;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** The settings: the on/off switch, the PIN, the server state and the wrong-PIN lockout. Events are in EventStore. */
final class Store {
    static final String FILE = "family_calendar";
    private static final String ENABLED = "enabled", PIN = "pin", STATE = "state",
            WRONG = "wrong", WINDOW_START = "window_start", LOCKED_UNTIL = "locked_until";
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
