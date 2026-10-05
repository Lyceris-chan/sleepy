# sleepy

sleepy is an Android app that patches Discord and OctoGram on your phone. You choose the changes you want, and sleepy downloads the original app, applies them and saves a patched APK that you install. sleepy does its work on your device: the original app is downloaded, patched there, and never uploaded.

## Changes sleepy makes

The following sections list the changes sleepy makes to each app.

### Discord

Each of the following changes has its own switch, so you can apply all of them, one of them, or any combination.

- **Stops crash reporting.** Discord stops sending crash reports, and sleepy also removes the crash reporter's own libraries from the APK, so the mechanism is gone rather than switched off.
- **Stops analytics.** Discord stops sending usage, performance and advertising data, and stops reading your advertising ID and how you installed the app.
- **Blocks tracking servers.** Requests to 20 tracking, advertising, survey and payment-monitoring hosts, and to 61 Discord API paths, are answered with an empty response instead of being sent. This deliberately includes Spotify, so the Spotify integration stops working.
- **Stops device fingerprinting.** Discord stops reading your contact list, stops fetching contact photos and stops listing the other apps installed on your phone.
- **Removes gift buttons.**
- **Removes quests**, including the Quests entry in Settings.
- **Removes Nitro upsells**: the inline buttons, the action sheets and the upsell dialogs.
- **Removes the shop**: collectibles, the storefront, promotions, the wishlist and the billing rows in Settings.
- **Removes animated profile card effects**, which are video loops that consume battery power.
- **Silences debug logging** and stops Discord capturing device logs in the background for crash reports.
- **Removes the app-rating survey dialogs.**
- **Stops background update downloads.** Discord keeps the version of its own code that shipped inside this APK instead of downloading a newer one that undoes the changes listed earlier.
- **Makes calls and media more reliable.** Voice calls stay alive when you leave the app, the video player stops blocking the interface while it starts up, and media failures are reported instead of dropped without a report.
- **Uses less memory and fewer downloads.** The image cache is smaller, screen-by-screen performance tracking is off, and all of Discord's network clients share one disk cache instead of each keeping its own in the same folder.

### OctoGram

- **Removes sponsored messages** from public channels, including the request that fetches them.
- **Removes the full-screen ads** shown while you swipe through photos and videos in a channel.
- **Removes sponsored channels and bots** from search results.
- **Stops the update check** that contacts GitHub in the background.
- **Stops Firebase's telemetry stack**: A/B testing, remote configuration and the transport that carries telemetry are all disabled. The app ships no Firebase analytics library, so there is none to switch off.
- **Silences OctoGram's logging**: its own log class, which writes to logcat and to a file, and the app-wide switch that gates Telegram's logging, with the five helpers that upload a diagnostic log stubbed.
- **Switches off OctoGram's own crash reporter.** OctoGram installs a crash handler of its own while the app starts, which writes the stack trace of a crash into the app's storage and raises an "OctoGram just crashed!" notification the next time the app opens. The patch stops the handler from being installed, so a crash writes nothing to the app's storage.
- **Hides the Telegram Premium row.** It goes from both settings lists, the one on your profile and the one on the app's Settings screen. On your profile the Send a Gift row goes with it, along with the combined premium-sections row that otherwise appears in their place; Telegram Stars and TON are separate products and stay.
- **Reduces the premium paywall.** One switch blocks most entry points through the app's navigation routers; a second closes the sheet presentations those routers never see. Some parts remain; see [Known limitations](#known-limitations).
- **Opens web links in your phone's browser by default.** OctoGram's own setting for this ships off, so links open in its in-app viewer until you find and change it. This makes the setting's default on for a fresh install; the setting stays yours to change, and Telegram's own links still open inside the app.
- **Hides the Telegram Business upsell row** from both settings lists, and stops the `/premium` and `/business` commands opening the premium screen. The row is a Telegram Premium upsell rather than an entry to Business settings, and the Telegram Business feature itself is untouched.
- **Closes the paywall's remaining sheet presentations.** The premium entry points that open as a bottom sheet are refused before the sheet is built. Every other sheet still opens.

## Choose which patches to apply

The patch screen is organised by what you want, not by how the app is built. Every change sits in one of seven sections: **Ads and promotions**, **Declutter**, **Tracking and analytics**, **Crash reporting**, **Background work**, **App fixes** and **Network blocking**. Expand a section to see the changes inside it.

- Each change has its own switch. Turn it on or off on its own.
- A section's own switch turns everything in that section on or off at once. It reports whether none, some or all of them are selected, so a section you have partly chosen shows that rather than rounding up or down. Tapping a partly selected section completes it; tapping a fully selected one clears it.
- Some switches are unavailable, with the reason shown. Two reasons appear:
  - **Already covered by …** shows a rule you already have switched on that blocks every request this one blocks. Switch that one off and this switch works again.
  - **Required …** marks one of the two checks the blocklist needs. Those checks run in every build.
- The counts shown with the list and on the button show how many changes you have selected.
- **Permissions** are listed before the sections, one switch per permission the app asks for. Switching one off deletes that permission from the patched app's manifest, and that is permanent: Android gives an app only the permissions its manifest declares, and an installed app cannot ask for another one later.

  A declaration the patch removes from every build it makes is listed as well, but its row shows that it is removed and gives the reason instead of offering a switch, because there is nothing left for a switch to control. The list comes with sleepy, for the exact builds this version supports, and you can check it against the build—sleepy downloads the build and shows any difference in full rather than summarizing it.
- **Clone app** gives the patched APK its own package name, so it installs next to the original app instead of replacing it.

One switch often stands for several changes: the 205 JavaScript changes are grouped into the 38 features they add up to, such as gift buttons, guild tags or quests, so you see what each one does rather than one row per rewritten function. The functions behind a switch are listed in its technical panel.

## Install and use sleepy

sleepy needs Android 8.0 (API level 26) or newer.

1. Download `sleepy-vVERSION.apk` from the [sleepy releases page](https://github.com/Lyceris-chan/sleepy/releases).
2. Install it. Android shows a prompt to allow installing apps from your browser or file manager the first time.
3. Open sleepy and pick a target: Discord or OctoGram.
4. Choose the changes you want on the patch screen. Expand a set to select individual changes.
5. Tap **Patch APK**.
6. Watch the step list. If you stop partway through, nothing is installed and the app you started with is untouched.
7. When it finishes, tap **Save to Downloads** or **Share APK**.
8. Install the saved APK from the file manager or the sharing app you sent it to.

### Uninstall the official app first

sleepy signs the patched APK with its own key, not the publisher's. Android treats an app as the same app only when the package name **and** the signing key match, so the patched Discord is a different app to Android even though it has the same name. Uninstall the official Discord before you install the patched one; otherwise the install fails.

The same applies to updates: the patched app does not receive official updates from Discord, and you cannot install an official update over it.

If you would rather keep both, turn on **Clone app** before patching. Clone app gives the patched APK a package name of its own, so the official app and the patched one can live side by side. The cloned app keeps the code that shipped inside the APK, because sleepy stops it downloading a newer copy of its own code.

Patching Discord is memory-heavy: the base APK, its 74 MB of ARM64 libraries and the rebuilt APK come to more than a phone holds at once. sleepy moves them through storage a piece at a time rather than keeping them in memory, so the peak memory is a single buffer, not the whole archive—but it takes a long time, so close other apps before you start.

## Supported builds

sleepy patches one exact build of each app. The following table lists the builds this version supports.

| App | Version | Version code | Downloaded from |
| :--- | :--- | :--- | :--- |
| Discord | 349.5 Alpha | 349205 | [Vendetta tracker](https://tracker.vendetta.rocks/tracker/download/349205/base), plus its ARM64, density and language splits |
| OctoGram | 3.6.1 Beta 2 | 38275 | [OctoGram releases on GitHub](https://github.com/OctoGramApp/OctoGram/releases/download/v3.6.0_3827/OctoGram_arm64.apk) |

The Discord source is a base APK plus four separate files: the ARM64 native libraries, the images for one screen density, and the German and English strings. sleepy downloads all five and merges them into one APK, so the patched build runs on ARM64 phones and carries the resources the desktop build has. The resource table is rebuilt around them at patch time: every split ships a partial table with entries for only its own files, and the base's table has no entries for the others. The merged files then resolve instead of remaining in the archive unreferenced.

Every download URL, version number and hash is declared in [the `sources.json` manifest](https://github.com/Lyceris-chan/sleepy/blob/main/sources.json). sleepy downloads the original APK from the source named there and patches it on your device. sleepy does not host or redistribute Discord or OctoGram.

Where the source provides a SHA-256 hash, sleepy checks the download against it and stops the build if the file does not match. OctoGram's release publishes its own hash; for Discord, where the tracker publishes none, the manifest records the hash of the copy the tracker serves. The Settings screen lists the hosts every supported target is downloaded from, and marks the one you opened last, so sleepy contacts no host that you have not seen named.

## Comparison with the desktop patch suites

sleepy's changes are ported from the reference patch suite for each app, and the result is checked against that suite rather than assumed to match. For Discord, 203 of the 205 JavaScript changes the suite makes are made to the same bytes, and each is checked against the code the suite's build ships. The other two are a deliberate divergence, recorded in the manifest: the reference stubs both functions to `undefined`, and the one caller of each function reads a property off that return, so the reference's own stub throws a `TypeError` on the path the caller runs. sleepy returns a value of the shape the caller reads instead, so those two paths work.

In two places sleepy does more than the suite. The network blocklist is worked out from the APK you selected instead of being written down as fixed names that the next release can invalidate. And the permissions an app asks for are listed with a switch each, so what it can ask for is your decision rather than a fixed set.

What is not covered yet is under [Known limitations](#known-limitations), per app.

## Known limitations

The builds this version supports have the limitations listed in the following sections. A behavior that is not listed as a patch is unchanged from the original app.

**Discord**

- JavaScript changes are written for one exact Discord release, 349.5. A different release's code is not patched, because the identifiers these changes use are numbered per release and point at unrelated code in another build.
- The blocklist is built from the APK you selected rather than shipped as fixed text, because three of the names it needs are renamed by the app's own obfuscation on every release. A build where these cannot be found is skipped, with the reason shown, instead of being patched with names from another release.
- Every manifest edit the desktop suite makes is applied. The split declarations, the native library setting, any permission you switch off, the six declarations sleepy removes on its own, the crash reporter's two providers, the three Play split metadata entries, the AppsFlyer link query and the three Google Analytics components are all edited into the compiled manifest. The RPC service is closed to other apps on every build, because it is exported with no permission on it and no check on its caller. The Analytics components are marked `android:enabled="false"` rather than deleted, which is the edit the suite makes: their classes are still in the app's code, and the platform does not start a component declared that way.
- New entries cannot be added to the app's resource table, only carried across from a split. The desktop build adds some video player image aliases that sleepy cannot add. Discord's own APK ships without them and runs, so this matches what Discord itself ships.
- Three media changes from the reference build stay switched off, for the reasons recorded for that build: one crashes the camera as soon as it opens and cannot be reverted without breaking a string other code reuses, one records video below the encoder's minimum, and one passes a value the media engine does not document.
- Signed with sleepy's own key, so it installs as a different app identity: uninstall the official Discord first, and it does not receive official updates.

**OctoGram**

- Only the 3.6.1 build listed above can be patched. The reference scripts also carry changes for 3.6.0, and sleepy does not ship them: with no 3.6.0 download registered they cannot run, so the patch list does not include them. A build that cannot be identified as 3.6.1 does not receive the changes written for that release; the step log says the build's version could not be identified. The version-independent Firebase registrar changes still apply by class name where those classes exist.
- The update check is switched off, the logger is silenced and the crash reporter is stubbed out, but the rewritten methods use a register count that differs from the reference build's, so the assembled text is not identical; the operations are the same.
- Premium paywall removal is split across two switches, and it is still partial. The navigation-router switch blocks 53 of the 61 places the app can present the paywall; the sheet switch closes the remaining eight, which would otherwise open it as a sheet. The premium feature cells and the limit preview screens still appear, and a subscriber loses the screen where they manage their subscription. The premium rows in a profile's own settings list are hidden by that earlier switch rather than by either of these.
- Hiding the Telegram Premium row is one switch rather than a choice per row, and applying part of it is a state this app does not let you reach. On your profile the rows share one list and their conditions overlap: hiding only the Telegram Premium row makes the combined premium-sections row appear in its place, which is a different premium row on screen rather than none. The row on the app's Settings screen is built separately from that list, so it is a branch of its own, but it is the same upsell and moves with the same switch.
- Signed with sleepy's own key, so it installs as a different app identity and does not receive official updates.

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

Some engine tests check the patcher against a reference APK. They are reported as skipped when that file is not on your machine, so the test task passes on a clean checkout and the skipped count shows how much of the suite did not run. The heap for every test task is capped at 512 MB, which is what a phone grants an app with `largeHeap`. The tests that merge the real Discord splits run under that cap on purpose, so a repack that holds the whole archive in memory fails on a build machine rather than on a phone.

The Gradle build leaves the release APK unsigned unless you give it a signing key of its own: put a `keystore.properties` at the repository root pointing at the keystore, and the release is signed with it. No build falls back to the debug key, which every Android SDK installs and publishes; a release signed with it is one anybody can sign a newer version of. The published releases are re-signed by the release workflow with the project's own key.

## Source and license

sleepy is open source. The source is at [github.com/Lyceris-chan/sleepy](https://github.com/Lyceris-chan/sleepy), which is this project's repository and not the repository of any app it patches.

The repository does not include a license file, so no license is granted for reuse.
