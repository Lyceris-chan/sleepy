#!/usr/bin/env python3
"""Re-derive Hermes function IDs for a new bundle by *module structure*.

Why this exists next to `remap_hermes.py`
-----------------------------------------
`remap_hermes.py` maps old ids to new by function *name*, with an id-window
fallback anchored on uniquely-named functions. That worked while names were
stable and function order moved by a small delta.

349.5 breaks both assumptions. Discord shipped a React Compiler build: every
component is now emitted **twice** (a compiled and an uncompiled variant)
behind `isReactCompilerEnabled()`,

    memo(isReactCompilerEnabled() ? (() => {...}) : (() => {...}))

so the bundle gained ~27k functions (128,469 -> 155,429) while its string
table stayed the same size. The components are anonymous arrows passed to
`memo()`, so the bare identifier strings that used to name them
("NitroUpsellButton") are simply absent, and an id-window heuristic is
worthless when ids have shifted wholesale.

What is still stable is the **Metro module**: every factory calls
`fileFinishedImporting("<path>")` with a path that is unique in the bundle,
and creates its exported components as closures. So a target is addressed
structurally:

    (module path, [index of the child at each level down from the factory])

a chain rather than a single index, because most targets are not direct
children of the factory - they sit inside a class body or a helper that the
factory creates first. At each level the child list is the parent's
`CreateClosure` operands in bytecode order.

The React Compiler split is handled by fanout: when a level that used to
create N closures now creates 2N, each old child became an adjacent
(compiled, uncompiled) pair, so the walk descends into both. That is what a
"hide this component" stub wants anyway - the component must render nothing
whichever branch of `isReactCompilerEnabled()` is taken.

Usage
-----
    tools/remap_by_module.py extract <old-bundle> --out /tmp/remap_map.json
    tools/remap_by_module.py apply <new-bundle> --map /tmp/remap_map.json \\
        [--core patches/core.py] [--write]
"""

import argparse
import collections
import functools
import json
import os
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
HERMES = os.path.join(HERE, "hermes-decomp")
DEFAULT_CORE = os.path.join(HERE, "..", "patches", "core.py")

PAIR_RE = re.compile(r'^\s*"(\d+)":\s*"([A-Za-z_][\w$]*)",')
TABLE_RE = re.compile(r"^\s*([A-Z_]*TARGETS)\s*=\s*\{")
XREF_FN_RE = re.compile(r"^\s*Function (\d+)")
CLOSURE_RE = re.compile(r"CreateClosure(?:LongIndex)?\s+r\d+,\s*r\d+,\s*(\d+)")
STR_RE = re.compile(r'LoadConstString(?:LongIndex)?\s+r\d+,\s*"(.*)"\s*$')
MAX_DEPTH = 12


def run(*args, timeout=1800):
    return subprocess.run(
        [HERMES, *args], capture_output=True, text=True, timeout=timeout
    ).stdout


def read_tables(path):
    """Every `*TARGETS` dict in core.py -> {table: [(fn_id, name), ...]}."""
    tables, current = collections.OrderedDict(), None
    for line in open(path, encoding="utf-8"):
        m = TABLE_RE.match(line)
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


@functools.lru_cache(maxsize=None)
def parent_of(bundle, fid):
    """Function ids that create a closure for `fid`, in id order."""
    out = run("xref", "--kind", "function", "--query", str(fid), bundle)
    return tuple(sorted(
        int(m.group(1)) for m in
        (XREF_FN_RE.match(l) for l in out.split("\n")) if m
    ))


@functools.lru_cache(maxsize=None)
def closures_of(bundle, fid):
    """The CreateClosure targets of a function, in bytecode order."""
    out = run("disasm", "--function", str(fid), bundle)
    return tuple(
        int(m.group(1)) for m in
        (CLOSURE_RE.search(l) for l in out.split("\n")) if m
    )


JMP_TRUE_RE = re.compile(r"JmpTrue L(\d+),")
LABEL_RE = re.compile(r"^L(\d+):")


@functools.lru_cache(maxsize=None)
def groups_of(bundle, fid):
    """CreateClosure targets grouped by React Compiler variant.

    A component compiled by React Compiler is emitted as a *pair* selected at
    runtime, and the selection is right there in the bytecode:

        GetByIdShort r1, r6, 13, "isReactCompilerEnabled"
        Call1       r1, r1, r6
        JmpTrue     L416, r1
        CreateClosureLongIndex r1, r5, 71380    <- enabled() == false
        Jmp         L423
      L416:
        CreateClosureLongIndex r1, r5, 71379    <- enabled() == true
      L423:

    so the two ids are one logical closure, not two. On a pre-React-Compiler
    bundle the same grouping yields one id per group, which is why a chain
    recorded against 348.2 keeps its index meaning here.
    """
    lines = [l.strip() for l in
             run("disasm", "--function", str(fid), bundle).split("\n")]
    groups, i = [], 0
    while i < len(lines):
        m = CLOSURE_RE.search(lines[i])
        if not m:
            i += 1
            continue
        first = int(m.group(1))
        jt = JMP_TRUE_RE.search(lines[i - 1]) if i else None
        if jt:
            # Find the label the JmpTrue targets; the closure right after it
            # is the other half of the pair.
            want = jt.group(1)
            for k in range(i + 1, min(len(lines), i + 6)):
                lab = LABEL_RE.match(lines[k])
                if lab and lab.group(1) == want and k + 1 < len(lines):
                    m2 = CLOSURE_RE.search(lines[k + 1])
                    if m2:
                        groups.append([first, int(m2.group(1))])
                        i = k + 2
                        break
            else:
                groups.append([first])
                i += 1
            continue
        groups.append([first])
        i += 1
    return tuple(tuple(g) for g in groups)


@functools.lru_cache(maxsize=None)
def module_path_of(bundle, factory):
    """The `fileFinishedImporting("<path>")` argument, or None.

    The path is the LoadConstString immediately preceding the
    fileFinishedImporting call in the factory's body.
    """
    lines = run("disasm", "--function", str(factory), bundle).split("\n")
    for i, line in enumerate(lines):
        if "fileFinishedImporting" in line:
            # The path is loaded into a register *after* the property lookup
            # on `fileFinishedImporting` and before the Call, so look forward
            # first; fall back to backwards for any other emission order.
            order = list(range(i + 1, min(len(lines), i + 6))) + \
                    list(range(i - 1, max(-1, i - 6), -1))
            for j in order:
                m = STR_RE.search(lines[j].strip())
                if m:
                    return m.group(1)
    return None


def address_of(bundle, fid):
    """(module path, chain) or (None, reason).

    `chain` is ordered factory-first: each entry is
    [index of the child in its parent's closure list, that parent's fanout].
    """
    chain, node = [], int(fid)
    for _ in range(MAX_DEPTH):
        parents = parent_of(bundle, node)
        if len(parents) != 1:
            return None, f"{len(parents)} parent(s)"
        parent = parents[0]
        # groups_of, not closures_of: an index must mean the same thing on
        # both sides of the walk, and on the old bundle every group is one id.
        sibs = [g[0] for g in groups_of(bundle, parent)]
        if node not in sibs:
            return None, "not in parent's closure list"
        chain.append([sibs.index(node), len(sibs)])
        path = module_path_of(bundle, parent)
        if path is not None:
            return (path, list(reversed(chain))), None
        node = parent
    return None, f"no module factory within {MAX_DEPTH} levels"


def descend(bundle, node, chain, fanout):
    """Follow a chain down from `node`, returning the ids it names.

    A level whose fanout doubled under the React Compiler is descended in
    both halves; every other level must match exactly.
    """
    if not chain:
        return [node]
    idx, fan = chain[0]
    groups = groups_of(bundle, node)
    if len(groups) != fan:
        raise ValueError(f"{fan} closure(s) -> {len(groups)} group(s) at {idx}")
    # An old index addresses the *logical* closure, which may now be two ids
    # (the React Compiler pair). Descend into whichever it turns out to be.
    out = []
    for k in groups[idx]:
        out.extend(descend(bundle, k, chain[1:], fanout))
    return out


def cmd_extract(a):
    tables = read_tables(a.core)
    targets = [(fid, name) for pairs in tables.values() for fid, name in pairs]
    print(f"describing {len(targets)} target(s) against {a.old_bundle}")

    with ThreadPoolExecutor(max_workers=a.jobs) as ex:
        rows = list(ex.map(lambda t: address_of(a.old_bundle, t[0]), targets))

    record, bad = [], []
    for (fid, name), (addr, err) in zip(targets, rows):
        if err:
            bad.append((fid, name, err))
            continue
        path, chain = addr
        record.append({"fn": fid, "name": name, "path": path, "chain": chain})

    json.dump(record, open(a.out, "w"), indent=1)
    print(f"  recorded {len(record)}/{len(targets)} -> {a.out}")
    if bad:
        print(f"  UNRESOLVED {len(bad)}:")
        for fid, name, err in bad:
            print(f"    {fid:>7}  {name:<36} {err}")
    return 0


def cmd_apply(a):
    record = json.load(open(a.map))
    print(f"re-deriving {len(record)} target(s) against {a.new_bundle}")

    by_path = collections.defaultdict(list)
    for r in record:
        by_path[r["path"]].append(r)
    print(f"  across {len(by_path)} module(s)")

    def work(item):
        path, rows = item
        out = run("xref", "--query", path, a.new_bundle)
        facs = [int(m.group(1)) for m in
                (XREF_FN_RE.match(l) for l in out.split("\n")) if m]
        if len(facs) != 1:
            return path, rows, None, f"{len(facs)} factories for path"
        return path, rows, facs[0], None

    with ThreadPoolExecutor(max_workers=a.jobs) as ex:
        results = list(ex.map(work, by_path.items()))

    mapping, problems = {}, []
    for path, rows, fac, err in results:
        if err:
            problems += [(r["fn"], r["name"], path, err) for r in rows]
            continue
        for r in rows:
            try:
                got = descend(a.new_bundle, fac, r["chain"], a.fanout)
            except ValueError as e:
                problems.append((r["fn"], r["name"], path, str(e)))
                continue
            mapping[(r["fn"], r["name"])] = got

    singles = {k: v for k, v in mapping.items() if len(v) == 1}
    fans = {k: v for k, v in mapping.items() if len(v) > 1}
    print(f"\n  resolved 1:1       : {len(singles)}")
    print(f"  resolved 1:N (RC)  : {len(fans)}  "
          f"({sum(len(v) for v in fans.values())} ids total)")
    print(f"  unresolved         : {len(problems)}")
    for fn, name, path, why in problems:
        print(f"    {fn:>7}  {name:<36} {why}")

    dump = {"singles": {f"{k[0]}\t{k[1]}": v for k, v in singles.items()},
            "fans": {f"{k[0]}\t{k[1]}": v for k, v in fans.items()}}
    json.dump(dump, open(a.out, "w"), indent=1)
    print(f"\n  mapping -> {a.out}")

    if not a.write:
        print("(dry run — pass --write to rewrite core.py)")
        return 0

    src = open(a.core, encoding="utf-8").read()
    newpairs = []

    def sub_table(m):
        table, body = m.group(1), m.group(0)
        for (old, name), new in mapping.items():
            if len(new) == 1:
                if str(new[0]) == old:
                    continue
                body, n = re.subn(
                    rf'(^\s*)"{re.escape(old)}":(\s*"{re.escape(name)}",)',
                    rf'\g<1>"{new[0]}":\g<2>', body, flags=re.M)
            # 1:N rows are collected and appended after the loop below, so
            # the tables keep working if the caller re-runs this.
        return body

    out = re.sub(r"^\s*([A-Z_]*TARGETS)\s*=\s*\{.*?^\s*\}",
                 sub_table, src, flags=re.M | re.S)
    open(a.core + ".bak", "w", encoding="utf-8").write(src)
    open(a.core, "w", encoding="utf-8").write(out)
    print(f"wrote {a.core} (1:1 ids rewritten; backup core.py.bak)")

    if fans:
        print(f"\n{len(fans)} target(s) split into TWO React Compiler variants.")
        print("Add these as extra rows, then re-run — the table keys must be unique:")
        for (old, name), new in fans.items():
            print(f'        "{new[0]}": "{name}",   # was {old}')
            print(f'        "{new[1]}": "{name}",   # RC pair of the above')
    return 0


def main():
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--core", default=DEFAULT_CORE)
    common.add_argument("--jobs", type=int, default=8)

    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter,
        parents=[common])
    sub = ap.add_subparsers(dest="cmd", required=True)

    e = sub.add_parser("extract", parents=[common])
    e.add_argument("old_bundle")
    e.add_argument("--out", required=True)
    e.set_defaults(func=cmd_extract)

    a = sub.add_parser("apply", parents=[common])
    a.add_argument("new_bundle")
    a.add_argument("--map", required=True)
    a.add_argument("--out", required=True)
    a.add_argument("--no-fanout", dest="fanout", action="store_false")
    a.set_defaults(func=cmd_apply, fanout=True)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
