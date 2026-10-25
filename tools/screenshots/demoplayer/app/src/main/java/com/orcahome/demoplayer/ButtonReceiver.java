package com.orcahome.demoplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/** "Play" from a media button (Orca Home's last-played card) starts playing in the background. */
public class ButtonReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (c.getSharedPreferences("demo", Context.MODE_PRIVATE).getBoolean("ignore", false)) return;
        KeyEvent k = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (k == null || k.getAction() != KeyEvent.ACTION_UP) return;
        DemoActivity.startSession(c.getApplicationContext(), false);
    }
}
