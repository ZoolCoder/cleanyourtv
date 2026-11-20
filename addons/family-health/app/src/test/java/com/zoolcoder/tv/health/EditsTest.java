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
import java.util.Map;

/** What a phone may add or change: every field checked, the limits, the example family, and the list. */
public class EditsTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final String GM = "00000000000a", PILL = "00000000000b", VISIT = "00000000000c";

    private static Map<String, String> form(String body) {
        return Form.parse(body);
    }

    private static Records family() {
        Records r = new Records();
        r.members.add(new Member(GM, "Grandma", 2));
        r.medicines.add(new Medicine(PILL, GM, "Metformin", "500 mg", "after a meal",
                List.of(LocalTime.of(8, 0), LocalTime.of(20, 0)), TODAY.minusDays(3), null, 2, 10));
        r.visits.add(new Visit(VISIT, GM, "Dr. Amal", "Cardiologist", "Heart Centre", "",
                TODAY.plusDays(2), LocalTime.of(11, 30)));
        return r;
    }

    @Test
    public void addsAPersonWithTheirColour() {
        Records r = new Records();
        assertEquals(200, Edits.person(r, form("name=Sara&color=4"), "00000000000d").status);
        assertEquals("Sara", r.members.get(0).name);
        assertEquals(4, r.members.get(0).color);
        assertEquals(200, Edits.person(r, form("name=Omar&color=9"), "00000000000e").status);
        assertEquals(1, r.members.get(1).color);
    }

    @Test
    public void aPersonNeedsAName() {
        Records r = new Records();
        assertEquals(400, Edits.person(r, form("name=&color=1"), "00000000000d").status);
        assertEquals(400, Edits.person(r, form("color=1"), "00000000000d").status);
        assertEquals(400, Edits.person(r, form("name=" + "a".repeat(25)), "00000000000d").status);
        assertTrue(r.members.isEmpty());
    }

    @Test
    public void theFirstRealPersonRemovesTheExample() {
        Records r = family();
        r.example = true;
        assertEquals(200, Edits.person(r, form("name=Dad"), "00000000000d").status);
        assertFalse(r.example);
        assertEquals(1, r.members.size());
        assertTrue(r.medicines.isEmpty());
        assertTrue(r.visits.isEmpty());
        // A bad form leaves the example as it was.
        r = family();
        r.example = true;
        assertEquals(400, Edits.person(r, form("name="), "00000000000d").status);
        assertTrue(r.example);
        assertEquals(1, r.medicines.size());
    }

    @Test
    public void theFamilyHasALimit() {
        Records r = new Records();
        for (int i = 0; i < Edits.MAX_MEMBERS; i++) {
            assertEquals(200, Edits.person(r, form("name=P" + i), String.format("%012x", i)).status);
        }
        assertEquals(409, Edits.person(r, form("name=One+more"), "0000000000ff").status);
        assertEquals(Edits.MAX_MEMBERS, r.members.size());
    }

    @Test
    public void addsAMedicineWithItsDefaults() {
        Records r = family();
        assertEquals(200, Edits.medicine(r, form("member=" + GM + "&name=Vitamin+D&times=20:00,08:00,08:00"),
                "00000000000d", TODAY).status);
        Medicine m = r.medicine("00000000000d");
        assertEquals(List.of(LocalTime.of(8, 0), LocalTime.of(20, 0)), m.times);
        assertEquals(TODAY, m.start);
        assertNull(m.end);
        assertEquals(1, m.perDose);
        assertEquals(-1, m.left);
        assertEquals("", m.dose);
        assertEquals("", m.how);
    }

    @Test
    public void aCourseOfDaysEndsOnItsLastDay() {
        Records r = family();
        assertEquals(200, Edits.medicine(r, form("member=" + GM + "&name=Antibiotic&dose=1+capsule&how=After+a+meal"
                + "&times=08:00&start=2026-11-21&days=7&per=2&left=14"), "00000000000d", TODAY).status);
        Medicine m = r.medicine("00000000000d");
        assertEquals(LocalDate.of(2026, 11, 21), m.start);
        assertEquals(LocalDate.of(2026, 11, 27), m.end);
        assertEquals(2, m.perDose);
        assertEquals(14, m.left);
    }

    @Test
    public void aMedicineRefusesFieldsThatAreNotValid() {
        String good = "member=" + GM + "&name=Pill&times=08:00";
        String[] bad = {
            "member=0000000000ff&name=Pill&times=08:00",
            "member=nope&name=Pill&times=08:00",
            "member=" + GM + "&times=08:00",
            "member=" + GM + "&name=Pill",
            "member=" + GM + "&name=Pill&times=8:00",
            "member=" + GM + "&name=Pill&times=25:00",
            "member=" + GM + "&name=Pill&times=08:00,",
            "member=" + GM + "&name=Pill&times=01:00,02:00,03:00,04:00,05:00,06:00,07:00,08:00,09:00",
            good + "&start=2028-01-01",
            good + "&start=2026-02-30",
            good + "&days=0",
            good + "&days=366",
            good + "&days=-1",
            good + "&per=0",
            good + "&per=21",
            good + "&left=10000",
            good + "&left=1.5",
            good + "&dose=" + "x".repeat(25),
            good + "&how=" + "x".repeat(41),
        };
        for (String body : bad) {
            Records r = family();
            assertEquals(body, 400, Edits.medicine(r, form(body), "00000000000d", TODAY).status);
            assertEquals(body, 1, r.medicines.size());
        }
        assertEquals(200, Edits.medicine(family(), form(good + "&left=0&days=365&per=20"), "00000000000d", TODAY).status);
    }

    @Test
    public void addsAnAppointment() {
        Records r = family();
        assertEquals(200, Edits.visit(r, form("member=" + GM + "&doctor=Dr.+Hassan&specialty=Dentist"
                + "&place=Smile+Dental&note=&day=2026-11-20&time=17:15"), "00000000000d", TODAY).status);
        Visit v = r.visits.get(1);
        assertEquals("Dr. Hassan", v.doctor);
        assertEquals(TODAY, v.day);
        assertEquals(LocalTime.of(17, 15), v.time);
        assertEquals("", v.note);
    }

    @Test
    public void anAppointmentIsTodayOrLater() {
        String rest = "&member=" + GM + "&doctor=Dr.+Hassan&time=10:00";
        assertEquals(400, Edits.visit(family(), form("day=2026-11-19" + rest), "00000000000d", TODAY).status);
        assertEquals(400, Edits.visit(family(), form("day=2028-11-21" + rest), "00000000000d", TODAY).status);
        assertEquals(400, Edits.visit(family(), form("day=2026-11-21&time=10" + rest.replace("&time=10:00", "")),
                "00000000000d", TODAY).status);
        assertEquals(400, Edits.visit(family(), form("day=2026-11-21&member=" + GM + "&time=10:00"),
                "00000000000d", TODAY).status);
        assertEquals(200, Edits.visit(family(), form("day=2028-11-20" + rest), "00000000000d", TODAY).status);
    }

    @Test
    public void refillSetsThePack() {
        Records r = family();
        String dose = Plan.key(r.medicine(PILL), TODAY, LocalTime.of(8, 0));
        r.take(dose, 5L);
        assertEquals(200, Edits.refill(r, form("id=" + PILL + "&left=60")).status);
        assertEquals(60, r.medicine(PILL).left);
        // Undoing a dose taken before the refill leaves the counted pack as it is.
        r.undo(dose);
        assertEquals(60, r.medicine(PILL).left);
        assertEquals(400, Edits.refill(r, form("id=" + PILL + "&left=")).status);
        assertEquals(400, Edits.refill(r, form("id=" + PILL + "&left=-3")).status);
        assertEquals(400, Edits.refill(r, form("id=xyz&left=3")).status);
        assertEquals(404, Edits.refill(r, form("id=0000000000ff&left=3")).status);
        assertEquals(60, r.medicine(PILL).left);
    }

    @Test
    public void deletingAPersonTakesTheirMedicinesAndAppointments() {
        Records r = family();
        String key = Plan.key(r.medicine(PILL), TODAY, LocalTime.of(8, 0));
        r.take(key, 5L);
        assertEquals(200, Edits.delete(r, form("kind=person&id=" + GM)).status);
        assertTrue(r.members.isEmpty());
        assertTrue(r.medicines.isEmpty());
        assertTrue(r.visits.isEmpty());
        assertTrue(r.taken.isEmpty());
        assertTrue(r.used.isEmpty());
        assertEquals(404, Edits.delete(r, form("kind=person&id=" + GM)).status);
    }

    @Test
    public void deletesOneMedicineOrAppointment() {
        Records r = family();
        r.snooze(Plan.key(r.medicine(PILL), TODAY, LocalTime.of(8, 0)), Long.MAX_VALUE);
        r.told.add(VISIT + "#eve");
        r.told.add(VISIT + "#soon");
        assertEquals(200, Edits.delete(r, form("kind=medicine&id=" + PILL)).status);
        assertTrue(r.medicines.isEmpty());
        assertTrue(r.snoozed.isEmpty());
        assertEquals(200, Edits.delete(r, form("kind=visit&id=" + VISIT)).status);
        assertTrue(r.visits.isEmpty());
        assertTrue(r.told.isEmpty());
        assertEquals(1, r.members.size());
        assertEquals(400, Edits.delete(r, form("kind=member&id=" + GM)).status);
        assertEquals(400, Edits.delete(r, form("kind=person&id=GM")).status);
        assertEquals(400, Edits.delete(r, form("id=" + GM)).status);
    }

    @Test
    public void listsTheFamilyAsJson() throws Exception {
        Records r = family();
        r.visits.add(new Visit("00000000000e", GM, "Dr. Old", "", "", "", TODAY.minusDays(1), LocalTime.of(9, 0)));
        r.visits.add(new Visit("00000000000f", GM, "Dr. Soon", "", "", "", TODAY, LocalTime.of(9, 0)));
        r.take(Plan.key(r.medicine(PILL), TODAY, LocalTime.of(8, 0)), 5L);
        Response res = Edits.list(r, TODAY.atTime(9, 30));
        assertEquals(200, res.status);
        assertTrue(res.contentType.startsWith("application/json"));
        JSONObject json = new JSONObject(res.body);
        assertEquals("2026-11-20", json.getString("today"));
        assertFalse(json.getBoolean("example"));
        assertEquals("Grandma", json.getJSONArray("members").getJSONObject(0).getString("name"));
        JSONObject pill = json.getJSONArray("medicines").getJSONObject(0);
        assertEquals("08:00", pill.getJSONArray("times").getString(0));
        assertEquals("", pill.getString("end"));
        assertEquals(1, pill.getInt("taken"));
        assertEquals(8, pill.getInt("left"));
        assertTrue(pill.getBoolean("today"));
        // The past appointment is left out, the one half an hour ago stays, and they come in order.
        JSONArray visits = json.getJSONArray("visits");
        assertEquals(2, visits.length());
        assertEquals("Dr. Soon", visits.getJSONObject(0).getString("doctor"));
        assertEquals("Dr. Amal", visits.getJSONObject(1).getString("doctor"));
    }

    @Test
    public void restoreUndoesAChange() throws Exception {
        Records r = family();
        r.take(Plan.key(r.medicine(PILL), TODAY, LocalTime.of(8, 0)), 5L);
        String before = r.toJson().toString();
        Edits.delete(r, form("kind=person&id=" + GM));
        r.restore(Records.fromJson(new JSONObject(before)));
        assertEquals(before, r.toJson().toString());
    }

    @Test
    public void countsAndDatesAreStrict() {
        assertEquals(0, Edits.count(" ", 0, 1, 5));
        assertEquals(-2, Edits.count("+3", 0, 1, 5));
        assertEquals(-2, Edits.count("12345", 0, 1, 99999));
        assertEquals(3, Edits.count(" 3 ", 0, 1, 5));
        assertNull(Edits.date("2026-11-20T00:00", TODAY, TODAY));
        assertEquals(TODAY, Edits.date("2026-11-20", TODAY, TODAY));
        assertNull(Edits.time("24:00"));
        assertNull(Edits.times(" "));
    }
}
