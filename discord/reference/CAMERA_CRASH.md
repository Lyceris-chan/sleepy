# Camera turns on → app closes instantly

## RESOLVED 2026-09-27 — cause was `CameraStatistics$1`, and the analysis below was wrong

`adb logcat -b crash` on the device:

```
FATAL EXCEPTION: DiscordTextureCapture
java.lang.VerifyError: Verifier rejected class
  org.webrtc.CameraVideoCapturer$CameraStatistics$1:
  run() failed to verify: [0x29] register v1 has type Undefined
  but expected Reference: java.lang.String
    at CameraVideoCapturer$CameraStatistics.<init>(SourceFile:2)
    at CameraCapturer$1.onDone(SourceFile:83)
    at Camera2Session$CaptureSessionCallback.onConfigured(SourceFile:159)
```

Deleting the fps-string block also deleted `const-string v1, "CameraStatistics"`,
which the **freeze-detection branches further down reuse as the tag** for their
own `Logging.e` calls. ART then rejects the class **at load**, and
`CameraStatistics.<init>` runs the moment the camera opens. That is the instant
crash.

**The "Eliminated: the CameraStatistics edit" section below is WRONG.** It argued
the edit could not be the cause because `run()` is reposted with
`postDelayed(2000)`, so it cannot fire for two seconds. That constrains when
`run()` **executes** - not when the class is **verified**. Verification happens at
first use, which is construction. Stated confidently, and it sent the
investigation down two dead ends.

Reverted. `DISCORD_STRIP_CAMERA_FPS_LOG = False`, and it must stay off: the
saving was a discarded log line, and it cost four broken builds.

## Original (superseded) analysis follows

Two candidates were eliminated by reasoning rather than guessing, then one was
left standing and reverted.

## Eliminated: the Hermes bundle

`hermes-decomp bin-diff` (pristine vs patched) is the tool that settles this:

```
Identical: 41014     Modified: 91     Removed: 0     Added: 0
```

Every one of the 91 is a function the patch suite names. Nothing was changed
that we did not intend, and nothing was corrupted — which also rules out
`patch-function` shifting data underneath a lazily-loaded module (Metro modules
load on demand, so a corrupted module *would* surface as a crash exactly when
its feature is first used).

Header comparison confirms it: identical function/string/identifier counts and
the same `Instruction offset` (13,706,128) in both bundles, 29,712 bytes smaller
from the stubs.

## Eliminated: the CameraStatistics edit

`CameraVideoCapturer$CameraStatistics$1.run()` is the 2-second stats timer, and
its body is reposted with `postDelayed(p0, 2000)`. Even a completely broken body
could not fire before the camera has been running for two seconds. The user's
crash is **instant**, so this edit cannot be the cause. It stays applied.

## Eliminated (earlier): the capture resolution

`getVideoQuality` builds a *pair* — `obj.capture` from `options.videoCapture`,
`obj.encode` from `ladder.getResolution()` — and the ladder is derived from
`options.videoBudget`, still 1280x720. Lowering capture alone makes
capture < encode, which is a genuine inconsistency, and it was reverted. But the
crash persisted after that revert, so it was real but not the cause.

## Standing: the native log level

`MediaEngine.getEngine` constructs `new NativeEngine(context, 2)`, and
`NativeEngine.<init>` passes that int **straight into
`nativeCreateInstance(context, egl, logLevel, offloadAdmControls)`** with no
validation anywhere in between.

`patch_media_log_level` changed the argument to `0`. The patch's own docstring
said so:

> 0 is not a named constant — Discord only defines 1 and 2 — so the semantics of
> "below DEBUG" are inferred, not documented. It is the conventional encoding
> for "no logging", but it is not proven.

Discord defines `LOGLEVEL_DEBUG = 1` and `LOGLEVEL_DEFAULT = 2`. `0` is outside
the documented set and goes into native unchecked. Native code that indexes a
table, switches exhaustively, or asserts on the level would abort — and engine
construction is one of the few things that happens **instantly** when media
starts.

**Reverted** to `const/4 v3, 0x2`, and the patch is now opt-in
(`DISCORD_LOWER_MEDIA_LOGLEVEL = False`) so it cannot come back by accident.

### Why this also explains "voice probably still works"

It does not. If engine construction is what aborts, **any** media would crash,
not just video. The likely reason it looks camera-specific is that the engine is
created on first media use and the user has not made a voice call since
installing. Worth testing a voice call on the next build: if voice *also*
crashes, this diagnosis is confirmed; if voice works and only the camera
crashes, the log level is exonerated and the next suspect is the foreground
service type chain.

## Lesson applied

`bin-diff` should have been the first tool reached for, not the last. It answers
"what did we actually change" in one command and would have eliminated the
bundle in one step instead of three rounds of speculation.
