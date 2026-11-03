#!/usr/bin/env bash
# Decode a Discord APK bundle (base.apk + config.*.apk splits) with apktool and
# merge the splits into the base so the rebuilt APK installs as one universal app.
#
# Usage: 01-decode.sh <version-dir>
#   <version-dir> must contain apk/extracted/{base.apk,config.*.apk}
#
# Fully relocatable: resolves the toolchain relative to this script.
set -euo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
TOOLS="$(cd "$HERE/../tools" && pwd)"
VDIR="$(cd "${1:?usage: 01-decode.sh <version-dir>}" && pwd)"
APK="$VDIR/apk/extracted"
DC="$VDIR/decompiled"
export JAVA_HOME="$TOOLS/jre17"
export PATH="$TOOLS/bin:$JAVA_HOME/bin:$PATH"

mkdir -p "$DC"

echo "[decode] base.apk (resources + smali)"
timeout 3600 apktool d -f -o "$DC/base" "$APK/base.apk" 2>&1 | tail -3

shopt -s nullglob
for f in "$APK"/config.*.apk; do
  name="$(basename "$f" .apk)"
  echo "[decode] $name (resources only)"
  timeout 900 apktool d -f -s -o "$DC/$name" "$f" 2>&1 | tail -1
done
shopt -u nullglob

echo "[decode] merging splits into base"
python3 "$TOOLS/merge_splits.py" "$DC/base" "$DC"

echo "[decode] result:"
grep -E "versionName|versionCode" "$DC/base/apktool.yml" | sed 's/^/  /'
echo "  libs: $(find "$DC/base/lib" -name '*.so' 2>/dev/null | wc -l)"
echo "  res:  $(find "$DC/base/res" -type f 2>/dev/null | wc -l)"
echo "[decode] DONE"
