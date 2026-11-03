# Links out to the system browser, the Board tab, and the last "Quests" rows

Three changes in one pass:

1. `EXTERNAL_BROWSER` — new, **on by default**. Every web link opens in the
   system browser instead of Discord's in-app Chrome Custom Tab.
2. `profile_tabs` / `profile_tabs_other` — the Board tab is now removed as
   well as the Wishlist tab, on both your own profile and other people's.
3. The last two rows in Settings that carry the word "Quests".

## 1. External browser

### The surface, in full

Discord's whole link surface is one React Native module plus the singleton it
delegates to:

```
com/discord/browser_manager/BrowserManagerModule   <- JS-facing
com/discord/browser_manager/BrowserManager         <- the implementation
```

The JS side is a single module, `modules/links/native/BrowserManager.tsx`
(factory 4852), and a single function, `browserManagerOpenUrl` (fn 32049):

```js
function browserManagerOpenUrl(url, selectedBrowserOpt) {
  let selectedBrowser = selectedBrowserOpt ?? getState().selectedBrowser;
  ...
  if (selectedBrowser === WebBrowserType.IN_APP) return openInAppURL(url).then(...);
  if (selectedBrowser === WebBrowserType.CHROME) return openInChromeURL(url);
  return assertNever(selectedBrowser);
}
```

with

```js
// modules/<...>/ConstantsIOS.tsx, fn 1106
WebBrowserType = { SAFARI: 0, [0]: "SAFARI", IN_APP: 1, [1]: "IN_APP", CHROME: 2, [2]: "CHROME" }
```

and the two native branches land here:

| JS call | Native | What actually happens |
|---|---|---|
| `openInAppURL` | `tryOpenUrlWithCustomTabs` | Chrome Custom Tabs, rendered **inside Discord's task**, under its toolbar |
| `openInChromeURL` | `tryOpenUrlExternally` | plain `ACTION_VIEW` -> **the real system browser** |

So `openInChromeURL` already does exactly what is wanted. It is simply not what
the default selects.

### Why the default was IN_APP

`BrowserManagerModule.getTypedExportedConstants()` is where the JS store gets
`selectedBrowser` from:

```kotlin
val supportsCustomTabs = CustomTabs.supportsCustomTabs(reactApplicationContext)
val selected = Cache.get().getItem("SELECTED_BROWSER")?.toIntOrNull()
    ?: if (supportsCustomTabs) 1 else 2      // <- 1 = IN_APP
```

The fallback is `1` — in-app — whenever Chrome Custom Tabs are available, which
on a normal phone is always. The `2` branch only ever runs where Custom Tabs are
missing.

### The two edits

**a. `getTypedExportedConstants`** — the cache key is renamed to
`"SELECTED_BROWSER_IGNORED"` (nothing writes that key, so the lookup always
misses) and the `const/4 v1, 0x1` fallback becomes `0x2`. The app now reports
CHROME.

Renaming the key rather than deleting the lookup is deliberate: `selectBrowser`
still writes the real key, so nothing else in the app has to change, and the
edit stays a two-token diff instead of control-flow surgery.

**b. `openInAppURL`** — re-pointed from `tryOpenUrlWithCustomTabs` to
`tryOpenUrlExternally`. The two methods have **identical descriptors**:

```
tryOpenUrlWithCustomTabs(Landroid/content/Context;Ljava/lang/String;Lkotlin/jvm/functions/Function1;)V
tryOpenUrlExternally     (Landroid/content/Context;Ljava/lang/String;Lkotlin/jvm/functions/Function1;)V
```

so this is a method-name swap inside one `invoke-virtual` — no register change,
no signature change, nothing for the verifier to disagree with.

The pair is deliberately redundant. (a) makes the external browser the *default*
and keeps Discord's own settings UI self-consistent; (b) makes it a *guarantee*
even if something still selects IN_APP — a stale store, a deep link that passes
an explicit `selectedBrowser`, or a future caller added upstream.

### Deliberately not changed

- **`openTrackedCustomTab`.** Called from exactly one place —
  `modules/vibegrations/native/VibegrationsDesignFeedbackOverlay.tsx`
  (f138378 <- 96218 <- 46230 <- 8101 <- 16594), a design-feedback survey. Its
  callback is `Function1<Boolean, Unit>` where `tryOpenUrlExternally` wants
  `Function1<Unit, Unit>`; the generics erase, so the swap would assemble and
  then throw `ClassCastException` on the callback's Boolean cast. One survey
  overlay is not worth that.
- **`openPlayStoreInline`.** A Play Store deep link, not a web link, and
  `tryOpenUrlExternally` is what it would do anyway.
- **Internal Telegram-style schemes.** Not applicable here — Discord has no
  equivalent short-circuit; `tryOpenUrlExternally` handles whatever it is given.

## 2. Board tab

`profile_tabs` (f127527) and `profile_tabs_other` (f113177) now carry **two**
byte-neutral edits each:

| File | Edit | Effect |
|---|---|---|
| both | `JmpFalseLong L300, r<env>` -> `, r0` | `r0` is `LoadConstUndefined` at `001c` and is never written before the guard, so the Board branch is never taken |
| `profile_tabs` | `0235 Call2 r2, r3, r1, r2` -> `0235 JmpLong L570` | the unconditional Wishlist push; `Call2` (4 regs) and `JmpLong` are both 5 bytes, so zero shift |
| `profile_tabs_other` | `01fb JmpFalse L622, r1` -> `, r0` | the guarded Wishlist push |

Decompiled result, both files: Main always, Activity under its own flag,
**no Board, no Wishlist**.

## 3. The last "Quests" surfaces

### 3a. The Quests home screen

`modules/quests/native/QuestHome.tsx` (factory 14807) was never covered. Every
*mobile entry point* into it already is — `useShouldShowQuestsActivityPanelItem`,
`useIsMobileQuestDockVisibleToUser`, `floating_nav_quests`, and the Settings row
via 68593 — but the screen is what a route name navigates to, and route names
are cheap to reach (241 references to `QUEST_HOME` alone).

The module exports `memo(...)`, so the same shape as the wishlist grids:

```
L827: CreateClosureLongIndex r5, r1, 68628     ; enabled() == true
L834: CreateClosureLongIndex r5, r1, 68629     ; enabled() == false  <- live
      GetById r1, r6, 17, "memo"
      Call2   r1, r1, r6, r5
      PutByIdStrict r2, r1, 2, "default"
```

Both variants are in `NULL_TARGETS`, so a React Compiler flag flip cannot bring
it back. With the screen nulled, anything that still navigates there renders
nothing rather than the quests UI.

### 3b. The two "Use data for quests" Settings rows

Both toggles that literally carry the word, in **Data & Privacy**:

```
modules/user_settings/defs/native/UseDataForQuestsSetting.tsx    (factory 15761)
modules/user_settings/defs/native/UseDataForQuests3PSetting.tsx  (factory 15764)
```

Both are `createToggle({ parent: MobileUserSettings.DATA_AND_PRIVACY,
usePredicate(){...}, ... })` and their predicates are:

```js
usePredicate() { return !useIsAdTopicOptOutClientEnabled(); }
```

Hermes deduplicated the two predicates onto **one 37-byte body at 2506814** —
same offset, both named `usePredicate` — so a single `FALSE_TARGETS` entry
(71516) hides both rows. `71528` is deliberately not listed: it points at the
same body, so a second entry would produce a permanent
`decompiler did not name it` SKIP once the first stub had landed.

The guard against patching something unintended is the `SHARED_OK` alias set,
which must match **exactly** (`{"usePredicate"}`) or the patch is refused —
verified against `dump --kind functions`, not assumed.

### Checked and left alone

| Surface | Why |
|---|---|
| `CollectiblesShopViewAllCategoryItemsSetting` (15749) | its predicate is the **shared** `useStaffOrDeveloperSettingPredicate`, so the row is already invisible to anyone who is not staff or a developer |
| `QuestPreviewToolSetting` (14969) | gated on `useIsPreviewerOnAnyQuest()`, a previewer-only flag |
| `ParentalControlsUseDataForQuests{,3P}Setting` (15811/15812) | Family Centre rows; they carry **no** predicate, so hiding them needs `regen_setting_predicate.py` object surgery rather than a stub. Not done — they are only reachable with Family Centre configured |
| the `…SponsoredContentSetting` siblings in 15761/15764 | their predicate *is* the imported `useIsAdTopicOptOutClientEnabled`, shared with the ad-personalisation rows, and their titles are about ads, not quests |

## State

```
patch:  0 FAIL, 162 Hermes functions stubbed, 67 OK
        EXTERNAL_BROWSER on by default
build:  discord-alpha-349.5-patched-aligned-signed.apk
```
