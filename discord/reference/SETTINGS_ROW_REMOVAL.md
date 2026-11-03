# Settings rows: adding a `usePredicate` to a `createRoute` config

## The lever

`createRoute` / `createToggle` / `createPressable` copy their config object
straight through (`Object.assign` + a `type` tag), and `SettingHookHarness`
(61429) hides any entry where `false === cfg.usePredicate()`. That is the whole
visibility rule, and it is the reason the Quest row could be removed by stubbing
its predicate (62294) while the *screen* it navigates to (62298) could not.

## It works when you add the slot

`CollectiblesShopSetting` (factory **15420**) had no `usePredicate` at all.
Appending, immediately before the `createRoute` call:

```
CreateClosure r<N>, r<env>, 62294          # 62294 is now `return false`
DefineOwnById r<obj>, r<N>, <slot>, "usePredicate"
```

removed the Collectibles Shop row from Settings. **Verified on device** - the
row is absent from `uiautomator dump` of the Settings screen, on a build that
otherwise runs 10+ minutes with no crash.

## It failed when applied to nine rows at once

The same generator was pointed at the nine billing / Nitro / boost / gifting
defs (14518, 14522, 14524, 14527, 14529, 14530, 14752, 14791, 15418). The
result crashed in `libhermesvm` from `performMicrotaskCheckpoint` after ~130 s,
with a **garbage fault address** (`0x503858723`, not the `0x14` null-deref seen
for the mid-instruction-jump class), and the user saw "something isn't a
function" on the Settings screen. All nine were reverted; the build is clean.

That is the *second* distinct failure signature in this file's history, and it
is worth naming: a null-ish fault means control flow went somewhere impossible,
a garbage non-null address means the object graph is corrupt.

## Where the generator was naive

Both suspects are in the two registers the insertion needs, and neither was
checked properly:

1. **The environment operand.** `CreateClosure rDest, rEnv, fnId` takes an
   environment. The generator took the *last* `CreateClosure` in the function
   and reused its source register - but Hermes emits `CreateClosure r8, r8, ...`
   (dest == env) for closures over the current frame, so that register holds a
   **closure** by the time the next instruction runs, not an environment. The
   shop edit has the same flaw and works, which suggests Hermes ignores the
   operand when the target function's `environmentSize` is 0 - and 62294 is
   stubbed to `return false`, so it captures nothing. That would make the
   operand harmless *if* the size is genuinely 0, and harmful otherwise.
   **Unproven; check 62294's header before reusing this.**

2. **The scratch register.** The generator picked the highest-numbered register
   not textually mentioned after the call. That is not liveness. A register can
   be unread in the remainder of the function and still be live across the call -
   held for an exception handler, or read by a nested closure through the
   environment. Clobbering it would corrupt exactly the kind of object graph
   that crashes later in a microtask checkpoint.

## Before retrying

- **One row at a time, on device.** The shop row worked and nine at once did
  not; batching hid which one, and the bisect cost more than the batching saved.
- **Do not reuse a `CreateClosure` source register as the environment.** Use the
  function's own `CreateFunctionEnvironment` register, or check the callee's
  environment size is 0 first.
- **Pick the scratch register from the frame.** Use `dump --kind functions` for
  the frame size, or reuse the destination of a `CreateClosure` already proven
  dead, rather than guessing from textual absence.

## Simpler alternative, not yet tried

Four of the nine already carry a `usePredicate` (14524, 14530, 14752, 14791).
Stubbing *those four closures* to `return false` needs no object surgery at all
and is the same shape as the Quest fix - the proven path. That cannot cover the
other five, but it may cover the rows the user actually sees.

---

# Resolved, 2026-09-27

The technique is sound. The batch was the problem, and so was the row mapping.

## What actually shipped

| Row | Def | Lever | Verified |
|---|---|---|---|
| Gift Inventory | 14530 | stub its existing `usePredicate` (62289) | on device |
| Manage Subscriptions | 14524 | stub its existing `usePredicate` (62280) | (not visible when unsubscribed) |
| Server Subscriptions | 14752 | stub existing `usePredicate` (62839) | (not visible when unsubscribed) |
| Restore Subscription | 14791 | stub existing `usePredicate` (62954) | (not visible when unsubscribed) |
| Nitro / **Manage Nitro** | 14518 | route gets a `usePredicate` (slot 6) | on device |
| Manage Plan | 14522 | route gets a `usePredicate` (slot 3) | 200 s clean, alone |
| Server Boost | 14529 | route gets a `usePredicate` (slot 4) | on device |

With all three route rows hidden, the **"Billing Settings" section header disappears
on its own** - `toSettingListItems` only emits a `SECTION_HEADER` when the filtered
row list is non-empty, so the header was never a separate thing to remove.

## The row mapping was wrong, and that cost a round

The user reported "Manage Nitro" still visible after the `manage_plan` edit. The
edit had applied correctly - **14522 is not the Manage Nitro row**. 14518
(`PremiumSetting`) is, and its title is chosen at runtime by
`getPremiumSettingTitle` (closure 62267):

```js
const subscribed = hasPremiumSubscriptionToDisplay(currentUser, getPremiumTypeSubscription());
if (subscribed) { if (experiment) t = t["4gwVVn"]; ... }   // "Manage Nitro"
```

so the same route reads "Nitro" or "Manage Nitro" depending on subscription
state. **Read the title closure before assuming the def name matches the label** -
`PremiumManagePlanSetting` is the "Manage Plan" row, not "Manage Nitro".

## Diagnosing it properly

- Comparing per-key values before/after the edit needs a real JS object-literal
  scanner; the quick regex version reported every edit as "LOST everything"
  because after the edit the literal is no longer inline in `createRoute({...})`.
  A check that fails on a correct edit is worse than no check.
- What worked was applying **one def at a time and running it on the device**.
  14522 alone: 200 s clean. 14529 with it: clean. 14518 with both: clean, and
  the user confirmed the Settings screen end-to-end.
- The culprit in the original nine is therefore one of **14524, 14527, 15418**.
  They are left unedited. (14524's row is covered by its predicate stub anyway.)

## Correction to the earlier section

The "where the generator was naive" section above is speculation and was wrong to
state as the likely cause. What the evidence supports is narrower: the technique
works, three specific defs were verified individually, and one of the three defs
that were *not* included is what broke the batch. Do not edit those three without
testing each one alone first.
