#!/usr/bin/env bash
# Screenshots and checks of Orca Home on an Android 10 emulator (1024x600, 160 dpi).
# Run by .github/workflows/screenshots.yml once the emulator has booted.
#   shoot.sh <debug.apk> <release.apk> <demoplayer.apk> <out dir>
# The debug build is used for the screenshots (run-as lets us switch themes); the release
# APK that scripts/launcher.sh installs is used for the crash, memory and background checks.
set -uo pipefail
DEBUG_APK=$1 RELEASE_APK=$2 PLAYER_APK=$3 OUT=$4
HERE=$(cd "$(dirname "$0")" && pwd)
PKG=com.orcahome
mkdir -p "$OUT"
REPORT=$OUT/report.txt
: > "$REPORT"

say() { echo "$*" | tee -a "$REPORT"; }
sh_() { adb shell "$@" | tr -d '\r'; }
shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }

# --- Emulator set-up ---------------------------------------------------------------------
sh_ settings put global window_animation_scale 0.5
sh_ settings put global transition_animation_scale 0.5
sh_ settings put global animator_duration_scale 0.5
sh_ settings put system accelerometer_rotation 0
sh_ settings put system user_rotation 0
sh_ settings put system screen_off_timeout 1800000
sh_ svc power stayon true
sh_ service call alarm 3 s16 Asia/Jakarta > /dev/null || true
sh_ cmd location set-location-enabled true 2>/dev/null || sh_ settings put secure location_providers_allowed +gps
say "Screen: $(sh_ wm size | tail -1), $(sh_ wm density | tail -1)"
say "Android: $(sh_ getprop ro.build.version.release) (SDK $(sh_ getprop ro.build.version.sdk)), $(sh_ getprop ro.product.cpu.abi)"

adb install -r -g "$PLAYER_APK" > /dev/null

setup_launcher() {   # $1 = apk; same steps as scripts/launcher.sh
    adb uninstall "$PKG" > /dev/null 2>&1
    adb install -r "$1" | tail -1
    sh_ pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
    sh_ cmd notification allow_listener "$PKG/$PKG.MediaListener"
    sh_ cmd package set-home-activity "$PKG/.MainActivity"
}

front() { sh_ dumpsys activity activities | grep -m1 mResumedActivity | grep -o '[a-z][a-zA-Z0-9_.]*/[a-zA-Z0-9_.$]*'; }
player_state() { sh_ dumpsys media_session | grep -A15 'package=com.orcahome.demoplayer' | grep -m1 -o 'state=PlaybackState {state=[0-9]*' | grep -o '[0-9]*$'; }

requests() { sh_ dumpsys location | grep -c "UpdateRecord\[.*$PKG"; }

home() { sh_ am start -W -a android.intent.action.MAIN -c android.intent.category.HOME > /dev/null; }

# Home tiles: apps this emulator has, in place of ZLink/Spotify/Netflix on the head unit.
FAVS=""
for p in com.google.android.youtube com.google.android.apps.maps com.android.chrome \
         com.google.android.apps.youtube.music com.google.android.apps.photos com.android.vending \
         com.android.settings; do
    cn=$(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN \
            -c android.intent.category.LAUNCHER "$p" | tail -1)
    case "$cn" in "$p"/*) ;; *) continue ;; esac
    cls=${cn#*/}
    case "$cls" in "$p".*) cls=".${cls#"$p".}" ;; esac
    FAVS="${FAVS:+$FAVS;}$p/$cls"
done
say "Home tiles: $FAVS"

prefs() {   # theme map(true/false) mapstyle(theme/classic) [direction north/heading] [layout wide/tall] [side left/right] [autotheme false/true]
            # EXTRA="<xml lines>" adds or overrides settings (2.6 features are off unless given here)
    local xml b64
    xml="<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name=\"theme7\" value=\"$1\" />
    <boolean name=\"map\" value=\"$2\" />
    <string name=\"mapstyle\">$3</string>
    <string name=\"mapdir\">${4:-north}</string>
    <string name=\"maplayout\">${5:-wide}</string>
    <string name=\"side\">${6:-left}</string>
    <boolean name=\"autotheme\" value=\"${7:-false}\" />
    <string name=\"home\">Monas, Jakarta</string>
    <string name=\"favorites\">$FAVS</string>
    <int name=\"speedlimit\" value=\"0\" />
    <boolean name=\"autozoom\" value=\"false\" />
    <boolean name=\"nightdim\" value=\"false\" />
    ${EXTRA:-}
    <long name=\"stamp\" value=\"$RANDOM$RANDOM\" />
</map>"
    b64=$(printf '%s' "$xml" | base64 | tr -d '\n')
    sh_ am force-stop "$PKG"
    sh_ "run-as $PKG sh -c 'mkdir -p shared_prefs && echo $b64 | base64 -d > shared_prefs/orcahome.xml'"
    # No remembered "last played" song unless a test plays one.
    sh_ "run-as $PKG rm -f shared_prefs/lastplayed.xml files/last-cover.jpg"
}

# Taps the middle of the first view whose text starts with $1, else at $2 (x y).
tap_text() {
    local at
    sh_ uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
    at=$(sh_ cat /sdcard/ui.xml | python3 -c '
import re, sys
m = re.search(r"text=\"" + re.escape(sys.argv[1]) + r"[^\"]*\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", sys.stdin.read())
print("%d %d" % ((int(m[1]) + int(m[3])) // 2, (int(m[2]) + int(m[4])) // 2) if m else "")' "$1")
    [ -n "$at" ] || { say "  (\"$1\" not found on screen, tapping at $2)"; at=$2; }
    sh_ input tap $at
}

# --- Screenshots (debug build) -----------------------------------------------------------
setup_launcher "$DEBUG_APK"
home; sleep 3
sh_ am start -n com.orcahome.demoplayer/.DemoActivity > /dev/null

# 'geo fix' (NMEA sentences put the car in California on this emulator).
python3 "$HERE/drive.py" ${DRIVE_MODE:-fix} &
DRIVE=$!
trap 'kill $DRIVE 2>/dev/null' EXIT
sleep 6
say "GPS seen by Android: $(sh_ dumpsys location | grep -m1 -E 'gps: Location\[')"

NAMES=(light dark sky-glass rose-dusk arctic-drive emerald-night midnight-drive)
# Default theme first, with extra time for the first map tiles and the weather.
prefs 6 true theme; home; shot 01-home-midnight-drive 15
n=2
for t in 0 1 2 3 4 5; do
    prefs $t true theme; home; shot "$(printf '%02d' $n)-home-${NAMES[$t]}" 10
    n=$((n + 1))
done
prefs 6 true classic; home; shot 08-home-classic-map 10
prefs 6 false theme; home; shot 09-grid-midnight-drive 6
prefs 0 false theme; home; shot 10-grid-light 6

prefs 6 true theme; home; sleep 3
# All apps isn't exported (only Orca Home opens it): tap the last tile under the map.
sh_ input tap 570 440; shot 11-all-apps 4
sh_ input keyevent KEYCODE_BACK
sh_ am start -W -n "$PKG/.SettingsActivity" > /dev/null; shot 12-settings-1 3
sh_ input swipe 512 520 512 120 400; shot 13-settings-2 2
sh_ input swipe 512 520 512 120 400; shot 14-settings-3 2
sh_ input keyevent KEYCODE_BACK

# 2.4: map direction, square-map layout, navigation mode, keyboard.
# From here the demo app is the GPS: it can report a heading (east), geo fix can't.
kill $DRIVE 2>/dev/null
sh_ appops set com.orcahome.demoplayer android:mock_location allow
sh_ am start -n com.orcahome.demoplayer/.DemoActivity --es drive start > /dev/null
sleep 4
say "Test GPS for 2.4 shots: $(sh_ dumpsys location | grep -m1 -E 'gps: Location\[')"
prefs 6 true theme heading wide; home; shot 20-heading-up 10
sh_ input tap 55 169; shot 28-compass-tap-north-up 3   # the compass, top left of the map
say "Compass tap: map direction now $(sh_ "run-as $PKG cat shared_prefs/orcahome.xml" | grep -o 'name="mapdir">[^<]*' | sed 's/.*>//') (expected north)"
prefs 6 true theme north tall; home; shot 21-square-map 8
prefs 6 true theme heading tall; home; shot 22-square-map-heading-up 8
sh_ am start -n com.orcahome.demoplayer/.DemoActivity --es nav on > /dev/null
prefs 6 true theme north wide; home; shot 23-navigation-wide 8
prefs 6 true theme heading tall; home; shot 24-navigation-square 8
sh_ am start -n com.orcahome.demoplayer/.DemoActivity --es nav off > /dev/null
home; shot 25-navigation-ended 5
# Keyboard: open it from the search bar, open another app, come back home.
sh_ settings put secure show_ime_with_hard_keyboard 1
prefs 6 true theme north wide; home; sleep 4
sh_ input tap 300 66; shot 26-keyboard-open 3
sh_ am start -W -n com.android.settings/.Settings > /dev/null; sleep 3
home; shot 27-keyboard-after-other-app 4
say "Keyboard shown after coming back: $(sh_ dumpsys input_method | grep -m1 -o 'mInputShown=[a-z]*')"

# 2.5: driver-side layout, automatic day/night theme, Home/Work, bigger buttons.
prefs 6 true theme north wide right; home; shot 40-driver-side 8
prefs 6 true theme north wide left true; home; shot 41-auto-theme 8
expected=$(python3 "$HERE/sun.py" -6.1864 106.8238)
say "Auto theme: at $(date -u +%H:%M) UTC it is $expected in Jakarta, so 41-auto-theme.png should show the $expected theme (day: Light, night: Midnight Drive)"
sh_ am start -W -n "$PKG/.SettingsActivity" > /dev/null; shot 44-settings-auto-theme 3
sh_ input keyevent KEYCODE_BACK
prefs 6 true theme north wide; home; sleep 4
adb logcat -c
sh_ input tap 70 113; sleep 4
say "Home button: $(adb logcat -d | grep -o -m1 'act=android.intent.action.VIEW dat=google.navigation[^ ]*' || echo 'no navigation started')"
home; sleep 3
sh_ input swipe 175 113 175 113 1200; shot 43-set-work-place 3
sh_ input keyevent KEYCODE_BACK; sh_ input keyevent KEYCODE_BACK

# 2.6: speed alert, map zoom with speed, dock buttons, recent places, music shortcuts, settings.
# The test GPS again: its app can be stopped while Google Maps opens above.
sh_ am start -n com.orcahome.demoplayer/.DemoActivity --es drive start > /dev/null
sleep 4
say "Test GPS for 2.6 shots: $(sh_ dumpsys location | grep -m1 -E 'gps: Location\[')"
EXTRA='<int name="speedlimit" value="40" />' prefs 6 true theme north wide; home; shot 50-speed-alert 8
EXTRA='<int name="speedlimit" value="40" />' prefs 0 false theme; home; shot 51-speed-alert-gauge 6
EXTRA='<boolean name="autozoom" value="true" />' prefs 6 true theme north wide; home; shot 52-zoom-with-speed 10
WEB_APP=${FAVS%%;*}
EXTRA="<string name=\"dock_web\">$WEB_APP</string>" prefs 6 true theme north wide; home; shot 53-dock-custom-app 6
say "Dock browser button set to $WEB_APP for 53-dock-custom-app.png"
sh_ input swipe 738 520 738 520 1200; shot 54-dock-chooser 3
sh_ input keyevent KEYCODE_BACK
EXTRA='<string name="recent">Bundaran HI&#10;Blok M Plaza</string>' prefs 6 true theme north wide; home; sleep 4
sh_ input tap 300 66; sleep 2
sh_ input text "Kota%sTua"; sh_ input keyevent KEYCODE_ENTER; sleep 5
home; shot 55-recent-places 5
say "Recent places saved: $(sh_ "run-as $PKG cat shared_prefs/orcahome.xml" | grep -o '<string name="recent">[^<]*' | sed 's/.*>//')"
EXTRA='<boolean name="nightdim" value="true" />' prefs 6 true theme north wide; home; sleep 3
sh_ am start -W -n "$PKG/.SettingsActivity" > /dev/null; shot 57-settings-night-brightness 3
sh_ input swipe 512 520 512 120 400; shot 58-settings-zoom 2
sh_ input swipe 512 520 512 120 400; shot 59-settings-speed-alert 2
sh_ input swipe 512 520 512 120 400; shot 60-settings-tips 2
sh_ input keyevent KEYCODE_BACK

# 2.6 night brightness: put the car (test GPS) where it is night now, then where it is day.
sh_ appops set "$PKG" WRITE_SETTINGS allow
sh_ settings put system screen_brightness_mode 0
sh_ settings put system screen_brightness 200
if [ "$(python3 "$HERE/sun.py" -6.1864 106.8238)" = night ]; then NIGHT="-6.1864,106.8238" DAY="-6.19,-73.18"
else NIGHT="-6.19,-73.18" DAY="-6.1864,106.8238"; fi
place() { sh_ am start -n com.orcahome.demoplayer/.DemoActivity --es place "$1" > /dev/null; sleep 4; }
sun() { python3 "$HERE/sun.py" "${1%,*}" "${1#*,}"; }
bright() { echo "$(sh_ settings get system screen_brightness) (mode $(sh_ settings get system screen_brightness_mode))"; }
# Settings first: prefs() rewrites the whole settings file, the "dimmed" record too.
EXTRA='<boolean name="nightdim" value="true" />' prefs 6 true theme north wide
adb logcat -c
place "$NIGHT"; home; sleep 4
say "Night brightness, car at $NIGHT ($(sun "$NIGHT")): brightness $(bright), expected 77 (30 %)"
sh_ settings put system screen_brightness 120  # turned up by hand at night: must stay
sh_ am start -W -n com.android.settings/.Settings > /dev/null; sleep 2; home; sleep 3
say "  turned up by hand to 120, after going home again: $(bright), expected 120"
place "$DAY"
sh_ am start -W -n com.android.settings/.Settings > /dev/null; sleep 2; home; sleep 3
say "  car at $DAY ($(sun "$DAY")): $(bright), expected 200 (mode 0)"
adb logcat -d -s OrcaHome:* > "$OUT/logcat-brightness.txt"
sh_ dumpsys location | grep -E 'Last Known Locations|^ +[a-z]+: Location\[' > "$OUT/location-brightness.txt"
sh_ am start -n com.orcahome.demoplayer/.DemoActivity --es drive stop > /dev/null
sh_ appops set "$PKG" WRITE_SETTINGS default

# 2.7: lyrics, "Up next" from the player's queue, the last song kept for the next start.
prefs 6 true theme north wide
sh_ am start -n com.orcahome.demoplayer/.DemoActivity --es song playlist > /dev/null; sleep 2
adb logcat -c
home; shot 61-lyrics 12
say "Lyrics: $(adb logcat -d -s OrcaHome:I | grep -o 'lyrics for .*' | tr '\n' ';')"
sh_ pm trim-caches 999G > /dev/null   # as optimize.sh / app-speed.sh do
say "Saved lyrics after 'trim caches': $(sh_ "run-as $PKG ls files/lyrics" | grep -c json) song(s) (expected 1 or more)"
sh_ input tap 823 441; sleep 2   # pause, so the screen is still for the next tap
tap_text "Up next" "823 351"; shot 62-up-next 3
tap_text "Viva la Vida" "512 256"; sleep 1
home; shot 63-next-song-lyrics 12
say "After picking from Up next: $(sh_ dumpsys media_session | grep -m1 -o 'description=[^,]*')"
say "Lyrics: $(adb logcat -d -s OrcaHome:I | grep -o 'lyrics for .*' | tr '\n' ';')"
# Lyrics timing: long-press the lyrics, "Later +" twice (paused, so the screen is still).
sh_ input tap 823 441; sleep 2
sh_ input swipe 823 240 823 240 1200; sleep 2
tap_text "Later +" "834 268"; sleep 1; tap_text "Later +" "834 268"; shot 66-lyrics-timing 2
say "Lyrics timing saved: $(sh_ "run-as $PKG cat shared_prefs/lyricsoffset.xml" | grep -o 'value="[^"]*"' | tr '\n' ' ') (expected -1000 for Viva la Vida)"
tap_text "Done" "960 268"; sleep 1
tap_text "Lyrics" "960 182"; shot 67-lyrics-off 3   # back to the big cover
sh_ am force-stop com.orcahome.demoplayer; sleep 2
home; shot 64-last-played 5
say "Remembered song: $(sh_ "run-as $PKG cat shared_prefs/lastplayed.xml" | grep -o 'name="title">[^<]*' | sed 's/.*>//'), cover saved: $(sh_ "run-as $PKG ls files/last-cover.jpg" 2>/dev/null | grep -c jpg)"
# Play on the last song: the player starts in the background, home stays in front.
sh_ appops set "$PKG" SYSTEM_ALERT_WINDOW allow   # as launcher.sh does (to come back home)
adb logcat -c
sh_ input tap 823 441; shot 65-play-last-played 6
say "Play on the last song: in front $(front), player $(player_state), $(adb logcat -d -s OrcaHome:I | grep -o 'resume: .*' | tr '\n' ';')"
# Again with a player that ignores the button (as when the firmware blocks it): Orca Home
# opens it, presses play once it's ready, and comes back.
sh_ am start -n com.orcahome.demoplayer/.DemoActivity --es buttons off > /dev/null; sleep 2
sh_ am force-stop com.orcahome.demoplayer; sleep 2
home; sleep 4; adb logcat -c
sh_ input tap 823 441; shot 69-play-last-played-opened 10
say "  player blocked in the background: in front $(front), player $(player_state), $(adb logcat -d -s OrcaHome:I | grep -o 'resume: .*' | tr '\n' ';')"
sh_ am start -n com.orcahome.demoplayer/.DemoActivity --es buttons on > /dev/null

# Music shortcuts: nothing playing (the demo player stopped).
sh_ am force-stop com.orcahome.demoplayer
prefs 6 true theme north wide; home; shot 56-music-shortcuts 8
sh_ am start -n com.orcahome.demoplayer/.DemoActivity > /dev/null   # the session the release checks expect
home; sleep 3
say "Debug-build crashes: $(adb logcat -b crash -d | grep -c 'Process: com.orcahome,') (other apps: $(adb logcat -b crash -d | grep 'Process: ' | grep -vc 'Process: com.orcahome,'))"
adb logcat -b crash -d > "$OUT/crash-debug.txt"

# --- Checks with the release APK the head unit gets ----------------------------------------
adb logcat -c
setup_launcher "$RELEASE_APK"
home; sleep 25
pid=$(sh_ pidof "$PKG")
say ""
say "Release APK: $(sh_ dumpsys package "$PKG" | grep -m1 versionName | xargs)"
say "Home screen visible for 25 s, process $pid"
sh_ dumpsys meminfo "$pid" > "$OUT/meminfo-visible.txt"
say "  memory (TOTAL PSS): $(grep -m1 -E '^ +TOTAL' "$OUT/meminfo-visible.txt" | awk '{print $2}') KB"
say "  CPU over 10 s: $(sh_ top -b -d 10 -n 2 -p "$pid" -o %CPU | tail -1 | xargs)%"
say "  location requests from Orca Home: $(requests)"

# Leave home: open Android settings like a user would open Spotify.
sh_ am start -W -n com.android.settings/.Settings > /dev/null
sleep 20
say "Another app in front for 20 s:"
say "  process still there: $(sh_ pidof "$PKG" || echo no)"
say "  CPU over 10 s: $(sh_ top -b -d 10 -n 2 -p "$pid" -o %CPU | tail -1 | xargs)%"
say "  location requests from Orca Home: $(requests)"
sh_ dumpsys location > "$OUT/location-hidden.txt"
sh_ dumpsys meminfo "$pid" > "$OUT/meminfo-hidden.txt"
say "  memory (TOTAL PSS): $(grep -m1 -E '^ +TOTAL' "$OUT/meminfo-hidden.txt" | awk '{print $2}') KB"

home; sleep 8
say "Back home: location requests from Orca Home: $(requests)"
shot 16-release-home 2
# Survives a theme rebuild and repeated home presses without crashing.
for i in 1 2 3 4 5; do sh_ input keyevent KEYCODE_HOME; sleep 1; done
sh_ am start -W -n "$PKG/.SettingsActivity" > /dev/null; sleep 2; sh_ input keyevent KEYCODE_BACK; sleep 2
crashes=$(adb logcat -b crash -d | grep -c 'Process: com.orcahome,')
say "Release-APK crashes: $crashes"
adb logcat -b crash -d > "$OUT/crash-release.txt"
adb logcat -d | grep -iE 'orcahome|AndroidRuntime' | tail -200 > "$OUT/logcat-orcahome.txt"
say "Done."
