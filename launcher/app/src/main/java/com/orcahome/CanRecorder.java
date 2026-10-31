package com.orcahome;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Records what the unit's CAN box sends (doors, car info, air, radar...) and the firmware's own
 * notices (handbrake, volume, backlight), to learn how this car's data is encoded. Only while
 * switched on in settings (two hours at most). Writes car-data-log.txt in the app's folder on
 * the unit's storage, for: adb pull /sdcard/Android/data/com.orcahome/files/car-data-log.txt
 */
final class CanRecorder {
    static final String NWD_CAN = "com.nwd.can.setting";
    private static final String ACTION = "com.nwd.can.service.ACTION_CAN_SERVICE";
    private static final String FEATURE = "com.nwd.can.sdk.outer.adil.ICanRemote4OuterFeature";
    private static final String MODEL_CB = "com.nwd.can.sdk.outer.adil.ICanRemoteModelCallback";
    private static final String OUTER_CB = "com.nwd.can.sdk.outer.adil.ICanRemote4OuterCallback";
    /** Feature call numbers: add<Kind>CallBack (the remove call is one more). */
    private static final int ADD_OUTER = 3;
    private static final Object[][] KINDS = {{"air", 9}, {"door", 11}, {"radar", 15}, {"carinfo", 17}, {"volume", 19}, {"candata", 23}, {"setting", 25}};
    /** Firmware notices seen on the unit. */
    private static final String[] NOTICES = {
        "com.nwd.action.ACTION_HAND_BREAK_STATE_CHANGE", "com.nwd.ACTION_SET_ARM_VOLUME_STATE",
        "com.nwd.android.ACTION_OS_VOLUME_STATE_CHANGE", "com.nwd.ACTION_SET_OS_BACK_LIGHT_STATE",
        "com.nwd.ACTION_OS_BACK_LIGHT_STATE_CHANGE", "com.nwd.action.ACTION_KEY_VALUE",
        "com.nwd.action.ACTION_NO_SOURCE_DEVICE_CHANGE", "com.nwd.android.ACTION_STREAM_MUSIC",
        "com.nwd.action.ACTION_AUX_OPEN_STATE", "com.nwd.ACTION_OS_CRASH",
    };
    private static final long MAX_BYTES = 2_000_000;

    private static CanRecorder instance;
    private final Context app;
    private final File file;
    private IBinder service;
    private boolean running;
    private final List<Binder> callbacks = new ArrayList<>();
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    static synchronized CanRecorder get(Context c) {
        if (instance == null) instance = new CanRecorder(c.getApplicationContext());
        return instance;
    }

    private CanRecorder(Context c) {
        app = c;
        File dir = c.getExternalFilesDir(null);
        file = new File(dir != null ? dir : c.getFilesDir(), "car-data-log.txt");
    }

    File file() { return file; }
    boolean running() { return running; }

    static boolean available(Context c) {
        try { c.getPackageManager().getPackageInfo(NWD_CAN, 0); return true; }
        catch (Exception e) { return false; }
    }

    void start() {
        if (running) return;
        running = true;
        write("---- recording started " + new Date());
        IntentFilter f = new IntentFilter();
        for (String a : NOTICES) f.addAction(a);
        app.registerReceiver(notices, f);
        if (available(app)) {
            boolean ok = app.bindService(new Intent(ACTION).setPackage(NWD_CAN), connection, Context.BIND_AUTO_CREATE);
            write("can service: " + (ok ? "connecting" : "can't connect"));
        } else {
            write("can service: not on this unit");
        }
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autoStop = this::timeUp;

    private void timeUp() {
        new Prefs(app).setCarDataUntil(0);
        stop();
    }

    /** Records until `until` (ms), then stops by itself. */
    void startUntil(long until) {
        start();
        main.removeCallbacks(autoStop);
        main.postDelayed(autoStop, Math.max(0, until - System.currentTimeMillis()));
    }

    void stop() {
        main.removeCallbacks(autoStop);
        if (!running) return;
        running = false;
        try { app.unregisterReceiver(notices); } catch (IllegalArgumentException ignored) { }
        IBinder b = service;
        if (b != null) {
            for (int i = 0; i < callbacks.size(); i++) {
                int code = i == 0 ? ADD_OUTER + 1 : (int) KINDS[i - 1][1] + 1; // the remove calls
                transact(b, code, callbacks.get(i));
            }
            try { app.unbindService(connection); } catch (IllegalArgumentException ignored) { }
        }
        callbacks.clear();
        service = null;
        write("---- recording stopped " + new Date());
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            service = b;
            write("can service: connected");
            Binder outer = callback(OUTER_CB, "outer");
            callbacks.add(outer);
            write("addCallBack4Outerface: " + transact(b, ADD_OUTER, outer));
            for (Object[] k : KINDS) {
                Binder cb = callback(MODEL_CB, (String) k[0]);
                callbacks.add(cb);
                write("add " + k[0] + ": " + transact(b, (int) k[1], cb));
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
            service = null;
            write("can service: gone");
        }
    };

    /** feature.<call>(callback); "ok" or the error. */
    private static String transact(IBinder b, int code, IBinder cb) {
        Parcel p = Parcel.obtain(), r = Parcel.obtain();
        try {
            p.writeInterfaceToken(FEATURE);
            p.writeStrongBinder(cb);
            b.transact(code, p, r, 0);
            r.readException();
            return "ok";
        } catch (RemoteException | RuntimeException e) {
            return e.toString();
        } finally {
            p.recycle();
            r.recycle();
        }
    }

    /** A callback that writes each byte message it gets, with its kind. */
    private Binder callback(String descriptor, String kind) {
        return new Binder() {
            { attachInterface(null, descriptor); }

            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(descriptor);
                byte[] bytes = data.createByteArray();
                write(kind + (OUTER_CB.equals(descriptor) ? (code == 1 ? "/raw" : "/can") : "") + ": " + hex(bytes));
                if (reply != null) reply.writeNoException();
                return true;
            }
        };
    }

    private final BroadcastReceiver notices = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            StringBuilder s = new StringBuilder("notice ").append(i.getAction());
            Bundle b = i.getExtras();
            if (b != null) for (String k : b.keySet()) s.append(' ').append(k).append('=').append(b.get(k));
            write(s.toString());
        }
    };

    static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.US, "%02X ", x));
        return s.toString().trim();
    }

    private synchronized void write(String line) {
        Log.i("OrcaCAN", line);
        if (file.length() > MAX_BYTES) return;
        try (FileWriter w = new FileWriter(file, true)) {
            w.write(time.format(new Date()) + " " + line + "\n");
        } catch (IOException ignored) {
        }
    }
}
