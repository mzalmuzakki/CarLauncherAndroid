package com.orcahome;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * Day or night at the car's position, for the automatic theme: sunrise and sunset are
 * worked out on the unit (NOAA sunrise equation) from the last known position.
 * No internet, no background work.
 */
final class DayNight {
    // Jakarta, when the unit has never had a position.
    private static final double FALLBACK_LAT = -6.2, FALLBACK_LON = 106.8;
    private static final long DAY = 24 * 3600 * 1000L;

    private DayNight() {}

    static boolean isNight(Context c, long now) {
        double[] p = position(c);
        long[] last = lastAndNext(p[0], p[1], now);
        return last[0] == 1; // the last event was a sunset
    }

    /** When the theme should next change (the next sunrise or sunset), epoch ms. */
    static long nextChange(Context c, long now) {
        double[] p = position(c);
        return lastAndNext(p[0], p[1], now)[1];
    }

    /** {1 if the last event before now was a sunset else 0, time of the next event}. */
    static long[] lastAndNext(double lat, double lon, long now) {
        List<long[]> events = new ArrayList<>(); // {time, 1 = sunset / 0 = sunrise}
        for (int d = -1; d <= 1; d++) {
            long day = now + d * DAY;
            long rise = event(lat, lon, day, true), set = event(lat, lon, day, false);
            if (rise > 0) events.add(new long[]{rise, 0});
            if (set > 0) events.add(new long[]{set, 1});
        }
        Collections.sort(events, (a, b) -> Long.compare(a[0], b[0]));
        long night = 0, next = now + 6 * 3600 * 1000L;
        for (long[] e : events) {
            if (e[0] <= now) night = e[1];
            else { next = e[0]; break; }
        }
        return new long[]{night, next};
    }

    /** Sunrise or sunset nearest to the given day at this position, epoch ms; 0 if none (polar). */
    static long event(double lat, double lon, long dayMs, boolean rising) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(dayMs);
        int n = utc.get(Calendar.DAY_OF_YEAR);
        double lngHour = lon / 15;
        double t = n + ((rising ? 6 : 18) - lngHour) / 24;
        double m = 0.9856 * t - 3.289;
        double l = norm(m + 1.916 * sin(m) + 0.020 * sin(2 * m) + 282.634, 360);
        double ra = norm(Math.toDegrees(Math.atan(0.91764 * tan(l))), 360);
        ra = (ra + (Math.floor(l / 90) * 90 - Math.floor(ra / 90) * 90)) / 15;
        double sinDec = 0.39782 * sin(l);
        double cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (cos(90.833) - sinDec * sin(lat)) / (cosDec * cos(lat));
        if (cosH > 1 || cosH < -1) return 0;
        double h = (rising ? 360 - Math.toDegrees(Math.acos(cosH)) : Math.toDegrees(Math.acos(cosH))) / 15;
        double ut = norm(h + ra - 0.06571 * t - 6.622 - lngHour, 24);
        // The event in UTC hours; put it on the calendar day closest to the rough guess.
        utc.set(Calendar.HOUR_OF_DAY, 0);
        utc.set(Calendar.MINUTE, 0);
        utc.set(Calendar.SECOND, 0);
        utc.set(Calendar.MILLISECOND, 0);
        long midnight = utc.getTimeInMillis();
        long guess = midnight + (long) (((rising ? 6 : 18) - lngHour) * 3600 * 1000);
        long at = midnight + (long) (ut * 3600 * 1000);
        while (at - guess > DAY / 2) at -= DAY;
        while (guess - at > DAY / 2) at += DAY;
        return at;
    }

    /**
     * The newest position any provider has. Compared by time since boot, not by the clock in
     * the fix: a fix with a wrong date (a head unit's clock, an odd GPS) would otherwise win forever.
     */
    static Location lastKnown(Context c) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos())) best = l;
            }
        } catch (SecurityException | NullPointerException ignored) {
        }
        return best;
    }

    private static double[] position(Context c) {
        Location best = lastKnown(c);
        return best != null ? new double[]{best.getLatitude(), best.getLongitude()}
                : new double[]{FALLBACK_LAT, FALLBACK_LON};
    }

    private static double sin(double deg) { return Math.sin(Math.toRadians(deg)); }
    private static double cos(double deg) { return Math.cos(Math.toRadians(deg)); }
    private static double tan(double deg) { return Math.tan(Math.toRadians(deg)); }
    private static double norm(double v, double range) { v %= range; return v < 0 ? v + range : v; }
}
