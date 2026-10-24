#!/usr/bin/env bash
# Checks how reliably each way of reading adb output works on this computer.
. "$(dirname "$0")/common.sh"
command -v adb >/dev/null 2>&1 || { echo "adb not found"; exit 1; }
for m in plain execout file merged; do
  n=0
  for i in $(seq 1 10); do [ "$(adb_run $m echo ok$i)" = "ok$i" ] && n=$((n + 1)); done
  printf '  %-8s %2d/10 replies intact\n' "$m" "$n"
done
printf '  %-8s "%s"\n' "sample" "$(adb_run file getprop ro.product.model)"
