package com.zoolcoder.tv.calendar;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The picture on an event's tile, guessed from words in its title in English or Arabic: a meal, a birthday,
 * school, the doctor, travel, prayer, a wedding, sport, shopping or a family visit. Anything else is a calendar.
 * Emoji, so it costs no image and reads at 40 dp from the sofa; only ones Android 8 already draws.
 * Words match whole, so "passport" is not sport: an English plural and an Arabic "al", "wa", "bi" or "li" in
 * front still count.
 */
final class EventIcon {
    private static final String[][] KINDS = {
            {"🎂", "birthday", "عيد ميلاد", "ميلاد"},
            {"🍽️", "dinner", "lunch", "breakfast", "meal", "عشاء", "غداء", "فطور", "فطار", "عزومة"},
            {"📚", "school", "study", "homework", "exam", "lesson", "مدرسة", "مذاكرة", "مراجعة", "امتحان", "دراسة", "درس"},
            {"🏥", "doctor", "dentist", "clinic", "hospital", "طبيب", "دكتور", "أسنان", "عيادة", "مستشفى"},
            {"✈️", "travel", "flight", "trip", "airport", "سفر", "رحلة", "مطار", "طيران"},
            {"🕌", "prayer", "mosque", "jumu", "jumuah", "jummah", "صلاة", "مسجد", "تراويح"},
            {"💍", "wedding", "engagement", "زواج", "عرس", "زفاف", "خطوبة"},
            {"⚽", "football", "match", "sport", "gym", "training", "مباراة", "رياضة", "كورة", "تمرين"},
            {"🛍️", "shopping", "market", "groceries", "سوق", "تسوق", "مشتريات"},
            {"👪", "family", "visit", "gathering", "party", "عائلة", "زيارة", "اجتماع", "لمة", "حفلة"},
    };
    static final String DEFAULT = "📅";
    private static final Pattern NOT_A_LETTER = Pattern.compile("[^\\p{L}\\p{M}]+");
    private static final String[] ARABIC_PREFIXES = {"ال", "وال", "بال", "لل", "و", "ب", "ل"};

    private EventIcon() {
    }

    static String of(String title) {
        if (title == null) return DEFAULT;
        String[] words = NOT_A_LETTER.split(title.toLowerCase(Locale.ROOT).trim());
        String spaced = " " + String.join(" ", words) + " ";
        for (String[] kind : KINDS) {
            for (int i = 1; i < kind.length; i++) {
                String key = kind[i];
                if (key.indexOf(' ') >= 0 ? spaced.contains(" " + key + " ") : hasWord(words, key)) return kind[0];
            }
        }
        return DEFAULT;
    }

    private static boolean hasWord(String[] words, String key) {
        for (String word : words) {
            if (word.equals(key) || word.equals(key + "s")) return true;
            for (String prefix : ARABIC_PREFIXES) {
                if (word.length() == prefix.length() + key.length() && word.startsWith(prefix) && word.endsWith(key)) {
                    return true;
                }
            }
        }
        return false;
    }
}
