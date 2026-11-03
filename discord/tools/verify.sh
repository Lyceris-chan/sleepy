#!/usr/bin/env bash
# Verify the portable RE toolchain is complete and runnable.
set -uo pipefail
TOOLS_ROOT="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
cd "$TOOLS_ROOT"

fail=0
check() { # check <label> <command...>
  local label="$1"; shift
  if out=$("$@" 2>&1); then
    printf '  [ok]   %-14s %s\n' "$label" "$(echo "$out" | head -1)"
  else
    printf '  [FAIL] %-14s %s\n' "$label" "$(echo "$out" | head -1)"
    fail=1
  fi
}

echo "toolchain: $TOOLS_ROOT"
check java        ./jre17/bin/java -version
check apktool     ./bin/apktool --version
check jadx        ./bin/jadx --version
check procyon     ./bin/procyon --version
check vineflower  ./bin/vineflower --help
check cfr         ./bin/cfr --version
check dex2jar     ./bin/dex2jar --help
check signer      ./bin/uber-apk-signer --version
check ecj         ./bin/ecj -version
check hermes-dec  ./bin/hermes-decomp versions

echo
echo "components:"
for f in jadx apktool.jar procyon.jar vineflower.jar cfr.jar dex2jar \
         uber-apk-signer.jar ecj.jar hermes-decomp jre17; do
  [ -e "$f" ] && printf '  %-22s %s\n' "$f" "$(du -sh "$f" 2>/dev/null | cut -f1)" \
              || printf '  %-22s MISSING\n' "$f"
done

exit $fail
