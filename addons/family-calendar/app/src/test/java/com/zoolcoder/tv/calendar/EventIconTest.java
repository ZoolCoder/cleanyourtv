package com.zoolcoder.tv.calendar;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EventIconTest {
    @Test
    public void picksFromEnglishWordsInAnyCase() {
        assertEquals("🍽️", EventIcon.of("Dinner with Grandpa"));
        assertEquals("🏥", EventIcon.of("DENTIST at 10"));
        assertEquals("📚", EventIcon.of("Exams week"));
    }

    @Test
    public void picksFromArabicWords() {
        assertEquals("📚", EventIcon.of("مراجعة مع الأطفال"));
        assertEquals("👪", EventIcon.of("اجتماع العائلة"));
    }

    @Test
    public void matchesWholeWordsOnly() {
        assertEquals(EventIcon.DEFAULT, EventIcon.of("Renew passport"));
        assertEquals(EventIcon.DEFAULT, EventIcon.of("مكالمة مع خالي"));
        assertEquals(EventIcon.DEFAULT, EventIcon.of("الجمعة"));
        assertEquals("📚", EventIcon.of("بعد المدرسة"));
        assertEquals("🎂", EventIcon.of("عيد ميلاد سارة"));
    }

    @Test
    public void birthdayWinsOverParty() {
        assertEquals("🎂", EventIcon.of("Birthday party"));
    }

    @Test
    public void unknownOrMissingTitleIsACalendar() {
        assertEquals(EventIcon.DEFAULT, EventIcon.of("Pick up the parcel"));
        assertEquals(EventIcon.DEFAULT, EventIcon.of(null));
    }
}
