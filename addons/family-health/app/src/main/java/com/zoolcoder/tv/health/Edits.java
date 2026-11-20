package com.zoolcoder.tv.health;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a phone may change, checked field by field: add a person, a medicine or an appointment, refill a pack,
 * delete one of them, and read the list. Each method runs where the records live and answers with fixed words,
 * except the list, which is JSON the page shows as text only. Adding the first person removes the example family.
 */
final class Edits {
    static final int MAX_MEMBERS = 12, MAX_MEDICINES = 40, MAX_VISITS = 60;
    static final int MAX_DAYS = 365, MAX_PER_DOSE = 20, MAX_PACK = 9999;
    static final Pattern ID = Pattern.compile("[0-9a-f]{12}");
    private static final Pattern TIME = Pattern.compile("\\d{2}:\\d{2}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern COUNT = Pattern.compile("\\d{1,4}");

    private Edits() {
    }

    static Response person(Records r, Map<String, String> form, String id) {
        String name = Sanitizer.name(form.get("name"));
        if (name == null) return invalid();
        if (r.example) r.clear();
        if (r.members.size() >= MAX_MEMBERS) return full();
        String c = form.get("color");
        int color = c != null && c.matches("[0-5]") ? c.charAt(0) - '0' : r.members.size();
        r.members.add(new Member(id, name, color));
        return ok();
    }

    static Response medicine(Records r, Map<String, String> form, String id, LocalDate today) {
        Member who = member(r, form.get("member"));
        String name = Sanitizer.title(form.get("name"));
        String dose = Sanitizer.optional(form.get("dose"), Sanitizer.MAX_NAME);
        String how = Sanitizer.optional(form.get("how"), Sanitizer.MAX_SHORT);
        List<LocalTime> times = times(form.get("times"));
        LocalDate start = blank(form.get("start")) ? today : date(form.get("start"), today.minusDays(MAX_DAYS), today.plusDays(MAX_DAYS));
        int days = count(form.get("days"), 0, 1, MAX_DAYS);
        int per = count(form.get("per"), 1, 1, MAX_PER_DOSE);
        int left = count(form.get("left"), -1, 0, MAX_PACK);
        if (who == null || name == null || dose == null || how == null || times == null || start == null
                || days < 0 || per < 0 || left < -1) {
            return invalid();
        }
        if (r.medicines.size() >= MAX_MEDICINES) return full();
        LocalDate end = days == 0 ? null : start.plusDays(days - 1);
        r.medicines.add(new Medicine(id, who.id, name, dose, how, times, start, end, per, left));
        return ok();
    }

    static Response visit(Records r, Map<String, String> form, String id, LocalDate today) {
        Member who = member(r, form.get("member"));
        String doctor = Sanitizer.title(form.get("doctor"));
        String specialty = Sanitizer.optional(form.get("specialty"), Sanitizer.MAX_SHORT);
        String place = Sanitizer.optional(form.get("place"), Sanitizer.MAX_TITLE);
        String note = Sanitizer.optional(form.get("note"), Sanitizer.MAX_TITLE);
        LocalDate day = date(form.get("day"), today, today.plusYears(2));
        LocalTime time = time(form.get("time"));
        if (who == null || doctor == null || specialty == null || place == null || note == null || day == null
                || time == null) {
            return invalid();
        }
        if (r.visits.size() >= MAX_VISITS) return full();
        r.visits.add(new Visit(id, who.id, doctor, specialty, place, note, day, time));
        return ok();
    }

    /**
     * Sets how many units a medicine's pack holds now, after buying more. The count is what is in the pack, so
     * undoing a dose taken before the refill puts nothing back on top of it.
     */
    static Response refill(Records r, Map<String, String> form) {
        String id = form.get("id");
        int left = count(form.get("left"), -2, 0, MAX_PACK);
        if (id == null || !ID.matcher(id).matches() || left < 0) return invalid();
        Medicine m = r.medicine(id);
        if (m == null) return gone();
        m.left = left;
        r.used.keySet().removeIf(key -> Plan.medicineOf(key).equals(id));
        return ok();
    }

    static Response delete(Records r, Map<String, String> form) {
        String id = form.get("id"), kind = form.get("kind");
        if (id == null || !ID.matcher(id).matches() || kind == null) return invalid();
        boolean removed;
        switch (kind) {
            case "person":
                removed = r.removeMember(id);
                break;
            case "medicine":
                removed = r.removeMedicine(id);
                break;
            case "visit":
                removed = r.removeVisit(id);
                break;
            default:
                return invalid();
        }
        return removed ? ok() : gone();
    }

    /** The family, every medicine with its pack and today's doses, and the appointments not yet over. */
    static Response list(Records r, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        try {
            JSONArray people = new JSONArray();
            for (Member m : r.members) {
                people.put(new JSONObject().put("id", m.id).put("name", m.name).put("color", m.color));
            }
            JSONArray meds = new JSONArray();
            for (Medicine m : r.medicines) {
                JSONArray times = new JSONArray();
                int taken = 0;
                for (LocalTime t : m.times) {
                    times.put(t.toString());
                    if (r.taken.containsKey(Plan.key(m, today, t))) taken++;
                }
                meds.put(new JSONObject().put("id", m.id).put("member", m.member).put("name", m.name)
                        .put("dose", m.dose).put("how", m.how).put("times", times).put("start", m.start.toString())
                        .put("end", m.end == null ? "" : m.end.toString()).put("per", m.perDose).put("left", m.left)
                        .put("daysLeft", m.daysLeft()).put("low", m.low()).put("today", m.on(today))
                        .put("taken", taken));
            }
            List<Visit> coming = new ArrayList<>();
            for (Visit v : r.visits) if (now.isBefore(v.at().plusHours(1))) coming.add(v);
            coming.sort((a, b) -> a.at().compareTo(b.at()));
            JSONArray visits = new JSONArray();
            for (Visit v : coming) {
                visits.put(new JSONObject().put("id", v.id).put("member", v.member).put("doctor", v.doctor)
                        .put("specialty", v.specialty).put("place", v.place).put("note", v.note)
                        .put("day", v.day.toString()).put("time", v.time.toString()));
            }
            return Response.json(new JSONObject().put("today", today.toString()).put("example", r.example)
                    .put("members", people).put("medicines", meds).put("visits", visits).toString());
        } catch (JSONException e) {
            return Response.text(503, "busy");
        }
    }

    private static Member member(Records r, String id) {
        return id == null || !ID.matcher(id).matches() ? null : r.member(id);
    }

    /** "08:00,20:00": one to Records.MAX_TIMES distinct times, sorted; null when any is not valid. */
    static List<LocalTime> times(String s) {
        if (blank(s)) return null;
        List<LocalTime> times = new ArrayList<>();
        for (String part : s.split(",", -1)) {
            LocalTime t = time(part.trim());
            if (t == null) return null;
            if (!times.contains(t)) times.add(t);
        }
        if (times.size() > Records.MAX_TIMES) return null;
        times.sort(null);
        return times;
    }

    static LocalTime time(String s) {
        if (s == null || !TIME.matcher(s).matches()) return null;
        try {
            return LocalTime.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static LocalDate date(String s, LocalDate first, LocalDate last) {
        if (s == null || !DATE.matcher(s).matches()) return null;
        try {
            LocalDate d = LocalDate.parse(s);
            return d.isBefore(first) || d.isAfter(last) ? null : d;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** A whole number from min to max; {@code empty} when the field is blank, -2 when it is not valid. */
    static int count(String s, int empty, int min, int max) {
        if (blank(s)) return empty;
        if (!COUNT.matcher(s.trim()).matches()) return -2;
        int n = Integer.parseInt(s.trim());
        return n < min || n > max ? -2 : n;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static Response ok() {
        return Response.text(200, "ok");
    }

    private static Response invalid() {
        return Response.text(400, "a field is missing or not valid");
    }

    private static Response full() {
        return Response.text(409, "the list is full");
    }

    private static Response gone() {
        return Response.text(404, "not found");
    }
}
