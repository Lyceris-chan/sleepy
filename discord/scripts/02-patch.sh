#!/usr/bin/env bash
# Apply the full patch suite to a decoded Discord base.
#
# Usage: 02-patch.sh <version-dir>
#
# Both scripts are idempotent, so re-running after a failed marker is safe.
set -uo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
VDIR="$(cd "${1:?usage: 02-patch.sh <version-dir>}" && pwd)"
BASE="$VDIR/decompiled/base"
PATCHES="$(cd "$HERE/../patches" && pwd)"

[ -d "$BASE" ] || { echo "no decoded base at $BASE (run 01-decode.sh first)" >&2; exit 1; }

# Optional per-build selection written by tools/patches.py. Without it the
# manifest defaults apply (and discord/options.json, if present, is used).
if [ -f "$VDIR/options.json" ]; then
  export PATCH_OPTIONS="$VDIR/options.json"
  echo "[patch] options: $PATCH_OPTIONS"
elif [ -f "$HERE/../options.json" ]; then
  export PATCH_OPTIONS="$HERE/../options.json"
  echo "[patch] options: $PATCH_OPTIONS"
else
  echo "[patch] options: manifest defaults (run tools/patches.py list)"
fi

rc=0
CORE_LOG="$VDIR/patch-core.log"
BLOCK_LOG="$VDIR/patch-blocklist.log"

echo "### core.py"
python3 -I "$PATCHES/core.py" "$BASE" 2>&1 | tee "$CORE_LOG" || rc=1
echo
echo "### blocklist.py"
python3 -I "$PATCHES/blocklist.py" "$BASE" 2>&1 | tee "$BLOCK_LOG" || rc=1
echo

okn=$(cat "$CORE_LOG" "$BLOCK_LOG" 2>/dev/null | grep -c '\[OK\]')
skipn=$(cat "$CORE_LOG" "$BLOCK_LOG" 2>/dev/null | grep -c '\[SKIP\]')
failn=$(cat "$CORE_LOG" "$BLOCK_LOG" 2>/dev/null | grep -c '\[FAIL\]')
echo "[patch] summary: $okn applied, $skipn skipped, $failn failed"
echo "[patch] logs: $CORE_LOG, $BLOCK_LOG"

if [ "$rc" -ne 0 ] || [ "$failn" -gt 0 ]; then
  echo "[patch] FINISHED WITH FAILURES - review the [FAIL]/[SKIP] lines above"
  exit 1
fi
echo "[patch] all patch scripts applied cleanly"
exit 0
