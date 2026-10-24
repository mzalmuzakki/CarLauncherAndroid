package com.orcahome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Launcher settings. Every visible change bumps a stamp so the home screen knows to rebuild. */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("orcahome", Context.MODE_PRIVATE);
    }

    int theme() { return sp.getInt("theme7", Ui.DEFAULT_THEME); }
    /** Automatic day/night: the day theme from sunrise to sunset, the night theme otherwise. */
    boolean autoTheme() { return sp.getBoolean("autotheme", true); }
    int dayTheme() { return sp.getInt("daytheme", 0); }
    int nightTheme() { return sp.getInt("nighttheme", Ui.DEFAULT_THEME); }
    /** The theme to show now. */
    int activeTheme(Context c) {
        if (!autoTheme()) return theme();
        return DayNight.isNight(c, System.currentTimeMillis()) ? nightTheme() : dayTheme();
    }
    /** Which side the map (or app grid) panel is on: "left", or "right" (nearer the driver
     *  in right-hand-drive cars). */
    String panelSide() { return sp.getString("side", "left"); }
    /** One-tap destinations: an address, a place name or "lat,lng"; empty = not set. */
    String homePlace() { return sp.getString("home", ""); }
    String workPlace() { return sp.getString("work", ""); }
    boolean showWeather() { return sp.getBoolean("weather", true); }
    boolean showGauge() { return sp.getBoolean("gauge", true); }
    boolean showNavCard() { return sp.getBoolean("navcard", true); }
    /** Package the dock's navigation button opens; empty = automatic. */
    String navApp() { return sp.getString("navapp", ""); }
    long stamp() { return sp.getLong("stamp", 0); }
    /** Left panel: a live map (default) or the app grid with clock and gauge. */
    boolean showMap() { return sp.getBoolean("map", true); }
    /** "theme" = dark map with dark themes; "classic" = standard OpenStreetMap colours. */
    String mapStyle() { return sp.getString("mapstyle", "theme"); }
    int mapZoom() { return sp.getInt("mapzoom", 16); }
    /** "north": north up, the arrow turns; "heading": driving direction up, the map turns. */
    String mapDirection() { return sp.getString("mapdir", "north"); }
    /** "wide": map with the apps in a row below; "tall": squarer map, apps in a column beside it. */
    String mapLayout() { return sp.getString("maplayout", "wide"); }

    void setShowMap(boolean b) { put("map", b); }
    void setMapStyle(String st) { sp.edit().putString("mapstyle", st).putLong("stamp", System.nanoTime()).apply(); }
    void setMapZoom(int z) { sp.edit().putInt("mapzoom", z).apply(); }
    void setMapDirection(String d) { sp.edit().putString("mapdir", d).putLong("stamp", System.nanoTime()).apply(); }
    void setMapLayout(String l) { sp.edit().putString("maplayout", l).putLong("stamp", System.nanoTime()).apply(); }

    void setTheme(int t) { sp.edit().putInt("theme7", t).putLong("stamp", System.nanoTime()).apply(); }
    void setAutoTheme(boolean b) { put("autotheme", b); }
    void setDayTheme(int t) { sp.edit().putInt("daytheme", t).putLong("stamp", System.nanoTime()).apply(); }
    void setNightTheme(int t) { sp.edit().putInt("nighttheme", t).putLong("stamp", System.nanoTime()).apply(); }
    void setPanelSide(String s) { sp.edit().putString("side", s).putLong("stamp", System.nanoTime()).apply(); }
    void setHomePlace(String p) { sp.edit().putString("home", p).putLong("stamp", System.nanoTime()).apply(); }
    void setWorkPlace(String p) { sp.edit().putString("work", p).putLong("stamp", System.nanoTime()).apply(); }
    void setShowWeather(boolean b) { put("weather", b); }
    void setShowGauge(boolean b) { put("gauge", b); }
    void setShowNavCard(boolean b) { put("navcard", b); }
    void setNavApp(String pkg) { sp.edit().putString("navapp", pkg).apply(); }

    // Last weather, so the card shows something immediately after a restart.
    long weatherTime() { return sp.getLong("w_time", 0); }
    float weatherTemp() { return sp.getFloat("w_temp", Float.NaN); }
    int weatherCode() { return sp.getInt("w_code", -1); }
    String weatherCity() { return sp.getString("w_city", ""); }

    void setWeather(float temp, int code, String city) {
        SharedPreferences.Editor e = sp.edit().putLong("w_time", System.currentTimeMillis())
                .putFloat("w_temp", temp).putInt("w_code", code);
        if (city != null && !city.isEmpty()) e.putString("w_city", city);
        e.apply();
    }

    /** Flattened ComponentNames in display order, or null before the first run. */
    List<String> favorites() {
        String s = sp.getString("favorites", null);
        if (s == null) return null;
        if (s.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(s.split(";")));
    }

    void setFavorites(List<String> list) {
        StringBuilder b = new StringBuilder();
        for (String f : list) {
            if (b.length() > 0) b.append(';');
            b.append(f);
        }
        sp.edit().putString("favorites", b.toString()).putLong("stamp", System.nanoTime()).apply();
    }

    void resetFavorites() {
        sp.edit().remove("favorites").putLong("stamp", System.nanoTime()).apply();
    }

    private void put(String k, boolean v) {
        sp.edit().putBoolean(k, v).putLong("stamp", System.nanoTime()).apply();
    }
}
