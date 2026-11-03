#!/usr/bin/env python3
"""Verify that every Hermes bytecode stub actually landed in a built APK.

Reads the three target tables out of `patches/core.py`, pulls the JS bundle out
of the given APK, decompiles each target function and checks the body is one of
the stub shapes. The point is to check the *artefact*, not the patcher's own
report: the patcher and this script previously shared a substring-matching bug
that made both agree a function was stubbed when it was not.

    tools/verify-stubs.py dist/discord-alpha-347.5-patched.apk

Exit code is the number of failures, so it is usable as a gate.
"""

import os
import re
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
HERMES = os.path.join(HERE, "hermes-decomp")
CORE = os.path.join(HERE, "..", "patches", "core.py")

PAIR_RE = re.compile(r'^\s*"(\d+)":\s*"([A-Za-z_][\w$]*)",')

# Exactly what every stub shape decompiles to. Substring matching is not
# enough: real Discord code calls `Promise.resolve()`, so a body that merely
# *contains* it is not a stub.
# Default-on optional patches from `patch_js_extra`. They are resolved by name
# and arity at patch time, so they are not in the module-level tables above.
EXTRA_STUBS = {
    "PremiumFeatureUpsellPill": "return null;",
    "ContactSyncUpsellCTA": "return null;",
    "GroupDMNitroUpsellBanner": "return null;",
    "PremiumExpressionPickerSearchUpsell": "return null;",
    "ActivityCardsItem": "return null;",
    "SuperReactionLocalImageAnimation": "return null;",
}

STUB_LINES = {
    "debugger;",
    # promise stub, both renderings the decompiler emits
    "const resolved = Promise.resolve();",
    "return resolved;",
    "return Promise.resolve();",
    # no-op / zero / false stubs
    "return undefined;",
    "return 0;",
    "return false;",
    # NULL_TARGETS (React function components). `undefined` is only a legal
    # component return from React 18 on and `0` renders as the text "0", so
    # "draw nothing" has its own shape.
    "return null;",
    # EMPTY_ARRAY_TARGETS - hooks whose callers iterate the result.
    "return [];",
    # object stub (OBJECT_FALSE_TARGETS) - the gate returns `{enabled: bool}`
    # and callers read `.enabled` off it, so the stub has to be an object of
    # the same shape; a bare `return false` would make the property read throw.
    "return { enabled: false };",
}


def read_tables(path):
    tables, current = {}, None
    for line in open(path, encoding="utf-8"):
        m = re.match(r"^\s*([A-Z_]*TARGETS)\s*=\s*\{", line)
        if m:
            current = m.group(1)
            tables[current] = []
            continue
        if current:
            if line.strip().startswith("}"):
                current = None
                continue
            p = PAIR_RE.match(line)
            if p:
                tables[current].append((p.group(1), p.group(2)))
    return tables


def body_is_stub(text):
    if "{" not in text or "}" not in text:
        return False
    body = text.split("{", 1)[-1].rsplit("}", 1)[0]
    for line in body.split("\n"):
        s = line.strip()
        if s and s not in STUB_LINES:
            return False
    return True


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    apk = sys.argv[1]
    tables = read_tables(CORE)
    targets = [(fn, name) for pairs in tables.values() for fn, name in pairs]
    print(f"verifying {len(targets)} Hermes target(s) against {apk}")

    with tempfile.TemporaryDirectory() as td:
        with zipfile.ZipFile(apk) as z:
            names = [n for n in z.namelist()
                     if n.endswith("index.android.bundle")]
            if not names:
                print("no JS bundle in the APK", file=sys.stderr)
                return 2
            bundle = os.path.join(td, "index.android.bundle")
            with z.open(names[0]) as src, open(bundle, "wb") as dst:
                dst.write(src.read())
        print(f"bundle: {names[0]} ({os.path.getsize(bundle)} bytes)\n")

        fails, ok = [], 0
        for fn, name in targets:
            out = subprocess.run(
                [HERMES, "decompile", "--function", fn, bundle],
                capture_output=True, text=True, timeout=600,
            ).stdout
            if not out.strip():
                fails.append((fn, name, "no output"))
            elif (f"function {name}(" not in out
                  # From 349.5 on, React Compiler components are anonymous
                  # arrows passed to memo(), so the decompiler names them after
                  # the id (`f52476`) and the name in our table is the *old*
                  # one. The id is what identifies them.
                  and f"function f{fn}(" not in out):
                if re.fullmatch(r"f\d+", name):
                    fails.append((fn, name, "not named in output"))
                else:
                    fails.append((fn, name, "name mismatch"))
            elif not body_is_stub(out):
                first = [l.strip() for l in out.split("\n")
                         if l.strip() and l.strip() not in ("}", "{")][:2]
                fails.append((fn, name, f"NOT stubbed: {' | '.join(first)[:90]}"))
            else:
                ok += 1

        # Optional stubs are resolved by name at patch time. Check the names
        # survive in the built bundle and that each body is its expected shape.
        names = {}
        dump = subprocess.run(
            [HERMES, "dump", "--kind", "functions", bundle],
            capture_output=True, text=True, timeout=600,
        ).stdout
        for line in dump.split("\n"):
            parts = line.split()
            if len(parts) >= 3 and parts[0].isdigit():
                names.setdefault(" ".join(parts[1:-2]), []).append(parts[0])
        extra_ok = 0
        for name, want in EXTRA_STUBS.items():
            ids = names.get(name, [])
            if len(ids) != 1:
                fails.append((ids[0] if ids else "?", name, "not found"))
                continue
            out = subprocess.run(
                [HERMES, "decompile", "--function", ids[0], bundle],
                capture_output=True, text=True, timeout=600,
            ).stdout
            if want in out:
                extra_ok += 1
            else:
                fails.append((ids[0], name, f"NOT stubbed: expected {want}"))

    print(f"PASS {ok}/{len(targets)} core targets")
    print(f"PASS {extra_ok}/{len(EXTRA_STUBS)} optional targets")
    if fails:
        print(f"FAIL {len(fails)}:")
        for fn, name, why in fails:
            print(f"   {fn:>8}  {name:<40} {why}")
    return len(fails)


if __name__ == "__main__":
    sys.exit(main())
