package com.orcahome;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Some head-unit firmwares (NWD) ask "Select a Home app" after a reboot and list only their
 * own launchers, even when Orca Home is the home screen. This opens Orca Home once the unit
 * has started, over that box. It runs only at boot, and only acts when Orca Home may
 * "display over other apps" (granted by scripts/launcher.sh, taken back by 'off'):
 * Android 10 needs that to open a screen from the background anyway.
 */
public class BootReceiver extends BroadcastReceiver {
    // The firmware's box can come up a few seconds after start-up, on top of Orca Home.
    private static final long SECOND_TRY_MS = 8000;

    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        Brightness.apply(c); // alarms don't survive a reboot
        if (!Settings.canDrawOverlays(c)) {
            Log.i("OrcaHome", "start-up: not opening (no \"display over other apps\")");
            return;
        }
        Log.i("OrcaHome", "start-up: opening Orca Home");
        final Context app = c.getApplicationContext();
        final PendingResult pending = goAsync();
        open(app);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!MainActivity.shown) open(app);
            pending.finish();
        }, SECOND_TRY_MS);
    }

    private static void open(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(new ComponentName(c, MainActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(home); } catch (RuntimeException e) { Log.w("OrcaHome", "start-up: " + e); }
    }
}
