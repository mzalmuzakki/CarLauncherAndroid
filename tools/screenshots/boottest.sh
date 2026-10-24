#!/usr/bin/env bash
# Reboot test for Orca Home's start-up helper (BootReceiver), run after shoot.sh.
# With two home apps and no default, Android asks "Select a Home app" at boot, like the
# NWD head unit does. Reboots once with the helper off (the box should be in front) and
# once with it on (Orca Home should be in front).
#   boottest.sh <release.apk> <out dir>
set -uo pipefail
APK=$1 OUT=$2
PKG=com.orcahome
REPORT=$OUT/report.txt
say() { echo "$*" | tee -a "$REPORT"; }
sh_() { adb shell "$@" | tr -d '\r'; }

reboot_and_wait() {
    adb reboot
    sleep 10
    adb wait-for-device
    for i in $(seq 1 60); do
        [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
        sleep 5
    done
}

# Up to 2 minutes for Orca Home to come to the front (start-up broadcasts are slow here).
wait_front() {
    for i in $(seq 1 24); do
        in_front | grep -q com.orcahome/ && { echo "after about $((i * 5)) s"; return; }
        sleep 5
    done
    echo "not within 2 min"
}

in_front() { sh_ dumpsys activity activities | grep -m1 -E 'mResumedActivity|ResumedActivity' | sed 's/^ *//'; }

say ""
say "Boot test (two home apps, no default):"
# A fresh install clears the default home app, so Android has to ask at boot.
adb uninstall "$PKG" > /dev/null 2>&1
say "  install: $(adb install -r "$APK" 2>&1 | tail -1)"
sh_ pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
sh_ cmd notification allow_listener "$PKG/$PKG.MediaListener"
# Opened once, like on the unit: Android doesn't wake apps that were never opened.
sh_ am start -W -n "$PKG/.MainActivity" > /dev/null; sleep 3
say "  home apps: $(sh_ cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep / | xargs)"
say "  default home: $(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"

sh_ appops set "$PKG" SYSTEM_ALERT_WINDOW default
sh_ appops write-settings
say "  helper off: $(sh_ appops get "$PKG" SYSTEM_ALERT_WINDOW | xargs)"
reboot_and_wait
sleep 30
say "  helper off, after reboot: $(in_front)"
adb exec-out screencap -p > "$OUT/17-boot-helper-off.png"

sh_ appops set "$PKG" SYSTEM_ALERT_WINDOW allow
sh_ appops write-settings
say "  helper on: $(sh_ appops get "$PKG" SYSTEM_ALERT_WINDOW | xargs)"
adb logcat -c
reboot_and_wait
say "  helper on: Orca Home in front $(wait_front)"
front=$(in_front)
say "  helper on, after reboot:  $front"
adb exec-out screencap -p > "$OUT/18-boot-helper-on.png"
say "  after reboot: $(sh_ appops get "$PKG" SYSTEM_ALERT_WINDOW | xargs)"
adb logcat -d | grep -i -E "START u0.*(HOME|orcahome)|background activity|OrcaHome|BootReceiver|BOOT_COMPLETED" | tail -25 > "$OUT/logcat-boot.txt"
case "$front" in *com.orcahome/*) say "  RESULT: Orca Home opened by itself over the chooser" ;;
                 *) say "  RESULT: Orca Home did NOT come to the front (see logcat-boot.txt)" ;; esac
say "  crashes: $(adb logcat -b crash -d | grep -c "$PKG")"
