package com.zoolcoder.tv.health;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the app keeps: the family, their medicines and appointments, which doses were taken, which
 * reminders wait and which were already shown. Saved as one JSON file by Store; read leniently, so a damaged or
 * older file loses only the entries it cannot read.
 */
final class Records {
    static final int MAX_TEXT = 80, MAX_TIMES = 8, KEEP_DAYS = 60;

    final List<Member> members = new ArrayList<>();
    final List<Medicine> medicines = new ArrayList<>();
    final List<Visit> visits = new ArrayList<>();
    /** Dose key to when it was marked taken, in epoch milliseconds. */
    final Map<String, Long> taken = new HashMap<>();
    /** Dose key to the units taking it removed from the pack, so an undo puts back no more than that. */
    final Map<String, Long> used = new HashMap<>();
    /** Dose key to the epoch milliseconds until which its reminder stays quiet. */
    final Map<String, Long> snoozed = new HashMap<>();
    /** The appointment reminders already shown. */
    final Set<String> told = new HashSet<>();
    /** True while the records are the built-in example, so the screen offers to remove them. */
    boolean example;

    boolean empty() {
        return members.isEmpty();
    }

    Member member(String id) {
        for (Member m : members) if (m.id.equals(id)) return m;
        return null;
    }

    Medicine medicine(String id) {
        for (Medicine m : medicines) if (m.id.equals(id)) return m;
        return null;
    }

    /** Marks a dose taken and takes its units from the pack. False when it already was taken. */
    boolean take(String key, long at) {
        if (taken.containsKey(key)) return false;
        taken.put(key, at);
        snoozed.remove(key);
        Medicine m = medicine(Plan.medicineOf(key));
        if (m != null && m.left >= 0) {
            int units = Math.min(m.left, m.perDose);
            m.left -= units;
            used.put(key, (long) units);
        }
        return true;
    }

    /** Takes back a "Taken" pressed by mistake, and puts the units back in the pack. */
    boolean undo(String key) {
        if (taken.remove(key) == null) return false;
        Long units = used.remove(key);
        Medicine m = medicine(Plan.medicineOf(key));
        if (m != null && m.left >= 0 && units != null) m.left += units.intValue();
        return true;
    }

    void snooze(String key, long until) {
        snoozed.put(key, until);
    }

    void clear() {
        members.clear();
        medicines.clear();
        visits.clear();
        taken.clear();
        used.clear();
        snoozed.clear();
        told.clear();
        example = false;
    }

    /** Forgets doses older than KEEP_DAYS, reminders that are over and appointments that are long past. */
    void prune(LocalDate today, long nowMillis) {
        LocalDate oldest = today.minusDays(KEEP_DAYS);
        taken.keySet().removeIf(key -> {
            LocalDate day = Plan.dayOf(key);
            return day == null || day.isBefore(oldest);
        });
        used.keySet().retainAll(taken.keySet());
        snoozed.values().removeIf(until -> until < nowMillis);
        visits.removeIf(v -> v.day.isBefore(oldest));
        told.removeIf(key -> {
            int hash = key.indexOf('#');
            String id = hash < 0 ? key : key.substring(0, hash);
            for (Visit v : visits) if (v.id.equals(id)) return false;
            return true;
        });
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("v", 1);
        JSONArray people = new JSONArray();
        for (Member m : members) {
            people.put(new JSONObject().put("id", m.id).put("name", m.name).put("color", m.color));
        }
        json.put("members", people);
        JSONArray meds = new JSONArray();
        for (Medicine m : medicines) {
            JSONArray times = new JSONArray();
            for (LocalTime t : m.times) times.put(t.toString());
            JSONObject o = new JSONObject().put("id", m.id).put("member", m.member).put("name", m.name)
                    .put("dose", m.dose).put("how", m.how).put("times", times).put("start", m.start.toString())
                    .put("per", m.perDose).put("left", m.left);
            if (m.end != null) o.put("end", m.end.toString());
            meds.put(o);
        }
        json.put("medicines", meds);
        JSONArray seen = new JSONArray();
        for (Visit v : visits) {
            seen.put(new JSONObject().put("id", v.id).put("member", v.member).put("doctor", v.doctor)
                    .put("specialty", v.specialty).put("place", v.place).put("note", v.note)
                    .put("day", v.day.toString()).put("time", v.time.toString()));
        }
        json.put("visits", seen);
        json.put("taken", new JSONObject(taken));
        json.put("used", new JSONObject(used));
        json.put("snoozed", new JSONObject(snoozed));
        json.put("told", new JSONArray(told));
        json.put("example", example);
        return json;
    }

    static Records fromJson(JSONObject json) {
        Records r = new Records();
        JSONArray people = json.optJSONArray("members");
        for (int i = 0; people != null && i < people.length(); i++) {
            JSONObject o = people.optJSONObject(i);
            if (o == null || o.optString("id").isEmpty()) continue;
            r.members.add(new Member(o.optString("id"), text(o, "name"), o.optInt("color")));
        }
        JSONArray meds = json.optJSONArray("medicines");
        for (int i = 0; meds != null && i < meds.length(); i++) {
            Medicine m = medicine(meds.optJSONObject(i));
            if (m != null && r.member(m.member) != null) r.medicines.add(m);
        }
        JSONArray seen = json.optJSONArray("visits");
        for (int i = 0; seen != null && i < seen.length(); i++) {
            Visit v = visit(seen.optJSONObject(i));
            if (v != null && r.member(v.member) != null) r.visits.add(v);
        }
        longs(json.optJSONObject("taken"), r.taken);
        longs(json.optJSONObject("used"), r.used);
        longs(json.optJSONObject("snoozed"), r.snoozed);
        JSONArray told = json.optJSONArray("told");
        for (int i = 0; told != null && i < told.length(); i++) r.told.add(told.optString(i));
        r.example = json.optBoolean("example");
        return r;
    }

    private static Medicine medicine(JSONObject o) {
        if (o == null || o.optString("id").isEmpty()) return null;
        try {
            List<LocalTime> times = new ArrayList<>();
            JSONArray list = o.optJSONArray("times");
            for (int i = 0; list != null && i < list.length() && times.size() < MAX_TIMES; i++) {
                LocalTime t = LocalTime.parse(list.getString(i)).withSecond(0).withNano(0);
                if (!times.contains(t)) times.add(t);
            }
            times.sort(null);
            String end = o.optString("end");
            return new Medicine(o.getString("id"), o.getString("member"), text(o, "name"), text(o, "dose"),
                    text(o, "how"), times, LocalDate.parse(o.getString("start")),
                    end.isEmpty() ? null : LocalDate.parse(end), o.optInt("per", 1), o.optInt("left", -1));
        } catch (JSONException | DateTimeParseException e) {
            return null;
        }
    }

    private static Visit visit(JSONObject o) {
        if (o == null || o.optString("id").isEmpty()) return null;
        try {
            return new Visit(o.getString("id"), o.getString("member"), text(o, "doctor"), text(o, "specialty"),
                    text(o, "place"), text(o, "note"), LocalDate.parse(o.getString("day")),
                    LocalTime.parse(o.getString("time")).withSecond(0).withNano(0));
        } catch (JSONException | DateTimeParseException e) {
            return null;
        }
    }

    /** A string field, trimmed, cut to MAX_TEXT characters and with control characters taken out. */
    static String text(JSONObject o, String name) {
        String value = o.optString(name).replaceAll("\\p{Cntrl}", " ").trim();
        return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) : value;
    }

    private static void longs(JSONObject from, Map<String, Long> into) {
        if (from == null) return;
        for (Iterator<String> keys = from.keys(); keys.hasNext(); ) {
            String key = keys.next();
            long value = from.optLong(key, -1);
            if (value >= 0) into.put(key, value);
        }
    }
}
