package com.zoolcoder.tv.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** The routes; origin, form type and PIN checked before the fields; fixed answers; /list as JSON. */
public class CalendarEndpointTest {
    private static final String PAGE = "<html>static page</html>";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final AtomicLong clock = new AtomicLong(1_000);
    private final AtomicInteger ids = new AtomicInteger();
    private EventStore store;
    private CalendarEndpoint endpoint;

    private CalendarEndpoint endpoint() {
        if (endpoint == null) {
            store = new EventStore(new File(folder.getRoot(), EventStore.FILE));
            endpoint = new CalendarEndpoint(PAGE, () -> "123456", new PinGuard(clock::get), store, () -> TODAY,
                    () -> String.format("%012x", ids.incrementAndGet()));
        }
        return endpoint;
    }

    private static HttpRequest request(String method, String path, String type, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("host", "192.168.1.50:8766");
        if (type != null) headers.put("content-type", type);
        return new HttpRequest(method, path, headers, body);
    }

    private Response post(String path, String body) {
        return endpoint().handle(request("POST", path, FORM, body));
    }

    @Test
    public void getRootIsTheStaticPage() {
        Response r = endpoint().handle(request("GET", "/", null, ""));
        assertEquals(200, r.status);
        assertEquals(PAGE, r.body);
        assertTrue(new String(r.toBytes()).contains("Content-Security-Policy"));
    }

    @Test
    public void everythingElseIs404() {
        assertEquals(404, endpoint().handle(request("GET", "/add", null, "")).status);
        assertEquals(404, endpoint().handle(request("GET", "/list", null, "")).status);
        assertEquals(404, post("/", "pin=123456").status);
        assertEquals(404, post("/note", "pin=123456").status);
        assertEquals(404, endpoint().handle(request("GET", "/etc/passwd", null, "")).status);
        assertEquals(404, endpoint().handle(request("PUT", "/add", FORM, "")).status);
        assertEquals(404, endpoint().handle(request("HEAD", "/", null, "")).status);
    }

    @Test
    public void addsAnEventAndListsIt() throws Exception {
        assertEquals(200, post("/add", "title=Dentist&date=2026-10-27&start=10:00&end=11:00&who=Mom&color=2"
                + "&repeat=none&reminder=10m&pin=123456").status);
        assertEquals(200, post("/add", "title=%D8%B9%D9%8A%D8%AF&date=2026-10-26&allday=1&pin=123456").status);
        Response r = post("/list", "pin=123456");
        assertEquals(200, r.status);
        assertTrue(r.contentType.startsWith("application/json"));
        JSONObject root = new JSONObject(r.body);
        assertEquals("2026-10-26", root.getString("today"));
        JSONArray events = root.getJSONArray("events");
        assertEquals(2, events.length());
        assertEquals("عيد", events.getJSONObject(0).getString("title"));
        assertEquals("2026-10-26", events.getJSONObject(0).getString("day"));
        JSONObject dentist = events.getJSONObject(1);
        assertEquals("Dentist", dentist.getString("title"));
        assertEquals("10:00", dentist.getString("start"));
        assertEquals("11:00", dentist.getString("end"));
        assertEquals("Mom", dentist.getString("who"));
        assertEquals(2, dentist.getInt("color"));
        assertEquals("10m", dentist.getString("reminder"));
        assertEquals("000000000001", dentist.getString("id"));
    }

    @Test
    public void listCoversFourteenDaysWithRepeats() throws Exception {
        post("/add", "title=Football&date=2026-10-20&start=17:00&repeat=weekly&pin=123456");
        post("/add", "title=Later&date=2026-11-09&pin=123456");
        post("/add", "title=Past&date=2026-10-25&pin=123456");
        JSONArray events = new JSONObject(post("/list", "pin=123456").body).getJSONArray("events");
        assertEquals(2, events.length());
        assertEquals("2026-10-27", events.getJSONObject(0).getString("day"));
        assertEquals("2026-11-03", events.getJSONObject(1).getString("day"));
    }

    @Test
    public void deletesById() {
        post("/add", "title=x&date=2026-10-27&pin=123456");
        assertEquals(400, post("/delete", "id=../../x&pin=123456").status);
        assertEquals(400, post("/delete", "pin=123456").status);
        assertEquals(404, post("/delete", "id=ffffffffffff&pin=123456").status);
        assertEquals(200, post("/delete", "id=000000000001&pin=123456").status);
        assertTrue(store.events().isEmpty());
    }

    @Test
    public void wrongPinIs403OnEveryRoute() {
        assertEquals(403, post("/add", "title=x&date=2026-10-27&pin=000000").status);
        assertEquals(403, post("/add", "title=x&date=2026-10-27").status);
        assertEquals(403, post("/list", "pin=000000").status);
        assertEquals(403, post("/delete", "id=000000000001&pin=").status);
        assertTrue(store.events().isEmpty());
    }

    @Test
    public void fiveWrongPinsAnswer429EvenForTheRightPin() {
        for (int i = 0; i < 5; i++) assertEquals(403, post("/list", "pin=000000").status);
        assertEquals(429, post("/add", "title=x&date=2026-10-27&pin=123456").status);
        assertTrue(store.events().isEmpty());
        clock.addAndGet(PinGuard.LOCKOUT_MS);
        assertEquals(200, post("/add", "title=x&date=2026-10-27&pin=123456").status);
    }

    @Test
    public void aLockedPhoneDoesNotLockTheOthers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("host", "192.168.1.50:8766");
        headers.put("content-type", FORM);
        for (int i = 0; i < 5; i++) {
            endpoint().handle(new HttpRequest("POST", "/list", headers, "pin=000000", "192.168.1.66"));
        }
        assertEquals(429, endpoint().handle(new HttpRequest("POST", "/list", headers, "pin=123456", "192.168.1.66")).status);
        assertEquals(200, endpoint().handle(new HttpRequest("POST", "/list", headers, "pin=123456", "192.168.1.67")).status);
    }

    @Test
    public void anEventTheTvCannotSaveIs500() {
        endpoint = new CalendarEndpoint(PAGE, () -> "123456", new PinGuard(clock::get),
                new EventStore(new File(folder.getRoot(), "missing/" + EventStore.FILE), Runnable::run), () -> TODAY,
                () -> String.format("%012x", ids.incrementAndGet()));
        Response r = post("/add", "title=x&date=2026-10-27&pin=123456");
        assertEquals(500, r.status);
        assertFalse(r.body.contains("missing"));
    }

    @Test
    public void thePinIsCheckedBeforeTheFields() {
        assertEquals(403, post("/add", "title=&date=nope&pin=000000").status);
        assertEquals(400, post("/add", "title=&date=nope&pin=123456").status);
    }

    @Test
    public void badFieldsAre400WithAFixedAnswer() {
        Response r = post("/add", "title=%3Cscript%3E&date=2026-02-30&pin=123456");
        assertEquals(400, r.status);
        assertFalse(r.body.contains("script"));
        assertFalse(r.body.contains("2026"));
    }

    @Test
    public void aFullCalendarIs409() {
        endpoint();
        for (int i = 0; i < EventStore.MAX_EVENTS; i++) {
            store.add(new Event(String.format("f%011x", i), "x", TODAY, null, null, null, 0, Event.Repeat.NONE,
                    Event.Reminder.NONE));
        }
        assertEquals(409, post("/add", "title=x&date=2026-10-27&pin=123456").status);
    }

    @Test
    public void formDataOnly() {
        assertEquals(415, endpoint().handle(request("POST", "/add", "application/json", "{}")).status);
        assertEquals(415, endpoint().handle(request("POST", "/list", null, "pin=123456")).status);
        assertEquals(415, endpoint().handle(request("POST", "/add", "text/plain", "pin=123456")).status);
    }

    @Test
    public void refusesHostNamesAgainstDnsRebinding() {
        HttpRequest r = request("GET", "/", null, "");
        r.headers.put("host", "evil.example:8766");
        assertEquals(403, endpoint().handle(r).status);
        assertTrue(CalendarEndpoint.ipHost("192.168.1.50:8766"));
        assertTrue(CalendarEndpoint.ipHost("[fe80::1]:8766"));
        assertTrue(CalendarEndpoint.ipHost(null));
        assertFalse(CalendarEndpoint.ipHost("tv.local"));
        assertFalse(CalendarEndpoint.ipHost("192.168.1.50.evil.example"));
    }

    @Test
    public void refusesCrossSitePosts() {
        HttpRequest r = request("POST", "/add", FORM, "title=x&date=2026-10-27&pin=123456");
        r.headers.put("origin", "http://evil.example");
        assertEquals(403, endpoint().handle(r).status);
        HttpRequest site = request("POST", "/list", FORM, "pin=123456");
        site.headers.put("sec-fetch-site", "cross-site");
        assertEquals(403, endpoint().handle(site).status);
        HttpRequest same = request("POST", "/list", FORM, "pin=123456");
        same.headers.put("origin", "http://192.168.1.50:8766");
        same.headers.put("sec-fetch-site", "same-origin");
        assertEquals(200, endpoint().handle(same).status);
        assertTrue(store.events().isEmpty());
    }
}
