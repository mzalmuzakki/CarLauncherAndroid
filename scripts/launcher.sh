#!/usr/bin/env bash
# Installs Orca Home (launcher/orca-home.apk, built by GitHub Actions), grants
# its two permissions and makes it the home screen.
#
#   ./scripts/launcher.sh        install or update, switch to it, compare memory
#   ./scripts/launcher.sh check  memory now: Orca Home vs the original launcher, free RAM
#   ./scripts/launcher.sh off    switch back to the original launcher (Orca Home stays installed)
. "$(dirname "$0")/common.sh"
require_device
APK="$(dirname "$0")/../launcher/orca-home.apk"
ME=com.orcahome/.MainActivity

# Memory a package uses (PSS, MB), or empty when it isn't running.
pss_mb() {
  dev dumpsys meminfo "$1" | awk '$1 == "TOTAL" && $2 ~ /^[0-9]+$/ {printf "%d", $2 / 1024; exit}'
}

free_mb() {
  dev cat /proc/meminfo | awk '/^MemAvailable/ {printf "%d", $2 / 1024; exit}'
}

report() { # old-launcher-package
  local mine old
  mine=$(pss_mb com.orcahome); old=$(pss_mb "$1")
  echo "  Orca Home         : ${mine:-not running} MB"
  echo "  Original launcher : ${old:-not running (good: not using memory)} MB   ($1)"
  echo "  Free RAM          : $(free_mb) MB"
}

if [ "${1:-}" = check ]; then
  prev=$(cat "$STATE_DIR/home-before.txt" 2>/dev/null); prev=${prev%%/*}
  info "Memory"
  report "${prev:-com.android.launcher}"
  exit 0
fi

if [ "${1:-}" = off ]; then
  pkg_installed com.orcahome && orca_boot off && ok "Orca Home no longer opens by itself at start-up"
  pkg_installed com.orcahome && dev pm revoke com.orcahome android.permission.ACCESS_BACKGROUND_LOCATION >/dev/null 2>&1
  # Without this the music-card reader keeps Orca Home running (GPS for the trip, start-up volume).
  pkg_installed com.orcahome && dev cmd notification disallow_listener com.orcahome/com.orcahome.MediaListener >/dev/null 2>&1 \
    && ok "Orca Home no longer runs in the background (music card reader off)"
  pkg_installed com.orcahome && orca_brightness off \
    && ok "Orca Home no longer changes the brightness (if the screen is dim, turn it up in the unit's settings)"
  nwd_home_back
  maps_pip back
  prev=$(cat "$STATE_DIR/home-before.txt" 2>/dev/null)
  real_home "$prev" || prev=$(original_home)
  if [ -n "$prev" ] && dev cmd package set-home-activity "$prev" | grep -qi success; then
    ok "home screen switched back to $prev"
    rm -f "$STATE_DIR/home-before.txt"
  else
    warn "choose the original launcher on the unit: Settings opens now"
    dev am start -a android.settings.HOME_SETTINGS >/dev/null
  fi
  dev input keyevent KEYCODE_HOME
  sleep 1
  orca_stop  # what still runs of Orca Home stops, now that it isn't the home screen
  exit 0
fi

[ -f "$APK" ] || { echo "launcher/orca-home.apk isn't here yet. GitHub builds it a few minutes after a change; run: git pull"; exit 1; }

# Note the original launcher before installing: with two home apps and no
# default yet, Android would only answer "ask the user".
mkdir -p "$STATE_DIR"
saved=$(cat "$STATE_DIR/home-before.txt" 2>/dev/null)
if real_home "$saved"; then
  before=$saved
else
  before=$(original_home)
  if [ -n "$before" ]; then echo "$before" > "$STATE_DIR/home-before.txt"
  else warn "couldn't tell which launcher the unit uses now; './scripts/launcher.sh off' will open the chooser"; fi
fi
old_pkg=${before%%/*}

info "Installing Orca Home"
out=$(adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} install -r "$(host_path "$APK")" </dev/null 2>&1)
if echo "$out" | grep -q Success; then ok "installed"
else echo "$out" | sed 's/^/    /'; warn "install failed (if a Play Protect dialog appeared on the unit, choose Install anyway / More details > Install)"; exit 1; fi

info "Permissions"
dev pm grant com.orcahome android.permission.ACCESS_FINE_LOCATION >/dev/null && ok "GPS for the speedometer"
dev pm grant com.orcahome android.permission.ACCESS_BACKGROUND_LOCATION >/dev/null \
  && ok "GPS while other apps are in front (trip info, service km)"
dev cmd notification allow_listener com.orcahome/com.orcahome.MediaListener >/dev/null && ok "music card (reads what's playing)"
[ "$(dev settings get secure location_mode)" = 0 ] && warn "Location is off on the unit: the speedometer needs it (Settings > Location)"

info "Home screen"
old_mb=""; [ -n "$old_pkg" ] && old_mb=$(pss_mb "$old_pkg")
free_before=$(free_mb)
dev cmd package set-home-activity "$ME" >/dev/null
if nwd_home_fw; then
  # This firmware ignores Android's default on Home and opens the launcher named
  # in its own setting. Note the factory value once, for 'off' and restore.sh.
  if [ ! -f "$STATE_DIR/nwd-home-before.txt" ]; then
    orig=$(ask getprop "$NWD_HOME_PROP")
    # Already switched by hand: the factory launcher is the one noted above.
    [ "$orig" = com.orcahome ] && orig=$old_pkg
    if [ -n "$orig" ] && [ "$orig" != com.orcahome ]; then echo "$orig" > "$STATE_DIR/nwd-home-before.txt"
    else warn "couldn't tell the unit's own launcher setting; to undo by hand: adb exec-out setprop $NWD_HOME_PROP com.android.launchertheme"; fi
  fi
  if set_nwd_home com.orcahome; then ok "Orca Home is now the home screen (the unit's own launcher setting too)"
  else warn "the unit kept its own launcher setting: Home may still open the old launcher"; fi
  # After a reboot this firmware asks "Select a Home app" with only its own launchers listed.
  if orca_boot on; then ok "Orca Home opens by itself after a reboot"
  else warn "couldn't switch on Orca Home's start-up helper; at boot choose \"UI Style\" -> Just once, then press Home"; fi
elif current_home | grep -q '^com.orcahome/'; then
  ok "Orca Home is now the home screen"
else
  warn "press Home on the unit and choose \"Orca Home\" -> Always"
fi
if orca_brightness on; then ok "night brightness on: the screen dims at sunset (Orca Home settings to change)"
else warn "couldn't allow night brightness; Orca Home settings has a button to allow it by hand"; fi
if maps_pip off; then ok "Google Maps' floating window off: Orca Home shows the guidance itself"
else warn "couldn't switch off Google Maps' floating window (it may cover the home screen while navigating)"; fi
dev input keyevent KEYCODE_HOME

# Don't keep the old launcher running next to Orca Home: that would cost memory.
# Not on NWD firmware or for a com.nwd launcher: the factory launcher there also
# handles car functions (door view, car info), and a force-stopped app stays
# stopped until it's opened again.
if ! nwd_home_fw; then
  case "$old_pkg" in
    ""|android|com.orcahome|com.nwd.*) ;;
    *) dev am force-stop "$old_pkg" ;;
  esac
fi
sleep ${LAUNCHER_WAIT:-5}
info "Memory (so Orca Home never makes the unit slower)"
mine=$(pss_mb com.orcahome)
echo "  Before: original launcher ${old_mb:-?} MB, free RAM ${free_before:-?} MB"
echo "  Now   : Orca Home ${mine:-?} MB, free RAM $(free_mb) MB"
if [ -n "$mine" ] && [ -n "$old_mb" ] && [ "$mine" -gt "$old_mb" ]; then
  warn "Orca Home uses more memory than the original launcher. Switch back: ./scripts/launcher.sh off"
elif [ -n "$mine" ] && [ -n "$old_mb" ]; then
  ok "Orca Home uses $((old_mb - mine)) MB less than the original launcher"
fi
echo
echo "Check again any time: ./scripts/launcher.sh check"
echo "Switch back any time: ./scripts/launcher.sh off   (or ./scripts/restore.sh)"
