package com.zoolcoder.tv.notes;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * What the server answers: GET / is the static page, POST /note takes a note, everything else is 404.
 * The page is never built from user input. The order of checks for a note is: form type, lockout and PIN,
 * then the fields, so a wrong PIN learns nothing about the field rules.
 */
final class NoteEndpoint {
    private static final Pattern IP_HOST =
            Pattern.compile("(\\d{1,3}(\\.\\d{1,3}){3}|\\[[0-9A-Fa-f:.]+\\])(:\\d{1,5})?");

    private final String page;
    private final Supplier<String> pin;
    private final PinGuard guard;
    private final LongSupplier wallClock;
    private final Consumer<Note> sink;

    NoteEndpoint(String page, Supplier<String> pin, PinGuard guard, LongSupplier wallClock, Consumer<Note> sink) {
        this.page = page;
        this.pin = pin;
        this.guard = guard;
        this.wallClock = wallClock;
        this.sink = sink;
    }

    Response handle(HttpRequest request) {
        if (!ipHost(request.headers.get("host"))) return Response.text(403, "open the address shown on the TV");
        if ("GET".equals(request.method) && "/".equals(request.path)) return Response.page(page);
        if ("POST".equals(request.method) && "/note".equals(request.path)) return receive(request);
        return Response.text(404, "not found");
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

    private Response receive(HttpRequest request) {
        if (!sameOrigin(request.headers)) return Response.text(403, "cross-site request refused");
        String type = request.headers.get("content-type");
        if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            return Response.text(415, "form data only");
        }
        Map<String, String> form = Form.parse(request.body);
        if (form == null) return Response.text(400, "bad form");
        switch (guard.check(form.get("pin"), pin.get())) {
            case LOCKED:
                return Response.text(429, "too many wrong PINs, try again later");
            case WRONG:
                return Response.text(403, "wrong PIN");
            default:
                break;
        }
        String name = Sanitizer.name(form.get("name"));
        String text = Sanitizer.text(form.get("text"));
        if (name == null || text == null) return Response.text(400, "name or note missing or too long");
        sink.accept(new Note(name, text, wallClock.getAsLong()));
        return Response.text(200, "ok");
    }
}
