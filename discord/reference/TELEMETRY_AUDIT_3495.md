# Telemetry audit for 349.5 — what is new, and one thing we never covered

Asked: "check if there is new telemetry / analytics in the new apk in case we
didn't check yet". Method was a diff of both bundles rather than a re-read, so
"new" means new in 349.5 and "missed" means present in both and never covered.

## Diff results

**No new network hosts.** 120 `https://` hosts in 348.2, 120 in 349.5, set
difference empty. The blocklist therefore still covers the whole surface —
Sentry (incl. regional DSNs and `sentry-cdn.com`), Datadog, PayPal stats,
Adjust, AppsFlyer, Firebase, Google Analytics, Amplitude, Segment, Qualtrics,
Litix, Spotify.

**New smali classes:** 25 added. Two of interest, both benign:

- `com/discord/chat/bridge/botuikit/CheckpointStatsV2026` (+`Companion`,
  `$$serializer`) - the yearly-recap *display* model (days played, top emoji,
  top game, top guild, voice). Referenced only inside its own package. Not
  telemetry.
- `io/sentry/t6`, `io/sentry/u6` - more obfuscated Sentry internals. Already
  covered: `CrashReporting.isDisabled()` is forced true, both Sentry providers
  are removed from the manifest, the DSN is scrubbed and `sentry.io` is
  blocked, so there is nothing for them to send to.

**New JS modules with a telemetry-shaped path** - five, all verified to route
through the chokepoints we already stub:

| Module | Verdict |
|---|---|
| `modules/guild_space/ServerHubAnalytics.tsx` | both exports call `....track(...)` → the stubbed emitter |
| `modules/tti_analytics/native/navigation/NavigationTTIAnalytics.tsx` | `dispatch({type:"TRACK", ...})` → `AnalyticsTrackingStore` → the stubbed `submitEventsImmediately` |
| `modules/tti_analytics/.../NavigationTTIExperiment.tsx` | experiment plumbing |
| `modules/icymi/trackFeedLoaded.tsx` | calls `.track` |
| `modules/dismissible_content/trackDismissibleContentActioned.tsx` | calls `.track` |
| `modules/quests/lib/analytics/QuestImpressionContext.tsx` | quest impression context, feeds `.track` |

`modules/jank_stats/native/attachJankPanelReporters.native.tsx` is new, but it
only attaches listeners; collection is native and already dead (the three
`jank_stats` smali initialisers are no-op'd and JankPerScreen is pinned to
control). `JankSessionManager.android.tsx` emits through `AnalyticEvents`, i.e.
the same chokepoint.

## The gap: libdiscore action telemetry

Present in **348.2 as well** - this was never covered, in either version. It
surfaced only because this audit looked at the experiment framework rather than
at the event names.

`actionHandler` (owned by `DispatcherBridge`) runs on **every redux action**:

```js
const tmp3 = closure_1_7[arg0.type];
const nowResult = performance.now();
if (null != tmp3) { ...; json = JSON.stringify(obj1); }
else               {      json = JSON.stringify(arg0); }   // every action
const shouldCollect = TelemetryExperiment.shouldCollectMetrics();
const iter = closure_1.dispatchAction(json, shouldCollect);
```

and the sampling rate is **server-controlled**:

```js
getMetricsSampleRate() {
  const t = this.getCachedConfig()?.treatmentId;
  if (t === 1) return 0.01;   // 1%
  if (t === 2) return 0.05;   // 5%
  if (t === 3) return 1;      // 100%
  return 0;                   // default
}
// up to MAX_EMISSIONS_PER_APP_LAUNCH = 5 per launch
```

So Discord can turn on serialised-redux-action collection for a given client at
any time, and the payloads include whatever the actions carry.

**Fixed** by cutting the gate in two places, because there are two ways in:

- `ZERO_TARGETS   "19937": "getMetricsSampleRate"` → `return 0`
- `FALSE_TARGETS "19939": "shouldCollectMetrics"` → `return false`

**`actionHandler` itself is deliberately NOT stubbed.** `dispatchAction` is the
store's own dispatch - 11 references including `_dispatchWithLogging` and
`useOnAction` - so it is the redux↔libdiscore bridge, not a telemetry sink.
No-oping it would have broken the app's action pipeline to stop a counter.

Note the residual cost: the action is still `JSON.stringify`'d unconditionally,
because that is how the *duration* metric is measured. Removing that would mean
editing the bridge, which is the thing we just decided not to touch.

## Present, server-controlled, and NOT closable from JS

The experimentation protobuf
(`discord_common/js/packages/protos/discord_protos/discord_experimentation/v1/experiment.tsx`)
carries these fields from the server to the client:

```
enableDecisionLogging   decisionLogSampleRate   logContextOnFailure
logRawHeaders           metricsSampleRate       tagFilterMetrics
```

`logRawHeaders` is the one worth naming: raw HTTP header logging would include
authorization material.

**Every one of these is referenced only by the protobuf codec's own
`internalBinaryRead` / `internalBinaryWrite`** - there is no other `GetById` on
those strings anywhere in the bundle. So the consumer is native (libdiscore),
and nothing we can patch on the JS side changes what it does with them.

Recording the limit rather than implying it is handled. If these ever need
closing, it is a native-side change, not another table entry.

## Coverage: every axis, not just the convenient ones

The first pass looked at four axes and called it done. It was not. Full list,
each diffed 348.2 -> 349.5 rather than re-read:

| Axis | Result |
|---|---|
| `https://` hosts | **120 -> 120, set difference empty** |
| API path strings | no new telemetry-shaped path |
| AndroidManifest permissions/components | **227 -> 227, identical** |
| Native libs (`lib/arm64-v8a/*.so`) | **41 -> 41, none added** |
| Dex top-level packages | 711 -> 710; every "new" name is R8 obfuscation churn (`a`, `ac`, `c8`...) |
| Analytic event constants | 24,093 -> 24,347; **one** telemetry-shaped addition, `LEADERBOARD_END_IMPRESSION` (routes through `.track`) |
| SharedPreferences keys | **4 -> 4, no new key** |
| Protobuf modules | 47 -> 46, no new message |
| Native module specs exposed to JS | 70 -> 72: `NativeAppStoreModule`, `NativeImageManagerModule`, `NativePermissionManagerModule` - none telemetry |
| Smali log tags | 4,396 -> 4,415; no new telemetry tag |
| JS modules with telemetry-shaped paths | 5 new, all verified to reach a stubbed chokepoint |
| New smali classes | 25; two of interest (`CheckpointStatsV2026` = display data, `io/sentry/t6|u6` = Sentry internals) |
| Experiment-config protobuf | the server-controlled switches, below |

**The headline is the negative result**: the network surface is *unchanged*.
No new host, no new path, no new library, no new permission. Whatever 349.5
added, it did not add a new place to send data.

### What this still does not cover

Worth naming so it is not mistaken for complete:

- **The blocklist is a denylist applied at the OkHttp interceptor.** Traffic
  that does not go through OkHttp - native libdiscore, WebRTC, FCM - is not
  covered by it and never was. An unchanged host list means "no new OkHttp
  destination", not "no new destination".
- **The experiment-config switches** (`logRawHeaders` and friends) are
  native-consumed, so they are out of reach from here.
- **`actionHandler` still `JSON.stringify`s every redux action** even with the
  gate closed - that is how the duration metric is measured.

## Net change

```
+ "19937": "getMetricsSampleRate"   ZERO_TARGETS   -> return 0
+ "19939": "shouldCollectMetrics"   FALSE_TARGETS  -> return false
```
