# Second ANR / performance sweep — 349.5

One change, and a lot of negative results. The negatives are the point: this
patch set is mature enough that "I looked and it is already handled" is the
common answer, and the useful output is knowing *which* places were checked.

## Fixed: EmojiCompat was fetching a font Discord never uses

AndroidX Startup initialises `EmojiCompat` on the main thread at launch. It has
**zero references anywhere under `com/discord/`** - Discord draws its own emoji
from `assets/emoji-*.png`. With no emoji2 config in the manifest it resolves to
`DefaultEmojiCompatConfig`, which asks the Google Fonts provider for
`emojicompat-emoji-font`: an IPC to Play Services that can become a font
download.

The trigger, 500 ms after the first activity resumes:

```java
// androidx.emoji2.text.EmojiCompatInitializer$1.onResume
new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
    public void run() {
        Trace.beginSection("EmojiCompat.EmojiCompatInitializer.run");
        if (EmojiCompat singleton != null) EmojiCompat.get().load();
        Trace.endSection();
    }
}, 500);
```

### Why the runnable and not the initializer

The obvious move - drop the initializer - is a trap. `EmojiCompat.get()`
**throws `IllegalStateException`** when uninitialised, and it is called from
`androidx.appcompat.widget.*` and `androidx.emoji2.viewsintegration.*`. Discord
does have native AppCompat views (the voice overlay, notification layouts), so
skipping the initializer trades a background download for a possible crash.

Stubbing the runnable instead keeps the singleton built - `get()` still returns
it, nothing throws - and only skips the load. `smali/k2/l.smali` has exactly
one method, the `Runnable`, and it is referenced only from that initializer.

```
[OK] EmojiCompatInitializer load runnable (Google Fonts provider IPC)
```

## Looked at, already handled

| Checked | Finding |
|---|---|
| `DeviceResourceUsageManager.monitor()` | a 1 Hz loop doing a binder IPC + `/proc` reads forever - **already dead**: `start()` is no-op'd and `monitor()` is only reachable through its lambda |
| Media engine (`co/discord/media_engine`, `com/discord/media_player`) | zero `awaitUninterruptibly` / `CountDownLatch.await` / `Thread.sleep` / `runBlocking` in 348.2 **or** 349.5 |
| Discord's own receivers and services | `NotificationActions`, `NotificationService`, `HeadlessTasksService`, `PublicFileDownloader` - no blocking calls in any entry point |
| Firebase | `FirebaseAnalytics` has **0 references** under `com/discord/`; Firebase is present only for FCM. GA `AnalyticsReceiver`/`Service`/`JobService` are already `enabled="false"` |
| Call path | all 9 entries applied, including the Discord `EglRenderer.logStatisticsRunnable` 4 s self-reposting stats timer |
| `ProfileInstallerInitializer` | left alone deliberately - baseline profiles *improve* startup, and the write is deferred |

## Looked at, rejected

- **`SurfaceViewRenderer.updateSurfaceSize`** builds a five-part log string with
  four integers on every layout pass - behind `enableFixedSize`, whose only
  setter (`setEnableHardwareScaler`) is called from nowhere. Dead code.
- **`EglRenderer.onFrame`** matched a "log string in a hot method" scan, but the
  `logD` is on the *post-release* early return, not the per-frame path.
- **`WorkManagerInitializer`** opens the WorkManager SQLite DB on the main
  thread at startup and Discord itself never uses WorkManager - but
  `com.google.android.play.core.assetpacks` does, and `WorkManager.getInstance()`
  throws when uninitialised. Not worth the crash risk for one DB open.
- **`EmojiCompatInitializer`** removal - see above.

## Also checked, nothing found

- **Manifest**: 227 name entries in both 348.2 and 349.5, set difference empty -
  no new provider, receiver or service.
- **Dex top-level packages**: 711 -> 710; every addition is R8 obfuscation churn.
- **Native libs**: 41 `.so` in both, none added.

## State

```
patch:  0 FAIL, 156 Hermes functions stubbed, 66 OK
        EmojiCompatInitializer load runnable no-op'd
verify: PASS 164/164  (unchanged - this is a smali patch, not a JS target)
```
