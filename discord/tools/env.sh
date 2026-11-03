#!/usr/bin/env bash
# Portable reverse-engineering toolchain environment.
#
# Everything lives under this directory (jre17, jadx, apktool.jar, procyon.jar,
# vineflower.jar, cfr.jar, dex2jar, uber-apk-signer.jar) so the whole `discord/`
# tree can be moved to another machine or another path and keep working.
#
#   source tools/env.sh          # puts tools/bin + jre17/bin on PATH
#   tools/bin/jadx --version     # or call the wrappers directly
#
# No system packages are required: JAVA_HOME points at the bundled JRE 17.

if [ -n "${BASH_SOURCE[0]:-}" ]; then
  _env_self="${BASH_SOURCE[0]}"
else
  _env_self="$0"
fi
TOOLS_ROOT="$(cd "$(dirname "$_env_self")" && pwd)"
export TOOLS_ROOT
export JAVA_HOME="$TOOLS_ROOT/jre17"
export PATH="$TOOLS_ROOT/bin:$JAVA_HOME/bin:$PATH"
unset _env_self

# Repo root = the `discord/` directory that contains work/
export DISCORD_ROOT="$(cd "$TOOLS_ROOT/.." && pwd)"
