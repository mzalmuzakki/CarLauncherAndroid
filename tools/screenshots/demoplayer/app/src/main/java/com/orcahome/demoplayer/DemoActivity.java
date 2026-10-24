package com.orcahome.demoplayer;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * Screenshot helper only, never installed on the head unit: publishes one "playing" media
 * session so Orca Home's music card has something to show on the emulator, then closes.
 */
public class DemoActivity extends Activity {
    private static MediaSession session;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // "--es nav on|off": a Google Maps-like guidance notification (test builds of
        // Orca Home accept it from this app).
        String nav = getIntent().getStringExtra("nav");
        if (nav != null) { navigation("on".equals(nav)); finish(); return; }
        // "--es drive start|stop": a test GPS driving east (heading 90) at 48 km/h; the
        // emulator's own "geo fix" can't set a heading. Needs the mock-location app-op.
        String drive = getIntent().getStringExtra("drive");
        if (drive != null) {
            lat = -6.18640; lon = 106.82380; speed = 13.3f;
            drive(getApplicationContext(), "start".equals(drive)); finish(); return;
        }
        // "--es place lat,lon": the test GPS parked there (night brightness test).
        String place = getIntent().getStringExtra("place");
        if (place != null) {
            String[] p = place.split(",");
            lat = Double.parseDouble(p[0]); lon = Double.parseDouble(p[1]); speed = 0f;
            drive(getApplicationContext(), true); finish(); return;
        }
        if (session == null) {
            session = new MediaSession(getApplicationContext(), "demo");
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { state(PlaybackState.STATE_PLAYING); }
                @Override public void onPause() { state(PlaybackState.STATE_PAUSED); }
            });
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Sunset Drive")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Demo Artist")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, 214000)
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art())
                    .build());
            state(PlaybackState.STATE_PLAYING);
            session.setActive(true);
        }
        finish();
    }

    private static volatile boolean driving;
    private static volatile double lat, lon;
    private static volatile float speed;

    private static void drive(Context c, boolean on) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        String gps = LocationManager.GPS_PROVIDER;
        if (!on) {
            driving = false;
            try { lm.removeTestProvider(gps); } catch (RuntimeException ignored) { }
            return;
        }
        if (driving) return;
        try {
            lm.addTestProvider(gps, false, true, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
        } catch (RuntimeException alreadyThere) { // left by an earlier, stopped process
        }
        lm.setTestProviderEnabled(gps, true);
        driving = true;
        new Thread(() -> {
            for (int i = 0; driving; i++) {
                Location l = new Location(gps);
                l.setLatitude(lat);
                l.setLongitude(lon + (speed > 0 ? 0.00012 * (i % 120) : 0));
                l.setSpeed(speed);
                l.setBearing(90f);
                l.setAccuracy(5f);
                l.setAltitude(10);
                l.setTime(System.currentTimeMillis());
                l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                try { lm.setTestProviderLocation(gps, l); } catch (RuntimeException e) { driving = false; }
                SystemClock.sleep(1000);
            }
        }).start();
    }

    private void navigation(boolean on) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (!on) { nm.cancel(1); return; }
        nm.createNotificationChannel(new NotificationChannel("nav", "Navigation", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "nav")
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setOngoing(true)
                .setContentTitle("300 m")
                .setContentText("Turn left onto Jl. Kebon Sirih")
                .setSubText("19:33 arrival \u00b7 12 min")
                .setLargeIcon(turnLeft())
                .build();
        nm.notify(1, n);
    }

    private static Bitmap turnLeft() {
        Bitmap bm = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(16);
        p.setStrokeJoin(Paint.Join.ROUND);
        Path stem = new Path();
        stem.moveTo(84, 118);
        stem.lineTo(84, 56);
        stem.lineTo(36, 56);
        c.drawPath(stem, p);
        p.setStyle(Paint.Style.FILL);
        Path head = new Path();
        head.moveTo(14, 56);
        head.lineTo(46, 28);
        head.lineTo(46, 84);
        head.close();
        c.drawPath(head, p);
        return bm;
    }

    private static void state(int s) {
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE)
                .setState(s, 83000, 1f, SystemClock.elapsedRealtime())
                .build());
    }

    private static Bitmap art() {
        Bitmap bm = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 320, 320, 0xFFFF7A59, 0xFF6A3DE8, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 320, 320, p);
        p.setShader(null);
        p.setColor(0x55FFFFFF);
        c.drawCircle(160, 200, 90, p);
        p.setColor(0xFF1B1035);
        c.drawRect(0, 230, 320, 320, p);
        return bm;
    }
}
