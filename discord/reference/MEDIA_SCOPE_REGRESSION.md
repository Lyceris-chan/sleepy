# A patch that silently stopped applying, and the call-drop it left behind

Reported: "sometimes turning on my camera kicks me from a Discord call".

One confirmed defect, self-inflicted, of exactly the right shape. Whether it is
the only trigger needs a device log; the diagnostic is at the end.

## The defect

`patch_media_scope_supervisor` hardcoded four R8-obfuscated class names:

```
Lnr/q0;   dispatcher
Lnr/x;    CoroutineScope factory
Lnr/m1;   SupervisorJob
Lnr/x0;   Job base constructor
```

349.5 renamed the entire Kotlin coroutines package **`nr` → `mr`**, and the
dispatcher **`q0` → `r0`**, and the SupervisorJob/base pair **`m1`/`x0` →
`n1`/`y0`**. Verified against the tree:

```
nr/x   MISSING      mr/x   present
nr/q0  MISSING      mr/q0  present
nr/x0  MISSING      mr/x0  present
nr/m1  MISSING      mr/m1  present
```

The patch looks for `new-instance v2, Lnr/q0;`, found nothing, and took its own
`SKIP` path:

```python
if i < 0:
    print("  [SKIP] dispatcher construction not found (shape changed?)")
    return
```

So the 349.5 build shipped with **the exact defect the patch exists to fix**, and
the only trace was one SKIP line in a 150-line log.

## What that defect is

`MediaEngineModule.appScope` was built as `CoroutineScope(dispatcher)`. With only
a dispatcher in the context, `CoroutineScope(context)` installs a **plain
`Job`** — and a plain Job cancels *itself and every child* the first time any
child throws.

**64 methods** launch on that scope — the entire media ReactMethod surface.
Confirmed by walking the class:

```
createConnection, createOwnStreamConnectionWithOptions, connectionInstanceDestroy,
connectionInstanceSetVideoBroadcast, setVideoInputDevice, setVideoInputDeviceById,
getVideoInputDevices, connectionInstanceFastUdpReconnect, ... 64 in total
```

One throw anywhere in those 64 turns **every one of them** into a silent no-op
for the rest of the process. The media engine cannot recover, the RTC connection
tears down, and you leave the call.

### Why the camera

`createOwnStreamConnectionWithOptions` (line 2921) is the camera path, and it is
one of the 64:

```
invoke-static {v0, p2, v1, p1}, Lmr/x;->s(Lkotlinx/coroutines/CoroutineScope;...Lmr/m1;
```

That is `CoroutineScope.launch` — it runs the stream-connection setup as a child
of `appScope`. Anything thrown inside it (a camera that another app already
holds, a `Camera2` failure, a bad stream connection) kills the scope.

"**Sometimes**" is the expected signature: it needs the throw to happen, which
depends on timing and on what else is using the camera.

### A second, independent throw source

`MediaEngineNativeConnections.register` (line 153) checks
`connections.containsKey(id)` and throws `IllegalStateException("Check failed.")`
on a duplicate — **inside the coroutine**, so JS is never told. It is called from
`MediaEngine.createVoiceConnection` (line 4977). Under a plain Job, that alone is
enough to kill media for the session.

## The fix

The patch no longer spells the names. It reads the SupervisorJob idiom from
Discord's own `MainImmediateScopeKt.MainImmediateScope()` — a class whose package
path is **not** obfuscated — so it cannot drift from what Discord actually does:

```
new-instance v0, Lmr/n1;                   <- SupervisorJob
invoke-direct {v0}, Lmr/y0;-><init>()V     <- Job base ctor
```

and resolves the dispatcher and factory from the file it is patching. It refuses
to write anything unless all four resolved classes exist on disk.

The injection, in `MediaEngineModule.<init>`:

```
invoke-direct {v2, v1}, Lmr/r0;-><init>(Ljava/util/concurrent/Executor;)V
new-instance v1, Lmr/n1
invoke-direct {v1}, Lmr/y0;-><init>()V
invoke-static {v1, v2}, Lkotlin/coroutines/e;->c(...)
move-result-object v2
invoke-static {v2}, Lmr/x;->b(...)          <- original line, now supervised
```

`v1` held the executor and is dead after the `Lmr/r0;-><init>` line, so
overwriting it is safe; the scope is now built from `dispatcher + SupervisorJob`.

Verified in the **signed APK's** `classes.dex`, disassembled with baksmali, not in
the working tree.

## Two safety nets that were also stale

This is the part worth internalising, because the class of failure is the same.

**`verify-asks.py` pointed at the wrong tree.** Its defaults were
`build/alpha3482/` and `dist/discord-alpha-348.5-patched.apk`, so it had not been
run against 349.5 at all.

**Its SupervisorJob check was hardcoded to the same stale name** — it asserted
`"Lnr/m1;" in _msrc`. Even run against the right tree it would have passed a
build that did not contain the fix. Both now resolve the name the same way the
patch does.

**Its `ASKS` list is still 348.2-era function IDs** (`57120`, `63907`, `57595`,
`66966`, ...). Against 349.5 those ids mean something else, so the block reports
23 misleading FAILs. That is not fixed here — it needs the same remap the target
tables got — but it is why `verify-asks.py` reads `44 passed, 32 failed` and
should not be treated as a gate until then.

`verify-stubs.py` *was* current and did pass, 202/202. Note what that does and
does not prove: it proves every entry in the target tables landed. It says
nothing about whether the entries point at the right functions, and nothing at
all about smali patches like this one.

## Confirming it on the device

The build now logs the media engine's swallowed callback failures under one tag:

```
adb logcat -s MediaEngineCB
```

Before the fix, the shape to look for is the scope dying — after which every
media call silently does nothing, so subsequent lines go quiet rather than
erroring. After the fix, a camera failure should leave the rest of the call
working: audio, mute, and re-enabling the camera.

## What this does not establish

The scope defect is real and fits the symptom, but nothing here proves it is the
trigger the user hit. Alternatives not excluded:

- the camera itself failing (another app holding it) with Discord tearing the
  call down by design — not patchable from the APK;
- `patch_video_egl_main_thread_block`, which is on and whose docstring records
  that it is "not verified on a real multi-participant call".

A capture from `MediaEngineCB` during a failing camera toggle separates them.
