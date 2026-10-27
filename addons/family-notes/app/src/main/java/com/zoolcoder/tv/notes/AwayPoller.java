package com.zoolcoder.tv.notes;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Family away for notes: sets the box up on the relay, then fetches, opens and hands on what relatives
 * abroad sent. Each message is sealed with the box key, cleaned like a home note and tagged as from abroad;
 * anything that does not open or is not a note is dropped and counted. The read position is saved before the
 * relay is told to drop what was read, so a lost acknowledgement never shows a note twice. Every call runs on
 * the one Family away thread (THREAD), which keeps the service's polls and the app's buttons in order.
 */
final class AwayPoller {
    static final long INTERVAL_MS = 2 * 60 * 1000L;
    static final long MAX_INTERVAL_MS = 15 * 60 * 1000L;
    /** A full page came back: fetch the rest soon instead of in two minutes. */
    static final long DRAIN_MS = 1000;
    static final long STOP = -1;
    static final int PAGE = 50;

    static final ScheduledExecutorService THREAD = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "family-away");
        thread.setDaemon(true);
        return thread;
    });

    private final AwayBox box;
    private final RelayClient client;
    private final LongSupplier clock;
    private final SecureRandom random;
    private final Consumer<Note> sink;
    private long interval = INTERVAL_MS;

    AwayPoller(AwayBox box, RelayClient client, LongSupplier clock, SecureRandom random, Consumer<Note> sink) {
        this.box = box;
        this.client = client;
        this.clock = clock;
        this.random = random;
        this.sink = sink;
    }

    /** One round. Returns the milliseconds until the next, or STOP when off or the box is gone. */
    long poll() {
        if (!box.on() || AwayBox.STATUS_GONE.equals(box.status())) return STOP;
        try {
            if (!box.created()) setUp();
            RelayClient.Batch batch = client.fetch(box.boxId(), box.readToken(), box.lastSeq());
            int dropped = 0;
            for (RelayClient.Message message : batch.messages) {
                Note note = open(message);
                if (note == null) dropped++;
                else sink.accept(note);
                box.setSeq(message.seq);
            }
            box.addDropped(dropped);
            if (batch.next > box.lastSeq()) box.setSeq(batch.next);
            if (!batch.messages.isEmpty()) client.ack(box.boxId(), box.readToken(), box.lastSeq());
            box.contact(clock.getAsLong());
            interval = INTERVAL_MS;
            return batch.messages.size() >= PAGE ? DRAIN_MS : interval;
        } catch (RelayClient.RelayException e) {
            return failed(e);
        }
    }

    private void setUp() throws RelayClient.RelayException {
        if (box.boxId().isEmpty()) box.generate(random);
        try {
            client.createBox(box.boxId(), RelayCrypto.sha256Hex(box.readToken()), RelayCrypto.sha256Hex(box.sendToken()));
        } catch (RelayClient.RelayException e) {
            // A taken id (1 in 2^128) gets fresh secrets on the next round; the box is new, so nothing is lost.
            if (e.kind == RelayClient.RelayException.Kind.CONFLICT) box.generate(random);
            if (e.gone()) throw new RelayClient.RelayException(RelayClient.RelayException.Kind.BAD_RESPONSE, "create");
            throw e;
        }
        box.markCreated(clock.getAsLong());
    }

    private long failed(RelayClient.RelayException e) {
        if (e.gone()) {
            box.setStatus(AwayBox.STATUS_GONE);
            return STOP;
        }
        box.setStatus(AwayBox.STATUS_OFFLINE);
        interval = Math.min(interval * 2, MAX_INTERVAL_MS);
        return interval;
    }

    /** The note inside, cleaned; null when it does not open, is not a note, or fails the home rules. */
    Note open(RelayClient.Message message) {
        try {
            JSONObject o = new JSONObject(RelayCrypto.open(box.key(), box.boxId(), message.sealed));
            if (o.optInt("v") != 1 || !"note".equals(o.optString("kind"))) return null;
            Object from = o.opt("from"), text = o.opt("text");
            if (!(from instanceof String) || !(text instanceof String)) return null;
            String name = Sanitizer.name((String) from), body = Sanitizer.text((String) text);
            if (name == null || body == null) return null;
            // The relay's arrival time, so a note sent while the TV was off sorts where it belongs.
            long now = clock.getAsLong();
            long at = message.at > 0 && message.at <= now ? message.at : now;
            return new Note(name, body, at, true);
        } catch (GeneralSecurityException | JSONException e) {
            return null;
        }
    }

    /** New link: a new send token, so links shared before stop working. False when the relay was not reached. */
    boolean rotate() {
        if (!box.created()) return false;
        String token = RelayCrypto.newToken(random);
        try {
            client.rotateSend(box.boxId(), box.readToken(), RelayCrypto.sha256Hex(token));
        } catch (RelayClient.RelayException e) {
            failed(e);
            return false;
        }
        box.commitSendToken(token, clock.getAsLong());
        return true;
    }

    /** Deletes the box on the relay, then here. False, and the box kept, when the relay was not reached. */
    boolean delete() {
        if (box.created()) {
            try {
                client.deleteBox(box.boxId(), box.readToken());
            } catch (RelayClient.RelayException e) {
                if (!e.gone()) {
                    failed(e);
                    return false;
                }
            }
        }
        box.clear();
        box.setOn(false);
        interval = INTERVAL_MS;
        return true;
    }
}
