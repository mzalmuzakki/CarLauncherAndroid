package com.orcahome;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Theme, what the home screen shows, permissions, and a way back to the old launcher. */
public class SettingsActivity extends Activity {

    private Prefs prefs;
    private Ui ui;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        build(); // permission states may have changed
    }

    private void build() {
        ui = new Ui(this, prefs.activeTheme(this));
        ui.applyWindow(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(24));

        TextView title = ui.label(this, 26, ui.text, true);
        title.setText("Orca Home settings");
        col.addView(title);

        header(col, "Theme");
        check(col, "Automatic day/night (switches at sunrise and sunset, worked out from the GPS position)",
                prefs.autoTheme(), on -> { prefs.setAutoTheme(on); build(); });
        if (prefs.autoTheme()) {
            swatches(col, "Day", prefs.dayTheme(), t -> { prefs.setDayTheme(t); build(); });
            swatches(col, "Night", prefs.nightTheme(), t -> { prefs.setNightTheme(t); build(); });
        } else {
            swatches(col, null, prefs.theme(), t -> { prefs.setTheme(t); build(); });
        }

        header(col, "Night brightness");
        check(col, "Dim the screen from sunset to sunrise (back to your day brightness in the morning)",
                prefs.nightDim(), on -> { prefs.setNightDim(on); Brightness.apply(this); build(); });
        if (prefs.nightDim()) {
            TextView level = ui.label(this, 14, ui.text, false);
            level.setText("Night level: " + prefs.nightLevel() + "%");
            level.setPadding(0, ui.dp(6), 0, 0);
            col.addView(level);
            SeekBar bar = new SeekBar(this);
            bar.setMax(70); // 10 % to 80 %
            bar.setProgress(prefs.nightLevel() - 10);
            bar.setProgressTintList(ColorStateList.valueOf(ui.accent));
            bar.setThumbTintList(ColorStateList.valueOf(ui.accent));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    level.setText("Night level: " + (p + 10) + "%");
                    if (user) { prefs.setNightLevel(p + 10); Brightness.levelChanged(SettingsActivity.this); }
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(44)));
            if (!Brightness.allowed(this)) {
                button(col, "Allow Orca Home to change the brightness", () ->
                        open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))));
            }
            TextView dimNote = ui.label(this, 13, ui.sub, false);
            dimNote.setText(prefs.dimmed() ? "Dimmed now. Changing the brightness by hand keeps your level until sunrise."
                    : "Changing the brightness by hand at night keeps your level until sunrise.");
            col.addView(dimNote);
        }

        header(col, "Main panel");
        RadioGroup mode = new RadioGroup(this);
        String[][] modes = {{"map", "Live map (follows your GPS position)"}, {"grid", "App grid with clock and speed gauge"}};
        for (int i = 0; i < modes.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(300 + i);
            r.setText(modes[i][1]);
            r.setTextColor(ui.text);
            r.setButtonTintList(ColorStateList.valueOf(ui.accent));
            mode.addView(r);
        }
        mode.check(prefs.showMap() ? 300 : 301);
        mode.setOnCheckedChangeListener((g, id) -> prefs.setShowMap(id == 300));
        col.addView(mode);

        header(col, "Map style");
        RadioGroup ms = new RadioGroup(this);
        String[][] styles = {{"theme", "Match the theme (dark map with dark themes)"}, {"classic", "Classic OpenStreetMap colours (always light)"}};
        for (int i = 0; i < styles.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(400 + i);
            r.setText(styles[i][1]);
            r.setTextColor(ui.text);
            r.setButtonTintList(ColorStateList.valueOf(ui.accent));
            ms.addView(r);
        }
        ms.check("classic".equals(prefs.mapStyle()) ? 401 : 400);
        ms.setOnCheckedChangeListener((g, id) -> prefs.setMapStyle(id == 401 ? "classic" : "theme"));
        col.addView(ms);
        radios(col, "Panel side", new String[][]{
                {"left", "Map or app grid on the left"},
                {"right", "Map or app grid on the right (nearer the driver in right-hand-drive cars)"}},
                prefs.panelSide(), prefs::setPanelSide, 700);
        radios(col, "Map direction", new String[][]{
                {"north", "North up (the arrow turns)"},
                {"heading", "Driving direction up (the map turns, the arrow always points up)"}},
                prefs.mapDirection(), prefs::setMapDirection, 500);
        radios(col, "Map layout", new String[][]{
                {"wide", "Wide map, apps in a row below"},
                {"tall", "Square map, apps in two columns beside it"}},
                prefs.mapLayout(), prefs::setMapLayout, 600);

        check(col, "Zoom with speed: closer in town, further out on fast roads (+ / \u2212 on the map shift it)",
                prefs.autoZoom(), prefs::setAutoZoom);

        TextView mapNote = ui.label(this, 13, ui.sub, false);
        mapNote.setText("Map tiles are downloaded only while the home screen is visible and kept on the unit (max 50 MB), so roads you drive often need no data.");
        mapNote.setPadding(0, ui.dp(4), 0, 0);
        col.addView(mapNote);

        header(col, "Show on the home screen");
        check(col, "Weather (Open-Meteo, internet, refreshed at most every 30 min)", prefs.showWeather(), prefs::setShowWeather);
        check(col, "Speed (gauge, or a bubble on the map)", prefs.showGauge(), prefs::setShowGauge);
        check(col, "Navigation card while Google Maps / Waze navigates", prefs.showNavCard(), prefs::setShowNavCard);
        check(col, "Song lyrics in the music card, in time with the music (from LRCLIB, internet; also the Lyrics button on the card)",
                prefs.showLyrics(), prefs::setShowLyrics);
        TextView musicNote = ui.label(this, 13, ui.sub, false);
        musicNote.setText("The music card keeps the last Spotify song: it shows when the car starts, and the play button carries on with it. \"Up next\" appears when the player shares its queue (the rest of the playlist or album); tap it to pick a song. Lyrics out of time? Long-press them for Earlier / Later (kept for that song).");
        musicNote.setPadding(0, ui.dp(4), 0, 0);
        col.addView(musicNote);

        radios(col, "Speed alert (the speed turns red above it)", new String[][]{
                {"0", "Off"}, {"60", "60 km/h"}, {"80", "80 km/h"}, {"100", "100 km/h"}, {"110", "110 km/h"}, {"120", "120 km/h"}},
                String.valueOf(prefs.speedLimit()), v -> prefs.setSpeedLimit(Integer.parseInt(v)), 800);

        header(col, "Maps app (navigation button, map tap, destination search)");
        RadioGroup nav = new RadioGroup(this);
        List<String> maps = NavApps.installed(this);
        List<String> ids = new ArrayList<>();
        ids.add("");
        ids.addAll(maps);
        String current = maps.contains(prefs.navApp()) ? prefs.navApp() : "";
        for (int i = 0; i < ids.size(); i++) {
            RadioButton r = new RadioButton(this);
            r.setId(200 + i);
            r.setText(i == 0 ? "Automatic: Google Maps, then Waze, then other maps apps" : NavApps.label(this, ids.get(i)));
            r.setTextColor(ui.text);
            r.setButtonTintList(ColorStateList.valueOf(ui.accent));
            nav.addView(r);
            if (ids.get(i).equals(current)) nav.check(200 + i);
        }
        nav.setOnCheckedChangeListener((g, id) -> prefs.setNavApp(ids.get(id - 200)));
        col.addView(nav);
        if (maps.isEmpty()) {
            TextView none = ui.label(this, 13, ui.sub, false);
            none.setText("No maps app installed. Install Google Maps, Waze or Organic Maps from the Play Store.");
            col.addView(none);
        }

        header(col, "Permissions");
        boolean gps = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        button(col, gps ? "Speed gauge and weather: GPS allowed ✓" : "Allow GPS for the speed gauge and weather", () -> {
            if (!gps) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        });
        boolean listener = listenerAllowed();
        button(col, listener ? "Music and navigation cards: allowed ✓" : "Allow the music and navigation cards (notification access)", () ->
                open(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));

        header(col, "Home tiles");
        button(col, "Reset home tiles to the defaults", () -> {
            prefs.resetFavorites();
            Toast.makeText(this, "Home tiles reset", Toast.LENGTH_SHORT).show();
        });
        TextView tip = ui.label(this, 14, ui.sub, false);
        tip.setText("Long-press a tile to move or remove it. Long-press an app in \"All apps\" to add it. Long-press Home or Work above the map to change the place, or a recent place to remove it. Long-press the phone, music, navigation or browser button in the dock to put any app there (e.g. ZLink). Long-press the gear in the dock for Android settings.");
        col.addView(tip);

        header(col, "Launcher");
        button(col, "Choose the home app (switch back to the original launcher)", () ->
                open(new Intent(Settings.ACTION_HOME_SETTINGS)));
        boolean atBoot = Settings.canDrawOverlays(this);
        button(col, atBoot ? "Opens by itself when the unit starts: on ✓ (tap to change)"
                : "Open Orca Home by itself when the unit starts (for units that ask \"Select a Home app\" at boot)", () ->
                open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        TextView bootNote = ui.label(this, 13, ui.sub, false);
        bootNote.setText("Uses Android's \"Display over other apps\" permission, only to open the home screen once after start-up.");
        col.addView(bootNote);

        header(col, "About");
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(120));
        llp.bottomMargin = ui.dp(10);
        col.addView(ui.logo(this, 120, true), llp);
        TextView about = ui.label(this, 14, ui.text, false);
        about.setText("Orca Home 2.7 · CAKRA Labs\nNo ads and no background services. Internet is used only for weather, map tiles and song lyrics.");
        about.setLineSpacing(ui.dp(3), 1f);
        col.addView(about);
        TextView copy = ui.label(this, 13, ui.sub, false);
        copy.setText("Copyright © 2026 Muhammad Zaki Almuzakki · CAKRA Labs");
        copy.setPadding(0, ui.dp(8), 0, 0);
        col.addView(copy);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.bg);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        build();
    }

    private boolean listenerAllowed() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(new ComponentName(this, MediaListener.class));
    }

    private void header(LinearLayout col, String text) {
        TextView h = ui.label(this, 16, ui.accent, true);
        h.setText(text);
        h.setPadding(0, ui.dp(22), 0, ui.dp(8));
        col.addView(h);
    }

    private interface Setter { void set(boolean b); }
    private interface Chooser { void set(String value); }
    private interface ThemeChooser { void set(int theme); }

    /** One row of the 7 themes, optionally labelled (Day / Night). */
    private void swatches(LinearLayout col, String label, int current, ThemeChooser c) {
        if (label != null) {
            TextView l = ui.label(this, 13, ui.sub, true);
            l.setText(label);
            l.setPadding(0, ui.dp(6), 0, ui.dp(4));
            col.addView(l);
        }
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < Ui.THEME_NAMES.length; i++) {
            final int t = i;
            Ui u = new Ui(this, i);
            TextView s = ui.label(this, 13, u.text, t == current);
            s.setText(Ui.THEME_NAMES[i]);
            s.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable g = u.box(u.panel, ui.dp(12));
            g.setStroke(ui.dp(t == current ? 3 : 1), t == current ? u.accent : u.tile);
            s.setBackground(g);
            s.setOnClickListener(v -> { if (t != current) c.set(t); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ui.dp(label != null ? 46 : 56), 1f);
            lp.rightMargin = ui.dp(8);
            row.addView(s, lp);
        }
        col.addView(row);
    }

    /** A heading and one radio button per {value, label}; ids start at idBase. */
    private void radios(LinearLayout col, String title, String[][] options, String current, Chooser c, int idBase) {
        header(col, title);
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < options.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(idBase + i);
            r.setText(options[i][1]);
            r.setTextColor(ui.text);
            r.setButtonTintList(ColorStateList.valueOf(ui.accent));
            g.addView(r);
            if (options[i][0].equals(current)) g.check(idBase + i);
        }
        g.setOnCheckedChangeListener((gr, id) -> c.set(options[id - idBase][0]));
        col.addView(g);
    }

    private void check(LinearLayout col, String text, boolean value, Setter s) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextColor(ui.text);
        c.setButtonTintList(ColorStateList.valueOf(ui.accent));
        c.setChecked(value);
        c.setOnCheckedChangeListener((b, on) -> s.set(on));
        col.addView(c);
    }

    private void button(LinearLayout col, String text, Runnable r) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(ui.text);
        b.setBackground(ui.box(ui.panel, ui.dp(12)));
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(8);
        col.addView(b, lp);
    }

    private void open(Intent i) {
        try { startActivity(i); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "Not available on this unit", Toast.LENGTH_SHORT).show(); }
    }
}
