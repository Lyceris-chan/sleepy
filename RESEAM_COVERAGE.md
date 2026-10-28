# Reseam patch coverage

This is the audit of every patch in Reseam's Discord, Telegram and universal sets against
sleepy's 353.2.0 and OctoGram 3.6.1 patch sets. It is the record that nothing applicable was
left out, and the reason each remaining Reseam patch is not here.

Reseam targets Discord 347.12 and Telegram 12.10.6. sleepy targets Discord 353.2.0 and
OctoGram 3.6.1, so a Reseam target that was renamed, removed or inlined by those builds is
listed as not applicable instead of being written from another release's names.

Re-audited at Reseam commit `0ab5714` (2026-10-27, engine 0.33.0). Every `patch("...")` name in
its Discord, Telegram and universal sets is still listed below; the latest Discord additions are
the 2026-10-13/14 Custom themes, Server details, Draft preview, Jump to first message, Colored
names, Moderator badges, Better stream quality and Add source cards patches, all of which are
either included or require the Reseam runtime. The Hide typing indicator patch's second target,
`sendTyping`, no longer exists in 353.2.0 under that name or as a string cross-reference; the
353 path is `handleTypingStartLocal`, which sleepy stubs.

## Discord

| Reseam patch | Status in sleepy |
| --- | --- |
| Add source cards | Not applicable — needs Reseam's JavaScript runtime and UI extension. |
| Better stream quality | **Higher stream quality** (JavaScript stub `canStreamQuality`). |
| Better video quality | **Higher video upload quality** (JavaScript stub `canUseHighVideoUploadQuality`). |
| Block analytics | **Analytics events** (`handleTrack`, id 71544). |
| Block crash reports | Crash-reporting native sets no-op the Java entry points, breadcrumbs and native report paths, including libdiscore's native Sentry reporter and crash handler. |
| Chat animation switch | **Message cards and animation** — the combined switch forces `animateEmoji`/`gifAutoPlay` false. |
| Clean outgoing links | Not applicable — needs the JavaScript link-rewriting extension. |
| Colored names | Not applicable — needs the JavaScript name-rendering extension. |
| Custom themes | Not applicable — needs Reseam's JavaScript/native theme runtime. |
| Developer menu | Not applicable — needs Reseam's JavaScript settings-row extension. |
| Disable message swipes | **Message swipe actions** (`useIsMessageSwipeActionsEnabled`, both copies). |
| Draft preview | Not applicable — needs Reseam's JavaScript send-button extension. |
| Freemoji | Not applicable — needs the JavaScript parse/send rewriting, not only the entitlement predicate. |
| Hide activity invites | **Message cards and animation** — combined switch forces the activity-invite option reads false. |
| Hide contact sync prompt | **Other upsell prompts** / contact-sync stubs (`ContactSyncUpsellCTA`, ids 70291/70292). |
| Hide gift button | **Gift buttons** (`ChatInputActionButtonGift`, ids 63929/63930). |
| Hide gift cards | **Message cards and animation** — combined switch forces `renderGiftCode` false. |
| Hide group chat promotion | **Nitro upsells** (`GroupDMNitroUpsellBanner`, ids 79943/79944). |
| Hide link cards | **Message cards and animation** — combined switch forces the link/embed card option reads false. |
| Hide Nitro buttons | **Other upsell prompts** (`PremiumFeatureUpsellPill`, ids 52994/52995). |
| Hide picker promotions | **Emoji and sticker upsells** (`PremiumExpressionPickerSearchUpsell` and `PremiumFeatureUpsell`, ids 52965/52966 and 52996/52997). |
| Hide quests | **Quests in the app** — expanded with the eligibility predicate, the dock, the activity button and the rendered-height hooks (ids 51460, 74246, 74252, 74253, 74695, 74696, 80963). |
| Hide reactions | **Message cards and animation** — combined switch forces `renderReactions` false. |
| Hide reply previews | **Message cards and animation** — combined switch forces `renderReplies` false. |
| Hide shop entry | **Shop buttons and prompts** (`CollectiblesShopEntryButton`, ids 67250/67251) plus the shop route gates. |
| Hide theme activity previews | **Appearance activity cards** (`ActivityCardsItem`, ids 75117/75118). |
| Hide typing indicator | **Typing status you send** (`handleTypingStartLocal`, id 63701). |
| Instant chat jumps | Not applicable — 353.2.0 no longer has the `ChatListView.scrollToPosition` target. |
| Jump to first message | Not applicable — needs Reseam's JavaScript button extension. |
| Keep deleted messages | Not applicable — needs Reseam's JavaScript message-history runtime. |
| Local edit history | Not applicable — needs Reseam's JavaScript message-history runtime. |
| Local read state | Not applicable — needs Reseam's JavaScript read-state rewrite. |
| Local themes | Not applicable — needs Reseam's JavaScript theme picker/runtime. |
| Moderator badges | Not applicable — needs Reseam's JavaScript tag-rendering extension. |
| Precise timestamps | Not applicable — needs Reseam's JavaScript timestamp formatting. |
| Premium app icons | Not applicable — Reseam maps the JavaScript icon objects to clear `isPremium`; bytecode cannot reproduce that mapping. |
| Quiet Super Reactions | **Super Reaction animations** (`SuperReactionLocalImageAnimation`, ids 52769/52770). |
| Replies without pings | **Replies without pings** (`createPendingReply` and `createShallowPendingReply`). |
| Reveal spoilers | **Spoiler text and media** (ids 48533, 48534, 48535). |
| Server details | Not applicable — needs Reseam's JavaScript sheet extension. |
| Show blocked messages | **Messages from blocked users** (`isBlockedForMessage`, id 32823). |
| Skip masked link warnings | **Masked link warnings** (both `isLinkTrusted` copies, ids 48870/53244). |
| Static chat emoji | **Message cards and animation** — combined switch forces `animateEmoji` false. |
| Static chat GIFs | **Message cards and animation** — combined switch forces `gifAutoPlay` false. |
| Static stickers | **Animated stickers** (`shouldAnimateSticker`, id 37655). |
| Sticker links | Not applicable — needs the JavaScript sticker/link rewriting. |
| Keep patched bundle | **Stop the app replacing its JavaScript** (`BUNDLE_LOCK` native set). |

## Telegram

| Reseam patch | Status in sleepy |
| --- | --- |
| Hide sponsored messages | **Block sponsored posts in channels**, **Remove the ads between photos**, **Filter sponsored channels out of search**. |
| Disable auto-update | **Stop the built-in update check**. |
| Unlock Premium | **Unlock premium features** — `cld.p()` returns true. Server-checked features still need a real subscription. |
| Hide typing indicator | **Hide typing notifications** (`MessagesController.u4`, `a28.smali`). |
| Download speed boost | Not applicable — the 3.6.1 build has neither `updateParams` nor the 2,097,152,000 literal. |
| Save from restricted chats | Not applicable — 3.6.1 has no `isChatNoForwards` / `isUserNoForwards` / `isPeerNoForwards` gates; the checks are inlined into UI code. |
| Remove Play Store restrictions | Not applicable — 3.6.1 has no `ApplicationLoader.isStandaloneBuild` call to redirect. |
| Recover deleted messages | Not applicable — needs Reseam's runtime archive and message store. |
| Forward from restricted chats | Not applicable — needs Reseam's runtime forward bridge. |
| Allow screenshots in secret viewers | **Allow screenshots in secret viewers** — clears FLAG_SECURE from the viewer's window flags. |
| Bypass integrity | **Bypass integrity checks** — both SafetyNet verdicts report true. |
| Fix push notifications | Not included — depends on Reseam's original-signature spoof; sleepy re-signs the APK and does not embed that runtime. |

## Universal

| Reseam patch | Status in sleepy |
| --- | --- |
| Spoof signature | Not applicable — requires redirecting the app's `PackageManager` signature lookups to the original certificate; sleepy does not embed that runtime. |
| Report Play Store as installer | Not applicable — neither target has Play-Store-only restrictions to unlock. |
| Remove Pairip | Not applicable — neither target contains `com.pairip` classes. |
| Remove ads | Not applicable — neither target contains an AdMob/ad-network SDK; Discord's own ads are handled by the network blocklist and OctoGram's by the sponsored sets. |
| Override certificate pinning | Deliberately not included — it weakens TLS for the patched app. |
| Disable Play Store updates | Covered by the bundle-lock and update-check patches. |
| Enable debugging | Deliberately not included — it makes the patched app debuggable. |
| Hide mock location | Not applicable — neither target checks mock location. |
| Change package name | Covered by sleepy's clone feature. |

## Verification

- Discord native set: **105 applied, 0 skipped** on the real 353.2.0 APK.
- Discord JavaScript table: **620 entries**, all read back by real Hermes with **0 rejected**.
- OctoGram: **20 sets, 23 items, 48 edits**, all applied and reassembled by the payload test.
- Full unit suite: 286 tests, 0 failures (17 fixture-dependent tests skip when a split or fixture
  APK is not present locally).
