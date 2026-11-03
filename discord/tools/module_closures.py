#!/usr/bin/env python3
"""List a Metro module's closures with a decompile preview, for 349.5 targeting.

The React Compiler build makes this necessary: a component that used to carry
its own name in the function table is now an anonymous arrow passed to
`memo()`, so `TARGETS` entries cannot be matched by name and the structural
walk can be off when the module's closure order changed. What still works is
reading the bodies.

    tools/module_closures.py <bundle> <module path> [--depth N] [--grep RE]
    tools/module_closures.py <bundle> --function <id> [--depth N]
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
HERMES = os.path.join(HERE, "hermes-decomp")
CLOSURE_RE = re.compile(r"CreateClosure(?:LongIndex)?\s+r\d+,\s*r\d+,\s*(\d+)")
JMP_TRUE_RE = re.compile(r"JmpTrue L(\d+),")
LABEL_RE = re.compile(r"^L(\d+):")
XREF_FN_RE = re.compile(r"^\s*Function (\d+)")


def run(*a, timeout=900):
    return subprocess.run([HERMES, *a], capture_output=True, text=True,
                          timeout=timeout).stdout


def groups_of(bundle, fid):
    lines = [l.strip() for l in run("disasm", "--function", str(fid), bundle).split("\n")]
    out, i = [], 0
    while i < len(lines):
        m = CLOSURE_RE.search(lines[i])
        if not m:
            i += 1
            continue
        first = int(m.group(1))
        jt = JMP_TRUE_RE.search(lines[i - 1]) if i else None
        if jt:
            want = jt.group(1)
            for k in range(i + 1, min(len(lines), i + 6)):
                lab = LABEL_RE.match(lines[k])
                if lab and lab.group(1) == want and k + 1 < len(lines):
                    m2 = CLOSURE_RE.search(lines[k + 1])
                    if m2:
                        out.append((first, int(m2.group(1))))
                        i = k + 2
                        break
            else:
                out.append((first,))
                i += 1
            continue
        out.append((first,))
        i += 1
    return out


def preview(bundle, fid, lines=3):
    with tempfile.TemporaryDirectory() as td:
        o = os.path.join(td, "f.hasm")
        run("emit-hasm", "--function", str(fid), "-o", o, bundle)
        if not os.path.exists(o):
            return -1, "<no hasm>"
        n = len(open(o).read().split("\n")) - 1
    d = run("decompile", "--function", str(fid), bundle).split("\n")[1:lines + 1]
    return n, " ".join(x.strip() for x in d)[:110]


def walk(bundle, fid, depth, path=""):
    for gi, g in enumerate(groups_of(bundle, fid)):
        for x in g:
            n, p = preview(bundle, x)
            tag = f"{path}{gi}" if not path else f"{path}.{gi}"
            print(f"  [{tag}] {x:<8} {n:>4} lines  {p}")
            if depth > 0:
                walk(bundle, x, depth - 1, tag + ">")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("bundle")
    ap.add_argument("path", nargs="?")
    ap.add_argument("--function")
    ap.add_argument("--depth", type=int, default=0)
    a = ap.parse_args()

    if a.function:
        print(f"function {a.function}:")
        walk(a.bundle, int(a.function), a.depth)
        return
    xr = run("xref", "--query", a.path, a.bundle)
    facs = [int(m.group(1)) for m in
            (XREF_FN_RE.match(l) for l in xr.split("\n")) if m]
    print(f"{a.path}\n  factories: {facs}")
    for f in facs:
        walk(a.bundle, f, a.depth)


if __name__ == "__main__":
    sys.exit(main())
