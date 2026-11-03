#!/usr/bin/env python3
"""Regenerate the settings-row `usePredicate` body edits for a new bundle.

Each of these edits adds one property to a `createRoute(...)` config object so
`SettingHookHarness` hides the row (`false === cfg.usePredicate()`), by
inserting two instructions immediately before the call:

    CreateClosure r<scratch>, r<env>, <predicate>   ; the `return false` stub
    DefineOwnById  r<obj>, r<scratch>, <slot>, "usePredicate"

They belong to the family of edits that CANNOT be made byte-neutral - two
instructions is two instructions - which is why HBC_BODY_EDIT_HAZARD.md says to
re-derive rather than copy. What makes these particular ones safe is that every
one of the affected factories is straight-line: no `Jmp`, no labels, nothing
for a shifted offset to land in the middle of. `--check` asserts that.

Everything the insertion needs is read out of the *new* body, not carried over:

  obj      the register the createRoute call assigns back into
  env      from `CreateFunctionEnvironment r<E>, N`
  slot     1 + the highest slot already used on the config object
  scratch  the highest register that is not read after the insertion point
           and is not one of the live operands at the call

The one thing that cannot be read out of the body is the predicate's own id,
so it is passed in (`--predicate`) and must be the CURRENT id of the stub that
returns false.

    tools/regen_setting_predicate.py --old <348 bundle> --check \\
        --factory 14522 --predicate 62294 --stem manage_plan
    tools/regen_setting_predicate.py --new <349 bundle> \\
        --factory 14790 --predicate 68593 --stem manage_plan --write
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
HERMES = os.path.join(HERE, "hermes-decomp")
HASM = os.path.join(HERE, "..", "patches", "hasm")

INSN_RE = re.compile(r"^([0-9a-f]+)\s+(.*)$")
ENV_RE = re.compile(r"CreateFunctionEnvironment r(\d+),")
PUT_RE = re.compile(r"^PutOwnBySlotIdx r(\d+),")
CALL_RE = re.compile(r"^Call2 r(\d+), r(\d+), r(\d+), r(\d+)$")
SLOT_RE = re.compile(r"^PutOwnBySlotIdx r(\d+), r\d+, (\d+)$")
JUMP_RE = re.compile(r"\bJmp\w*\s+L([0-9a-f]+)")
LABEL_RE = re.compile(r"^L([0-9a-f]+):")


def shiftable(lines, anchor_off):
    """True when inserting at `anchor_off` cannot invalidate a jump.

    HBC_BODY_EDIT_HAZARD.md: a `L<n>` operand is a byte offset, so inserting
    bytes at position P makes every `L<n>` with n >= P name a position that
    moved. A body is therefore only unsafe if some jump targets an offset at or
    after the insertion point - jumps *before* it keep their offsets, because
    insertion shifts bytes after P only.

    This matters now: 349.5's React Compiler wraps component closures in
    `isReactCompilerEnabled() ? ... : ...`, so bodies that were previously
    straight-line contain real jumps. Refusing every body with a jump would
    reject edits that are provably safe.
    """
    bad = []
    for l in lines:
        m = INSN_RE.match(l)
        body = m.group(2) if m else l.strip()
        # `L<n>` is DECIMAL (see HBC_BODY_EDIT_HAZARD.md); the leading offset
        # column is hex. Reading these as hex makes every label look like it
        # is far past the anchor and refuses safe edits - which is exactly
        # what happened first time round.
        j = JUMP_RE.search(body)
        if j and int(j.group(1)) >= anchor_off:
            bad.append((l.strip(), int(j.group(1))))
        lab = LABEL_RE.match(body)
        if lab and int(lab.group(1)) >= anchor_off:
            bad.append((l.strip(), int(lab.group(1))))
    return bad


def emit(bundle, fid):
    "Disassemble one function to HASM and return it as a list of lines."
    with tempfile.TemporaryDirectory() as td:
        out = os.path.join(td, "f.hasm")
        r = subprocess.run(
            [HERMES, "emit-hasm", "--function", str(fid), "-o", out, bundle],
            capture_output=True, text=True, timeout=900,
        )
        if not os.path.exists(out):
            raise SystemExit(
                f"emit-hasm failed for {fid}: {(r.stderr or '').strip()[:200]}"
            )
        text = open(out).read()
    if not text.strip():
        raise SystemExit(f"emit-hasm produced nothing for {fid}")
    return text.rstrip("\n").split("\n")


def analyse(lines):
    """(anchor index, obj, fn, recv, env) for the createRoute call."""
    env = None
    for l in lines:
        m = ENV_RE.search(l)
        if m:
            env = m.group(1)
            break
    for i, l in enumerate(lines):
        m = INSN_RE.match(l)
        if not m:
            continue
        body = m.group(2)
        if not PUT_RE.match(body):
            continue
        obj = PUT_RE.match(body).group(1)
        nxt = INSN_RE.match(lines[i + 1]) if i + 1 < len(lines) else None
        if not nxt:
            continue
        c = CALL_RE.match(nxt.group(2))
        # The route call assigns the config object back into itself and is
        # immediately followed by the dependency lookup that the module needs.
        if c and c.group(1) == obj and c.group(4) == obj:
            after = INSN_RE.match(lines[i + 2]) if i + 2 < len(lines) else None
            if after and after.group(2).startswith("GetByIndex"):
                return i + 1, obj, c.group(2), c.group(3), env
    raise SystemExit("createRoute anchor not found")


def used_slots(lines, obj):
    """Slot numbers already taken on the *config object*.

    Only the config object's slots matter. A `createRoute` config holds nested
    objects of its own (the title, the icon), and those have their own slot
    numbering starting at 0 - counting them would push the new property to an
    index the object never sized for.

    The slot is the *second* operand; the first is the object register.
    """
    out = []
    for l in lines:
        m = INSN_RE.match(l)
        if not m:
            continue
        s = SLOT_RE.match(m.group(2))
        if s and s.group(1) == obj:
            out.append(int(s.group(2)))
    return out


def scratch_reg(lines, anchor, taken):
    """Highest register not read after the insertion point and not live there.

    Not a liveness analysis - it is the rule the original generator used, and
    it is only trusted here because these bodies are straight-line and every
    candidate it picks in practice is a register whose value was already
    consumed by a PutOwnBySlotIdx a few instructions earlier.
    """
    after = " ".join(INSN_RE.match(l).group(2) for l in lines[anchor + 1:]
                     if INSN_RE.match(l))
    seen = set(int(x) for x in re.findall(r"r(\d+)", after))
    for r in range(15, 0, -1):
        if str(r) in taken:
            continue
        if r in seen:
            continue
        if re.search(rf"r{r}\b", " ".join(INSN_RE.match(l).group(2)
                                          for l in lines[:anchor]
                                          if INSN_RE.match(l))):
            return str(r)
    raise SystemExit("no dead scratch register found")


def build(lines, obj, env, slot, scratch, predicate):
    """Insert the two instructions and renumber the offsets.

    The leading offset column is a *rendering*, not an input: the assembler
    encodes each instruction and tracks its own positions, which is how it
    resolves the `L<n>` operands. The proof is in the stored after-images -
    they number the SAME `CreateClosure` as 5 bytes in one body and 4 in the
    next, so nothing downstream can be reading them for width. They are kept
    monotonic here only so a human reading the file sees plausible addresses.

    Bare `L<n>:` label lines are carried through untouched. Their names are
    decimal byte offsets, and `shiftable()` has already refused any body where
    a label or a jump target sits at or after the insertion point - so every
    label that survives is positioned before the insert and does not move.
    """
    anchor, *_ = analyse(lines)
    # `CreateClosure` carries the function index in 16 bits. That was fine
    # while the ids were in the 60-thousands; 349.5 has 155k functions and the
    # predicate sits at 68593, which the assembler silently wrapped to 3057
    # (68593 - 65536). The long form takes the full width - and the body edit
    # is an insertion either way, so the extra two bytes cost nothing that
    # `shiftable()` has not already accounted for.
    mnemonic = "CreateClosure" if int(predicate) <= 0xFFFF else "CreateClosureLongIndex"
    ins = [
        f"{mnemonic} r{scratch}, r{env}, {predicate}",
        f'DefineOwnById r{obj}, r{scratch}, {slot}, "usePredicate"',
    ]
    pre = lines[:anchor]
    # Offset of the last real instruction before the insert, for the two new
    # rows' cosmetic offset column.
    base = 0
    for l in pre:
        m = INSN_RE.match(l)
        if m:
            base = int(m.group(1), 16)
    out = list(pre)
    for delta, body in ((0, ins[0]), (4, ins[1])):
        out.append(f"{base + delta:04x}  {body}")
    for l in lines[anchor:]:
        m = INSN_RE.match(l)
        if m:
            out.append(f"{int(m.group(1), 16) + 8:04x}  {m.group(2)}")
        else:
            out.append(l)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--factory", required=True)
    ap.add_argument("--predicate", required=True)
    ap.add_argument("--stem", required=True)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--old")
    g.add_argument("--new")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--write", action="store_true")
    a = ap.parse_args()

    bundle = a.old or a.new
    lines = emit(bundle, a.factory)

    anchor, obj, fn, recv, env = analyse(lines)
    anchor_off = int(INSN_RE.match(lines[anchor]).group(1), 16)  # hex column
    bad = shiftable(lines, anchor_off)
    if bad:
        print(f"{a.stem}: {len(bad)} jump target(s) at or after the insertion "
              f"point (0x{anchor_off:x}) would be invalidated:")
        for t, off in bad[:8]:
            print(f"    {t}  (0x{off:x})")
        raise SystemExit("refusing to insert")
    slots = used_slots(lines, obj)
    slot = 1 + max(slots)
    sc = scratch_reg(lines, anchor, {obj, fn, recv, env})
    after = build(lines, obj, env, slot, sc, a.predicate)

    print(f"{a.stem}: factory {a.factory}")
    print(f"  anchor={anchor} obj=r{obj} fn=r{fn} recv=r{recv} env=r{env}")
    print(f"  slots used={sorted(slots)} -> insert slot {slot}")
    print(f"  scratch=r{sc}  predicate={a.predicate}")

    if a.check:
        want = open(os.path.join(HASM, f"{a.stem}.after.hasm")).read().rstrip("\n").split("\n")
        got_ins = [l.split("  ", 1)[1] for l in after]
        want_ins = [l.split("  ", 1)[1] for l in want]

        # The scratch register is not part of what the edit means: any register
        # that is dead at the insertion point gives an identical program. The
        # stored images were made by a generator that picked a different one
        # (it did not check liveness at all - see the note on `scratch_reg`),
        # so compare everything except that register.
        def canon(seq):
            """Blank the scratch register on the two INSERTED lines only.

            Only those two carry it as a choice. The same register number may
            appear elsewhere in the body meaning something unrelated, so
            rewriting the whole sequence would compare the wrong thing.
            """
            out, i = list(seq), 0
            while i < len(out):
                m = re.match(rf"CreateClosure r(\d+), r\d+, {a.predicate}$", out[i])
                if m and i + 1 < len(out) and out[i + 1].startswith("DefineOwnById"):
                    out[i] = re.sub(rf"\br{m.group(1)}\b", "rS", out[i])
                    out[i + 1] = re.sub(rf"\br{m.group(1)}\b", "rS", out[i + 1])
                i += 1
            return out

        if len(got_ins) != len(want_ins):
            print(f"  CHECK: length differs: got {len(got_ins)} want {len(want_ins)}")
            return 1
        gc, wc = canon(got_ins), canon(want_ins)
        bad = [i for i, (x, y) in enumerate(zip(gc, wc)) if x != y]
        if bad:
            print("  CHECK: MISMATCH (beyond the scratch register)")
            for i in bad[:6]:
                print(f"    got  {got_ins[i]}")
                print(f"    want {want_ins[i]}")
            return 1
        print("  CHECK: regenerated stream is identical up to the scratch "
              "register, which is dead at the insertion point")
        return 0

    if a.write:
        open(os.path.join(HASM, f"{a.stem}.before.hasm"), "w").write(
            "\n".join(lines) + "\n")
        open(os.path.join(HASM, f"{a.stem}.after.hasm"), "w").write(
            "\n".join(after) + "\n")
        print(f"  wrote {a.stem}.before.hasm / .after.hasm")
    return 0


if __name__ == "__main__":
    sys.exit(main())
