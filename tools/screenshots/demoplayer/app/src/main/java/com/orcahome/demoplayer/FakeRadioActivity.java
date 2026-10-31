package com.orcahome.demoplayer;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/** Screenshot helper only: the stand-in radio's "screen" (opens and closes straight away). */
public class FakeRadioActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.i("FakeRadio", "radio screen opened");
        finish();
    }
}
