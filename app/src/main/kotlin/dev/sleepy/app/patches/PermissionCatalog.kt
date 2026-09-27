package dev.sleepy.app.patches

import dev.sleepy.app.model.BlocklistCoverage
import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.Permission
import dev.sleepy.app.model.PermissionCoverage
import dev.sleepy.app.model.PermissionRow

/**
 * What sleepy knows about permissions: one entry per permission, saying what it lets an app do,
 * what stops working without it, and whether removing it is offered at all.
 *
 * ## What this is, and what it deliberately is not
 * The set of permissions a build declares is read from that build's own manifest at patch time —
 * never from here. A frozen list of names would be wrong within one release of either app, and
 * wrong in a way that is invisible: it would offer a switch for a permission the build does not
 * declare, and quietly keep one it does. What is shipped here is the *knowledge* a declaration
 * cannot carry: what a permission means to a person, and what removing it costs.
 *
 * ## Removal is permanent, and every description says so
 * Android grants an app only the permissions its manifest declares. An installed app cannot add a
 * declaration later — there is no API for it, and an update is a different install — so a
 * permission removed here cannot be asked for again, by this build or by anything the app does at
 * runtime. That is not a consequence a user can be expected to infer from "contacts", so every
 * entry's description ends with [REMOVAL_CONSEQUENCE], written once and appended by the two
 * builders below so no entry can be written without it.
 *
 * ## Essentiality
 * A permission is locked only where removing the declaration breaks the app's *core* function —
 * the thing the app is for — and not merely a feature. Where that was not certain the permission
 * is left switchable and its description says what would stop working, because a lock the user
 * cannot argue with is worse than a consequence they can read: a feature that guards itself
 * degrades, while one the app assumes throws.
 *
 * Two are locked: [INTERNET] and [ACCESS_NETWORK_STATE]. Both are install-time permissions that
 * prompt for nothing and reveal nothing about the user, so there is no privacy to gain from
 * removing them; both are enforced by the platform with an exception rather than a refusal, so the
 * app does not degrade without them, it crashes or hangs on the first thing it tries to do. Every
 * other declaration here — a camera, a contact list, an advertising id — is a feature the user can
 * weigh, and is offered as a choice.
 */
object PermissionCatalog {

    /**
     * The id of the permission section.
     *
     * It is not a patch set: it has no entry in `sources.json`, no [dev.sleepy.app.model.PatchSet]
     * and nothing in [PatchItemCatalog], because its items are read from the APK being patched
     * rather than from a table. What it does have is item keys, so the same [PatchSelection] the
     * patch rows are toggled through also carries the permissions, and one switch model serves
     * both.
     */
    const val SET_ID = "manifest_permissions"

    /** The feature group every permission item belongs to, which is what the section heads itself. */
    const val DECLARED_GROUP = "Permissions this build declares"

    /**
     * The sentence every entry's description ends with.
     *
     * It says *permanently* rather than "until you reinstall", because reinstalling the official
     * app does not undo this: the build that was patched would have to be patched again, and that
     * is a different build, not a setting.
     */
    const val REMOVAL_CONSEQUENCE: String =
        "Removing this is permanent: Android gives an app only the permissions its manifest " +
            "declares, and an installed app has no way to declare another one later, so the app " +
            "can never ask for this permission again — on this install, or on any update of it."

    // -- The two locked permissions ------------------------------------------------------------
    // Locked because the platform does not degrade without them. Both are `normal` permissions:
    // granted at install with no prompt, which is why removing one takes nothing away from the
    // user's privacy and only takes away the app's ability to work.

    private val INTERNET = entry(
        name = "android.permission.INTERNET",
        label = "Internet",
        what = "Lets the app open network connections at all — messages, calls, media, updates.",
        lockReason = "Required: every request the app makes goes through this permission, and the " +
            "platform refuses the connection at the socket rather than reporting a network error. " +
            "Without it the app cannot sign in, load a conversation or reach anything: it fails on " +
            "launch. It also prompts for nothing and reveals nothing about you, so there is no " +
            "privacy to gain by removing it."
    )

    private val ACCESS_NETWORK_STATE = entry(
        name = "android.permission.ACCESS_NETWORK_STATE",
        label = "Network state",
        what = "Lets the app ask whether the device is online and what kind of connection it is on.",
        lockReason = "Required: the app checks connectivity before it connects, and the platform " +
            "throws for that check when this is missing instead of answering \"offline\". The check " +
            "the app makes on start-up becomes a crash. It reports no personal data — whether " +
            "there is a connection, not what is sent over it — so removing it costs you nothing and " +
            "breaks the app."
    )

    // -- Connectivity --------------------------------------------------------------------------

    private val CONNECTIVITY = listOf(
        entry(
            name = "android.permission.ACCESS_WIFI_STATE",
            label = "Wi-Fi state",
            what = "Lets the app see whether Wi-Fi is connected and how good the link is, so it can " +
                "prefer it over mobile data. It cannot read traffic or passwords, and the network's " +
                "name is hidden from apps on modern Android."
        ),
        entry(
            name = "android.permission.BROADCAST_STICKY",
            label = "Sticky broadcasts",
            what = "Lets the app send a broadcast the system keeps and re-delivers to whoever asks " +
                "for it later. Without it, the app's internal start-up messages reach only the parts " +
                "of it that are already running. Very few apps still use them."
        ),
        entry(
            name = "android.permission.WAKE_LOCK",
            label = "Keep the processor awake",
            what = "Lets the app keep the processor running while it finishes something with the " +
                "screen off, such as a download or the end of a call. Without it the device may " +
                "sleep mid-transfer and the work is resumed later instead of finishing."
        ),
        entry(
            name = "android.permission.NFC",
            label = "NFC",
            what = "Lets the app read and write NFC tags and hand a phone number to the system dialler."
        )
    )

    // -- Storage and media ---------------------------------------------------------------------

    private val STORAGE = listOf(
        entry(
            name = "android.permission.READ_EXTERNAL_STORAGE",
            label = "Read shared storage (older Android)",
            what = "Lets the app read files and media in shared storage on the Android versions " +
                "where this declaration still applies — builds cap it at the version that replaced " +
                "it with the per-type permissions below. Without it, choosing a file or a photo to " +
                "send fails on those versions."
        ),
        entry(
            name = "android.permission.WRITE_EXTERNAL_STORAGE",
            label = "Write shared storage (older Android)",
            what = "Lets the app save into shared storage on the Android versions where this " +
                "declaration still applies — a downloaded file, an exported photo. Without it those " +
                "saves fail on those versions; the app's own private storage keeps working."
        ),
        entry(
            name = "android.permission.READ_MEDIA_IMAGES",
            label = "Your photos",
            what = "Lets the app read the photos in shared storage, which is what fills the gallery " +
                "when you pick a picture to send. Without it the picker shows nothing and sending a " +
                "photo from the device stops working. Photos you already sent stay where they are."
        ),
        entry(
            name = "android.permission.READ_MEDIA_VIDEO",
            label = "Your videos",
            what = "Lets the app read the videos in shared storage, for the same picker. Without it " +
                "a video cannot be chosen from the gallery to send."
        ),
        entry(
            name = "android.permission.READ_MEDIA_AUDIO",
            label = "Your audio files",
            what = "Lets the app read music and audio files in shared storage, so one can be " +
                "attached or set as a notification sound. Without it those files cannot be chosen."
        ),
        entry(
            name = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            label = "Photos and videos you selected",
            what = "Lets the app keep reading the photos and videos you picked through Android 14's " +
                "\"select photos\" sheet across restarts, without asking for the whole library. " +
                "Without it the app forgets your selection as soon as it restarts and asks again."
        ),
        entry(
            name = "android.permission.ACCESS_MEDIA_LOCATION",
            label = "Photo locations",
            what = "Lets the app read the GPS position stored inside a photo you send. Without it " +
                "the photo is sent without that position: the picture is unchanged, its embedded " +
                "location is not readable."
        )
    )

    // -- Camera, microphone and calls ------------------------------------------------------------

    private val CAPTURE = listOf(
        entry(
            name = "android.permission.CAMERA",
            label = "Camera",
            what = "Lets the app open the camera to take a photo or record video, in the app or " +
                "during a call. Without it the camera button fails and a video call starts without " +
                "your video; receiving other people's video is unaffected."
        ),
        entry(
            name = "android.permission.RECORD_AUDIO",
            label = "Microphone",
            what = "Lets the app record from the microphone — voice messages, calls, voice chat. " +
                "Without it recording a voice message fails and a call connects with no audio from " +
                "you; hearing the other side is unaffected."
        ),
        entry(
            name = "android.permission.MODIFY_AUDIO_SETTINGS",
            label = "Audio routing",
            what = "Lets the app choose where its sound goes and how loud it is, which is how a " +
                "call moves to the loudspeaker, to headphones or to a Bluetooth device. Without it " +
                "a call stays on the default output and its volume control stops working."
        ),
        entry(
            name = "android.permission.MANAGE_OWN_CALLS",
            label = "System call integration",
            what = "Lets the app register its calls with Android, so they appear in the system's " +
                "call screen and on a car's hands-free system. Without it calls still work, but " +
                "only inside the app, and the car cannot see them."
        ),
        entry(
            name = "android.permission.BLUETOOTH_CONNECT",
            label = "Bluetooth devices",
            what = "Lets the app use devices already paired with the phone — a headset or a car's " +
                "microphone. Without it call and media audio never goes to Bluetooth; it plays " +
                "through the phone's own speaker instead."
        ),
        entry(
            name = "android.permission.BLUETOOTH",
            label = "Bluetooth (older Android)",
            what = "The Bluetooth permission on Android 11 and below, where it needed no separate " +
                "connect permission. Without it the same thing happens there: audio does not route " +
                "to a paired headset."
        ),
        entry(
            name = "android.permission.READ_PHONE_STATE",
            label = "Phone state",
            what = "Lets the app read whether a call is in progress, which network it is on, and " +
                "whether the phone is roaming. Apps use it to lower their own audio during a call. " +
                "Without it the app cannot tell a call is happening, so that adjustment stops."
        ),
        entry(
            name = "android.permission.READ_PHONE_NUMBERS",
            label = "This phone's number",
            what = "Lets the app read the number(s) of the phone's own SIM, which it uses to " +
                "prefill a sign-in. Without it that field starts empty and the number is typed by " +
                "hand."
        )
    )

    // -- Background work -------------------------------------------------------------------------

    private val BACKGROUND = listOf(
        entry(
            name = "android.permission.FOREGROUND_SERVICE",
            label = "Background service",
            what = "Lets the app keep something running while it is not on screen, under a visible " +
                "notification — a call, a transfer, a sync. Without it anything running stops when " +
                "the app leaves the screen and only resumes when it is opened again."
        ),
        entry(
            name = "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            label = "Background service: microphone",
            what = "Lets the background service above keep the microphone open — a voice call or a " +
                "voice chat. Without it a call ends or goes silent the moment the app is not on " +
                "screen."
        ),
        entry(
            name = "android.permission.FOREGROUND_SERVICE_CAMERA",
            label = "Background service: camera",
            what = "Lets the background service keep the camera open, for a video call continued in " +
                "another app. Without it a video call drops back to audio when the app leaves the " +
                "screen."
        ),
        entry(
            name = "android.permission.FOREGROUND_SERVICE_LOCATION",
            label = "Background service: location",
            what = "Lets the background service keep reading your location while it is not on " +
                "screen, which is what makes a live-location share keep moving. Without it a share " +
                "stops updating in the background."
        ),
        entry(
            name = "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            label = "Background service: media playback",
            what = "Lets the background service keep playing audio — a music player, a voice " +
                "message. Without it playback stops when the app leaves the screen."
        ),
        entry(
            name = "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
            label = "Background service: screen sharing",
            what = "Lets the background service keep capturing the screen, which is what screen " +
                "sharing is. Without it a screen share stops when the app is not the app on screen."
        ),
        entry(
            name = "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            label = "Background service: data sync",
            what = "Lets the background service keep fetching — message history, media, an upload. " +
                "Without it syncing stops when the app leaves the screen and catches up only when " +
                "it is opened again."
        ),
        entry(
            name = "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            label = "Background service: uncategorised",
            what = "Lets an app declare a background service that fits none of the named kinds " +
                "above. Without it that service cannot be started at all on Android 14 and later."
        ),
        entry(
            name = "android.permission.RECEIVE_BOOT_COMPLETED",
            label = "Start after a reboot",
            what = "Lets the system start the app when the phone finishes booting. Without it the " +
                "app does not start itself after a restart, so messages wait until it is opened."
        ),
        entry(
            name = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            label = "Ask to be exempt from battery saving",
            what = "Lets the app ask the system to stop putting it to sleep in the background. " +
                "Without it the app cannot make that request, so background delivery is at the " +
                "mercy of the system's battery manager. It does not exempt the app by itself."
        ),
        entry(
            name = "android.permission.SCHEDULE_EXACT_ALARM",
            label = "Exact alarms",
            what = "Lets the app set a timed reminder that fires at the minute it is set for — a " +
                "scheduled message, a reminder. Without it those are scheduled loosely and can " +
                "arrive late, because the system batches them with everything else."
        ),
        entry(
            name = "android.permission.USE_EXACT_ALARM",
            label = "Exact alarms (granted on install)",
            what = "The same as exact alarms, for apps the platform decides always need them, and " +
                "granted at install instead of asked for. Without it, timed reminders fall back to " +
                "the system's loose scheduling."
        )
    )

    // -- Notifications and the screen ------------------------------------------------------------

    private val NOTIFICATIONS = listOf(
        entry(
            name = "android.permission.POST_NOTIFICATIONS",
            label = "Notifications",
            what = "Lets the app post notifications, which is also how messages reach the lock " +
                "screen and the status bar. Without it the app cannot post any notification at all: " +
                "messages arrive silently, and nothing tells you they did."
        ),
        entry(
            name = "android.permission.USE_FULL_SCREEN_INTENT",
            label = "Full-screen notifications",
            what = "Lets the app take over the whole screen for an incoming call or an alarm, " +
                "including over the lock screen. Without it an incoming call shows as an ordinary " +
                "notification, which can still be answered but is easy to miss."
        ),
        entry(
            name = "android.permission.VIBRATE",
            label = "Vibration",
            what = "Lets the app vibrate the phone for a notification, a call or haptic feedback. " +
                "Without it everything still arrives, silently and untapped."
        ),
        entry(
            name = "android.permission.POST_PROMOTED_NOTIFICATIONS",
            label = "Promoted notifications",
            what = "Lets the app post a notification Android 15 treats as promoted, which is what " +
                "lets it be shown above the rest. Without it the system drops the notifications the " +
                "app marks that way."
        ),
        entry(
            name = "android.permission.SYSTEM_ALERT_WINDOW",
            label = "Draw over other apps",
            what = "Lets the app draw on top of other apps, which is how a floating call window " +
                "stays visible while you do something else. Without it that window is gone; the app " +
                "cannot ask for this again, so the \"display over other apps\" switch will be off."
        )
    )

    // -- Identity, accounts and contacts ---------------------------------------------------------

    private val IDENTITY = listOf(
        entry(
            name = "android.permission.READ_CONTACTS",
            label = "Read your contacts",
            what = "Lets the app read your contact list to match phone numbers to people you know " +
                "and to show a name and a photo instead of a number. Without it those matches are " +
                "gone: unknown numbers stay numbers, and \"find people you know\" finds nobody."
        ),
        entry(
            name = "android.permission.WRITE_CONTACTS",
            label = "Change your contacts",
            what = "Lets the app add or edit a contact on the device, which is what \"add to " +
                "contacts\" does. Without it that action fails and the contact has to be added by hand."
        ),
        entry(
            name = "android.permission.GET_ACCOUNTS",
            label = "See device accounts",
            what = "Lets the app list the accounts on the device, so it can offer one to a sign-in " +
                "it hands to the system. Without it those screens show no accounts to choose from."
        ),
        entry(
            name = "android.permission.MANAGE_ACCOUNTS",
            label = "Manage device accounts",
            what = "Lets the app add and remove accounts of its own kind in Android's account " +
                "settings. Without it the app's account cannot be added there, and the account's " +
                "sync entry disappears from the system settings."
        ),
        entry(
            name = "android.permission.AUTHENTICATE_ACCOUNTS",
            label = "Be an account provider",
            what = "Lets the app act as an account type the system can store and sync. Without it " +
                "the app cannot register its account with Android at all, so the system's sync " +
                "framework cannot call it."
        ),
        entry(
            name = "android.permission.READ_PROFILE",
            label = "Read your own profile",
            what = "Lets the app read the name you gave yourself in the device's contacts profile. " +
                "Without it the app cannot prefill your own name from the phone."
        ),
        entry(
            name = "android.permission.READ_SYNC_SETTINGS",
            label = "Read sync settings",
            what = "Lets the app see whether the system's background sync is on for its account. " +
                "Without it the app cannot tell, and it assumes the default."
        ),
        entry(
            name = "android.permission.WRITE_SYNC_SETTINGS",
            label = "Change sync settings",
            what = "Lets the app turn the system's background sync for its account on or off — the " +
                "checkbox in Android's account settings. Without it the app cannot change that " +
                "setting and the checkbox does nothing."
        ),
        entry(
            name = "android.permission.READ_CLIPBOARD",
            label = "Clipboard",
            what = "Lets the app read what you copied on the phones that require a permission for " +
                "it. Without it the app's paste-a-link and copy-a-code actions have nothing to read " +
                "there."
        )
    )

    // -- Location --------------------------------------------------------------------------------

    private val LOCATION = listOf(
        entry(
            name = "android.permission.ACCESS_COARSE_LOCATION",
            label = "Approximate location",
            what = "Lets the app place you roughly — to the neighbourhood — for sharing a live " +
                "location or finding people nearby. Without it those features cannot start."
        ),
        entry(
            name = "android.permission.ACCESS_FINE_LOCATION",
            label = "Precise location",
            what = "Lets the app place you precisely, which is what a live location share or a map " +
                "inside a chat needs. Without it those features cannot start."
        ),
        entry(
            name = "android.permission.ACCESS_BACKGROUND_LOCATION",
            label = "Location in the background",
            what = "Lets the app keep using your location while it is not on screen, which is what " +
                "keeps a live location share moving after you switch apps. Without it a share stops " +
                "updating as soon as the app is not on screen."
        )
    )

    // -- Device unlock ---------------------------------------------------------------------------

    private val UNLOCK = listOf(
        entry(
            name = "android.permission.USE_FINGERPRINT",
            label = "Fingerprint unlock",
            what = "Lets the app ask for your fingerprint to unlock the app itself or a locked chat. " +
                "Without it the app's own lock falls back to its passcode, or stops being offered " +
                "on the phones that only had this older permission."
        ),
        entry(
            name = "android.permission.USE_BIOMETRIC",
            label = "Biometric unlock",
            what = "Lets the app ask for fingerprint or face unlock for its own lock. Without it " +
                "the app's lock falls back to a passcode."
        )
    )

    // -- What the app does to the device ---------------------------------------------------------

    private val DEVICE = listOf(
        entry(
            name = "android.permission.REQUEST_INSTALL_PACKAGES",
            label = "Install apps",
            what = "Lets the app install an APK it has — this is how a built-in updater installs " +
                "what it downloaded. Without it that install fails, and the update has to be " +
                "installed by hand from a file manager."
        ),
        entry(
            name = "android.permission.INSTALL_SHORTCUT",
            label = "Add home screen shortcuts",
            what = "Lets the app put a shortcut on the home screen, such as a particular chat. " +
                "Without it shortcuts cannot be created from the app."
        ),
        entry(
            name = "com.android.launcher.permission.INSTALL_SHORTCUT",
            label = "Add home screen shortcuts (older launchers)",
            what = "The same shortcut, on the launchers that publish this permission instead of " +
                "Android's own. Without it shortcuts cannot be created on those launchers."
        ),
        entry(
            name = "com.android.launcher.permission.UNINSTALL_SHORTCUT",
            label = "Remove home screen shortcuts (older launchers)",
            what = "Lets the app take its own shortcut back off the home screen on those launchers. " +
                "Without it the app can add a shortcut but not remove it."
        )
    )

    // -- Ads, purchases and attribution ----------------------------------------------------------

    private val COMMERCE = listOf(
        entry(
            name = "com.google.android.gms.permission.AD_ID",
            label = "Advertising identifier",
            what = "Lets the app read the resettable advertising identifier Play Services keeps, " +
                "which is what ad measurement and personalised ads are built on. Without it the app " +
                "gets the all-zeros identifier, so anything measured or targeted by it stops being."
        ),
        entry(
            name = "android.permission.ACCESS_ADSERVICES_AD_ID",
            label = "Advertising identifier (Privacy Sandbox)",
            what = "The Privacy Sandbox's replacement for reading the advertising identifier. " +
                "Without it the app cannot read it through the newer API, so ad measurement that " +
                "has moved over stops."
        ),
        entry(
            name = "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
            label = "Ad attribution (Privacy Sandbox)",
            what = "Lets the app register an attribution source and read the sandbox's report of " +
                "which ad led to an install. Without it the app cannot report ad attribution. " +
                "Nothing you see in the app changes."
        ),
        entry(
            name = "com.android.vending.BILLING",
            label = "In-app purchases",
            what = "Lets the app sell through Google Play — a Nitro subscription, a coin pack, " +
                "Telegram Premium. Without it the purchase screens cannot reach Play, so buying or " +
                "restoring a purchase inside the app fails."
        ),
        entry(
            name = "com.google.android.c2dm.permission.RECEIVE",
            label = "Google push messages",
            what = "Lets the app receive push messages through Google's service, which is how a " +
                "notification or an incoming call reaches you while the app is closed. Without it " +
                "push does not arrive at all: the app learns about a message the next time it is " +
                "opened."
        ),
        entry(
            name = "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
            label = "Install referrer",
            what = "Lets the app read, once, which link or ad an install came from — the mechanism " +
                "behind an invite link that credits whoever sent it. Without it the app cannot read " +
                "it and the credit does not happen."
        ),
        entry(
            name = "com.google.android.providers.gsf.permission.READ_GSERVICES",
            label = "Read Google service values",
            what = "Lets the app read settings Google Play Services publishes, such as the device's " +
                "Android ID. Without it the app cannot read them and falls back to its defaults."
        )
    )

    // -- Launcher badge counts -------------------------------------------------------------------
    // One per launcher family: an app that wants its unread count on the icon has to ask each
    // launcher's own provider, because Android itself never generalised this. Removing one costs
    // the number on the icon on that launcher and nothing else.

    private val BADGES = listOf(
        badge("android.permission.READ_APP_BADGE", "launchers that publish a badge count"),
        badge("com.sec.android.provider.badge.permission.READ", "Samsung launchers (read)"),
        badge("com.sec.android.provider.badge.permission.WRITE", "Samsung launchers (write)"),
        badge("com.htc.launcher.permission.READ_SETTINGS", "HTC launchers (read)"),
        badge("com.htc.launcher.permission.UPDATE_SHORTCUT", "HTC launchers (write)"),
        badge("com.sonyericsson.home.permission.BROADCAST_BADGE", "Sony Ericsson launchers"),
        badge("com.sonymobile.home.permission.PROVIDER_INSERT_BADGE", "Sony launchers"),
        badge("com.anddoes.launcher.permission.UPDATE_COUNT", "Apex and AndDoes launchers"),
        badge("com.majeur.launcher.permission.UPDATE_BADGE", "Solo launcher"),
        badge("com.huawei.android.launcher.permission.CHANGE_BADGE", "Huawei launchers (write)"),
        badge("com.huawei.android.launcher.permission.READ_SETTINGS", "Huawei launchers (read)"),
        badge("com.huawei.android.launcher.permission.WRITE_SETTINGS", "Huawei launchers (settings)"),
        badge("com.oppo.launcher.permission.READ_SETTINGS", "Oppo launchers (read)"),
        badge("com.oppo.launcher.permission.WRITE_SETTINGS", "Oppo launchers (write)"),
        badge("me.everything.badger.permission.BADGE_COUNT_READ", "Nova, Apex and similar (read)"),
        badge("me.everything.badger.permission.BADGE_COUNT_WRITE", "Nova, Apex and similar (write)")
    )

    // -- Vendor and app-defined permissions ------------------------------------------------------

    private val VENDOR = listOf(
        entry(
            name = "it.octogram.ondevice.permission.USE_TRANSLATION_SERVICE",
            label = "OctoGram's own translation service",
            what = "OctoGram's own permission, which it defines and then holds: it is what keeps " +
                "its on-device translation service to itself, so no other app can bind to it. " +
                "Without the app's request for it, the service is still OctoGram's — the app " +
                "reaches it either way, being its owner — so nothing in the app changes; what is " +
                "gone is the guard against other apps."
        ),
        entry(
            name = "com.samsung.android.mapsagent.permission.READ_APP_INFO",
            label = "Samsung map agent",
            what = "Samsung-only: lets Samsung's map agent read app information so it can hand a " +
                "place or a route from another app into this one. Without it that hand-off is not " +
                "offered."
        ),
        entry(
            name = "com.huawei.appmarket.service.commondata.permission.GET_COMMON_DATA",
            label = "Huawei AppGallery data",
            what = "Huawei-only: lets the app read the shared data AppGallery publishes, which is " +
                "what a Huawei build checks its updates against. Without it the app cannot read it, " +
                "so that update check does nothing."
        )
    )

    /**
     * Every entry, by the name the manifest declares.
     *
     * A permission name is fixed by whoever declares it, so the name is the key and a build
     * declaring one of these gets this entry whatever release it is.
     */
    private val ENTRIES: List<Permission> = buildList {
        add(INTERNET)
        add(ACCESS_NETWORK_STATE)
        addAll(CONNECTIVITY)
        addAll(STORAGE)
        addAll(CAPTURE)
        addAll(BACKGROUND)
        addAll(NOTIFICATIONS)
        addAll(IDENTITY)
        addAll(LOCATION)
        addAll(UNLOCK)
        addAll(DEVICE)
        addAll(COMMERCE)
        addAll(BADGES)
        addAll(VENDOR)
    }

    private val BY_NAME: Map<String, Permission> = ENTRIES.associateBy { it.name }

    /** Every entry, in table order — what a reviewer reads to see the classification. */
    val all: List<Permission> get() = ENTRIES

    /**
     * The suffix of the permission the build tooling adds so an app can keep its own runtime
     * receivers to itself.
     *
     * It is built from the application id, so it is a different name in every app and in every
     * clone of one — `com.discord.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` and
     * `it.octogram.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` are the same declaration of
     * two apps. It is matched by its suffix for that reason rather than listed by name.
     */
    private const val DYNAMIC_RECEIVER_SUFFIX = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    /**
     * The entry for a permission the manifest declares.
     *
     * A name the table does not cover is described rather than dropped: sleepy cannot say what it
     * does, and saying so is the honest answer — hiding it would leave a declaration the user
     * cannot see and cannot choose about.
     */
    fun entryFor(name: String): Permission = BY_NAME[name] ?: when {
        name.endsWith(DYNAMIC_RECEIVER_SUFFIX) -> entry(
            name = name,
            label = "Private broadcast receivers",
            what = "Added by the Android build tooling rather than written by the app: it is the " +
                "permission the app itself holds so its internal broadcast receivers accept " +
                "broadcasts only from itself on Android 13 and later. Without it, a receiver " +
                "registration that asks for this permission is no longer covered, so receivers " +
                "registered that way can fail where they run — this is the one declaration in this " +
                "list whose removal can stop an app from starting rather than cost it a feature."
        )

        else -> entry(
            name = name,
            label = name.substringAfterLast('.').replace('_', ' ').lowercase()
                .replaceFirstChar { it.uppercase() },
            what = "Sleepy has no entry for this permission, so it cannot say what stops working " +
                "without it — only that this build asks for it."
        )
    }

    /**
     * The entries for the permission names a build declares, in the order the manifest declares
     * them, with duplicates dropped.
     *
     * The build's own order is kept because it is the order the APK was built with — the locked
     * ones generally come first, since the build tooling writes them that way — and a list that
     * re-sorted them would be reordering a file the user is about to have edited.
     */
    fun entriesFor(declared: List<String>): List<Permission> =
        declared.distinct().map { entryFor(it) }

    /** The key the selection names one declared permission by. */
    fun itemKeyOf(name: String): String = PatchItem.keyOf(SET_ID, name)

    /**
     * The declared permissions as selectable items.
     *
     * These are [PatchItem]s so the permission switches are the same switches as everywhere else —
     * the same keys, the same [PatchSelection], the same row rendering — even though no
     * [dev.sleepy.app.model.PatchSet] has them and nothing in [PatchItemCatalog] lists them.
     */
    fun itemsOf(declared: List<String>): List<PatchItem> = entriesFor(declared).map { permission ->
        PatchItem(
            setId = SET_ID,
            identity = permission.identity,
            label = permission.label,
            group = DECLARED_GROUP,
            description = permission.description
        )
    }

    /**
     * The declared permissions as rows, each locked when it cannot be removed.
     *
     * [PermissionCoverage.rows] supplies both the per-permission locks and the rule that the last
     * remaining permission stays, so the list and [removals] cannot disagree about what is
     * removable: they are the same call.
     */
    fun rows(declared: List<String>, isKept: (Permission) -> Boolean): List<PermissionRow> =
        PermissionCoverage.rows(entriesFor(declared), isKept)

    /**
     * [rows] against a selection, which is what both the list and the pipeline read.
     *
     * A selection that names no permission is read as "every declaration kept" rather than as
     * "every one of them switched off". The two are not the same claim and the difference is not
     * cosmetic: a selection is a list of what is *on*, so a selection made for the patch sets
     * names no permission by construction, and reading that as a list of removals would show the
     * user a manifest being gutted that nothing is going to touch.
     */
    fun rows(declared: List<String>, selection: PatchSelection): List<PermissionRow> {
        val asked = isEngaged(selection)
        return rows(declared) { !asked || selection.contains(itemKeyOf(it.name)) }
    }

    /**
     * Whether the selection says anything at all about this build's permissions.
     *
     * False is the state a source is in before its APK has been read, and it is the gate that
     * keeps a permission change from being made by a selection that never mentioned permissions:
     * a selection that names none of them removes none of them, however many the build declares.
     */
    fun isEngaged(selection: PatchSelection): Boolean =
        selection.keys.any { it.startsWith("$SET_ID:") }

    /**
     * The declarations to remove, in the order the manifest declares them.
     *
     * A permission is removed when the selection does not name it *and* the row it produces is
     * switchable — so a locked row, and the last permission standing, are never in this list
     * whatever the selection says. Both gates are re-applied here rather than trusted to the
     * caller: this is the list a build is edited with, and an empty selection reaching it means
     * "nothing to do", never "remove everything".
     */
    fun removals(declared: List<String>, selection: PatchSelection): List<String> {
        if (!isEngaged(selection)) return emptyList()
        return rows(declared, selection)
            .filter { it.switchable && !it.kept }
            .map { it.permission.name }
    }

    /**
     * One badge entry.
     *
     * The sixteen launcher badge permissions differ only in whose provider they open, so they are
     * built rather than written out; the consequence is the same for all of them and never
     * involves the notification itself.
     */
    private fun badge(name: String, launcher: String): Permission = entry(
        name = name,
        label = "Badge count ($launcher)",
        what = "Lets the app set the number on its own icon on $launcher. Without it the icon " +
            "shows no unread count there — the notification itself still arrives, and every other " +
            "launcher is unaffected."
    )

    /**
     * One catalogue entry: what it does, and — unless it is locked — that removing it cannot be
     * undone.
     *
     * The consequence is appended here rather than written into each of the entries so that
     * [REMOVAL_CONSEQUENCE] is stated once and stated everywhere, including on permissions added
     * later. [lockReason] is phrased in the vocabulary the blocklist's gates established (see
     * [BlocklistCoverage.REQUIRED_REASON_PREFIX]), because "you cannot switch this off" is the
     * same claim there as here, and the UI reads the prefix.
     */
    private fun entry(
        name: String,
        label: String,
        what: String,
        lockReason: String? = null
    ): Permission = Permission(
        name = name,
        label = label,
        description = "$what $REMOVAL_CONSEQUENCE",
        lockReason = lockReason
    )
}
