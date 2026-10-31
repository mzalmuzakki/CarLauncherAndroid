package com.orcahome;

import android.icu.util.IslamicCalendar;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * The five prayer times, worked out on the unit from the GPS position (no internet), with the
 * Kemenag RI method: Subuh at the sun 20° below the horizon, Isya at 18°, Ashar at a shadow
 * as long as the object (Syafi'i), and 2 minutes of ihtiyat (safety margin) on each. Within a
 * minute or two of the Kemenag schedule. Also the Hijri date (Umm al-Qura calendar; Indonesia
 * announces some months by sighting, so it can be a day off at the start of a month).
 */
final class PrayerTimes {
    static final String[] NAMES = {"Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    private static final double FAJR = 20, ISHA = 18, SUNSET = 0.833;
    private static final long IHTIYAT_MS = 2 * 60_000L;
    private static final long DAY = 24 * 3600 * 1000L;

    private PrayerTimes() {}

    /** The five times (epoch ms) of the day that contains `now` in tz. */
    static long[] forDay(double lat, double lon, long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long midnight = c.getTimeInMillis();
        double offsetH = tz.getOffset(midnight + DAY / 2) / 3600000.0;
        double jd = julian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)) - lon / (15 * 24.0);
        // Two passes: the second uses the sun's position at the first pass's times.
        double[] t = {5, 12, 13, 18, 18};
        for (int pass = 0; pass < 2; pass++) {
            double[] d = new double[5];
            for (int i = 0; i < 5; i++) d[i] = t[i] / 24;
            t = new double[]{
                angleTime(jd, d[0], lat, FAJR, true),
                midDay(jd, d[1]),
                asrTime(jd, d[2], lat),
                angleTime(jd, d[3], lat, SUNSET, false),
                angleTime(jd, d[4], lat, ISHA, false),
            };
        }
        long[] out = new long[5];
        for (int i = 0; i < 5; i++) {
            double h = t[i] + offsetH - lon / 15;
            out[i] = midnight + Math.round(h * 3600000) + IHTIYAT_MS;
        }
        return out;
    }

    /** The next prayer from `now`: {index into NAMES, time}; after Isya, tomorrow's Subuh. */
    static long[] next(double lat, double lon, long now, TimeZone tz) {
        long[] today = forDay(lat, lon, now, tz);
        for (int i = 0; i < 5; i++) if (today[i] > now) return new long[]{i, today[i]};
        return new long[]{0, forDay(lat, lon, now + DAY, tz)[0]};
    }

    /** True from 15 minutes before a prayer time until 30 minutes after it. */
    static boolean nearPrayer(double lat, double lon, long now, TimeZone tz) {
        for (long t : forDay(lat, lon, now, tz)) if (now >= t - 15 * 60_000L && now <= t + 30 * 60_000L) return true;
        return false;
    }

    private static final String[] HIJRI_MONTHS = {
        "Muharram", "Safar", "Rabiul Awal", "Rabiul Akhir", "Jumadil Awal", "Jumadil Akhir",
        "Rajab", "Syakban", "Ramadan", "Syawal", "Zulkaidah", "Zulhijah",
    };

    /** "9 Jumadil Awal 1448" for the civil day of `now`. */
    static String hijri(long now, TimeZone tz) {
        IslamicCalendar h = new IslamicCalendar(android.icu.util.TimeZone.getTimeZone(tz.getID()));
        h.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        h.setTimeInMillis(now);
        return h.get(IslamicCalendar.DAY_OF_MONTH) + " " + HIJRI_MONTHS[h.get(IslamicCalendar.MONTH)]
                + " " + h.get(IslamicCalendar.YEAR);
    }

    // ---- sun position (the praytimes.org formulas) -------------------------------------

    private static double julian(int y, int m, int d) {
        if (m <= 2) { y--; m += 12; }
        double a = Math.floor(y / 100.0), b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5;
    }

    /** {declination, equation of time (hours)} at julian date jd. */
    private static double[] sun(double jd) {
        double d = jd - 2451545.0;
        double g = fix(357.529 + 0.98560028 * d, 360), q = fix(280.459 + 0.98564736 * d, 360);
        double l = fix(q + 1.915 * sin(g) + 0.020 * sin(2 * g), 360);
        double e = 23.439 - 0.00000036 * d;
        double ra = Math.toDegrees(Math.atan2(cos(e) * sin(l), cos(l))) / 15;
        return new double[]{Math.toDegrees(Math.asin(sin(e) * sin(l))), q / 15 - fix(ra, 24)};
    }

    private static double midDay(double jd, double t) { return fix(12 - sun(jd + t)[1], 24); }

    private static double angleTime(double jd, double t, double lat, double angle, boolean before) {
        double decl = sun(jd + t)[0], noon = midDay(jd, t);
        double x = (-sin(angle) - sin(decl) * sin(lat)) / (cos(decl) * cos(lat));
        double h = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, x)))) / 15;
        return noon + (before ? -h : h);
    }

    private static double asrTime(double jd, double t, double lat) {
        double decl = sun(jd + t)[0];
        double angle = -Math.toDegrees(Math.atan(1 / (1 + Math.tan(Math.toRadians(Math.abs(lat - decl))))));
        return angleTime(jd, t, lat, angle, false);
    }

    private static double sin(double deg) { return Math.sin(Math.toRadians(deg)); }
    private static double cos(double deg) { return Math.cos(Math.toRadians(deg)); }
    private static double fix(double v, double range) { v %= range; return v < 0 ? v + range : v; }
}
