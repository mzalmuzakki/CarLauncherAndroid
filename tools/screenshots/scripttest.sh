#!/usr/bin/env bash
# Runs the real scripts/launcher.sh, 'launcher.sh off' and restore.sh against the
# emulator and checks that each way back really restores the original launcher.
# (The emulator isn't NWD firmware; that path is covered by the simulated unit.)
#   scripttest.sh <out dir>
set -uo pipefail
OUT=$1
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
REPORT=$OUT/report.txt
export STATE_DIR=$(mktemp -d) LAUNCHER_WAIT=2
say() { echo "$*" | tee -a "$REPORT"; }
home() { adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1; }
front() { adb shell dumpsys activity activities | tr -d '\r' | grep -m1 -E 'mResumedActivity' | grep -o '[a-z][a-zA-Z0-9_.]*/[a-zA-Z0-9_.$]*'; }
saw() { adb shell appops get com.orcahome SYSTEM_ALERT_WINDOW | tr -d '\r' | grep -o -E 'allow|default|ignore|deny' | head -1; }
check() { # label expected-home-prefix
    local h f; sleep 3; h=$(home); f=$(front)
    case "$h" in "$2"*) say "  OK   $1: home=$h, in front=$f, overlay=$(saw)" ;;
                 *) say "  FAIL $1: home=$h (expected $2...), in front=$f" ;; esac
}

say ""
say "Script test (real launcher.sh / off / restore.sh on the emulator):"
# Start from the emulator's own launcher as the default home app.
adb shell cmd package set-home-activity com.google.android.apps.nexuslauncher/.NexusLauncherActivity > /dev/null
ORIG=com.google.android.apps.nexuslauncher/
check "before" "$ORIG"

bash "$ROOT/scripts/launcher.sh" > "$OUT/scripttest-install.txt" 2>&1
check "launcher.sh" "com.orcahome/"
bash "$ROOT/scripts/launcher.sh" off > "$OUT/scripttest-off.txt" 2>&1
check "launcher.sh off" "$ORIG"

bash "$ROOT/scripts/launcher.sh" >> "$OUT/scripttest-install.txt" 2>&1
check "launcher.sh again" "com.orcahome/"
bash "$ROOT/scripts/restore.sh" > "$OUT/scripttest-restore.txt" 2>&1
check "restore.sh" "$ORIG"

bash "$ROOT/scripts/launcher.sh" >> "$OUT/scripttest-install.txt" 2>&1
rm -rf "$STATE_DIR"/*
check "launcher.sh, records deleted" "com.orcahome/"
bash "$ROOT/scripts/restore.sh" >> "$OUT/scripttest-restore.txt" 2>&1
check "restore.sh without records" "$ORIG"
say "  crashes: $(adb logcat -b crash -d | grep -c com.orcahome)"
