#!/usr/bin/env bash
# Assemble one .smali file and print the real assembler diagnostics.
#
# apktool only ever says "Could not smali file: <path>". Its bundled smali
# assembler prints a line and column when driven directly, which is the
# difference between a five-minute fix and an afternoon of bisecting — a
# method descriptor missing its trailing `V`, for example, reports as a parse
# error on some unrelated later line.
#
# Usage:  tools/smali-check.sh <file.smali>
#         tools/smali-check.sh <decompiled-base-dir> <relative/path.smali>
#
# Exit code 0 = assembles, 1 = does not.
set -uo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
WORK="$(cd "$HERE/.." && pwd)"
export JAVA_HOME="$HERE/jre17"
JAVA="$JAVA_HOME/bin/java"
BUILD="$HERE/smali-check/build"

SRC="$HERE/smali-check/TrySmali.java"
CLASS="$BUILD/TrySmali.class"

# Compile the harness once, with the bundled Eclipse compiler (there is no
# javac in the JRE).
if [ ! -f "$CLASS" ] || [ "$SRC" -nt "$CLASS" ]; then
    mkdir -p "$BUILD"
    "$JAVA" -cp "$HERE/ecj.jar" \
        org.eclipse.jdt.internal.compiler.batch.Main \
        -source 17 -target 17 -cp "$HERE/apktool.jar" \
        -d "$BUILD" "$SRC" >/dev/null 2>&1 || {
        echo "[smali-check] could not compile the harness" >&2
        exit 2
    }
fi

# Accept either a direct path, or <base-dir> <relative.smali>.
if [ "$#" -eq 2 ]; then
    FILE=""
    for d in smali smali_classes2 smali_classes3 smali_classes4; do
        if [ -f "$1/$d/$2" ]; then FILE="$1/$d/$2"; break; fi
    done
    [ -n "$FILE" ] || { echo "[smali-check] not found under any smali dir: $2" >&2; exit 2; }
else
    FILE="${1:?usage: smali-check.sh <file.smali> | <base-dir> <rel.smali>}"
fi

"$JAVA" -cp "$HERE/apktool.jar:$BUILD" TrySmali "$FILE"
