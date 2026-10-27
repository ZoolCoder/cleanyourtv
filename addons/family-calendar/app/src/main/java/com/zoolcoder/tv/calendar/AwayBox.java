package com.zoolcoder.tv.calendar;

import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * This TV's Family away box: its id, the read token (stays here), the send token and key (go into the invite
 * link only), and how far the TV has read. Kept in its own app-private preferences file; the app turns backup
 * and device transfer off, so the secrets never leave the TV. Shared with Family Calendar: keep this file free
 * of anything notes-specific.
 */
final class AwayBox {
    static final String FILE = "away";
    static final String STATUS_SETUP = "setup", STATUS_OK = "ok", STATUS_OFFLINE = "offline", STATUS_GONE = "gone";

    private static final String ON = "on", BOX = "box", READ = "read", SEND = "send", KEY = "key",
            CREATED = "created", SEQ = "seq", CONTACT = "contact", STATUS = "status", DROPPED = "dropped";

    private final SharedPreferences prefs;

    AwayBox(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    boolean on() {
        return prefs.getBoolean(ON, false);
    }

    /** On again after the relay dropped the box (deleted, or idle too long) starts over with a new one. */
    void setOn(boolean on) {
        SharedPreferences.Editor edit = prefs.edit();
        if (on && STATUS_GONE.equals(status())) edit.clear();
        edit.putBoolean(ON, on).apply();
    }

    String boxId() {
        return prefs.getString(BOX, "");
    }

    String readToken() {
        return prefs.getString(READ, "");
    }

    String sendToken() {
        return prefs.getString(SEND, "");
    }

    String key() {
        return prefs.getString(KEY, "");
    }

    /** True once the relay has accepted the box. */
    boolean created() {
        return prefs.getBoolean(CREATED, false) && !boxId().isEmpty();
    }

    long lastSeq() {
        return prefs.getLong(SEQ, 0);
    }

    long lastContact() {
        return prefs.getLong(CONTACT, 0);
    }

    String status() {
        return prefs.getString(STATUS, "");
    }

    int dropped() {
        return prefs.getInt(DROPPED, 0);
    }

    /** Fresh secrets for a box the relay has not seen yet; the old ones, if any, are forgotten. */
    void generate(SecureRandom random) {
        prefs.edit()
                .putString(BOX, RelayCrypto.newBoxId(random))
                .putString(READ, RelayCrypto.newToken(random))
                .putString(SEND, RelayCrypto.newToken(random))
                .putString(KEY, RelayCrypto.newKey(random))
                .putBoolean(CREATED, false)
                .putLong(SEQ, 0)
                .putString(STATUS, STATUS_SETUP)
                .commit();
    }

    void markCreated(long now) {
        prefs.edit().putBoolean(CREATED, true).putLong(CONTACT, now).putString(STATUS, STATUS_OK).commit();
    }

    void commitSendToken(String token, long now) {
        prefs.edit().putString(SEND, token).putLong(CONTACT, now).putString(STATUS, STATUS_OK).commit();
    }

    void setSeq(long seq) {
        prefs.edit().putLong(SEQ, seq).commit();
    }

    void contact(long now) {
        prefs.edit().putLong(CONTACT, now).putString(STATUS, STATUS_OK).apply();
    }

    void setStatus(String status) {
        if (!status.equals(status())) prefs.edit().putString(STATUS, status).apply();
    }

    void addDropped(int count) {
        if (count > 0) prefs.edit().putInt(DROPPED, dropped() + count).apply();
    }

    /** Forgets the box entirely; Family away stays as switched. */
    void clear() {
        boolean on = on();
        prefs.edit().clear().putBoolean(ON, on).commit();
    }
}
