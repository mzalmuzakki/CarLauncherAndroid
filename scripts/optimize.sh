#!/usr/bin/env bash
# Speeds up the head unit without root. Every change is recorded in backup/
# and can be undone with ./scripts/restore.sh.
#
#   ./scripts/optimize.sh            UI + Wi-Fi + projection + app launch + storage
#   ./scripts/optimize.sh --debloat  also disable the unused apps in packages-debloat.txt
#   ./scripts/optimize.sh --dry-run  only show the debloat candidates found
#   ./scripts/optimize.sh --lowram   extra tuning for low-RAM units (combine with the above)
#   --keep-projection=PKG            only keep this CarPlay/AA app alive; calm the others
#                                    (e.g. --keep-projection=zlink or =com.zjinnova.zlink)
#   --manual-projection              CarPlay/AA apps (ZLink...) only run when you open them: no
#                                    auto-connect at car start. Without it they wait in the
#                                    background and connect your phone by themselves.
. "$(dirname "$0")/common.sh"

DEBLOAT=0; DRY=0; LOWRAM=0; KEEP_PROJ=; MANUAL_PROJ=0
for a in "$@"; do
  case "$a" in
    --debloat) DEBLOAT=1 ;;
    --dry-run) DEBLOAT=1; DRY=1 ;;
    --lowram) LOWRAM=1 ;;
    --keep-projection=*) KEEP_PROJ=${a#*=} ;;
    --manual-projection) MANUAL_PROJ=1 ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
  esac
done

require_device
mkdir -p "$STATE_DIR"
SDK=$(dev getprop ro.build.version.sdk)

# Apps that must keep running for the car to work, plus the apps the user
# opens most. They are exempted from battery/doze killing and precompiled.
PROJECTION_PKGS="com.zjinnova.zlink com.zjinnova.zlink.wired com.ts.tlink com.suding.speedplay
  com.carlinkit.carplay net.easyconn com.google.android.projection.gearhead com.syu.carlink com.fyt.carlink"
MEDIA_PKGS="com.spotify.music com.google.android.youtube com.google.android.apps.youtube.kids com.netflix.mediaclient
  com.google.android.apps.youtube.music tstudio.nfc.ceksaldo"

# --keep-projection also takes the short names app-speed.sh uses. A wrong name
# would restrict every phone-link app, the one you use included, so stop instead.
if [ -n "$KEEP_PROJ" ]; then
  case "$KEEP_PROJ" in
    zlink) KEEP_PROJ=com.zjinnova.zlink ;;
    easyconn|easyconnection) KEEP_PROJ=net.easyconn ;;
    tlink) KEEP_PROJ=com.ts.tlink ;;
    carlinkit) KEEP_PROJ=com.carlinkit.carplay ;;
    androidauto|gearhead) KEEP_PROJ=com.google.android.projection.gearhead ;;
  esac
  if ! echo $PROJECTION_PKGS | tr ' ' '\n' | grep -qxF -- "$KEEP_PROJ" || ! pkg_installed "$KEEP_PROJ"; then
    echo "--keep-projection: \"$KEEP_PROJ\" isn't an installed CarPlay/Android Auto app."
    echo "Installed ones:"
    for p in $PROJECTION_PKGS; do pkg_installed "$p" && echo "  $p"; done
    exit 1
  fi
fi

# Remove one line from a record file.
drop_line() { # file line
  [ -f "$1" ] || return 0
  grep -vxF "$2" "$1" > "$1.tmp"; mv "$1.tmp" "$1"
}
add_line() { # file line (once)
  grep -qxF "$2" "$1" 2>/dev/null || echo "$2" >> "$1"
}

if [ "$DRY" = 0 ]; then
info "1/6 Faster UI (animations 0.5x)"
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  put_setting global "$k" 0.5
done

info "2/6 Wi-Fi: stop background scanning, keep the link awake"
put_setting global wifi_scan_always_enabled 0
put_setting global ble_scan_always_enabled 0
put_setting global wifi_networks_available_notification_on 0
put_setting global wifi_wakeup_enabled 0
put_setting global network_recommendations_enabled 0
# Wi-Fi sleeping when the screen dims is the classic cause of wireless
# CarPlay/Android Auto drop-outs on these units.
put_setting global wifi_sleep_policy 2
put_setting global wifi_suspend_optimizations_enabled 0
# Faster "connected": skip the internet/captive-portal check Android runs
# before it uses a new Wi-Fi network (hotel login pages won't pop up any more).
put_setting global captive_portal_mode 0
# Let the unit scan as often as it needs right after ignition-on.
[ "${SDK:-0}" -ge 28 ] && put_setting global wifi_scan_throttle_enabled 0

if [ "$MANUAL_PROJ" = 1 ]; then
  info "3/6 CarPlay / Android Auto: only when you open the app (no auto-connect)"
else
  info "3/6 CarPlay / Android Auto: wait in the background and auto-connect the phone"
fi
# Apps already exempt before we started: never record them as our change.
if ! WL=$(user_whitelist); then
  warn "no answer from the unit about battery exemptions — step skipped, run the script again"
  PROJ_TODO=""
else
  PROJ_TODO=$PROJECTION_PKGS
fi
W="$STATE_DIR/whitelisted.txt"; UW="$STATE_DIR/unwhitelisted.txt"; R="$STATE_DIR/restricted.txt"
for p in $PROJ_TODO; do
  pkg_installed "$p" || continue
  if [ "$MANUAL_PROJ" = 1 ]; then
    # Not allowed to start or stay in the background; opening it from the home screen still works.
    # An exemption that was there before us is noted so restore.sh can give it back.
    if echo "$WL" | grep -qx "$p" && ! grep -qxF "$p" "$W" 2>/dev/null; then add_line "$UW" "$p"; fi
    dev cmd deviceidle whitelist -"$p" >/dev/null
    drop_line "$W" "$p"
    set_appop "$p" RUN_IN_BACKGROUND ignore || continue
    set_appop "$p" RUN_ANY_IN_BACKGROUND ignore || continue
    dev am force-stop "$p"
    add_line "$R" "$p"
    ok "$p only runs when you open it"
    continue
  fi
  if [ -n "$KEEP_PROJ" ] && [ "$p" != "$KEEP_PROJ" ]; then
    # A second phone-link app you don't use: may still be opened, but can't sit in the background.
    set_appop "$p" RUN_ANY_IN_BACKGROUND ignore || continue
    add_line "$R" "$p"
    ok "$p can no longer run in the background (you use $KEEP_PROJ)"
    continue
  fi
  dev cmd deviceidle whitelist +"$p" >/dev/null
  if grep -qxF "$p" "$UW" 2>/dev/null; then drop_line "$UW" "$p"        # exempt again, as it was originally
  elif ! echo "$WL" | grep -qx "$p"; then add_line "$W" "$p"; fi        # our change
  set_appop "$p" RUN_IN_BACKGROUND allow || continue
  set_appop "$p" RUN_ANY_IN_BACKGROUND allow || continue
  drop_line "$R" "$p"
  ok "$p exempt from background killing"
done

info "4/6 Faster app launch: precompile Spotify/YouTube/Netflix/projection apps"
if [ "${SDK:-0}" -ge 24 ]; then
  todo=""
  for p in $PROJECTION_PKGS $MEDIA_PKGS; do
    if [ -n "$KEEP_PROJ" ] && echo "$PROJECTION_PKGS" | grep -qw "$p" && [ "$p" != "$KEEP_PROJ" ]; then continue; fi
    pkg_installed "$p" || continue
    case "$(compiled_as "$p")" in
      speed|everything) ok "$p already fully precompiled" ;;
      *) todo="$todo $p" ;;
    esac
  done
  compile_apps $todo
  # Runs on the unit by itself: on slow chips it takes 10-20 min, longer than
  # a Wi-Fi ADB link reliably stays up.
  # Once a week is enough; while it runs, app installs and updates have to wait.
  stamp="$STATE_DIR/last-bg-dexopt"
  last=$(cat "$stamp" 2>/dev/null)
  if [ -n "$last" ] && [ $(( $(date +%s) - last )) -lt 604800 ]; then
    ok "all other apps were optimized less than a week ago — skipped"
  else
    dev 'setsid cmd package bg-dexopt-job >/dev/null 2>&1 </dev/null &'
    sleep 3
    # Only count it as done once it's seen running, or the next week's run would be skipped.
    if dev ps -A -o ARGS | grep -q 'bg-dexopt-job'; then
      date +%s > "$stamp"
      ok "optimizing all other apps on the unit in the background (10-20 min; app installs wait until it's done)"
    else
      warn "couldn't confirm the background optimization started; it will be tried again next run"
    fi
  fi
else
  warn "Android $SDK is too old for on-demand compiling — skipped"
fi

info "    Firmware log spam"
# Some units' sensor driver logs "AccelSensor: unknown code" thousands of
# times per second, keeping logd busy on a whole CPU core. Mute that log tag
# (only the messages; the sensor itself is unchanged).
if dev logcat -d -t 300 -s AccelSensors | grep -q 'unknown code' \
   || [ "$(dev getprop persist.log.tag.AccelSensors)" = S ]; then
  put_prop persist.log.tag.AccelSensors S
  put_prop log.tag.AccelSensors S
else
  ok "no known log spam found"
fi

info "5/6 Storage: trim caches and run flash maintenance"
dev pm trim-caches 999G >/dev/null; ok "app cache trim requested"
dev sm fstrim >/dev/null 2>&1; ok "fstrim requested (restores eMMC write speed)"
dev sm idle-maint run >/dev/null 2>&1; ok "idle maintenance requested"

if [ "$LOWRAM" = 1 ]; then
  info "2 GB mode: free RAM for the apps you actually use"
  # Freeze apps sitting in the background so they stop using CPU (Android 11+).
  [ "${SDK:-0}" -ge 30 ] && put_setting global cached_apps_freezer enabled
  # Put rarely used apps into standby so they can't wake up on their own.
  put_setting global app_standby_enabled 1
  # Stop heavy non-car apps from running when you are not looking at them.
  RESTRICT=$(read_list "$(dirname "$0")/packages-restrict-bg.txt")
  # Lift the restriction from apps taken off the list since an earlier run.
  if [ -f "$R" ]; then
    for p in $(sort -u "$R"); do
      echo "$RESTRICT" | grep -qx "$p" && continue
      echo "$PROJECTION_PKGS" | grep -qw "$p" && continue
      orig=$(awk -v p="$p" '$1 == p && $2 == "RUN_ANY_IN_BACKGROUND" {print $3; exit}' "$STATE_DIR/appops.txt" 2>/dev/null)
      dev cmd appops set "$p" RUN_ANY_IN_BACKGROUND "${orig:-default}" >/dev/null
      drop_line "$R" "$p"
      ok "$p may run in the background again"
    done
  fi
  echo "$RESTRICT" | while read -r p; do
    pkg_installed "$p" || continue
    set_appop "$p" RUN_ANY_IN_BACKGROUND ignore || continue
    add_line "$R" "$p"
    ok "$p can no longer run in the background"
  done
fi
fi

info "6/6 Unused apps"
if [ "$DEBLOAT" = 0 ]; then
  echo "  skipped (run with --dry-run to see candidates, --debloat to disable them)"
else
  LISTS=("$(dirname "$0")/packages-debloat.txt")
  [ "$LOWRAM" = 1 ] && LISTS+=("$(dirname "$0")/packages-debloat-lowram.txt")
  # Never touch car-critical components, whatever the list says.
  PROTECT='syu|fyt|canbus|nfc|bluetooth|launcher|zlink|tlink|carlink|carplay|gearhead|systemui|settings|inputmethod|gms$|vending|webview|telephony|radio|media$|providers'
  installed=$(dev pm list packages -e | sed 's/^package://')
  KEEP=$(read_list "$(dirname "$0")/packages-keep.txt")
  for l in "${LISTS[@]}"; do read_list "$l"; done | while read -r p; do
    echo "$installed" | grep -qx "$p" || continue
    if echo "$p" | grep -Eq "$PROTECT"; then warn "$p is protected — skipped"; continue; fi
    if echo "$KEEP" | grep -qx "$p"; then warn "$p is in packages-keep.txt — skipped"; continue; fi
    if [ "$DRY" = 1 ]; then echo "  would disable: $p"; continue; fi
    if dev pm disable-user --user 0 "$p" | grep -q disabled; then
      echo "$p" >> "$STATE_DIR/disabled.txt"; ok "disabled $p"
    fi
  done
fi

[ "$DRY" = 1 ] && exit 0
dev am kill-all >/dev/null
echo
info "Done. Reboot the head unit now (turn the volume down first, then: adb reboot) and follow the manual steps in README.md."
echo "    Undo everything with: ./scripts/restore.sh"
