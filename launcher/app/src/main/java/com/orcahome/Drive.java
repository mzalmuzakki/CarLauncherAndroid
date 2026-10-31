package com.orcahome;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Locale;

/**
 * Trip info and the service odometer: the km driven, from GPS (one fix every 2 s while the car
 * is on, nothing while it sleeps). A trip starts when the car does (the screen comes on after
 * at least 10 minutes off, or the unit starts), which is also when the start-up volume is set.
 * With "background location" (launcher.sh allows it) it counts while other apps are in front
 * too; without, only while the home screen shows. After 10 minutes without moving at all
 * (crawling counts as moving) it stops asking for GPS and listens to fixes other apps get,
 * with a 30-second GPS check every 2 minutes; GPS comes back as soon as the car moves.
 */
final class Drive implements LocationListener {
    interface Listener { void changed(); }

    private static Drive instance;
    private static final long CAR_OFF_MS = 10 * 60_000L;
    private static final long SAVE_MS = 30_000L;
    private final Context app;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean running;
    private boolean idle; // listening only (passive), after standing still
    private long movedAt;
    private static final long IDLE_MS = 10 * 60_000L, PROBE_EVERY_MS = 2 * 60_000L, PROBE_MS = 30_000L;
    private double lastLat = Double.NaN, lastLon;
    private long lastT, savedAt, lastFixAt; // lastT: time of the last counted point
    private Listener listener;

    // Kept in memory, saved every 30 s and when the car goes off.
    private long tripStart, tripMovingMs;
    private double tripM, odoM;

    static synchronized Drive get(Context c) {
        if (instance == null) instance = new Drive(c.getApplicationContext());
        return instance;
    }

    private Drive(Context c) {
        app = c;
        sp = c.getSharedPreferences("drive", Context.MODE_PRIVATE);
        tripStart = sp.getLong("trip_start", 0);
        tripMovingMs = sp.getLong("trip_moving", 0);
        tripM = sp.getFloat("trip_m", 0);
        odoM = sp.getLong("odo_m", 0);
    }

    void setListener(Listener l) { listener = l; }

    /** GPS fixes every 2 s from now on (if allowed). */
    void start() {
        if (running && !idle) return;
        movedAt = System.currentTimeMillis();
        request(LocationManager.GPS_PROVIDER);
        idle = false;
        main.removeCallbacks(probe);
        main.removeCallbacks(probeEnd);
        main.removeCallbacks(idleCheck);
        main.postDelayed(idleCheck, IDLE_MS);
    }

    private void request(String provider) {
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            LocationManager lm = app.getSystemService(LocationManager.class);
            lm.removeUpdates(this);
            lm.requestLocationUpdates(provider, 2000, 0, this, Looper.getMainLooper());
            running = true;
        } catch (RuntimeException e) {
            Log.w("OrcaHome", "trip: " + e);
        }
    }

    /** Standing still for 10 minutes (parked with the screen on): stop asking for GPS. */
    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (!running || idle) return;
            long still = System.currentTimeMillis() - movedAt;
            if (still < IDLE_MS) { main.postDelayed(this, IDLE_MS - still); return; }
            request(LocationManager.PASSIVE_PROVIDER);
            idle = true;
            save();
            main.postDelayed(probe, PROBE_EVERY_MS);
            Log.i("OrcaHome", "trip: standing still, GPS off until the car moves");
        }
    };

    /** While idle: GPS for 30 s every 2 minutes, in case no other app has a fix to share. */
    private final Runnable probe = () -> {
        if (!idle) return;
        request(LocationManager.GPS_PROVIDER);
        main.postDelayed(this.probeEnd, PROBE_MS);
    };

    /** Still not moving after the check: back to listening only. */
    private final Runnable probeEnd = () -> {
        if (!idle) return; // moved: start() already took over
        request(LocationManager.PASSIVE_PROVIDER);
        main.postDelayed(this.probe, PROBE_EVERY_MS);
    };

    /** The car went off (screen off): no GPS while it sleeps. */
    void screenOff() {
        sp.edit().putLong("screen_off", System.currentTimeMillis()).apply();
        main.removeCallbacks(idleCheck);
        main.removeCallbacks(probe);
        main.removeCallbacks(probeEnd);
        if (running) app.getSystemService(LocationManager.class).removeUpdates(this);
        running = false;
        idle = false;
        save();
    }

    /** The screen came on: after 10 minutes off that's the car starting. */
    void screenOn() {
        long off = new Prefs(app).testFast(app) ? 5000 : CAR_OFF_MS;
        if (System.currentTimeMillis() - sp.getLong("screen_off", 0) >= off) carStarted();
        start();
    }

    /** New trip, and the start-up volume (a few seconds later, after the firmware sets its own). */
    void carStarted() {
        Log.i("OrcaHome", "car started: new trip");
        newTrip();
        int pct = new Prefs(app).startVolume();
        if (pct < 0) return;
        main.postDelayed(() -> {
            AudioManager am = app.getSystemService(AudioManager.class);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int v = Math.round(pct * max / 100f);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            Log.i("OrcaHome", "start-up volume: " + v + "/" + max + " (now " + am.getStreamVolume(AudioManager.STREAM_MUSIC) + ")");
        }, 4000);
    }

    private void newTrip() {
        tripStart = System.currentTimeMillis();
        tripM = 0;
        tripMovingMs = 0;
        lastLat = Double.NaN;
        save();
        if (listener != null) listener.changed();
    }

    @Override
    public void onLocationChanged(Location l) {
        long now = System.currentTimeMillis();
        long sinceFix = lastFixAt > 0 ? now - lastFixAt : 0;
        if (tripStart == 0 || sinceFix > 30 * 60_000L) newTrip(); // a start nobody saw
        lastFixAt = now;
        if (!l.hasAccuracy() || l.getAccuracy() > 40) return;
        if (Double.isNaN(lastLat)) { mark(l, now); return; }
        float[] d = new float[1];
        Location.distanceBetween(lastLat, lastLon, l.getLatitude(), l.getLongitude(), d);
        long dt = now - lastT;
        // No fixes for two minutes (a tunnel, GPS off): start again from here. A jump faster
        // than 250 km/h is a bad fix. Standing or crawling keeps the last point (below), so
        // after a long jam the way crawled still counts.
        if (sinceFix > 120_000 || d[0] / Math.max(1, dt / 1000.0) > 70) { mark(l, now); return; }
        // Crawling (from about 3 km/h; slower is GPS wander) is movement for the idle timer.
        if (l.hasSpeed() ? l.getSpeed() > 0.8f : d[0] >= 8) {
            movedAt = now;
            if (idle) start(); // moving again: GPS back
        }
        boolean moving = l.hasSpeed() ? l.getSpeed() > 1.5f : d[0] >= 10;
        if (!moving) return; // counted once the car moves on
        tripM += d[0];
        odoM += d[0];
        tripMovingMs += Math.min(dt, 10_000);
        mark(l, now);
        if (now - savedAt > SAVE_MS) save();
        if (listener != null) listener.changed();
    }

    private void mark(Location l, long now) {
        lastLat = l.getLatitude();
        lastLon = l.getLongitude();
        lastT = now;
    }

    private void save() {
        savedAt = System.currentTimeMillis();
        sp.edit().putLong("trip_start", tripStart).putLong("trip_moving", tripMovingMs)
                .putFloat("trip_m", (float) tripM).putLong("odo_m", Math.round(odoM)).apply();
    }

    /** km driven, by GPS, since Orca Home started counting (for the service reminder). */
    double odometerKm() { return odoM / 1000; }

    /** "12.4 km · 0:32" (distance and time since the car started), or "" before the first 100 m. */
    String tripText() {
        if (tripStart == 0 || tripM < 100) return "";
        long mins = (System.currentTimeMillis() - tripStart) / 60_000;
        return String.format(Locale.US, "%.1f km · %d:%02d", tripM / 1000, mins / 60, mins % 60);
    }

    /** "avg 23 km/h" over the time actually moving, or "". */
    String averageText() {
        if (tripMovingMs < 60_000 || tripM < 500) return "";
        return "avg " + Math.round(tripM / 1000 / (tripMovingMs / 3600000.0)) + " km/h";
    }

    @Override public void onStatusChanged(String p, int s, Bundle b) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }
}
