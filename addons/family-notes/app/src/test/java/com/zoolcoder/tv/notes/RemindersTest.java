package com.zoolcoder.tv.notes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class RemindersTest {
    private static final String FROM = "Calendar · التقويم";

    @Test
    public void theLineHasTitleTimeAndWhoUnderTheCalendarsName() {
        Note note = Reminders.note("Dentist", "Amna", "Fri 21 Nov 18:30", FROM, 7);
        assertEquals("Dentist · Fri 21 Nov 18:30 · Amna", note.text);
        assertEquals(FROM, note.name);
        assertEquals(7, note.time);
        assertTrue(note.reminder);
        assertFalse(note.away);
    }

    @Test
    public void aLongArabicDayAndTimeIsKept() {
        Note note = Reminders.note("طبيب", "أمنة", "الثلاثاء 10 فبراير 10:30 ص", FROM, 1);
        assertEquals("طبيب · الثلاثاء 10 فبراير 10:30 ص · أمنة", note.text);
    }

    @Test
    public void missingPartsAreLeftOut() {
        assertEquals("Eid", Reminders.note("Eid", null, null, FROM, 1).text);
        assertEquals("Eid · Sun 1 Mar", Reminders.note("Eid", " ", "Sun 1 Mar", FROM, 1).text);
    }

    @Test
    public void noTitleIsNoNote() {
        assertNull(Reminders.note(null, "Amna", "18:30", FROM, 1));
        assertNull(Reminders.note(" ‏ ", "Amna", "18:30", FROM, 1));
    }

    @Test
    public void itIsCleanedLikeANoteFromAPhone() {
        Note note = Reminders.note("Call‮ grandma\u0007", "Am​na", "18:30", FROM, 1);
        assertEquals("Call grandma · 18:30 · Amna", note.text);
    }

    @Test
    public void aLineTooLongKeepsJustTheTitle() {
        String title = "x".repeat(Sanitizer.MAX_TEXT - 2);
        assertEquals(title, Reminders.note(title, "Amna", "18:30", FROM, 1).text);
    }

    @Test
    public void theReminderTagSurvivesTheStore() {
        List<Note> notes = Store.parse("[{\"n\":\"Calendar\",\"t\":\"Eid\",\"w\":5,\"r\":true},{\"n\":\"Mum\",\"t\":\"x\",\"w\":4}]");
        assertTrue(notes.get(0).reminder);
        assertFalse(notes.get(1).reminder);
    }
}
