# Removing the Wishlist surface — and the two reasons the first attempt did nothing

Reported: "wishlist is still shown in discord ... shown on our profile and on
other profiles". Both halves of that were right, and for two different reasons.
One was a wrong instruction; the other was a **wrong function** — the patch
edited a body that never runs.

## The trap: 349.5 ships every component twice, and only one copy is live

React Compiler adoption is the thing to internalise before touching any
component in this release. Every gated component is emitted as a pair:

```
GetByIdShort r1, r6, 12, "isReactCompilerEnabled"
Call1       r1, r1, r6
JmpTrue     L554, r1
CreateClosureLongIndex r1, r5, 75720     ; enabled() == false   <- LIVE
Jmp         L561
L554:
CreateClosureLongIndex r1, r5, 75719     ; enabled() == true    (dead)
L561:
```

The experiment is `2026-09-react-compiler-mobile`, `defaultValue = false`, and
this patch set leaves it on the default (`DISCORD_REACT_COMPILER = False`). So
**the first closure of the pair is the one that runs**, and the second is dead
weight that decompiles perfectly, round-trips through the assembler, passes
`bad_jumps()` and changes nothing at all.

That is exactly how the Wishlist tab survived. The previous revision of
`profile_tabs` targeted **f127527**, which is a closure of **75720**. Checking
the parent chain says 127527 is live — `xref --kind function --query 127527`
returns `75720`, and `75720` is the `false` branch — so that part was right.

## Reason one: `profile_tabs` flipped the wrong tab's guard

The previous edit was

```
0085  JmpFalseLong L300, r2   ->   JmpFalseLong L300, r0
```

described as "skips the Wishlist `push`". Decompiling the *patched* body says
otherwise:

```js
items[0] = obj;                                  // { id: "main" }
if (undefined) { ...; items.push(obj11); }        // { id: "board" }  <- this one
if (closure_1_18) { ...; items.push(obj14); }     // { id: "activity" }
const obj17 = { id: "wishlist", ... };
items.push(obj17);                                // unconditional
```

The Wishlist entry is the **last** push in the list and has **no guard at all** —
every account gets a Wishlist tab, empty or not, which is why it is always
visible and why there was no branch to flip. The edit removed the Board tab,
which `env[17]` was already hiding.

### The fix: swap a 5-byte `Call2` for a 5-byte `JmpLong`

```
0231  PutOwnBySlotIdx r2, r4, 2      ; obj17.page = ...
0235  Call2 r2, r3, r1, r2           ; items.push(obj17)   <- r1 = items, r3 = r1.push
023a  Ret r1
```

`Call2` with four register operands and `JmpLong` are **both 5 bytes**, so

```
0235  JmpLong L570                   ; L570 = 0x23a = the Ret
```

deletes the push with a **zero-byte shift**. Every instruction offset in the
body stays where it was, both labels (`L300` = 0x12c, `L458` = 0x1ca) keep their
offsets, and the two other pushes are untouched. The element construction above
it still runs and is discarded.

Note the arithmetic that cost one round: HASM `L<n>` is **decimal** while the
leading offset column is hex, so `0x23a` is `L570`, not `L586`. `bad_jumps()`
caught it — *"1 jump(s) land mid-instruction (0x235 JmpLong L586)"* — which is
the check earning its keep.

## Reason two: the other-profile edit pointed at dead code

`UserProfileContent.tsx` (factory 12882) has the same pair shape, and the first
attempt targeted **62409** — the *compiled* branch, dead for the same reason.
The live body is **f113177**, a closure of 62410.

Its tab list is built the same way (Main / Board / Activity / Wishlist), and
here the Wishlist push *is* guarded:

```
01f7  LoadFromEnvironment r1, r5, 24     ; the "show wishlist" flag
01fb  JmpFalse L622, r1
      ... NewObjectWithBufferLong r3, 19560, 837508   ; { id: "wishlist" }
      ... r2.push(r3)
L622: 026e Ret r2
```

`r0` is `LoadConstUndefined` at `001c` and is never written again before the
guard — confirmed by scanning every write to every register in the body, not by
spot-checking — so

```
01fb  JmpFalse L622, r1   ->   JmpFalse L622, r0
```

makes the branch always fire. Same opcode, same label, same register width.

## Reason three: the grid targets named a hook

Separately from the tab, the `UserProfileWishlistSuggestionsGrid` entries
pointed at **62773 / 62772**. Those are the module's *last* RC pair, stored to
environment slot 18 — not the export. 62773 decompiles to

```js
const items = [closure_5];
const stateFromStores = useStateFromStores(items, () => {...});
return useMemo(() => {...}, [stateFromStores]);
```

which is a **hook**. Nulling a hook hands its callers `null` where they expect a
subscription result — a plausible crash, not just a hidden grid. The export is
the **first** pair: `r1` is set at `L564` and is never written again before
`PutByIdStrict r2, r1, 0, "default"`.

`62767` (the live variant) is a gate already, which is why `null` is safe for it:

```js
let tmp = null;
if (useIsMobileWishlistSuggestionsEnabled("user_profile_wishlist_suggestions_grid")) {
    tmp = ...grid...;
}
return tmp;
```

The targets are now **62767 / 62766**.

`UserProfileWishlistGrid` was correct as it stood: `CreateClosure r3, r3, 62758`
sits immediately before `PutByIdStrict r2, r3, 0, "default"`, and 62758 is a
plain function with no RC pair on the export.

## What is covered now

| Surface | Target | Edit |
|---|---|---|
| You screen tab list | `127527` (of 75720) | `0235 Call2 r2,r3,r1,r2` → `JmpLong L570` |
| Other users' tab list | `113177` (of 62410) | `01fb JmpFalse L622,r1` → `,r0` |
| Active section index | `62714` | `wishlistTabIndex` is always `-1` (kept) |
| Wishlist grid | `62758` | `NULL` |
| Wishlist suggestions grid | `62767` / `62766` | `NULL` |

With the tab gone, `WishlistViewerCoachmark`, `EditWishlistActionSheet` and
`WishlistEmptyState` are unreachable from a profile; they are not targeted.
`AddToWishlistGrid` / `AddToWishlistItemCard` (62778/62779) were already
covered, and the collectibles `WishlistButton` is the *add* affordance, not a
display of the wishlist — left alone.

## Still open, deliberately

**The compiled variants are not patched.** 75719 (You screen) and 62409 (other
profiles) contain the same wishlist code and are dead only because
`DISCORD_REACT_COMPILER = False`. They are *not* equally easy to blind: in
75719 the push at `0d44 Call2 r55, r55, r11, r56` is followed by the memo-cache
writes for the items array, so jumping over it would also skip cache population.
If the React Compiler flag is ever turned on, the Wishlist tab comes back — the
two are coupled, and that is worth knowing before flipping it.

## State

```
patch:  0 FAIL, 159 Hermes functions stubbed, 66 OK
        all 10 body edits applied
build:  discord-alpha-349.5-patched-aligned-signed.apk
verify: PASS 169/169
```
