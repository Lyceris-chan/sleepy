# The 354.1 media scope: camera failures can still end the call

Reported symptom: turning the camera on during a call sometimes drops the call
or kills the app.

## What 354.1 does

`MediaEngineModule.<init>` builds its scope as:

```smali
new-instance v3, Li40/w0;                     # dispatcher
invoke-direct {v3, v2}, Li40/w0;-><init>(Ljava/util/concurrent/Executor;)V
invoke-static {v3}, Li40/z;->b(Lz00/h;)Ln40/d;  # CoroutineScope(context)
iput-object v2, v0, MediaEngineModule;->appScope:Li40/x;
```

Only the dispatcher is in the context, so `Li40/z.b` installs the plain Job
`Li40/e1`. All 64 media launch sites share that scope. The first child failure
cancels the scope and every sibling.

A plain Job also reports the failure as unhandled. The child's
`handleJobException` runs, `i40/z.q` finds no `CoroutineExceptionHandler`, and
`n40/b.d` passes the throwable to `Thread.getUncaughtExceptionHandler()`. On
the media thread that is a fatal process crash.

The 349.5 supervisor patch cannot fix this in 354.1: `MainImmediateScopeKt` is
gone, the constructor registers differ, and the old kotlin coroutine helper is
fully obfuscated.

## The two-part fix

`patch_media_scope_supervisor` now adds both a SupervisorJob and a
CoroutineExceptionHandler to the context before the scope is created:

```smali
new-instance v4, Li40/t1;                 # SupervisorJob
invoke-direct {v4}, Li40/e1;-><init>()V
invoke-interface {v3, v4}, Lz00/h;->V(Lz00/h;)Lz00/h;
move-result-object v3

new-instance v4, Lj40/b;                  # CoroutineExceptionHandler
invoke-direct {v4}, Lj40/b;-><init>()V
invoke-interface {v3, v4}, Lz00/h;->V(Lz00/h;)Lz00/h;
move-result-object v3
```

- The supervisor stops the failing child from cancelling its 63 siblings.
- The handler consumes the throwable at `i40/z.q`, so it never reaches the
  process uncaught-exception handler.

The same injection is applied to `MediaEngine`'s internal scope
(`cd/o.<init>`), which transport code launches into.

All four classes (`i40/t1`, `i40/e1`, `j40/b`, `i40/z`) are checked on disk
before anything is written. `v4` is dead in the constructor at the insertion
point, and `v1` is dead in `cd/o`; neither live value is touched.

The handler also logs the throwable it consumes, under the tag
`DiscordCoroutines`. A contained failure is therefore visible rather than
silent:

```bash
adb logcat -s DiscordCoroutines
```

## What this does not fix

- Native aborts (`SIGABRT`, JNI CHECK failures) and Rust panics are outside
  the coroutine machinery.
- Duplicate connection registration in `cd/p0` still throws
  `IllegalStateException("Check failed.")`. The handler now contains it
  instead of crashing, but the connection is not registered.
- The exact throwable that fires when the camera starts is not proven
  statically. It needs a device run.

## Verify on a device

```bash
adb logcat -b crash -v threadtime
adb logcat -v threadtime | grep -E \
  "FATAL|AndroidRuntime|AppSingleThread|MediaEngine|setVideoBroadcast|Check failed"
adb shell pidof com.discord
```

Toggle the camera 10-20 times during a call. A stable pid and no
`FATAL EXCEPTION: AppSingleThread` means the Java path is contained. A
`Fatal signal 6` means the failure is native and this fix is not the answer.
