# Orca Home versions

Every version that worked in the car is kept in `releases/`. To go back to one:
`adb install -r releases/orca-home-<version>.apk` (same signing key, so it installs over
any newer version; Android may ask to allow a downgrade: add `-d` after `-r`).

## 2.7 — in testing
- Map compass: a red arrow pointing north (N under it) that turns with the map, in both
  map directions; tap it to switch between north up and driving direction up.
- The music card keeps the last Spotify song (title, artist, cover): it shows as soon as the
  car starts, and the play button carries on with it without leaving the home screen. If
  the unit doesn't let Spotify start in the background, Orca Home opens it, presses play as
  soon as it's ready and comes back to the home screen. Spotify is first among the music
  buttons.
- "Up next" on the music card: the next song in the playlist or album; tap for the list and
  pick a song (when the player shares its queue with Android).
- Lyrics in the music card, in time with the song (LRCLIB, free; one small request per new
  song; saved on the unit for 30 days after a song was last played, up to 1000 songs, and
  not removed by "trim caches", so songs played again show their lyrics at once): while they show, the card trades its big cover for a small one
  beside the title, so the line just sung, the current line (large) and about four lines
  ahead fit; clock and weather stay as they are. Lyrics button on the card, or off in settings.
  Lyrics out of time with your version of a song: long-press them, then Earlier / Later
  (half a second a tap); remembered for that song.

## 2.6 — in testing
- Night brightness (on by default): the screen dims at sunset (30 %, adjustable in settings)
  and goes back to the day brightness and auto/manual mode at sunrise. Turning it up by hand
  at night keeps your level until sunrise. launcher.sh allows it ("modify system settings");
  'off' / restore.sh take it back.
- Speed alert: the speed bubble and gauge turn red above a limit you choose (off, 60–120 km/h).
- The map zooms with speed (closer in town, further out on fast roads); + / − shift it.
- Dock: long-press the phone, music, navigation or browser button to put any app there
  (e.g. ZLink); pick the first entry to go back.
- Music card: Spotify, YouTube Music, YouTube and radio buttons while nothing plays.
- The last 5 places typed in the search bar appear as buttons after Gas stations
  (tap to navigate, long-press to remove).

## 2.5 — in testing
- Automatic day/night theme (on by default: Light by day, Midnight Drive at night),
  switching at sunrise and sunset worked out on the unit from the GPS position.
- Home and Work buttons above the map start Google Maps / Waze navigation in one tap
  (long-press to set: an address, a place name, or where the car is now); Gas stations stays.
- Bigger music buttons, titles and app icons for use while driving.
- Panel side: map (or app grid) on the left or on the right, nearer the driver.

## 2.4 — in testing
- Keyboard closes when tapping outside the search bar, when opening another app and when
  coming back home.
- While Google Maps / Waze navigates, the map layouts show a large guidance banner (turn
  arrow, distance, street) and the arrival time over the map; back to normal when it ends.
  launcher.sh turns off Google Maps' floating window ('off' / restore.sh put it back).
- The notification reader reconnects when the home screen opens (NWD firmware could keep it
  from connecting at boot, so the nav card never showed).
- Settings: map direction (north up, or driving direction up with the map turning) and map
  layout (wide map with apps below, or square map with apps in two columns beside it; same
  left/right split as before).

## 2.3 — first version working in the car (24 Oct 2026)
`releases/orca-home-2.3.apk`, built from commit fb4d62f.
- Live OpenStreetMap map (dark with dark themes), GPS speed bubble, weather, Spotify
  music card, favourite apps, 7 themes.
- On NWD firmware: becomes the home screen through the firmware's own launcher setting, and
  opens itself after a reboot over the firmware's "Select a Home app" box.
- `scripts/launcher.sh off` / `scripts/restore.sh` go back to the factory launcher.
