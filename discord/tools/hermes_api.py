#!/usr/bin/env python3
"""Map a Hermes module's exported names to their bytecode function IDs.

Discord's JS bundle is Hermes bytecode, and the only way to patch a JS
function is by its numeric ID. This walks a module factory's disassembly,
pairs each ``CreateClosure`` with the ``PutByIdStrict`` that names it, and
prints the result.

Usage:
    hermes_api.py <bundle> <module-function-id> [name-filter]

Example:
    hermes_api.py index.android.bundle 1233 addBreadcrumb
"""

import os
import re
import subprocess
import sys


def _tool():
    """Return the absolute path of hermes-decomp, walking up from here."""
    here = os.path.dirname(os.path.abspath(__file__))
    for _ in range(4):
        candidate = os.path.join(here, "tools", "hermes-decomp")
        if os.path.exists(candidate):
            return candidate
        candidate = os.path.join(here, "hermes-decomp")
        if os.path.exists(candidate):
            return candidate
        here = os.path.dirname(here)
    raise SystemExit("hermes-decomp not found")


TOOL = _tool()


def disasm(bundle, function_id):
    """Return the HASM text for one function."""
    return subprocess.run(
        [TOOL, "disasm", "--function", str(function_id), bundle],
        capture_output=True,
        text=True,
        timeout=900,
        check=True,
    ).stdout


def exports(bundle, function_id):
    """Return {exported name: closure function id} for a module factory."""
    found, closure_reg = {}, {}
    for line in disasm(bundle, function_id).split("\n"):
        m = re.match(r"\s*CreateClosure(?:LongIndex)?\s+r(\d+),\s*r\d+,\s*(\d+)", line)
        if m:
            closure_reg[m.group(1)] = m.group(2)
            continue
        m = re.match(r'\s*PutByIdStrict\s+r\d+,\s*r(\d+),\s*\d+,\s*"([^"]+)"', line)
        if m and m.group(1) in closure_reg:
            found[m.group(2)] = closure_reg.pop(m.group(1))
    return found


def main():
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    bundle, function_id = sys.argv[1], sys.argv[2]
    wanted = sys.argv[3].lower() if len(sys.argv) > 3 else ""
    for name, fid in sorted(exports(bundle, function_id).items()):
        if wanted and wanted not in name.lower():
            continue
        print(f"{fid:>8}  {name}")


if __name__ == "__main__":
    sys.exit(main())
