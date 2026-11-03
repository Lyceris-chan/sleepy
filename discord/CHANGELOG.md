# Changelog

All notable changes to this build are recorded here.

This file follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the [Google developer documentation style guide](https://developers.google.com/style).
Versions are listed newest first. Each version is a signed, installable APK in
`dist/`.

Write entries for the reader, not for the author:

- One line per change. State what changed, not how it was found.
- Put the user-visible effect first. Keep implementation detail in
  `reference/`, and link to it.
- Use `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, and `Security`.
  Omit a heading when it has no entries.

## [354.1] - 2026-11-03

### Added

- **An interactive patch manager** (`tools/patches.py`) that lists patches,
  toggles options, and regenerates [`OPTIONS.md`](OPTIONS.md).
- **A keyboard-driven selector** (`tools/patches.py edit`): arrows move, space
  toggles, Enter shows details, `s` saves.
- **Remaining promotion surfaces are hidden**: the Nitro upsell pill, the
  contact-sync prompt, the group-chat Nitro banner, emoji and sticker search
  promotions, and the Appearance activity previews.
- **Quiet Super Reactions** stops looping decorations.
- **Hide list dividers** removes separator lines in settings lists.
- **Hide your typing indicator** stops outgoing typing events.
- **Keep empty DM calls open** removes the client-side three-minute timeout.
- **Static sticker previews** stops sticker animation.
- **Higher stream quality** exposes the high and mid stream tiers.
- **Higher video upload quality** exposes the increased upload quality tier.
- **Skip masked-link warnings** opens masked links without the warning.
- **Show blocked users' messages** expands blocked-message groups.

### Changed

- **Target Discord 354.1 Alpha (354201).** The manifest, README, and generated
  options now name this version.
- **Hermes targets were re-derived for the new bundle.** The profile-tab edits,
  wishlist index, markup cache bound, and the four Settings-row edits were
  regenerated against 354.1 bodies; all eight apply.
- **Smali targets follow 354.1's obfuscated dex.** Patches that used to name a
  class now find it by method descriptor or a distinctive string, and a target
  that no longer exists is reported as retired instead of failing the build.

### Fixed

- **The blocklist interceptor is registered as an application interceptor**
  again, so its local 204 responses are legal rather than a fatal OkHttp
  contract violation.
- **Calls keep the microphone/camera foreground-service type when
  backgrounded**, using 354.1's refactored `ServiceCompat` chain.
- **Opening a link always leaves Discord**, including the in-app URL method.
- **AppsFlyer's init, start, and deep-link subscription are inert.**
- **Contacts are never read**: the map and both photo helpers return empty or
  null while their React callbacks still settle.
- **The permanent logcat child process does not start.**
- **TelemetryRing's append and init are no-ops.**
- **The media engine scope survives a failing child.** It now uses a
  supervisor plus a coroutine exception handler, so a camera failure no longer
  cancels all media calls or reaches the process crash handler.

### Removed

- **Shared OkHttp cache memoisation** is retired; 354.1 has no provider class.
- **Native MonitoringAgent** is retired; 354.1 removed the class.
- **JankSessionRecorder** is retired; 354.1 removed the class.
- **Blocking OTA recovery** is retired; 354.1 removed ActivityDelegate.
- **Media-callback Sentry diagnostic** is retired; 354.1 has no such call.
- **Discord's EglRenderer fork edit** is retired; 354.1 uses stock WebRTC.
- **Spotify activity filter** is retired; the 354.1 filter shape changed.
- **Floating-nav Quests button** is retired; 354.1 no longer builds it.

## [349.5] - 2026-10-04

### Added

- **Open links in your browser** (on by default). Tapping a web link now hands
  it to your default browser instead of opening Discord's in-app tab. You can
  turn this off in the patch options; see [`OPTIONS.md`](OPTIONS.md).
- **Profile decorations are hidden.** Avatar decorations, nameplate gradients,
  profile frames, guild and clan tags, and the badge row no longer draw.
- **Quests are gone from Settings**, along with the Quests screen itself.
- **The guild shop is hidden.**

### Changed

- **Profile tabs show Main and Activity only.** The Board and Wishlist tabs are
  gone from your own profile and from other people's.
- **Unused translations are removed**, cutting 8.25 MB from the download.
- **Removed screens no longer leave dead buttons behind.** Tapping where they
  were does nothing.

### Fixed

- **The Wishlist and Board tabs survived on profile screens.** The patches that
  remove them were aimed at a copy of the code that never runs. See
  [`reference/WISHLIST_3495.md`](reference/WISHLIST_3495.md).
- **The wishlist suggestions grid was matched to the wrong function** — a data
  hook rather than the grid itself. Stubbing it would have handed its callers
  `null` where they expect a subscription result.
- **Turning the camera on could drop the call.** The media engine was still
  built on a job that cancels all 64 of its methods the first time any one of
  them fails, so a camera that failed to start left the call unable to recover.
  The fix for this stopped applying during the 349.5 update and reported a skip
  instead of failing. See
  [`reference/MEDIA_SCOPE_REGRESSION.md`](reference/MEDIA_SCOPE_REGRESSION.md).
- **Video calls built a log string every two seconds, per camera.** It was
  written to a sink nothing reads. The buffer the freeze-detection branch
  relies on is kept, so the camera still starts.
- **The message cache grew without limit.** Cached message formatting was never
  evicted, because its size cap was set to infinity and reading an entry
  refreshed it forever. It is now capped like Discord's other message cache.
- **Startup loaded a performance recorder it then never used.** The recorder's
  setup was already a no-op, but the class was still loaded at launch to call
  it.
- **Startup loaded seven exception classes for a feature that is switched
  off.** The crash reporter's initializer exists to build a list it only needs
  in order to decide what to report. Nothing is reported in this build.
- **An incoming call could freeze the app.** The incoming-call screen waited on
  a network image fetch, on the interface thread, before it drew. It now draws
  immediately and shows the caller's name without their avatar.
- **Crash recovery could freeze the app on launch.** After a crash, the app
  waited on an update check before it drew the first screen.
- **The app requested an emoji font it never uses** on every launch.

### Removed

- 1,683 unused per-locale resource files.

## [348.5] - 2026-09-28

### Added

- **Crash reporter removed at every layer** — the native library, the Java
  integration, and the JavaScript SDK. The two Sentry `.so` files are no longer
  packaged, saving 258 KB.
- **Five dead permissions removed** from the manifest: `READ_CONTACTS`,
  `BIND_GET_INSTALL_REFERRER_SERVICE`, `ACCESS_ADSERVICES_ATTRIBUTION`, and the
  Huawei and Samsung app-market permissions. Each was verified to have no
  reachable code behind it first.

### Fixed

- **The blocklist missed two purchase endpoints.** `/users/@me/claim-premium-collectibles-product`
  and `/users/@me/valid-collectibles-gift-recipients-batch` are now blocked.
- **The crash-reporter host rule did not match modern addresses**, so a regional
  reporting endpoint would have been allowed through.
- **A failed media connection could kill the whole media engine** for the rest
  of the session. One failed child no longer cancels its siblings.
- **Camera log spam** wrote two strings and a log line every two seconds per
  camera, for the duration of every video call.
- **Video calls now capture at 640×360 @ 24 fps** instead of 1280×720 @ 30,
  which is most of the battery and heat cost of a call.

## [347.5] - 2026-09-20

### Added

- `tools/remap_hermes.py`, `tools/verify-stubs.py`, `tools/telemetry-audit.py`,
  and `tools/smali-check.sh`. `verify-stubs.py` checks the stubs in the *built*
  APK rather than in the patcher's report, and exits non-zero on failure.

### Fixed

- **A new Discord version silently disabled two patches.** The OkHttp cache
  patch named an obfuscated class directly, and one stub shape used an opcode
  that does not exist. Both now resolve from the decompiled tree.
- **Function IDs are re-mapped automatically** after a version bump. All 94
  targets previously stopped matching at once.
- **A stall made the app stall harder.** The profiler that recorded a stalled
  JavaScript thread ran *because* the thread had stalled, on that thread.

### Changed

- **Removing a dead JSON.stringify from the gateway path was reverted.** It
  passed every static check and still crashed the app about a second after
  launch. Recorded in `reference/HBC_BODY_EDIT_HAZARD.md`.

## [346.2] - 2026-09-09

### Fixed

- **A live telemetry upload was still running.** The `client_telemetry`
  heartbeat posted to `/api/v9/beaker`, which was not blocked. The shared POST
  path behind both `/science` and `/beaker` is now stubbed.
- **Notifications were broken by the previous build**, which had disabled the
  push token request. Reverted.
- **The app froze on its loading screen.** Stubbing one function silently
  disabled 57 unrelated calls, because Hermes stores identical function bodies
  once. The patch suite now refuses to patch a shared body.
- **A stub check matched real code** and silently skipped the main analytics
  emitter.

### Removed

- **The link rewriter.** It hooked outgoing requests and stopped messages
  sending. Removed along with its patch file.

## [344.2] - 2026-08-26

- NoType implemented natively, with no mod loader.
- Blocklist refactored to a shared 204 response covering 34 rules.
- Root telemetry modules no-opped; log noise silenced.
- Privacy and stability patches carried over from 341.13.

## [342.14] - 2026-08-21

- Discord science killed, Spotify removed, quests stripped, Firebase Analytics
  transport removed, promotions stubbed.
- Resource monitors no-opped.

## [341.13] - 2026-08-21

- First build: crash reporter fully disabled, AppsFlyer disabled, advertising ID
  permission removed, Play split metadata stripped, ExoPlayer drawable aliases
  restored.
- Fixes for the LSPatch loader crash and a React Native exception storm.
