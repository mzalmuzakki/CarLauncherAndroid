package com.orcahome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The unit's own FM/AM radio (NWD firmware), through its radio service: the station playing,
 * the presets, tuning and seeking. The service hands its interface (an AIDL one, spoken here
 * by hand: same interface names, call numbers and data layout as the firmware's) to any app.
 * Frequencies: FM in 10 kHz steps (9870 = 98.7 MHz), AM in kHz. Presets are counted from 1
 * (0 = not a preset). Radio state: 0 closed, 1 playing, 2 searching, 3 intro scan.
 * Test builds without the NWD radio use a stand-in in the screenshot demo app.
 */
final class RadioClient {
    interface Listener { void changed(); }

    static final class Station {
        final byte band;
        final int freq;
        final String name;

        Station(byte band, int freq, String name) {
            this.band = band;
            this.freq = freq;
            this.name = name == null ? "" : name.trim();
        }

        boolean fm() { return freq >= 6400; }

        /** "98.7" (FM, MHz) or "1080" (AM, kHz). */
        String number() { return fm() ? String.format(Locale.US, "%.1f", freq / 100f) : String.valueOf(freq); }

        String unit() { return fm() ? "MHz" : "kHz"; }

        String key() { return band + ":" + freq + ":" + name.replace(':', ' '); }

        /** Same station: band and frequency (the RDS name scrolls and changes). */
        boolean same(Station o) { return o != null && o.freq == freq && o.band == band; }

        static Station fromKey(String k) {
            String[] p = k.split(":", 3);
            if (p.length < 2) return null;
            try { return new Station(Byte.parseByte(p[0]), Integer.parseInt(p[1]), p.length > 2 ? p[2] : ""); }
            catch (NumberFormatException e) { return null; }
        }
    }

    static final String NWD_SERVICE = "com.nwd.radio.service", NWD_APP = "com.nwd.radio";
    private static final String TEST_PKG = "com.orcahome.demoplayer";
    private static final String ACTION = "com.nwd.radio.service.ACTION_RADIO_SERVICE";
    private static final String FEATURE = "com.nwd.radio.service.RadioFeature";
    private static final String CALLBACK = "com.nwd.radio.service.RadioCallback";
    // Call numbers of RadioFeature and RadioCallback in the firmware.
    // SEARCH finds the next station (the firmware's scan); call 3 would only step the frequency.
    private static final int SET_FREQUENCY = 1, GET_FREQUENCY = 2, SEARCH = 4, GET_PRESETS = 21,
            GET_STATE = 23, REGISTER = 24, GET_RT = 28;
    private static final int CB_STATE = 1, CB_FREQUENCY = 2, CB_PRESETS = 8, CB_RT = 13;

    private static RadioClient instance;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService calls = Executors.newSingleThreadExecutor();
    private IBinder service;
    private boolean binding;
    private Listener listener;
    private volatile Station current;
    private volatile List<Station> presets = Collections.emptyList();
    private volatile String rt = "";
    private volatile int state = -1;
    /** When the radio last came on, changed station or was started from Orca Home (elapsed ms). */
    private volatile long playedAt;

    static synchronized RadioClient get(Context c) {
        if (instance == null) instance = new RadioClient(c.getApplicationContext());
        return instance;
    }

    private RadioClient(Context c) { app = c; }

    /** The package with the radio service: the NWD one, or the test stand-in (test builds, when asked). */
    String servicePackage() {
        if (installed(NWD_SERVICE)) return NWD_SERVICE;
        return new Prefs(app).testRadio(app) && installed(TEST_PKG) ? TEST_PKG : null;
    }

    /** The radio's own screen (it makes the radio the unit's sound source). */
    Intent appIntent() {
        if (installed(NWD_APP)) return app.getPackageManager().getLaunchIntentForPackage(NWD_APP);
        if (TEST_PKG.equals(servicePackage())) return new Intent().setClassName(TEST_PKG, TEST_PKG + ".FakeRadioActivity");
        return null;
    }

    private boolean installed(String pkg) {
        try { app.getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    void setListener(Listener l) { listener = l; }
    Station current() { return current; }
    List<Station> presets() { return presets; }
    String radioText() { return rt; }

    /** The radio is on (playing or searching), as the radio service says. */
    boolean on() { return state > 0; }
    long playedAt() { return playedAt; }

    /** Orca Home is starting the radio: count it as on until the radio says otherwise. */
    void starting() {
        state = 1;
        playedAt = android.os.SystemClock.elapsedRealtime();
        notifyChanged();
    }

    /** Connects (once); the station and presets follow through the listener. */
    void connect() {
        if (service != null || binding) return;
        String pkg = servicePackage();
        if (pkg == null) return;
        binding = app.bindService(new Intent(ACTION).setPackage(pkg), connection, Context.BIND_AUTO_CREATE);
        Log.i("OrcaHome", "radio: " + (binding ? "connecting to " + pkg : "can't connect to " + pkg));
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            service = b;
            binding = false;
            calls.execute(() -> {
                try {
                    call(REGISTER, p -> p.writeStrongBinder(callback), null);
                    refresh();
                    Log.i("OrcaHome", "radio: connected, state " + state + ", " + describe(current) + ", " + presets.size() + " presets");
                } catch (RemoteException | RuntimeException e) {
                    Log.w("OrcaHome", "radio: " + e);
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
            service = null;
            current = null;
            presets = Collections.emptyList();
            state = -1;
            notifyChanged();
            Log.i("OrcaHome", "radio: service gone");
        }
    };

    /** Station, presets, radio text and state, read again (on the calls thread). */
    private void refresh() throws RemoteException {
        call(GET_FREQUENCY, null, r -> { current = r.readInt() != 0 ? readStation(r) : null; });
        call(GET_PRESETS, null, r -> presets = readStations(r));
        call(GET_RT, null, r -> { String s = r.readString(); rt = s == null ? "" : s.trim(); });
        call(GET_STATE, null, r -> state = r.readByte());
        if (state > 0) playedAt = android.os.SystemClock.elapsedRealtime();
        notifyChanged();
    }

    /** presetIndex: 0-based place in presets(), or -1 when it isn't one (the firmware counts from 1). */
    void tune(Station s, int presetIndex) {
        run("tune " + describe(s), () -> call(SET_FREQUENCY, p -> { p.writeInt(s.freq); p.writeByte(s.band); p.writeInt(presetIndex + 1); }, null));
    }

    /** The next station up or down (the radio's own search). */
    void search(boolean up) {
        run("search " + (up ? "up" : "down"), () -> call(SEARCH, p -> p.writeInt(up ? 1 : 0), null));
    }

    private interface Call { void run() throws RemoteException; }

    private void run(String what, Call c) {
        connect();
        calls.execute(() -> {
            if (service == null) { Log.i("OrcaHome", "radio: not connected, can't " + what); return; }
            try {
                c.run();
                Log.i("OrcaHome", "radio: " + what);
            } catch (RemoteException | RuntimeException e) {
                Log.w("OrcaHome", "radio: " + what + ": " + e);
            }
        });
    }

    private interface Writer { void write(Parcel p); }
    private interface Reader { void read(Parcel r); }

    private void call(int code, Writer w, Reader rd) throws RemoteException {
        IBinder b = service;
        if (b == null) return;
        Parcel p = Parcel.obtain(), r = Parcel.obtain();
        try {
            p.writeInterfaceToken(FEATURE);
            if (w != null) w.write(p);
            b.transact(code, p, r, 0);
            r.readException();
            if (rd != null) rd.read(r);
        } finally {
            p.recycle();
            r.recycle();
        }
    }

    /** A Frequency as the firmware writes it: band, name, frequency. */
    private static Station readStation(Parcel r) {
        byte band = r.readByte();
        String name = r.readString();
        return new Station(band, r.readInt(), name);
    }

    private static List<Station> readStations(Parcel r) {
        int n = r.readInt();
        List<Station> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (r.readInt() != 0) out.add(readStation(r));
        return out;
    }

    static String describe(Station s) { return s == null ? "no station" : s.number() + " " + s.unit() + " band " + s.band + " \"" + s.name + "\""; }

    private void notifyChanged() { main.post(() -> { if (listener != null) listener.changed(); }); }

    /** What the radio service tells us: station changes, presets, radio text. */
    private final Binder callback = new Binder() {
        { attachInterface(null, CALLBACK); }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(CALLBACK);
            switch (code) {
                case CB_STATE:
                    state = data.readByte();
                    if (state > 0) playedAt = android.os.SystemClock.elapsedRealtime();
                    Log.i("OrcaHome", "radio: state " + state);
                    break;
                case CB_FREQUENCY: {
                    byte band = data.readByte();
                    int freq = data.readInt();
                    String name = data.readString();
                    data.readInt();
                    Station s = new Station(band, freq, name);
                    Station before = current;
                    current = s;
                    // A station change means the radio is what's being listened to.
                    if (!s.same(before) && state > 0) playedAt = android.os.SystemClock.elapsedRealtime();
                    Log.i("OrcaHome", "radio: now " + describe(s));
                    break;
                }
                case CB_PRESETS:
                    presets = readStations(data);
                    break;
                case CB_RT: {
                    String s = data.readString();
                    rt = s == null ? "" : s.trim();
                    break;
                }
                default:
                    break; // other notices (stereo, RDS, scan...) aren't used
            }
            if (reply != null) reply.writeNoException();
            notifyChanged();
            return true;
        }
    };
}
