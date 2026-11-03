# Options

This is the option list for Discord 354.1 - Alpha (`com.discord`).

Change an option with the patch manager. It writes `options.json`; the next `scripts/02-patch.sh` run applies it:

```bash
tools/patches.py edit                 # interactive list
tools/patches.py set EXTERNAL_BROWSER off
scripts/02-patch.sh build/<version>
scripts/03-build.sh  build/<version>
```

`tools/patches.py list` prints the same list without the detail.
Core patches are always enabled; if a target moved, the patch log
reports a skip instead of guessing.

A per-build `build/<version>/options.json`, when present, takes
precedence over `discord/options.json`.

## Quick reference

### Privacy & tracking

What the app reports, stores, and asks the system for.

| Patch | Default | Current | Risk | Option |
| --- | --- | --- | --- | --- |
| Hide your typing indicator | Off | Off | warning | `DISCORD_HIDE_TYPING_INDICATOR` |

### Ads & monetisation

Storefront, Nitro, quests, orbs, and promotion surfaces.

| Patch | Default | Current | Risk | Option |
| --- | --- | --- | --- | --- |
| Hide remaining promotion surfaces | On | On | safe | `DISCORD_HIDE_EXTRA_PROMOTIONS` |

### Calls & stability

Voice, video, foreground-service, and main-thread behaviour.

| Patch | Default | Current | Risk | Option |
| --- | --- | --- | --- | --- |
| Keep empty DM calls open | Off | Off | test on device | `DISCORD_DISABLE_CALL_IDLE` |
| Create video surfaces off the main thread | On | On | test on device | `DISCORD_EGL_NONBLOCKING` |
| Stop incoming calls freezing the interface | On | On | test on device | `DISCORD_INCOMING_CALL_NO_BLOCK` |
| Cap the video surface teardown wait | Off | Off | test on device | `DISCORD_BOUND_SURFACE_RELEASE` |
| Lower video capture resolution | Off | Off | experimental | `DISCORD_LOWER_CAPTURE` |

### Interface

What the app shows and how it opens links.

| Patch | Default | Current | Risk | Option |
| --- | --- | --- | --- | --- |
| Open links in your browser | On | On | safe | `EXTERNAL_BROWSER` |
| Quiet Super Reactions | On | On | safe | `DISCORD_QUIET_SUPER_REACTIONS` |
| Hide list dividers | Off | Off | safe | `DISCORD_HIDE_LIST_DIVIDERS` |
| Skip masked link warnings | Off | Off | warning | `DISCORD_SKIP_MASKED_LINK_WARNINGS` |
| Show blocked users' messages | Off | Off | warning | `DISCORD_SHOW_BLOCKED_MESSAGES` |
| React Compiler components | Off | Off | experimental | `DISCORD_REACT_COMPILER` |

### Media & data

Animations, uploads, streams, and translations.

| Patch | Default | Current | Risk | Option |
| --- | --- | --- | --- | --- |
| Static sticker previews | Off | Off | safe | `DISCORD_STATIC_STICKERS` |
| Higher stream quality | Off | Off | warning | `DISCORD_BETTER_STREAM_QUALITY` |
| Higher video upload quality | Off | Off | warning | `DISCORD_BETTER_VIDEO_QUALITY` |
| Remove unused translations | On | On | test on device | `DISCORD_STRIP_INTL` |

### Build & diagnostics

Packaging, logging, and developer instrumentation.

| Patch | Default | Current | Risk | Option |
| --- | --- | --- | --- | --- |
| Remove the camera frame-rate log | On | On | test on device | `DISCORD_STRIP_CAMERA_FPS_LOG` |
| Quiet the media engine logs | Off | Off | experimental | `DISCORD_LOWER_MEDIA_LOGLEVEL` |

## Core patches

These are always applied.

- **Remove crash reporting** (safe). Sentry is dead at every layer: native libraries, Java integration, and the JavaScript SDK.
- **Remove telemetry and analytics** (safe). Analytics events, metrics, jank reports, NetStats, watchdog traces, and the telemetry ring are stubbed.
- **Disable ad and attribution SDKs** (safe). AppsFlyer, Google Analytics, Firebase Analytics, Install Referrer, and the advertising ID are disabled.
- **Close privacy side channels** (safe). No address-book read, no installed-app enumeration, no exported Discord RPC service, and dead permissions removed.
- **Block tracking and storefront endpoints** (safe). A rebuilt OkHttp interceptor answers analytics, storefront, quest, promotion, and Spotify requests with a local 204.
- **Keep the patched JavaScript bundle** (safe). Discord cannot replace the patched Hermes bundle with an over-the-air download.
- **Remove Shop, Quests, Orbs, and Nitro surfaces** (safe). Storefront and engagement screens are hidden or nulled, and their entry points stop rendering.
- **Remove the Spotify integration** (safe). Spotify calls and track rendering are blocked; a friend's already-cached activity row can still appear.
- **Keep calls alive and responsive** (test on device). Foreground-service type, call-path overhead, EGL statistics, and camera-log fixes are applied.
- **Reduce startup and background work** (safe). Debug logging, logcat capture, resource monitors, and dead startup loads are removed.

## Option details

### Privacy & tracking

#### Hide your typing indicator

`DISCORD_HIDE_TYPING_INDICATOR` · default **off** · currently **off** · warning

Stops outgoing TYPING events, so other people do not see you typing.

This changes what other people see. It does not hide other people's typing from you.

### Ads & monetisation

#### Hide remaining promotion surfaces

`DISCORD_HIDE_EXTRA_PROMOTIONS` · default **on** · currently **on** · safe

Hides Nitro upsell pills, the contact-sync prompt, group-chat Nitro banners, picker promotions, and activity previews.

Complements the core Shop/Nitro/Quests removal. Each surface is a separate component and is stubbed independently.

### Calls & stability

#### Keep empty DM calls open

`DISCORD_DISABLE_CALL_IDLE` · default **off** · currently **off** · test on device

Stops Discord from leaving an empty DM call after three minutes.

The idle disconnect still runs on Discord's servers; this only removes the client-side trigger.

#### Create video surfaces off the main thread

`DISCORD_EGL_NONBLOCKING` · default **on** · currently **on** · test on device

Stops a video tile from freezing the interface while its EGL surface starts.

Pairs the off-thread post with a released-guard, so a tile closed mid-start cannot leak an EGL context.

#### Stop incoming calls freezing the interface

`DISCORD_INCOMING_CALL_NO_BLOCK` · default **on** · currently **on** · test on device

Draws the incoming-call screen immediately instead of waiting on a network avatar fetch.

Costs the caller's avatar on that screen; the name still shows. Keeping the avatar needs a background-thread restructure.

#### Cap the video surface teardown wait

`DISCORD_BOUND_SURFACE_RELEASE` · default **off** · currently **off** · test on device

Bounds the UI-thread wait when a video tile closes, so a stuck render thread cannot hang the interface.

Trades ANR risk for a narrow ordering race. Test join, leave, rotate, and backgrounding during a call.

#### Lower video capture resolution

`DISCORD_LOWER_CAPTURE` · default **off** · currently **off** · experimental

Requests 640x360 at 24 fps instead of 1280x720 at 30 fps.

Do not enable alone: capture and encode share one budget, and lowering capture without the encoder budget crashes the camera on open.

### Interface

#### Open links in your browser

`EXTERNAL_BROWSER` · default **on** · currently **on** · safe

Web links open in your default browser instead of Discord's in-app tab.

Discord already ships the external-browser path. Nothing new runs; the app does not select the in-app tab.

#### Quiet Super Reactions

`DISCORD_QUIET_SUPER_REACTIONS` · default **on** · currently **on** · safe

Stops looping Super Reaction decorations from animating over messages.

#### Hide list dividers

`DISCORD_HIDE_LIST_DIVIDERS` · default **off** · currently **off** · safe

Removes the thin separator lines between settings and other list rows.

Cosmetic. The divider component is replaced with a null renderer; the rows themselves are untouched.

#### Skip masked link warnings

`DISCORD_SKIP_MASKED_LINK_WARNINGS` · default **off** · currently **off** · warning

Opens masked links without Discord's destination warning.

The warning exists to stop phishing. Leave this off unless you accept that risk.

#### Show blocked users' messages

`DISCORD_SHOW_BLOCKED_MESSAGES` · default **off** · currently **off** · warning

Shows messages from blocked users without unblocking them.

#### React Compiler components

`DISCORD_REACT_COMPILER` · default **off** · currently **off** · experimental

Runs Discord's compiled component variants instead of the uncompiled ones.

Selects a code path Discord has not shipped to users. It also brings back the Wishlist and Board profile tabs.

### Media & data

#### Static sticker previews

`DISCORD_STATIC_STICKERS` · default **off** · currently **off** · safe

Uses still sticker previews and chat images instead of animated ones.

#### Higher stream quality

`DISCORD_BETTER_STREAM_QUALITY` · default **off** · currently **off** · warning

Reports the Nitro high and mid stream tiers as available.

Discord can reject the tier on its side. This does not grant an entitlement; it only stops the client from gating the option.

#### Higher video upload quality

`DISCORD_BETTER_VIDEO_QUALITY` · default **off** · currently **off** · warning

Reports the increased video upload quality tier as available. Upload limits are unchanged.

Discord can reject the tier on its side. This does not grant an entitlement.

#### Remove unused translations

`DISCORD_STRIP_INTL` · default **on** · currently **on** · test on device

Deletes translation catalogs for languages the app never loads, cutting several MB from the download.

Keeps British English. If text is blank or the app fails to start, turn this off and rebuild.

### Build & diagnostics

#### Remove the camera frame-rate log

`DISCORD_STRIP_CAMERA_FPS_LOG` · default **on** · currently **on** · test on device

Stops the video engine building a log line every two seconds per camera for a sink nothing reads.

The tag constant used by freeze detection is kept. Removing it previously crashed the camera on open.

#### Quiet the media engine logs

`DISCORD_LOWER_MEDIA_LOGLEVEL` · default **off** · currently **off** · experimental

Passes a non-debug log level to the native voice and video engine.

The value is undocumented and is a suspect for the instant camera crash. Leave it off unless testing.
