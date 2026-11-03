#!/usr/bin/env python3
"""Merge the name-based and structure-based remaps, then rewrite core.py.

Two tools can resolve a target's new Hermes function id:

  remap_hermes.py    -- by function name, then by id-window + bytecode
                        similarity for ambiguous names. Resolves the analytics
                        and logging methods, which keep their names.

  remap_by_module.py -- by Metro module path plus the chain of CreateClosure
                        indices down from the factory. Resolves the React
                        Compiler components, which have *no* name left to
                        match on but are still exactly where the module puts
                        them.

Neither alone is enough for 349.5, and they overlap. Where both answer they
usually agree; this reports every disagreement rather than silently picking
one, because a wrong id here does not fail - it stubs an unrelated function.

    tools/merge_remap.py --names /tmp/remap_name.json \\
        --struct /tmp/remap_struct.json [--core patches/core.py] [--write]
"""

import argparse
import collections
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CORE = os.path.join(HERE, "..", "patches", "core.py")
TABLE_RE = re.compile(r"^\s*([A-Z_]*TARGETS)\s*=\s*\{")
PAIR_RE = re.compile(r'^\s*"(\d+)":\s*"([A-Za-z_][\w$]*)",')


def read_tables(path):
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


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--names", required=True)
    ap.add_argument("--struct", required=True)
    ap.add_argument("--core", default=DEFAULT_CORE)
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args()

    # remap_hermes keys by "TABLE\t<old id>"; remap_by_module keys by
    # "<old id>\t<name>". Normalise both onto (table, old id).
    names = {}
    for k, v in json.load(open(args.names)).items():
        t, oid = k.split("\t")
        names[(t, oid)] = str(v)

    raw = json.load(open(args.struct))
    struct = {}
    # Both groups store a list of new ids; a React Compiler pair (two ids) is
    # the only difference between them. A pair can collapse to one id when
    # the compiled and uncompiled variants are byte-identical, so dedupe.
    for group in ("singles", "fans"):
        for k, v in raw[group].items():
            oid, name = k.split("\t")
            struct[(oid, name)] = sorted({str(x) for x in v})

    tables = read_tables(args.core)
    targets = [(t, fid, name) for t, pairs in tables.items()
               for fid, name in pairs]

    agree, conflict, only_s, only_n, missing = [], [], [], [], []
    for t, fid, name in targets:
        key = (fid, name)
        s, n = struct.get(key), names.get((t, fid))
        if s and n:
            if s == [n]:
                agree.append((key, s))
            else:
                conflict.append((t, fid, name, s, [n]))
        elif s:
            only_s.append((key, s))
        elif n:
            only_n.append((key, [n]))
        else:
            missing.append((t, fid, name))

    print(f"targets                 : {len(targets)}")
    print(f"  both agree            : {len(agree)}")
    print(f"  structure only        : {len(only_s)}")
    print(f"  name only             : {len(only_n)}")
    print(f"  CONFLICT              : {len(conflict)}")
    print(f"  unresolved            : {len(missing)}")

    if conflict:
        print("\nCONFLICTS (structure wins; review each):")
        for t, fid, name, s, n in conflict:
            print(f"  {t:20} {fid:>7} {name:<34} struct={s} name={n}")
    if missing:
        print("\nUNRESOLVED (left at their old ids; they will SKIP):")
        for t, fid, name in missing:
            print(f"  {t:20} {fid:>7}  {name}")

    final = {}
    for key, v in agree + only_s + only_n:
        final[key] = [str(x) for x in v]

    if not args.write:
        print("\n(dry run - pass --write to rewrite core.py)")
        return 0

    src = open(args.core, encoding="utf-8").read()
    new_rows, n_rewrites = [], 0

    def sub_table(m):
        nonlocal n_rewrites
        body = m.group(0)
        for (old, name), ids in final.items():
            if len(ids) == 1 and ids[0] != old:
                body, n = re.subn(
                    rf'(^\s*)"{re.escape(old)}":(\s*"{re.escape(name)}",)',
                    rf'\g<1>"{ids[0]}":\g<2>', body, flags=re.M)
                n_rewrites += n
        return body

    out = re.sub(rf"^\s*([A-Z_]*TARGETS)\s*=\s*\{{.*?^\s*\}}",
                 sub_table, src, flags=re.M | re.S)

    # Extra React Compiler variants become additional rows in the table they
    # came from. The patcher walks every value in the dict, so a second row
    # with the same name is one more stub - which is what we want.
    for (old, name), ids in sorted(final.items()):
        if len(ids) > 1:
            table = next((t for t, f, n in targets if f == old and n == name),
                         "TARGETS")
            new_rows.append((table, ids, name, old))

    if new_rows:
        for table, ids, name, old in new_rows:
            pat = rf"(\n(\s*){re.escape(table)}\s*=\s*\{{)"
            m = re.search(pat, out)
            if not m:
                continue
            indent = m.group(2) + "    "
            block = "".join(f'{indent}"{i}": "{name}",\n' for i in ids)
            out = out[:m.end()] + "\n" + block.rstrip("\n") + out[m.end():]

    open(args.core + ".bak", "w", encoding="utf-8").write(src)
    open(args.core, "w", encoding="utf-8").write(out)
    print(f"\nwrote {args.core}: {n_rewrites} id(s) rewritten, "
          f"{len(new_rows)} React Compiler pair(s) added")
    print(f"backup: {args.core}.bak")
    return 0


if __name__ == "__main__":
    sys.exit(main())
