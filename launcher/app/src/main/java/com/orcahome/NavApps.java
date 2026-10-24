package com.orcahome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/** Installed maps apps, in the order the navigation button tries them. */
final class NavApps {
    /** Known maps apps, preferred first. Anything else that opens geo: links comes after. */
    private static final String[] KNOWN = {
        NavState.MAPS, NavState.WAZE,
        "app.organicmaps", "app.comaps", "net.osmand", "net.osmand.plus",
        "com.here.app.maps", "com.sygic.aura", "com.huawei.maps.app", "com.mapswithme.maps.pro",
    };
    /** Opens geo: links but is not a maps app the user navigates with. */
    private static final String[] NOT_MAPS = {"com.zjinnova.zlink", "net.easyconn", "com.orcahome"};

    private NavApps() {}

    static List<String> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String> out = new ArrayList<>();
        for (String p : KNOWN) {
            if (pm.getLaunchIntentForPackage(p) != null) out.add(p);
        }
        Intent geo = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=test"));
        for (ResolveInfo ri : pm.queryIntentActivities(geo, 0)) {
            String p = ri.activityInfo.packageName;
            if (out.contains(p) || isExcluded(p) || pm.getLaunchIntentForPackage(p) == null) continue;
            out.add(p);
        }
        return out;
    }

    /** The chosen maps app if it is still installed, otherwise the first installed one; null if none. */
    static String preferred(Context c, Prefs prefs) {
        List<String> apps = installed(c);
        String chosen = prefs.navApp();
        if (apps.contains(chosen)) return chosen;
        return apps.isEmpty() ? null : apps.get(0);
    }

    static String label(Context c, String pkg) {
        // Google Maps calls itself just "Maps"; use the names people know.
        if (NavState.MAPS.equals(pkg)) return "Google Maps";
        if ("app.organicmaps".equals(pkg)) return "Organic Maps";
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static boolean isExcluded(String p) {
        for (String x : NOT_MAPS) if (x.equals(p)) return true;
        return false;
    }
}
