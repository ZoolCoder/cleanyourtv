package com.zoolcoder.tv.health;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** The routes; host, origin, form type and PIN checked before the fields; changes saved, reads not. */
public class PhoneEndpointTest {
    private static final String PAGE = "<html>static page</html>";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String HOST = "192.168.1.50:8767";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 9, 0);

    private final AtomicLong clock = new AtomicLong(1_000);
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicInteger saves = new AtomicInteger();
    private final Records records = new Records();
    private final PhoneEndpoint endpoint = new PhoneEndpoint(PAGE, () -> "123456", new PinGuard(clock::get),
            (action, change) -> {
                Response r = action.apply(records);
                if (change && r.status == 200) saves.incrementAndGet();
                return r;
            }, () -> NOW, () -> String.format("%012x", ids.incrementAndGet()));

    private static HttpRequest request(String method, String path, String type, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("host", HOST);
        if (type != null) headers.put("content-type", type);
        return new HttpRequest(method, path, headers, body);
    }

    private Response post(String path, String body) {
        return endpoint.handle(request("POST", path, FORM, body));
    }

    @Test
    public void getRootIsTheStaticPage() {
        Response r = endpoint.handle(request("GET", "/", null, ""));
        assertEquals(200, r.status);
        assertEquals(PAGE, r.body);
        assertTrue(new String(r.toBytes()).contains("Content-Security-Policy"));
    }

    @Test
    public void everythingElseIs404() {
        assertEquals(404, endpoint.handle(request("GET", "/list", null, "")).status);
        assertEquals(404, endpoint.handle(request("GET", "/etc/passwd", null, "")).status);
        assertEquals(404, endpoint.handle(request("PUT", "/person", FORM, "")).status);
        assertEquals(404, endpoint.handle(request("HEAD", "/", null, "")).status);
        assertEquals(404, post("/", "pin=123456").status);
        assertEquals(404, post("/take", "pin=123456").status);
    }

    @Test
    public void addsAFamilyAndListsIt() throws Exception {
        assertEquals(200, post("/person", "name=Grandma&color=2&pin=123456").status);
        String gm = records.members.get(0).id;
        assertEquals(200, post("/medicine", "member=" + gm + "&name=Metformin&times=08:00,20:00&left=30"
                + "&pin=123456").status);
        assertEquals(200, post("/visit", "member=" + gm + "&doctor=Dr.+Amal&day=2026-11-22&time=11:30"
                + "&pin=123456").status);
        assertEquals(3, saves.get());

        Response r = post("/list", "pin=123456");
        assertEquals(200, r.status);
        assertTrue(r.contentType.startsWith("application/json"));
        JSONObject json = new JSONObject(r.body);
        assertEquals(1, json.getJSONArray("members").length());
        assertEquals("Metformin", json.getJSONArray("medicines").getJSONObject(0).getString("name"));
        assertEquals("Dr. Amal", json.getJSONArray("visits").getJSONObject(0).getString("doctor"));
        assertEquals("a read is not saved", 3, saves.get());

        String pill = records.medicines.get(0).id;
        assertEquals(200, post("/refill", "id=" + pill + "&left=60&pin=123456").status);
        assertEquals(60, records.medicines.get(0).left);
        assertEquals(200, post("/delete", "kind=person&id=" + gm + "&pin=123456").status);
        assertTrue(records.members.isEmpty());
        assertTrue(records.medicines.isEmpty());
        assertEquals(5, saves.get());
    }

    @Test
    public void aRefusedChangeIsNotSaved() {
        assertEquals(400, post("/person", "name=&pin=123456").status);
        assertEquals(400, post("/medicine", "member=000000000001&name=Pill&times=08:00&pin=123456").status);
        assertEquals(0, saves.get());
    }

    @Test
    public void theWrongPinLearnsNothingAboutTheFields() {
        assertEquals(403, post("/person", "name=&pin=000000").status);
        assertEquals(403, post("/list", "").status);
        assertTrue(records.members.isEmpty());
    }

    @Test
    public void fiveWrongPinsLockThePageOut() {
        for (int i = 0; i < 5; i++) assertEquals(403, post("/list", "pin=000000").status);
        assertEquals(429, post("/list", "pin=123456").status);
        assertEquals(429, post("/person", "name=Sara&pin=123456").status);
        assertTrue(records.members.isEmpty());
    }

    @Test
    public void onlyFormDataIsAccepted() {
        assertEquals(415, endpoint.handle(request("POST", "/person", "application/json", "{}")).status);
        assertEquals(415, endpoint.handle(request("POST", "/person", null, "name=Sara&pin=123456")).status);
        assertEquals(415, endpoint.handle(request("POST", "/person", "text/plain", "name=Sara&pin=123456")).status);
        assertTrue(records.members.isEmpty());
    }

    @Test
    public void anotherSiteCannotPost() {
        HttpRequest cross = request("POST", "/person", FORM, "name=Sara&pin=123456");
        cross.headers.put("origin", "http://evil.example");
        assertEquals(403, endpoint.handle(cross).status);
        HttpRequest fetchSite = request("POST", "/person", FORM, "name=Sara&pin=123456");
        fetchSite.headers.put("sec-fetch-site", "cross-site");
        assertEquals(403, endpoint.handle(fetchSite).status);
        HttpRequest same = request("POST", "/person", FORM, "name=Sara&pin=123456");
        same.headers.put("origin", "http://" + HOST);
        same.headers.put("sec-fetch-site", "same-origin");
        assertEquals(200, endpoint.handle(same).status);
    }

    @Test
    public void aNameForTheTvIsRefused() {
        HttpRequest rebound = request("GET", "/", null, "");
        rebound.headers.put("host", "tv.evil.example:8767");
        assertEquals(403, endpoint.handle(rebound).status);
        assertFalse(PhoneEndpoint.ipHost("localhost"));
        assertTrue(PhoneEndpoint.ipHost("[fe80::1]:8767"));
    }
}
