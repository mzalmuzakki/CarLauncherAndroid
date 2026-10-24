#!/usr/bin/env bash
# Measures how long an app takes to open from scratch, shows why, and with
# --fix precompiles it and measures again.
#
#   ./scripts/app-speed.sh youtube          measure only
#   ./scripts/app-speed.sh youtube --fix    measure, fix, measure again
#   (also: spotify, netflix, maps, waze, zlink, or any package name)
. "$(dirname "$0")/common.sh"

case "${1:-}" in
  youtube) PKG=com.google.android.youtube ;;
  spotify) PKG=com.spotify.music ;;
  netflix) PKG=com.netflix.mediaclient ;;
  maps)    PKG=com.google.android.apps.maps ;;
  waze)    PKG=com.waze ;;
  zlink)   PKG=com.zjinnova.zlink ;;
  ""|-h|--help) sed -n '2,8p' "$0"; exit 0 ;;
  *)       PKG=$1 ;;
esac
FIX=0; [ "${2:-}" = "--fix" ] && FIX=1

require_device
pkg_installed "$PKG" || { echo "$PKG is not installed."; exit 1; }
ACT=$(dev cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PKG" | tail -1)
case "$ACT" in */*) ;; *) echo "Can't find the launch screen of $PKG."; exit 1 ;; esac

# Cold start: force-stop first, so it's timed like opening it after ignition-on.
cold_start() {
  local total=0 n=0 t
  for i in 1 2; do
    dev am force-stop "$PKG"
    # Quoted for the unit's shell: activity names can contain '$' (YouTube's does).
    t=$(dev am start -W -S -n "'$ACT'" | awk -F': *' '/TotalTime/ {print $2}')
    [ -n "$t" ] && total=$((total + t)) && n=$((n + 1))
    dev input keyevent KEYCODE_HOME >/dev/null
  done
  [ "$n" -gt 0 ] && echo $((total / n)) || echo "?"
}

report() {
  local ms=$1
  [ "$ms" = "?" ] && { echo "  could not measure"; return; }
  echo "  Cold start: $(awk -v m="$ms" 'BEGIN{printf "%.1f", m/1000}') s"
  if   [ "$ms" -lt 4000 ]; then ok "good for a head unit"
  elif [ "$ms" -lt 8000 ]; then warn "okay, could be faster"
  else warn "slow"; fi
}

info "$PKG"
echo "  Version    : $(dev dumpsys package "$PKG" | awk -F= '/versionName/ {print $2; exit}')"
apk=$(dev pm path "$PKG" | head -1 | sed 's/^package://')
apk_mb=$(dev ls -l "$apk" | awk '{printf "%d", $5/1048576}')
echo "  APK size   : ${apk_mb} MB"
status=$(compiled_as "$PKG")
echo "  Compiled as: ${status:-unknown}"
case "$status" in
  speed|everything) ok "fully precompiled" ;;
  speed-profile)    warn "only partly precompiled" ;;
  ""|unknown)       ;;
  *)                warn "NOT precompiled ($status) — the app is interpreted on every start; this alone can make it 2–3x slower" ;;
esac
kb=$(dev cat /proc/meminfo | awk '/MemAvailable/ {print $2; exit}')
avail_mb=$(( ${kb:-0} / 1024 ))
if [ -n "$kb" ]; then echo "  Free RAM   : ${avail_mb} MB"; else echo "  Free RAM   : unknown"; fi
[ -n "$kb" ] && [ "$avail_mb" -lt 400 ] && warn "too little free RAM to start a big app quickly — close other apps / run optimize.sh --lowram"

info "Measuring cold start (opens the app twice, please wait)"
before=$(cold_start); report "$before"

[ "$FIX" = 0 ] && { echo; echo "Try: $0 $1 --fix"; exit 0; }

info "Fixing"
dev pm trim-caches 999G >/dev/null; ok "cache trim requested"
dev am kill-all >/dev/null; ok "background apps closed"
case "$status" in
  speed|everything) ok "already fully precompiled — skipping the compile" ;;
  *) compile_apps "$PKG" ;;
esac

info "Measuring again"
after=$(cold_start); report "$after"
if [ "$before" != "?" ] && [ "$after" != "?" ] && [ "$after" -lt "$before" ]; then
  ok "$(( (before - after) * 100 / before ))% faster"
fi
