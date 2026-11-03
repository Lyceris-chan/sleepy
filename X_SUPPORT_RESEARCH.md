# X support: patch sources and a first port

Research date: 2026-11-03. The app is named **X** throughout; its package
remains `com.twitter.android`.

## What exists

| Source | X patches | Target X version | Format |
| --- | ---: | --- | --- |
| Reseam | 26 named + 2 internal | 12.34.0-prod.01 | Kotlin patch code, Kotlin extensions |
| Piko (legacy) | 75 | 12.19.1-release.0 | Kotlin + Java extension, `.mpp` |
| Piko NewX | 52 | 12.39.0-prod.01 | Kotlin + Java, `.mpp` |
| ReVanced | 7 | 10.60.0 / 10.86.0 (legacy) | Kotlin + Java extension |
| Serajr | 7 | 12.29.1 / 12.31.0 / 12.33.0 | Kotlin + Java extension |
| Kveld9 Extra | 2 | 12.37.2 | Kotlin + Java |
| Xittered, AetherMagee, Sylonin | 1-2 each | 12.19.1 or 12.39.0 | Kotlin + Java |

Morphe's official bundle has no X patches. The Morphe-compatible X bundles are
Piko and Piko NewX, not Morphe itself.

Orion Store has no patch engine or patch code for X. Its catalog points at
prebuilt Piko-patched APKs hosted in the `communitybuilds/Community-builds`
GitLab releases. It distributes APKs; it does not patch.

## Recommended first port: Reseam 12.34.0-prod.01

Port behavior and matchers, not copied code. Reseam is AGPL-3.0-or-later; Piko
and Morphe are GPL-3.0. Copying their source changes sleepy's license. Any
port should be a clean-room implementation with attribution in
`THIRD_PARTY_NOTICES.md` if matcher text is reused.

Start with patches that are pure DEX edits or feature-switch values:

1. Feature-switch override hook:
   `com.x.featureswitches.FeatureSwitchesRepositoryImpl.getFeatureSwitchValue(Ljava/lang/String;Z)Ljava/lang/Object;`.
2. Hide promotion prompts: `x_lite_quick_promote_enabled`,
   `android_tweet_promote_button_boost_cta_creation_enabled` and the four
   related render/label switches.
3. Hide notification prompts: `x_lite_notifications_permission_prompt_enabled`,
   `..._fullscreen_prompt_enabled`, `..._banner_enabled`, plus the boolean
   method containing `pending_token_checkin`.
4. Hide Premium upsells: the 27 `subscriptions_upsells_*` switches and
   `premium_paywall_on_app_load_journey_enabled`.
5. Privacy: the worker whose class strings include `com.anthropic.claude` and
   `com.openai.chatgpt`; make `doWork` return `Result.success()`, and disable
   the three ad-tracking switches.
6. Hide new posts banner: force false before `IPUT_BOOLEAN` for the data class
   whose `toString` contains `", isEligibleToShowPill="`.
7. Hide compose button: return early in the method reading
   `ComposerFabState(secondaryActions=` and taking
   `Landroidx/compose/runtime/Composer;`.
8. Hide view counts: the `ViewCount` enum composable and the `view_count`
   resource path.
9. Download videos: force `", isDownloadable="` true and set
   `subscriptions_watermarked_video_download_enabled`.
10. Default to Following: return the `Following` static instance from the
    `PreferredTabRepo` method and set the two back-to-feed switches.
11. Hide ads: set `android_x_lite_ssp_ads_enabled` and
    `ssp_ads_home_enabled`, and hook the SQLite `prepare(String)` used by
    `TimelineView` to add `promoted_metadata IS NULL`.
12. Hide Live bar: return early in the caller that constructs the live bar
    after the `content_description_fleetline` string.
13. Unlock Premium (optional, legal review): add
    `feature/twitter_blue_verified` to the `SharedPreferences.getStringSet`
    result for `subscriptions`.

Defer anything that injects UI or handles credentials: Save media, Download
button, Import/export login, Reseam/X settings entry, Themes, Share links, and
Customize navigation bar.

## Release and legal constraints

- Do not ship or mirror prebuilt X APKs. Patch code is the safe artifact.
- X's terms prohibit reverse engineering and circumventing technical limits.
  Premium, watermark, and download restrictions carry extra risk. Get a legal
  review before enabling those switches by default.
- Keep source licenses compatible: Reseam AGPL-3.0-or-later, Piko GPL-3.0,
  ReVanced GPL-3.0. Use clean-room implementations derived from behavior and
  matchers, and keep attribution.
- Do not use the Reseam, Morphe, Piko, or ReVanced names or logos in sleepy's
  branding.

## What sleepy needs

1. A source entry for X 12.34.0-prod.01 with a verified APK URL and SHA-256.
2. A version gate that fails closed on any other X build.
3. A `XPatches` patch set and UI sections named for X.
4. Tests that assert every anchor string is present in the target APK before a
   patch is written.
5. A device pass for each default-on patch; feature switches can hide screens
   in ways static analysis cannot prove.
