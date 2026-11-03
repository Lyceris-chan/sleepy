# Why the feature stubs did not hide the UI (348.5, on-device)

On-device report after the first 348.5 install:

| Element | Expected | Actual |
|---|---|---|
| Shop on the bar | hidden | **hidden — correct** |
| Quest on the bar | hidden | **still shown** |
| Shop row in Settings | hidden | **still listed** |
| Quest row in Settings | hidden | **still listed** |
| Friends' Spotify on profiles | hidden | **still shows** |
| Wishlist | hidden | **still shows** |

`verify-asks.py` reported all of these as hidden. It was passing on the wrong
functions.

## Root cause

Targets were chosen by **function name** out of the bundle's function table, and
verified by checking that the stub *landed* — never by checking what the
function actually *was*. This bundle has 128,469 functions and reuses names
heavily; `track` alone appears ten times, which is how the real analytics
emitter (fn 73760) was missed the first time.

Two confirmed instances:

- **`SpotifyTrack` (fn 54101) is a class, not a renderer.** Verified in the
  pristine bundle: the body is `HermesBuiltin.applyArguments(new.target, ...)`,
  i.e. a Hermes-compiled class constructor. Stubbing it to `return false`
  cannot affect the UI. The actual renderer is
  **`UserProfileRecentActivityCard` (fn 57537)**, which branches:
  ```js
  if (isGamingLikeEntry(entry))        title = entry.extra.game_name
  else if (isWatchedMediaEntry(entry)) title = media_title; subtitle = media_subtitle
  else if (isListenedSessionEntry(entry))   // <- Spotify
      title    = entry.extra.entries[0].media.title
      subtitle = media.artists[0].name
  ```
  and is fed by `useUserProfileActivity` (factory 12597, hook fn 57283) which
  returns `{ live, recent, stream, outbox }`.

- **`QuestHomeSetting` names two different modules.** `modules/user_settings/
  defs/native/QuestHomeSetting.tsx` (factory 14531) and
  `modules/quests/native/QuestHomeSetting.tsx` (factory 14534). The stub landed
  on one of them; a settings *row* is not rendered by the screen it navigates
  to, so stubbing the screen cannot remove the row either way.

## Why blocking Spotify hosts never hid friends' tracks

`api.spotify.com` / `dealer.spotify.com` are the *local* client's Spotify API.
A friend's listening activity arrives in the **gateway presence payload** on
`discord.com`. No host rule and no Spotify-API stub touches it. The activity is
already in the store by the time the UI renders it, so hiding it has to happen
in the activity pipeline, not at the network.

## The method that works

Same one that found the analytics emitter and the SupervisorJob idiom: anchor on
something **unique**, then walk the real path.

1. Anchor on the **module path string** — every Metro factory calls
   `fileFinishedImporting("<path>")`, so `xref --query "<path>"` returns exactly
   one function. Names are not unique; paths are.
2. Decompile the factory and read its `CreateClosure` targets to get the real
   component/hook function ids rather than guessing from the function table.
3. Before stubbing anything, check what it *is*: a class body
   (`new.target` / `applyArguments`), a screen, or a renderer.
4. Verify by **reading the render path back**, not just by confirming the stub
   landed — `verify-stubs.py` answers "did the stub apply", which is a different
   question from "is the element gone", and this file exists because those two
   questions were conflated.

## Still to do

- Quest on the bar; quest and shop rows in Settings (need the settings
  **definition table**, not the screens).
- Friends' Spotify on profiles (need a filter in the activity list, or an
  early-return in `UserProfileRecentActivityCard`).
- Wishlist (the two stubbed `AddToWishlist*` entries are presumably the same
  class-of-error; needs re-tracing).
- **New request:** block the animated profile **card** effects, keep the
  profile-picture (avatar) animation.


---

# Live-device findings, 2026-09-27 (ADB attached)

Method: `adb shell uiautomator dump` gives the real on-screen labels and tap
coordinates. This is the ground truth that was missing — it took five minutes
and immediately showed that **every target I had picked was wrong**.

## What is actually on screen

Dumped from the You screen / profile:

| Element | Where it really is | Tap coords seen |
|---|---|---|
| **Quests** | bottom bar of the You screen, between Friends and Settings | 440,2245 |
| Friends | same bar | 540,2340 |
| Settings | same bar | 631,2245 |
| **Wishlist** | a *profile section tab* — `Main \| Board \| Wishlist` | 852,884 |
| Main / Board | same tab row | 204,884 / 516,884 |

So "the bar" is the You screen's footer (`Friends / Quests / Settings`), not the
quest dock and not the settings list. And **Wishlist is a profile tab**, not the
`AddToWishlistGrid` / `AddToWishlistItemCard` components that were stubbed.

## Real modules, found by path

| Module path | What it is |
|---|---|
| `modules/main_tabs_v2/native/tabs/you/YouScreen.tsx` | the You screen that renders the Friends/Quests/Settings bar |
| `modules/main_tabs_v2/native/tabs/you/YouScreenNavIcon.tsx` | the nav-icon component that bar uses |
| `modules/main_tabs_v2/native/tabs/you/YouScreenContainer.tsx` | its container |
| `modules/user_profile/hooks/native/useProfileSectionTabs.tsx` | **builds the Main/Board/Wishlist section tabs** |

`useProfileSectionTabs` is the wishlist tab's source. `YouScreen` is the quest
bar entry's source. Neither has been touched yet.

## Method that works, restated for the next pass

1. `adb shell uiautomator dump /sdcard/ui.xml` + `cat` — read the live UI. Do this
   FIRST for every remaining item; it costs one command and eliminates guessing.
2. Take the exact label ("Quests", "Wishlist") and anchor on the **module path**
   of the component that renders it.
3. `xref --query "<module path>"` -> factory function id (paths are unique, names
   are not).
4. `disasm --function <factory>` -> `CreateClosure` targets -> the real component.
5. Stub or edit, rebuild, install, **re-dump the UI to confirm the label is gone**.
   Verification is the label disappearing from `uiautomator dump`, not the stub
   applying.

## Still to do

- Quests entry in `YouScreen`'s footer bar (440,2245).
- Wishlist section tab in `useProfileSectionTabs` (852,884).
- Quest + Shop rows in Settings — still need the settings definition table; dump
  the Settings screen to find the exact labels and their container.
- Friends' Spotify on `UserProfileRecentActivityCard` (fn 57537) — needs a filter
  in the entry list, not a stub; there is no null-title guard.
- Profile-card animation block, keeping the avatar animation.


---

# Traced targets, 2026-09-27 (second pass)

Found by module path -> factory -> `CreateClosure`, the method that works.

## Wishlist profile tab

| Step | Value |
|---|---|
| `modules/user_profile/hooks/native/useProfileSectionTabs.tsx` | factory **12644** |
| its closures | **57553** (builds `{boardTabIndex, activityTabIndex, wishlistTabIndex}`), **57554** (`useProfileSectionTabs` itself) |
| `YouScreenUserProfileContent` | factory **16616**, component **66982** |
| the call site in 66982 | `useProfileSectionTabs({ initialUserProfileSection, wishlistTabIndex, boardTabIndex, activityTabIndex, onTabChange })` |

`57554` branches on the section enum (`WISHLIST` / `WIDGETS` / `ACTIVITY` / `MAIN`) to pick the initial index, and normalises a negative index to `MAIN`. The tab **entries** are built by the caller (66982), not here — so hiding the Wishlist tab means finding where 66982 assembles that array and dropping the wishlist entry, not editing this hook.

`WISHLIST` is a member of the section enum from `closure_2` (the `UserProfileSections`-style object at `dependencyMap[46]`/`[48]` in 66982).

## Quests entry in the You screen footer

`YouScreen` = **66950** (factory 16599; it is the last `CreateClosure` before `PutByIdStrict ..., "default"`). It only forwards callbacks into `YouScreenUserProfileContent`:

```js
obj8 = { user, navigateToSettings, navigateToPremium, navigateToProfileCustomization,
         navigateToCustomStatus, navigateToFriends, navigateToShop, initialTab }
```

The `Friends | Quests | Settings` footer is **not** built here and not in 66982's tab list — it is a separate row and still needs its own trace. Start from the live label: `uiautomator dump` shows `Quests` at `440,2245`, between `Friends` (540,2340) and `Settings` (631,2245).

## Next steps, in order

1. In **66982**, find the array that builds the profile section tab entries and drop the `WISHLIST` entry. Rebuild, install, `uiautomator dump`, confirm `Wishlist` at 852,884 is gone.
2. Trace the footer row for `Quests`. Candidate starting points: `YouScreenNavIcon` (factory from its path), `YouScreenContainer`, or the object `obj8` consumers.
3. Settings rows — dump the Settings screen first, get the exact labels, then find the definition table.
4. Spotify on profiles — filter in the entry list feeding `UserProfileRecentActivityCard` (57537).
5. Profile-card animations.


---

# Resolved, 2026-09-27 (third pass) — all five, traced to a real lever

Every target below was reached by **module path → factory → `CreateClosure`**, then
checked against the actual consumer. Each edit is a before/after pair of
disassembled bodies under `patches/hasm/`, applied by
`patch_js_feature_removal` in `patches/core.py`, which refuses to write unless
the function still disassembles to the stored before-image.

The live `uiautomator dump` was the thing that fixed this round: it showed the
footer is a floating pill holding **exactly two** buttons (`Quests`, `Settings`)
at `[304,2153][776,2337]`, and that the full-width `Friends` at `[42,2271]` is a
*card inside the scroll content*, not a footer button. "The bar" had been the
wrong mental model.

## 1. Wishlist profile tab — `105481`, `57553`

`YouScreenUserProfileContent` (66982) hands `useSegmentedControlState` an `items`
array built by the `useMemo` at **105481** (10th `CreateClosure` in 66982,
`CreateClosureLongIndex r9, r7, 105481`). That memo pushes `main`, then `board`
if enabled, then `activity` if enabled, then **`wishlist` unconditionally**.
The wishlist push is removed, so the array is Main/Board/Activity.

`useProfileTabIndices` (**57553**) computed `wishlistTabIndex` from its third
argument; it now always yields `-1`. That matters because
`useProfileSectionTabs` treats a negative index as "not available" and
normalises to `MAIN` — without it, a deep link into the Wishlist section would
select index 3 of a now-3-item array.

`useProfileSectionTabs` (57554) itself is *not* the lever: it only picks an
initial index from the section enum. The entries are the caller's.

## 2. Quests button in the You-screen footer — `66956`

`UnconnectedYouScreen` (66947) builds `obj63 = { isLoading, navigateToPremium,
navigateToSettings, navigateToShop, shopButtonRef, settingsButtonRef,
paddingBottom }` and renders it through the component at `dependencyMap[41]` =
the floating nav, **66956**. That is why the *Shop* button disappeared when
`CollectiblesShopEntryButton` (66966) was stubbed and *Quests* did not: the
Quests button is not a sibling component, it is built inline:

```js
const isEligibleForQuests = getIsEligibleForQuests();
let tmp25 = null;
if (isEligibleForQuests) { tmp25 = createElement(NavButton, { IconComponent: QuestsIcon, ... }, "quests"); }
const items5 = [tmp25, createElement(ShopEntryButton, ...), ...];
```

`getIsEligibleForQuests` is the permanently unpatchable shared body, so the
gate is opened **at the call site**: `02e7 JmpFalse L855, r23` becomes an
unconditional `Jmp L855`, leaving `tmp25` null. The block stays in the
bytecode, dead — which is why only the *disassembly* settles this and the
decompiler still prints the element.

## 3 & 4. Quest and Shop rows in Settings — `62294`, `15420`

The settings list is built from `SETTING_RENDERER_CONFIG`. `SettingHookHarness`
(61429) reads each entry and treats `false === cfg.usePredicate()` as hidden;
`toSettingListItems` (99355) then filters rows through `isBlocked`. (The
consumer reads `usePredicate` by name in exactly one place in the whole bundle,
so that chain is complete.)

- **Quest** — `modules/user_settings/defs/native/QuestHomeSetting.tsx` (factory
  14531) builds its route with `usePredicate` = closure **62294**, body
  `getIsEligibleForQuests()`. 62294 is a unique 34-byte body at offset
  `1fd3172`, so it is stubbable even though the function it calls is not. It
  goes in `FALSE_TARGETS`, **not** `TARGETS`: the harness compares with
  `false ===`, so an `undefined` return would leave the row visible.

- **Shop** — `CollectiblesShopSetting.tsx` (factory 15420) has **no
  `usePredicate` at all**, which is why a predicate-shaped fix could not work.
  The route object is built with `NewObjectWithBufferLong` + `PutOwnBySlotIdx`,
  and the edit appends
  `DefineOwnById r1, r8, 5, "usePredicate"` before `createRoute` is called,
  pointing at the same (now-false) 62294. The decompiler renders the result as
  the ordinary statement `obj1.usePredicate = function usePredicate() { ... }`
  — the cleanest available evidence that the encoding is sound.

`62298` ("QuestHomeSetting" in the old `ASKS` list) is the **screen the row
navigates to**. Stubbing it removed nothing, which is exactly what the
on-device report said.

## 5. Friends' Spotify on profiles — `117345`

`useUserProfileActivity` (57283) returns `recent` from a `useMemo` (96546)
whose body is `entries.filter(...)`. The filter is closure **117345**:

```js
if (isEntryLive(entry)) return false;
if (isListenedSessionEntry(entry)) return entry.extra.entries.length > 0 && !blocked.some(...);
if (isWatchedMediaEntry(entry))     return !blocked.some(...);
return isRecentActivityEntry(entry);
```

The `isListenedSessionEntry` branch is replaced with `LoadConstZero r1`, so
listened-session entries never reach `UserProfileRecentActivityCard` (57537).
Editing the card instead would not have worked: it picks a title per entry type
and has **no null-title guard**, so blanking the Spotify title leaves an empty
card rather than no card. `entry.extra` is read only in that branch, so its
absence from the disassembly is the check that the branch stopped computing.

## 6. Profile card animation — `45655` (avatar decoration kept)

`modules/collectibles/profile_effects/native/ProfileEffect.tsx` (factory 8265)
exports `WrappedProfileEffect` as closure **45655** — the animated decoration
layer over the profile **card**. It is stubbed to `return null` via a new
`NULL_TARGETS` / `NULL_HASM` shape: `undefined` is only a legal React return
from React 18 on, and `0` renders as the literal text "0", so neither the
existing no-op nor zero stub is correct for a function component. The
component already has an early `return null` on its unresolved path, so every
caller handles it.

The profile-**picture** decoration is a different module
(`modules/collectibles/avatar_decorations/`) and is deliberately untouched.

## What generalised

- **`xref` works on identifiers, not just strings** — `xref --query
  "usePredicate"` found the single consumer of the route predicate in one call,
  after three rounds of guessing at consumers. Reach for it before reasoning
  about who reads a property.
- **Never read column 3 of the function table.** Rows with an empty name have no
  name column, so `awk '{print $3}'` returns the *size* for them and the
  *offset* for named ones. That silently reported 108 sharers for a body that
  has exactly one, and would have refused a perfectly safe edit.
- **A dead branch still decompiles.** Both the footer edit and the tab edit
  leave unreachable code behind; the decompiler prints it as though it were
  live. Only `disasm` shows whether a jump is conditional.

