#!/usr/bin/env bash
# Checks the private working repository against the public one (CarLauncherAndroid)
# after a change was copied across:
#   1. only the files on the list below may differ,
#   2. no signing key, password or private detail (tools/private-words.txt, which
#      exists only in the private repository) may be in the public one.
#   tools/sync-check.sh <private repo dir> <public repo dir>
set -uo pipefail
PRIV=$1 PUB=$2
# Meant to differ: the signing key (private only), how the build gets it, the personal
# notes (cleaned in public) and the private word list.
ALLOWED='^(launcher/orcahome\.jks|\.github/workflows/launcher\.yml|notes/unit-log\.md|tools/private-words\.txt)$'
bad=0
files() { git -C "$1" ls-files | sort; }

other=$( { comm -3 <(files "$PRIV") <(files "$PUB") | tr -d '\t'
           comm -12 <(files "$PRIV") <(files "$PUB") | while IFS= read -r f; do
             cmp -s "$PRIV/$f" "$PUB/$f" || echo "$f"; done; } | sort -u | grep -Ev "$ALLOWED")
if [ -n "$other" ]; then
  echo "Different, and not on the list of files allowed to differ:"; echo "$other" | sed 's/^/  /'; bad=1
else
  echo "OK  the repositories differ only in the expected files"
fi

keys=$(git -C "$PUB" ls-files | grep -Ei '\.(jks|keystore|p12|pfx)$')
if [ -n "$keys" ]; then echo "Key file in the public repository: $keys"; bad=1
else echo "OK  no key file in the public repository"; fi

pw=$(git -C "$PUB" grep -n -I -E "(storePassword|keyPassword|KEY_PASSWORD:) +['\"]?[A-Za-z0-9]" -- . ':!tools/sync-check.sh' |
     grep -v 'System.getenv\|secrets\.')
if [ -n "$pw" ]; then echo "Password in the public repository:"; echo "$pw" | sed 's/^/  /'; bad=1
else echo "OK  no password in the public repository"; fi

if [ -f "$PRIV/tools/private-words.txt" ]; then
  hits=$(git -C "$PUB" grep -n -I -i -F -f <(grep -v '^#' "$PRIV/tools/private-words.txt" | grep .) || true)
  if [ -n "$hits" ]; then echo "Private details in the public repository:"; echo "$hits" | sed 's/^/  /'; bad=1
  else echo "OK  no private details in the public repository"; fi
else
  echo "!   $PRIV/tools/private-words.txt missing: private details not checked"; bad=1
fi
exit $bad
