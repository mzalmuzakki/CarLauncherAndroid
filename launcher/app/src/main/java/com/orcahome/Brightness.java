package com.orcahome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * Night brightness: at sunset the screen dims to the chosen level, at sunrise it goes back
 * to the brightness (and auto/manual mode) it had before. Only the switch itself changes
 * anything, so turning the brightness up by hand at night stays as set until sunrise.
 * Needs "modify system settings" (granted by scripts/launcher.sh, taken back by 'off').
 * One non-waking alarm per sunrise/sunset; a sleeping unit catches up when it wakes.
 */
final class Brightness {
    private Brightness() {}

    static boolean allowed(Context c) { return Settings.System.canWrite(c); }

    /** Dims or restores for the time of day now, then sets the alarm for the next change. */
    static void apply(Context c) {
        Prefs p = new Prefs(c);
        long now = System.currentTimeMillis();
        if (!allowed(c)) return;
        boolean dim = p.nightDim() && DayNight.isNight(c, now);
        ContentResolver r = c.getContentResolver();
        try {
            if (dim && !p.dimmed()) {
                p.setDimmed(true,
                        Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 128),
                        Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, 0));
                set(r, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, level(p.nightLevel()));
                Log.i("OrcaHome", "night brightness: dimmed to " + p.nightLevel() + "%");
            } else if (!dim && p.dimmed()) {
                set(r, p.dayMode(), p.dayBrightness());
                p.setDimmed(false, p.dayBrightness(), p.dayMode());
                Log.i("OrcaHome", "night brightness: back to " + p.dayBrightness());
            }
        } catch (SecurityException e) {
            Log.w("OrcaHome", "night brightness: " + e);
            return;
        }
        schedule(c, p.nightDim() ? DayNight.nextChange(c, now) + 30_000 : 0);
    }

    /** The night level slider moved: show it right away while dimmed. */
    static void levelChanged(Context c) {
        Prefs p = new Prefs(c);
        if (!p.dimmed() || !allowed(c)) return;
        try {
            set(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, level(p.nightLevel()));
        } catch (SecurityException ignored) {
        }
    }

    static int level(int pct) { return Math.max(5, Math.min(255, Math.round(pct * 255 / 100f))); }

    private static void set(ContentResolver r, int mode, int value) {
        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, mode);
        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, value);
    }

    /** at = 0 cancels. */
    private static void schedule(Context c, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Alarm.class), PendingIntent.FLAG_UPDATE_CURRENT);
        if (at == 0) am.cancel(pi);
        else am.set(AlarmManager.RTC, at, pi);
    }

    /** Sunrise / sunset alarm (not exported). */
    public static class Alarm extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) { apply(c); }
    }
}
