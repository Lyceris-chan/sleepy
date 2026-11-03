# 349.5: intl strip, Spotify completion, experiment and ANR sweep

## 1. The intl cache — 8.25 MB, and a build bug that hid it

`res/raw/cache_intl_*` is **1741 files, 23.63 MB uncompressed / 7.26 MB
compressed**, covering 52 namespaces x 31 locales.

### The names decode

```
cache_intl_<base64(namespace).lower()>_<locale>messages<md5>compiledmessages.jsona
```

The namespace is base64 with the case flattened and padding stripped, which
makes it one-way — but it verifies in the other direction:

| namespace | base64 of |
|---|---|
| `aw50ba` | `intl` |
| `bw9kdwxlcy9wyxjlbnrfdg9vbhm` | `modules/parent_tools` |
| `bw9kdwxlcy9zbgf5zxjfc3rvcmvmcm9udc9pbnrs` | `modules/slayer_storefront/intl` |

`aw50ba` ("intl") alone is **21.40 MB of the 23.63**; the other 51 namespaces
are 2.22 MB between them. The content is the translated UI text:

```json
{"hPBScv":["Dismiss pop-up window"],"ZqK0uI":["Pop-up Window"], ...}
```

### Why removal looks safe

- **Nothing statically references them.** Not one smali method, no native
  library, not the Hermes bundle. Verified by taking an actual resource id
  (`0x7f120005`) and grepping every smali file: the only hit is its own
  declaration in `R$raw.smali`.
- **Discord ships `res/raw/keep.xml` listing all 1741.** A statically
  referenced resource does not need a shrinker keep-rule, so the existence of
  that list is itself evidence the lookup is by *constructed name*.
- **There is no `en`/`enus` catalog — only `engb`.** An en-US client (the
  default) already runs with no catalog for its own locale, so the
  fall-back-to-source path exists and is exercised today.

`DISCORD_STRIP_INTL` is now **on**, with `INTL_KEEP_LOCALES = {"engb"}`.
Keeping English gives up 0.63 MB of 23.63 (~3%) and bounds the worst case.
Revert is the one flag.

### The build bug

The first build after the strip changed the APK by **0.48 MB instead of
~7 MB** — because *nothing was actually removed*. apktool uses
`<base>/build/apk` as its staging tree and **packs from it without cleaning
it**, so every file a patch deletes from `res/` comes back from the previous
build's copy. All 1741 were still sitting in `build/apk/res/raw`.

`scripts/03-build.sh` now deletes `$BASE/build` before invoking apktool. With
that, the same patch gives:

| | before | after |
|---|---|---|
| APK | 142.51 MB | **134.26 MB** |
| `cache_intl` files | 1741 | 58 |

**8.25 MB saved.** This only affected res-file deletions (the intl strip was
the only one, and it was off), so no earlier build was wrong — but it would
have silently defeated any future patch that removes a resource.

## 2. Spotify — the last leak is closed

Already covered: `api.spotify.com` / `dealer.spotify.com` /
`/connections/spotify` / `/content-inventory/users/@me/spotify` at the
network layer, plus `fetchIsSpotifyProtocolRegistered`, `SpotifyTrack`,
`isListeningOnSpotify`, `subscribePlayerStateNotifications` stubbed in JS.

The one gap was the profile "recent activity" filter, whose stored
before-image no longer matched. Re-derived: the edit turns

```
00ad  Greater r1, r7, r0     ; entries.length > 0
```

into

```
00ad  Greater r1, r0, r0     ; 0 > 0, always false
```

byte-neutral, same instruction count, every label keeps its offset.

**349.5 has two copies of that predicate in the same module**
(`modules/user_profile/hooks/useUserProfileActivity.tsx`) where 348.2 had one.
`142676` is the exact structural match to the old `117345` (identical module
and closure chain); `113052` is the copy the React Compiler build added,
reachable through a different parent. Both are now edited — patching only the
first would have left Spotify on some surfaces. They need separate
before-images because their environment depth differs (1 vs 0).

## 3. Experiments

The React Compiler is the only new one — see `REACT_COMPILER_3495.md`. It is
implemented as `DISCORD_REACT_COMPILER`, off by default.

The four native experiment classes (`Fresco`, `Hermes`, `ChatMosaic`,
`JankPerScreen`) plus `mountingMode` were already pinned. Reading
`NativeExperiments.smali` in full turns up exactly **one method not covered**:

- `getRnFlagReparenting()` / `rnFlagReparenting_delegate$lambda$5` reads
  `2026-08-android-rn-reparenting-flag`, and feeds
  `MainApplication$reactNativeInitializationTask$1$1.enableFixForParentTagDuringReparenting()`.

  The polarity is inverted: treatment `<= 0` returns **true** (fix on),
  otherwise false. The experiment label is *"RN Flag, was false on RN 0.81 and
  true in RN 0.86, suspect of causing RMLE regressions"*, and this build forces
  RMLE on via `mountingMode = 3`. So the interesting arm is treatment 1 (fix
  off) — but that is a behavioural gamble with a regression risk, and the
  default 0 is what the app already does. **Left alone deliberately**; the lever
  is documented here so it can be tested rather than guessed at.

## 4. ANR / performance sweep

### Real, and now available: `SurfaceEglRenderer.surfaceDestroyed`

```java
checkIsOnMainThread();                    // runs on the UI thread
CountDownLatch latch = new CountDownLatch(1);
releaseEglSurface(latch::countDown);      // posted to the render thread
ThreadUtils.awaitUninterruptibly(latch);  // blocks the UI thread, no timeout
```

Stock upstream WebRTC, and deliberate — returning early could let the surface
be destroyed while the render thread is still drawing into it. But if the
render thread is wedged, which is exactly the state during a call that has
already failed, the **UI thread blocks behind it and ANRs**.

`DISCORD_BOUND_SURFACE_RELEASE` swaps the unbounded wait for the timed
overload that already exists in `ThreadUtils` (`awaitUninterruptibly(Latch,
long)`, 2 s). Ordering still holds in every normal case; only the pathological
case changes. **Off by default** — an early return narrows but does not remove
the window upstream blocks to close.

### Checked and rejected

- **`SurfaceViewRenderer.updateSurfaceSize`** builds a five-part log string
  with four integers on every layout pass, before checking whether anything
  changed — main thread, and it looked like a clean win. It is **dead code**:
  the block sits behind `enableFixedSize`, which is only ever written by
  `setEnableHardwareScaler`, and that method is **called from nowhere** in this
  APK (no smali, no bundle, no reflection). Patching it would have been a
  no-op.
- **`EglRenderer.setLayoutAspectRatio`** used to write logcat per layout pass.
  In 349.5 it is a plain field write under a lock. Fixed upstream.
- **`EglRenderer.release()`** blocks the caller on a `CountDownLatch` too, but
  is public API with real ordering semantics. Not touched.

## Verification

```
Hermes no-op'd at bytecode level: 137 function(s), 21 id(s) did not match
tools/verify-stubs.py:            PASS 137/158
intl:                             58 files remain (engb)
spotify edits:                    Greater r1, r0, r0 present in both
build:                            0 FAIL
```

The 21 remaining Hermes skips and the 8 body edits are unchanged from
`BUMP_3495.md`.
