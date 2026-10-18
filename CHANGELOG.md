# Changelog

This file documents all notable changes to this project.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.9.2] - 2026-10-18

### Fixed

- **A camera that fails to start can no longer end the call.** The media engine runs its camera
  and connection work as coroutines on a scope that had a supervisor job but no exception
  handler. The supervisor kept one failed operation from cancelling the scope, but it did not
  stop the exception itself: without a handler in the context, `handleCoroutineException` falls
  through to the thread's uncaught-exception handler, which kills the process. That is the shape
  of a call that ends the moment the camera is turned on, and it depends on why the camera failed
  — another app holding it, a Camera2 error, a bad stream connection — which is why it was
  intermittent.

  The scope now carries the app's own `CoroutineExceptionHandler` (`tr/b`, Android's
  `AndroidExceptionPreHandler`) alongside the supervisor job, so a throw in any of the media
  engine's coroutine bodies is handled instead of reaching the process kill. The handler also
  writes the exception to logcat under the `MediaEngineCB` tag before Android's pre-handler sees
  it, so a contained camera failure leaves a trace. On API 26 and 27 that pre-handler also logs
  the stack; on 28 and later the message above is the record.

  This contains every media coroutine body, not only the four on the camera path: the same throw
  from a connection, audio-device or stream coroutine would also have ended the process. The
  native patch set grows from 109 edits to 110.

## [3.9.1] - 2026-10-17

### Fixed

- **Joining a call no longer crashes the app.** The two bytecode edits behind **Lower video call
  cost** were assembled from text whose jump labels are numeric byte offsets. Replacing a
  4-byte multiply with a 6-byte constant moved every instruction after it by two bytes but left
  the labels at their old offsets, so `getVideoQuality` jumped into the middle of an instruction
  and Hermes crashed on its JavaScript thread a few seconds into every call. The labels now name
  the offsets the assembler actually places them at, and the sibling `MediaSinkWantsLadder` edit
  reaches its invalid-argument `throw` again instead of returning early.

## [3.9.0] - 2026-10-17

### Added

- **Badge customization can be removed.** A new switch under **Declutter → Profile** hides the
  prompts and popups around badges: the coachmark that offers to customize them, its Rive
  animation, the customization sheet, the badge-directory coachmark, the visibility menu, the
  badge details sheet, and the remaining badge coachmarks — the one shown when a profile already
  has a badge, the new-badge coachmark, and the orbs, tenure and gifting badge coachmarks.
- **The remaining Nitro and promotion popups can be removed.** The Nitro upsell switch now also
  hides the Nitro home and marketing pages, the marketing-moment sheet, the app-icon and
  super-reaction coachmarks, the gifting-promotion coachmark, and the text renderer behind the
  upsell copy. The premium switch adds the plan-selection, what-you-lose, discount-offer and
  trial-offer sheets, the premium-group education sheet, the outbound promotion card and claim
  alert, the collectibles announcement sheet, and the generic promo sheet. The guild switch adds
  the boost and premium-role subscribe buttons and their coachmarks.
- **Video calls can cost less.** A new **Lower video call cost** switch sends 640×360 at 24
  frames per second instead of 1280×720 at 30 — roughly four times fewer pixels — and caps the
  encoder at 1 Mbps. Unlike the reference patch, which lowers only the camera and crashes the
  camera because the encoder still expects 720p, this lowers the encoder's resolution budget
  with the capture, so the pair stays matched, and it ignores the per-channel full-quality mode
  that would otherwise put the encoder back at 720p. Screen sharing keeps its own 720p path.

### Changed

- **The JavaScript table is 518 entries in 49 features**, up from 420 in 47. Every added entry is
  a constant-shaped stub whose replacement bytes name nothing bundle-specific, or one of the two
  video-quality edits; the bundle test applies all 518 and reads every patched function back.

## [3.8.1] - 2026-10-17

### Fixed

- **The theme set an attribute the minimum API does not have.** `windowLightNavigationBar`
  arrived in API 27 and the app supports 26, so the base theme declared an attribute id an API 26
  device does not know. The flag now lives in `values-v27/themes.xml` and the base theme keeps
  only what API 26 understands — the one Android Lint error the project had.
- **Two redundant conversions in the okhttp resolver** are gone, which is the
  `w: Redundant call of conversion method` warning the release build printed. The Kotlin
  compilation is now warning-free.

### Changed

- **Backup exclusion is stated on both sides of Android 12.** `allowBackup="false"` is joined by
  `dataExtractionRules` and `fullBackupContent` rules that exclude everything, so the posture is
  written in the rules each platform version reads rather than inferred from one switch.
- **Downloads refuse a cleartext redirect**, and a `file://` source is read only when it is a
  regular file within the same 512 MB limit as the network path — a device node such as
  `/dev/zero` has length zero but never ends, so the limit alone would not have stopped it.
- **Dependencies are current.** Kotlin 2.4.20 → 2.4.21; the Gradle wrapper 9.8.0 → 9.8.1 with the
  distribution hash pinned; `androidx.core:core-ktx` declared explicitly at 1.19.1, which replaces
  the two `Uri.parse` calls with `String.toUri`. Every other dependency was already the newest
  stable release.
- **The launcher icon declares a monochrome layer** for themed icons, and the
  `mipmap-anydpi-v26` folder merged into `mipmap-anydpi` now that the minimum API makes the
  qualifier redundant.
- **The comments and KDoc now follow the Kotlin style guide.** The file headers had grown into
  essays—117 lines on the patch table, 63 on the resource merger, 55 on the function table—so
  each is now a short summary plus the constraints that prevent a regression, with the history
  left to this file. File headers fell from 729 lines to about 120 and comments from 7,843 lines
  to 7,143, and every executable line and every comment line fits 100 columns; the remaining
  long lines are string literals—smali replacement bodies and user-facing descriptions—which
  must stay byte-exact.

## [3.8.0] - 2026-10-16

### Added

- **Games being played can be removed.** A new switch under **Declutter** takes the game
  somebody is playing out of the app: the status line drawn under their name in a member list, a
  DM row or a search result; the entry in their profile's activity list; the label on a private
  channel; the You bar's accessibility label; and the games section the voice panel draws for a
  voice channel.

  It is two stubs and a component rather than a change per surface. Discord asks one of two
  predicates — `isGameActivity` and `isPlayingGameActivity`, both in `modules/activities/utils/` —
  before it draws a game anywhere, and every surface above reads one of the two, so answering
  false at both leaves each of them with nothing to render. The voice panel's own
  `VoicePanelGamesSection` is the container the games list is drawn inside, so an empty list would
  have left the container on screen; it returns null instead, in both of the copies Discord ships
  for its React Compiler experiment.

  Nothing else about presence changes. Streaming, listening, custom status and the voice-channel
  line are separate branches and are untouched, and the game *mentions* feature — typing a game's
  name in a message — is a different module and is not affected.

- **The storefront, the upsells, the trials and the leaderboard badges can be removed too.**
  Eight more switches under **Declutter**, five new headings:

  - **Storefront** — the product page, the gift and purchase flows, the wishlist cards and the
    store item card, plus the actions that open and close them.
  - **Nitro upsells** — the banners, promo sheets, action sheets, cards, headers and the lock
    toasts the app raises when something needs Nitro.
  - **Premium upsells** — the same, for the rest of the premium surface, split into emoji and
    sticker upsells, profile upsells, guild and boost upsells, and the remaining sheets.
  - **Free trials** — the card that counts down an expiring trial and the hooks that decide
    whether to show it.
  - **Leaderboard badges** — the badge a leaderboard winner carries on their profile.

  208 functions in all, and each was chosen by what its own body returns rather than by its name.
  A function whose every return path builds a React element (with the null on the "nothing to
  show" path that Discord's own compiler emits) is a screen or a button, and returns null; one
  whose every return is a comparison answers false; and one that already returned nothing on
  every path is left returning nothing. Discord ships each component twice — once plain and once
  for its React Compiler experiment — and the module picks between them as it loads, so both
  copies of each are switched off together; stubbing one would have left the screen live half the
  time. Anything whose returns fit none of those three shapes was left alone.

### Changed

- **Discord support moves to 351.2.5** (version code 351205, published as `351.5 - Alpha`). The
  351.2.0 build is no longer offered: Hermes numbers functions per bundle, so a patch written for
  one release names unrelated functions in another and the two cannot be carried side by side.
  Everything the change list did before still happens, with the game-activity switch added.

  The JavaScript table was re-derived for the new bundle rather than translated. Its 208 entries
  became 212 (**420 in all**, with the declutter switches above): **180 are constant-shaped stubs** whose bytes name nothing bundle-specific and carry
  unchanged; **17 are promise-shaped stubs**, re-assembled against this bundle because they read
  the strings "Promise" and "resolve" through the bundle's own string identifiers, which are
  renumbered every release; **15 are edits of an original body**, re-derived against the body this
  build actually has; and **4 are the new game-activity entries**.

  Each id was resolved by more than one method and the answers were required to agree — the name
  the bundle gives the function with its parameter count, frame size and body size; the module the
  function belongs to; the strings its body reads; and, where nothing else reached it, a
  hand-checked body. That requirement is not ceremony: on this port the string method alone put
  two profile-activity filters and the voice-state callback on the wrong functions, and the module
  method alone is what the 3.7.0 crash came from.

  The register each tab-list edit points a guard at was chosen again for this build rather than
  carried, and is only used when the instruction that last wrote it before the guard is a constant
  load and nothing writes it in between. The five entries whose replacements are longer than the
  bodies they replace, and the two pairs that share a body, are relocated exactly as before.

- **The native edits were re-pointed from the new build's own smali.** All 47 class paths the
  native patch set names still exist in 351.2.5, which was checked against the class list of the
  build's DEX files rather than assumed. Four kinds of name inside those classes did move, and
  each is read out of the build's own disassembly: five experiment lambdas take the enclosing
  `NativeExperiments` where they took a libdiscore module; `FrameMetricsAggregator.bindTo` is
  private rather than public and the method the other patch stubbed as `initialize(Window)` is
  now `enableTracking`, while the neighbouring `JankStatsAggregator` keeps `initialize`; the
  coroutine classes the supervisor-scope injection names moved from `Ltr/` to `Lsr/` with their
  relationships intact; and the obfuscated okhttp cache type moved from `Lvs/g;` to `Lus/g;` on
  the same builder field.

  A stale name here fails quietly — the patch is skipped and the run reports a step that did
  nothing — so each was checked before it was written, by asking whether its signature or anchor
  occurs exactly once in that class in the new build's smali. Four anchors and one signature
  occur more than once in both builds, which is how they shipped, and are left as they were.

  Every manifest edit, the resource-table merge and the blocklist's five DEX-resolved okhttp
  names are unchanged and were re-run against the new build.

### Fixed

- **Nothing.** This release moves the patch set to a new build and adds a switch; no reported
  fault is fixed by it.

### Notes

- The claim about what is checked has not changed and is deliberately narrow. Every one of the 420
  entries must apply to the build the table names with nothing skipped, and a real Hermes
  disassembler must read all 420 patched functions back. That catches a body which is not valid
  bytecode. It does not catch a body that assembles cleanly and does something other than what its
  entry says, and there is still no recorded patched build to compare against — the 349.5 set had
  one and no release since has.

## [3.7.4] - 2026-10-15

### Fixed

- **The network blocklist interceptor called two methods the build does not have.** The interceptor the blocklist set generates reads the request it is handed through `okhttp3.Interceptor$Chain`, and that interface's two methods are obfuscated like everything else okhttp. The generator resolved three obfuscated names from the target build — the `Response` constructor descriptor, the `Protocol` enum and its `HTTP_1_1` field — and wrote the other two down: `i()` for the no-argument `Request` accessor, which is 349.5's name and is `h` in 351.2.0, and `a(Request)` for the one that proceeds, which happened still to be right. So the first request the React Native XHR client made died with

  ```
  java.lang.NoSuchMethodError: No interface method i()Lokhttp3/Request;
    in class Lokhttp3/Interceptor$Chain;
      at DeviceResourceUsageRecorder$Companion.requestStatsInterceptor(SourceFile:2)
  ```

  on the OkHttp dispatcher thread. Both names are now resolved from the build's DEX by what they take and return, like the other three, and `interceptorBody` takes them as required arguments rather than defaulting — a default is how a stale name gets back in.

- **The reference audit now covers the generated bodies too.** It only read the static table, which is the half of the patch set that was already being checked; the interceptor is generated at patch time and nothing looked at it. The audit reports what it covered, so a run that checked only the table cannot read as one that checked everything: `67 references across 106 patches and 1 generated body all resolve`.

## [3.7.3] - 2026-10-15

### Fixed

- **The app died as it opened, and this is why.** The patch that drops seven eagerly-loaded exception classes from `CrashReporting`'s static initializer builds an empty list through `Lkotlin/collections/a0;->g`. That name came from the recorded change set for 349.5. In 351.2.0 R8 gave `kotlin/collections/a0` to a different class — it is `ArrayDeque` there, it has eight methods, and `g` is not one of them — and the method that was `a0.g` is `kotlin/collections/v;->h`. So the class died with

  ```
  java.lang.NoSuchMethodError: No static method g([Ljava/lang/Object;)Ljava/util/List;
    in class Lkotlin/collections/a0;
      at com.discord.crash_reporting.CrashReporting.<clinit>(SourceFile:53)
      at com.discord.MainApplication.performInitialization(SourceFile:328)
  ```

  on `ReactNativeInitThread`, before anything was drawn. The name is corrected to the one this build actually has, which is the same call the stock initializer makes.

  Nothing about assembling smali requires the names in it to resolve, so this applied cleanly, read back cleanly and passed every test the project had. It is the third defect in this release series with that shape, and the second where the answer was in a log nobody had read.

- **A test now checks that every class and member a patch body names exists in the build being patched.** It walks the replacement bodies and anchor replacements of the whole native set, extracts every `Lclass;->member` reference, and resolves each against the classes and members of all four DEX files of the shipped build. Class and member names are matched rather than full signatures — a wrong overload would pass — but the failure that happened is a name that moved to another class, and that is what it catches. System classes are skipped, and a member the patch set declares itself counts as present, so an entry that adds a field is not reported as one that names a missing one.

## [3.7.2] - 2026-10-15

### Fixed

- **Seven JavaScript functions were written to a place Hermes cannot read, and Hermes aborts on them.** When a replacement does not fit where the body it replaces is, the patcher appends the new body and a 37-byte large `FunctionHeader` after the end of the file, and repoints the function's table entry at it. What it did not append is the data Hermes expects to find *behind* that header: the exception-handler table and the function's debug offsets are not part of the body, and `BCProviderFromBuffer::getExceptionTableAndDebugOffsets` reads them from immediately after the header, whenever the header's flags say they are there. The patcher copied the header from the function it was replacing, which kept both flags set, and wrote nothing behind it — so Hermes read the next appended body as a `DebugOffsets` structure and took a garbage offset into the debug-info section. That is not a wrong value it can carry on with: it is `assert` in `DebugInfo::getLocationForAddress`, which aborts the process. A patched build dies whenever the runtime asks one of those seven functions for a source location, and the seven are ordinary application functions, not telemetry.

  The flags are now cleared on a relocated header, and nothing is written behind it — which is what the replacement actually is: a body with no handler table, and no line numbers to report for code that is not the code that was there. A test asserts it off the written bundle rather than off the intent, because the flag byte is copied from a header the patch set does not own: a function with no large header of its own gets one synthesised over a template borrowed from an unrelated function, and that template arrives with its own flags.

  This was found by building `hbcdump` from `discord/hermes` on the machine that builds the app, which turned a failing CI step into a debuggable one: the same bundle is produced by a unit test, so the abort, the backtrace and the fix were all local. The verification job had been red since 2026-10-12 and named exactly these seven functions; nothing had read it.

- **A repointed entry left the 25th bit of the header offset as it was.** Hermes' `Offset` field is 25 bits and its reader takes all of them, while its writer puts the low 24 in `Offset` and the high 8 in the entry's `functionName`. Clearing the low 24 bits and writing 24 back leaves bit 24 holding an address from the other scheme, so any entry that was repointed *and* had that bit set sent Hermes 16 MB past its header. `SmallFuncHeader(uint32_t)` in the Hermes source is the reference, and both sides now match it bit for bit.

- **The patched app claimed the microphone foreground-service type at all times, which is what made it report a call that was not happening.** `ForegroundServiceUtilsKt.buildServiceTypeChain` decides which foreground-service types Discord is allowed to promote its notification service through. Discord's own rule is the one Android 14 enforces: the app is eligible for the microphone and camera types while it is in the foreground, or while a telecom bar is present, and *not* while it is backgrounded with no call. The patch replaced the "not eligible" arm with "eligible", so the app asked for a microphone-type foreground service while sitting in the background with no call in progress. Android refuses that with a `SecurityException`, which the promotion chain catches and steps past — so the service still came up, but as a call service, and its notification said so. That is the "in a call" notification that appears when there is no call, and that outlives the app when it dies mid-promotion.

  The patch is removed rather than adjusted. Which service types an app may use is a fact about whether a call is running, and this function has no way to know that; Discord asks the question it can actually answer, and the answer it gives is the correct one. Backgrounded audio during a real call still works, because a real call is exactly the case Android grants the exemption for. The other patch in the set — widening the per-type catch so a refusal falls through to the next candidate instead of escaping — is kept: `ForegroundServiceStartNotAllowedException` is an `IllegalStateException`, not a `SecurityException`, so an uncaught one takes the process down.

### Notes

- **What is verified about the patched artifact, and what is not.** Everything the project can check without a device now has been, against the real 351.2.0 build, and it is clean: every one of the 208 JavaScript bodies reads back through Hermes; all 106 DEX edits apply with nothing skipped; no method in the patched DEX names a register its frame does not allocate (checked against the same rule ART's verifier applies, with the stock build as the control); the rebuilt DEX keeps every class, method and field it started with; and `aapt2` reads the rebuilt resource table and finds it complete but for the split-install metadata the merge drops on purpose.

- **What was reproduced, and what was not.** The relocation defect above was reproduced, diagnosed and fixed here: the patched bundle is built by a unit test, and Hermes' own `hbcdump` — built from `discord/hermes` at the revision the app ships — aborted on the seven functions before the fix and disassembles all 208 after it, in one batch and one at a time. The foreground-service patch was not: it was found by reading the patched output against the stock output, and no device was attached to confirm it. It is a real defect with a real mechanism, but it is offered as the cause of the phantom call notification rather than of the crash.

- **The verification job is the thing that caught this, and it was being ignored.** It has failed on every push since 2026-10-12 with the seven function ids in its log. A workflow that is red is a workflow that is not read, and this one was the only check in the project that used the engine the app actually runs.

## [3.7.1] - 2026-10-12

### Fixed

- **Six patches were aimed at the wrong functions, and 3.7.0 shipped that way.** Two modules are affected: the time-to-interactive tracker, where `recordStart` was patched at the id of `recordEnd`, and the collectibles shop, where two entries landed on a pair of loading helpers that did not exist in the previous release. Two of the intended functions were not patched at all.

  The cause is how function ids were carried from one release to the next. Hermes numbers functions per bundle, so each id has to be resolved again, and the resolution identified a function by its ordinal position among the closures of the module it belongs to. That is only stable while the module's closure list is: add or remove one closure anywhere in a module and every later ordinal in it shifts by one. The tracker gained a pair of closures two places before its four targets; the shop gained two in the middle of its block. Everything after them moved, and nothing about the method noticed.

  What made it invisible is that nothing checks *which* function an id names. The patcher compares the size an entry declares against the size the bundle holds, which catches a table built for the wrong release - but the size is read from whatever function the id now names, so it agrees with itself either way. A mis-aimed patch applies cleanly, reads back cleanly, and every test passes.

  It was found by resolving every id a second and third way and comparing: by the **name the bundle itself gives each function**, which for these entries is Discord's own method name (`recordStart`, `recordEnd`, `serializeEnd`, `hasData`), and by how closely the new body's instruction stream matches the old one's. 104 of 107 entries with a name agreed with what shipped; the three that did not were three of the six. The other three were caught by the same two checks.

  A test now enforces the first of those checks against the real build: every entry that records a name must be pointing at a function the bundle names that way. All 111 named entries pass. That would have caught four of the six.

- The patch table is verified against the build it names before release, not only against itself: every entry applies with nothing skipped, every patched body stays inside its function's register frame, and all 208 functions read back through a Hermes disassembler.

### Notes

- **The verification gap this release does not close.** There is still no recorded patched 351.2.0 build to compare against, and no way to run the bundle: Hermes compares its bytecode version for equality and no released desktop build reads this version. A body that is well formed and does something other than what its entry says passes every check the project has. That is the class the six mis-aimed entries belonged to, and the reason this release leans on a second and third independent resolution rather than on one.

## [3.7.0] - 2026-10-12

### Changed

- **sleepy patches Discord Alpha 351.2.0.** The list offers this build and no longer offers 349.5: Hermes numbers functions per bundle, so a patch written for one release names unrelated functions in another, and the two cannot be carried side by side. Everything the list did before still happens — the same 38 things, switched on the same way.

  Moving the JavaScript patches to a new build is not a rename. Of the 208 entries, 175 are stubs that name nothing release-specific and carry unchanged; 17 name the strings "Promise" and "resolve" through the bundle's own string identifiers, which are renumbered every release, so each is re-assembled against this bundle rather than carried; and 16 are edits of an original body, where the registers, property names and closure ids all move, so each was re-derived against the body this build actually has.

  One entry is gone. It lived in `modules/vibegrations/lib/vibegrationsPreviewNativeSurfaces.tsx`, and 351.2.0 ships no `modules/vibegrations/` module path at all — the feature's screens are not in this build, so there is nothing to patch.

  Two entries that shared a body with another are now given the same replacement as their pair, rather than one of each pair carrying bytes written for a body it no longer matched.

- **Every native edit was re-pointed at this build.** R8 renames what it likes on every release, and ten of the smali patches named classes or methods that had moved: the obfuscated coroutine package, okhttp's obfuscated package, the AndroidX Startup initialiser that loads the emoji font, and the lambda numbering of four experiment delegates and the launch path. Each new name was read out of the build's own code — the emoji initialiser, for instance, by the string its `run()` names — and checked to have the same body at the new name before it was substituted.

### Fixed

- **One entry in the 3.6.0 set carried a truncated body.** Its replacement ended in a `Call2` with two of its four operands written, which is not valid bytecode. Nothing came of it: the entry was an alias of another that was patched correctly, and the two share one body, so the good one was the one that landed. It is fixed rather than carried, and the suite now reads every patched function back with a real Hermes disassembler, which is the check that catches this shape of mistake.

### Security

- The blocklist's generated interceptor no longer trusts a name it did not check. The three obfuscated okhttp names it reads out of the target are now each verified to exist in that target before the method is built from them.

### Notes

- **This release cannot be verified the way 3.6.0 was, and says so.** The 349.5 set was extracted from a build that had been patched and verified by hand, and every function of sleepy's output was compared against that build byte for byte. There is no such build for 351.2.0. What the suite checks instead is that every entry applies to the build it names with nothing skipped, and that every patched function decodes as valid Hermes bytecode. That is a real check and a narrower one: it would not catch a body that assembles cleanly and does something other than what its entry says.

## [3.6.0] - 2026-10-07

### Changed

- **Three more things stop happening while the app starts.** Discord's launch report lists what startup did, and three of its entries are Java-side work this build has no use for.

  The crash reporter's setup ran on a thread of its own that the launch path then waited on. It read the stored OTA bundle for a release name, handed that name to a call that ignores it in a build where reporting is off at the source, recorded a launch metric, and installed a logger whose only callback writes a breadcrumb the same switch drops. All of that is now the single object its caller expects back.

  The jank recorder's launch metric outlived the call it described: the recorder stopped being initialised a release ago, so the entry named work that no longer happened. It goes with the rest of that call.

  The third entry, `BundlerUpdater.init()`, stays, and cannot be removed: the updater's instance accessor raises if that initialiser never ran, and the code that chooses which JavaScript bundle the app loads goes through it.

## [3.5.0] - 2026-10-07

### Fixed

- **Turning the camera on no longer drops the call.** The media engine keeps a registry of its live connections and refused a connection id it already held, by throwing. The path a camera is turned on through is the one that reaches it, from inside a coroutine nothing catches: the app's own JavaScript is never told, so the callback that carries the result is never invoked and the caller waits forever, and the connection the registry refused had already been built and could no longer be freed. That is the shape of a call that ends while the other end still shows you connected. A repeated id now names the newest connection, the one it replaced is freed rather than leaked, and the event is written to logcat under the `MediaEngineCB` tag the other media diagnostics use.

  This is one identified throw on that path, not proof that it is the one you hit. A throw anywhere else in the media engine's coroutine bodies is still fatal, and the only way to tell them apart is a capture: `adb logcat -s MediaEngineCB` while a camera toggle drops a call.

  The defect was found and the fix written down long before this build, but never applied. This release applies it.

- **Shop This Look is gone from profile menus.** Emptying the sheet removed the sheet and the prompt that points at it, but not the menu row that opens them, so the row stayed on screen and opened an empty sheet. The row is now removed where it is built: the experiment that turns the feature on answers no, and the row's visibility comes from that answer. Nothing that renders Shop This Look is reachable any more.

  The row is left alone elsewhere because the code that opens it is shared with an unrelated display-name sheet. The two functions this changes are recorded as named exceptions in the comparison.

- **Stop stops.** Cancelling a run asks the work to stop at its next suspension point, and the download's read loop had none: it reads a socket and writes a buffer in a loop that never yields, so a Stop pressed during a transfer was ignored until the transfer finished. On a 96 MB download over a slow connection that is minutes of an app that looks like it did not hear you. The loop now checks on every chunk, as do the three steps that hold the disk longest. The same check is what stops a cancelled run writing its progress back over the idle screen.
- **Saving the patched APK can no longer freeze the app or end it.** The copy is over a hundred megabytes and ran on the interface thread; it now runs on the IO dispatcher, so the screen stays responsive while it runs. And on Android 9 and earlier the write goes to a public Downloads folder the platform refuses without a storage grant, which sleepy does not ask for, so the write raised an exception no caller caught: it now reports that the save did not happen and points at Share, which works on those versions.
- A save whose output stream could not be opened reported success and left an empty row in Downloads. The row is removed and the failure is reported.
- **Labels no longer clip when text is enlarged.** Six buttons had a fixed height, which crops the label once the system text size is at 200%, the size WCAG 2.2 asks an app to keep usable. They carry a minimum height instead, so the button grows with the text.
- Two text roles were the same size, so a heading and a subheading were indistinguishable. Both now follow the Material 3 baseline scale.

### Changed

- **The app draws its own colours on every device.** Android 12 and later can build an app's palette from the wallpaper, and sleepy offered that. The contrast the app checks is checked against its own scheme, so a wallpaper-derived palette made those checks describe colours the app was not drawing, and whether the result met the contrast thresholds came down to which wallpaper the device had. The scheme sleepy ships is now the default.

### Security

- **A release is checked against the key that is supposed to have signed it.** Verifying a signature proves it is valid, not that it is ours, so any correctly signed APK would have been published; the release build now compares the signing certificate's SHA-256 with a repository secret. Until that secret is set, the check reports itself as not run rather than as a pass.
- An APK whose entry points at a local header that is not there is refused rather than skipped. The skip kept such an entry out of the alignment check while the check still reported success.
- The resource table and binary XML readers bound their arithmetic before using it as a length or a slice bound. A declared count near the top of the 32-bit range wrapped, passed the check, and asked for a multi-gigabyte allocation, which ends the process rather than failing the run.
- A compiled copy of the release-notes script is no longer committed, and Python bytecode is ignored.

## [3.4.1] - 2026-10-05

### Fixed

- **The Board and Wishlist tabs are gone from profiles for good.** Discord ships every screen twice—one compiled with the React Compiler, one not—and picks between them with an experiment. The changes that remove the tabs were only ever made to the uncompiled copy, so the tabs came back for anyone whose account runs the other one. Both copies are edited now.

  Those two components no longer hold the bytes the comparison build does, and the check that compares them records the difference as a named exception with the reason.

## [3.4.0] - 2026-10-05

### Changed

- **The patch list is organised by what you want, not by how the app is built.** It was one card per patch set—38 of them, 22 holding a single switch—under names like `discord_native_systrace`, above 81 blocklist rules grouped by where they matched rather than by what they blocked. Every change now sits in one of seven sections: **Ads and promotions**, **Declutter**, **Tracking and analytics**, **Crash reporting**, **Background work**, **App fixes** and **Network blocking**.

  The list is two levels deep rather than four, and a section's heading is a switch over everything in it, so a whole section goes on or off in one tap.

### Added

- **Links open in your browser rather than in Discord's own tab.** Discord draws web links in a tab inside its own window, under its own toolbar, with its session. They now open in the browser you actually chose. Nothing new runs: Discord already ships the path this uses, and the change also covers links that ignore your browser setting.
- **An incoming call no longer freezes the screen.** The screen that appears when a call arrives waited on the caller's avatar, and that wait ran on the interface thread and included a network round-trip. It no longer waits. The caller's name still shows; only the picture is dropped.
- **The message cache is bounded.** The cache the app fills while drawing messages had no limit, and its expiry was refreshed every time an entry was read, so it grew for as long as the app ran. It is now capped at the same size the app already uses for message previews.

### Fixed

- Counts no longer read as "Show the 1 items", or as "1 patches available" for a source offering one set.
- **The camera no longer writes a frame-rate log while it is on.** It built a string and wrote a logcat line every two seconds for the whole length of a video call, and nothing reads either. The check that reports a frozen camera still runs.
- **Launch no longer loads two things the app does not use.** Discord loads a jank recorder at startup to call two methods that do nothing, and builds a list of seven exception classes to decide whether a network error is worth reporting—in a build that reports nothing. Neither is loaded now.

## [3.3.1] - 2026-10-04

### Changed

- A release is built on a named Ubuntu image rather than the `ubuntu-latest` label, which moves whenever GitHub promotes a new Ubuntu release. The environment a build runs in can no longer change without a change in this repository.
- One dependency used only by the tests is updated to its current version.
- **Release notes no longer reproduce every known limitation.** They carried the same twelve long paragraphs under every version, which buried the entry for the release itself. A gap belongs to the build being patched rather than to the release that mentions it, so the notes now give the count and a link, and the limitations are written in full in the README and in the manifest each release attaches.

No part of the app changed in this release. It carries three build changes, and exists so the published build includes them.

## [3.3.0] - 2026-10-04

### Changed

- **The Discord patch list is switches for features now, not for functions.** It listed one row per patched function: 204 rows, 95 of them named only by a number and the rest named after the minified JavaScript they were found in. You could not tell what a row did, and one thing you would recognise—guild tags, say—was seven rows. Each feature is now one switch, named for what it does, with the functions it patches listed in its technical panel.

  A selection saved by an earlier version names functions, so it is read as the features that now cover them.

### Fixed

- The arrow on a source card keeps its size. A long name, such as OctoGram's, took the width it needed and squeezed the arrow into whatever was left.

## [3.2.0] - 2026-10-04

### Fixed

- The Telegram Premium and Telegram Business rows now leave the app's Settings screen as well as the settings list on your profile. Both were hidden on your profile and stayed visible one screen over, because that screen builds its rows separately and the changes had never covered it.

## [3.1.0] - 2026-10-04

### Changed

- The Discord patch set covers 38 more functions, on the same 349.5 build, so nothing has to be downloaded again. The additions take in the guild shop, avatar decorations, nameplates and profile frames, guild tags, the Shop This Look sheets, the wishlist grids, the Quests screen and its settings, the Game Profile Shop carousel and the voice guild tag.

### Added

- **OctoGram: links open in your browser by default.** A fresh install sends web links to the phone's browser instead of the in-app viewer. A device that has already changed the setting keeps its choice, and Telegram's own links still open inside the app.
- **OctoGram: the Telegram Business row and the two commands behind it are removed**, and the premium screens that open as a bottom sheet are closed as well. The Business feature itself is untouched.

## [3.0.0] - 2026-10-03

### Changed

- **Discord support moves to 349.5, and 348.5 can no longer be patched.** Discord renumbered every function in its JavaScript bundle in this release, so the patch set was recorded again from the checked build: 166 functions, up from 145, of which 164 match it byte for byte and two do not (below). A run that has already downloaded 348.5 has to start again.
- A build whose version cannot be identified is refused rather than patched, and says so.

### Added

- The EmojiCompat load runnable is stubbed, which this build had missed.

### Removed

- OctoGram 3.6.0 is no longer recognised, because no source here offers it.

### Fixed

- Two Discord JavaScript patches no longer throw when their callers run. Both functions were stubbed to `undefined`, and each has a caller that reads a property off the result, which throws a `TypeError`. One now returns an object carrying `{confirmed: false}` from the confirmation handler and the other an empty array from the typing-indicator hook, which is the shape each caller reads.

## [2.1.0] - 2026-10-03

### Added

- **Downloads are checked against published hashes.** Every file sleepy fetches has a SHA-256 in the manifest, and a file that does not match is refused rather than patched. Each split is checked on its own, and also for the content its configuration implies: an ABI split must carry native libraries, a density split must carry resources, and a language split must carry a resource table.
- A scheduled workflow refreshes those hashes from the source and opens a pull request with the changes. It refuses to publish a hash for a file it cannot identify. The change arrives as a pull request rather than a commit, because the identity checks cannot tell a rebuilt APK that keeps the same package name and version from the genuine one.

### Changed

- The manifest records each split as an object carrying its URL, hash and size. Manifests written in the older form still load.

### Security

- The workflow holds write permission only in the job that opens the pull request; the job that downloads and hashes has read-only access.

## [2.0.0] - 2026-10-03

### Changed

- **The signing key moved into the Android keystore, and this is a breaking change.** sleepy signs with an EC P-256 key the platform generates and holds, preferring a secure element where the device provides one. No key material and no password are written to disk.

  A build signed by an older installation is a different app identity from one signed by this version, so Android will not install one over the other. Uninstall the apps you patched previously and patch them again.

- Failed steps decide the outcome of a run. A build where the resource-table rename, the signature check or the alignment failed is reported as failed, its output is deleted, and it is not offered for saving. A run where one patch failed is reported as incomplete, because the file is still a valid build.

### Added

- The clone package name is checked before the build starts, and the field says what is wrong with it.
- The app reports whether it actually checked a download against a published hash: unchecked, matched, or mismatched.
- Contrast on light-theme dividers and on the failure screen in dark theme meets the APCA thresholds.

### Fixed

- The test suite skips the tests that need the second APK instead of failing, which made continuous integration report a failure on every clean run.

### Security

- The software signing path is deleted, along with the password file it wrote beside the key.
- The release workflow passes the tag through the environment rather than substituting it into shell text.
- The app no longer exposes its files directory to other apps. Only the cache directory holding the finished APK is shared.

### Removed

- The software certificate builder and the PKCS#12 key path.

## [1.7.0] - 2026-10-03

### Added

- Three Discord JavaScript patches: stubs for the voice call analytics functions `_trackStartSpeaking` and `_trackStartListening`, and for the 60-second voice state interval callback. These stop recurring allocations during calls that caused stalls.
- Four more Discord patches: frame metrics and jank recording are skipped, blocking OTA recovery after a crash is disabled, and EGL setup returns early once the renderer has been released, which stops leaked graphics contexts.

### Fixed

- A build with a changed package name no longer fails to start. Renaming the package left the resource table's copy of the name pointing at the original, so every name-based resource lookup returned 0 and the app stalled on its splash screen.

## [1.6.0] - 2026-09-28

### Changed

- The permission list no longer offers a switch over a declaration the patch removes from every build. Those rows state that the permission is removed and why, because which permissions sleepy removes is a fact about the build rather than a choice about the run.
- Three checks that compare the patched JavaScript bundle against the recorded one now run instead of skipping. Each read a bundle out of a temporary directory that a restart emptied, so all three compared nothing while reporting success.

### Fixed

- The crash reporter's native libraries are no longer left in the finished APK, along with the Play source stamp. The check ran only against the base APK's own entries, and the libraries arrive from the ABI split, which the merge adds afterward.
- Six permission declarations are removed: the contact list, the advertising ID, Privacy Sandbox attribution, and three for services the patched app does not bind. They are removed on Discord builds only, because OctoGram uses the contact list for real.
- The three Google Analytics components are marked `android:enabled="false"`, which stops the platform starting them.
- The split-install metadata is removed, along with the resource-table row naming it.
- Some translated strings in arrays and plurals showed the wrong text, because they kept the string-pool position they held in the split they came from. 48 values were affected.

## [1.5.0] - 2026-09-27

### Added

- The permissions each app asks for ship with sleepy, so the permission switches are on screen as soon as you pick a target rather than after the whole download.
- You can check that list against the build it applies to. Checking downloads the build and reports any difference in full.
- Settings lists where every target is downloaded from, and marks the one you opened last.
- The patched build carries the images a density split holds and the strings the language splits hold, and the resource table is rebuilt around them so they are reachable. A set of tables that cannot be reconciled is not used, and the reason is reported.
- The manifest edits are applied to the compiled manifest. The crash reporter's two provider declarations, the three Play split markers and the AppsFlyer query go with the switches that make them pointless. The RPC service is no longer exported on any build, because it was exported with no permission on it and no check on its caller.

### Changed

- The permission list comes before the patch list.
- The version in settings is the version the build was made as, read from this file.
- Corner radii come from the Material 3 shape scale rather than being chosen per screen.
- Two unused libraries are no longer declared, so the APKs are smaller.

### Removed

- A build output file committed to the repository by mistake.

### Fixed

- The rebuilt APK keeps the storage method its entries had, so the resource table stays aligned and the JavaScript bundle is mapped rather than unpacked at startup.
- A check that cannot be made is reported as unchecked rather than as a pass.
- The permission section is usable: nothing was listed until a whole APK had been downloaded for it, and it sat after every patch set.
- In dark mode, the borders of components with no fill of their own meet 3:1 against every surface.
- The JAR signature is no longer reported as failing. It does not apply to any build sleepy makes, since it is honored only in Android 7.0 and earlier, and the step now says so while the v2 and v3 signatures are still checked.

### Security

- Releases are no longer signed with the debug key, which every Android SDK installs and publishes.
- The password on the signing key sleepy creates on your device is generated for that installation rather than written into the source.
- Releases from this version are signed with a new key, so this build does not install over 1.0.0 to 1.4.0.

## [1.4.0] - 2026-09-27

### Added

- OctoGram's own crash reporter can be switched off, which stops the handler being installed while the app starts.
- The premium rows in the profile's settings list can be hidden: the Telegram Premium row, the Send a Gift row, and the combined premium-sections row that would otherwise appear in their place. It is one switch, because hiding the first row on its own puts a different premium row on screen.

### Changed

- Every OctoGram set is listed change by change with a switch of its own, described in the same terms as the Discord sets.
- The technical panel under a set lists every change with its own title.

### Removed

- The five OctoGram changes written for 3.6.0, which could never run.

### Fixed

- Patching Discord no longer runs the phone out of memory while the merged APK is rebuilt. The inputs now move through disk one at a time.

## [1.3.0] - 2026-09-27

### Added

- Individual patches can be switched on or off instead of choosing a whole set at once.
- The JavaScript patches are grouped by the feature they affect, and each one says what it does.
- The Discord network blocklist is listed rule by rule, each with its own switch and description.
- A switch that cannot be moved says why. A rule another enabled rule already covers is marked "Already covered by" and names it; the two checks the blocklist needs are marked "Required".

### Changed

- A set's switch reports whether none, some or all of its changes are selected. Tapping a partly selected set selects the rest of it.
- Switching off a rule that covered other rules makes those rules switchable again straight away.

## [1.2.0] - 2026-09-27

### Added

- A network blocklist for Discord. Requests to 20 tracking, advertising, survey and payment-monitoring hosts and to 61 Discord API paths are answered with an empty response instead of being sent. Spotify is included on purpose, so the Spotify integration stops working.

### Changed

- The patch list says that a set with no fixed changes is built for the APK you selected, instead of showing "0 hooks" for it.

## [1.1.0] - 2026-09-27

### Changed

- Discord's own JavaScript is patched change for change, 142 functions, each written to the bytes the checked build carries.

### Removed

- The crash reporter's own libraries, and the data it writes on a crash, when crash reporting is switched off. The mechanism is gone from the patched APK rather than disabled.

### Fixed

- The alignment check no longer fails valid APKs. Only uncompressed entries need aligning, and the rule was being applied to compressed entries too.
- The JavaScript patches are tied to the exact Discord release they were made for.

## [1.0.0] - 2026-09-27

### Added

- sleepy: download a supported app, patch it on your device, sign the result and save it, with no other software needed.
- Clone mode, which gives the patched app a package name of its own so it installs next to the original.
- A report of every step: what was applied, what was skipped and why, what failed, and the signature, alignment and size read back from the finished APK.
- Discord: crash reporting, telemetry, debug logging, deep-link attribution and the monitoring settings are all switched off, and the contact list, contact photos and list of installed apps are no longer read.
- Discord: the JavaScript that powers the app is patched on your device, so gift buttons, quests and Nitro upsells stop appearing and the animation on profile cards is dropped.
- Discord: one shared disk cache for the app's network clients instead of one each, and cheaper performance settings.
- Discord: calls stay alive when you leave the app, and the video player no longer blocks the interface while it starts.
- OctoGram: sponsored messages, photo viewer ads and sponsored search results are removed, along with the requests that fetch them.
- OctoGram: the update check is off, and Firebase analytics, A/B testing, remote configuration and telemetry uploads are disabled.
- OctoGram: the app's own logging is silenced, along with the diagnostic files it uploads.
- OctoGram: most entry points to the premium paywall are blocked.

### Fixed

- Patching no longer runs out of memory, because sleepy edits only the classes it needs.
- The patched Discord APK includes the ARM64 native libraries from the separate split, so it starts.
- JavaScript changes are checked against the app's code before they are written.
- OctoGram changes that matched more than one place in the code are resolved, and each change applies only to the version it was made for.

[Unreleased]: https://github.com/Lyceris-chan/sleepy/compare/v3.7.1...HEAD
[3.7.4]: https://github.com/Lyceris-chan/sleepy/compare/v3.7.3...v3.7.4
[3.7.3]: https://github.com/Lyceris-chan/sleepy/compare/v3.7.2...v3.7.3
[3.7.2]: https://github.com/Lyceris-chan/sleepy/compare/v3.7.1...v3.7.2
[3.7.1]: https://github.com/Lyceris-chan/sleepy/compare/v3.7.0...v3.7.1
[3.7.0]: https://github.com/Lyceris-chan/sleepy/compare/v3.6.0...v3.7.0
[3.6.0]: https://github.com/Lyceris-chan/sleepy/compare/v3.5.0...v3.6.0
[3.5.0]: https://github.com/Lyceris-chan/sleepy/compare/v3.4.1...v3.5.0
[3.4.1]: https://github.com/Lyceris-chan/sleepy/compare/v3.4.0...v3.4.1
[3.4.0]: https://github.com/Lyceris-chan/sleepy/compare/v3.3.1...v3.4.0
[3.3.1]: https://github.com/Lyceris-chan/sleepy/compare/v3.3.0...v3.3.1
[3.3.0]: https://github.com/Lyceris-chan/sleepy/compare/v3.2.0...v3.3.0
[3.2.0]: https://github.com/Lyceris-chan/sleepy/compare/v3.1.0...v3.2.0
[3.1.0]: https://github.com/Lyceris-chan/sleepy/compare/v3.0.0...v3.1.0
[3.0.0]: https://github.com/Lyceris-chan/sleepy/compare/v2.1.0...v3.0.0
[2.1.0]: https://github.com/Lyceris-chan/sleepy/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.7.0...v2.0.0
[1.7.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.6.0...v1.7.0
[1.6.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.5.0...v1.6.0
[1.5.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/Lyceris-chan/sleepy/releases/tag/v1.0.0
