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
 * account). Each answer is one small request; the home screen asks only while visible and
 * moving (speed limit: at most every 30 s and 200 m) or around prayer time (mosque).
 */
final class RoadInfo {
    interface Limit { void got(int kmh); } // -1: not known for this road
    interface Place { void got(String name, double lat, double lon); } // name null: none near

    private static final String API = "https://overpass-api.de/api/interpreter?data=";
    private static final String UA = "OrcaHome/2.8 (https://github.com/mzalmuzakki/CarLauncherAndroid)";
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Higher on the list wins where roads meet. */
    private static final List<String> RANK = Arrays.asList("motorway", "trunk", "primary", "secondary",
            "tertiary", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
            "unclassified", "residential", "living_street", "service");

    private RoadInfo() {}

    static void speedLimit(double lat, double lon, Limit done) {
        String q = String.format(Locale.US, "[out:json][timeout:10];way(around:20,%.6f,%.6f)[highway][maxspeed];out tags;", lat, lon);
        NET.execute(() -> {
            int best = -1, bestRank = Integer.MAX_VALUE;
            try {
                JSONArray els = new JSONObject(get(q)).getJSONArray("elements");
                for (int i = 0; i < els.length(); i++) {
                    JSONObject tags = els.getJSONObject(i).optJSONObject("tags");
                    if (tags == null) continue;
                    int kmh = parse(tags.optString("maxspeed"));
                    int rank = RANK.indexOf(tags.optString("highway"));
                    if (kmh > 0 && rank >= 0 && rank < bestRank) { best = kmh; bestRank = rank; }
                }
            } catch (Exception e) {
                Log.i("OrcaHome", "speed limit: " + e);
                return; // offline: keep what's shown
            }
            final int fin = best;
            Log.i("OrcaHome", "speed limit: " + (fin > 0 ? fin + " km/h" : "not known here"));
            MAIN.post(() -> done.got(fin));
        });
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
        String q = String.format(Locale.US,
                "[out:json][timeout:10];nwr(around:2500,%.6f,%.6f)[amenity=place_of_worship][religion=muslim];out center tags;", lat, lon);
        NET.execute(() -> {
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
            MAIN.post(() -> done.got(fn, fLat, fLon));
        });
    }

    private static String get(String query) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API + Uri.encode(query)).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
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
