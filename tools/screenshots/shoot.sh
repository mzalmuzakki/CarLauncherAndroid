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

prefs() {   # theme map(true/false) mapstyle(theme/classic) [direction north/heading] [layout wide/tall]
    local xml b64
    xml="<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name=\"theme7\" value=\"$1\" />
    <boolean name=\"map\" value=\"$2\" />
    <string name=\"mapstyle\">$3</string>
    <string name=\"mapdir\">${4:-north}</string>
    <string name=\"maplayout\">${5:-wide}</string>
    <string name=\"favorites\">$FAVS</string>
    <long name=\"stamp\" value=\"$RANDOM$RANDOM\" />
</map>"
    b64=$(printf '%s' "$xml" | base64 | tr -d '\n')
    sh_ am force-stop "$PKG"
    sh_ "run-as $PKG sh -c 'mkdir -p shared_prefs && echo $b64 | base64 -d > shared_prefs/orcahome.xml'"
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
sh_ am start -n com.orcahome.demoplayer/.DemoActivity --es drive stop > /dev/null
home; sleep 3
say "Debug-build crashes: $(adb logcat -b crash -d | grep -c 'FATAL EXCEPTION')"
adb logcat -b crash -d > "$OUT/crash-debug.txt"

# --- Checks with the release APK the head unit gets ----------------------------------------
adb logcat -c
setup_launcher "$RELEASE_APK"
home; sleep 25
pid=$(sh_ pidof "$PKG")
say ""
say "Release APK: $(sh_ dumpsys package "$PKG" | grep -m1 versionName | xargs)"
say "Home screen visible for 25 s, process $pid"
sh_ dumpsys meminfo "$PKG" > "$OUT/meminfo-visible.txt"
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
sh_ dumpsys meminfo "$PKG" > "$OUT/meminfo-hidden.txt"
say "  memory (TOTAL PSS): $(grep -m1 -E '^ +TOTAL' "$OUT/meminfo-hidden.txt" | awk '{print $2}') KB"

home; sleep 8
say "Back home: location requests from Orca Home: $(requests)"
shot 16-release-home 2
# Survives a theme rebuild and repeated home presses without crashing.
for i in 1 2 3 4 5; do sh_ input keyevent KEYCODE_HOME; sleep 1; done
sh_ am start -W -n "$PKG/.SettingsActivity" > /dev/null; sleep 2; sh_ input keyevent KEYCODE_BACK; sleep 2
crashes=$(adb logcat -b crash -d | grep -c 'FATAL EXCEPTION')
say "Release-APK crashes: $crashes"
adb logcat -b crash -d > "$OUT/crash-release.txt"
adb logcat -d | grep -iE 'orcahome|AndroidRuntime' | tail -200 > "$OUT/logcat-orcahome.txt"
say "Done."
