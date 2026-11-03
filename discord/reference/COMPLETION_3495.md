# Finishing the 349.5 bump: the body edits, the last targets, two ANR finds

Follows `BUMP_3495.md`, which left 21 Hermes targets and all 8 body edits
skipping. Both are now closed.

## The body edits

All nine now apply. They needed re-deriving rather than copying: each stores an
exact before-image and the guard correctly refused every one on 349.5.

Two of the three techniques are **byte-neutral** - one operand changes, no
instruction moves, so no `L<n>` operand can be invalidated:

| Edit | Was | Now |
|---|---|---|
| `wishlist_index` | `Mov r2, r0` | `Mov r2, r2` (r2 stays -1) |
| `floating_nav_quests` | `JmpFalse L884, r21` | `JmpFalse L884, r10` (r10 was just set to null, so the branch always fires and the Quests item is never built) |
| `profile_tabs` | `JmpFalseLong L300, r2` | `JmpFalseLong L300, r0` (r0 is `undefined` from `001c` and never rewritten - same trick, skips the Wishlist `push`) |

The third technique is an **insertion**, for the four settings rows: two
instructions added immediately before the `createRoute` call,

```
CreateClosureLongIndex r<scratch>, r<env>, 68593
DefineOwnById r1, r<scratch>, <slot>, "usePredicate"
```

Everything the insertion needs is read out of the *new* body (object register,
environment register, next free slot, dead scratch register); only the
predicate id is passed in.

### The generator earned its trust by reproducing 348.2

`tools/regen_setting_predicate.py --check` regenerates the *stored* after-image
and diffs it. It had to match before it was allowed near 349.5. It caught two
of my own bugs on the way:

- I read the **object** register where the slot operand was, so every slot came
  out wrong (`[1,1,1,7,7]` instead of `[0,1,2]`).
- I compared `L<n>` targets as hex. They are **decimal** (the leading offset
  column is hex) - so every jump looked like it was past the insertion point
  and the guard refused four safe edits.

### `CreateClosure` wraps at 16 bits

First run against 349.5 failed with:

```
round-trip mismatch (line 33: 'CreateClosure r10, r8, 3057' != '... 68593')
```

`68593 - 65536 = 3057`. 348.2's predicate was at 62294, which fits; 349.5 has
155k functions and it does not. The long form takes the full width. For an
insertion the extra two bytes cost nothing that the offset check has not
already accounted for.

### A body full of jumps is not automatically unsafe

349.5's React Compiler wraps component closures in `isReactCompilerEnabled()`,
so `premium_setting` - straight-line in 348.2 - now contains real jumps. The
blanket "body has jumps, refuse" rule was wrong. What actually matters is
whether any `L<n>` target is at or *after* the insertion point, because
inserting at P only moves bytes after P. In `premium_setting` every target
(L168/L175/L205/L212) is below the anchor at 0x136, so the insert is safe.

## The last Hermes targets

153 functions are now stubbed, up from 137, and only 4 ids do not match.

Two systemic fixes mattered more than the individual lookups:

- **`useVirtualCurrencyMobileEnabled`** was skipping on "no object template".
  Its 349.5 body is an *exact* match for 348.2 - the only difference is
  `NewObjectWithBuffer` (short form, small buffer index) where the patch
  required `NewObjectWithBufferLong`. This is the orb gate, so it was hiding a
  whole feature behind a regex. The template now keeps whatever width the
  original used.
- **`remap_hermes.py` only covered three of the six `*TARGETS` tables**, so
  `FALSE_TARGETS` / `NULL_TARGETS` / `OBJECT_FALSE_TARGETS` kept stale ids and
  skipped on every bump. All six are covered now.

Individual findings, each confirmed by **body match**, not by position:

| Target | 349.5 | Evidence |
|---|---|---|
| `WrappedProfileEffect` | 47309/47308 | `let thumbnailUrlOverride = arg0; ... arg0.skuId`; the pair is the module `default` |
| `BalanceWidgetMenu` | 70912 | renders `accessibilityLabel`/`onPress`/`trailing` off `useFetchVirtualCurrencyBalance` - **the name match was right and the structural walk wrong**, because the module's closure order changed |
| `GiftButton` | 62908/62907 | `({ product, analyticsLocations, variant } = arg0)`; the section's `default` would have hidden too much |
| `CollectiblesShopInternal` | 71380/71379 | `const analyticsSource = arg0.analyticsSource` |
| `usePredicate` (Gift) | 68590 | `isPremiumGiftingSupported()` |
| `useHasPremiumRestoreSubscriptionSetting` | 69607/69606 | `...verified && !isAndroid()`; 69609 is the *action* closure |
| `useShowManageSubscriptionsSetting` | 68574/68573 | `useHasPremiumSubscriptionToDisplay()` |
| `useIsMobileQuestDockVisibleToUser` | 68858 | `PutByIdStrict ..., "useIsMobileQuestDockVisibleToUser"` |
| `useShouldShowQuestsActivityPanelItem` | 56206 | `PutByIdStrictLong ..., "useShouldShow..."` |
| `CollectiblesShopEntryButton` | 75688/75687 | module `default` |
| `CollectiblesShopV2` / `QuestHomeSetting` / `QuestOrbsRewardModal` / `ChatInputActionButtonGift{,OrThread}` | 71382/81, 68601/00, 56457/56, 59212/11, 59200/99 | module `default` |

**Dropped as gone**: `OrbsBalance`, `OrbsPriceTag`, `EditSection` have no
module path, no name string and no function anywhere in 349.5. There is nothing
left to hide; the orb *gate* (`useVirtualCurrencyMobileEnabled`) still covers
the feature at the source.

### The "unpatchable" four were not

`AddToWishlistGrid`, `AddToWishlistItemCard`, `MobileShopButtonCoachmark` and
`f73887` all resolved once they were looked at properly - by **module path**
for the first three and by **body match** for the last. The real lesson is what
`57595` turned out to be:

> `57595` carried the name `AddToWishlistGrid` and its body was
> `BaseIconImage` - an **icon renderer**, whose bytecode is shared by **359
> function headers**. The React Compiler emits arrow components with identical
> bodies by the thousand and Hermes deduplicates them onto one offset, so
> "359 headers, one body" is normal here, not suspicious.
>
> The shared-body guard is what stopped it: stubbing that body would have
> stubbed 359 icons. A wrong entry that points at a *deduplicated* body is
> invisible until you check the offset - which is why there is now an audit for
> exactly that (below).

`77913 onPostConnectionOpen` was dropped rather than fixed: its body is shared
with two functions in `usePromotionMarketingComponent` (a different module) so
stubbing it would stop those too, and `/promotion`, `/bogo-promotions` and
`/outbound-promotions` are already in `blocklist.py`, so the fetch it triggers
is dead anyway.

### Audit: no entry points at a shared body any more

```
entries whose body is deduplicated:
  OBJECT_FALSE_TARGETS  62046 useVirtualCurrencyMobileEnabled
                              shared with 1 (isVirtualCurrencyEnabled)   <- SHARED_OK alias pair
```

That is the only one, and it is the vetted alias set. Running this after any
bump is the cheap way to catch the `57595` class of error.

## Two ANR finds

### 1. `ActivityDelegate` blocks the UI thread on OTA recovery (fixed)

`onCreate$lambda$2`, on the UI thread during activity creation:

```java
if (!latch.await(30, SECONDS)) {
    Log.e("ActivityDelegate", "App load still waiting ... after 30s");
    captureException(...);
    latch.await();                 // unbounded
}
runBlockingOtaRecovery();          // BundleUpdater.checkForUpdate().get()
```

`needsBlockingOtaRecovery()` is just `CrashReporting.isCrashedLastRun()` - so
the path arms itself **after a crash**, exactly when the user is reopening the
app. And because this patch set points the OTA host at a non-resolving domain,
the `checkForUpdate()` future waits out a network timeout before `.get()`
returns.

`needsBlockingOtaRecovery()` now returns false. That is safe *because* of the
OTA patch: the app is already pinned to the APK's bundle, so a blocking OTA
check can only ever delay startup. The untimed `latch.await()` above it is left
alone on purpose - it only runs when app load has already failed to complete in
30 s, and bounding it would let the activity continue against a half-
initialised runtime.

### 2. `SurfaceEglRenderer.surfaceDestroyed` blocks the UI thread (opt-in)

Covered in `INTL_SPOTIFY_PERF_3495.md`; `DISCORD_BOUND_SURFACE_RELEASE`.

### Checked and rejected

- `SurfaceViewRenderer.updateSurfaceSize`'s per-layout log string is **dead
  code** - gated on `enableFixedSize`, whose only setter
  (`setEnableHardwareScaler`) is called from nowhere.
- `EglRenderer.setLayoutAspectRatio` no longer logs; fixed upstream in 349.5.
- `TTIModule.yieldConfig`'s `Thread.sleep` takes a `Promise`, so it is off-main.

## Final state

```
patch:  0 FAIL, 154 Hermes functions stubbed, 0 ids unmatched
        all 9 body edits applied
build:  discord-alpha-349.5-patched-aligned-signed.apk
verify: PASS 164/164
intl:   58 cache_intl files kept (engb), 1683 removed
```

## Reproducing

```sh
# body edits: regenerate, then check against the stored 348.2 image first
tools/regen_setting_predicate.py --old /tmp/pristine3482.bundle --check \
    --factory 14522 --predicate 62294 --stem manage_plan
tools/regen_setting_predicate.py --new /tmp/pristine3495.bundle --write \
    --factory 14790 --predicate 68593 --stem manage_plan

# inspect a module's closures when a target has no name left
tools/module_closures.py <bundle> "modules/<path>.tsx"

scripts/02-patch.sh build/alpha3495 && scripts/03-build.sh build/alpha3495
```
