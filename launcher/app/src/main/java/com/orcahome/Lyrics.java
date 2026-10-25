package com.orcahome;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Synced lyrics from LRCLIB (lrclib.net: free, no account), looked up by title, artist and
 * length from the playing app's media session, so they work for Spotify and any other player.
 * One small request per new song, only while the home screen shows lyrics; answers are kept
 * on the unit (max 300 songs), so songs played again need no data.
 */
final class Lyrics {
    /** l: the lyrics, or null; failed: they couldn't be loaded (no internet), so try again later. */
    interface Done { void got(Lyrics l, boolean failed); }

    /** Line start times (ms) and texts, in order; empty when only plain lyrics exist. */
    final long[] times;
    final String[] lines;
    final String plain;

    private Lyrics(long[] times, String[] lines, String plain) {
        this.times = times;
        this.lines = lines;
        this.plain = plain;
    }

    /** A line without words (the music plays on). */
    static final String BREAK = "\u266A";

    boolean synced() { return times.length > 0; }

    /** The line sung at pos, or -1 before the first one. */
    int index(long pos) {
        int i = Arrays.binarySearch(times, pos);
        return i >= 0 ? i : -i - 2;
    }

    private static final String API = "https://lrclib.net/api/";
    private static final String UA = "OrcaHome/2.7 (https://github.com/mzalmuzakki/CarLauncherAndroid)";
    private static final Lyrics NONE = new Lyrics(new long[0], new String[0], "");
    private static final long RETRY_NONE_MS = 7L * 24 * 3600 * 1000;
    private static final int MAX_FILES = 300;
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Lyrics> MEMORY = new LruCache<>(20);

    /** The user's timing fix for one song (ms; positive = lyrics come earlier). */
    static int offset(Context c, String key) {
        return c.getSharedPreferences("lyricsoffset", Context.MODE_PRIVATE).getInt(key, 0);
    }

    static void setOffset(Context c, String key, int ms) {
        android.content.SharedPreferences.Editor e = c.getSharedPreferences("lyricsoffset", Context.MODE_PRIVATE).edit();
        (ms == 0 ? e.remove(key) : e.putInt(key, ms)).apply();
    }

    static String key(String artist, String title) {
        return (artist + "\n" + title).toLowerCase(Locale.ROOT);
    }

    /** Calls done on the main thread: the lyrics, or null if there are none (or no internet). */
    static void get(Context c, String artist, String title, String album, long durationMs, Done done) {
        final String key = key(artist, title);
        Lyrics m = MEMORY.get(key);
        if (m != null) { done.got(m == NONE ? null : m, false); return; }
        final File dir = new File(c.getCacheDir(), "lyrics");
        final boolean log = (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        NET.execute(() -> {
            Lyrics l = fromDisk(dir, key);
            if (l == null) {
                try {
                    JSONObject j = lookup(artist, title, album, durationMs);
                    l = j == null ? NONE : parse(str(j, "syncedLyrics"), str(j, "plainLyrics"));
                    toDisk(dir, key, j);
                } catch (IOException | JSONException | RuntimeException e) {
                    if (log) Log.d("OrcaHome", "lyrics: " + e);
                    l = null; // offline: try again next time
                }
            }
            if (log) Log.i("OrcaHome", "lyrics for \"" + title + "\": "
                    + (l == null ? "not loaded" : l == NONE ? "none" : l.synced() ? l.lines.length + " synced lines" : "plain only"));
            if (l != null) MEMORY.put(key, l);
            final Lyrics fin = l == NONE ? null : l;
            final boolean failed = l == null;
            MAIN.post(() -> done.got(fin, failed));
        });
    }

    /** Exact match first (title, artist, album, length), then a search by title and artist. */
    private static JSONObject lookup(String artist, String title, String album, long durationMs)
            throws IOException, JSONException {
        if (!TextUtils.isEmpty(album) && durationMs > 0) {
            String q = "get?artist_name=" + Uri.encode(artist) + "&track_name=" + Uri.encode(title)
                    + "&album_name=" + Uri.encode(album) + "&duration=" + Math.round(durationMs / 1000.0);
            String body = fetch(q);
            if (body != null) {
                JSONObject j = new JSONObject(body.isEmpty() ? "{}" : body);
                if (j.has("syncedLyrics") || j.has("plainLyrics")) return j;
            }
        }
        JSONObject best = searchBest(artist, title, durationMs);
        if (best == null && !clean(title).equals(title)) best = searchBest(artist, clean(title), durationMs);
        return best;
    }

    private static JSONObject searchBest(String artist, String title, long durationMs)
            throws IOException, JSONException {
        String body = fetch("search?artist_name=" + Uri.encode(artist) + "&track_name=" + Uri.encode(title));
        if (body == null) return null;
        JSONArray a = new JSONArray(body);
        JSONObject best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.getJSONObject(i);
            boolean synced = !str(j, "syncedLyrics").isEmpty();
            double off = durationMs > 0 ? Math.abs(j.optDouble("duration", 0) - durationMs / 1000.0) : 0;
            if (durationMs > 0 && off > 8) continue; // another version of the song
            double score = off + (synced ? 0 : 100);
            if (score < bestScore) { bestScore = score; best = j; }
        }
        return best;
    }

    /** "Song - Remastered 2011" / "Song (feat. X)" -> "Song". */
    static String clean(String title) {
        String t = title.replaceAll("\\s*[(\\[](feat\\.|ft\\.|with |from )[^)\\]]*[)\\]]", "");
        int dash = t.indexOf(" - ");
        if (dash > 0) t = t.substring(0, dash);
        return t.trim();
    }

    /** The body, or null for 404 (not found). */
    private static String fetch(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", UA);
        try {
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) { return read(in); }
        } finally {
            c.disconnect();
        }
    }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");

    static Lyrics parse(String synced, String plain) {
        List<long[]> idx = new ArrayList<>(); // {time, line number}
        List<String> texts = new ArrayList<>();
        if (synced != null) {
            for (String raw : synced.split("\\r?\\n")) {
                Matcher m = STAMP.matcher(raw);
                List<Long> at = new ArrayList<>();
                int end = 0;
                while (m.find() && m.start() == end) {
                    at.add(Long.parseLong(m.group(1)) * 60000 + Math.round(Double.parseDouble(m.group(2)) * 1000));
                    end = m.end();
                }
                if (at.isEmpty()) continue;
                String text = raw.substring(end).trim();
                for (long t : at) { idx.add(new long[]{t, texts.size()}); }
                texts.add(text.isEmpty() ? BREAK : text);
            }
        }
        Collections.sort(idx, (x, y) -> Long.compare(x[0], y[0]));
        long[] times = new long[idx.size()];
        String[] lines = new String[idx.size()];
        for (int i = 0; i < idx.size(); i++) { times[i] = idx.get(i)[0]; lines[i] = texts.get((int) idx.get(i)[1]); }
        String p = plain == null ? "" : plain.trim();
        if (times.length == 0 && p.isEmpty()) return NONE;
        return new Lyrics(times, lines, p);
    }

    // ---- disk cache: one small JSON file per song ("{}" = LRCLIB has none) --------------

    private static File file(File dir, String key) {
        return new File(dir, Integer.toHexString(key.hashCode()) + "-" + key.length() + ".json");
    }

    private static Lyrics fromDisk(File dir, String key) {
        File f = file(dir, key);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            JSONObject j = new JSONObject(read(in));
            if (!key.equals(j.optString("key"))) return null;
            if (!j.has("syncedLyrics") && !j.has("plainLyrics")) {
                if (System.currentTimeMillis() - f.lastModified() > RETRY_NONE_MS) return null;
                return NONE;
            }
            return parse(str(j, "syncedLyrics"), str(j, "plainLyrics"));
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }

    private static void toDisk(File dir, String key, JSONObject found) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            JSONObject j = new JSONObject();
            j.put("key", key);
            if (found != null) {
                if (!str(found, "syncedLyrics").isEmpty()) j.put("syncedLyrics", str(found, "syncedLyrics"));
                if (!str(found, "plainLyrics").isEmpty()) j.put("plainLyrics", str(found, "plainLyrics"));
            }
            try (FileOutputStream out = new FileOutputStream(file(dir, key))) {
                out.write(j.toString().getBytes(StandardCharsets.UTF_8));
            }
            File[] all = dir.listFiles();
            if (all != null && all.length > MAX_FILES) {
                Arrays.sort(all, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
                for (int i = 0; i < all.length - MAX_FILES; i++) all[i].delete();
            }
        } catch (IOException | JSONException ignored) {
        }
    }

    /** optString gives "null" for a JSON null. */
    private static String str(JSONObject j, String k) { return j.isNull(k) ? "" : j.optString(k, ""); }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
