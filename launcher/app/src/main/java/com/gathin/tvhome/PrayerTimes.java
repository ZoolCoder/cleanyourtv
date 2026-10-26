package com.gathin.tvhome;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * The five daily prayers for a place and a day (config.json prayer_times), by the Egyptian General Authority of
 * Survey's angles (Fajr 19.5°, Isha 17.5°, Asr at shadow length 1): the standard sun-position formulas, as used by
 * praytimes.org, good to about a minute. Sunrise is computed too, for the end of Fajr.
 */
final class PrayerTimes {
    static final int FAJR = 0, SUNRISE = 1, DHUHR = 2, ASR = 3, MAGHRIB = 4, ISHA = 5;
    private static final double FAJR_ANGLE = 19.5, ISHA_ANGLE = 17.5, HORIZON = 0.833;

    private PrayerTimes() {
    }

    /** The six times of {@code day} at the place, as local wall-clock times in {@code zone}. */
    static LocalTime[] of(LocalDate day, double latitude, double longitude, ZoneId zone) {
        double d = day.toEpochDay() - 10957.5 + 0.5; // days since J2000.0 noon, taken at noon UTC
        double g = Math.toRadians(norm(357.529 + 0.98560028 * d, 360));
        double q = norm(280.459 + 0.98564736 * d, 360);
        double l = Math.toRadians(norm(q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g), 360));
        double e = Math.toRadians(23.439 - 0.00000036 * d);
        double ra = norm(Math.toDegrees(Math.atan2(Math.cos(e) * Math.sin(l), Math.cos(l))) / 15, 24);
        double declination = Math.asin(Math.sin(e) * Math.sin(l));
        double equationOfTime = q / 15 - ra;
        double noon = norm(12 - longitude / 15 - equationOfTime, 24); // Dhuhr, hours UTC
        double lat = Math.toRadians(latitude);
        double[] utc = {
                noon - hourAngle(FAJR_ANGLE, lat, declination),
                noon - hourAngle(HORIZON, lat, declination),
                noon,
                noon + asrHourAngle(1, lat, declination),
                noon + hourAngle(HORIZON, lat, declination),
                noon + hourAngle(ISHA_ANGLE, lat, declination)};
        LocalTime[] times = new LocalTime[utc.length];
        for (int i = 0; i < utc.length; i++) {
            long seconds = Math.round(utc[i] * 3600);
            ZonedDateTime at = day.atStartOfDay(ZoneOffset.UTC).plusSeconds(seconds).withZoneSameInstant(zone);
            times[i] = at.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        }
        return times;
    }

    /** The next prayer after {@code now}: its index (sunrise is not a prayer) and when it is. */
    static Next next(LocalDateTime now, double latitude, double longitude, ZoneId zone) {
        for (int offset = 0; offset <= 1; offset++) {
            LocalDate day = now.toLocalDate().plusDays(offset);
            LocalTime[] times = of(day, latitude, longitude, zone);
            for (int i = 0; i < times.length; i++) {
                if (i == SUNRISE) continue;
                LocalDateTime at = day.atTime(times[i]);
                if (at.isAfter(now)) return new Next(i, at);
            }
        }
        return null;
    }

    /**
     * The fasting day's next mark (config.json ramadan_countdown): iftar, at Maghrib, from Fajr until then; the end
     * of suhoor, at Fajr, through the night.
     */
    static Next fasting(LocalDateTime now, double latitude, double longitude, ZoneId zone) {
        LocalDate day = now.toLocalDate();
        LocalTime[] times = of(day, latitude, longitude, zone);
        if (now.isBefore(day.atTime(times[FAJR]))) return new Next(FAJR, day.atTime(times[FAJR]));
        if (now.isBefore(day.atTime(times[MAGHRIB]))) return new Next(MAGHRIB, day.atTime(times[MAGHRIB]));
        LocalDate tomorrow = day.plusDays(1);
        return new Next(FAJR, tomorrow.atTime(of(tomorrow, latitude, longitude, zone)[FAJR]));
    }

    static final class Next {
        final int prayer;
        final LocalDateTime at;

        Next(int prayer, LocalDateTime at) {
            this.prayer = prayer;
            this.at = at;
        }
    }

    /** Hours between noon and the sun at {@code angle} degrees below the horizon. */
    private static double hourAngle(double angle, double lat, double declination) {
        double cos = (-Math.sin(Math.toRadians(angle)) - Math.sin(lat) * Math.sin(declination))
                / (Math.cos(lat) * Math.cos(declination));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos)))) / 15;
    }

    /** Hours after noon when a shadow is {@code factor} times the object's length plus its noon shadow. */
    private static double asrHourAngle(double factor, double lat, double declination) {
        double altitude = Math.atan(1 / (factor + Math.tan(Math.abs(lat - declination))));
        double cos = (Math.sin(altitude) - Math.sin(lat) * Math.sin(declination))
                / (Math.cos(lat) * Math.cos(declination));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos)))) / 15;
    }

    private static double norm(double value, double range) {
        double v = value % range;
        return v < 0 ? v + range : v;
    }
}
