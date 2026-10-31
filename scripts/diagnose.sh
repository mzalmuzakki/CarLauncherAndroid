#!/usr/bin/env bash
# Read-only health check: shows WHY the unit is slow. Changes nothing.
. "$(dirname "$0")/common.sh"
require_device

info "Unit"
echo "  Model        : $(dev getprop ro.product.model) / $(dev getprop ro.product.manufacturer)"
echo "  Platform     : $(dev getprop ro.board.platform) $(dev getprop ro.hardware)"
echo "  Android      : $(dev getprop ro.build.version.release) (SDK $(dev getprop ro.build.version.sdk))"
echo "  Firmware     : $(dev getprop ro.build.display.id)"
up_s=$(dev cat /proc/uptime | cut -d. -f1)
procs=$(dev ps -A | awk 'NR > 1' | wc -l)
threads=$(dev ps -A -T | awk 'NR > 1' | wc -l)
[ -n "$up_s" ] && echo "  Uptime       : $((up_s / 3600)) h $((up_s % 3600 / 60)) min since last real reboot; $procs processes, $threads threads"
# Google Play services: the version last seen running without the start loop is noted in
# backup/gms-good.txt, so when an automatic update brings the loop back, the warning names
# the update and gives the command that puts the good version back.
gms=$(dev dumpsys package com.google.android.gms | grep -m1 -o 'versionName=[^ ]*' | cut -d= -f2)
gms_good=$(cat "$STATE_DIR/gms-good.txt" 2>/dev/null)
gms_apk() { # a downloaded Play services APK of version $1 (Termux / computer Downloads), if any
  local f
  for f in "$HOME"/storage/downloads/com.google.android.gms_"$1"_*.apk \
           "$HOME"/Downloads/com.google.android.gms_"$1"_*.apk ./com.google.android.gms_"$1"_*.apk; do
    [ -f "$f" ] && { echo "$f"; return; }
  done
}
# App processes that never finished starting keep the name <pre-initialized>.
# On this unit Google Play services 26.36 got stuck in a start loop at boot:
# a new copy every few seconds, 1,000+ after minutes, RAM gone.
stuck=$(dev ps -A -o UID,NAME | awk '$2 == "<pre-initialized>" {print $1}' | sort | uniq -c | sort -rn | head -1)
n_stuck=$(echo "$stuck" | awk '{print $1}')
if [ "${n_stuck:-0}" -gt 50 ]; then
  uid=$(echo "$stuck" | awk '{print $2}')
  case "$uid" in u0_a*) uid=$((10000 + ${uid#u0_a})) ;; esac
  app=$(dev pm list packages -U | awk -v u="uid:$uid" '$2 == u {sub("package:", "", $1); print $1}' | head -1)
  warn "$n_stuck processes stuck starting (app: ${app:-uid $uid}). This is what makes the unit slow."
  case "$app" in
    com.google.android.gms|com.google.android.gsf)
      if [ -n "$gms_good" ] && [ "$gms_good" != "$gms" ]; then
        echo "    Play services updated itself: $gms_good ran fine, now $gms. Put $gms_good back, then reboot:"
        apk=$(gms_apk "$gms_good")
        echo "      adb install -r -d \"${apk:-<the Play services $gms_good APK (armeabi-v7a, nodpi)>}\""
        echo "      adb reboot"
        echo "    (README: 'Play services start loop'; then Play Store > Settings > Network preferences >"
        echo "    Auto-update apps > Don't auto-update apps)"
      else
        echo "    Reboot once: it doesn't happen at every boot. If it keeps coming back, install the current"
        echo "    Play services (README: 'Play services start loop'); the factory version can't sign in to Google."
      fi ;;
  esac
elif [ "${procs:-0}" -gt 900 ]; then
  warn "many processes: something leaks over time. A full reboot (not just ignition off) clears it"
fi
if [ -n "$gms" ]; then
  echo "  Play services: $gms${gms_good:+ (last seen without the start loop: $gms_good)}"
  if [ "${n_stuck:-0}" -le 50 ]; then
    if [ -n "$gms_good" ] && [ "$gms_good" != "$gms" ]; then
      warn "Play services changed by itself ($gms_good -> $gms). No start loop now; if the unit gets slow"
      echo "    after a reboot, run diagnose.sh again: it then gives the command to put $gms_good back."
    fi
    # The loop shows within about two minutes of a start: after three, this version runs fine.
    if [ "${up_s:-0}" -ge 180 ] && [ "$gms_good" != "$gms" ]; then
      mkdir -p "$STATE_DIR" && echo "$gms" > "$STATE_DIR/gms-good.txt"
    fi
  fi
fi
echo "  CPU cores    : $(dev cat /proc/cpuinfo | grep -c '^processor') (as reported)"
max_khz=$(dev cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq)
[ -n "$max_khz" ] && echo "  CPU max freq : $(awk -v k="$max_khz" 'BEGIN{printf "%.2f", k/1000000}') GHz (as reported)"
echo "  CPU part     : $(dev cat /proc/cpuinfo | grep -m1 'CPU part' | awk '{print $NF}')  (0xd03 = Cortex-A53, 0xd05 = A55)"
cores=$(dev cat /proc/cpuinfo | grep -c '^processor')
case "$(dev getprop ro.board.platform) $(dev getprop ro.hardware)" in
  *sun50iw9*) real="4 cores, max ~1.5 GHz (Allwinner T507/H616 family)" ; real_cores=4 ;;
  *sun50iw10*) real="4 cores, max ~1.8 GHz (Allwinner A133/T5 family)" ; real_cores=4 ;;
  *sun8iw*)   real="4 cores, max ~1.3-1.8 GHz (32-bit Allwinner T3/T8/A-series)" ; real_cores=4 ;;
  *) real=""; real_cores=0 ;;
esac
if [ -n "$real" ]; then
  echo "  Chip really  : $real"
  [ "$real_cores" -gt 0 ] && [ "$cores" -gt "$real_cores" ] && warn "the system reports $cores cores, but this chip has $real_cores: the CPU specs are faked"
fi
abis=$(dev getprop ro.product.cpu.abilist)
echo "  App ABIs     : $abis"
[ -z "$abis" ] || echo "$abis" | grep -q arm64 || warn "32-bit only Android: newer YouTube/Netflix/Spotify builds are 64-bit-first, so these run older, slower versions"

info "Real RAM (fake-spec check)"
# Some units report MemTotal twice (a faked value and the real one): use the smaller.
mem_all=$(dev cat /proc/meminfo | awk '/^MemTotal/ {print $2}')
mem_kb=$(echo "$mem_all" | sort -n | head -1)
[ "$(echo "$mem_all" | grep -c .)" -gt 1 ] && warn "the kernel reports two RAM sizes ($(echo $mem_all | tr ' ' '/') kB); using the smaller, real one"
avail_kb=$(dev cat /proc/meminfo | awk '/^MemAvailable/ {print $2; exit}')
[ -n "$mem_kb" ] || { warn "couldn't read memory info — run the scan again"; exit 1; }
mem_gb=$(awk -v k="$mem_kb" 'BEGIN{printf "%.1f", k/1048576}')
avail_mb=$((avail_kb / 1024))
echo "  Total        : ${mem_gb} GB (kernel)   free now: ${avail_mb} MB"
[ "${mem_kb:-0}" -lt 2500000 ] && warn "Only ~2 GB RAM or less. If the box said 4/6/8 GB, the spec is faked — keep few apps installed."
[ "$avail_mb" -lt 500 ] && warn "Under 500 MB free RAM: apps will cold-start every time (this is the main cause of slow Spotify/YouTube/Netflix)."

swap_kb=$(dev cat /proc/meminfo | awk '/SwapTotal/ {print $2}')
if [ "${swap_kb:-0}" -gt 0 ]; then
  echo "  zram/swap    : $(( swap_kb / 1024 )) MB (already set up by the manufacturer)"
else
  echo "  zram/swap    : none (the root module in magisk/ can add it)"
fi

info "System"
if dev 'su -c id' | grep -q 'uid=0'; then echo "  Root         : yes (magisk/install.sh can be used)"
elif dev 'which su' | grep -q su; then echo "  Root         : su present, but ADB access not allowed (allow Shell in the Magisk app)"
else echo "  Root         : no"; fi
echo "  Kernel       : $(dev uname -r)"
echo "  zram support : $(dev 'ls /sys/block/zram0 >/dev/null 2>&1 && echo yes || echo no')"
echo "  Bootloader   : $(dev getprop ro.boot.verifiedbootstate) / $(dev getprop ro.boot.flash.locked)"

info "Storage"
dev df -h /data | tail -1 | awk '{print "  /data        : "$3" used of "$2" ("$5")"}'
used_pct=$(dev df /data | tail -1 | awk '{gsub("%","",$5); print $5}')
[ "${used_pct:-0}" -gt 80 ] && warn "Storage over 80% full: eMMC gets very slow. Free space to under 75%."

info "Temperature"
for z in $(dev ls -1 /sys/class/thermal | grep '^thermal_zone'); do
  t=$(dev cat /sys/class/thermal/$z/temp); ty=$(dev cat /sys/class/thermal/$z/type)
  case "$t" in ''|*[!0-9-]*) continue ;; esac
  [ "$t" -gt 1000 ] && t=$((t / 1000))
  echo "  $ty: ${t}°C"
  if [ "$t" -ge 80 ]; then warn "$ty is hot: the chip slows itself down. Check ventilation behind the dash."
  elif [ "$t" -ge 70 ]; then warn "$ty is warm: close to the point where it slows down (worse in sun/traffic)."
  fi
done

info "Animations (should be 0.5 or 0)"
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  echo "  $k = $(dev settings get global $k)"
done

info "Wi-Fi"
winfo=$(dev dumpsys wifi | grep -m1 'mWifiInfo')
freq=$(echo "$winfo" | grep -o 'Frequency: [0-9]*' | grep -o '[0-9]*$')
echo "  Network: $(echo "$winfo" | sed -n 's/.*mWifiInfo SSID: "\{0,1\}\([^",]*\).*/\1/p'), $freq MHz $([ "${freq:-0}" -ge 5000 ] && echo '(5 GHz)' || echo '(2.4 GHz)')"
echo "  Always-on scanning: $(dev settings get global wifi_scan_always_enabled)  (1 = wastes CPU/radio)"
echo "  BLE scanning      : $(dev settings get global ble_scan_always_enabled)"

info "Phone projection apps found"
for p in com.zjinnova.zlink com.zjinnova.zlink.wired com.ts.tlink com.suding.speedplay \
         com.carlinkit.carplay net.easyconn com.google.android.projection.gearhead \
         com.syu.carlink com.fyt.carlink; do
  pkg_installed "$p" && echo "  $p"
done

info "Top memory users (MB)"
dev ps -A -o RSS,NAME | awk 'NR > 1 && $1 ~ /^[0-9]+$/ {printf "  %5d  %s\n", $1/1024, $2}' | sort -rn | head -12

info "Sensor driver (firmware bug check)"
# CPU time used by the sensor driver as a share of uptime. On units with the
# "AccelSensor: unknown code" bug it busy-loops on a whole core.
st=$(dev ps -A -o TIME,NAME | awk '/sensors@/ {print $1; exit}')
if [ -n "$st" ] && [ -n "$up_s" ] && [ "$up_s" -gt 0 ]; then
  secs=$(echo "$st" | awk -F'[-:]' '{s = 0; for (i = 1; i <= NF; i++) s = s * 60 + $i; if (NF == 4) s = $1 * 86400 + $2 * 3600 + $3 * 60 + $4; print int(s)}')
  pct=$((secs * 100 / up_s))
  echo "  CPU time     : $st since boot (~$pct% of one core on average)"
  [ "$pct" -ge 20 ] && warn "the sensor driver is busy-looping again (firmware bug). A reboot calms it for a while"
else
  echo "  not found"
fi

info "Most threads per process (a number that keeps growing over days = a leak)"
# One line per thread with its process's PID and name; count them per process.
dev ps -A -T -o PID,NAME | awk 'NR > 1 && $1 ~ /^[0-9]+$/ {c[$1]++; n[$1] = $2}
  END {for (p in c) printf "  %5d  %s\n", c[p], n[p]}' | sort -rn | head -8

info "Installed third-party apps: $(dev pm list packages -3 | wc -l)"
echo
echo "Next: ./scripts/optimize.sh   (shows each change; fully reversible with ./scripts/restore.sh)"
