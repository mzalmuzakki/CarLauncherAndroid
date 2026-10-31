package com.orcahome;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Current weather from Open-Meteo (free, no account). Fetched at most every
 * 30 minutes and only when the home screen asks, i.e. while it is visible.
 */
final class Weather {
    static final long MAX_AGE_MS = 30 * 60 * 1000L;
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long RETRY_MS = 60 * 1000L;
    private static boolean running;
    private static long lastTry;

    private Weather() {}

    /** Refreshes if the cached weather is old; calls done on the main thread when updated. */
    static void refreshIfStale(Context ctx, Prefs prefs, Runnable done) {
        long now = System.currentTimeMillis();
        if (running || now - prefs.weatherTime() < MAX_AGE_MS || now - lastTry < RETRY_MS) return;
        lastTry = now;
        final Location loc = lastLocation(ctx);
        if (loc == null) return;
        running = true;
        final Context app = ctx.getApplicationContext();
        NET.execute(() -> {
            try {
                String url = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code"
                                + "&minutely_15=precipitation&forecast_minutely_15=8&timezone=auto",
                        loc.getLatitude(), loc.getLongitude());
                JSONObject all = new JSONObject(get(url));
                JSONObject cur = all.getJSONObject("current");
                float temp = (float) cur.getDouble("temperature_2m");
                int code = cur.getInt("weather_code");
                long rain = rainStart(all, isRain(code));
                android.util.Log.i("OrcaHome", "weather: code " + code + ", rain expected: " + (rain > 0 ? new java.util.Date(rain) : "no"));
                String city = cityName(app, loc);
                MAIN.post(() -> {
                    prefs.setWeather(temp, code, city, rain);
                    running = false;
                    done.run();
                });
            } catch (Exception e) {
                MAIN.post(() -> running = false);
            }
        });
    }

    private static Location lastLocation(Context c) { return DayNight.lastKnown(c); }

    static boolean isRain(int code) { return code >= 51 && code <= 99 && !(code >= 71 && code <= 77); }

    /**
     * When rain is expected to start within the next two hours (epoch ms), from the 15-minute
     * forecast; 0 if none, or if it's raining already. Times come in the place's local time.
     */
    static long rainStart(JSONObject all, boolean rainingNow) {
        if (rainingNow) return 0;
        try {
            JSONObject m = all.getJSONObject("minutely_15");
            JSONArray times = m.getJSONArray("time"), mm = m.getJSONArray("precipitation");
            long offset = all.optLong("utc_offset_seconds", 0) * 1000;
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            long now = f.parse(all.getJSONObject("current").getString("time")).getTime();
            for (int i = 0; i < times.length() && i < mm.length(); i++) {
                long t = f.parse(times.getString(i)).getTime();
                if (t > now && t - now <= 2 * 3600_000L && !mm.isNull(i) && mm.getDouble(i) >= 0.3) return t - offset;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String cityName(Context c, Location l) {
        try {
            List<Address> a = new Geocoder(c, Locale.getDefault()).getFromLocation(l.getLatitude(), l.getLongitude(), 1);
            if (a != null && !a.isEmpty()) {
                Address x = a.get(0);
                if (x.getLocality() != null) return x.getLocality();
                if (x.getSubAdminArea() != null) return x.getSubAdminArea();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather code to a short label and an emoji (drawn by the system font). */
    static String label(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 95) return "Thunderstorm";
        return "–";
    }

    static String emoji(int code) {
        if (code == 0) return "☀️";
        if (code <= 2) return "⛅";
        if (code == 3 || code == 45 || code == 48) return "☁️";
        if (code >= 95) return "⛈️";
        if (code >= 51 && code <= 82) return "🌧️";
        return "☁️";
    }
}
