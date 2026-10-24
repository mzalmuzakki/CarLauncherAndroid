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
        ui = new Ui(this, prefs.theme());
        ui.applyWindow(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(24));

        TextView title = ui.label(this, 26, ui.text, true);
        title.setText("Orca Home settings");
        col.addView(title);

        header(col, "Theme");
        LinearLayout swatches = new LinearLayout(this);
        for (int i = 0; i < Ui.THEME_NAMES.length; i++) {
            final int t = i;
            Ui u = new Ui(this, i);
            TextView s = ui.label(this, 13, u.text, t == prefs.theme());
            s.setText(Ui.THEME_NAMES[i]);
            s.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable g = u.box(u.panel, ui.dp(12));
            g.setStroke(ui.dp(t == prefs.theme() ? 3 : 1), t == prefs.theme() ? u.accent : u.tile);
            s.setBackground(g);
            s.setOnClickListener(v -> { if (t != prefs.theme()) { prefs.setTheme(t); build(); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ui.dp(56), 1f);
            lp.rightMargin = ui.dp(8);
            swatches.addView(s, lp);
        }
        col.addView(swatches);

        header(col, "Left panel");
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
        radios(col, "Map direction", new String[][]{
                {"north", "North up (the arrow turns)"},
                {"heading", "Driving direction up (the map turns, the arrow always points up)"}},
                prefs.mapDirection(), prefs::setMapDirection, 500);
        radios(col, "Map layout", new String[][]{
                {"wide", "Wide map, apps in a row below"},
                {"tall", "Square map, apps in two columns beside it"}},
                prefs.mapLayout(), prefs::setMapLayout, 600);

        TextView mapNote = ui.label(this, 13, ui.sub, false);
        mapNote.setText("Map tiles are downloaded only while the home screen is visible and kept on the unit (max 50 MB), so roads you drive often need no data.");
        mapNote.setPadding(0, ui.dp(4), 0, 0);
        col.addView(mapNote);

        header(col, "Show on the home screen");
        check(col, "Weather (Open-Meteo, internet, refreshed at most every 30 min)", prefs.showWeather(), prefs::setShowWeather);
        check(col, "Speed (gauge, or a bubble on the map)", prefs.showGauge(), prefs::setShowGauge);
        check(col, "Navigation card while Google Maps / Waze navigates", prefs.showNavCard(), prefs::setShowNavCard);

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
        tip.setText("Long-press a tile to move or remove it. Long-press an app in \"All apps\" to add it. Long-press the gear in the dock for Android settings.");
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
        about.setText("Orca Home 2.4 · CAKRA Labs\nNo ads and no background services. Internet is used only for weather and map tiles.");
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
