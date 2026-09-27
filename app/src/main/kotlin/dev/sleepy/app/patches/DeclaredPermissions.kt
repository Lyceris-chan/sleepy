package dev.sleepy.app.patches

/**
 * The permissions each supported release declares, shipped with the app.
 *
 * ## Why the list is here rather than downloaded
 * The list exists so a permission can be read, weighed and switched off *before* anything is
 * fetched. It used to be read out of the build being patched, which meant the section had nothing
 * in it until the whole archive — 96 MB for one of these builds — had been downloaded and opened,
 * and a list that costs a download to see is a list most people never see. What a release declares
 * is fixed when the release is published, so it can be written down once, here, and shown at once.
 *
 * A source names an exact build, not "the latest Discord": `sources.json` pins a version, a URL
 * and — for one of them — a SHA-256, and a list here is the list of that build. [packages] names
 * what is shipped, and a source pointing anywhere else is handled by reading the build instead of
 * by guessing from a list that does not describe it.
 *
 * ## The order is the manifest's order
 * Each list is written in the order its manifest declares the permissions, which is the order the
 * rows are shown in, so the list a user reads is the list a manifest edit walks. Declaration order
 * is also the only order that is a fact about the build rather than a choice made here.
 *
 * ## A build that no longer matches is a thing to report
 * A shipped list is only right while the build at the source's URL is the build it was read from.
 * Where it is not, the difference is stated rather than resolved silently: a permission the build
 * declares and this list does not name is exactly the permission nobody can see, so it is reported
 * in the permission section and again in the run's step log rather than being kept quiet.
 */
object DeclaredPermissions {

    /** Every package sleepy ships a declaration list for, which is every source it offers. */
    val packages: Set<String> get() = BY_PACKAGE.keys

    /**
     * The declarations of the release with this package name, in the order its manifest lists them.
     *
     * `null` means no release of that package is listed here, which is not a claim that it declares
     * nothing: it is the answer for a package this app knows nothing about, and the caller reads
     * the build instead.
     */
    fun forPackage(packageName: String?): List<String>? = packageName?.let { BY_PACKAGE[it] }

    // Both lists were read from the builds `sources.json` points at, through the same reader the
    // patch run uses — `BinaryXmlEditor` on the APK's AndroidManifest.xml — and a test compares
    // them against those builds permission for permission and in order. A list edited to say
    // something those builds do not say fails there rather than on someone's phone.

    /** Discord Alpha 348.5 (`com.discord`), 33 declarations. */
    private val DISCORD_ALPHA_348_5 = listOf(
        "android.permission.ACCESS_NETWORK_STATE", "android.permission.BROADCAST_STICKY",
        "android.permission.INTERNET", "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
        "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.CAMERA",
        "android.permission.FOREGROUND_SERVICE", "android.permission.RECORD_AUDIO",
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION", "android.permission.VIBRATE",
        "android.permission.WAKE_LOCK", "android.permission.USE_FULL_SCREEN_INTENT",
        "android.permission.SYSTEM_ALERT_WINDOW", "android.permission.READ_CONTACTS",
        "com.google.android.gms.permission.AD_ID", "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE_CAMERA",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "android.permission.MANAGE_OWN_CALLS", "android.permission.ACCESS_WIFI_STATE",
        "com.android.vending.BILLING", "com.google.android.c2dm.permission.RECEIVE",
        "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
        "com.samsung.android.mapsagent.permission.READ_APP_INFO",
        "com.huawei.appmarket.service.commondata.permission.GET_COMMON_DATA",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "com.discord.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE"
    )

    /** OctoGram 3.6.1 (`it.octogram.android`), 69 declarations. */
    private val OCTOGRAM_3_6_1 = listOf(
        "android.permission.READ_CLIPBOARD", "android.permission.BLUETOOTH_CONNECT",
        "android.permission.INTERNET", "android.permission.FOREGROUND_SERVICE",
        "android.permission.POST_PROMOTED_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
        "android.permission.FOREGROUND_SERVICE_CAMERA",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE", "android.permission.RECORD_AUDIO",
        "android.permission.MODIFY_AUDIO_SETTINGS", "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE", "android.permission.WAKE_LOCK",
        "android.permission.READ_EXTERNAL_STORAGE", "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO",
        "android.permission.GET_ACCOUNTS", "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS", "android.permission.MANAGE_ACCOUNTS",
        "android.permission.READ_PROFILE", "android.permission.WRITE_SYNC_SETTINGS",
        "android.permission.READ_SYNC_SETTINGS", "android.permission.AUTHENTICATE_ACCOUNTS",
        "android.permission.VIBRATE", "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.READ_PHONE_STATE", "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.USE_FINGERPRINT", "android.permission.USE_BIOMETRIC",
        "android.permission.INSTALL_SHORTCUT",
        "com.android.launcher.permission.INSTALL_SHORTCUT",
        "com.android.launcher.permission.UNINSTALL_SHORTCUT", "android.permission.CAMERA",
        "android.permission.BLUETOOTH", "android.permission.MANAGE_OWN_CALLS",
        "android.permission.USE_FULL_SCREEN_INTENT",
        "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.READ_PHONE_NUMBERS",
        "android.permission.POST_NOTIFICATIONS",
        "com.sec.android.provider.badge.permission.READ",
        "com.sec.android.provider.badge.permission.WRITE",
        "com.htc.launcher.permission.READ_SETTINGS",
        "com.htc.launcher.permission.UPDATE_SHORTCUT",
        "com.sonyericsson.home.permission.BROADCAST_BADGE",
        "com.sonymobile.home.permission.PROVIDER_INSERT_BADGE",
        "com.anddoes.launcher.permission.UPDATE_COUNT",
        "com.majeur.launcher.permission.UPDATE_BADGE",
        "com.huawei.android.launcher.permission.CHANGE_BADGE",
        "com.huawei.android.launcher.permission.READ_SETTINGS",
        "com.huawei.android.launcher.permission.WRITE_SETTINGS",
        "android.permission.READ_APP_BADGE", "com.oppo.launcher.permission.READ_SETTINGS",
        "com.oppo.launcher.permission.WRITE_SETTINGS",
        "me.everything.badger.permission.BADGE_COUNT_READ",
        "me.everything.badger.permission.BADGE_COUNT_WRITE", "com.android.vending.BILLING",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "it.octogram.ondevice.permission.USE_TRANSLATION_SERVICE",
        "com.google.android.providers.gsf.permission.READ_GSERVICES",
        "com.google.android.c2dm.permission.RECEIVE",
        "it.octogram.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    )

    // After the lists rather than above them: an object's properties initialize in the order they
    // are written, so a map built before its lists would hold nulls.
    private val BY_PACKAGE: Map<String, List<String>> = mapOf(
        "com.discord" to DISCORD_ALPHA_348_5,
        "it.octogram.android" to OCTOGRAM_3_6_1
    )
}
