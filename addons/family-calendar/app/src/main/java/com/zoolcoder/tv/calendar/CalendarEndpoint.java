package com.zoolcoder.tv.calendar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * What the server answers: GET / is the static page; POST /add, /list and /delete change or read the
 * calendar; everything else is 404. The page is never built from user input, and the answers are fixed words,
 * except /list, which is JSON. The order of checks for a POST is: origin, form type, lockout and PIN, then the
 * fields, so a wrong PIN learns nothing about the field rules.
 */
final class CalendarEndpoint {
    static final int LIST_DAYS = 14;
    static final int LIST_MAX = 200;
    private static final Pattern IP_HOST =
            Pattern.compile("(\\d{1,3}(\\.\\d{1,3}){3}|\\[[0-9A-Fa-f:.]+\\])(:\\d{1,5})?");
    private static final Pattern ID = Pattern.compile("[0-9a-f]{12}");

    private final String page;
    private final Supplier<String> pin;
    private final PinGuard guard;
    private final EventStore store;
    private final Supplier<LocalDate> today;
    private final Supplier<String> ids;

    CalendarEndpoint(String page, Supplier<String> pin, PinGuard guard, EventStore store, Supplier<LocalDate> today,
                     Supplier<String> ids) {
        this.page = page;
        this.pin = pin;
        this.guard = guard;
        this.store = store;
        this.today = today;
        this.ids = ids;
    }

    Response handle(HttpRequest request) {
        if (!ipHost(request.headers.get("host"))) return Response.text(403, "open the address shown on the TV");
        if ("GET".equals(request.method) && "/".equals(request.path)) return Response.page(page);
        if (!"POST".equals(request.method)) return Response.text(404, "not found");
        switch (request.path) {
            case "/add":
            case "/list":
            case "/delete":
                return guarded(request);
            default:
                return Response.text(404, "not found");
        }
    }

    /**
     * The TV shows its address as an IP, so a browser's Host is one. A name here means DNS rebinding: a site
     * that pointed its own name at the TV, whose Origin then matches its Host and would pass sameOrigin.
     */
    static boolean ipHost(String host) {
        return host == null || IP_HOST.matcher(host.trim()).matches();
    }

    /**
     * A page on another site must not be able to post here, even from a phone on the home network: refuse a
     * browser that says the request is cross-site, or whose Origin is not this very host. Clients that send
     * neither header (curl, old browsers) pass; the PIN still guards them.
     */
    static boolean sameOrigin(Map<String, String> headers) {
        String site = headers.get("sec-fetch-site");
        if (site != null && "cross-site".equalsIgnoreCase(site.trim())) return false;
        String origin = headers.get("origin");
        if (origin == null) return true;
        String host = headers.get("host");
        return host != null && !host.isEmpty() && ("http://" + host).equalsIgnoreCase(origin.trim());
    }

    private Response guarded(HttpRequest request) {
        if (!sameOrigin(request.headers)) return Response.text(403, "cross-site request refused");
        String type = request.headers.get("content-type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            return Response.text(415, "form data only");
        }
        Map<String, String> form = Form.parse(request.body);
        if (form == null) return Response.text(400, "bad form");
        switch (guard.check(request.peer, form.get("pin"), pin.get())) {
            case LOCKED:
                return Response.text(429, "too many wrong PINs, try again later");
            case WRONG:
                return Response.text(403, "wrong PIN");
            default:
                break;
        }
        switch (request.path) {
            case "/add":
                return add(form);
            case "/delete":
                return delete(form.get("id"));
            default:
                return list();
        }
    }

    private Response add(Map<String, String> form) {
        Event event;
        try {
            event = Event.fromForm(ids.get(), form);
        } catch (Event.Invalid e) {
            return Response.text(400, "a field is missing or not valid");
        }
        switch (store.add(event)) {
            case FULL:
                return Response.text(409, "the calendar is full");
            case NOT_SAVED:
                return Response.text(500, "the TV could not save it, try again");
            default:
                return Response.text(200, "ok");
        }
    }

    private Response delete(String id) {
        if (id == null || !ID.matcher(id).matches()) return Response.text(400, "a field is missing or not valid");
        if (!store.delete(id)) return Response.text(404, "not found");
        return Response.text(200, "ok");
    }

    /** The next 14 days from today, one entry per occurrence. The page shows the strings as text only. */
    private Response list() {
        LocalDate from = today.get();
        try {
            JSONArray items = new JSONArray();
            for (Occurrence o : Agenda.between(store.events(), from, LIST_DAYS, LIST_MAX)) {
                JSONObject item = EventStore.toJson(o.event).put("day", o.day.toString());
                items.put(item);
            }
            return Response.json(new JSONObject().put("today", from.toString()).put("events", items).toString());
        } catch (JSONException e) {
            return Response.text(503, "busy");
        }
    }
}
