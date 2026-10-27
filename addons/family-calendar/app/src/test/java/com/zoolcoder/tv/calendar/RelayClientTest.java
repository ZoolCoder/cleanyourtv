package com.zoolcoder.tv.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class RelayClientTest {
    static final String BOX = "EBESExQVFhcYGRobHB0eHw";
    static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    static final String HASH = RelayCrypto.sha256Hex("x");
    static final String WIRE = "oKGio6SlpqeoqaqrrK1wHdjasA8SBi-aq4YSmIBjkOXATe";

    /** Answers each call with the next queued response and keeps what was sent. */
    static final class Fake implements RelayClient.Transport {
        final Deque<Object> answers = new ArrayDeque<>();
        final List<String> sent = new ArrayList<>();
        final List<String> bearers = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();

        Fake answer(int status, String body) {
            answers.add(new RelayClient.Response(status, body));
            return this;
        }

        Fake fail() {
            answers.add(new IOException("down"));
            return this;
        }

        @Override
        public RelayClient.Response send(String method, String url, String bearer, String body) throws IOException {
            sent.add(method + " " + url);
            bearers.add(bearer);
            bodies.add(body);
            Object next = answers.poll();
            if (next instanceof IOException) throw (IOException) next;
            if (next == null) throw new IOException("nothing queued");
            return (RelayClient.Response) next;
        }
    }

    @Test
    public void createsABoxWithOnlyTheHashes() throws Exception {
        Fake fake = new Fake().answer(201, "{\"ok\":true}");
        new RelayClient("https://relay.test/", fake).createBox(BOX, HASH, RelayCrypto.sha256Hex("y"));
        assertEquals("POST https://relay.test/v1/box", fake.sent.get(0));
        assertNull(fake.bearers.get(0));
        JSONObject body = new JSONObject(fake.bodies.get(0));
        assertEquals(3, body.length());
        assertEquals(BOX, body.getString("boxId"));
        assertEquals(HASH, body.getString("readHash"));
    }

    @Test
    public void fetchesAcksRotatesAndDeletesWithTheReadToken() throws Exception {
        Fake fake = new Fake()
                .answer(200, "{\"msgs\":[{\"seq\":4,\"c\":\"" + WIRE + "\",\"at\":1700},{\"seq\":6,\"c\":\"" + WIRE + "\",\"at\":1800}],\"next\":6}")
                .answer(200, "{\"ok\":true}").answer(200, "{\"ok\":true}").answer(200, "{\"ok\":true}");
        RelayClient client = new RelayClient("https://relay.test", fake);
        RelayClient.Batch batch = client.fetch(BOX, TOKEN, 3);
        assertEquals(2, batch.messages.size());
        assertEquals(6, batch.next);
        assertEquals(4, batch.messages.get(0).seq);
        assertEquals(WIRE, batch.messages.get(1).sealed);
        client.ack(BOX, TOKEN, 6);
        client.rotateSend(BOX, TOKEN, HASH);
        client.deleteBox(BOX, TOKEN);
        assertEquals("GET https://relay.test/v1/box/" + BOX + "/msg?after=3", fake.sent.get(0));
        assertEquals("DELETE https://relay.test/v1/box/" + BOX + "/msg?upto=6", fake.sent.get(1));
        assertEquals("PUT https://relay.test/v1/box/" + BOX + "/send", fake.sent.get(2));
        assertEquals("DELETE https://relay.test/v1/box/" + BOX, fake.sent.get(3));
        for (String bearer : fake.bearers) assertEquals(TOKEN, bearer);
    }

    @Test
    public void statusCodesMapToKinds() {
        int[] codes = {401, 404, 409, 429, 500, 400};
        RelayClient.RelayException.Kind[] kinds = {RelayClient.RelayException.Kind.UNAUTHORIZED,
                RelayClient.RelayException.Kind.NOT_FOUND, RelayClient.RelayException.Kind.CONFLICT,
                RelayClient.RelayException.Kind.RATE_LIMITED, RelayClient.RelayException.Kind.SERVER,
                RelayClient.RelayException.Kind.BAD_RESPONSE};
        for (int i = 0; i < codes.length; i++) {
            Fake fake = new Fake().answer(codes[i], "{\"error\":\"x\"}");
            assertEquals(kinds[i], kindOf(() -> new RelayClient("https://relay.test", fake).ack(BOX, TOKEN, 1)));
        }
        assertEquals(RelayClient.RelayException.Kind.NETWORK,
                kindOf(() -> new RelayClient("https://relay.test", new Fake().fail()).ack(BOX, TOKEN, 1)));
        assertTrue(new RelayClient.RelayException(RelayClient.RelayException.Kind.UNAUTHORIZED, "").gone());
        assertFalse(new RelayClient.RelayException(RelayClient.RelayException.Kind.NETWORK, "").gone());
    }

    @Test
    public void oddAnswersAreRefused() {
        String[] bad = {
            "not json",
            "{\"msgs\":[],\"next\":\"3\"}",
            "{\"msgs\":[{\"seq\":2,\"c\":\"" + WIRE + "\",\"at\":1}],\"next\":2}",
            "{\"msgs\":[{\"seq\":5,\"c\":\"short\",\"at\":1}],\"next\":5}",
            "{\"msgs\":[{\"seq\":5,\"c\":\"" + WIRE + "\",\"at\":1.5}],\"next\":5}",
            "{\"msgs\":[{\"seq\":5,\"c\":\"" + WIRE + "\",\"at\":1}],\"next\":4}",
            "{\"msgs\":[{\"seq\":-1,\"c\":\"" + WIRE + "\",\"at\":1}],\"next\":4}",
        };
        for (String body : bad) {
            Fake fake = new Fake().answer(200, body);
            assertEquals(body, RelayClient.RelayException.Kind.BAD_RESPONSE,
                    kindOf(() -> new RelayClient("https://relay.test", fake).fetch(BOX, TOKEN, 3)));
        }
    }

    @Test
    public void onlyHttpsRelaysAndWellFormedIdsAreUsed() {
        assertFalse(RelayClient.usable(""));
        assertFalse(RelayClient.usable("http://relay.test"));
        assertFalse(RelayClient.usable("https://user@relay.test"));
        assertFalse(RelayClient.usable("https://relay.test/?a=1"));
        assertTrue(RelayClient.usable("https://relay.test"));
        try {
            new RelayClient("http://relay.test", new Fake());
            fail();
        } catch (IllegalArgumentException expected) {
        }
        Fake fake = new Fake();
        try {
            new RelayClient("https://relay.test", fake).fetch("../x", TOKEN, 0);
            fail();
        } catch (IllegalArgumentException | RelayClient.RelayException expected) {
        }
        assertTrue(fake.sent.isEmpty());
    }

    @Test
    public void answersOverTheCapAreRefused() throws Exception {
        assertEquals("ok", RelayClient.readCapped(new ByteArrayInputStream("ok".getBytes())));
        try {
            RelayClient.readCapped(new ByteArrayInputStream(new byte[RelayClient.MAX_RESPONSE + 1]));
            fail();
        } catch (IOException expected) {
        }
    }

    interface Call {
        void run() throws RelayClient.RelayException;
    }

    static RelayClient.RelayException.Kind kindOf(Call call) {
        try {
            call.run();
        } catch (RelayClient.RelayException e) {
            return e.kind;
        }
        throw new AssertionError("no error");
    }
}
