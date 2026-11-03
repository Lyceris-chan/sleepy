#!/usr/bin/env bash
# Rebuild a patched Discord APK from a decoded base and sign it.
#
# Usage: 03-build.sh <version-dir> [output-name]
set -euo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
TOOLS="$(cd "$HERE/../tools" && pwd)"
WORK="$(cd "$HERE/.." && pwd)"
VDIR="$(cd "${1:?usage: 03-build.sh <version-dir> [output-name]}" && pwd)"
# Derive the output name from the tree's own manifest. It used to default to a
# hardcoded "discord-346.2-alpha-patched", so a 348.5 build with no explicit
# name silently produced a file labelled 346.2 - and a mislabelled artifact is
# how a previous round of on-device verification ended up being run against the
# wrong APK. Read it, never guess it.
if [ -n "${2:-}" ]; then
  NAME="$2"
else
  VNAME="$(sed -n 's/^[[:space:]]*versionName:[[:space:]]*//p' "$VDIR/decompiled/base/apktool.yml" | head -1)"
  VNUM="${VNAME%% *}"          # "348.5 - Alpha" -> "348.5"
  case "$VNAME" in
    *Alpha*) NAME="discord-alpha-$VNUM-patched" ;;
    *)       NAME="discord-$VNUM-patched" ;;
  esac
  [ -n "$VNUM" ] || { echo "[build] cannot read versionName" >&2; exit 1; }
fi
BASE="$VDIR/decompiled/base"
OUT="$VDIR/out"
KS="$WORK/build/signing.keystore"
export JAVA_HOME="$TOOLS/jre17"
export PATH="$TOOLS/bin:$JAVA_HOME/bin:$PATH"

mkdir -p "$OUT"

# hermes-decomp leaves a multi-hundred-MB analysis cache next to the bundle.
# apktool will happily pack it into the APK, silently turning a 128 MB build
# into a 178 MB one. The patch suite cleans it up, but any ad-hoc
# `hermes-decomp` run after that recreates it, so guard here too.
find "$BASE/assets" -maxdepth 1 -name '*.hdcache' -delete 2>/dev/null || true

# apktool uses `$BASE/build/apk` as its staging tree and packs from it, but it
# does not clean it first. So any file a patch DELETED from `res/` comes back
# on the next build, from the previous build's copy - the APK keeps shipping
# it and the patch silently does nothing. Found the hard way: stripping 1683
# intl catalogs from res/raw changed the APK by 0.48 MB instead of 7.3 MB,
# because all 1741 were still sitting in build/apk/res/raw. Always start from
# an empty staging tree.
if [ -d "$BASE/build" ]; then
  echo "[build] clearing stale apktool staging tree ($BASE/build)"
  rm -rf "$BASE/build"
fi

echo "[build] apktool b $BASE"
timeout 3600 apktool b -o "$OUT/$NAME-unsigned.apk" "$BASE" 2>&1 | tail -8
ls -la "$OUT/$NAME-unsigned.apk"

if [ ! -f "$KS" ]; then
  echo "[build] generating keystore"
  "$JAVA_HOME/bin/keytool" -genkeypair -v -keystore "$KS" -alias discord \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass discordpatch -keypass discordpatch \
    -dname "CN=Discord Patched, OU=Privacy Patch, O=Self, L=Self, ST=Self, C=US" >/dev/null
fi

echo "[build] zipalign + sign"
uber-apk-signer --apks "$OUT/$NAME-unsigned.apk" \
  --ks "$KS" --ksAlias discord --ksKeyPass discordpatch --ksPass discordpatch \
  --out "$OUT" 2>&1 | tail -12

echo "[build] OUTPUT"
ls -la "$OUT"/*.apk
sha256sum "$OUT"/*signed.apk 2>/dev/null || true
