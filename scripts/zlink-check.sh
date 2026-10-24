#!/usr/bin/env bash
# Run while CarPlay / Android Auto is connected through ZLink and in use (e.g.
# with a map moving). Shows what limits it: Wi-Fi doubling, CPU, or heat.
. "$(dirname "$0")/common.sh"
require_device
Z=com.zjinnova.zlink

info "ZLink"
echo "  Version      : $(dev dumpsys package $Z | awk -F= '/versionName/ {print $2; exit}')"
echo "  Compiled as  : $(compiled_as $Z)"
dev dumpsys deviceidle whitelist | grep -q "$Z" && ok "exempt from background killing" || warn "not exempt — run optimize.sh"

info "Wi-Fi (the head unit's single radio)"
winfo=$(dev dumpsys wifi | grep -m1 'mWifiInfo')
ssid=$(echo "$winfo" | sed -n 's/.*mWifiInfo SSID: "\{0,1\}\([^",]*\).*/\1/p')
freq=$(echo "$winfo" | grep -o 'Frequency: [0-9]*' | grep -o '[0-9]*$')
# Interfaces with an IPv4 address: wlan0 = joined a network; an extra wlan/ap
# interface = the head unit's own hotspot (used by wireless CarPlay/AA).
ifs=$(dev ip -4 -o addr show | awk '{print $2}' | grep -E '^(wlan|ap|swlan)' | sort -u | tr '\n' ' ')
echo "  Joined network : ${ssid:-none} ${freq:+($freq MHz)}"
echo "  Wi-Fi interfaces with an address: ${ifs:-none}"
if [ -n "$ssid" ] && [ "$ssid" != "<unknown ssid>" ] && [ "$(echo $ifs | wc -w)" -ge 2 ]; then
  warn "joined to \"$ssid\" AND running its own hotspot: the radio is split between both."
  warn "For wireless CarPlay/AA turn off the phone's hotspot, or use a USB cable."
else
  ok "Wi-Fi isn't doing double duty"
fi

info "CPU right now (busiest processes)"
dev top -b -n 1 -m 8 | awk 'NR > 4' | cut -c1-110

info "Temperature"
for z in $(dev ls -1 /sys/class/thermal | grep '^thermal_zone'); do
  t=$(dev cat /sys/class/thermal/$z/temp); ty=$(dev cat /sys/class/thermal/$z/type)
  case "$t" in ''|*[!0-9-]*) continue ;; esac
  [ "$t" -gt 1000 ] && t=$((t / 1000))
  echo "  $ty: ${t}°C"
  [ "$t" -ge 80 ] && warn "$ty is hot: the chip slows down, so ZLink stutters"
done
