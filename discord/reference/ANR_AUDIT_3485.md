# ANR / call-freeze audit — 348.5 (alpha3482)

Follow-on to `VOICECALL_FREEZE.md` and `CALL_DISCONNECT.md`. Every finding below
was traced to bytecode in the alpha3482 patched tree, not inferred. Findings
that were checked and **rejected** are listed too — they exist to stop the same
false lead being re-investigated.

The original ANR never captured a Java stack (`libdebuggerd_client: failed to
read status response from tombstoned`), so nothing here is anchored to an
allocation site in that trace. These are mechanisms that *can* produce the
symptom, ranked by evidence strength.

## Verification status

| # | Finding | Verified how | Fixed? |
|---|---|---|---|
| 1 | `IncomingCallActivity` UI-thread `runBlocking` | 3 call sites at 242/266/475; `initializeFresco` at 308, suspending `fetchDecodedImage` at 333 | **yes** — `fetchImage` stubbed (`DISCORD_INCOMING_CALL_NO_BLOCK`); costs the caller avatar |
| 2 | `MediaEngineNativeConnections` throw + plain `Job` scope | `if-nez` guard re-read at instruction level; `L0/x0`=JobImpl vs `L0/m1`=SupervisorJobImpl; `L0/x;->b` does `context[Job] ?: Job()` | **yes** — supervisor scope. But the fix **silently stopped applying on the 349.5 bump** (the Kotlin package was renamed `nr`→`mr`) and the 349.5 build shipped without it. Re-applied and made name-resolving; see `MEDIA_SCOPE_REGRESSION.md` |
| 3 | `MarkupParsers` LRU `max: Infinity` | decompiled: `{max: Infinity, maxAge: 15*MINUTE, updateAgeOnGet: true}` | **yes** — `max` clamped to 2000 after construction (the value is in an object buffer, so it cannot be swapped as an operand) |
| 4 | preview-markup cache key | decompiled: `closure_1_3.get(content)` only | no |
| 5 | IDENTIFY `stringify`+`deflate` | writer 1590 B in both bundles; reader 509 → 5 B | no — crashes (347.5) |
| 6 | `CameraStatistics` 2 s fps log | `Logging.d` → `java.util.logging` fallback builds a 2nd StringBuilder; `CameraCapturer$1:184` constructs it; `NativeCapturerObserver` → `CameraEnumeratorProvider.createCapturer` is live | **yes** — safe version: the string build and `Logging.d` are removed, the `const-string "CameraStatistics"` tag the freeze branch reuses is KEPT. The earlier attempt deleted the tag and failed ART verification at class load |

## Confirmed, not yet fixed

### 1. `IncomingCallActivity` blocks the UI thread on nested `runBlocking`

`com/discord/notifications/renderer/IncomingCallActivity.smali` calls
`runBlocking` (`Lnr/x;->x(Function2)`) at **three** sites — lines 242, 266 and
475 — from `onCreate`/`initView`/`configureUser`. Behind them:

- `:308` `FrescoModuleDiscord$Companion.initializeFresco` — full image-pipeline
  construction on the calling thread.
- `:333` `FrescoFetchDecodedImageKt.fetchDecodedImage` — a **suspending network
  image fetch**. Under `runBlocking` the calling thread is parked until the
  image round-trips *and* decodes.

So an incoming call performs image-pipeline init plus a blocking network wait on
the main thread. Reachable: `NotificationRenderer.getFullscreenIntent` names the
activity, and it is declared in the manifest (exported, `showOnLockScreen`).

Severity: high on the *first* incoming call, when Fresco is still cold. Fires
exactly when a call arrives, which is when the freeze is reported.

Fix shape: the `runBlocking` sites are fire-and-forget (return value unused) —
move them to a background dispatcher. Minimum-risk subset: bound the wait.

### 2. `MediaEngineNativeConnections` throws on duplicate registration

`MediaEngineNativeConnections.smali:182` `Map.containsKey` → `:190`
`const-string "Check failed."` → throws `IllegalStateException`. It also throws
at `:415`.

`MediaEngineModule.appScope` is built with `Lnr/x;->b(...)`, which installs a
plain `Job` (no `childCancelled` override) — **not** a `SupervisorJob`. So the
first uncaught throw cancels the scope, and every later `appScope.launch`
silently no-ops. That is across all ~40 media ReactMethods.

Because the throw happens *inside* the coroutine, JS is never told the
connection failed. This is a plausible mechanism for "the media path dies and
stays dead while the gateway still reports connected".

Fix shape: make `register` non-throwing (`removeAndDispose` + `put` + log)
rather than patching the coroutine scope builder.

### 3. `MarkupParsers` LRU has no size bound

`markup/MarkupParsers` (fn 7536):

```js
const obj = { max: Infinity, maxAge: 15 * MINUTE, updateAgeOnGet: true };
```

`max: Infinity` makes `trim()` early-return, so nothing caps the entry count.
`updateAgeOnGet: true` refreshes the clock on every read, so **frequently-read
content never expires at all**. These caches are on the message-render path.

This is GC pressure of the shape the ANR shows. Nothing in `patches/core.py` or
`patches/blocklist.py` mentions LRU or the markup path.

Fix shape: set a finite `max`.

### 4. Context passed to the message-preview cache key, but not parsed

`getOrParseMessagePreviewMarkupAST` (fn 50313) caches on **`content` alone**:

```js
const value = closure_1_3.get(content);
```

…while the parse it caches depends on `layout`, `color`, `fontScale`,
`maxFontSizeMultiplier`, and — through `hydrateUserMention` — the resolved
mention. The mention resolution bakes the literal `"@Name"` into the AST and
**falls back to the username** when `NicknameUtils.getNickname` returns null.

So two different contexts with identical message text collide, and the cached
AST can carry the *username* where the display name belongs. Reachable from
`ChannelSubtitle` on channel-list scroll.

This is the mechanism behind "the channel list sometimes shows their username
instead of the display name". **Correctness, not GC** — the cache is otherwise
bounded (2000).

Fix shape: include the omitted context in the cache key.

### 5. Gateway IDENTIFY still stringifies and deflates on every connect

Writer fn 117649 is 1590 bytes in both the pristine and patched bundles and
still runs `JSON.stringify` (`:149`) and `.deflate(...)` (`:153`). Its reader,
`logReadyPayloadReceived` (fn 58507), went 509 → 5 bytes, so the two values it
computes are written to fields with **no readers left**.

Provably dead work. Not fixed because the patch that removes it **segfaults the
app at startup** — removing a `StoreToEnvironment` that a nested closure reads,
which the outer function's disassembly does not show. `patch_js_body_edits` is
disabled in `core.py` with that finding recorded.

Cost is milliseconds once per connect, not per frame. A safer shape for a future
attempt: keep every environment store and cheapen only the *values*, rather than
deleting stores.

## Checked and rejected — do not re-investigate

- **The native failure callback DOES have a subscriber.** `NativeConnection`
  `$ConnectionFailedCallback` has no Java implementer and no Java caller of
  `setOnConnectionFailedCallback` — but that is because the subscriber is a **JS
  function passed across the bridge**. `VoiceConnection` (`initialize`, fn
  32782) does:
  ```js
  if (fnResult.setOnConnectionFailedCallback != null) {
    setOnConnectionFailedCallback(self.handleConnectionFailed);
  }
  ```
  A smali-only search cannot see this. The "no subscriber" conclusion is wrong.
- **`mqt_v_js` is the main RN JS thread**, renamed by `ReactInstance`. The 53%
  in the ANR is a symptom of the GC storm, not a second runtime.
- **No per-frame timer and no un-cleared per-second timer on the call path.**
- **`handleFlushEnd` (fn 58534) is correct.** The decompiler dropped a shared
  tail from converging branches; `disasm` shows the `PutByIdStrict "length"` +
  `Call2`. General rule: never trust a decompiled *missing* cleanup or return —
  confirm with `disasm`.
- **The media-callback diagnostic is complete** — 9 catch-alls, 9 `MediaEngineCB`
  markers. `Log.e` is not stubbed, so `adb logcat -s MediaEngineCB` works.
- **No Java voice websocket exists** (the JS `RTCControlSocket` self-heals);
  TURN/ICE is entirely native with no smali-side refresh hook; no OkHttp timeout
  can kill the media path (all are `0` = no timeout) and the blocklist cannot
  match `turn:` / `stun:` / the voice `wss://` host.
- **No per-call thread churn**, no leaked threads, all receivers balanced.
- `NotificationRenderer.displayLocal`'s `runBlocking` writes files but is
  reached only from the RN native-modules queue, **not** the UI thread.

## Open questions

- Does native actually fire `onConnectionFailed`? (The JS handler exists; the
  native side is unconfirmed.)
- What JS action re-issues `createConnection` with a stale id, triggering the
  `Check failed.` throw?
- `org.webrtc.CameraVideoCapturer$CameraStatistics` posts itself every 2 s for
  the whole camera session and builds a `StringBuilder` for a `Logging.d` whose
  sink (`enableLogToDebugOutput`) has no caller. Reachability rests on JNI
  string evidence, not a smali edge — low severity, not recommended for a patch
  without confirming it is constructed at runtime.


## Sweep coverage — what was checked and came back clean

Recorded so the same ground is not re-covered, and so what is *not* covered is
visible.

| Area | Method | Result |
|---|---|---|
| Periodic JS timers | `xref --query setInterval` in the patched bundle — 75 sites, each attributed | one live defect found (`CameraStatistics`, fixed); `startHeartbeater` is the **voice** control-socket keepalive and must not be touched; `SessionFlusher`/`MetricsAggregator` are Sentry internals, dead |
| Dead-sink periodic logging | every `org.webrtc.Logging` call site, filtered to files containing `postDelayed` | only one periodic site; fixed. The other is `org.webrtc.EglRenderer`, which nothing instantiates |
| Native blocking-sync TurboModule methods | all `isBlockingSynchronousMethod` in `com/discord/codegen` | the only media-related one (`getSupportedImageTypes`) throws `UnsupportedOperationException` on Android — dead |
| WebRTC capture/render classes | who constructs them | `CameraStatistics` live (fixed); `SurfaceViewRenderer`/`org.webrtc.EglRenderer` never instantiated by the app |
| App-declared ContentProviders | manifest | none — nothing runs before `Application.onCreate` |
| App receivers/services `onReceive`/`onCreate` | op-level scan for HTTP, file I/O, SQLite, `Thread.sleep` | clean; `HeadlessTasksService` only overrides `getTaskConfig` |
| `MainApplication.onCreate` | op scan | 13 ops, no heavy work |
| StrictMode | search | not enabled anywhere |
| JS stall path | `JSWatchdogManager.saveStallReport` / `updateStallTime` | the profiler dump (`findSampleTrace`) is already stubbed; the remainder is orchestration |
| Media-callback rewrite | 9/9 catch-alls carry the `MediaEngineCB` marker | complete |
| Blocklist reachability of the media path | host + path rules vs `turn:`/`stun:`/voice `wss://` | cannot match; no OkHttp timeout can kill the media path (all `0`) |

### Not covered

- Anything requiring execution: actual allocation sites, real GC behaviour, and
  whether the native engine honours the lowered capture request.
- The 53 MB Hermes bundle's own startup work — it cannot be executed here
  (HBC v98, no matching VM), so startup cost is reasoned about, not measured.
- Native (ARM64) code paths beyond string and symbol inspection.


## Fix applied: media scope is now supervised

`MediaEngineModule.<init>` built its scope as `CoroutineScope(dispatcher)`, which
installs a **plain `Job`** (`Lnr/x0`) because `Lnr/x;->b` is
`CoroutineScope(context)` = `context[Job] ?: Job()`. A plain Job cancels itself
and every child the first time any child throws. With ~66 `appScope.launch`
sites hanging off it — every media ReactMethod — one throw turns the media
engine into a no-op for the rest of the process lifetime.
`MediaEngineNativeConnections.register` throws
`IllegalStateException("Check failed.")` on a duplicate connection id, *inside*
the coroutine, so JS is never told. That is the "call dies and stays dead while
the gateway still says connected" shape.

The fix was **copied verbatim from Discord's own `MainImmediateScopeKt
.MainImmediateScope()` in this same APK**, not reconstructed:

```
new-instance v1, Lnr/m1;                          # SupervisorJobImpl
invoke-direct {v1}, Lnr/x0;-><init>()V            # super constructor
invoke-static {v1, v2}, Lkotlin/coroutines/e;->c(
    Lkotlin/coroutines/CoroutineContext$Element;
    Lkotlin/coroutines/CoroutineContext;)
    Lkotlin/coroutines/CoroutineContext;          # CoroutineContext.plus
move-result-object v2
```

That also settled an open question: `Lnr/m1` declares no constructor of its
own, and the way a class like that gets built is a direct super-constructor
`invoke-direct` — legal, and here it is in shipping code in the same bundle.
Guessing at this instead of looking for the existing idiom is what made the fix
look unbuildable earlier.

SupervisorJob changes only *child-failure* propagation: an explicit
`scope.cancel()` still cancels children, so teardown still works. Verified by
`smali-check` assembly and by the build.

**Residual risk:** this changes how the media scope is constructed and has not
run on hardware. If it is wrong the media module fails to construct and calls
break immediately and visibly.


---

# Live ANR trace recovered from the device, 2026-09-27

ADB was attached for the first time in this project, and `dumpsys dropbox`
produced an **actual ANR report** rather than a reconstruction. The earlier
audit above was built by reading bytecode; this one is the operating system's
own account.

```
data_app_anr (compressed text, 19664 bytes)
Process: com.discord   Package: com.discord v347205 (347.5 - Alpha)
144% 24553/com.discord: 100% user + 44% kernel / faults: 2069 minor 12 major
Subject: executing service com.discord/.foreground_service.ForegroundService,
         waited 20001ms
```

The stack, which is the part that matters:

```
com.discord.media.engine.video.texture_view.TextureViewEglRenderer.initialize(SourceFile:19)
com.discord.media.engine.video.texture_view.VideoStreamTextureView.init(SourceFile:29)
com.discord.media.engine.video.texture_view.VideoStreamTextureView.attachToStream(SourceFile:98)
com.discord.media.engine.video.texture_view.VideoStreamTextureView.startRenderingStream(SourceFile:19)
com.discord.media.engine.video.VideoStreamViewManager.applyStreamId(SourceFile:44)
com.discord.media.engine.video.VideoStreamViewManager.onAfterUpdateTransaction(SourceFile:4)
com.discord.chat.presentation.list.delegate.b.invoke(SourceFile:141)
com.discord.reactevents.ReactEvents.emitEvent(...)
```

## The mechanism, confirmed in smali

`onAfterUpdateTransaction` is a React Native ViewManager lifecycle callback: it
runs **on the UI thread**, inside the view-update batch.

`TextureViewEglRenderer.initialize` (called from there, unguarded) does:

```java
if (isOnMainThread()) {                 // and it *throws* if it is not
    ...
    init();                             // EglRenderer.init()
    setErrorCallback(this);
    return;
}
```

and `EglRenderer.init()` ends with:

```java
ThreadUtils.invokeAtFrontUninterruptibly(renderThreadHandler, runnable);
```

`invokeAtFrontUninterruptibly` **posts to the render thread and blocks the
calling thread on a latch until it completes.** So every video tile created
posts work to the render thread — EGL context creation, GL program setup — and
then *waits for it on the UI thread*.

The ANR watchdog reports 20 s of an unresponsive main thread, at 144 % CPU, and
the service start that was pending when the clock ran out. On a call with
several participants this is paid once per tile.

Note the 144 % / 2069 minor faults: this is not a lock or a deadlock, it is
genuine blocking work on the main thread.

## Not yet fixed

`invokeAtFrontUninterruptibly` → `post` is the obvious change: the render
thread's Handler is FIFO, the very next statement is already
`post(eglSurfaceCreationRunnable)`, and nothing between them reads a result of
the runnable. **Not applied** — see reference/HBC_BODY_EDIT_HAZARD.md for why
this project does not ship an edit without device verification, and the smali
equivalent of that lesson is the camera crash in reference/CAMERA_CRASH.md.
It needs a test on a real call with a real second participant, which is the one
thing that cannot be checked from here.

## What ADB changes about ANR hunting

- `adb shell dumpsys dropbox --print` — **the actual ANR traces**, with the
  full blocked stack. This is the tool that should have been reached for first
  and was not available without a device.
- `adb shell dumpsys gfxinfo <pkg> framestats` — per-frame timings, and
  `reset` to open a clean measurement window. Needs the app in the *foreground*;
  a cached process reports `Skipped: process is frozen`.
- `adb shell dumpsys cpuinfo` — who is burning CPU, sampled by the OS.
- The previous audit's "Open questions" section can now be answered directly
  rather than reasoned about.

---

# Sweep after the EGL fix, 2026-09-27

Re-ran the two hazard classes across the whole dex:

| Search | Result |
|---|---|
| `ThreadUtils.invokeAtFrontUninterruptibly` outside `org/webrtc` | **none** — the EGL site was the only app-owned one, now fixed |
| Periodic work (`postDelayed` / `scheduleAtFixedRate` / `sendEmptyMessageDelayed`) under `com/discord/media` | **2 sites**: `ScreenCapturer` (screen share, not a normal call) and `EglRenderer` (the 4s per-tile stats timer, already stubbed) |
| `runBlocking` (`Lnr/x;->x`) in app code | **2 classes**: `IncomingCallActivity` (3 sites), `NotificationRenderer` |
| `CountDownLatch.await` | every app-relevant hit is **bounded**: Rive `await(10, SECONDS)` / `awaitShutdown(timeoutMs)`, and Sentry's, which is dead in this build |

So the video-call path is now clear of the two mechanisms that produce ANRs here —
unbounded main-thread waits, and self-reposting timers. What remains is
`IncomingCallActivity`, which fires on an incoming call rather than during one.

## The one thing left, and why it is not shipped

`IncomingCallActivity` calls `runBlocking` at three sites (`configureActions`,
`configureUser`, `initView`) — all Activity lifecycle methods, so all on the UI
thread. Behind them: full Fresco image-pipeline construction, and a **suspending
network image fetch** that the UI thread is parked on until the image round-trips
and decodes.

The return value is discarded at all three sites (`invoke-static` with no
`move-result`), so they are fire-and-forget and the blocking is pure waste.

Not shipped because there is no drop-in replacement: `runBlocking` → `launch`
needs a `CoroutineScope` threaded through, and the fix has to be verified against
a real incoming call, which cannot be done from here. Discord's own
`MainImmediateScopeKt.MainImmediateScope()` (already used by
`patch_media_scope_supervisor`) is the scope to use — it dispatches on the main
thread but *suspends* at suspension points instead of blocking, which is exactly
the difference that matters.

## What "cheaper video" can and cannot touch

- **Capture resolution cannot be lowered alone.** `getVideoQuality` builds
  `capture` and `encode` as a pair from one `videoBudget`; lowering capture only
  makes capture < encode and the engine aborts rather than upscale. Confirmed on
  device — see `reference/CAMERA_CRASH.md`.
- **Incoming video** is rendered by `TextureViewEglRenderer` /
  `EglRenderer`, both now free of main-thread blocking and of the 4s stats timer.
- Anything that changes the negotiated video quality is a **media negotiation**
  change, not a local rendering change, and the remote peer has to accept it.
  That is the boundary of what can be done locally.

---

# The EGL change was WRONG. Root cause of the 2026-09-28 crash.

`invokeAtFrontUninterruptibly` → `post` in `EglRenderer.init()` leaked one EGL
context per torn-down video tile, until `eglCreateContext` failed.

```
android.opengl.GLException: Failed to create EGL context: 0x3003   (EGL_BAD_ALLOC)
  at org.webrtc.EglBase14Impl.createEglContext
  at org.webrtc.EglHelper.create
  at EglRenderer.init$lambda$10$lambda$9(SourceFile:11)
  at EglRenderer.b.run          <- VideoStreamTextureView-241EglRenderer
  at Handler.handleCallback
  at HandlerWithExceptionCallback.dispatchMessage
```

Two render threads died in the same millisecond: `VideoStreamTextureView-241`
and `-242`. The render-thread counter had reached 242.

## The invariant the change broke

`EglRenderer.release()` does **not** wait its turn. It jumps the queue:

```java
synchronized (handlerLock) {
    if (renderThreadHandler == null) { logD("Already released"); return; }
    renderThreadHandler.removeCallbacks(logStatisticsRunnable);
    renderThreadHandler.postAtFrontOfQueue(cleanupRunnable);   // <-- FRONT
    ...  // then blocks on a CountDownLatch until it has run
}
```

and the cleanup it jumps ahead with is null-guarded:

```java
if (self.eglBase != null) {
    logD("eglBase detach and release.");
    self.eglBase.detachCurrent();
    self.eglBase.release();          // frees the EGL context
}
self.eglBase = null;
latch.countDown();
```

**With the original code** the ordering is guaranteed: `invokeAtFrontUninterruptibly`
*blocks* until the create runnable has run, so when `init()` returns `eglBase` is
already non-null. Any later `release()` — even one that jumps the front of the
queue — finds it and frees it. **Create always completes before any release can
be scheduled.**

**With `post`** the ordering inverts. `init()` returns with `eglBase == null` and
the create runnable still sitting at the **back** of the queue. Detach the view
before the render thread reaches it and:

1. `release()` posts its cleanup with `postAtFrontOfQueue` — **ahead** of the
   pending create;
2. the cleanup sees `eglBase == null`, skips the release branch, and nulls the
   field;
3. the create runnable then runs, **after** its renderer was released, and builds
   an EGL context nothing will ever free.

One leaked context per tile torn down in that window. Hence ~240 live contexts
and `EGL_BAD_ALLOC`.

The second half of the failure is the same edit: `invokeAtFrontUninterruptibly`
captures a throwable on the render thread and **rethrows it on the calling
thread** (`ThreadUtils$3` / `ThreadUtils$4` / `ThreadUtils$1Result`). With `post`
there is no wrapper, so the `GLException` went straight to
`HandlerWithExceptionCallback` on the render thread — an unhandled exception on a
non-main thread, which is process-fatal.

## What this means

- **Reverted**, and the flag `DISCORD_EGL_NONBLOCKING` is `False` so it calls
  through the disabled-patch loop with this reason printed on every build.
- `invokeAtFrontUninterruptibly` here is **load-bearing**, not incidental. The
  20-second ANR it causes is the *price* of the create-before-release invariant.
- The ANR cannot be fixed by replacing the wait. A correct fix has to make the
  ordering explicit instead — e.g. have the create runnable check a
  released flag and skip (and log) rather than build a context after teardown,
  or have `release()` cancel the pending create rather than jump ahead of it.
  That is a change to Discord's own class, and it needs a real multi-participant
  call to verify. Do not re-apply the `post` version.

---

# The correct fix, 2026-09-28

The block in `init()` was load-bearing, so the fix keeps the ordering guarantee
and removes the block *separately*, rather than trading one for the other.

## Why `post` alone leaked

`release()` jumps its cleanup to the **front** of the render thread's queue and
puts the looper quit at the **back**:

```java
renderThreadHandler.postAtFrontOfQueue(cleanup);   // frees eglBase
renderThreadHandler.post(quitRunnable);            // BACK of the queue
renderThreadHandler = null;
ThreadUtils.awaitUninterruptibly(latch);
```

With the blocking call, `eglBase` is always assigned before any `release()` can
be scheduled, so cleanup always finds it. With a plain `post` the queue can
become

```
[ cleanup(front) ] [ create ] [ quit ]
```

cleanup sees `eglBase == null`, skips the free, `eglBase = null`; then **create
runs and builds an EGL context nothing will ever release**. One per tile
detached inside that window — 242 of them, until `eglCreateContext` returned
`EGL_BAD_ALLOC` and threw on the render thread.

## The fix, both halves

1. `invokeAtFrontUninterruptibly` -> `Handler.post` in `EglRenderer.init()`.
   This is what removes the UI-thread block (the ANR).
2. A **released-guard** at the top of the create lambda
   `init$lambda$10$lambda$9`:

```java
iget-object v0, p0, ...->renderThreadHandler:Landroid/os/Handler;
if-nez v0, :egl_create
return-void
:egl_create
```

`release()` sets `renderThreadHandler = null`, so that field is an exact
"already released" indicator. The lambda lives inside `EglRenderer`, so it reads
the private field directly. This makes the ordering guarantee **explicit**
instead of relying on the caller blocking.

**The guard needs no cooperation from consumers**: `EglSurfaceCreation.run()`
already returns early on a null `eglBase` (verified in smali), so a skipped
create produces no surface rather than a null dereference.

## What is now guaranteed

| Interleaving | Result |
|---|---|
| create runs, then release | cleanup finds `eglBase`, frees it |
| release first, create still queued | guard sees `renderThreadHandler == null`, create becomes a no-op, nothing leaks |
| create skipped, surface-creation queued | `EglSurfaceCreation.run()` null-guards `eglBase` and returns |

UI thread never blocks on EGL bring-up in any of them.

## Scope check

`EglRenderer` is the **only** app-owned user of `postAtFrontOfQueue` in
`com/discord` — so this create-after-teardown pattern, where teardown can jump
ahead of a pending constructor, exists in exactly one place and it is now fixed.
(Other `HandlerThread` owners in the app: `PlaceholderSinkPool`,
`FrameMetricsFrameTracker`.)

## Still needs a real call

The failure mode if the reasoning is wrong is a video tile that stays blank
rather than a leak, but that has to be seen with a second participant. This is
the one claim in this file that is analysis rather than observation, and it is
labelled as such.
