package com.orcahome.demoplayer;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Screenshot helper only: a stand-in for the NWD firmware's radio service (same interface
 * name, call numbers and data layout), so Orca Home's radio card can be tried on the emulator.
 */
public class FakeRadioService extends Service {
    private static final String FEATURE = "com.nwd.radio.service.RadioFeature";
    private static final String CALLBACK = "com.nwd.radio.service.RadioCallback";
    private static final int[] PRESETS = {9870, 10140, 10260, 10500, 9590, 8800};
    private static final String[] NAMES = {"PRAMBORS", "", "TRAX FM", "", "", ""};
    private static byte band = 0;
    private static int freq = 9870;
    private static IBinder callback;

    @Override
    public IBinder onBind(Intent i) { return feature; }

    private static String nameOf(int f) {
        for (int i = 0; i < PRESETS.length; i++) if (PRESETS[i] == f) return NAMES[i];
        return "";
    }

    private static void tuned() {
        Log.i("FakeRadio", "now " + freq);
        IBinder cb = callback;
        if (cb == null) return;
        Parcel p = Parcel.obtain(), r = Parcel.obtain();
        try {
            p.writeInterfaceToken(CALLBACK);
            p.writeByte(band);
            p.writeInt(freq);
            p.writeString(nameOf(freq));
            p.writeInt(0);
            cb.transact(2, p, r, 0);
            r.readException();
        } catch (RemoteException | RuntimeException e) {
            Log.w("FakeRadio", "callback: " + e);
        } finally {
            p.recycle();
            r.recycle();
        }
    }

    private final Binder feature = new Binder() {
        { attachInterface(null, FEATURE); }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(FEATURE);
            boolean changed = false;
            switch (code) {
                case 1: freq = data.readInt(); band = data.readByte(); data.readInt(); changed = true; reply.writeNoException(); break;
                case 2: reply.writeNoException(); reply.writeInt(1); reply.writeByte(band); reply.writeString(nameOf(freq)); reply.writeInt(freq); break;
                case 3: freq = Math.max(8750, Math.min(10800, freq + (data.readInt() != 0 ? 20 : -20))); changed = true; reply.writeNoException(); break;
                case 21:
                    reply.writeNoException();
                    reply.writeInt(PRESETS.length);
                    for (int i = 0; i < PRESETS.length; i++) { reply.writeInt(1); reply.writeByte(band); reply.writeString(NAMES[i]); reply.writeInt(PRESETS[i]); }
                    break;
                case 23: reply.writeNoException(); reply.writeByte((byte) 1); break;
                case 24: callback = data.readStrongBinder(); reply.writeNoException(); break;
                case 25: callback = null; reply.writeNoException(); break;
                case 28: reply.writeNoException(); reply.writeString("Demo radio text"); break;
                default: reply.writeNoException(); break;
            }
            if (changed) tuned();
            return true;
        }
    };
}
