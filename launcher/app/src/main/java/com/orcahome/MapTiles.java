package com.orcahome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OpenStreetMap tiles: kept in memory (small LRU, 16-bit colour), cached on
 * storage (capped), downloaded only when missing and only while the map is
 * visible. No map engine and no API key. Every style uses the same tiles; the
 * dark style is drawn through a colour filter (see MapPanel), so switching
 * themes needs no new downloads.
 */
final class MapTiles {
    static final String STYLE_DARK = "dark", STYLE_LIGHT = "light", STYLE_CLASSIC = "osm";

    private static final long DISK_MAX_BYTES = 50L * 1024 * 1024;
    private static final long FRESH_MS = 14L * 24 * 3600 * 1000; // map data changes slowly
    private static final String USER_AGENT = "OrcaHome/2.7 (personal car head unit launcher)";

    // ~40 tiles x 128 KB (RGB_565) = ~5 MB of RAM at most.
    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService NET = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> PENDING = new HashSet<>();
    // A tile that failed is not asked for again for a minute; after a network error
    // nothing is downloaded for 30 s (no signal, hotspot not up yet).
    private static final long RETRY_MS = 60_000, OFFLINE_MS = 30_000;
    private static final Map<String, Long> FAILED = new HashMap<>();
    private static volatile long offlineUntil;
    // Queued downloads check these, so leaving home or changing zoom drops the rest of the queue.
    private static volatile boolean active;
    private static volatile int wantZoom = -1;
    private static int downloads;

    interface Listener { void onTileLoaded(); }

    private MapTiles() {}

    static String attribution() { return "© OpenStreetMap contributors"; }

    // CARTO's free basemaps now answer "API key required", so all styles use OpenStreetMap.
    private static String url(int z, int x, int y) {
        return "https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png";
    }

    /** Called by the map: tiles are only loaded while the home screen is visible. */
    static void setActive(boolean a) { active = a; }

    /** The tile if it is in memory; otherwise null, and it is loaded in the background. */
    static Bitmap get(Context ctx, int z, int x, int y, Listener l) {
        final String key = "osm/" + z + "/" + x + "/" + y;
        Bitmap b = MEM.get(key);
        if (b != null) return b;
        wantZoom = z;
        synchronized (PENDING) {
            Long failed = FAILED.get(key);
            if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return null;
            if (!PENDING.add(key)) return null;
        }
        final File dir = new File(ctx.getCacheDir(), "tiles");
        final File f = new File(dir, key.replace('/', '_') + ".png");
        NET.execute(() -> {
            Bitmap loaded = null;
            boolean failed = false;
            if (active && z == wantZoom) {
                boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < FRESH_MS;
                if (!fresh && System.currentTimeMillis() >= offlineUntil) {
                    try {
                        failed = !download(url(z, x, y), f);
                        if (!failed && ++downloads % 200 == 1) prune(dir);
                    } catch (Exception e) {
                        failed = true;
                        offlineUntil = System.currentTimeMillis() + OFFLINE_MS;
                    }
                }
                // Offline or a failed download: an old tile is better than none.
                if (f.exists()) loaded = decode(f);
                else failed = true;
            }
            final Bitmap fin = loaded;
            final boolean fail = failed;
            MAIN.post(() -> {
                synchronized (PENDING) {
                    PENDING.remove(key);
                    if (fail) FAILED.put(key, System.currentTimeMillis()); else FAILED.remove(key);
                    if (FAILED.size() > 500) FAILED.clear();
                }
                if (fin != null) { MEM.put(key, fin); l.onTileLoaded(); }
            });
        });
        return null;
    }

    private static Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** True when the tile was saved; false when the server said no (e.g. 403/429). */
    private static boolean download(String url, File f) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        File tmp = new File(f.getPath() + ".tmp");
        try {
            if (c.getResponseCode() != 200) return false;
            f.getParentFile().mkdirs();
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            return tmp.renameTo(f);
        } finally {
            tmp.delete();
            c.disconnect();
        }
    }

    /** Keeps the tile cache under DISK_MAX_BYTES by deleting the oldest tiles (and leftovers). */
    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) {
            String n = f.getName();
            // Leftover downloads, and CARTO tiles from version 2.1 (they only say "API key required").
            if ((n.endsWith(".tmp") && System.currentTimeMillis() - f.lastModified() > 60_000) || n.startsWith("carto-")) f.delete();
            else total += f.length();
        }
        if (total <= DISK_MAX_BYTES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= DISK_MAX_BYTES * 3 / 4) break;
            total -= f.length();
            f.delete();
        }
    }
}
