package dev.sleepy.app.patches

/**
 * The permissions each supported release declares, shipped with the app.
 *
 * Shipped rather than downloaded so the list can be read before anything is fetched; a source
 * pins an exact build, so each list describes that build. Each is in manifest declaration order,
 * which is also the order the rows are shown in. Where the build at the source no longer matches
 * its list, the difference is reported in the step log rather than left for the user to find.
 */
object DeclaredPermissions {

    /** Every package sleepy ships a declaration list for, which is every source it offers. */
    val packages: Set<String> get() = BY_PACKAGE.keys

    /**
     * The declarations of the release with this package name, in the order its manifest lists them.
     *
     * `null` means no release of that package is listed here, which is not a claim that it declares
     * nothing: it is the answer for a package this app has no list for, and the caller reads the
     * build instead.
     */
    fun forPackage(packageName: String?): List<String>? = packageName?.let { BY_PACKAGE[it] }

    // Both lists were read from the builds `sources.json` points at, using the same reader the
    // patch run uses—`BinaryXmlEditor` on the APK's AndroidManifest.xml—and a test compares
    // them against those builds permission for permission and in order. A list edited to a value
    // those builds do not declare fails that test rather than the user's device.

    /** Discord Alpha 351.2 (`com.discord`), 33 declarations. */
    private val DISCORD_ALPHA_351_2 = listOf(
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

    // After the lists rather than before them: an object's properties initialize in the order they
    // are written, so a map declared before its lists reads them as nulls.
    private val BY_PACKAGE: Map<String, List<String>> = mapOf(
        "com.discord" to DISCORD_ALPHA_351_2,
        "it.octogram.android" to OCTOGRAM_3_6_1
    )
}
