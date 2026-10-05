# Changelog

This file documents all notable changes to this project.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [3.4.1] - 2026-10-05

### Fixed

- **The Board and Wishlist tabs are gone from profiles for good.** Discord ships every screen twice—one compiled with the React Compiler, one not—and picks between them with an experiment. The changes that remove the tabs were only ever made to the uncompiled copy, here and in the desktop suite this build is ported from, so the tabs came back for anyone whose account runs the other copy. Both copies are edited now.

  This is a deliberate departure from the desktop suite, which leaves the compiled copy alone. Those two components no longer match that build, and the bundle comparison records them as named exceptions with the reason.

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

- The Telegram Premium and Telegram Business rows now leave the app's Settings screen as well as the settings list on your profile. Both were hidden on your profile and stayed visible one screen over, because that screen builds its rows separately and the changes ported from the desktop suite had never covered it.

## [3.1.0] - 2026-10-04

### Changed

- The Discord patch set covers 38 more functions, on the same 349.5 build, so nothing has to be downloaded again. The additions take in the guild shop, avatar decorations, nameplates and profile frames, guild tags, the Shop This Look sheets, the wishlist grids, the Quests screen and its settings, the Game Profile Shop carousel and the voice guild tag.

### Added

- **OctoGram: links open in your browser by default.** A fresh install sends web links to the phone's browser instead of the in-app viewer. A device that has already changed the setting keeps its choice, and Telegram's own links still open inside the app.
- **OctoGram: the Telegram Business row and the two commands behind it are removed**, and the premium screens that open as a bottom sheet are closed as well. The Business feature itself is untouched.

## [3.0.0] - 2026-10-03

### Changed

- **Discord support moves to 349.5, and 348.5 can no longer be patched.** Discord renumbered every function in its JavaScript bundle in this release, so the patch set was derived again from the desktop suite: 166 functions, up from 145, of which 164 match that build byte for byte and two are a deliberate divergence (below). A run that has already downloaded 348.5 has to start again.
- A build whose version cannot be identified is refused rather than patched, and says so.

### Added

- The EmojiCompat load runnable is stubbed, a change the desktop suite makes and this build did not.

### Removed

- OctoGram 3.6.0 is no longer recognised, because no source here offers it.

### Fixed

- Two Discord JavaScript patches no longer throw when their callers run. The desktop suite stubs two functions to `undefined`, and each has a caller that reads a property off the result, which throws a `TypeError`. One now returns an object carrying `{confirmed: false}` from the confirmation handler and the other an empty array from the typing-indicator hook, which is the shape each caller reads. This is a deliberate divergence from the desktop build.

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

- The test suite skips the tests that need the reference apps instead of failing, which made continuous integration report a failure on every clean run.

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
- Three checks that compare the patched JavaScript bundle against the desktop build now run instead of skipping. Each read a bundle out of a temporary directory that a restart emptied, so all three compared nothing while reporting success.

### Fixed

- The crash reporter's native libraries are no longer left in the finished APK, along with the Play source stamp. The check ran only against the base APK's own entries, and the libraries arrive from the ABI split, which the merge adds afterward.
- Six permission declarations the desktop build strips are removed: the contact list, the advertising ID, Privacy Sandbox attribution, and three for services the patched app does not bind. They are removed on Discord builds only, because OctoGram uses the contact list for real.
- The three Google Analytics components are marked `android:enabled="false"`, which matches the desktop build and stops the platform starting them.
- The split-install metadata is removed, along with the resource-table row naming it.
- Some translated strings in arrays and plurals showed the wrong text, because they kept the string-pool position they held in the split they came from. 48 values were affected.

## [1.5.0] - 2026-09-27

### Added

- The permissions each app asks for ship with sleepy, so the permission switches are on screen as soon as you pick a target rather than after the whole download.
- You can check that list against the build it applies to. Checking downloads the build and reports any difference in full.
- Settings lists where every target is downloaded from, and marks the one you opened last.
- The patched build carries the images a density split holds and the strings the language splits hold, and the resource table is rebuilt around them so they are reachable. A set of tables that cannot be reconciled is not used, and the reason is reported.
- The manifest edits the desktop build makes are applied to the compiled manifest. The crash reporter's two provider declarations, the three Play split markers and the AppsFlyer query go with the switches that make them pointless. The RPC service is no longer exported on any build, because it was exported with no permission on it and no check on its caller.

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

- Discord's own JavaScript is patched to match the desktop build change for change: 142 functions, each written to the same bytes that build ships.

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

[Unreleased]: https://github.com/Lyceris-chan/sleepy/compare/v3.4.1...HEAD
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
