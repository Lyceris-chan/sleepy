#!/usr/bin/env bash
# Post a build-status update to the project Discord webhook.
#
# Usage:
#   scripts/notify.sh "message text"
#   scripts/notify.sh -f notes.md          # post a file's contents
#   echo "text" | scripts/notify.sh -
#
# The webhook URL is a credential. It is read from, in order:
#   1. $DISCORD_WEBHOOK
#   2. <repo>/discord/.webhook   (gitignored)
# It is never echoed and never committed.
set -euo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
HOOK="${DISCORD_WEBHOOK:-}"
if [ -z "$HOOK" ] && [ -f "$ROOT/.webhook" ]; then
    HOOK="$(tr -d '[:space:]' < "$ROOT/.webhook")"
fi
[ -n "$HOOK" ] || { echo "no webhook configured (\$DISCORD_WEBHOOK or discord/.webhook)" >&2; exit 1; }

case "${1:-}" in
    -f) [ -n "${2:-}" ] || { echo "usage: notify.sh -f <file>" >&2; exit 2; }
        BODY="$(cat "$2")" ;;
    -)  BODY="$(cat)" ;;
    "") echo "usage: notify.sh <message> | -f <file> | -" >&2; exit 2 ;;
    *)  BODY="$*" ;;
esac
[ -n "$BODY" ] || { echo "refusing to post an empty message" >&2; exit 2; }

# Discord caps content at 2000 chars; split on paragraph boundaries.
python3 - "$HOOK" <<'PY' "$BODY"
import json, sys, urllib.request

hook, body = sys.argv[1], sys.argv[2]
LIMIT = 1900
chunks, cur = [], ""
for para in body.split("\n\n"):
    if len(cur) + len(para) + 2 > LIMIT and cur:
        chunks.append(cur); cur = para
    else:
        cur = f"{cur}\n\n{para}" if cur else para
if cur:
    chunks.append(cur)

for i, c in enumerate(chunks, 1):
    if len(chunks) > 1:
        c = f"{c}\n\n_({i}/{len(chunks)})_"
    req = urllib.request.Request(
        hook,
        data=json.dumps({"content": c[:2000]}).encode(),
        headers={"Content-Type": "application/json",
                 "User-Agent": "discord-patch-pipeline/1.0"},
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        print(f"[notify] part {i}/{len(chunks)} -> HTTP {r.status}")
PY
