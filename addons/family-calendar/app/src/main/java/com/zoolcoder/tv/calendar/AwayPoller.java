package com.zoolcoder.tv.calendar;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Family away for the calendar: sets the box up on the relay, then fetches, opens and saves what relatives
 * abroad sent. Each message is sealed with the box key and goes through the same checks as an event from a
 * phone at home, marked as from abroad; anything that does not open, is not an event or fails those checks is
 * dropped and counted. The relay is told to drop a message only once it is saved here: when the TV cannot
 * save, the rest of the batch waits for the next round. Every call runs on the one Family away thread
 * (THREAD), which keeps the service's polls and the app's buttons in order.
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
    private final Supplier<String> ids;
    private final Function<Event, EventStore.Added> sink;
    private long interval = INTERVAL_MS;

    AwayPoller(AwayBox box, RelayClient client, LongSupplier clock, SecureRandom random, Supplier<String> ids,
               Function<Event, EventStore.Added> sink) {
        this.box = box;
        this.client = client;
        this.clock = clock;
        this.random = random;
        this.ids = ids;
        this.sink = sink;
    }

    /** One round. Returns the milliseconds until the next, or STOP when off or the box is gone. */
    long poll() {
        if (!box.on() || AwayBox.STATUS_GONE.equals(box.status())) return STOP;
        try {
            if (!box.created()) setUp();
            long before = box.lastSeq();
            RelayClient.Batch batch = client.fetch(box.boxId(), box.readToken(), before);
            int dropped = 0;
            boolean stuck = false;
            for (RelayClient.Message message : batch.messages) {
                Event event = open(message);
                EventStore.Added added = event == null ? EventStore.Added.FULL : sink.apply(event);
                if (added == EventStore.Added.NOT_SAVED) {
                    stuck = true;
                    break;
                }
                if (added != EventStore.Added.OK) dropped++;
                box.setSeq(message.seq);
            }
            box.addDropped(dropped);
            if (!stuck && batch.next > box.lastSeq()) box.setSeq(batch.next);
            if (box.lastSeq() > before) client.ack(box.boxId(), box.readToken(), box.lastSeq());
            box.contact(clock.getAsLong());
            interval = INTERVAL_MS;
            return !stuck && batch.messages.size() >= PAGE ? DRAIN_MS : interval;
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
        box.setStatus(box.created() ? AwayBox.STATUS_OFFLINE : AwayBox.STATUS_SETUP);
        interval = Math.min(interval * 2, MAX_INTERVAL_MS);
        return interval;
    }

    /**
     * The event inside, checked like one from a phone at home and marked away; null when it does not open, is
     * not an event, or fails those checks. With no "who", the sender's name shows on the event.
     */
    Event open(RelayClient.Message message) {
        try {
            JSONObject o = new JSONObject(RelayCrypto.open(box.key(), box.boxId(), message.sealed));
            if (o.optInt("v") != 1 || !"event".equals(o.optString("kind"))) return null;
            String from = text(o, "from"), who = text(o, "who");
            if (from == null || Sanitizer.name(from) == null) return null;
            Map<String, String> form = new HashMap<>();
            form.put("title", text(o, "title"));
            form.put("date", text(o, "date"));
            form.put("start", text(o, "time"));
            form.put("who", who == null || who.trim().isEmpty() ? from : who);
            form.put("repeat", text(o, "repeat"));
            form.put("reminder", o.optBoolean("remind", false) ? "at" : "none");
            Event e = Event.fromForm(ids.get(), form);
            return new Event(e.id, e.title, e.date, e.start, null, e.who, e.color, e.repeat, e.reminder, true);
        } catch (GeneralSecurityException | JSONException | Event.Invalid e) {
            return null;
        }
    }

    /** A string field, or null when it is missing or not a string. */
    private static String text(JSONObject o, String key) {
        Object value = o.opt(key);
        return value instanceof String ? (String) value : null;
    }

    /**
     * The link a relative opens: the relay's page, then the box, send token, key, language and "event" in the
     * fragment, which the browser never sends, so the relay sees only sealed events.
     */
    static String invite(String relay, AwayBox box, String lang) {
        return relay.replaceAll("/+$", "") + "/f/#" + box.boxId() + "." + box.sendToken() + "." + box.key() + "."
                + lang + ".event";
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
