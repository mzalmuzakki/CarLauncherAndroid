package com.orcahome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The last Spotify song (title, artist, cover), kept on the unit so the music card can show
 * it as soon as the car starts, before Spotify has a session again. Written once per song,
 * off the main thread.
 */
final class LastPlayed {
    static final String SPOTIFY = "com.spotify.music";
    private static final String DEMO = "com.orcahome.demoplayer";
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();

    final String pkg, title, artist;
    final Bitmap art; // may be null

    private LastPlayed(String pkg, String title, String artist, Bitmap art) {
        this.pkg = pkg;
        this.title = title;
        this.artist = artist;
        this.art = art;
    }

    /** Spotify only (test builds: also the screenshot demo app). */
    static boolean remembers(Context c, String pkg) {
        if (SPOTIFY.equals(pkg)) return true;
        return DEMO.equals(pkg) && (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private static String savedKey; // what is on disk, so the same song isn't written again

    static void save(Context c, String pkg, MediaMetadata m) {
        if (m == null || !remembers(c, pkg)) return;
        String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(title)) return;
        String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(artist)) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        // The cover often arrives a moment after the title: save again once it's there.
        String key = pkg + "\n" + title + "\n" + artist + "\n" + (art != null);
        if (key.equals(savedKey)) return;
        savedKey = key;
        final Context app = c.getApplicationContext();
        final String a = artist == null ? "" : artist;
        final Bitmap cover = art;
        WRITER.execute(() -> {
            File f = artFile(app);
            if (cover != null) {
                int side = Math.min(256, Math.max(cover.getWidth(), cover.getHeight()));
                Bitmap small = Bitmap.createScaledBitmap(cover, side, side, true);
                try (FileOutputStream out = new FileOutputStream(f)) {
                    small.compress(Bitmap.CompressFormat.JPEG, 85, out);
                } catch (IOException e) {
                    f.delete();
                }
            } else {
                f.delete();
            }
            prefs(app).edit().putString("pkg", pkg).putString("title", title).putString("artist", a).apply();
        });
    }

    /** The remembered song, or null. Reads one small file: call it when the card is built. */
    static LastPlayed load(Context c) {
        SharedPreferences p = prefs(c);
        String pkg = p.getString("pkg", "");
        String title = p.getString("title", "");
        if (pkg.isEmpty() || title.isEmpty()) return null;
        File f = artFile(c);
        Bitmap art = f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
        return new LastPlayed(pkg, title, p.getString("artist", ""), art);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("lastplayed", Context.MODE_PRIVATE);
    }

    private static File artFile(Context c) { return new File(c.getFilesDir(), "last-cover.jpg"); }
}
