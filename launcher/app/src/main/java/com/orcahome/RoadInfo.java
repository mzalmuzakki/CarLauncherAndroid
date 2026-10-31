package com.orcahome;

import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Road speed limits and the nearest mosque from OpenStreetMap (the Overpass API, free, no
 * account; the car's position is sent with each request). Each answer is one small request;
 * the home screen asks only while visible and moving (speed limit: at most every 30 s and
 * 200 m) or around prayer time (mosque). When the main public server doesn't answer, two
 * other public Overpass servers are tried.
 */
final class RoadInfo {
    interface Limit { void got(int kmh); } // -1: not known for this road; STALE: answer came too late
    static final int STALE = -2;
    interface Place { void got(String name, double lat, double lon); } // name null: none near

    /** OpenStreetMap's main Overpass server, then a long-running community one (Germany). */
    private static final String[] SERVERS = {
        "https://overpass-api.de/api/interpreter?data=",
        "https://overpass.kumi.systems/api/interpreter?data=",
    };
    /** One lookup of each kind at a time; an answer older than this is for a place long gone. */
    private static final java.util.concurrent.atomic.AtomicBoolean limitBusy = new java.util.concurrent.atomic.AtomicBoolean(),
            mosqueBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private static final long MAX_AGE_MS = 45_000;
    private static int server; // the last one that answered
    private static final String UA = "OrcaHome/2.9 (https://github.com/mzalmuzakki/CarLauncherAndroid)";
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static Handler main;

    private static synchronized Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }
    /** Roads a car drives on. */
    private static final List<String> ROADS = Arrays.asList("motorway", "trunk", "primary", "secondary",
            "tertiary", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
            "unclassified", "residential", "living_street", "service");

    private RoadInfo() {}

    /**
     * The limit of the road the car is on: of the roads within 30 m, the nearest one that runs
     * the way the car drives (bearing; NaN = unknown). Its maxspeed, or -1 if it has none: a
     * road beside or above (an elevated toll road) doesn't lend its limit. When two different
     * roads the same way are too close to tell apart (a toll road right above a street), both
     * with a known limit and the limits differ, the answer is -1 rather than a guess (pieces of
     * one road, and side lanes without a limit, don't count). False: skipped, one is running.
     */
    static boolean speedLimit(double lat, double lon, float bearing, Limit done) {
        if (!limitBusy.compareAndSet(false, true)) return false;
        long asked = android.os.SystemClock.elapsedRealtime();
        String q = String.format(Locale.US, "[out:json][timeout:10];way(around:30,%.6f,%.6f)[highway];out tags geom;", lat, lon);
        NET.execute(() -> {
            int best = -1, second = -1;
            double bestScore = Double.MAX_VALUE, secondScore = Double.MAX_VALUE;
            String bestName = "", secondName = "";
            try {
                JSONArray els = new JSONObject(get(q)).getJSONArray("elements");
                for (int i = 0; i < els.length(); i++) {
                    JSONObject e = els.getJSONObject(i);
                    JSONObject tags = e.optJSONObject("tags");
                    JSONArray geom = e.optJSONArray("geometry");
                    if (tags == null || geom == null || !ROADS.contains(tags.optString("highway"))) continue;
                    double score = score(geom, lat, lon, bearing);
                    int kmh = parse(tags.optString("maxspeed"));
                    String name = tags.optString("name");
                    if (score < bestScore) {
                        second = best; secondScore = bestScore; secondName = bestName;
                        best = kmh; bestScore = score; bestName = name;
                    } else if (score < secondScore) {
                        second = kmh; secondScore = score; secondName = name;
                    }
                }
            } catch (Exception e) {
                Log.i("OrcaHome", "speed limit: " + e);
                return; // offline: keep what's shown
            } finally {
                limitBusy.set(false);
            }
            boolean sameRoad = !bestName.isEmpty() && bestName.equals(secondName);
            if (secondScore - bestScore < 10 && best > 0 && second > 0 && second != best && !sameRoad) best = -1; // can't tell which road
            final int fin = android.os.SystemClock.elapsedRealtime() - asked > MAX_AGE_MS ? STALE : best;
            Log.i("OrcaHome", "speed limit: " + (fin > 0 ? fin + " km/h" : fin == STALE ? "answer too late" : "not known here"));
            main().post(() -> done.got(fin));
        });
        return true;
    }

    /** Metres to the nearest piece of the road, plus 50 if that piece runs across the car's way. */
    static double score(JSONArray geom, double lat, double lon, float bearing) throws org.json.JSONException {
        double kx = 111320 * Math.cos(Math.toRadians(lat)), ky = 110540, best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < geom.length(); i++) {
            JSONObject p = geom.getJSONObject(i), q = geom.getJSONObject(i + 1);
            double ax = (p.getDouble("lon") - lon) * kx, ay = (p.getDouble("lat") - lat) * ky;
            double bx = (q.getDouble("lon") - lon) * kx, by = (q.getDouble("lat") - lat) * ky;
            double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double d = Math.hypot(ax + t * dx, ay + t * dy);
            if (!Float.isNaN(bearing) && len2 > 0) {
                double seg = Math.toDegrees(Math.atan2(dx, dy)); // compass bearing of the piece
                double diff = Math.abs(((seg - bearing) % 180 + 180) % 180); // either direction of travel
                if (Math.min(diff, 180 - diff) > 45) d += 50;
            }
            best = Math.min(best, d);
        }
        return best;
    }

    /** "80", "80 km/h", "50 mph"; anything else (e.g. "ID:urban") counts as unknown. */
    static int parse(String maxspeed) {
        String s = maxspeed.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("mph")) return Math.round(Integer.parseInt(s.replace("mph", "").trim()) * 1.609f);
            return Integer.parseInt(s.replace("km/h", "").replace("kmh", "").trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static void nearestMosque(double lat, double lon, Place done) {
        if (!mosqueBusy.compareAndSet(false, true)) return;
        String q = String.format(Locale.US,
                "[out:json][timeout:10];nwr(around:2500,%.6f,%.6f)[amenity=place_of_worship][religion=muslim];out center tags;", lat, lon);
        NET.execute(() -> {
            try { findMosque(q, lat, lon, done); } finally { mosqueBusy.set(false); }
        });
    }

    private static void findMosque(String q, double lat, double lon, Place done) {
        String name = null;
        double bLat = 0, bLon = 0;
        float bestM = Float.MAX_VALUE;
        try {
            JSONArray els = new JSONObject(get(q)).getJSONArray("elements");
            float[] d = new float[1];
            for (int i = 0; i < els.length(); i++) {
                JSONObject e = els.getJSONObject(i);
                JSONObject at = e.has("center") ? e.getJSONObject("center") : e;
                if (!at.has("lat")) continue;
                Location.distanceBetween(lat, lon, at.getDouble("lat"), at.getDouble("lon"), d);
                if (d[0] >= bestM) continue;
                bestM = d[0];
                bLat = at.getDouble("lat");
                bLon = at.getDouble("lon");
                JSONObject tags = e.optJSONObject("tags");
                String n = tags == null ? "" : tags.optString("name");
                name = n.isEmpty() ? "Masjid" : n;
            }
        } catch (Exception e) {
            Log.i("OrcaHome", "mosque: " + e);
            return;
        }
        Log.i("OrcaHome", "mosque: " + (name == null ? "none within 2.5 km" : name + ", " + Math.round(bestM) + " m"));
        final String fn = name;
        final double fLat = bLat, fLon = bLon;
        main().post(() -> done.got(fn, fLat, fLon));
    }

    /** The answer from the last server that worked, else the next ones in turn. */
    private static String get(String query) throws Exception {
        Exception last = null;
        for (int k = 0; k < SERVERS.length; k++) {
            int i = (server + k) % SERVERS.length;
            try {
                String body = get(SERVERS[i], query, k == 0 ? 8000 : 5000);
                server = i;
                return body;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last;
    }

    private static String get(String api, String query, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(api + Uri.encode(query)).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs + 4000);
        c.setRequestProperty("User-Agent", UA);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
