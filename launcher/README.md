# Orca Home

A light dashboard launcher for the 1024x600 Android 10 head unit, by CAKRA Labs.
Layout inspired by car dashboard launchers; all code and icons are our own
(icons are Material Design shapes, Apache 2.0).

- **Main panel:** destination search with quick places (gas stations, ATMs, restaurants, hotels)
  and a **live map** that keeps the car centred (north up, direction arrow, accuracy circle,
  speed bubble, zoom buttons), with a row of favourite apps below. Settings can switch the
  left panel back to the app grid with clock and speed gauge. While Google Maps or Waze
  navigates, a turn-by-turn card (next turn, distance, ETA) replaces the search bar.
- **Map:** OpenStreetMap tiles, drawn dark (through a colour filter, no extra downloads) with
  dark themes, or always in the classic OSM colours. Our own tiny tile viewer, no map engine and no API key: 256 px image tiles,
  ~5 MB memory cache (16-bit colour), up to 50 MB on storage, downloaded only while the home
  screen is visible, redrawn only on a GPS fix (1/s) or when a tile arrives. No traffic layer.
- **Right column:** calendar, weather (Open-Meteo) and a music card (album art, progress,
  previous / play-pause / next) for whatever app is playing.
- **Dock:** home, phone (the unit's Bluetooth phone app), all apps, music, navigation
  (always a maps app: Google Maps, then Waze, then other installed maps apps, or a chosen one), browser, settings (long-press: Android settings).
- **Themes:** Light, Dark, Sky Glass, Rose Dusk, Arctic Drive, Emerald Night, Midnight Drive (default).

## Never slower than the original launcher
- Nothing runs while the home screen isn't visible: GPS, the music progress tick and weather
  start in `onResume` and stop in `onPause`. No background services or timers.
- The notification listener (needed for the music and nav cards) ignores every app except
  Google Maps and Waze.
- No live map, no animations; the gauge redraws only when the speed changes.
- Weather: one small request at most every 30 minutes, only while home is visible; cached.
- Map: tiles only while home is visible, cached on the unit; no continuous redraw.
- `scripts/launcher.sh` stops the old launcher after switching and prints memory before/after;
  `scripts/launcher.sh check` shows it again. If Orca Home is ever heavier: `scripts/launcher.sh off`.

Built by GitHub Actions (`.github/workflows/launcher.yml`), which commits `orca-home.apk` here.
`orcahome.jks` is a personal signing key so later builds install as updates.
