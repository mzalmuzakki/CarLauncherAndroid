package com.orcahome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.LruCache;
import android.widget.ImageView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Launchable apps, cached until a package is installed, removed or changed. */
final class Apps {
    static final class Entry {
        final ComponentName cn;
        final String label;

        Entry(ComponentName cn, String label) {
            this.cn = cn;
            this.label = label;
        }

        String key() { return cn.flattenToShortString(); }
    }

    /** Home tiles on first run, in this order, when installed. */
    private static final String[] DEFAULT_FAVORITES = {
        "com.zjinnova.zlink", "com.spotify.music", "com.google.android.youtube",
        "com.google.android.apps.youtube.kids", "com.netflix.mediaclient",
        "com.google.android.apps.maps", "tstudio.nfc.ceksaldo",
    };

    /** Tiles that fit beside the map (row below it, or two columns in the square-map layout);
     *  the app-grid layout shows them all. */
    static final int MAP_ROW_TILES = 6, MAP_COLUMN_TILES = 7;

    static int mapTiles(Prefs p) { return "tall".equals(p.mapLayout()) ? MAP_COLUMN_TILES : MAP_ROW_TILES; }

    private static List<Entry> cache;
    private static final LruCache<String, Drawable> ICONS = new LruCache<>(120);
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Apps() {}

    static synchronized void invalidate() { cache = null; }

    /** Loads the app list off the main thread, then runs done on the main thread. */
    static void preload(Context c, Runnable done) {
        final Context app = c.getApplicationContext();
        LOADER.execute(() -> {
            all(app);
            MAIN.post(done);
        });
    }

    static synchronized List<Entry> all(Context c) {
        if (cache != null) return cache;
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<Entry> list = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            list.add(new Entry(cn, String.valueOf(ri.loadLabel(pm))));
        }
        final Collator col = Collator.getInstance();
        Collections.sort(list, (a, b) -> col.compare(a.label, b.label));
        cache = list;
        return list;
    }

    static Map<String, Entry> byKey(Context c) {
        Map<String, Entry> m = new HashMap<>();
        for (Entry e : all(c)) m.put(e.key(), e);
        return m;
    }

    /** Saved home tiles that are still installed; defaults on first run. */
    static List<Entry> favorites(Context c, Prefs p) {
        List<String> keys = p.favorites();
        if (keys == null) {
            keys = defaults(c);
            p.setFavorites(keys);
        }
        Map<String, Entry> m = byKey(c);
        List<Entry> out = new ArrayList<>();
        for (String k : keys) {
            Entry e = m.get(k);
            if (e != null) out.add(e);
        }
        return out;
    }

    private static List<String> defaults(Context c) {
        List<Entry> all = all(c);
        List<String> keys = new ArrayList<>();
        for (String pkg : DEFAULT_FAVORITES) {
            for (Entry e : all) {
                if (e.cn.getPackageName().equals(pkg)) { keys.add(e.key()); break; }
            }
        }
        // The head unit's own radio app, whatever its package is called.
        for (Entry e : all) {
            String l = e.label.toLowerCase(Locale.ROOT);
            if ((l.contains("radio") || l.equals("fm")) && !keys.contains(e.key())) { keys.add(e.key()); break; }
        }
        for (Entry e : all) {
            if (e.cn.getPackageName().equals("com.android.settings")) { keys.add(e.key()); break; }
        }
        return keys;
    }

    static void launch(Context c, ComponentName cn) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(c, "Can't open this app", Toast.LENGTH_SHORT).show();
        }
    }

    static void openAppInfo(Context c, String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); } catch (ActivityNotFoundException ignored) { }
    }

    /** Sets the app icon, loading it off the main thread the first time. */
    static void icon(final ImageView v, final Entry e) {
        final String key = e.key();
        Drawable d = ICONS.get(key);
        v.setTag(key);
        if (d != null) { v.setImageDrawable(d); return; }
        v.setImageDrawable(null);
        final PackageManager pm = v.getContext().getApplicationContext().getPackageManager();
        LOADER.execute(() -> {
            Drawable loaded;
            try {
                loaded = pm.getActivityIcon(e.cn);
            } catch (PackageManager.NameNotFoundException ex) {
                loaded = pm.getDefaultActivityIcon();
            }
            ICONS.put(key, loaded);
            final Drawable fin = loaded;
            MAIN.post(() -> { if (key.equals(v.getTag())) v.setImageDrawable(fin); });
        });
    }
}
