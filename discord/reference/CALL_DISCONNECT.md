# "Call disconnects but still shows connected"

Investigation into a reported Discord Android symptom: a voice/video call
drops, but the ongoing-call notification keeps showing the call as connected.

Analysed on the 347.5 JS bundle (`assets/index.android.bundle`, Hermes v98)
and smali. Function IDs below are from the 346.2 bundle, which is what the
extracted sources in `build/alpha3462/decompiled/jssrc/` came from; the code
is the same in 347.5.

## The short version

**The notification reports voice-channel *membership*, not media health.**
They are different things, and the gap between them is the whole symptom.

So "still shows connected" is not a stuck notification — it is an *accurate*
rendering of what Discord's gateway state says. The real event is that the
media path died and was not recovered.

## How the notification works

The notification is owned by `ForegroundService` but driven **entirely from
JS**:

- `ForegroundServiceModule` exposes exactly three `@ReactMethod`s:
  `startService(ReadableArray)`, `stopService()`, `isServiceRunning(Callback)`.
  There is no native path that tears the notification down on its own.
- `VoiceNotificationManager` (`modules/.../VoiceNotificationManager.tsx`)
  maintains it. Its constructor registers four store listeners:

  ```js
  RTCConnectionStore.addChangeListener(this.handleVoiceStateChange);
  MediaEngineStore.addChangeListener(this.handleMediaEngineStateChange);
  EmbeddedActivitiesStore.addChangeListener(this.handleEmbeddedActivityStateChange);
  ApplicationStreamingStore.addChangeListener(this.handleApplicationStreamStateChange);
  ```

- The handler that matters reads the RTC connection state, which comes from the
  **gateway**, and removes the notification only when the channel no longer
  resolves:

  ```js
  const channelId = RTCConnectionStore.getChannelId();
  const state = RTCConnectionStore.getState();
  ...
  const channel = ChannelStore.getChannel(connectionState.channelId);
  if (null != channel) { updateServiceHandler(id, obj); }
  else                 { ForegroundServiceManager.removeServiceHandler(id); }
  ```

`RTCConnectionStore.getState()` cannot return null — it falls back to
`constants.DISCONNECTED` — so the handler always runs. Removal is keyed on
**leaving the channel**, not on the media connection failing.

Therefore: media dead + gateway still listing you in the channel → the
notification correctly says you are connected. That is by design.

## Why the media path dies and stays dead

Recovery on the JS side runs through exactly one door:

```
RTCConnection._handleResuming()  ->  connection.fastUdpReconnect()
```

`fastUdpReconnect` is called **only** from `_handleResuming` — i.e. when the
voice connection is resuming, which in practice means the app was
backgrounded and came back.

Ping timeouts do **not** trigger recovery:

```js
_handlePingTimeout(seq, value) {
    this._pingTimeouts.push(seq);
    // only if the last three sequence numbers are consecutive
    if (<three consecutive>) self._handlePing(value);
}
```

and `_handlePing` only records latency history (capped at 200 samples) and
emits a `Ping` event; it increments `_pingBadCount` if the value exceeds
500 ms. **Nothing reconnects.**

`PING_INTERVAL` is `5000` ms (`Constants.tsx`), and three *consecutive*
timeouts are required — so the earliest a dead path is even noticed is ~15 s,
and noticing it triggers no action. The remaining recovery is whatever the
native WebRTC stack does on its own (ICE), which is not patchable from the
APK.

**This is Discord's design, not something we broke.** There is no clean APK
-side fix: forcing a reconnect from JS would mean authoring new logic into
Hermes bytecode, and the recovery decision belongs to the native engine.

## The one thing that is fixable: swallowed callback failures

Every media callback wrapper in `MediaEngine` catches `Throwable` and returns
normally:

```java
private final void nativeCallbackSafe(String name, Function0 cb) {
    try { cb.invoke(); return; }
    catch (Throwable t) {
        CrashReporting.captureException(new RuntimeException(
            "media engine native callback [" + name + "] threw", t));
        return;                       // <-- swallowed
    }
}
```

and each generated callback has the same shape — 9 such catch blocks in
`MediaEngine.smali`, covering ping timeout, MLS failure, first frame, video,
speaking, speaking-while-muted, secure-frames state, and `nativeCallbackSafe`.

So if a JS handler throws — for a ping timeout, an MLS failure, a video frame —
that callback **aborts silently**. No reconnect, no teardown, no notification
update. Combined with the section above, that is a complete account of the
symptom: the callback that would have reacted to the failure threw, and the
evidence was discarded.

With crash reporting gated inert in our build the report goes nowhere at all,
so the failure is not merely unhandled — it is unobservable.

### What the patch does

`patch_media_callback_visibility` rewrites the `captureException$default` call
in each of those 9 catch blocks into a `Log.e` under the tag `MediaEngineCB`:

```smali
sget-object p0, Lcom/discord/logging/Log;->INSTANCE:Lcom/discord/logging/Log;
const-string p4, "MediaEngineCB"
const-string p3, "media engine native callback threw"
invoke-virtual {p0, p4, p3, p2}, Lcom/discord/logging/Log;->e(...)V
```

It is a **diagnostic, not a fix** — behaviour is otherwise unchanged.

```
adb logcat -s MediaEngineCB
```

If this tag is silent during a disconnect, the callbacks are not the cause and
the failure is inside native WebRTC. If it fires, the exception's message names
the callback (`media engine native callback [onPingTimeout] threw`) and the
stack trace points at the JS frame that threw.

The rewrite is register-safe by construction: the three registers the original
passed for `captureException$default`'s trailing `(boolean, int, Object)`
arguments are constants loaded immediately before the call — scratch — and the
fourth is the Throwable. All four were already legal in a 35c `invoke`, so
none exceeds v15 and the frame does not grow.

## What we checked and ruled out

- **Foreground service types.** `ForegroundService` declares
  `dataSync|mediaProjection|camera|microphone`, and the manifest holds
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`,
  `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`,
  `FOREGROUND_SERVICE_DATA_SYNC`, `RECORD_AUDIO`, `CAMERA`, `POST_NOTIFICATIONS`
  and `WAKE_LOCK`. Nothing missing for Android 14/15.
- **`handleUpdate` being skipped on a null connection state.** Initially looked
  like the mechanism, but `RTCConnectionStore.getState()` falls back to
  `DISCONNECTED` rather than returning null, so the guard always passes. Ruled
  out.
- **Our blocklist interceptor.** Matched against the gateway and voice hosts and
  against every API rule: `/api/`-gated path rules cannot fire on
  `gateway*.discord.gg` or the voice websocket, and no host entry matches
  `discord.gg`. Not the cause.

## Open, not investigated

`dataSync` is declared on the same service as `microphone`. From Android 15,
`dataSync` foreground services are subject to a **6-hour per 24-hour** runtime
limit, after which the system calls `Service.onTimeout` and the app must stop
the service. Whether a long call is affected depends on how the system treats a
multi-type service, and we have not tested a call anywhere near that long. It
would not explain drops that happen sooner, so it is noted rather than acted
on.
