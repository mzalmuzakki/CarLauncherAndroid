package com.orcahome;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Being an enabled notification listener lets the music card read the active
 * media sessions. It also passes Google Maps / Waze guidance to the nav card;
 * every other notification is ignored straight away.
 */
public class MediaListener extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        try {
            for (StatusBarNotification s : getActiveNotifications()) {
                if (NavState.isNavApp(s.getPackageName())) NavState.posted(s);
            }
        } catch (SecurityException ignored) {
        }
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
