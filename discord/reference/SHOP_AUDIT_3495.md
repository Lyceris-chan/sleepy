# Shop audit — what was still reachable, and what still is

Asked: "verify we fully got rid of all mentions of the shop". The honest answer
is **no, not fully** — three surfaces were still reachable and are now closed,
and one remains that needs a riskier edit than it is worth. This is the audit.

## Method

"Covered" is not the same as "unreachable", so the pass did not start from the
target tables. It started from the module graph:

1. Enumerate every JS module path matching `[Ss]hop` (67 of them), plus the
   neighbouring feature packages (`creator_monetization`, `shop_this_look`,
   `game_profile`).
2. Drop everything that is plainly a child of the shop screen (cards, PDP
   previews, checkout, price placeholders) — `CollectiblesShopV2` /
   `CollectiblesShopInternal` are already nulled, so nothing they render is
   reachable.
3. For what is left, ask whether it is reached from *outside* the shop screen.
   That is the only way a surface survives nulling the screen, and it is where
   the three real gaps were.

## Already covered (unchanged this pass)

| Surface | Target |
|---|---|
| Settings row "Collectibles Shop" | route surgery, `shop_setting_predicate` |
| Profile shop entry button | `CollectiblesShopEntryButton` |
| Shop screen | `CollectiblesShopInternal`, `CollectiblesShopV2` |
| Shop button coachmark | `MobileShopButtonCoachmark` |
| Orb shop carousel | `QuestHomeOrbShopCarousel` |
| Orb gate (the feature switch) | `useVirtualCurrencyMobileEnabled` |
| Chat input gift button | `ChatInputActionButtonGift{,OrThread}` |
| Add-to-wishlist grids | `AddToWishlistGrid`, `AddToWishlistItemCard` |

## Added this pass (three gaps)

### 1. The shop screen component itself — `CollectiblesShopScreen` (71375/71374)

`modules/user_settings/shop/native/CollectiblesShopScreen.tsx` is what the
`CollectiblesShopSetting` route's `getComponent()` returns. Hiding the *route*
closes the Settings path, but the shop has a second way in —
`modules/collectibles/native/useCollectiblesShopDeepLinkProps.tsx` — so the
screen component is nulled directly. Both RC variants, so a flag flip cannot
revive it.

### 2. "Shop This Look" — 62163/62162 and 62180/62179

`modules/collectibles/shop_this_look/` is a **separate** shop surface: an action
sheet opened from a profile overflow menu plus a marketing coachmark. Neither
lives inside the shop screen, so nulling the screen did nothing to them.

### 3. Guild Shop — the two visibility gates

`modules/creator_monetization/guild_shop/` holds exactly two modules, and both
are boolean gates rather than components:

```
useGuildShopVisibleInGuild   hook (live 39223 / compiled 39222)
                             + a plain isGuildShopVisibleInGuild (39224)
useGuildShopPreviewVisible   RC pair, live 39226 / compiled 39225
```

Stubbed to `false` — the same shape as the quest gates already in the table.
Chasing the guild shop *screen* was abandoned deliberately: it is not under any
shop-named module, and the gate is the thing every reader consults anyway.

### 4. Game profile shop carousel — 47110/47109

`modules/game_profile/native/components/GameProfileShopCarousel.tsx`, rendered
on a game profile page. Nothing to do with the collectibles shop.

## Still there, deliberately

**"Shop This Look" is still an entry in the profile overflow menu.**
`modules/user_profile/native/UserProfileOverflowMenu.tsx` (factory 12790 ->
default closure 62133) builds 25 menu items, and the item whose action opens the
sheet is built around `0x5f5` in that body:

```
05db  GetById r35, r35, 51, "xNdRDO"          ; the label key
05e6  PutOwnBySlotIdx r33, r35, 0             ; item.label
05ea  CreateClosureLongIndex r35, r24, 112799 ; item.action
05f1  PutOwnBySlotIdx r33, r35, 1
05f5  Call2 r33, r34, r27, r33                ; build the item
```

Removing one item from a 25-item React-Compiler-generated menu means a length
change inside a body that HBC_BODY_EDIT_HAZARD.md exists because of, and the
menu also carries Block / Report / Copy ID — a bad edit there is not a missing
shop row, it is a broken overflow menu.

**The opener is also not patchable.** `openShopThisLookActionSheet` (62159) has
its 76-byte body **deduplicated with `showGummyCustomColorSheet`** (69939) from
`modules/display_name_styles/native/showGummyCustomColorSheet.tsx`. Stubbing it
would take an unrelated display-name feature with it; the shared-body guard
refused it. Nulling the sheet's own component instead means the item, if tapped,
opens an **empty sheet** rather than shop content.

So: one menu entry, and it leads nowhere. Flagged rather than fixed.

## Also checked, not patched

| Module | Why |
|---|---|
| `CollectiblesShopViewAllCategoryItemsSetting` | predicate is the shared `useStaffOrDeveloperSettingPredicate` — already invisible unless staff/dev |
| `DevToolsShopScreen` | devtools only |
| `QuestHomeOrbShopRewardCard{,AssetTile}` | behind the orbs gate, already off |
| `ShopNitroUpsellBanner` / `ShopNitroUpsellPromoSheet` | shop-screen internals |
| `ShopCategory` / `ShopFlashList` / `ShopBlockItem` | shop-screen internals |
| `game_profile/.../GameProfileStoreLinksActionSheet` | store *links*, not the shop |

## Result

```
patch:  0 FAIL, 175 Hermes functions stubbed, 67 OK
```

---

# OctoGram: Telegram Premium setting

Separate question, separate codebase. Verified against the **signed APK**, not
the working tree.

## The Premium rows are gone

`ProfileActivity.yd()` is the single Settings row builder; it resets every row
field to `-1` and then inserts each row behind a branch. The three edits from
PATCHES_3.6.1.md §8 are still present in the build:

| row | line | now |
|---|---|---|
| `premiumRow` — "Telegram Premium" | 160300 | `goto :cond_2e8` skips the `iput premiumRow` at 160316 |
| `premiumGiftingRow` — "Send a Gift" | 160449 | `goto :cond_332` |
| `premiumSectionsRow` | 160474 | `goto :cond_34e` |

Neither field occurs anywhere else as a *write* — `158881`/`158897`/`158901` are
the `-1` resets and the inserts are the lines above. The click router is
index-matched (`if-ne v2, v5, :cond_…` at 69175), so a row left at `-1` is
unclickable for free.

Confirmed in the shipped artefact:
`classes3.dex` inside `OctoGram_361_arm64-noads.apk` hashes **exactly** to the
assembled `work361/classes3.dex.new` (`992bde96…`), so what shipped is what was
assembled.

## Stars and Business are intact

- **`starsRow`** (160353 insert, guard `if-eqz v4, :cond_2fa` untouched) opens
  `Lr1c`, a Stars fragment — a different class from the paywall, unaffected by
  the §7 navigation guard.
- **`tonRow`** (160396) opens `slc`.
- **`businessRow`** (160432, guard `if-nez v4, :cond_322`) is still inserted.
- The whole **`org/telegram/ui/Business/` package is untouched** — the Business
  screens themselves are intact for anyone who has Business.

### One thing to know about the Business row

Its tap presents `PremiumPreviewFragment(account, "settings")` — line 69229:

```
69229  new-instance v0, Lorg/telegram/ui/PremiumPreviewFragment;
69231  invoke-direct {v0, v6, v7}, PremiumPreviewFragment;-><init>(ILjava/lang/String;)V
```

`v7` is the same `"settings"` set at 69177 for `premiumRow`, so this row is a
**Premium upsell**, not an entry to the Business settings. And the fragment's
`source` field is read exactly once, at line 12007, and handed to
`C3(String)` — an analytics call. It does **not** select a different UI.

So §7's guard (which refuses every `PremiumPreviewFragment`) turns that tap from
"show the Premium screen" into "do nothing". The row is still visible and
Telegram Business itself still works; only that one upsell entry is inert.

If you want the row to actually open the Business settings instead, it needs a
different edit — pointing it at the real Business screen rather than the
paywall. That is inventing behaviour, so it is not done here.

### One more surface, outside Settings

`work361/smali3/hq6.smali` — `.method public final k(Ljava/util/List;)Z` at line 4403 —
is a **slug-keyed command router**. It takes a command and its arguments and
dispatches on `equalsIgnoreCase`:

```
12174  "earn"     -> Lcq1    (earn/stars funnel)
12224  (default)  -> Lr1c    (Stars)
12245  "premium"  -> new-instance Lorg/telegram/ui/PremiumPreviewFragment;
12279  "business" -> new-instance Lorg/telegram/ui/PremiumPreviewFragment;
```

So there is a **`/premium` command** that presents the paywall, separate from the
Settings row. §7's navigation guard already makes it inert — it constructs the
fragment and the presentation is refused.

This is not a Settings entry and it is not removed, only defused. Worth knowing
if the command still shows up in a slash-command list, which is a different
question and a different edit.
