package com.orcahome;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Indonesian public holidays (tanggal merah, cuti bersama included) from Google's public
 * holiday calendar (an .ics file, no account), fetched at most once every two weeks while the
 * home screen is visible and kept on the unit; observances that aren't days off are left out.
 */
final class Holidays {
    private static final String URL_ICS =
            "https://calendar.google.com/calendar/ical/en.indonesian%23holiday%40group.v.calendar.google.com/public/basic.ics";
    private static final long MAX_AGE_MS = 14L * 24 * 3600 * 1000;
    private static final long RETRY_MS = 6L * 3600 * 1000;
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static Map<String, String> days; // "20261225" -> "Christmas Day"
    private static long lastTry;

    private Holidays() {}

    /** The holiday on that day ("Christmas Day"), or null. */
    static synchronized String on(Context c, Date day) {
        if (days == null) days = load(file(c));
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        return days.get(f.format(day));
    }

    /** Fetches the calendar again when it's two weeks old; done runs on the main thread. */
    static void refreshIfStale(Context c, Runnable done) {
        File f = file(c);
        long now = System.currentTimeMillis();
        if ((f.exists() && now - f.lastModified() < MAX_AGE_MS) || now - lastTry < RETRY_MS) return;
        lastTry = now;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        NET.execute(() -> {
            try {
                Map<String, String> got = parse(get(URL_ICS));
                if (got.isEmpty()) return;
                StringBuilder s = new StringBuilder();
                for (Map.Entry<String, String> e : got.entrySet()) s.append(e.getKey()).append('|').append(e.getValue()).append('\n');
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(s.toString().getBytes(StandardCharsets.UTF_8)); }
                synchronized (Holidays.class) { days = got; }
                Log.i("OrcaHome", "holidays: " + got.size() + " days off");
                main.post(done);
            } catch (IOException | RuntimeException e) {
                Log.i("OrcaHome", "holidays: " + e);
            }
        });
    }

    /** Days off from the .ics text: all-day events described as a public holiday (or joint leave). */
    static Map<String, String> parse(String ics) {
        Map<String, String> out = new HashMap<>();
        String date = null, name = null, desc = "";
        for (String line : ics.replace("\r\n ", "").replace("\n ", "").split("\r?\n")) {
            if (line.startsWith("BEGIN:VEVENT")) { date = name = null; desc = ""; }
            else if (line.startsWith("DTSTART")) date = line.substring(line.indexOf(':') + 1).trim();
            else if (line.startsWith("SUMMARY")) name = line.substring(line.indexOf(':') + 1).trim().replace("\\,", ",");
            else if (line.startsWith("DESCRIPTION")) desc = line.substring(line.indexOf(':') + 1);
            else if (line.startsWith("END:VEVENT") && date != null && name != null && date.length() >= 8) {
                String d = desc.toLowerCase(Locale.ROOT), n = name.toLowerCase(Locale.ROOT);
                if (d.contains("public holiday") || n.contains("joint") || n.contains("cuti bersama")) out.put(date.substring(0, 8), name);
            }
        }
        return out;
    }

    private static Map<String, String> load(File f) {
        Map<String, String> out = new HashMap<>();
        if (!f.exists()) return out;
        try (InputStream in = new FileInputStream(f)) {
            for (String line : read(in).split("\n")) {
                int bar = line.indexOf('|');
                if (bar == 8) out.put(line.substring(0, 8), line.substring(9));
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static File file(Context c) { return new File(c.getFilesDir(), "holidays.txt"); }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) { return read(in); } finally { c.disconnect(); }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
