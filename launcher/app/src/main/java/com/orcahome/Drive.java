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
 * too; without, only while the home screen shows.
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
    private double lastLat = Double.NaN, lastLon;
    private long lastT, savedAt;
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
        if (running) return;
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            app.getSystemService(LocationManager.class)
                    .requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, this, Looper.getMainLooper());
            running = true;
        } catch (RuntimeException e) {
            Log.w("OrcaHome", "trip: " + e);
        }
    }

    /** The car went off (screen off): no GPS while it sleeps. */
    void screenOff() {
        sp.edit().putLong("screen_off", System.currentTimeMillis()).apply();
        if (running) app.getSystemService(LocationManager.class).removeUpdates(this);
        running = false;
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
        if (tripStart == 0 || (lastT > 0 && now - lastT > 30 * 60_000L)) newTrip(); // a start nobody saw
        if (!l.hasAccuracy() || l.getAccuracy() > 40) return;
        if (Double.isNaN(lastLat)) { mark(l, now); return; }
        float[] d = new float[1];
        Location.distanceBetween(lastLat, lastLon, l.getLatitude(), l.getLongitude(), d);
        long dt = now - lastT;
        if (dt > 120_000 || d[0] / Math.max(1, dt / 1000.0) > 70) { mark(l, now); return; } // a gap or a jump: don't count it
        boolean moving = l.hasSpeed() ? l.getSpeed() > 1.5f : d[0] >= 10;
        if (!moving) { lastT = now; return; } // standing: GPS wander isn't distance
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

    /** "12.4 km · 0:32" (distance and time since the car started), or "" before the first km. */
    String tripText() {
        if (tripStart == 0) return "";
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
