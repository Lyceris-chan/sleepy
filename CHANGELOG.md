# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- The OctoGram sets are described in the same terms as the Discord ones: what you lose or gain, rather than the name of the code the change edits. Each of OctoGram's twelve diagnostic log emitters now says what it would have written and at which level, instead of sharing one sentence between them.
- The technical panel under a set now lists every change with its own title and what it does, so a set whose changes are not listed one by one can still be read before you patch.
- The notes on what is left running are more precise. The app ships no Firebase analytics library, so the set switches off A/B testing, remote configuration and the telemetry transport rather than an analytics library; and OctoGram's own crash reporter, which writes a crash log and raises a notification, is named as something this version does not patch.

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

[Unreleased]: https://github.com/Lyceris-chan/sleepy/compare/v1.3.0...HEAD
[1.3.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/Lyceris-chan/sleepy/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/Lyceris-chan/sleepy/releases/tag/v1.0.0
