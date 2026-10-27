package com.zoolcoder.tv.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AwayPollerTest {
    /** The relay's box API in memory: token hashes, sequence numbers, acknowledgements, the same status codes. */
    static final class FakeRelay implements RelayClient.Transport {
        static final Pattern MSG = Pattern.compile("https://relay\\.test/v1/box/([^/?]+)(/msg|/send)?(?:\\?(after|upto)=(\\d+))?");
        final Map<String, String[]> boxes = new HashMap<>();
        final Map<String, TreeMap<Long, String>> queues = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        long seq;
        boolean down;
        int status;

        @Override
        public RelayClient.Response send(String method, String url, String bearer, String body) throws IOException {
            calls.add(method + " " + url.replace("https://relay.test", ""));
            if (down) throw new IOException("unreachable");
            if (status != 0) return new RelayClient.Response(status, "{\"error\":\"x\"}");
            try {
                if (method.equals("POST") && url.endsWith("/v1/box")) {
                    JSONObject o = new JSONObject(body);
                    String id = o.getString("boxId");
                    if (boxes.containsKey(id)) return new RelayClient.Response(409, "{\"error\":\"exists\"}");
                    boxes.put(id, new String[]{o.getString("readHash"), o.getString("sendHash")});
                    queues.put(id, new TreeMap<>());
                    return new RelayClient.Response(201, "{\"ok\":true}");
                }
                Matcher m = MSG.matcher(url);
                if (!m.matches()) return new RelayClient.Response(404, "{\"error\":\"not_found\"}");
                String[] hashes = boxes.get(m.group(1));
                if (hashes == null || bearer == null || !hashes[0].equals(RelayCrypto.sha256Hex(bearer))) {
                    return new RelayClient.Response(401, "{\"error\":\"unauthorized\"}");
                }
                TreeMap<Long, String> queue = queues.get(m.group(1));
                if ("/send".equals(m.group(2))) {
                    hashes[1] = new JSONObject(body).getString("sendHash");
                } else if ("/msg".equals(m.group(2)) && method.equals("GET")) {
                    long after = Long.parseLong(m.group(4));
                    StringBuilder msgs = new StringBuilder();
                    long next = after;
                    int count = 0;
                    for (Map.Entry<Long, String> e : queue.tailMap(after, false).entrySet()) {
                        if (count++ == AwayPoller.PAGE) break;
                        if (msgs.length() > 0) msgs.append(',');
                        msgs.append("{\"seq\":").append(e.getKey()).append(",\"c\":\"").append(e.getValue()).append("\",\"at\":1000}");
                        next = e.getKey();
                    }
                    return new RelayClient.Response(200, "{\"msgs\":[" + msgs + "],\"next\":" + next + "}");
                } else if ("/msg".equals(m.group(2))) {
                    queue.headMap(Long.parseLong(m.group(4)), true).clear();
                } else {
                    boxes.remove(m.group(1));
                    queues.remove(m.group(1));
                }
                return new RelayClient.Response(200, "{\"ok\":true}");
            } catch (org.json.JSONException e) {
                return new RelayClient.Response(400, "{\"error\":\"bad_json\"}");
            }
        }

        /** What a relative's phone does: seal with the invite's key and post with the send token. */
        void post(AwayBox box, String sendToken, String plaintext) throws Exception {
            String[] hashes = boxes.get(box.boxId());
            if (!hashes[1].equals(RelayCrypto.sha256Hex(sendToken))) throw new AssertionError("send token refused");
            queues.get(box.boxId()).put(++seq, RelayCrypto.seal(box.key(), box.boxId(), plaintext, new SecureRandom()));
        }

        void postRaw(AwayBox box, String wire) {
            queues.get(box.boxId()).put(++seq, wire);
        }
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private FakeRelay relay;
    private AwayBox box;
    private List<Event> saved;
    private EventStore.Added answer = EventStore.Added.OK;
    private int ids;
    private long now = 5000;
    private AwayPoller poller;

    @Before
    public void setUp() {
        relay = new FakeRelay();
        box = new AwayBox(new FakePrefs());
        saved = new ArrayList<>();
        poller = new AwayPoller(box, new RelayClient("https://relay.test", relay), () -> now, new SecureRandom(),
                () -> String.format("%012x", ++ids), e -> {
                    if (answer == EventStore.Added.OK) saved.add(e);
                    return answer;
                });
        box.setOn(true);
    }

    static String event(String from, String title, String date, String time, String who, String repeat, boolean remind) {
        try {
            return new JSONObject().put("v", 1).put("kind", "event").put("from", from).put("title", title)
                    .put("date", date).put("time", time).put("who", who).put("repeat", repeat)
                    .put("remind", remind).put("ts", 1).toString();
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String event(String title) {
        return event("Amna", title, "2026-11-03", "18:30", "", "none", true);
    }

    @Test
    public void offDoesNothing() {
        box.setOn(false);
        assertEquals(AwayPoller.STOP, poller.poll());
        assertTrue(relay.calls.isEmpty());
    }

    @Test
    public void firstPollCreatesTheBoxWithHashesOnly() {
        assertEquals(AwayPoller.INTERVAL_MS, poller.poll());
        assertTrue(box.created());
        assertEquals(AwayBox.STATUS_OK, box.status());
        assertEquals(now, box.lastContact());
        String[] hashes = relay.boxes.get(box.boxId());
        assertEquals(RelayCrypto.sha256Hex(box.readToken()), hashes[0]);
        assertEquals(RelayCrypto.sha256Hex(box.sendToken()), hashes[1]);
        assertNotEquals(box.readToken(), box.sendToken());
        assertEquals("POST /v1/box", relay.calls.get(0));
    }

    @Test
    public void eventsArriveMarkedAwayCleanedAndAreAcknowledged() throws Exception {
        poller.poll();
        relay.post(box, box.sendToken(), event("آمنة", "Eid​   dinner", "2026-11-03", "18:30", "Omar", "yearly", true));
        relay.post(box, box.sendToken(), event("Amna", "Call", "2026-11-04", "", "", "none", false));
        assertEquals(AwayPoller.INTERVAL_MS, poller.poll());
        assertEquals(2, saved.size());
        Event first = saved.get(0);
        assertTrue(first.away);
        assertEquals("Eid dinner", first.title);
        assertEquals("2026-11-03", first.date.toString());
        assertEquals("18:30", first.start.toString());
        assertEquals("Omar", first.who);
        assertEquals(Event.Repeat.YEARLY, first.repeat);
        assertEquals(Event.Reminder.AT_TIME, first.reminder);
        Event second = saved.get(1);
        assertTrue(second.allDay());
        assertEquals("Amna", second.who);
        assertEquals(Event.Reminder.NONE, second.reminder);
        assertNotEquals(first.id, second.id);
        assertEquals(2, box.lastSeq());
        assertTrue(relay.queues.get(box.boxId()).isEmpty());
        assertTrue(relay.calls.contains("DELETE /v1/box/" + box.boxId() + "/msg?upto=2"));
        poller.poll();
        assertEquals(2, saved.size());
        assertTrue(relay.calls.get(relay.calls.size() - 1).endsWith("/msg?after=2"));
    }

    @Test
    public void whatDoesNotOpenOrIsNotAnEventIsDroppedAndCounted() throws Exception {
        poller.poll();
        relay.postRaw(box, RelayCrypto.seal(RelayCrypto.newKey(new SecureRandom()), box.boxId(), event("x"), new SecureRandom()));
        relay.post(box, box.sendToken(), "{\"v\":1,\"kind\":\"note\",\"from\":\"x\",\"text\":\"y\"}");
        relay.post(box, box.sendToken(), event("x").replace("\"v\":1", "\"v\":2"));
        relay.post(box, box.sendToken(), event("Amna", "", "2026-11-03", "", "", "none", false));
        relay.post(box, box.sendToken(), event("Amna", "Bad date", "2026-13-03", "", "", "none", false));
        relay.post(box, box.sendToken(), event("Amna", "Bad time", "2026-11-03", "25:00", "", "none", false));
        relay.post(box, box.sendToken(), event("Amna", "Bad repeat", "2026-11-03", "", "", "daily", false));
        relay.post(box, box.sendToken(), event("​", "No sender", "2026-11-03", "", "", "none", false));
        relay.post(box, box.sendToken(), "not json");
        relay.post(box, box.sendToken(), event("kept"));
        poller.poll();
        assertEquals(1, saved.size());
        assertEquals("kept", saved.get(0).title);
        assertEquals(9, box.dropped());
        assertEquals(10, box.lastSeq());
        assertTrue(relay.queues.get(box.boxId()).isEmpty());
    }

    @Test
    public void whatTheTvCannotSaveStaysOnTheRelayForTheNextRound() throws Exception {
        poller.poll();
        relay.post(box, box.sendToken(), event("one"));
        relay.post(box, box.sendToken(), event("two"));
        answer = EventStore.Added.NOT_SAVED;
        assertEquals(AwayPoller.INTERVAL_MS, poller.poll());
        assertEquals(0, box.lastSeq());
        assertEquals(2, relay.queues.get(box.boxId()).size());
        for (String call : relay.calls) assertFalse(call, call.startsWith("DELETE"));
        answer = EventStore.Added.OK;
        poller.poll();
        assertEquals(2, saved.size());
        assertEquals(0, box.dropped());
        assertTrue(relay.queues.get(box.boxId()).isEmpty());
    }

    @Test
    public void aFullCalendarDropsAndCounts() throws Exception {
        poller.poll();
        relay.post(box, box.sendToken(), event("one"));
        answer = EventStore.Added.FULL;
        poller.poll();
        assertEquals(1, box.dropped());
        assertTrue(relay.queues.get(box.boxId()).isEmpty());
    }

    @Test
    public void savedThroughTheRealStoreAndReadBackAsAway() throws Exception {
        File file = new File(folder.getRoot(), "events.json");
        EventStore store = new EventStore(file, Runnable::run);
        poller = new AwayPoller(box, new RelayClient("https://relay.test", relay), () -> now, new SecureRandom(),
                () -> String.format("%012x", ++ids), store::add);
        poller.poll();
        relay.post(box, box.sendToken(), event("Visit"));
        poller.poll();
        List<Event> again = new EventStore(file, Runnable::run).events();
        assertEquals(1, again.size());
        assertTrue(again.get(0).away);
        assertEquals("Visit", again.get(0).title);
    }

    @Test
    public void aFullPageIsFollowedQuickly() throws Exception {
        poller.poll();
        for (int i = 0; i < AwayPoller.PAGE + 3; i++) relay.post(box, box.sendToken(), event("e" + i));
        assertEquals(AwayPoller.DRAIN_MS, poller.poll());
        assertEquals(AwayPoller.PAGE, saved.size());
        assertEquals(AwayPoller.INTERVAL_MS, poller.poll());
        assertEquals(AwayPoller.PAGE + 3, saved.size());
    }

    @Test
    public void anUnreachableRelayBacksOffToFifteenMinutesThenRecovers() {
        poller.poll();
        relay.down = true;
        long[] expected = {4, 8, 15, 15};
        for (long minutes : expected) assertEquals(minutes * 60_000L, poller.poll());
        assertEquals(AwayBox.STATUS_OFFLINE, box.status());
        relay.down = false;
        now = 9000;
        assertEquals(AwayPoller.INTERVAL_MS, poller.poll());
        assertEquals(AwayBox.STATUS_OK, box.status());
        assertEquals(9000, box.lastContact());
    }

    @Test
    public void rateLimitedOrServerErrorsBackOffToo() {
        poller.poll();
        relay.status = 429;
        assertEquals(4 * 60_000L, poller.poll());
        relay.status = 503;
        assertEquals(8 * 60_000L, poller.poll());
        assertEquals(AwayBox.STATUS_OFFLINE, box.status());
    }

    @Test
    public void setupThatCannotReachTheRelayStaysSettingUp() {
        relay.down = true;
        assertEquals(4 * 60_000L, poller.poll());
        assertFalse(box.created());
        assertEquals(AwayBox.STATUS_SETUP, box.status());
        String id = box.boxId();
        relay.down = false;
        poller.poll();
        assertTrue(box.created());
        assertEquals(id, box.boxId());
    }

    @Test
    public void aTakenBoxIdGetsNewSecrets() {
        box.generate(new SecureRandom());
        relay.boxes.put(box.boxId(), new String[]{"a", "b"});
        String taken = box.boxId();
        poller.poll();
        assertNotEquals(taken, box.boxId());
        poller.poll();
        assertTrue(box.created());
    }

    @Test
    public void aBoxTheRelayNoLongerKnowsStopsPollingAsGone() {
        poller.poll();
        relay.boxes.clear();
        assertEquals(AwayPoller.STOP, poller.poll());
        assertEquals(AwayBox.STATUS_GONE, box.status());
        int calls = relay.calls.size();
        assertEquals(AwayPoller.STOP, poller.poll());
        assertEquals(calls, relay.calls.size());
    }

    @Test
    public void aNewLinkStopsTheOldOne() throws Exception {
        poller.poll();
        String old = box.sendToken();
        assertTrue(poller.rotate());
        assertNotEquals(old, box.sendToken());
        try {
            relay.post(box, old, event("old link"));
            throw new IllegalStateException("old link still works");
        } catch (AssertionError expected) {
        }
        relay.post(box, box.sendToken(), event("new link"));
        poller.poll();
        assertEquals("new link", saved.get(0).title);
    }

    @Test
    public void aNewLinkThatCannotReachTheRelayKeepsTheOldOne() {
        poller.poll();
        String old = box.sendToken();
        relay.down = true;
        assertFalse(poller.rotate());
        assertEquals(old, box.sendToken());
    }

    @Test
    public void deleteRemovesTheBoxThereAndHere() {
        poller.poll();
        String id = box.boxId();
        relay.down = true;
        assertFalse(poller.delete());
        assertTrue(box.created());
        relay.down = false;
        assertTrue(poller.delete());
        assertFalse(relay.boxes.containsKey(id));
        assertFalse(box.created());
        assertFalse(box.on());
        assertEquals("", box.readToken());
    }

    @Test
    public void offKeepsTheBox() {
        poller.poll();
        String id = box.boxId();
        box.setOn(false);
        assertEquals(AwayPoller.STOP, poller.poll());
        box.setOn(true);
        assertEquals(id, box.boxId());
        assertTrue(box.created());
    }

    @Test
    public void theInviteCarriesTheSecretsInTheFragmentAndFitsAQrCode() {
        poller.poll();
        String invite = AwayPoller.invite("https://family-relay.example-household-name.workers.dev/", box, "ar");
        assertTrue(invite.startsWith("https://family-relay.example-household-name.workers.dev/f/#" + box.boxId() + "."));
        String[] parts = invite.substring(invite.indexOf('#') + 1).split("\\.");
        assertEquals(5, parts.length);
        assertEquals(box.sendToken(), parts[1]);
        assertEquals(box.key(), parts[2]);
        assertEquals("ar", parts[3]);
        assertEquals("event", parts[4]);
        assertFalse(invite.contains(box.readToken()));
        assertTrue(QrCode.encode(invite) != null);
    }
}
