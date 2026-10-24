#!/usr/bin/env bash
# Shows why Wi-Fi connects slowly and times a real reconnect.
#
#   ./scripts/wifi-speed.sh                 signal, band, saved networks (changes nothing)
#   ./scripts/wifi-speed.sh --measure       turn Wi-Fi off/on and time the reconnect
#   ./scripts/wifi-speed.sh --forget-others forget every saved network except the current one
. "$(dirname "$0")/common.sh"
MODE=${1:-}
[ "$MODE" = -h ] || [ "$MODE" = --help ] && { sed -n '2,7p' "$0"; exit 0; }
require_device
SDK=$(dev getprop ro.build.version.sdk)

info "Current connection"
winfo=$(dev dumpsys wifi | grep -m1 'mWifiInfo')
ssid=$(echo "$winfo" | sed -n 's/.*mWifiInfo SSID: "\{0,1\}\([^",]*\).*/\1/p')
rssi=$(echo "$winfo" | grep -o 'RSSI: -*[0-9]*' | grep -o -- '-*[0-9]*$')
freq=$(echo "$winfo" | grep -o 'Frequency: [0-9]*' | grep -o '[0-9]*$')
if [ -z "$ssid" ] || [ "$ssid" = "<unknown ssid>" ]; then
  warn "not connected to Wi-Fi"
else
  echo "  Network : $ssid"
  [ -n "$freq" ] && { [ "$freq" -ge 5000 ] && echo "  Band    : 5 GHz ($freq MHz)" || echo "  Band    : 2.4 GHz ($freq MHz) — shares the antenna with Bluetooth"; }
  if [ -n "$rssi" ]; then
    echo "  Signal  : $rssi dBm"
    [ "$rssi" -lt -70 ] && warn "weak signal: check the Wi-Fi antenna cable behind the unit, and where the phone sits"
  fi
fi

info "Saved networks"
if [ "${SDK:-0}" -ge 30 ]; then
  nets=$(dev cmd wifi list-networks | tail -n +2)
  count=$(echo "$nets" | grep -c .)
  echo "$nets" | sed 's/^/  /'
  [ "$count" -gt 2 ] && warn "$count saved networks: the unit scans for and tries each one after every start. Keep only your phone hotspot (--forget-others)."
else
  echo "  (Android $SDK can't list them from ADB — check Settings → Wi-Fi → Saved networks and remove the ones you don't use)"
fi
echo "  Captive-portal check: $(dev settings get global captive_portal_mode)  (0 = off, faster; optimize.sh sets this)"

case "$MODE" in
--forget-others)
  [ "${SDK:-0}" -ge 30 ] || { echo "Needs Android 11+. Remove them in Settings → Wi-Fi → Saved networks."; exit 1; }
  [ -n "$ssid" ] || { echo "Connect to the network you want to keep first."; exit 1; }
  others=$(echo "$nets" | awk -v keep="$ssid" '{id=$1; $1=""; sub(/^ /,""); name=$0; sub(/ +[^ ]+$/,"",name); if (name != keep) print id" "name}')
  [ -n "$others" ] || { ok "nothing to forget"; exit 0; }
  # Safety: if the current network isn't recognised in the list, forget nothing.
  [ "$(echo "$others" | grep -c .)" -lt "$count" ] || { warn "couldn't find \"$ssid\" in the saved list — forgetting nothing. Remove networks in Settings instead."; exit 1; }
  echo; echo "Will forget (you'd need to re-enter their passwords later):"; echo "$others" | sed 's/^[0-9]* /  - /'
  printf 'Keep only "%s"? [y/N] ' "$ssid"; read -r yn
  [ "$yn" = y ] || [ "$yn" = Y ] || exit 0
  echo "$others" | while read -r id name; do dev cmd wifi forget-network "$id" >/dev/null && ok "forgot $name"; done
  ;;
--measure)
  [ -n "$ssid" ] || { echo "Connect to Wi-Fi first."; exit 1; }
  info "Timing a reconnect to \"$ssid\""
  # Runs detached on the unit, because turning Wi-Fi off also cuts a wireless ADB link.
  dev_in 'cat > /data/local/tmp/wifi_time.sh' <<'EOS'
f=/data/local/tmp/wifi_time; rm -f $f
svc wifi disable; sleep 3; svc wifi enable
t0=$(date +%s); i=0
while [ $i -lt 90 ]; do ip -4 addr show wlan0 2>/dev/null | grep -q 'inet ' && break; sleep 1; i=$(( $(date +%s) - t0 )); done
ip_t=$i
while [ $i -lt 90 ]; do ping -c1 -W1 8.8.8.8 >/dev/null 2>&1 && break; sleep 1; i=$(( $(date +%s) - t0 )); done
echo "$ip_t $i" > $f
EOS
  # Look up the address while the link is still up, and clear any old result first.
  serial=${ADB_SERIAL:-$(adb devices | tr -d '\r' | awk 'NR > 1 && $2 == "device" {print $1; exit}')}
  dev rm -f /data/local/tmp/wifi_time
  if [ "$(dev cat /data/local/tmp/wifi_time.sh | grep -c .)" -lt 5 ]; then
    warn "couldn't copy the timing script to the unit; run this again"; exit 1
  fi
  dev 'setsid sh /data/local/tmp/wifi_time.sh >/dev/null 2>&1 </dev/null &'
  echo "  Wi-Fi is restarting. If you're on wireless ADB the link drops for a moment — waiting up to 3 minutes..."
  result=""
  for _ in $(seq 1 65); do
    sleep 3
    case "$serial" in *:*) [ "$ON_DEVICE" = 0 ] && adb connect "$serial" >/dev/null 2>&1 ;; esac
    result=$(dev cat /data/local/tmp/wifi_time)
    [ -n "$result" ] && break
  done
  if [ -z "$result" ]; then
    warn "no result. On Android 11+ Wireless debugging switches itself off when Wi-Fi drops: turn it back on and run: adb shell cat /data/local/tmp/wifi_time"
    exit 1
  fi
  set -- $result
  echo "  Got an IP address after : ${1} s"
  echo "  Internet working after  : ${2} s"
  if   [ "$2" -ge 90 ]; then warn "never got internet: check the phone hotspot has mobile data"
  elif [ "$1" -gt 15 ]; then warn "slow to join: forget old networks, use a static IP (README), keep the phone hotspot always on"
  elif [ "$2" -gt $(( $1 + 5 )) ]; then warn "joins fast but internet is slow to start: run optimize.sh (turns off the captive-portal check)"
  else ok "good (under ~10 s is normal)"; fi
  ;;
*) echo; echo "Next: $0 --measure" ;;
esac
