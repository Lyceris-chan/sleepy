# Telemetry and upsell audit: Discord 354.1

Date: 2026-11-04. Scope: every analytics emitter and monetisation surface added
or renamed in 354.1, checked against the patch suite's chokepoints.

## Result

No new central telemetry sink bypasses the suite. The 354.1 analytics modules
added on top of the 349.5 set are emitters, not new transports, and every one
of them terminates in a function the suite already stubs:

- `AnalyticsUtils.track` and the promise-shaped `track` used by
  `trackMaker` (81313), plus `trackWithMetadata` (36098), cover the event
  emitters. This includes PinotAnalytics, SmartSearchAnalyticsManager,
  HexagonCampaignAnalytics, FriendsSidebarTracking, ActivitySessionAnalytics,
  AgeVerificationAnalyticsUtils, CustomTypingIndicatorAnalytics,
  BackPressTracking, ConjureAnalytics, ArtProfileAnalytics, and the ad-event
  builders.
- `MonitoringAgent.increment` (38240), `MetricsAggregator` and
  `SessionFlusher` cover the new per-session metric increments.
- `submitEventsImmediately` (81318) covers the queue that posts `/science` and
  `/beaker`.
- Sentry's JS API stubs, the removed native providers, and the blank DSNs cover
  the crash-reporting side. The browser INP instrumentation has no viable
  transport because `sentry.io` is host-blocked and the DSNs are blank.
- No new vendor SDK is packaged: no Braze, Statsig SDK, Amplitude, Adjust,
  Segment, Datadog SDK, Mixpanel, Bugsnag, New Relic, Crashlytics, or Facebook
  AppEvents.

## Gaps found and fixed

- **PayPal SDK analytics logger.** The bundle contains
  `https://www.paypal.com/xoplatform/logger/api/logger`. It is an SDK beacon,
  not a Hermes event, so the central stubs do not see it, and the previous host
  rules covered only `b.stats.paypal.com` and `c.paypal.com`. The blocklist now
  matches the narrow `www.paypal.com/xoplatform/logger` path rather than
  blocking the payment site.
- **Orbs Arcade endpoints.** `/orbs-arcade/games/{id}/enrollment` and
  `/reset` are new; the `/claim` path happened to match `/reward`. The feature
  is already unreachable because `useVirtualCurrencyMobileEnabled` returns
  `{enabled:false}`, but `/orbs-arcade` is now a blocklist rule for
  defence in depth.

## New monetisation surfaces

The audit found these 354.1 additions and the suite now hides them with the
same extra-promotion option as the older surfaces:

| Surface | Module | Action |
| --- | --- | --- |
| Nitro-locked toast | `checkpoint/native/showNitroLockedToast.tsx` | no-op |
| Guild-profile tag upsell | `guild_profile/native/components/GuildProfileCreateTagUpsell.tsx` | return null |
| Premium try-it-out modal | `profile_customization/native/PremiumTryItOutModal.tsx` | return null |
| Try-it-out opener | `profile_customization/native/openPremiumTryItOutModal.tsx` | no-op |
| Referral-program action sheet | `premium/referral_program/native/ReferralProgramEntryPointActionSheet.tsx` | return null |
| Orbs Arcade quest-home section | `orbs_arcade/native/OrbsArcadeQuestHomeSection.tsx` | return null |
| Hexagon sponsored header | `hexagon_campaign/native/HexagonCampaignQuestHomeHeader.tsx` | return null |
| Hexagon app-icon section | `hexagon_campaign/native/HexagonCampaignAppIconSection.tsx` | return null |
| Hexagon display-name promo | `hexagon_campaign/native/HexagonCampaignDisplayNameStylePromo.tsx` | return null |
| Hexagon display-name edit button | `user_profile/.../UserProfileHexagonCampaignDisplayNameStyleEditButton.tsx` | return null |
| Hexagon sheet | `hexagon_campaign/native/HexagonCampaignSheet.tsx` | return null |

The Hexagon experiment gates (`useIsHexagonCampaignEnabled`,
`getIsHexagonCampaignEnabled`) share their tiny bodies with unrelated config
readers, so the components are the safe stubbing point. The general theme
carousel and font picker are deliberately untouched.

The guild-tag upsell hook returns `{creatableGuilds, isUpsellVisible}`. The
hook keeps the guild list and forces only `isUpsellVisible` false, so the
inline upsell in `UserPrimaryGuildListBottomSheet` is hidden without removing
guild-tag editing.

Shop, collectibles, wishlist, quest, billing and subscription rows remain
covered by the existing predicates, route edits and API blocklist. The new
billing path spellings (`/purchase`, `/store`, `/authorize-payment`,
`/app-store-consent`, `/reactivation-offers`, `/activate-perk`) are now
blocked rather than relying on the older segment rules.
