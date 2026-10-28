# Valenced plugin coverage

This is the audit of the seven Valenced plugin repositories against sleepy's 353.2.0 JavaScript
table. Valenced is a Discord client mod that loads JavaScript plugins at runtime; its plugins are
not APK patches. A plugin is includable only where its whole effect is a component that can return
nothing, a predicate that can be forced, or a value that can be replaced in the bundle.

The audit was run on 2026-10-28. Three plugins supplied ideas that fit: the table divider from
**No Dividers**, and the call-screen, DM activity and Conjure surfaces from **Junk Cleanup**
(**Declutter**'s boost goal is the same surface as the new **Server boost displays** switch).

## Included

| Valenced plugin | sleepy switch |
| --- | --- |
| No Dividers | **Separator lines** — `TableRowDivider` (40223/40224) plus the context-menu, form, list, section, vertical, autocomplete, authorization and server-bar separator components return null; the channel-list footer-divider predicate (78065) answers false. |
| Junk Cleanup — call screen | **Call screen extras** — the add-people banner (`VoicePanelFloatingCTAContainer`, 81454/81455) and the console section of the audio sheet (`VoicePanelAudioConsoleSection`, 50059/50060) return null. |
| Junk Cleanup — DM cards | **Activity cards in DMs** — `HappeningNow` (77657/77658) returns null and `getMessagesItemHappeningNowHeight` (77649) returns zero. |
| Junk Cleanup — Conjure | **Conjure AI buttons** — `ConjureChannelRow` (78150/78151) and `ConjureYouBarButton` (78671/78672) return null. |
| Junk Cleanup — reaction row | **Reaction bar in message menu** — `shouldShowEmojiRow` (66759) answers false. |
| Declutter — boost goal | **Server boost displays** — the channel-list boost bar and its related surfaces return null. |
| Declutter — display-name styles | **Display-name styles** — `useDisplayNameStyles` (37278) returns null. |
| Declutter — server themes | **Server themes** — the server-theme experiment predicates (34014/34015/34016) answer false. |
| Server Drawer — ICYMI | **ICYMI feed** — `getICYMIEnabled` (48813; the alias 48816 shares its body) and both `useICYMIEnabled` copies (48814/48815) answer false. |
| Questify — bounties | **Quest bounties** — `QuestHomeBounties` (73986/73987) returns null. |
| JumpToTop — anchor button | **Jump to present button** — `JumpToPresentButton` (63997/63998) returns null; the plugin itself only adds UI. |

Junk Cleanup's display-name-styles option is the same target as Declutter's, so it is covered by
**Display-name styles** as well. Most of the other hide options already have sleepy switches:
avatar decorations, nameplates, profile effects, profile frames, guild tags, profile badges,
wishlist tabs, the quest dock, shop entry points and orbs.

## Not portable

| Plugin | Why it cannot be a bundle patch |
| --- | --- |
| Server Drawer (Accentaro) | The drawer itself replaces the guild bar and navigation at runtime and has no single owning function; only its ICYMI gate is portable. |
| Quoter (Accentaro) | Adds a quote action and composes the message at runtime. |
| GIF to Chat Input (Accentaro) | Adds a picker and an attachment flow in JavaScript. |
| GIF Providers (Accentaro) | Adds provider UI and request handling. |
| Collapsible UI (Accentaro) | Wraps sections in runtime state and gesture handling. |
| Better You Bar (Accentaro) | Rearranges the you bar with runtime components. |
| Timezones (Bleelblep) | Formats timestamps at runtime. |
| Send Tweaks (Bleelblep) | Changes the send flow in JavaScript. |
| ReviewDB (Bleelblep) | Talks to a third-party service and renders its UI. |
| New Members (Bleelblep) | Adds runtime chat entries. |
| Hide Servers (Bleelblep) | Replaces the guild bar, its store getters and its navigation; a stub cannot reproduce it. |
| Custom Timestamps (Bleelblep) | Formats timestamps at runtime. |
| Platform Spoof (k1ng0p) | Changes the gateway platform identification. |
| Last Online Tracker (k1ng0p) | Keeps and renders presence history at runtime. |
| ValidUser (k1ng0p) | Runtime account validation UI. |
| Plugin Browser (k1ng0p) | Adds a settings screen for plugin repositories. |
| Platform Indicators (k1ng0p) | Renders platform state in profiles and chat at runtime. |
| Local Edit Delete (k1ng0p) | Needs a local message archive and edit/delete actions. |
| Instant Actions (k1ng0p) | Adds runtime actions to message sheets. |
| Watchlist (k1ng0p) | Adds a watch list and its UI. |
| Profile Replies (k1ng0p) | Adds a profile reply action and UI. |
| Custom Voice Messages (LisaExists) | Records, stores and sends voice messages outside Discord's own flow. |
| Permission Viewer (lvwmwm) | Adds a permission viewer screen. |
| JumpToTop (tralwdwd) | Adds a scroll-to-top control; only its jump-to-present anchor is a portable hide. |
| SearchActionSheet (tralwdwd) | Adds a search action sheet. |
| ValencedQuickActions (tralwdwd) | Adds a quick-actions menu. |
| tralwdwdd plugin library (tralwdwd) | Shared helpers for that repository's plugins, not a patch of its own. |
| Bot Client (WolfPlugs) | Changes the gateway client identity. |
| AddAttachments (WolfPlugs) | Adds an attachment flow and UI. |
| ToyLink (WolfPlugs) | Rewrites links at runtime. |
| Sound Controls (WolfPlugs) | Per-user volume needs native audio routing and its UI. |
| Questify (WolfPlugs) | Mostly quest automation and data filtering; only the Bounties section is a clean component null. |
| PetPet (WolfPlugs) | Processes media at runtime. |
| NSFW Blur (WolfPlugs) | Blurs media at runtime. |
| MediaUtils (WolfPlugs) | Runtime media helpers and UI. |
| Global Badges (WolfPlugs) | Injects badges into profiles at runtime. |
| GIF Text (WolfPlugs) | Composes GIF text at runtime. |

The remaining Junk Cleanup and Declutter options likewise need runtime JSX interception or string
formatting: pruning the Settings tree, stripping channel-name emojis, and hiding individual
long-press rows or attach-panel buttons. They are not stubs of one function.
