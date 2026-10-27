package com.zoolcoder.tv.notes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

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

    private FakeRelay relay;
    private AwayBox box;
    private List<Note> shown;
    private long now = 5000;
    private AwayPoller poller;

    @Before
    public void setUp() {
        relay = new FakeRelay();
        box = new AwayBox(new FakePrefs());
        shown = new ArrayList<>();
        poller = new AwayPoller(box, new RelayClient("https://relay.test", relay), () -> now, new SecureRandom(), shown::add);
        box.setOn(true);
    }

    static String note(String from, String text) {
        try {
            return new JSONObject().put("v", 1).put("kind", "note").put("from", from).put("text", text).put("ts", 1).toString();
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
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
    public void notesArriveTaggedCleanedAndAreAcknowledged() throws Exception {
        poller.poll();
        relay.post(box, box.sendToken(), note("آمنة", "Salam‏ from   Riyadh"));
        relay.post(box, box.sendToken(), note("Omar", "Second"));
        assertEquals(AwayPoller.INTERVAL_MS, poller.poll());
        assertEquals(2, shown.size());
        assertTrue(shown.get(0).away);
        assertEquals("آمنة", shown.get(0).name);
        assertEquals("Salam from Riyadh", shown.get(0).text);
        assertEquals(1000, shown.get(0).time);
        assertEquals(2, box.lastSeq());
        assertTrue(relay.queues.get(box.boxId()).isEmpty());
        assertTrue(relay.calls.contains("DELETE /v1/box/" + box.boxId() + "/msg?upto=2"));
        poller.poll();
        assertEquals(2, shown.size());
        assertTrue(relay.calls.get(relay.calls.size() - 1).endsWith("/msg?after=2"));
    }

    @Test
    public void whatDoesNotOpenOrIsNotANoteIsDroppedAndCounted() throws Exception {
        poller.poll();
        relay.postRaw(box, RelayCrypto.seal(RelayCrypto.newKey(new SecureRandom()), box.boxId(), note("x", "y"), new SecureRandom()));
        relay.post(box, box.sendToken(), "{\"v\":1,\"kind\":\"event\",\"from\":\"x\",\"title\":\"y\"}");
        relay.post(box, box.sendToken(), "{\"v\":2,\"kind\":\"note\",\"from\":\"x\",\"text\":\"y\"}");
        relay.post(box, box.sendToken(), "{\"v\":1,\"kind\":\"note\",\"from\":\"x\",\"text\":5}");
        relay.post(box, box.sendToken(), note("x", ""));
        relay.post(box, box.sendToken(), "not json");
        relay.post(box, box.sendToken(), note("Amna", "kept"));
        poller.poll();
        assertEquals(1, shown.size());
        assertEquals("kept", shown.get(0).text);
        assertEquals(6, box.dropped());
        assertEquals(7, box.lastSeq());
        assertTrue(relay.queues.get(box.boxId()).isEmpty());
    }

    @Test
    public void aFullPageIsFollowedQuickly() throws Exception {
        poller.poll();
        for (int i = 0; i < AwayPoller.PAGE + 3; i++) relay.post(box, box.sendToken(), note("A", "n" + i));
        assertEquals(AwayPoller.DRAIN_MS, poller.poll());
        assertEquals(AwayPoller.PAGE, shown.size());
        assertEquals(AwayPoller.INTERVAL_MS, poller.poll());
        assertEquals(AwayPoller.PAGE + 3, shown.size());
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
    public void setupThatCannotReachTheRelaySaysSoAndRetries() {
        relay.down = true;
        assertEquals(4 * 60_000L, poller.poll());
        assertFalse(box.created());
        assertEquals(AwayBox.STATUS_OFFLINE, box.status());
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
        box.setOn(false);
        box.setOn(true);
        assertFalse(box.created());
        poller.poll();
        assertTrue(box.created());
    }

    @Test
    public void aNewLinkStopsTheOldOne() throws Exception {
        poller.poll();
        String old = box.sendToken();
        assertTrue(poller.rotate());
        assertNotEquals(old, box.sendToken());
        try {
            relay.post(box, old, note("A", "old link"));
            throw new IllegalStateException("old link still works");
        } catch (AssertionError expected) {
        }
        relay.post(box, box.sendToken(), note("A", "new link"));
        poller.poll();
        assertEquals("new link", shown.get(0).text);
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
}
