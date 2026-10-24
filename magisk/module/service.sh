#!/system/bin/sh
# Runs once after every boot. Nothing here can stop the unit from booting:
# each step checks what the kernel supports and only logs if it can't apply.
LOG=/data/local/tmp/headunit_optim.log
log() { echo "$(date '+%F %T') $*" >> $LOG; }
echo "=== boot $(date) ===" > $LOG

# Wait until Android has finished booting, so the car apps start first.
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 20

mem_kb=$(awk '/MemTotal/ {print $2}' /proc/meminfo)

# --- zram: compressed swap in RAM. Makes 2 GB behave like ~3 GB, so big apps
# get swapped out compressed instead of killed and cold-started again.
if [ -e /sys/block/zram0 ]; then
  size=$((mem_kb * 3 / 4 * 1024))   # 75% of RAM (stores ~2x that compressed)
  swapoff /dev/block/zram0 2>/dev/null
  if grep -q zram0 /proc/swaps; then
    log "zram: existing swap still in use, keeping the vendor zram this boot"
  else
    echo 1 > /sys/block/zram0/reset
    for alg in lz4 lzo-rle lzo; do
      grep -qw "$alg" /sys/block/zram0/comp_algorithm 2>/dev/null && { echo $alg > /sys/block/zram0/comp_algorithm; break; }
    done
    if echo $size > /sys/block/zram0/disksize && mkswap /dev/block/zram0 >/dev/null 2>&1 \
       && swapon -p 32767 /dev/block/zram0 2>/dev/null; then
      log "zram on: $((size / 1048576)) MB, $(grep -o '\[[^]]*\]' /sys/block/zram0/comp_algorithm)"
    else
      log "zram: could not enable swap"
    fi
  fi
else
  log "zram: not supported by this kernel (skipped)"
fi
[ -w /proc/sys/vm/swappiness ] && echo 100 > /proc/sys/vm/swappiness && log "swappiness 100"
# Keep the file cache of app code around a bit longer: faster re-opens.
[ -w /proc/sys/vm/vfs_cache_pressure ] && echo 60 > /proc/sys/vm/vfs_cache_pressure

# --- eMMC storage: lower-latency scheduler and sensible read-ahead.
for q in /sys/block/mmcblk*/queue /sys/block/sd*/queue; do
  [ -d "$q" ] || continue
  for s in mq-deadline deadline bfq; do
    grep -qw "$s" $q/scheduler 2>/dev/null && { echo $s > $q/scheduler; break; }
  done
  echo 512 > $q/read_ahead_kb 2>/dev/null
  echo 0 > $q/iostats 2>/dev/null
  dev=${q%/queue}; log "io ${dev##*/}: $(grep -o '\[[^]]*\]' $q/scheduler), read-ahead $(cat $q/read_ahead_kb) KB"
done

# --- Trim the flash once per boot, in the background.
(sleep 120; sm fstrim 2>/dev/null; log "fstrim done") &

log "done. $(grep -E 'MemAvailable|SwapTotal' /proc/meminfo | tr -s ' ' | tr '\n' ' ')"
