package com.orcahome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;

/**
 * The play button on the "last played" card: carry on with the last song without leaving
 * the home screen. First "play" goes to the player's media button receiver (like a Bluetooth
 * headset), and play is pressed in its session once it's there. If the player doesn't start
 * in the background (NWD firmware blocks apps not opened since start-up), it is opened,
 * play is pressed as soon as it's ready, and the home screen comes back.
 * Checks once a second, only for the ~20 s after a tap.
 */
final class Resume {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context app;
    private static String pkg;
    private static int tries;
    private static boolean opened;

    private Resume() {}

    static void start(Context c, String player) {
        app = c.getApplicationContext();
        pkg = player;
        tries = 0;
        opened = false;
        MAIN.removeCallbacks(TICK);
        if (!sendPlay(player)) open(); // no receiver: opening it is the only way
        MAIN.postDelayed(TICK, 1000);
    }

    private static final Runnable TICK = Resume::tick;

    private static void tick() {
        if (pkg == null) return;
        tries++;
        MediaController c = session(pkg);
        int state = c == null || c.getPlaybackState() == null ? PlaybackState.STATE_NONE : c.getPlaybackState().getState();
        if (state == PlaybackState.STATE_PLAYING) { playing(); return; }
        boolean starting = state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING;
        if (c != null && !starting && tries % 2 == 0) c.getTransportControls().play(); // there, but not playing yet
        // Nothing after 3 s (or a session that won't play after 6 s): open the player,
        // unless the home screen was left meanwhile.
        if (!opened && MainActivity.shown && (c == null ? tries >= 3 : tries >= 6)) open();
        if (tries >= (opened ? 20 : 12)) {
            Log.i("OrcaHome", "resume: " + pkg + " did not start");
            pkg = null;
            return;
        }
        MAIN.postDelayed(TICK, 1000);
    }

    private static void playing() {
        Log.i("OrcaHome", "resume: " + pkg + " playing" + (opened ? ", back to the home screen" : ""));
        if (opened) { // it was opened to start it: back to the home screen
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(new ComponentName(app, MainActivity.class))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { app.startActivity(home); } catch (RuntimeException e) { Log.w("OrcaHome", "resume: " + e); }
        }
        pkg = null;
    }

    /** "Play" to the player's media button receiver; false if it has none. */
    private static boolean sendPlay(String player) {
        List<ResolveInfo> r = app.getPackageManager().queryBroadcastReceivers(
                new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(player), 0);
        if (r.isEmpty()) return false;
        ComponentName cn = new ComponentName(player, r.get(0).activityInfo.name);
        long t = SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            app.sendBroadcast(new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(cn)
                    .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(t, t, action, KeyEvent.KEYCODE_MEDIA_PLAY, 0)));
        }
        Log.i("OrcaHome", "resume: play sent to " + cn.flattenToShortString());
        return true;
    }

    private static void open() {
        opened = true;
        Intent i = app.getPackageManager().getLaunchIntentForPackage(pkg);
        Log.i("OrcaHome", "resume: opening " + pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { app.startActivity(i); } catch (RuntimeException e) { Log.w("OrcaHome", "resume: " + e); }
    }

    private static MediaController session(String player) {
        try {
            MediaSessionManager m = app.getSystemService(MediaSessionManager.class);
            for (MediaController c : m.getActiveSessions(new ComponentName(app, MediaListener.class))) {
                if (player.equals(c.getPackageName())) return c;
            }
        } catch (SecurityException ignored) {
        }
        return null;
    }
}
