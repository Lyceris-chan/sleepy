# Video-call freeze — analysis of the Revenge ANR

Source: `Error_in_Discord_8c37083beea9.txt` (repo root), an ANR report.
**This crash is not from our build.** It is `app.revenge:337010`
(installer `app.revenge.manager`) — Revenge, a client mod, on Discord 337.1.
It is analysed here because the freeze mechanism is in Discord's own code and
therefore applies to us too.

## The one-line version

The main thread was starved by **GC/CPU exhaustion during an active video
call**, not deadlocked. The app peaked at **2.0 GB RSS** and was burning
**~3 CPU cores** while the runtime suspended threads for collection.

## What the report actually captured

| Field | Value | Note |
| --- | --- | --- |
| Reason | `Input dispatching timed out ... Waited 5000ms for FocusEvent` | main thread unresponsive |
| `RssHwmKb` | 2 047 652 | **2.0 GB peak** |
| `RssKb` | 1 533 288 | 1.5 GB resident at ANR |
| `RssAnonKb` | 1 188 400 | 1.13 GB anonymous (Java + native) |
| `VmSwapKb` | 41 164 | device is swapping this process |
| CPU (pid 24484) | **312%** — 198% user + 113% kernel | ~3 cores sustained |
| faults | 140 130 minor / 3 major in ~9.7 s | **~14 400 minor faults/s** |
| Load | 9.68 / 6.88 / 6.61 | whole device saturated |
| pressure/cpu | `some avg10=26.93` | real CPU contention |

Per-thread, over a 750 ms window:

```
72%  24540/Signal Catcher     <-- the tell
53%  24610/mqt_v_js           <-- voice media JS thread
15%   5808/AudioTrack
12%   7934/VideoStreamText
 6%   5876/decoder-texture, 8646/MediaCodec_loop, ...
```

### The critical caveat

**The app's own Java stack was never captured.**

```
----- dumping pid: 24484 at 119746138
libdebuggerd_client: failed to read status response from tombstoned: Try again
```

The file contains **zero** managed frames for `app.revenge` — the only
`com.discord` string in it is the activity name in the ANR subject. system_server
and the IME were dumped successfully; the app was not.

So we have aggregate counters and a CPU breakdown, but **no allocation site**.
Everything below is inference from those counters, and is labelled as such.

## Why GC thrash, not a deadlock

- A deadlock shows an idle process: low CPU, main thread parked on a monitor.
  Here the process burns **3 cores**, 113% of it in the kernel.
- **`Signal Catcher` at 72%** is the strongest single signal. In ART that
  thread services `SIGQUIT` dumps *and* the `SIGUSR1` checkpoints used to
  **suspend all mutator threads** — which is exactly what every GC does. A
  Signal Catcher pinning a core means the runtime is endlessly suspending and
  resuming threads, i.e. collecting back-to-back.
- **113% kernel time** with **14 400 minor faults/s** is memory-management
  work: page mapping, `madvise`, heap growth. A deadlocked process does not
  do this.
- The dump failing is itself consistent: with every thread thrashing through
  checkpoints and the heap at 1.5 GB, the ANR dump cannot complete, so
  `debuggerd` times out waiting on `tombstoned`.

## What this evidence does *not* support

- **It does not identify the allocator.** With no managed stack we cannot say
  which component grew the heap. Any claim naming a specific feature as "the"
  cause would be a guess.
- **It is not a leak proof.** 2.0 GB is a high-water mark, not a floor. A burst
  that GC could not keep up with produces the same counters as a slow leak.

## What we checked, and what it means for our build

Surveyed with ASC (`tools/asc`) against `build/alpha3462/apk/extracted/base.apk`.

### Already dead in our build

- **`SystemLogCapture`** — spawns a child `/system/bin/logcat` and runs a
  **regex on every log line** (`shouldIncludeLogLine` accepts everything except
  `chatty  : uid=` and non-error `OpenSLESRecorder`). Our suite already no-ops
  `SystemLogUtils.initSystemLogCapture`, so the capture thread never starts.
  Worth noting the buffers are bounded (256 KB + 50 KB), so this was never a
  memory candidate — it is a CPU/allocation cost, and it is already gone.
- **`PerformanceTracing.start`**, jank aggregation, `FrameMetricsAggregator`.

### A real gap: Sentry is gated at the wrong layer

`CrashReporting.isDisabled()` is forced true by our suite, and it gates
`CrashReporting.init` → `io.sentry.android.core.d1.b(...)` (= `SentryAndroid.init`).
So the SDK never initialises and the Hub has no client. Good — but the gate
only covers call sites **inside `CrashReporting`**.

ASC's `findrefs` shows these reach the Sentry facade **directly**, bypassing it:

| Call site | Facade method | When it fires |
| --- | --- | --- |
| `com.discord.view.ReactViewManagerOverride.addView` | `addBreadcrumb` | every RN view re-added while it already has a parent |
| `com.discord.view.ScreenOverride.startRemovalTransition` | `addBreadcrumb` | per screen transition |
| `com.discord.view.ScreenOverride.endRemovalTransition` | `addBreadcrumb` | per screen transition |
| `com.discord.crash_reporting.PerformanceTracing.startTransaction` | `getHub` | per traced transaction |
| `com.discord.crash_reporting.WebrtcCrashReporting.reportWebrtcException` | `captureException` | **on WebRTC exceptions — the call path** |

`WebrtcCrashReporting.reportWebrtcException` has **no DEX callers** — it is a
JNI entry point the native WebRTC layer calls. It was found by decompiling the
class, not by reference search.

These are inert today (no client attached), but they still allocate — the
`ReactViewManagerOverride` path builds a `Breadcrumb`, five map entries and a
reflective `getClass().getName()` per event — and they are one config change
away from being live again.

### Discord's Sentry config is unusually heavy

```java
MAX_BREADCRUMBS = 5000;          // Sentry's default is 100
p7.setMaxBreadcrumbs(5000);
p7.setEnableUserInteractionBreadcrumbs(1);  // a breadcrumb per tap/gesture
p7.setEnableSystemEventBreadcrumbs(1);
p7.setEnableAppComponentBreadcrumbs(1);
p7.setEnableActivityLifecycleBreadcrumbs(1);
```

This only matters if the SDK initialises — which we prevent — but it is the
reason the call-site gaps above are worth closing properly rather than by
luck.

## Honest conclusion

The report proves the app was in **resource exhaustion during a call** and
that the main thread lost the CPU for 5 s. It does **not** prove which
subsystem grew the heap, and no single patch can be claimed to "fix" it on
this evidence.

What we can do — and what the patch suite now does — is remove per-event work
from the call path so there is less to churn on, and keep the crash reporter
structurally inert rather than inert-by-configuration.

Re-investigating properly needs a capture we do not have: a `traces.txt` /
`perfetto` heap profile, or an ANR where the app dump succeeds.
