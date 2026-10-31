# Orca Home versions

Every version that worked in the car is kept in `releases/`. To go back to one:
`adb install -r releases/orca-home-<version>.apk` (same signing key, so it installs over
any newer version; Android may ask to allow a downgrade: add `-d` after `-r`).

## 2.9 — in testing
- The unit's own FM/AM radio on the music card (through the NWD radio service): the station
  (name or frequency, radio text), its presets as buttons (tap to tune), ◀ ▶ search the
  previous / next station, the radio's screen from the middle button. Shown while the radio is
  in use and no other player plays.
- Favourite station: long-press a preset or the station name. The radio buttons (empty music
  card, quick switch) and a dock button set to "Radio: favourite station" start the radio on
  it and come back to the home screen.
- Car data recorder (settings, two hours): logs what the car's CAN box and the firmware send
  (doors, car info, air conditioning, handbrake, volume...), to work out this car's data.

## 2.8 — in testing
- Clock card: the next prayer time (Kemenag method, worked out on the unit, no internet) and
  the Hijri date; public holidays (Google's Indonesian holiday calendar, cuti bersama included)
  after the date, in red.
- Weather card: "Rain ~15:20" when rain is expected within two hours (same request as before).
- Under the speed: the road's speed limit as a sign (OpenStreetMap; the speed alert can use it)
  and the trip: distance and time since the car started, average speed. The km are counted
  by GPS while the car is on, also with other apps in front (launcher.sh allows background
  location; 'off' / restore.sh take it back).
- Pills at the bottom of the map: the destination of navigation started from Orca Home (also
  a pin on the map), the nearest mosque around prayer time (tap to go there), the Jakarta
  odd-even plate reminder, and the service reminder (every 5,000 / 7,500 / 10,000 km or
  6 months). The zoom buttons are a row now.
- Music card: while it shows the last Spotify song, small buttons for the other players
  (radio, YouTube Music, YouTube).
- Dock: brightness and volume buttons. Settings: volume when the car starts.

## 2.7 — working in the car (31 Oct 2026)
`releases/orca-home-2.7.apk`, built from commit 3292caf. Includes 2.4, 2.5 and 2.6.
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
  not removed by "trim caches", so songs played again show their lyrics at once; long-press
  the lyrics for Reload lyrics, settings has Delete saved lyrics): while they show, the card trades its big cover for a small one
  beside the title, so the line just sung, the current line (large) and about four lines
  ahead fit; clock and weather stay as they are. Lyrics button on the card, or off in settings.
  Lyrics out of time with your version of a song: long-press them, then Earlier / Later
  (half a second a tap); remembered for that song.

## 2.6 — working in the car, as part of 2.7
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

## 2.5 — working in the car, as part of 2.7
- Automatic day/night theme (on by default: Light by day, Midnight Drive at night),
  switching at sunrise and sunset worked out on the unit from the GPS position.
- Home and Work buttons above the map start Google Maps / Waze navigation in one tap
  (long-press to set: an address, a place name, or where the car is now); Gas stations stays.
- Bigger music buttons, titles and app icons for use while driving.
- Panel side: map (or app grid) on the left or on the right, nearer the driver.

## 2.4 — working in the car, as part of 2.7
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
