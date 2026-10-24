#!/usr/bin/env bash
# Experimental Google Maps window over Orca Home's map panel (launcher.sh maps-window):
# freeform windows on + reboot, Google Maps opened once, then test guidance from the demo
# app (only the debug build accepts it). Run last: it reinstalls the debug build.
#   mapswindow.sh <debug.apk> <out dir>
set -uo pipefail
APK=$1 OUT=$2
PKG=com.orcahome MAPS=com.google.android.apps.maps
REPORT=$OUT/report.txt
say() { echo "$*" | tee -a "$REPORT"; }
sh_() { adb shell "$@" | tr -d '\r'; }
shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
front() { sh_ dumpsys activity activities | grep -m1 -E 'mResumedActivity' | grep -o '[a-z][a-zA-Z0-9_.]*/[a-zA-Z0-9_.$]*'; }
maps_window() { sh_ dumpsys activity activities | grep -A3 "Task id.*$MAPS\|affinity=.*$MAPS" | grep -m2 -o -E 'mBounds=Rect\([^)]*\)|mWindowingMode=[a-z]*' | xargs; }

say ""
say "Maps window test (experimental, freeform windows):"
adb uninstall "$PKG" > /dev/null 2>&1
say "  install debug build: $(adb install -r "$APK" 2>&1 | tail -1)"
sh_ pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
sh_ cmd notification allow_listener "$PKG/$PKG.MediaListener"
sh_ cmd package set-home-activity "$PKG/.MainActivity" > /dev/null
sh_ settings put global enable_freeform_support 1
adb reboot; sleep 10; adb wait-for-device
for i in $(seq 1 60); do
    [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
    sleep 5
done
sleep 30
# Android saves the default home app lazily: set it again after the reboot.
sh_ cmd package set-home-activity "$PKG/.MainActivity" > /dev/null
sh_ input keyevent KEYCODE_HOME; sleep 5
say "  freeform setting after reboot: $(sh_ settings get global enable_freeform_support)"
sh_ am start -W -n "$MAPS/com.google.android.maps.MapsActivity" > /dev/null
sleep 10
sh_ input keyevent KEYCODE_HOME
shot 30-maps-window-before 6
sh_ am start -n com.orcahome.demoplayer/.DemoActivity --es nav on > /dev/null
shot 31-maps-window-navigating 10
say "  navigating: in front=$(front); Maps task: $(maps_window)"
say "  freeform windows on screen: $(sh_ dumpsys activity activities | grep -c 'mWindowingMode=freeform')"
adb logcat -d | grep -i -E "START u0.*maps|OrcaHome" | tail -5 >> "$OUT/maps-window-log.txt"
sh_ dumpsys activity activities > "$OUT/maps-window-activities.txt"
sh_ am start -n com.orcahome.demoplayer/.DemoActivity --es nav off > /dev/null
shot 32-maps-window-ended 8
say "  navigation ended: in front=$(front)"
sh_ settings put global enable_freeform_support 0
say "  crashes: $(adb logcat -b crash -d | grep -c "$PKG")"
