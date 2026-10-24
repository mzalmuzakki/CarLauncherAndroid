package com.orcahome;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

/**
 * Turn-by-turn info taken from the navigation notification that Google Maps
 * and Waze show while navigating. Only those two apps are looked at.
 */
final class NavState {
    interface Listener { void onNavChanged(); }

    static final String MAPS = "com.google.android.apps.maps";
    static final String WAZE = "com.waze";

    static String distance = "", instruction = "", eta = "", pkg = "";
    static PendingIntent open;
    static boolean active;

    private static String key;      // the guidance notification, so other notifications can't end it
    private static Icon arrowIcon;
    private static Drawable arrow;  // loaded from arrowIcon only when the card is shown

    private static Listener listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private NavState() {}

    /** Debuggable (test) builds only: guidance from the screenshot demo app counts too. */
    static volatile boolean testApp;
    private static final String TEST_APP = "com.orcahome.demoplayer";

    static boolean isNavApp(String p) { return MAPS.equals(p) || WAZE.equals(p) || (testApp && TEST_APP.equals(p)); }

    static void setListener(Listener l) { listener = l; }

    static void posted(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Icon li = n.getLargeIcon();
        // Guidance is marked as navigation, or (older app versions) is an ongoing notification
        // with a turn arrow and no other category. Location sharing, offline-map downloads and
        // "running in the background" notices don't count.
        boolean nav = Notification.CATEGORY_NAVIGATION.equals(n.category)
                || ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0 && n.category == null && li != null);
        if (!nav) return;
        Bundle x = n.extras;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = x.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence sub = x.getCharSequence(Notification.EXTRA_SUB_TEXT);
        if (title == null && text == null) return;
        final String d = title == null ? "" : title.toString();
        final String i = text == null ? "" : text.toString();
        final String e = sub == null ? "" : sub.toString();
        final PendingIntent pi = n.contentIntent;
        final String p = sbn.getPackageName();
        final String k = sbn.getKey();
        MAIN.post(() -> {
            distance = d; instruction = i; eta = e; open = pi; pkg = p; key = k; active = true;
            arrowIcon = li; arrow = null;
            if (listener != null) listener.onNavChanged();
        });
    }

    static void removed(StatusBarNotification sbn) {
        final String k = sbn.getKey();
        MAIN.post(() -> {
            if (!active || !k.equals(key)) return;
            active = false; arrowIcon = null; arrow = null; open = null; key = null;
            if (listener != null) listener.onNavChanged();
        });
    }

    /** The turn arrow (the guidance's large icon), or null. Main thread, card visible only. */
    static Drawable arrow(Context ctx) {
        if (arrow == null && arrowIcon != null) {
            try { arrow = arrowIcon.loadDrawable(ctx); } catch (RuntimeException ignored) { }
            if (arrow == null) arrowIcon = null; // don't retry a broken icon on every update
        }
        return arrow;
    }
}
