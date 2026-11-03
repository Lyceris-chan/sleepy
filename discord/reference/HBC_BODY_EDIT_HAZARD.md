# HASM label operands are offsets, not names

**This crashed the app on every launch for a whole build round. Read it before
writing another `patches/hasm/*.after.hasm`.**

## The rule

In `hermes-decomp` HASM, a jump operand is written `L<n>` and `<n>` is a
**byte offset in decimal**, not a symbol the assembler resolves by looking for a
line that says `L<n>:`. `emit-hasm` prints those labels because that is how the
disassembler names a position - it is a *rendering*, not a reference.

While a body keeps its length the distinction never shows, because every label's
name still equals its own offset. The moment an edit changes the length of
anything before a jump, every `L<n>` after that point names a position that has
moved.

## What it did

`floating_nav_quests` turned `JmpFalse L855, r23` into a shorter `Jmp L855` to
make the Quests button unreachable. That is one byte less, which moved
`NewArray r13, 4` from **855 to 854** - so the 4-byte instruction now spans
854..857 and offset 855 is its *second byte*.

The jump still said 855. Control landed mid-instruction.

```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x14 (read)
Cause: null pointer dereference
  tid: mqt_v_js   (the RN JS thread)
  #07 libreactnative.so  RuntimeScheduler_Modern::performMicrotaskCheckpoint
  #00-06 libhermesvm.so
```

Dead in 20 seconds, every launch. The identical signature is already recorded in
`reference/ANR_AUDIT_3485.md` for `patch_js_body_edits`, which is disabled for
the same class of reason - a body edit that assembles, round-trips and
decompiles cleanly, and still kills the process.

## Why nothing caught it

- **`asm-check` / round-trip passed.** Re-assembling a body whose labels resolve
  numerically to stale offsets reproduces itself exactly. A self-consistent
  wrong answer round-trips perfectly.
- **The decompiler still prints the dead block**, so the function looked
  plausible.
- **`bin-diff` did not flag it**, and could not have: it reports *which*
  functions changed, not whether a changed function's bytecode is coherent.
  (It also only compares ~41k of the bundle's 128k functions.)
- **`verify-asks.py` passed**, because the check asked "did the edit apply",
  which it did.

## The check that does catch it

`bad_jumps()` in `patches/core.py`. After assembling, it re-emits the body that
is about to be written and asserts that **every jump target is the offset of an
actual instruction**. Run against the original broken edit it reports all five:

```
0x2e7 Jmp L855            0x427 Jmp L1114
0x38c JmpTrueLong L1066   0x42b JmpFalse L1111   0x4e7 JmpFalse L1313
```

It is a post-flight assertion on the assembled artifact, not a rule about how
edits should be written, so an edit that shifts bytes is still allowed as long
as no jump actually breaks. `profile_tabs` shifts by 22 instructions and is
fine: it only removes instructions *after* its last jump target, so the two
labels it does reference keep their offsets.

## Prefer byte-neutral edits

Both crashes came from edits that changed a body's length. Both were fixed by
changing an *operand* instead:

| Edit | Was | Now |
|---|---|---|
| `floating_nav_quests` (66956) | `Jmp L855` (1 byte shorter) | `JmpFalse L855, r10` - `r10` was set to `null` two bytes earlier, so the branch is taken unconditionally and the encoding is the same width |
| `spotify_recent_activity` (117345) | delete the 14-instruction listened-session block | `Greater r1, r0, r0` - compares zero with itself, same 4 bytes, yields `false` |

Both are now single-operand changes with identical instruction counts, every
label keeps its offset, and the assembler's job is trivial.

When a byte-neutral edit is not possible, the after-image must have its jump
targets re-derived for the new layout - not copied from the old one.
