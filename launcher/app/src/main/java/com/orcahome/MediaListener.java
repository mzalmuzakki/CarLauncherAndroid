package com.orcahome;

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

    @Override
    public void onListenerConnected() {
        connected = true;
        Drive.get(this).start(); // trip info and the service km (Drive watches the screen from here on)
        long until = new Prefs(this).carDataUntil(); // car-data recording carries on after a restart
        if (until > System.currentTimeMillis()) CanRecorder.get(this).startUntil(until, true);
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
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (NavState.isNavApp(sbn.getPackageName())) NavState.posted(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (NavState.isNavApp(sbn.getPackageName())) NavState.removed(sbn);
    }
}
