#!/usr/bin/env python3
"""Re-map the Hermes function IDs in patches/core.py for a new Discord bundle.

Hermes function IDs are per-bundle, so every release shifts them and all three
target tables in `patches/core.py` stop matching. Names are stable though, so
this maps old -> new in two passes:

  1. **Name match.** If exactly one function in the new bundle carries the
     target's name, that is the answer. This resolves the majority.
  2. **Bytecode match.** Common names (`log`, `set`, `add`, `componentDidMount`)
     are ambiguous. For those, disassemble the old function and each candidate,
     then score by opcode histogram + referenced string literals. Both are
     stable across a version bump for an otherwise-unchanged function.

Ambiguous cases that no candidate explains are reported and left untouched —
they need a human, and silently guessing would patch the wrong function.

    tools/remap_hermes.py <old-bundle> <new-bundle> [--write] [--core PATH]
"""

import argparse
import collections
import difflib
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
HERMES = os.path.join(HERE, "hermes-decomp")
DEFAULT_CORE = os.path.join(HERE, "..", "patches", "core.py")
# Every `*TARGETS` dict in core.py. This used to be the first three only,
# which silently left FALSE_TARGETS / NULL_TARGETS / OBJECT_FALSE_TARGETS
# carrying ids from the previous release - they skipped on every bump and
# nobody noticed, because a skip is not an error.
TABLES_RE = r"([A-Z_]*TARGETS)"

PAIR_RE = re.compile(r'^\s*"(\d+)":\s*"([A-Za-z_][\w$]*)",')
FUNC_RE = re.compile(r"^\s*(\d+)\s+(\S+)\s+([0-9a-fA-F]{4,8})\s+(\d+)\s*$")
OP_RE = re.compile(r"^\s*(?:L\d+:\s*)?([A-Z][A-Za-z0-9]+)")
STR_RE = re.compile(r'LoadConstString(?:LongIndex)?\s+r\d+,\s*(.*)$')


def run(*args, **kw):
    return subprocess.run(
        [HERMES, *args], capture_output=True, text=True, timeout=1800, **kw
    ).stdout


def read_tables(path):
    tables, current = {}, None
    for line in open(path, encoding="utf-8"):
        m = re.match(rf"^\s*{TABLES_RE}\s*=\s*\{{", line)
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


def functions(bundle):
    """(by_name, offsets) from the bundle's function table."""
    by_name, offsets = collections.defaultdict(list), {}
    for line in run("dump", "--kind", "functions", bundle).split("\n"):
        m = FUNC_RE.match(line)
        if m:
            fid, name, off = m.group(1), m.group(2), m.group(3)
            by_name[name].append(fid)
            offsets[off] = offsets.get(off, 0) + 1
    return by_name, offsets


def signature(bundle, fid):
    """(opcode histogram, set of string literals) for one function."""
    ops, strs = collections.Counter(), set()
    for line in run("disasm", "--function", fid, bundle).split("\n"):
        o = OP_RE.match(line)
        if o:
            ops[o.group(1)] += 1
        s = STR_RE.match(line.strip())
        if s:
            strs.add(s.group(1).strip())
    return ops, strs


def similarity(a, b):
    """1.0 = identical opcode mix and identical strings."""
    oa, sa = a
    ob, sb = b
    if not oa and not ob:
        return 0.0
    opsim = sum((oa & ob).values()) / max(1, sum((oa | ob).values()))
    strs = (2 * len(sa & sb) / (len(sa) + len(sb))) if (sa or sb) else 1.0
    return 0.6 * opsim + 0.4 * strs


def ordered(bundle):
    """[(id, name)] in function-id order."""
    out = []
    for line in run("dump", "--kind", "functions", bundle).split("\n"):
        m = FUNC_RE.match(line)
        if m:
            out.append((int(m.group(1)), m.group(2)))
    return out


def build_anchors(old_seq, new_seq):
    """1:1 anchors between uniquely-named functions present in both bundles.

    Hermes numbers functions in bundle order, and Metro emits modules in a
    stable order, so two uniquely-named functions bracket every function
    between them. That lets an ambiguous name be resolved by *position*
    instead of by guessing from bytecode.
    """
    old_once, new_once = {}, {}
    for i, n in old_seq:
        old_once.setdefault(n, []).append(i)
    for i, n in new_seq:
        new_once.setdefault(n, []).append(i)
    anchors = [
        (old_once[n][0], new_once[n][0])
        for n in old_once.keys() & new_once.keys()
        if len(old_once[n]) == 1 and len(new_once[n]) == 1
    ]
    anchors.sort()
    return anchors


def bracket(anchors, old_id):
    """New-id range implied by the anchors around `old_id`."""
    lo = hi = None
    for o, n in anchors:
        if o < old_id:
            lo = n
        elif o > old_id and hi is None:
            hi = n
            break
    return lo, hi


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("old_bundle")
    ap.add_argument("new_bundle")
    ap.add_argument("--core", default=DEFAULT_CORE)
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--threshold", type=float, default=0.80)
    ap.add_argument("--emit-json", dest="emit_json",
                    help="write the resolved mapping as JSON and exit")
    args = ap.parse_args()

    tables = read_tables(args.core)
    total = sum(len(v) for v in tables.values())
    new_by_name, new_offsets = functions(args.new_bundle)

    old_seq = ordered(args.old_bundle)
    anchors = build_anchors(old_seq, ordered(args.new_bundle))
    print(f"unique-name anchors: {len(anchors)}")

    resolved, ambiguous, unmapped, shared = {}, [], [], []
    for table, pairs in tables.items():
        for old, name in pairs:
            cands = new_by_name.get(name, [])
            if len(cands) == 1:
                resolved[(table, old)] = cands[0]
                continue
            if not cands:
                # Anonymous helpers carry no name (`f73069`), so there is
                # nothing to look up. Place them by the id window the
                # surrounding anchors bracket, then confirm the guess against
                # the old body before trusting it.
                if re.fullmatch(r"f\d+", name):
                    lo, hi = bracket(anchors, int(old))
                    if lo is not None and hi is not None and hi > lo:
                        old_lo = max(o for o, _ in anchors if o < int(old))
                        guess = str(lo + (int(old) - old_lo))
                        if guess in {i for _, i in
                                     ((o, n) for o, n in anchors)}:
                            guess = str(int(guess) + 1)
                        if similarity(signature(args.old_bundle, old),
                                      signature(args.new_bundle, guess)) >= 0.9:
                            resolved[(table, old)] = guess
                            continue
                    unmapped.append(
                        (table, old, name,
                         f"anonymous; id-window {lo}..{hi} did not confirm"))
                    continue
                unmapped.append((table, old, name, "name absent from new bundle"))
                continue

            # Ambiguous name: keep only candidates inside the id window the
            # surrounding unique-name anchors imply, then fall back to
            # bytecode similarity to choose between those.
            lo, hi = bracket(anchors, int(old))
            window = [
                c for c in cands
                if (lo is None or int(c) > int(lo))
                and (hi is None or int(c) < int(hi))
            ]
            pool = window or cands
            if len(pool) == 1:
                resolved[(table, old)] = pool[0]
                continue

            old_sig = signature(args.old_bundle, old)
            scored = sorted(
                ((similarity(old_sig, signature(args.new_bundle, c)), c)
                 for c in pool),
                reverse=True,
            )
            best = scored[0]
            second = scored[1] if len(scored) > 1 else (0.0, None)
            if (
                best[0] >= args.threshold
                and best[0] - second[0] >= 0.02
            ):
                resolved[(table, old)] = best[1]
            else:
                ambiguous.append(
                    (table, old, name,
                     f"{len(cands)} candidates, {len(window)} in id-window "
                     f"{lo}..{hi}; best={best[0]:.3f} ({best[1]}) "
                     f"runner-up={second[0]:.3f} ({second[1]})")
                )

    if args.emit_json:
        json.dump({f"{t}\t{o}": n for (t, o), n in resolved.items()},
                  open(args.emit_json, "w"), indent=1)
        print(f"wrote {args.emit_json}")
        return 0

    print(f"targets    : {total}")
    print(f"  resolved : {len(resolved)}")
    print(f"  ambiguous: {len(ambiguous)}")
    print(f"  unmapped : {len(unmapped)}")
    for label, rows in (("AMBIGUOUS", ambiguous), ("UNMAPPED", unmapped)):
        if rows:
            print(f"\n{label}:")
            for r in rows:
                print("   " + "  ".join(str(x) for x in r))

    if not args.write:
        print("\n(dry run — pass --write to rewrite core.py)")
        return 0

    src = open(args.core, encoding="utf-8").read()

    def rewrite(match):
        table, body = match.group(1), match.group(0)
        for old, name in tables.get(table, []):
            new = resolved.get((table, old))
            if new and new != old:
                body = re.sub(
                    rf'(^\s*)"{re.escape(old)}":(\s*"{re.escape(name)}",)',
                    rf'\g<1>"{new}":\g<2>', body, flags=re.M,
                )
        return body

    # The tables are indented inside patch_js(), so the closing brace is too.
    out = re.sub(
        rf"^\s*{TABLES_RE}\s*=\s*\{{.*?^\s*\}}",
        rewrite, src, flags=re.M | re.S,
    )
    if out == src:
        print("no changes")
        return 0
    open(args.core + ".bak", "w", encoding="utf-8").write(src)
    open(args.core, "w", encoding="utf-8").write(out)
    print(f"\nwrote {args.core} (backup at core.py.bak)")
    if ambiguous or unmapped:
        print(f"WARNING: {len(ambiguous) + len(unmapped)} target(s) left "
              f"unresolved and untouched.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
