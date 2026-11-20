package com.zoolcoder.tv.health;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public class RecordsTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private static Records sample() {
        Records r = new Records();
        r.members.add(new Member("gm", "Grandma", 2));
        r.medicines.add(new Medicine("p1", "gm", "Metformin", "500 mg", "after a meal",
                List.of(LocalTime.of(8, 0)), TODAY.minusDays(3), TODAY.plusDays(30), 2, 10));
        r.visits.add(new Visit("v1", "gm", "Dr. Amal", "Cardiologist", "Heart Centre", "bring results",
                TODAY.plusDays(2), LocalTime.of(11, 30)));
        return r;
    }

    @Test
    public void takeUsesUpThePackAndUndoPutsItBack() {
        Records r = sample();
        Medicine m = r.medicine("p1");
        String key = Plan.key(m, TODAY, LocalTime.of(8, 0));
        assertTrue(r.take(key, 5L));
        assertEquals(8, m.left);
        assertFalse(r.take(key, 6L));
        assertEquals(8, m.left);
        assertTrue(r.undo(key));
        assertEquals(10, m.left);
        assertFalse(r.undo(key));
    }

    @Test
    public void packNeverGoesBelowZeroAndUncountedStaysUncounted() {
        Records r = sample();
        Medicine m = r.medicine("p1");
        m.left = 1;
        r.take(Plan.key(m, TODAY, LocalTime.of(8, 0)), 1L);
        assertEquals(0, m.left);
        m.left = -1;
        r.take(Plan.key(m, TODAY.minusDays(1), LocalTime.of(8, 0)), 1L);
        assertEquals(-1, m.left);
    }

    @Test
    public void undoPutsBackOnlyWhatTheTakeRemoved() {
        Records r = sample();
        Medicine m = r.medicine("p1");
        String key = Plan.key(m, TODAY, LocalTime.of(8, 0));
        m.left = 1;
        r.take(key, 1L);
        assertEquals(0, m.left);
        r.undo(key);
        assertEquals(1, m.left);
        m.left = 0;
        r.take(key, 2L);
        r.undo(key);
        assertEquals(0, m.left);
    }

    @Test
    public void takeClearsItsSnooze() {
        Records r = sample();
        String key = Plan.key(r.medicine("p1"), TODAY, LocalTime.of(8, 0));
        r.snooze(key, 99L);
        r.take(key, 1L);
        assertFalse(r.snoozed.containsKey(key));
    }

    @Test
    public void jsonRoundTripKeepsEverything() throws Exception {
        Records r = sample();
        String key = Plan.key(r.medicine("p1"), TODAY, LocalTime.of(8, 0));
        r.taken.put(key, 1234L);
        r.snoozed.put("x@2026-11-20T09:00", 99L);
        r.told.add("v1#eve");
        r.example = true;
        Records back = Records.fromJson(new JSONObject(r.toJson().toString()));
        assertEquals(r.toJson().toString(), back.toJson().toString());
        Medicine m = back.medicine("p1");
        assertEquals(2, m.perDose);
        assertEquals(TODAY.plusDays(30), m.end);
        assertEquals(LocalTime.of(11, 30), back.visits.get(0).time);
        assertEquals(Long.valueOf(1234L), back.taken.get(key));
        assertTrue(back.example);
    }

    @Test
    public void readingSkipsWhatBelongsToNobodyAndBrokenEntries() throws Exception {
        JSONObject json = sample().toJson();
        json.getJSONArray("medicines").put(new JSONObject().put("id", "orphan").put("member", "nobody")
                .put("name", "X").put("times", new JSONArray().put("08:00")).put("start", "2026-11-01"));
        json.getJSONArray("medicines").put(new JSONObject().put("id", "bad").put("member", "gm")
                .put("name", "Y").put("times", new JSONArray().put("not a time")).put("start", "2026-11-01"));
        json.getJSONArray("visits").put(new JSONObject().put("id", "v2").put("member", "nobody")
                .put("doctor", "Z").put("day", "2026-11-21").put("time", "10:00"));
        Records r = Records.fromJson(json);
        assertNull(r.medicine("orphan"));
        assertEquals(1, r.visits.size());
        assertEquals(1, r.members.size());
    }

    @Test
    public void textIsCleanedAndCut() throws Exception {
        String longName = "A\u0000B\nC" + "x".repeat(200);
        JSONObject json = sample().toJson();
        json.getJSONArray("members").getJSONObject(0).put("name", longName);
        String name = Records.fromJson(json).members.get(0).name;
        assertFalse(name.contains("\u0000"));
        assertFalse(name.contains("\n"));
        assertTrue(name.length() <= Records.MAX_TEXT);
    }

    @Test
    public void pruneForgetsOldDosesAndRemindersForGoneVisits() {
        Records r = sample();
        Medicine m = r.medicine("p1");
        String old = Plan.key(m, TODAY.minusDays(Records.KEEP_DAYS + 1), LocalTime.of(8, 0));
        String recent = Plan.key(m, TODAY.minusDays(1), LocalTime.of(8, 0));
        r.taken.put(old, 1L);
        r.taken.put(recent, 1L);
        r.snoozed.put(recent, 10L);
        r.told.add("v1#eve");
        r.told.add("gone#soon");
        r.prune(TODAY, 20L);
        assertFalse(r.taken.containsKey(old));
        assertTrue(r.taken.containsKey(recent));
        assertTrue(r.snoozed.isEmpty());
        assertTrue(r.told.contains("v1#eve"));
        assertFalse(r.told.contains("gone#soon"));
    }

    @Test
    public void daysLeftCountsDosesPerDay() {
        Medicine m = new Medicine("p", "gm", "N", "", "", List.of(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                TODAY, null, 1, 5);
        assertEquals(2, m.daysLeft());
        assertTrue(m.low());
        m.left = -1;
        assertEquals(-1, m.daysLeft());
        assertFalse(m.low());
    }
}
