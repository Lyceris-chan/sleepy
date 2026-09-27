# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.5.0] - 2026-09-27

### Added

- The permissions each supported app asks for are listed in sleepy itself, so the permission switches are on screen as soon as you pick a target. They used to be read out of the APK you were patching, which meant the section stayed empty until the whole download had finished, and there was no way to see what an app asks for without downloading it.
- You can check the permission list against the build it describes. Checking downloads the build and compares what it declares with the list, and any difference is shown in full — including an app asking for something the list does not cover, which is the one case where a permission has no switch and no description to read.
- Settings now lists where every target is downloaded from, not only the one you last opened, and marks the one you opened last.
- The patched build now carries the images a configuration split holds for one screen density and the strings the two language splits hold, which a base split on its own carries none of. They go back at the paths the desktop build has them at, so the finished APK's file set and its size come to match that build's — and the resource table is rebuilt around them while the APK is patched, so they are reachable rather than merely present. Every path the rebuilt table names is a file the APK holds, every resource file it holds is named, and each source's resource ids, configurations and compiled file paths are kept as they were; the table is read back and checked before the APK is rebuilt, and a set of tables that cannot be reconciled is refused with the reason, leaving the base's table in place.
- The manifest edits the desktop build makes are applied here too, to the compiled manifest rather than to its text. The two Sentry provider declarations go with the crash-reporting switch, the three Play split markers go when the splits have been merged into one APK, and the AppsFlyer package-visibility query goes with the deep-link switch that stops the only thing that used it. The RPC service is not exported any more, on every build and behind no switch: it is exported with no permission declared on it and no check on its caller, so any app on the device can bind it and publish presence frames as you. Each of those is reported as its own step, so a build that no longer declares one says so.

### Changed

- The permission list is above the patch list instead of below all of it.
- The version in settings is the version the build was made as. It is read from the changelog's newest release when the build is made, so the app, the changelog and the release tag cannot disagree; it used to be a number written into the screen, which is why the app still said 1.0.0 at 1.4.0.
- Corner radii now come from the Material 3 shape scale rather than being chosen per screen, so cards, dialogs, chips and text fields are rounded the way Material 3 rounds those components. They were 34, 26, 18, 12 and 6 dp where the tokens are 28, 16, 12, 8 and 4.
- Two libraries nothing called are no longer declared, and the debug build no longer carries the Compose preview tooling. The APKs are smaller for it.
- A test that needs a file from outside the repository is reported as skipped rather than as a pass when that file is not there, so a green run means the checks ran and not that they were unable to.

### Removed

- A build output file that had been committed to the repository by mistake is no longer tracked in it.

### Fixed

- The rebuilt APK compressed the two entries it is supposed to carry through unchanged. A replaced entry is dropped from the copied stream and rewritten from what replaced it, and the storage method it had in the source was read after that drop rather than before — so every replaced entry fell back to DEFLATE. The resource table and the JavaScript bundle are both stored uncompressed in Discord's APK, and both came out compressed: the table no longer aligned to a 4-byte boundary, and the bundle had to be unpacked at startup instead of being mapped. Both now keep the method their source had.
- The aligned-APK check treated "no entry was measured" the same as "every entry aligned", so a finished APK whose archive could not be read was reported as correctly aligned. A check that cannot be made is now reported as unchecked.
- The permission section could not be used at all. Nothing was listed until a whole APK had been downloaded for it, and even then it sat below every patch set, so its switches were out of reach; the list ships with the app now and the section is the first thing on the screen.
- In dark mode, the borders of components that carry no fill of their own — an unchecked switch, an outlined text field — were too faint to see: 2.0:1 against the surface where the accessibility guidelines ask 3:1. They are drawn in a lighter tone that clears 3:1 on every surface of the dark scheme.
- The JAR signature on the finished APK was reported as failing on every build, because a check that does not apply was being read as a check that failed. JAR signing is only honoured below Android 7.0, so a build that requires a newer version than that never has it read, which is every build sleepy makes; the signature is written and is valid, and there was no verdict to report on it. It now says the scheme does not apply to this build and names the version that puts it out of scope, while the v2 and v3 signatures are still checked and still fail the step when they do not verify.

### Security

- sleepy releases are no longer signed with the debug key. Every Android SDK installs that key and publishes it, so a release signed with it is one that anyone can build a newer version of. Without a signing key of its own a release is now left unsigned and named so.
- The password on the signing key sleepy creates on your device is generated for that installation rather than written into the source. It is kept in the app's private storage beside the key, which is what actually protects it; what changes is that a password read out of the source no longer opens anyone's key, and one that leaks opens a single installation instead of all of them.
- Releases from this one are signed with a new key. The key the earlier releases were signed with is no longer used, so this build will not install over 1.0.0 to 1.4.0 — Android treats two builds as the same app only when the package name and the signing key both match. Uninstall the earlier version first.

  That has a second effect worth knowing before you do it. Uninstalling removes sleepy's private storage, and the key sleepy generates on your device to sign the apps it patches is kept there. After reinstalling, sleepy makes a new one, so patched apps you still have will not accept patches signed by the new install. Uninstall those too, and patch them again, if you want to be able to update them.

## [1.4.0] - 2026-09-27

### Added

- OctoGram's own crash reporter can be switched off. It installs a crash handler of its own while the app starts, which writes the stack trace of a crash into the app's storage and raises an "OctoGram just crashed!" notification the next time the app opens; with it switched off the handler is never installed, so a crash leaves nothing behind. It is a switch of its own because that handler is installed regardless of the logging flag the logging switch pins false.
- The premium rows in the profile's settings list can be hidden: the Telegram Premium row, the Send a Gift row, and the combined premium-sections row that would otherwise appear in their place. It is one switch rather than three, because hiding the first row on its own puts a different premium row on screen instead.

### Changed

- Every OctoGram set is now listed change by change, with a switch of its own, as the Discord sets already were. Silencing OctoGram's log is two choices rather than one — its emitters and the helpers that would upload a log off the device — and the paywall's two code paths are separate choices too.
- The OctoGram sets are described in the same terms as the Discord ones: what you lose or gain, rather than the name of the code the change edits. Each of OctoGram's twelve diagnostic log emitters now says what it would have written and at which level, instead of sharing one sentence between them.
- The technical panel under a set now lists every change with its own title and what it does, so a set whose changes are not listed one by one can still be read before you patch.
- The notes on what is left running are more precise. The app ships no Firebase analytics library, so the set switches off A/B testing, remote configuration and the telemetry transport rather than an analytics library; and the profile's premium rows are named as hidden by a switch of their own rather than by the paywall patch.

### Removed

- The five OctoGram changes written for 3.6.0. Only a 3.6.1 build can be downloaded here, so they could never run, and listing them suggested otherwise.

### Fixed

- Patching Discord no longer runs the phone out of memory while the merged APK is rebuilt. The merged native libraries, the base APK and the archive being written were all held as byte arrays at once, which is more than a phone's heap; they now move through disk one at a time, so the rebuild's peak is a buffer rather than an archive.

## [1.3.0] - 2026-09-27

### Added

- Turn individual patches on or off instead of choosing a whole set at once.
- The 142 JavaScript patches are grouped into 18 features, such as gift buttons and quests, and each one says what it does before you switch it on.
- The Discord network blocklist is listed rule by rule: 20 host rules and 61 API path rules, each with its own switch and its own description.
- A greyed-out switch now says why it cannot be moved. A rule that another enabled rule already covers is marked "Already covered by" and names that rule; the two checks the blocklist cannot work without are marked "Required".

### Changed

- A patch set's switch now reports whether none, some or all of its changes are selected, instead of rounding a part-selected set to on or off. Tapping a partly selected set selects the rest of it, and turning a set off and on again restores all of its changes.
- Switching off a rule that covered other rules makes those rules switchable again straight away.

## [1.2.0] - 2026-09-27

### Added

- A network blocklist for Discord. Requests to 20 tracking, advertising, survey and payment-monitoring hosts and to 61 Discord API paths are answered with an empty response instead of being sent. Spotify is included on purpose, so the Spotify integration stops working.

### Changed

- The patch list now says that a set which has no fixed changes is built for the APK you selected, instead of showing "0 hooks" for it.

## [1.1.0] - 2026-09-27

### Changed

- Discord's own JavaScript code is now patched to match the desktop build change for change: 142 functions, each written to the same bytes that build ships.

### Removed

- The crash reporter's own libraries, and the data it writes on a crash, when crash reporting is switched off. The mechanism is gone from the patched APK rather than disabled.

### Fixed

- sleepy no longer reports a ZIP alignment failure for APKs that are valid. Only uncompressed entries need aligning, and the check was applying the rule to compressed entries too, which failed on every run.
- The JavaScript patches are now tied to the exact Discord release they were made for. A different release is refused rather than patched with changes that would point at unrelated code.

## [1.0.0] - 2026-09-27

### Added

- sleepy: download a supported app, patch it on your device, sign the result and save it. Nothing is uploaded, and no other software is needed.
- Clone mode, which gives the patched app a package name of its own so it installs next to the original.
- Discord: crash reporting switched off, including the native crash handlers.
- Discord: telemetry switched off, including the advertising ID, install attribution and performance reporting.
- Discord: debug logging silenced, background log capture stopped, and the settings that turn frame and resource monitoring off.
- Discord: deep-link attribution switched off, and the contact list, contact photos and the list of installed apps are no longer read.
- Discord: the JavaScript code that powers the app is patched on your device, so gift buttons, quests and Nitro upsells stop appearing and the animation on profile cards is dropped.
- Discord: one shared disk cache for the app's network clients instead of one each, and cheaper performance settings, so the app uses less memory and downloads less.
- Discord: calls fixed to stay alive when you leave the app, and the video player no longer blocks the interface while it starts up.
- OctoGram: sponsored messages in channels, full-screen adverts in the photo viewer and sponsored channels in search results are all removed, along with the requests that fetch them.
- OctoGram: the GitHub update check is switched off, and Firebase analytics, A/B testing, remote configuration and telemetry uploads are all disabled.
- OctoGram: the app's own logging is silenced, along with the diagnostic files it uploads.
- OctoGram: most entry points to the premium paywall are blocked. Some screens still appear; see the limitations in the release notes.
- A report of every step: what was applied, what was skipped and why, what failed, and the signature, alignment and size read back from the finished APK.

### Fixed

- Patching no longer runs out of memory. sleepy edits only the classes it needs instead of loading the whole app's code at once.
- The patched Discord APK now includes the ARM64 native libraries from the separate split file, so it starts instead of stopping on the first library it loads.
- JavaScript changes are checked against the app's code before they are written, so a change aimed at the wrong place cannot corrupt it.
- OctoGram changes that matched more than one place in the code are resolved, and each change now applies only to the app version it was made for.

[Unreleased]: https://github.com/Lyceris-chan/sleepy/compare/v1.5.0...HEAD
[1.5.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/Lyceris-chan/sleepy/releases/tag/v1.0.0
