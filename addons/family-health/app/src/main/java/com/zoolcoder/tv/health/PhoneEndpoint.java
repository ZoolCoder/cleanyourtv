package com.zoolcoder.tv.health;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * What the phone server answers: GET / is the static page; POST /list, /person, /medicine, /visit, /refill and
 * /delete read or change the records; everything else is 404. The page is never built from user input, and the
 * answers are fixed words, except /list, which is JSON. A POST is checked in this order: origin, form type,
 * lockout and PIN, then the fields, so a wrong PIN learns nothing about the field rules.
 */
final class PhoneEndpoint {
    private static final Pattern IP_HOST =
            Pattern.compile("(\\d{1,3}(\\.\\d{1,3}){3}|\\[[0-9A-Fa-f:.]+\\])(:\\d{1,5})?");

    /** Runs a read or a change where the records live; a change that answers 200 is saved. */
    interface Access {
        Response apply(Function<Records, Response> action, boolean change);
    }

    private final String page;
    private final Supplier<String> pin;
    private final PinGuard guard;
    private final Access records;
    private final Supplier<LocalDateTime> now;
    private final Supplier<String> ids;

    PhoneEndpoint(String page, Supplier<String> pin, PinGuard guard, Access records, Supplier<LocalDateTime> now,
                  Supplier<String> ids) {
        this.page = page;
        this.pin = pin;
        this.guard = guard;
        this.records = records;
        this.now = now;
        this.ids = ids;
    }

    Response handle(HttpRequest request) {
        if (!ipHost(request.headers.get("host"))) return Response.text(403, "open the address shown on the TV");
        if ("GET".equals(request.method) && "/".equals(request.path)) return Response.page(page);
        if (!"POST".equals(request.method)) return Response.text(404, "not found");
        switch (request.path) {
            case "/list":
            case "/person":
            case "/medicine":
            case "/visit":
            case "/refill":
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
        LocalDateTime at = now.get();
        switch (request.path) {
            case "/person": {
                String id = ids.get();
                return records.apply(r -> Edits.person(r, form, id), true);
            }
            case "/medicine": {
                String id = ids.get();
                return records.apply(r -> Edits.medicine(r, form, id, at.toLocalDate()), true);
            }
            case "/visit": {
                String id = ids.get();
                return records.apply(r -> Edits.visit(r, form, id, at.toLocalDate()), true);
            }
            case "/refill":
                return records.apply(r -> Edits.refill(r, form), true);
            case "/delete":
                return records.apply(r -> Edits.delete(r, form), true);
            default:
                return records.apply(r -> Edits.list(r, at), false);
        }
    }
}
