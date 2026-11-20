package com.zoolcoder.tv.health;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public class PlanTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20); // a Friday
    private Records r;
    private Medicine pill;

    @Before
    public void setUp() {
        r = new Records();
        r.members.add(new Member("gm", "Grandma", 2));
        pill = new Medicine("p1", "gm", "Metformin", "500 mg", "after a meal",
                List.of(LocalTime.of(8, 0), LocalTime.of(20, 0)), TODAY.minusDays(10), null, 1, 30);
        r.medicines.add(pill);
    }

    private static LocalDateTime at(int hour, int minute) {
        return TODAY.atTime(hour, minute);
    }

    @Test
    public void doseIsDueFromHalfAnHourBeforeUntilThreeHoursAfter() {
        LocalDateTime eight = at(8, 0);
        assertEquals(Plan.State.LATER, Plan.doseState(eight, at(7, 29), false));
        assertEquals(Plan.State.NOW, Plan.doseState(eight, at(7, 30), false));
        assertEquals(Plan.State.NOW, Plan.doseState(eight, at(10, 59), false));
        assertEquals(Plan.State.MISSED, Plan.doseState(eight, at(11, 0), false));
        assertEquals(Plan.State.DONE, Plan.doseState(eight, at(11, 0), true));
    }

    @Test
    public void appointmentIsNowFromTwoHoursBeforeUntilAnHourAfter() {
        LocalDateTime ten = at(10, 0);
        assertEquals(Plan.State.LATER, Plan.visitState(ten, at(7, 59)));
        assertEquals(Plan.State.NOW, Plan.visitState(ten, at(8, 0)));
        assertEquals(Plan.State.DONE, Plan.visitState(ten, at(11, 0)));
    }

    @Test
    public void aDoseStaysDueAcrossMidnight() {
        Medicine late = new Medicine("p2", "gm", "Insulin", "10 u", "",
                List.of(LocalTime.of(0, 15), LocalTime.of(23, 30)), TODAY.minusDays(10), null, 1, 30);
        r.medicines.add(late);
        String lastNight = Plan.key(late, TODAY.minusDays(1), LocalTime.of(23, 30));
        r.snooze(lastNight, 500L);
        List<Plan.Entry> due = Plan.ring(r, at(0, 10), 1000L);
        assertEquals(1, due.size());
        assertEquals(lastNight, due.get(0).key);
        assertEquals(lastNight, Plan.day(r, at(0, 10), null).get(0).key);

        List<Plan.Entry> beforeMidnight = Plan.day(r, at(23, 50), null);
        Plan.Entry next = beforeMidnight.get(beforeMidnight.size() - 1);
        assertEquals(Plan.key(late, TODAY.plusDays(1), LocalTime.of(0, 15)), next.key);
        assertEquals(Plan.State.NOW, next.state);
        for (Plan.Entry e : Plan.ring(r, at(23, 50), 0)) assertTrue(!e.key.equals(next.key));
    }

    @Test
    public void dayListsEachDoseInTimeOrderWithItsState() {
        r.taken.put(Plan.key(pill, TODAY, LocalTime.of(8, 0)), 1L);
        r.visits.add(new Visit("v1", "gm", "Dr. Amal", "Cardiologist", "", "", TODAY, LocalTime.of(12, 0)));
        List<Plan.Entry> day = Plan.day(r, at(11, 0), null);
        assertEquals(3, day.size());
        assertEquals(Plan.State.DONE, day.get(0).state);
        assertEquals(1L, day.get(0).takenAt);
        assertEquals("v1#visit", day.get(1).key);
        assertEquals(Plan.State.NOW, day.get(1).state);
        assertEquals(Plan.State.LATER, day.get(2).state);
        assertTrue(Plan.day(r, at(11, 0), "someone-else").isEmpty());
    }

    @Test
    public void medicineOutsideItsDatesIsNotListed() {
        r.medicines.clear();
        r.medicines.add(new Medicine("p2", "gm", "Antibiotic", "", "", List.of(LocalTime.of(8, 0)),
                TODAY.minusDays(7), TODAY.minusDays(1), 1, -1));
        r.medicines.add(new Medicine("p3", "gm", "Vitamin D", "", "", List.of(LocalTime.of(8, 0)),
                TODAY.plusDays(1), null, 1, -1));
        assertTrue(Plan.day(r, at(9, 0), null).isEmpty());
    }

    @Test
    public void ringsOnlyFromTheDoseTimeAndNotWhileSnoozed() {
        assertTrue(Plan.ring(r, at(7, 45), 0).isEmpty());
        List<Plan.Entry> due = Plan.ring(r, at(8, 5), 1000);
        assertEquals(1, due.size());
        assertEquals(Plan.key(pill, TODAY, LocalTime.of(8, 0)), due.get(0).key);
        r.snooze(due.get(0).key, 2000);
        assertTrue(Plan.ring(r, at(8, 5), 1500).isEmpty());
        assertEquals(1, Plan.ring(r, at(8, 20), 2000).size());
        r.take(due.get(0).key, 2100);
        assertTrue(Plan.ring(r, at(8, 21), 2200).isEmpty());
    }

    @Test
    public void missedDoseNoLongerRings() {
        assertTrue(Plan.ring(r, at(11, 1), 0).isEmpty());
    }

    @Test
    public void appointmentRemindsTheEveningBeforeAndTwoHoursBefore() {
        r.visits.add(new Visit("v1", "gm", "Dr. Amal", "", "", "", TODAY.plusDays(1), LocalTime.of(10, 0)));
        assertTrue(Plan.visitAlerts(r, at(18, 59)).isEmpty());
        List<Plan.Entry> eve = Plan.visitAlerts(r, at(19, 0));
        assertEquals("v1" + Plan.EVE_KEY, eve.get(0).key);
        r.told.add(eve.get(0).key);
        assertTrue(Plan.visitAlerts(r, at(23, 0)).isEmpty());
        LocalDateTime soon = TODAY.plusDays(1).atTime(8, 0);
        assertEquals("v1" + Plan.SOON_KEY, Plan.visitAlerts(r, soon).get(0).key);
        assertTrue(Plan.visitAlerts(r, TODAY.plusDays(1).atTime(10, 0)).isEmpty());
    }

    @Test
    public void weekDotsShowTakenMissedAndToCome() {
        LocalDate monday = TODAY.minusDays(4);
        for (LocalDate d = monday; d.isBefore(TODAY); d = d.plusDays(1)) {
            for (LocalTime t : pill.times) r.taken.put(Plan.key(pill, d, t), 1L);
        }
        r.taken.remove(Plan.key(pill, monday.plusDays(1), LocalTime.of(20, 0)));
        r.taken.put(Plan.key(pill, TODAY, LocalTime.of(8, 0)), 1L);
        Plan.Dot T = Plan.Dot.TAKEN, M = Plan.Dot.MISSED, U = Plan.Dot.UPCOMING;
        assertArrayEquals(new Plan.Dot[]{T, M, T, T, U, U, U},
                Plan.week(r, "gm", at(12, 0), DayOfWeek.MONDAY));
        assertEquals(Plan.Dot.NONE, Plan.week(r, "nobody", at(12, 0), DayOfWeek.MONDAY)[0]);
    }

    @Test
    public void upcomingKeepsAnAppointmentUntilAnHourAfterIt() {
        r.visits.add(new Visit("late", "gm", "B", "", "", "", TODAY.plusDays(3), LocalTime.of(9, 0)));
        r.visits.add(new Visit("soon", "gm", "A", "", "", "", TODAY, LocalTime.of(10, 0)));
        List<Visit> list = Plan.upcoming(r, "gm", at(10, 30));
        assertEquals("soon", list.get(0).id);
        assertEquals(2, list.size());
        assertEquals(1, Plan.upcoming(r, "gm", at(11, 0)).size());
    }

    @Test
    public void keyCarriesItsMedicineAndDay() {
        String key = Plan.key(pill, TODAY, LocalTime.of(8, 0));
        assertEquals("p1", Plan.medicineOf(key));
        assertEquals(TODAY, Plan.dayOf(key));
        assertEquals(null, Plan.dayOf("p1"));
    }
}
