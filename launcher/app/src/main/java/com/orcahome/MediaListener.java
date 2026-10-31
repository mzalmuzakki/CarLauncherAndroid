package com.orcahome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Being an enabled notification listener lets the music card read the active
 * media sessions. It also passes Google Maps / Waze guidance to the nav card;
 * every other notification is ignored straight away.
 */
public class MediaListener extends NotificationListenerService {
    /** False until Android has connected this reader (see MainActivity.rebindListener). */
    static volatile boolean connected;

    /** Car off / on (screen off / on): trip info, the service odometer and the start-up volume. */
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) Drive.get(c).screenOff();
            else Drive.get(c).screenOn();
        }
    };
    private boolean screenWatched;

    @Override
    public void onListenerConnected() {
        connected = true;
        if (!screenWatched) {
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screen, f);
            screenWatched = true;
        }
        if (getSystemService(PowerManager.class).isInteractive()) Drive.get(this).start();
        long until = new Prefs(this).carDataUntil(); // car-data recording carries on after a restart
        if (until > System.currentTimeMillis()) CanRecorder.get(this).startUntil(until);
        // Test builds (debuggable) also take test guidance from the screenshot demo app.
        NavState.testApp = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        try {
            for (StatusBarNotification s : getActiveNotifications()) {
                if (NavState.isNavApp(s.getPackageName())) NavState.posted(s);
            }
        } catch (SecurityException ignored) {
        }
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
    }

    @Override
    public void onDestroy() {
        if (screenWatched) unregisterReceiver(screen);
        screenWatched = false;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (NavState.isNavApp(sbn.getPackageName())) NavState.posted(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (NavState.isNavApp(sbn.getPackageName())) NavState.removed(sbn);
    }
}
