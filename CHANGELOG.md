# Changelog

This file documents all notable changes to this project.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [3.2.0] - 2026-10-04

### Fixed

- **The Telegram Premium and Telegram Business rows leave the app's Settings screen too, not only the settings list on your profile.** Both rows were already hidden in the list under your profile, by the two switches that hide them, and they stayed visible one screen over. The reason is that the Settings screen builds its rows in a class of its own rather than sharing the profile's list, and the edits ported from the desktop reference had never been derived against it. Each row is drawn only while the account is not subscribed, and each one opens the premium screen when tapped, so both are the same upsell the two switches already refuse.

  Telegram Stars and TON are separate products rather than Telegram Premium and still show, on both screens, as before.

## [3.1.0] - 2026-10-04

### Changed

- **38 more Discord functions are patched, on the same 349.5 build.** There is nothing new to download: the app version and the manifest are unchanged, so a run that has already fetched 349.5 patches it with the wider set. The additions cover the surfaces this release of Discord moved or added: the guild shop and its preview gates, avatar decorations, collectible nameplates and profile frames, guild tag chips and the gates that decide whether one is shown, the Shop This Look sheets, the wishlist grid and the suggestions grid a profile renders, the Quest Home screen, the two Quests toggles in Data & Privacy, the Game Profile Shop carousel, the voice guild tag, and other users' profile tab list, which is rebuilt to build Main and Activity only with no Board and no Wishlist tab.

  Two of the additions are byte-identical closures that Hermes stores once, so each pair shares a single body. A shared body can hold only one replacement, and where the two replacements differ the larger is written there and the smaller is relocated and declared with a size of its own. The desktop reference has both members of each pair end up relocated instead. The finished bundles therefore place those four bodies differently, and every one of them still reads back byte for byte as the reference has it; the bundle comparison checks each function's body on both sides rather than its position.

### Added

- **OctoGram: links open in your browser by default.** A fresh install sends http and https links to the phone's browser rather than to OctoGram's in-app viewer. The setting stays in the app, so a device that has already changed it keeps that choice, and Telegram's own links still open inside the app.

- **OctoGram: the Telegram Business row and the two commands behind it are gone.** The row leaves the settings list on your profile, and `/premium` and `/business` stop opening the premium screen. All three are one Telegram Premium upsell rather than an entry to the Business settings, so they are one switch. The Telegram Business feature itself is untouched.

- **OctoGram: the premium screens that present as a bottom sheet are closed as well.** The existing guard covers the entry points that go through the navigation router; eight more present the paywall as a sheet and were not covered. The guard sits in the helper that every sheet presentation goes through and refuses only the premium screen, so every other sheet still opens.

## [3.0.0] - 2026-10-03

### Changed

- **Discord support moves to 349.5, and 348.5 is no longer patchable.** Discord adopted the React Compiler in this release, which renumbered every function in its JavaScript bundle and removed the names the previous mapping relied on. The patch set was therefore re-derived from the reference build for this release rather than re-pointed: 166 functions, up from 145, each verified byte for byte against that build.

  A run that has already downloaded 348.5 has to start again on 349.5. The manifest now names only the newer build.

- A build whose app version cannot be identified is refused rather than patched. Version-tagged patches are written against obfuscated class names that do not survive a release, and a name that happens to resolve on a different build can edit an unrelated method. The refusal is reported as a skipped step with its reason, so it reaches the screen rather than only the log.

### Added

- A patch the reference makes that this build was missing: the EmojiCompat load runnable is stubbed so it does not run.

### Removed

- OctoGram 3.6.0 is no longer recognised. The reference scripts carry entries for it and no source here offers that build, so those entries could never run.

### Fixed

- **Two Discord 349.5 JavaScript patches no longer throw when their callers run.** The reference suite stubs function 49956, the `SHOW_CONFIRM_MODAL` handler that the vibegrations RPC interceptor registers, and function 58239, the uncompiled variant of the `useTypingUserIdsForDisplay` hook, to `undefined`. Neither id holds the upsell button it held in 348.5, and each function has one caller that reads a property off the return: 49954 reads `.result` off the handler's return and `hasTypingIndicatorContent` reads `.length` off the hook's. Reading a property of `undefined` throws a `TypeError`, so the reference's own stub threw wherever those callers ran, which is the confirmation an agent requests and the chat input of a channel with no slowmode rate limit. Both stubs now return a value of the shape the caller reads, an object carrying `{confirmed: false}` and an empty array.

  This is a deliberate divergence from the desktop reference build, so those two function bodies no longer match it. The bundle comparison carries a named exception for exactly those two ids, which checks each body on both sides, and the manifest's known limitations record what the reference does and what this build does instead.

## [2.1.0] - 2026-10-03

### Added

- **Downloads are checked against published hashes.** Every file sleepy fetches now has a SHA-256 recorded in the manifest, and a file whose contents or size do not match is refused rather than patched. Previously the manifest carried no hash for the app or for any of its splits, so nothing was verified and the result screen could only report the check as not performed.

  Each split's hash covers that split alone, so a substituted split is caught even when the rest of the download is genuine. A split is checked for the content its configuration implies as well: an ABI split must carry native libraries, a density split must carry resources, and a language split must carry a resource table.

- A scheduled workflow refreshes those hashes from the source. It downloads every file, verifies that each one is the app and version the manifest claims, and opens a pull request with the changes. It refuses to publish a hash for a file it cannot identify, so a source serving something other than the expected build fails the run instead of being recorded as correct.

  The change arrives as a pull request rather than a direct commit: the identity checks cannot tell a rebuilt APK that keeps the same package name and version from the genuine one, so a workflow that committed its own result would record a hash for exactly that file. A person sees the old and new hashes before the app trusts them.

### Changed

- The manifest records each split as an object carrying its URL, hash and size, rather than as a bare URL. Manifests written in the older form still load.

### Security

- The workflow that refreshes the hashes holds write permission only in the job that opens the pull request; the job that downloads and hashes has read-only access.

## [2.0.0] - 2026-10-03

### Changed

- **The signing key moved into the Android keystore, and this is a breaking change.** sleepy now signs with an EC P-256 key the platform generates and holds, preferring a secure element where the device provides one. The key cannot be read by any application, so no key material and no password are written to disk. The software key file and its password file are gone, and an existing installation's key file is ignored rather than migrated.

  The consequence is that a build signed by an older installation is a different app identity from one signed by this version. Android will not install one over the other, so apps you patched previously have to be uninstalled and patched again.

- Failed steps now decide the outcome of a run. A build where the resource-table rename, the signature check or the archive alignment failed is reported as failed, its output is deleted, and it is not offered for saving. A run where an individual patch failed is reported as incomplete, because the file is still a valid build.

### Added

- The clone package name is checked before the build starts, and the field reports what is wrong with it instead of accepting a name the resource table cannot hold.
- The app reports whether it actually checked a download against a published hash: unchecked, matched, or mismatched. It previously reported a check whenever a hash field existed, including when the field was empty.
- Contrast on light-theme dividers and on the failure screen in dark theme meets the APCA thresholds, with the measured values recorded in the tests.

### Fixed

- The test suite failed on a machine without the reference apps instead of skipping those tests, which made continuous integration report a failure on every clean run.

### Security

- The legacy software signing path is deleted, along with the password file it wrote beside the key. A device whose keystore refuses the request now fails the run rather than creating a key on disk.
- The release workflow passes the tag through the environment rather than substituting it into shell text, where a crafted tag could have run commands in the job that holds the signing secrets.
- The app no longer exposes its files directory to other apps through its file provider. Only the cache directory holding the finished APK is shared.

### Removed

- The software certificate builder and the PKCS#12 key path.

## [1.7.0] - 2026-10-03

### Added

- Three Discord JavaScript bytecode patches, bringing the total from 142 to 145 and matching the reference patch suite: stubs for the voice call analytics functions `_trackStartSpeaking` (function 68080) and `_trackStartListening` (68081), and for the 60-second voice state interval callback (121591). These stop recurring metadata allocations and speaking-state transition work that fed an analytics consumer that is already stubbed, which caused call stalls and unresponsive periods during voice calls.
- Four Discord patches that complete the set against the reference suite:
  - Frame metrics aggregator setup is skipped and jank session recording does not start, so per-frame statistics are no longer collected for a screen that is not being looked at.
  - Blocking OTA recovery after a crash is disabled, which removes a wait on the main thread while the app restarts.
  - EGL setup returns early once the renderer has been released. Without it, a teardown that jumps the render queue can create a graphics context after cleanup has already run, and nothing frees it—the reference records 242 leaked contexts before `eglCreateContext` failed and stopped the render thread.

### Fixed

- A build with a changed package name no longer fails to start. Renaming the package left the second copy of that name—the one inside the resource table—pointing at the original, and the platform matches name-based resource lookups against that field. Every `getIdentifier(name, type, getPackageName())` call returned 0 as a result, and the app stalled on its splash screen instead of opening. The table's copy of the name now moves with the manifest's.

## [1.6.0] - 2026-09-28

### Changed

- The permission list no longer offers a switch over a declaration the patch removes from every build it makes. Those rows show that the permission is removed and give the reason, instead of showing a control that changes nothing: which of an app's permissions sleepy removes by itself is a fact about the build being patched rather than a choice about the run, and a switch whose state does not match what the build produces is misleading.
- Three checks that compare the patched JavaScript bundle against the reference build run instead of skipping. Each of them read a copy of a bundle out of a temporary directory, and a restart emptied that directory, so all three were reported as skipped while comparing nothing—a passing run whose skipped count recorded that the comparison had not run. They read the bundles out of the two APKs now, the build the patch set was extracted from and the desktop build's patched output, which are the artifacts the comparison is about.

### Fixed

- The crash reporter's own native libraries were still in the finished APK—`libsentry.so` and the Android shim over it, about 740 KB—in every build, including the ones whose step list reported them as removed. The check for entries to leave out ran only against the base APK's own entries, and the base split carries no native libraries at all: they arrive from the ABI split, which the merge adds afterward, so the names matched no entries in that APK. The same rebuild kept `stamp-cert-sha256`, the Play source stamp that records the signed build an APK was derived from, in an APK signed with a key of its own. Both go now, from whichever part of the merge they arrive in.
- Six permission declarations the desktop build strips were kept: the contact list, the advertising ID, Privacy Sandbox attribution, and three for services the patched app does not bind—Samsung's app information, the Huawei app market's common data and Play's install-referrer binding. The patched build does not read any of them, so each was a capability the app requested but did not use. They are removed on Discord builds and by name, because OctoGram declares the contact list for real and syncs the address book through it: applying that rule to every app removes a live permission from that one.
- The three Google Analytics components the SDK declares were left switched on. They are inert here—the patches cut every path that reports through one—but the desktop build sets `android:enabled` false on all three, and the platform does not start a component declared that way. The manifest matches that build now.
- The split-install metadata was kept too. `res/xml/splits0.xml` lists the configuration splits an installation has and is read by the installer that puts them there, and an APK that carries all of its splits inside it is not a configuration split—its contents are for an installation that no longer exists. It goes along with the row in the resource table for it, because the two have to go together: a table resolving a path to a file the APK does not hold is a defect by itself.
- Some translated strings showed the wrong text. 48 string values inside the resources that carry lists of their own—arrays and plurals—kept the string-pool position they held in the split they were merged in from, so they resolved to whichever string now sat at that position: German playback-speed labels came out as a resource path. Values that are a plain string were unaffected, which is why it went unnoticed, and the check that should have caught it worked out where a value sits the same way the merge did—so it reproduced the mistake instead of finding it. Both are fixed.

## [1.5.0] - 2026-09-27

### Added

- The permissions each supported app asks for are listed in sleepy itself, so the permission switches are on screen as soon as you pick a target. They used to be read out of the APK you were patching, which meant the section stayed empty until the whole download had finished, and there was no way to see what an app asks for without downloading it.
- You can check the permission list against the build it applies to. Checking downloads the build and compares what it declares with the list, and any difference is shown in full—including an app asking for something the list does not cover, which is the one case where a permission has no switch and no description to read.
- Settings now lists where every target is downloaded from, not only the one you last opened, and marks the one you opened last.
- The patched build now carries the images a configuration split holds for one screen density and the strings the two language splits hold, which a base split on its own carries none of. They go back at the paths the desktop build has them at, so the finished APK's file set and its size come to match that build's—and the resource table is rebuilt around them while the APK is patched, so they are reachable rather than merely present. Every path in the rebuilt table is a file the APK holds, every resource file the APK holds has an entry in the table, and each source's resource ids, configurations and compiled file paths are kept as they were. The table is read back and checked before the APK is rebuilt, and a set of tables that cannot be reconciled is not used: the reason is reported, and the base's table stays in place.
- The manifest edits the desktop build makes are applied here too, to the compiled manifest rather than to its text. The two Sentry provider declarations go with the crash-reporting switch, the three Play split markers go when the splits have been merged into one APK, and the AppsFlyer package-visibility query goes with the deep-link switch that stops the only thing that used it. The RPC service is not exported any more, on every build and behind no switch: it is exported with no permission declared on it and no check on its caller, so any app on the device can bind it and publish presence frames as you. Each of those is reported as its own step, so a build that no longer declares one reports that.

### Changed

- The permission list now comes before the patch list instead of after all of it.
- The version in settings is the version the build was made as. It is read from the changelog's newest release when the build is made, so the app, the changelog and the release tag agree; it used to be a number written into the screen, which is why the app still reported 1.0.0 at 1.4.0.
- Corner radii now come from the Material 3 shape scale rather than being chosen per screen, so cards, dialogs, chips and text fields are rounded the way Material 3 rounds those components. They were 34, 26, 18, 12 and 6 dp where the tokens are 28, 16, 12, 8 and 4.
- Two libraries with no callers are no longer declared, and the debug build no longer carries the Compose preview tooling. The APKs are smaller as a result.
- A test that needs a file from outside the repository is reported as skipped rather than as a pass when that file is not there, so a passing run reports that the check ran, not that it could not run.

### Removed

- A build output file that had been committed to the repository by mistake is no longer tracked in it.

### Fixed

- The rebuilt APK compressed the two entries it is supposed to carry through unchanged. A replaced entry is dropped from the copied stream and rewritten from what replaced it, and the storage method it had in the source was read after that drop rather than before—so every replaced entry fell back to DEFLATE. The resource table and the JavaScript bundle are both stored uncompressed in Discord's APK, and both came out compressed: the table no longer aligned to a 4-byte boundary, and the bundle had to be unpacked at startup instead of being mapped. Both now keep the method their source had.
- The aligned-APK check treated "no entry was measured" the same as "every entry aligned", so a finished APK whose archive could not be read was reported as correctly aligned. A check that cannot be made is now reported as unchecked.
- The permission section could not be used at all. Nothing was listed until a whole APK had been downloaded for it, and even then it sat after every patch set, so its switches were out of reach; the list ships with the app now and the section is the first thing on the screen.
- In dark mode, the borders of components that carry no fill of their own—an unchecked switch, an outlined text field—were too faint to see: 2.0:1 against the surface where the accessibility guidelines ask 3:1. They are drawn in a lighter tone that clears 3:1 on every surface of the dark scheme.
- The JAR signature on the finished APK was reported as failing on every build, because a check that does not apply was treated as a check that failed. JAR signing is honored only in Android 7.0 and earlier, so a build that requires a newer version does not have it read, and that is every build sleepy makes. The signature is written and is valid, and there was no verdict to report on it. The step now reports that the scheme does not apply to this build and gives the version that puts it out of scope, while the v2 and v3 signatures are still checked and still fail the step when they do not verify.

### Security

- sleepy releases are no longer signed with the debug key. Every Android SDK installs that key and publishes it, so a release signed with it is one that anyone can build a newer version of. Without a signing key of its own, a release is left unsigned, and its filename records that.
- The password on the signing key sleepy creates on your device is generated for that installation rather than written into the source. It is kept in the app's private storage beside the key, which is what actually protects it; what changes is that a password read out of the source no longer opens anyone's key, and one that leaks opens a single installation instead of all of them.
- Releases from this version are signed with a new key. The key the earlier releases were signed with is no longer used, so this build does not install over 1.0.0 to 1.4.0—Android treats two builds as the same app only when the package name and the signing key both match. Uninstall the earlier version first.

  Uninstalling has a second effect worth knowing before you do it. Uninstalling removes sleepy's private storage, and the key sleepy generates on your device to sign the apps it patches is kept there. After reinstalling, sleepy makes a new one, so patched apps you still have do not accept patches signed by the new install. Uninstall those too, and patch them again, if you want to be able to update them.

## [1.4.0] - 2026-09-27

### Added

- OctoGram's own crash reporter can be switched off. It installs a crash handler of its own while the app starts, which writes the stack trace of a crash into the app's storage and raises an "OctoGram just crashed!" notification the next time the app opens; with it switched off the handler is not installed, so a crash writes nothing to the app's storage. It is a switch of its own because that handler is installed regardless of the logging flag the logging switch pins false.
- The premium rows in the profile's settings list can be hidden: the Telegram Premium row, the Send a Gift row, and the combined premium-sections row that would otherwise appear in their place. It is one switch rather than three, because hiding the first row on its own puts a different premium row on screen instead.

### Changed

- Every OctoGram set is now listed change by change, with a switch of its own, as the Discord sets already were. Silencing OctoGram's log is two choices rather than one—its emitters and the helpers that upload a log off the device—and the paywall's two code paths are separate choices too.
- The OctoGram sets are described in the same terms as the Discord ones: what you lose or gain, rather than the name of the code the change edits. Each of OctoGram's twelve diagnostic log emitters now has a description of what it writes and at which level, instead of sharing one sentence between them.
- The technical panel under a set now lists every change with its own title and what it does, so a set whose changes are not listed one by one can still be read before you patch.
- The notes on what is left running are more precise. The app ships no Firebase analytics library, so the set switches off A/B testing, remote configuration and the telemetry transport rather than an analytics library; and the profile's premium rows are named as hidden by a switch of their own rather than by the paywall patch.

### Removed

- The five OctoGram changes written for 3.6.0. Only a 3.6.1 build can be downloaded here, so they cannot run, and listing them suggested otherwise.

### Fixed

- Patching Discord no longer runs the phone out of memory while the merged APK is rebuilt. The merged native libraries, the base APK and the archive being written were all held as byte arrays at once, which is more than a phone's heap; they now move through disk one at a time, so the rebuild's peak memory is a single buffer, not the whole archive.

## [1.3.0] - 2026-09-27

### Added

- Turn individual patches on or off instead of choosing a whole set at once.
- The 142 JavaScript patches are grouped into 18 features, such as gift buttons and quests, and each one shows what it does before you switch it on.
- The Discord network blocklist is listed rule by rule: 20 host rules and 61 API path rules, each with its own switch and its own description.
- An unavailable switch now shows why it cannot be moved. A rule that another enabled rule already covers is marked "Already covered by" and shows which rule covers it; the two checks the blocklist cannot work without are marked "Required".

### Changed

- A patch set's switch now reports whether none, some or all of its changes are selected, instead of rounding a part-selected set to on or off. Tapping a partly selected set selects the rest of it, and turning a set off and on again restores all of its changes.
- Switching off a rule that covered other rules makes those rules switchable again straight away.

## [1.2.0] - 2026-09-27

### Added

- A network blocklist for Discord. Requests to 20 tracking, advertising, survey and payment-monitoring hosts and to 61 Discord API paths are answered with an empty response instead of being sent. Spotify is included on purpose, so the Spotify integration stops working.

### Changed

- The patch list now reports that a set which has no fixed changes is built for the APK you selected, instead of showing "0 hooks" for it.

## [1.1.0] - 2026-09-27

### Changed

- Discord's own JavaScript code is now patched to match the desktop build change for change: 142 functions, each written to the same bytes that build ships.

### Removed

- The crash reporter's own libraries, and the data it writes on a crash, when crash reporting is switched off. The mechanism is gone from the patched APK rather than disabled.

### Fixed

- sleepy no longer reports a ZIP alignment failure for APKs that are valid. Only uncompressed entries need aligning, and the check was applying the rule to compressed entries too, which failed on every run.
- The JavaScript patches are now tied to the exact Discord release they were made for. A different release is not patched with changes that point at unrelated code.

## [1.0.0] - 2026-09-27

### Added

- sleepy: download a supported app, patch it on your device, sign the result and save it. The work happens on your phone, and no other software is needed.
- Clone mode, which gives the patched app a package name of its own so it installs next to the original.
- Discord: crash reporting switched off, including the native crash handlers.
- Discord: telemetry switched off, including the advertising ID, install attribution and performance reporting.
- Discord: debug logging silenced, background log capture stopped, and the settings that turn frame and resource monitoring off.
- Discord: deep-link attribution switched off, and the contact list, contact photos and the list of installed apps are no longer read.
- Discord: the JavaScript code that powers the app is patched on your device, so gift buttons, quests and Nitro upsells stop appearing and the animation on profile cards is dropped.
- Discord: one shared disk cache for the app's network clients instead of one each, and cheaper performance settings, so the app uses less memory and downloads less.
- Discord: calls fixed to stay alive when you leave the app, and the video player no longer blocks the interface while it starts up.
- OctoGram: sponsored messages in channels, full-screen ads in the photo viewer and sponsored channels in search results are all removed, along with the requests that fetch them.
- OctoGram: the GitHub update check is switched off, and Firebase analytics, A/B testing, remote configuration and telemetry uploads are all disabled.
- OctoGram: the app's own logging is silenced, along with the diagnostic files it uploads.
- OctoGram: most entry points to the premium paywall are blocked. Some screens still appear; see the limitations in the release notes.
- A report of every step: what was applied, what was skipped and why, what failed, and the signature, alignment and size read back from the finished APK.

### Fixed

- Patching no longer runs out of memory. sleepy edits only the classes it needs instead of loading the whole app's code at once.
- The patched Discord APK now includes the ARM64 native libraries from the separate split file, so it starts instead of stopping on the first library it loads.
- JavaScript changes are checked against the app's code before they are written, so a change that does not match is not applied.
- OctoGram changes that matched more than one place in the code are resolved, and each change now applies only to the app version it was made for.

[Unreleased]: https://github.com/Lyceris-chan/sleepy/compare/v3.0.0...HEAD
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
