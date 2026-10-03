# sleepy

sleepy is an Android app that patches Discord and OctoGram on your phone. You choose the changes you want, and sleepy downloads the original app, applies them and saves a patched APK that you install. Everything happens on your device, and sleepy sends nothing anywhere.

## What sleepy changes

### Discord

Each change below has its own switch, so you can apply all of them, one of them, or any combination.

- **Stops crash reporting.** Discord stops sending crash reports, and sleepy also removes the crash reporter's own libraries from the APK, so the mechanism is gone rather than switched off.
- **Stops analytics.** Discord stops sending usage, performance and advertising data, and stops reading your advertising ID and how you installed the app.
- **Blocks tracking servers.** Requests to 20 tracking, advertising, survey and payment-monitoring hosts, and to 61 Discord API paths, are answered with an empty response instead of being sent. This deliberately includes Spotify, so the Spotify integration stops working.
- **Stops device fingerprinting.** Discord stops reading your contact list, stops fetching contact photos and stops listing the other apps installed on your phone.
- **Removes gift buttons.**
- **Removes quests**, including the Quests entry in Settings.
- **Removes Nitro upsells**: the inline buttons, the action sheets and the pop-up dialogs.
- **Removes the shop**: collectibles, the storefront, promotions, the wishlist and the billing rows in Settings.
- **Removes animated profile card effects**, which are video loops that drain the battery.
- **Silences debug logging** and stops Discord capturing device logs in the background for crash reports.
- **Removes the app-rating survey pop-ups.**
- **Stops background update downloads.** Discord keeps the version of its own code that shipped inside this APK instead of downloading a newer one that would undo the changes above.
- **Makes calls and media more reliable.** Voice calls stay alive when you leave the app, the video player stops blocking the interface while it starts up, and media failures are reported instead of silently dropped.
- **Uses less memory and fewer downloads.** The image cache is smaller, screen-by-screen performance tracking is off, and all of Discord's network clients share one disk cache instead of competing over the same folder.

### OctoGram

- **Removes sponsored messages** from public channels, including the request that fetches them.
- **Removes the full-screen adverts** shown while you swipe through photos and videos in a channel.
- **Removes sponsored channels and bots** from search results.
- **Stops the update check** that contacts GitHub in the background.
- **Stops Firebase's telemetry stack**: A/B testing, remote configuration and the transport that telemetry would travel over are all disabled. The app ships no Firebase analytics library, so there is none to switch off.
- **Silences OctoGram's logging**: its own log class, which writes to logcat and to a file, and the app-wide switch that gates Telegram's logging, with the five helpers that upload a diagnostic log stubbed.
- **Switches off OctoGram's own crash reporter.** OctoGram installs a crash handler of its own while the app starts, which writes the stack trace of a crash into the app's storage and raises an "OctoGram just crashed!" notification the next time the app opens. This stops that handler being installed, so a crash leaves nothing behind.
- **Hides the premium rows in the profile's settings list**: the Telegram Premium row, the Send a Gift row, and the combined premium-sections row that would otherwise appear in their place.
- **Reduces the premium paywall.** Most entry points to the premium screen are blocked. Some remain; see [Known limitations](#known-limitations).

## Choose which patches to apply

The patch screen lists a set for each part of the app. Expand a set to see the individual changes inside it.

- Each change has its own switch. Turn it on or off on its own.
- A set's own switch reports whether none, some or all of its changes are selected. If you select a few changes inside a set, the set's switch shows that rather than rounding up or down.
- Tapping a partly selected set selects every change inside it. Tapping a fully selected set clears it.
- Some switches are greyed out and state the reason. Two reasons appear:
  - **Already covered by …** names a rule you already have switched on that blocks every request this one would block. Switch that one off and this switch starts working again.
  - **Required …** marks one of the two checks the blocklist cannot work without. Those checks always run.
- The counts above the list and on the button at the bottom tell you how many changes you have selected.
- **Permissions** are listed above the patch sets, one switch per permission the app asks for. Switching one off deletes that permission from the patched app's manifest, and that is permanent: Android gives an app only the permissions its manifest declares, and an installed app cannot ask for another one later. A declaration the patch removes from every build it makes is listed as well, but its row reads as removed and gives the reason instead of offering a switch, because there is nothing left for a switch to decide. The list comes with sleepy, for the exact builds listed below, and you can check it against the build — sleepy downloads it and shows any difference in full rather than passing over it.
- **Clone app** gives the patched APK its own package name, so it installs next to the original app instead of replacing it.

The 145 JavaScript changes are grouped by the feature they affect, such as gift buttons or quests, so you can see what each one does before you switch it on.

## Install and use sleepy

sleepy needs Android 8.0 (API level 26) or newer.

1. Download `sleepy-v<version>.apk` from the [sleepy releases page](https://github.com/Lyceris-chan/sleepy/releases).
2. Install it. Android asks you to allow installing apps from your browser or file manager the first time.
3. Open sleepy and pick a target: Discord or OctoGram.
4. Choose the changes you want on the patch screen. Expand a set to select individual changes.
5. Tap **Patch APK**.
6. Watch the step list. If you stop partway through, nothing is installed and the app you started with is untouched.
7. When it finishes, tap **Save to Downloads** or **Share APK**.
8. Install the saved APK from the file manager or the sharing app you sent it to.

### Uninstall the official app first

sleepy signs the patched APK with its own key, not the publisher's. Android treats an app as the same app only when the package name **and** the signing key match, so the patched Discord is a different app to Android even though it has the same name. Uninstall the official Discord before you install the patched one; otherwise the install fails.

The same applies to updates: the patched app will not receive official updates from Discord, and you cannot install an official update over it.

If you would rather keep both, turn on **Clone app** before patching. That gives the patched APK a package name of its own, so the official app and the patched one can live side by side. The cloned app keeps the code that shipped inside the APK, because sleepy stops it downloading a newer copy of its own code.

Patching Discord is memory-heavy: the base APK, its 74 MB of ARM64 libraries and the rebuilt APK come to more than a phone holds at once. sleepy moves them through storage a piece at a time rather than keeping them in memory, so the peak is a buffer rather than an archive — but it is the slowest thing sleepy does, and it is worth closing other apps before you start.

## Supported builds

sleepy patches one exact build of each app. The list below is what this version supports.

| App | Version | Version code | Downloaded from |
| :--- | :--- | :--- | :--- |
| Discord | 348.5 Alpha | 348205 | [Vendetta tracker](https://tracker.vendetta.rocks/tracker/download/348205/base), plus its ARM64, density and language splits |
| OctoGram | 3.6.1 Beta 2 | 38275 | [OctoGram releases on GitHub](https://github.com/OctoGramApp/OctoGram/releases/download/v3.6.0_3827/OctoGram_arm64.apk) |

The Discord source is a base APK plus four separate files: the ARM64 native libraries, the images for one screen density, and the German and English strings. sleepy downloads all five and merges them into one APK, so the patched build runs on ARM64 phones and carries the resources the desktop build has. The resource table is rebuilt around them at patch time — every split ships a partial table naming only its own files, and the base's names none of the others — so the merged files resolve instead of sitting in the archive unreferenced.

Every download URL, version number and hash is declared in [the `sources.json` manifest](https://github.com/Lyceris-chan/sleepy/blob/main/sources.json). sleepy downloads the original APK from the source named there and patches it on your device. sleepy does not host or redistribute Discord or OctoGram.

Where the publisher provides a SHA-256 hash, as OctoGram does, sleepy checks the download against it and refuses a file that does not match. For Discord, the tracker publishes no hash, and the app says so rather than claiming a check it cannot make. The Settings screen lists the hosts every supported target is downloaded from, and marks the one you opened last, so nothing is contacted that you have not seen named.

## How this compares to the desktop patch suites

sleepy's changes are ported from the reference patch suite for each app, and the result is checked against that suite rather than assumed to match. For Discord, every one of the 145 JavaScript changes the suite makes is made to the same bytes, verified against the code the suite's build ships, so the patched app matches it change for change.

In two places sleepy goes past the suite. The network blocklist is worked out from the APK you selected instead of being written down as fixed names that the next release would invalidate. And the permissions an app asks for are listed with a switch each, so what it can ask for is your decision rather than a fixed set.

What is not covered yet is under [Known limitations](#known-limitations), per app.

## Known limitations

Stated plainly, because a limitation left unsaid reads as a guarantee.

**Discord**

- JavaScript changes are written for one exact Discord release, 348.5. A different release's code is refused instead of being patched, because the identifiers these changes use are numbered per release and would point at unrelated code in another build.
- The blocklist is built from the APK you selected rather than shipped as fixed text, because three of the names it needs are renamed by the app's own obfuscation on every release. A build where these cannot be found is skipped, with the reason shown, instead of being patched with names from another release.
- Every manifest edit the desktop suite makes is applied. The split declarations, the native library setting, any permission you switch off, the six declarations sleepy removes on its own, the crash reporter's two providers, the three Play split metadata entries, the AppsFlyer link query and the three Google Analytics components are all edited into the compiled manifest, and the RPC service is closed to other apps on every build because it is exported with no permission on it and checks nothing about its caller. The Analytics components are marked `android:enabled="false"` rather than deleted, which is the edit the suite makes: their classes are still in the app's code, and the platform never starts a component declared that way.
- New entries cannot be added to the app's resource table, only carried across from a split. The desktop build adds some video player image aliases that sleepy cannot add. Discord's own APK ships without them and runs, so this matches what Discord itself ships.
- Four media changes from the reference build stay switched off, for the reasons that build documents: they crash the camera, shrink recorded video below what the encoder expects, remove a string that is still in use, or pass a value the media engine does not document.
- Signed with sleepy's own key, so it installs as a different app identity: uninstall the official Discord first, and it will not receive official updates.

**OctoGram**

- Only 3.6.1 builds can be patched. The reference scripts also carry changes for 3.6.0, and sleepy does not ship them: with no 3.6.0 download registered, they could never run, and a change listed in the app that never does anything reads as a promise it does not keep.
- The update check is switched off, the logger is silenced and the crash reporter is stubbed out, but the rewritten methods use a register count that differs from the reference build's. The assembled code is equivalent; the text is not identical.
- Premium paywall removal is partial: 53 of the 61 places the app can present the paywall are blocked, and the remaining eight still open it as a sheet. The premium feature cells and the limit preview screens still appear, and a subscriber loses the screen where they manage their subscription. The premium rows in a profile's own settings list are hidden by the switch above rather than by this one.
- Hiding the premium rows is one switch rather than three, and applying part of it is a state this app does not let you reach. The rows share one list and their conditions overlap: hiding only the Telegram Premium row makes the combined premium-sections row appear in its place, which is a different premium row on screen rather than none.
- Signed with sleepy's own key, so it installs as a different app identity and will not receive official updates.

## Build sleepy from source

You need:

- JDK 17.
- The Android SDK, with `platforms;android-37.0` and `build-tools;36.0.0` installed.
- The Gradle wrapper in this repository, which uses Gradle 9.8.

Run the unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Build a release APK:

```bash
./gradlew :app:assembleRelease
```

Some engine tests check the patcher against a reference APK. They report themselves as skipped when that file is not on your machine, so the test task passes on a clean checkout and the skipped count says how much of the suite did not run. The heap for every test task is capped at 512 MB, which is what a phone grants an app with `largeHeap`: the tests that merge the real Discord splits run under that cap on purpose, so a repack that holds the whole archive in memory fails on a build machine rather than on a phone.

The Gradle build leaves the release APK unsigned unless you give it a signing key of its own: put a `keystore.properties` at the repository root naming the keystore, and the release is signed with it. Nothing falls back to the debug key, which every Android SDK installs and publishes — a release signed with it would be one anybody could sign a newer version of. The published releases are re-signed by the release workflow with the project's own key.

## Source and licence

sleepy is open source. The source is at [github.com/Lyceris-chan/sleepy](https://github.com/Lyceris-chan/sleepy), which is this project's repository and not the repository of any app it patches.

The repository does not currently include a licence file, so no licence is granted for reuse.
