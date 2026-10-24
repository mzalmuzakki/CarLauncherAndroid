package com.orcahome.demoplayer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * Screenshot helper only, never installed on the head unit: publishes one "playing" media
 * session so Orca Home's music card has something to show on the emulator, then closes.
 */
public class DemoActivity extends Activity {
    private static MediaSession session;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (session == null) {
            session = new MediaSession(getApplicationContext(), "demo");
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { state(PlaybackState.STATE_PLAYING); }
                @Override public void onPause() { state(PlaybackState.STATE_PAUSED); }
            });
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Sunset Drive")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Demo Artist")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, 214000)
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art())
                    .build());
            state(PlaybackState.STATE_PLAYING);
            session.setActive(true);
        }
        finish();
    }

    private static void state(int s) {
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE)
                .setState(s, 83000, 1f, SystemClock.elapsedRealtime())
                .build());
    }

    private static Bitmap art() {
        Bitmap bm = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 320, 320, 0xFFFF7A59, 0xFF6A3DE8, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 320, 320, p);
        p.setShader(null);
        p.setColor(0x55FFFFFF);
        c.drawCircle(160, 200, 90, p);
        p.setColor(0xFF1B1035);
        c.drawRect(0, 230, 320, 320, p);
        return bm;
    }
}
