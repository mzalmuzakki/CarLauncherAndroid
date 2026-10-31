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
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Formatting and writing happen here, so a callback returns to the CAN service at once. */
    private final java.util.concurrent.ExecutorService writer = oneThread();
    /** Calls to the CAN service (adding and removing callbacks) happen here, never on the screen's thread. */
    private final java.util.concurrent.ExecutorService binderCalls = oneThread();

    /**
     * One thread, tasks strictly in order (all go through the queue: no core thread), and the
     * thread ends after 30 s with nothing to do (none kept between recordings).
     */
    private static java.util.concurrent.ExecutorService oneThread() {
        return new java.util.concurrent.ThreadPoolExecutor(0, 1, 30,
                java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
    }
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private volatile boolean running;
    /** When the recording ends (wall clock, ms; 0: no end). */
    private volatile long until;
    private boolean bound;
    private volatile IBinder service;
    /** Registered callbacks with the call number that removes each (used on the binderCalls thread only). */
    private final List<Object[]> callbacks = new ArrayList<>();
    private java.io.Writer out;
    private long written, flushedAt;
    private volatile boolean full;

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

    private final Runnable autoStop = this::timeUp;
    private final Runnable lateFlush = () -> writer.execute(() -> {
        try { if (out != null) { out.flush(); flushedAt = System.currentTimeMillis(); } } catch (IOException ignored) { }
    });

    private void timeUp() {
        new Prefs(app).setCarDataUntil(0);
        stop();
    }

    /** The timer pauses while the unit sleeps, so the clock is also checked as messages come in. */
    private void checkTime(long now) {
        long u = until;
        if (running && u > 0 && now >= u) {
            until = 0; // asked once, not for every message until it has stopped
            main.post(this::timeUp);
        }
    }

    /** Records until `until` (ms), then stops by itself; resume: carry on in the same file (after a restart). */
    void startUntil(long until, boolean resume) {
        if (until <= System.currentTimeMillis()) { timeUp(); return; }
        this.until = until;
        start(resume);
        main.removeCallbacks(autoStop);
        main.postDelayed(autoStop, Math.max(0, until - System.currentTimeMillis()));
    }

    /** A new recording (the previous file is kept as car-data-log.old.txt), or resume the current one. */
    void start(boolean resume) {
        if (running) return;
        running = true;
        writer.execute(() -> {
            if (!resume) {
                File old = new File(file.getParentFile(), "car-data-log.old.txt");
                if (file.exists()) { old.delete(); file.renameTo(old); }
            }
            try { out = new java.io.BufferedWriter(new FileWriter(file, resume)); } catch (IOException e) { out = null; }
            written = resume ? file.length() : 0;
            full = written > MAX_BYTES;
        });
        line("---- recording " + (resume ? "carries on after a restart " : "started ") + new Date());
        IntentFilter f = new IntentFilter();
        for (String a : NOTICES) f.addAction(a);
        app.registerReceiver(notices, f);
        if (available(app)) {
            try {
                bound = app.bindService(new Intent(ACTION).setPackage(NWD_CAN), connection, Context.BIND_AUTO_CREATE);
                line("can service: " + (bound ? "connecting" : "can't connect"));
            } catch (RuntimeException e) { // the firmware refuses (a permission, not exported)
                bound = false;
                line("can service: refused: " + e);
            }
        } else {
            line("can service: not on this unit");
        }
    }

    void stop() {
        main.removeCallbacks(autoStop);
        if (!running) return;
        running = false;
        until = 0;
        try { app.unregisterReceiver(notices); } catch (IllegalArgumentException ignored) { }
        IBinder b = service;
        binderCalls.execute(() -> removeCallbacks(b));
        if (bound) {
            try { app.unbindService(connection); } catch (IllegalArgumentException ignored) { }
            bound = false;
        }
        service = null;
        line("---- recording stopped " + new Date());
        writer.execute(() -> {
            try { if (out != null) out.close(); } catch (IOException ignored) { }
            out = null;
        });
    }

    /** On the binderCalls thread. */
    private void removeCallbacks(IBinder b) {
        if (b != null) for (Object[] c : callbacks) transact(b, (int) c[0], (IBinder) c[1]);
        callbacks.clear();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            if (!running) return; // stopped meanwhile
            service = b;
            line("can service: connected");
            binderCalls.execute(() -> {
                callbacks.clear(); // a restarted service has none of ours
                if (!running || service != b) return;
                Binder outer = callback(OUTER_CB, "outer");
                line("addCallBack4Outerface: " + transact(b, ADD_OUTER, outer));
                callbacks.add(new Object[]{ADD_OUTER + 1, outer});
                for (Object[] k : KINDS) {
                    Binder cb = callback(MODEL_CB, (String) k[0]);
                    line("add " + k[0] + ": " + transact(b, (int) k[1], cb));
                    callbacks.add(new Object[]{(int) k[1] + 1, cb});
                }
                if (!running) removeCallbacks(b); // stopped while adding
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
            service = null;
            binderCalls.execute(callbacks::clear);
            line("can service: gone");
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

    /** A callback that hands each byte message to the writer and returns straight away. */
    private Binder callback(String descriptor, String kind) {
        return new Binder() {
            { attachInterface(null, descriptor); }

            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(descriptor);
                byte[] bytes = data.createByteArray();
                if (reply != null) reply.writeNoException();
                if (running && !full) {
                    long at = System.currentTimeMillis();
                    checkTime(at);
                    String what = kind + (OUTER_CB.equals(descriptor) ? (code == 1 ? "/raw" : "/can") : "");
                    writer.execute(() -> append(at, what + ": " + hex(bytes)));
                }
                return true;
            }
        };
    }

    private final BroadcastReceiver notices = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            StringBuilder s = new StringBuilder("notice ").append(i.getAction());
            try {
                Bundle b = i.getExtras();
                if (b != null) for (String k : b.keySet()) s.append(' ').append(k).append('=').append(b.get(k));
            } catch (RuntimeException e) { // extras this app can't read (an unknown Parcelable)
                s.append(" (extras unreadable: ").append(e.getClass().getSimpleName()).append(')');
            }
            checkTime(System.currentTimeMillis());
            line(s.toString());
        }
    };

    /** A status line: also to the system log. */
    private void line(String text) {
        Log.i("OrcaCAN", text);
        long at = System.currentTimeMillis();
        writer.execute(() -> append(at, text));
    }

    /** On the writer thread. Messages beyond 2 MB are dropped (one note says so). */
    private void append(long at, String text) {
        if (out == null || full) return;
        try {
            String l = time.format(new Date(at)) + " " + text + "\n";
            written += l.length();
            if (written > MAX_BYTES) {
                full = true;
                out.write("---- log full (2 MB): later messages not written\n");
            } else {
                out.write(l);
            }
            // To storage at most once a second (and at stop), not for every message; and a
            // second after the last one, so a quiet spell doesn't leave lines in the buffer.
            if (at - flushedAt >= 1000) { out.flush(); flushedAt = at; }
            main.removeCallbacks(lateFlush);
            main.postDelayed(lateFlush, 1100);
        } catch (IOException ignored) {
        }
    }

    static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.US, "%02X ", x));
        return s.toString().trim();
    }
}
