#!/usr/bin/env bash
# Shared helpers. Every script talks to the head unit through `dev`, which
# uses adb from a computer, or runs commands directly when executed on the
# head unit itself (e.g. from a terminal app with shell/ADB permission).

set -u

# Git Bash on Windows rewrites arguments like /proc/meminfo into Windows
# paths before they reach adb.exe; turn that off.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

STATE_DIR="${STATE_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/backup}"
ADB_SERIAL="${ADB_SERIAL:-}"

if command -v getprop >/dev/null 2>&1 && ! command -v adb >/dev/null 2>&1; then
  ON_DEVICE=1
else
  ON_DEVICE=0
fi

# How adb output is captured. Under Git Bash on Windows some ways randomly
# lose the output, so require_device picks one that works (see pick_adb_mode).
ADB_MODE="${ADB_MODE:-}"
TMP_OUT="${TMPDIR:-/tmp}/hu_adb_out.$$"

adb_run() { # mode, command...
  local mode=$1; shift
  case "$mode" in
    # exec-out has no separate error stream, so silence errors on the unit itself.
    execout) adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} exec-out "{ $*
} 2>/dev/null" </dev/null 2>/dev/null ;;
    file)    adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} shell -n "$@" </dev/null >"$TMP_OUT" 2>/dev/null; cat "$TMP_OUT"; rm -f "$TMP_OUT" ;;
    merged)  adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} shell -n "$@" </dev/null 2>&1 ;;
    *)       adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} shell -n "$@" </dev/null 2>/dev/null ;;
  esac | tr -d '\r'
}

dev() {
  if [ "$ON_DEVICE" = 1 ]; then
    sh -c "$*" 2>/dev/null
  else
    adb_run "${ADB_MODE:-plain}" "$@"
  fi
}

# Try each capture method 8 times; keep the first that never loses output.
pick_adb_mode() {
  local m i good
  for m in execout plain file merged; do
    good=1
    for i in 1 2 3 4 5 6 7 8; do
      [ "$(adb_run $m echo ok$i)" = "ok$i" ] || { good=0; break; }
    done
    [ "$good" = 1 ] && { ADB_MODE=$m; return 0; }
  done
  return 1
}

# Same as dev, but passes this script's stdin through (e.g. to write a file).
dev_in() {
  if [ "$ON_DEVICE" = 1 ]; then
    sh -c "$*" 2>/dev/null
  elif [ "$ADB_MODE" = execout ]; then
    adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} exec-in "$*" 2>/dev/null
  else
    adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} shell "$@" 2>/dev/null | tr -d '\r'
  fi
}

# A path on this computer, in the form adb.exe understands. Needed on Windows
# because MSYS path translation is switched off above (for paths on the unit).
host_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi
}

info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }

require_device() {
  if [ "$ON_DEVICE" = 0 ]; then
    command -v adb >/dev/null 2>&1 || { echo "adb not found. Install Android platform-tools first (see README)."; exit 1; }
    local out try serial
    for try in 1 2 3; do
      out=$(adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} exec-out echo ok </dev/null 2>&1 | tr -d '\r')
      [ "$out" = "ok" ] && break
      # Wi-Fi ADB links on some units drop to "offline"; reconnect once and retry.
      serial=${ADB_SERIAL:-$(adb devices | awk 'NR > 1 && $1 ~ /:/ {print $1; exit}')}
      if echo "$out" | grep -q offline && [ -n "$serial" ]; then
        adb disconnect "$serial" >/dev/null 2>&1
        adb connect "$serial" >/dev/null 2>&1
        sleep 2
      fi
    done
    if [ "$out" != "ok" ]; then
      echo "Can't talk to the head unit. adb said:"
      echo "$out" | sed 's/^/    /'
      echo "Check with: adb devices   (connect with: adb connect <head-unit-ip>:5555, see README step 3)"
      exit 1
    fi
  fi
  [ "$ON_DEVICE" = 1 ] && return
  if [ -z "$ADB_MODE" ] && ! pick_adb_mode; then
    echo "The head unit answers, but its replies keep coming back empty."
    echo "Try: adb kill-server && adb connect <head-unit-ip>:5555, then run this again."
    exit 1
  fi
  echo "(adb capture mode: $ADB_MODE)"
}

# The full package list, fetched once and retried: an empty reply means the
# link hiccupped, not that nothing is installed.
PKG_CACHE=""
pkg_installed() {
  local i
  for i in 1 2 3; do
    [ -n "$PKG_CACHE" ] && break
    PKG_CACHE=$(dev pm list packages)
  done
  echo "$PKG_CACHE" | grep -qx "package:$1"
}

# How an app is compiled (speed, speed-profile, quicken, ...). The dexopt dump
# is fetched once; it is large and slow to produce on weak units.
DEXOPT_CACHE=""
compiled_as() { # pkg
  [ -n "$DEXOPT_CACHE" ] || DEXOPT_CACHE=$(dev dumpsys package dexopt)
  echo "$DEXOPT_CACHE" | grep -A4 "\[$1\]" | grep -o 'status=[a-z-]*' | head -1 | cut -d= -f2
}

# Precompile apps fully ("speed"). Runs detached on the unit, which logs each
# result to a file that we poll, so a slow app or a dropped Wi-Fi ADB link
# loses nothing.
compile_apps() { # pkg...
  local log=/data/local/tmp/hu_compile.log shown=0 empty=0 idle=0 n out procs serial=""
  [ $# -gt 0 ] || return 0
  # A job from an earlier run would write its results into our log and end our wait early.
  if dev ps -A -o ARGS | grep -q 'hu_compile\.log'; then
    warn "an earlier compile job is still running on the unit; run this again when it's done"
    warn "  (progress: adb exec-out cat $log)"
    return 1
  fi
  dev "rm -f $log; setsid sh -c 'for p in $*; do r=\$(cmd package compile -m speed -f \$p 2>&1 | tail -1); echo \"\$p \$r\" >> $log; done; echo ALLDONE >> $log' >/dev/null 2>&1 </dev/null &"
  echo "  compiling $# app(s) on the unit (1-3 min each)..."
  # Wi-Fi ADB address, looked up while the link is known to be up.
  if [ "$ON_DEVICE" = 0 ]; then
    serial=${ADB_SERIAL:-$(adb devices | awk 'NR > 1 && $1 ~ /:/ {print $1; exit}')}
  fi
  for n in $(seq 1 360); do
    sleep 5
    # Reconnect only a link that has really dropped (a reconnect can itself fail).
    if [ -n "$serial" ] && [ "$(adb -s "$serial" get-state 2>/dev/null | tr -d '\r')" != device ]; then
      adb connect "$serial" >/dev/null 2>&1
      continue
    fi
    out=$(dev cat $log)
    if [ -z "$out" ]; then
      empty=$((empty + 1))
      # No log and no compiler running for a minute: the job never started or was stopped.
      # (An empty process list means the reply was lost, not that nothing runs.)
      if [ $((empty % 12)) = 0 ]; then
        procs=$(dev ps -A -o NAME)
        if [ -n "$procs" ] && ! echo "$procs" | grep -qE '^(dex2oat|dex2oat32|dex2oat64|cmd)$'; then
          idle=$((idle + 1))
          [ "$idle" -ge 2 ] && { warn "the compile job isn't running on the unit; try again later"; return 1; }
        fi
      fi
      continue
    fi
    empty=0
    echo "$out" | sed -n "$((shown + 1)),\$p" | while read -r p r; do
      [ "$p" = ALLDONE ] && continue
      case "$r" in *Success*) ok "$p compiled" ;; *) warn "$p: ${r:-no result}" ;; esac
    done
    shown=$(echo "$out" | grep -c .)
    echo "$out" | grep -q '^ALLDONE' && return 0
  done
  warn "still compiling after 30 min; it continues on the unit by itself"
}

# A value wrapped in single quotes for the unit's shell, which reads every
# command line again: ';', '$', '|' or spaces inside a value stay part of it.
q() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

# One line per entry from a package list: no comments, no Windows line endings.
read_list() { # file
  tr -d '\r' < "$1" | grep -v '^[[:space:]]*#' | awk '{print $1}' | grep .
}

# A reply that may be lost on the way: ask up to three times. `settings get`
# and `getprop` always answer something for a working link ("null" when unset).
ask() { # command...
  local i r
  for i in 1 2 3; do
    r=$(dev "$@")
    [ -n "$r" ] && { echo "$r"; return 0; }
  done
  return 1
}

# Save a settings value once, so restore.sh can put it back. Fails when the
# unit's answer never arrives: then the setting must not be changed.
backup_setting() { # namespace key
  mkdir -p "$STATE_DIR"
  local f="$STATE_DIR/settings.txt" v
  grep -q "^$1 $2 " "$f" 2>/dev/null && return 0
  v=$(ask settings get "$1" "$2") || return 1
  printf '%s %s %s\n' "$1" "$2" "$v" >> "$f"
}

# Set an Android property, remembering the old value for restore.sh.
# Some NWD firmwares (ro.nwd.start.launcher.by.fw=true) open the launcher named in
# persist.nwd.launcher.default when Home is pressed, whatever Android's default is.
NWD_HOME_PROP=persist.nwd.launcher.default
nwd_home_fw() { [ "$(ask getprop ro.nwd.start.launcher.by.fw)" = true ]; }
set_nwd_home() { # package; true when the unit now has it
  dev setprop "$NWD_HOME_PROP" "$(q "$1")" >/dev/null
  [ "$(dev getprop "$NWD_HOME_PROP")" = "$1" ]
}

# The unit's factory launcher package when no record says which: the one in the
# firmware's setting if it isn't Orca Home, else "UI Style" (launchertheme, the
# factory default on the owner's unit), else "Launcher".
nwd_factory_home() {
  local p
  p=$(ask getprop "$NWD_HOME_PROP")
  case "$p" in ""|com.orcahome) ;; *) pkg_installed "$p" && { echo "$p"; return; } ;; esac
  for p in com.android.launchertheme com.android.launcher; do
    pkg_installed "$p" && { echo "$p"; return; }
  done
}

# Put the NWD firmware's launcher setting back: to the recorded value, or, when
# there is no record but it still names Orca Home, to the factory launcher.
# Prints what it did; false when the unit didn't take it (record kept).
nwd_home_back() {
  nwd_home_fw || return 0
  local want
  want=$(cat "$STATE_DIR/nwd-home-before.txt" 2>/dev/null)
  if [ -z "$want" ]; then
    [ "$(ask getprop "$NWD_HOME_PROP")" = com.orcahome ] || return 0
    want=$(nwd_factory_home)
    [ -n "$want" ] || { warn "no factory launcher found; by hand: adb exec-out setprop $NWD_HOME_PROP com.android.launchertheme"; return 1; }
  fi
  if set_nwd_home "$want"; then
    ok "the unit's own launcher setting is back to $want"; rm -f "$STATE_DIR/nwd-home-before.txt"
  else
    warn "couldn't set the unit's launcher back; by hand: adb exec-out setprop $NWD_HOME_PROP $want"
    return 1
  fi
}

# Orca Home's start-up helper (BootReceiver) opens Orca Home over the firmware's
# "Select a Home app" box at boot, but only with "display over other apps"
# (Android 10 needs it for that). That permission is the on/off switch: adb can't
# switch single app components on or off.
orca_boot() { # on|off; for on, true once the unit has it
  # Android saves permission changes up to 30 min later; write-settings saves them
  # now, so they survive turning the car off straight away.
  if [ "$1" = on ]; then
    dev appops set com.orcahome SYSTEM_ALERT_WINDOW allow >/dev/null
    dev appops write-settings >/dev/null
    dev appops get com.orcahome SYSTEM_ALERT_WINDOW | grep -q allow
  else
    dev appops set com.orcahome SYSTEM_ALERT_WINDOW default >/dev/null
    dev appops write-settings >/dev/null
  fi
}

# ---- home screen (launcher.sh, restore.sh) ----

current_home() {
  dev cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1
}

# A real launcher component, not the "choose an app" dialog Android answers
# with while no default is set, and not the framework itself.
real_home() { # component
  case "$1" in
    ""|android/*|*ResolverActivity*|com.orcahome/*|com.android.settings/*) return 1 ;;
    */*) return 0 ;;
  esac
  return 1
}

# The home screen of one package (its HOME activity), or nothing.
home_of() { # package
  local c
  c=$(dev cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME "$1" | tail -1)
  real_home "$c" && echo "$c"
}

# The launcher that came with the unit: the default home app; on NWD firmware the
# launcher its own setting names (or its factory one); else the first installed
# home app that isn't Orca Home or Android's fallback screen.
original_home() {
  local c
  c=$(current_home)
  real_home "$c" && { echo "$c"; return; }
  if nwd_home_fw; then
    c=$(home_of "$(nwd_factory_home)")
    [ -n "$c" ] && { echo "$c"; return; }
  fi
  dev cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
    | tr -d ' ' | grep -E '^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$' | while read -r c; do
      real_home "$c" && { echo "$c"; break; }
    done
}

put_prop() { # name value
  local old
  mkdir -p "$STATE_DIR"
  # getprop prints nothing for an unset property, so an empty reply is
  # ambiguous: ask a few times before believing it.
  old=$(ask getprop "$1")
  # Already at the new value means it was set by hand following the README,
  # so the real original is empty (unset).
  [ "$old" = "$2" ] && old=""
  grep -q "^$1 " "$STATE_DIR/props.txt" 2>/dev/null || printf '%s %s\n' "$1" "$old" >> "$STATE_DIR/props.txt"
  dev setprop "$1" "$(q "$2")" >/dev/null
  if [ "$(dev getprop "$1")" = "$2" ]; then ok "$1 = $2"
  else warn "$1 could not be set"; fi
}

put_setting() { # namespace key value
  if ! backup_setting "$1" "$2"; then
    warn "$1/$2: no answer from the unit, left unchanged — run the script again"
    return 1
  fi
  dev settings put "$1" "$2" "$(q "$3")" >/dev/null
  # Read it back: over some adb links a command can be lost without an error.
  local now; now=$(dev settings get "$1" "$2")
  if [ "$now" = "$3" ]; then ok "$1/$2 = $3"
  else warn "$1/$2 didn't change (still '$now') — run the script again"; fi
}

# An app's background mode (allow, ignore, default...), "" when the reply was lost.
appop_mode() { # pkg op
  local out
  out=$(ask cmd appops get "$1" "$2") || return 0
  case "$out" in
    *"$2: "*) echo "$out" | sed -n "s/.*$2: \([a-z_]*\).*/\1/p" | head -1 ;;
    *) echo default ;;   # "No operations." = never changed
  esac
}

# Save an app's background mode once, then change it. restore.sh puts the saved one back.
set_appop() { # pkg op mode
  local f="$STATE_DIR/appops.txt" old
  mkdir -p "$STATE_DIR"
  if ! grep -q "^$1 $2 " "$f" 2>/dev/null; then
    old=$(appop_mode "$1" "$2")
    [ -n "$old" ] || { warn "$1: no answer from the unit, left unchanged"; return 1; }
    printf '%s %s %s\n' "$1" "$2" "$old" >> "$f"
  fi
  dev cmd appops set "$1" "$2" "$3" >/dev/null
}

# Apps the user exempted from battery optimisation ("Don't optimise").
# Fails when the unit doesn't answer.
user_whitelist() {
  local w
  w=$(ask cmd deviceidle whitelist) || return 1
  echo "$w" | sed -n 's/^user,\([^,]*\),.*/\1/p'
}
