package dev.sleepy.app.patches

/**
 * The sections the patch list is organised by: the level above a patch set.
 *
 * The list used to be one card per set—thirty-eight of them, twenty-two holding a single switch—
 * under headings that named where a rule matched rather than what it blocked. A section is the
 * goal a user has instead of the set that implements it: the sets and feature groups that serve
 * one section are listed flat inside it, so the screen has two levels to read (sections, then
 * rows) rather than four.
 *
 * The section is derived rather than written at each of the two hundred call sites:
 * [forHermesGroup] covers the JavaScript features, which already carry the group they belong to,
 * and [forSet] covers every set whose items all belong to one section. An id or group that
 * neither table names falls back to [FIXES] so a set added without an entry cannot break the
 * screen, and a test asserts that no registered set and no declared group actually relies on
 * that fallback.
 */
object PatchSections {

    const val ADS = "Ads and promotions"
    const val DECLUTTER = "Declutter"
    const val TRACKING = "Tracking and analytics"
    const val CRASHES = "Crash reporting"
    const val BACKGROUND = "Background work"
    const val FIXES = "App fixes"
    const val NETWORK = "Network blocking"

    /** Every section, in the order the list shows them. */
    val ALL: List<String> = listOf(ADS, DECLUTTER, TRACKING, CRASHES, BACKGROUND, FIXES, NETWORK)

    private val DESCRIPTIONS: Map<String, String> = mapOf(
        ADS to "Removes the ads, shops and subscription offers the app is built to show you.",
        DECLUTTER to "Hides the cosmetic and promotional things other people's accounts add to " +
            "the interface.",
        TRACKING to "Stops the app recording what you do and sending it out, from usage events " +
            "to device fingerprints.",
        CRASHES to "Stops crash and error reports leaving your device.",
        BACKGROUND to "Stops the timers, samplers and log writing the app runs while you are not " +
            "looking.",
        FIXES to "Fixes the patched app needs in order to keep working, and the update checks " +
            "that would otherwise replace it.",
        NETWORK to "Answers the requests behind ads, tracking and purchases with nothing, so they " +
            "never leave the device."
    )

    /** The one-line description of a section, as the list shows it under the section's name. */
    fun descriptionOf(section: String): String = DESCRIPTIONS.getValue(section)

    /**
     * The section each patch set is listed under, by set id, for sets whose items all belong to
     * one section.
     *
     * The Discord JavaScript set is the exception: its features are spread across the sections by
     * the group they carry, so it is absent here and its items are placed by [forHermesGroup].
     */
    val SET_SECTIONS: Map<String, String> = mapOf(
        DiscordPatches.BUNDLE_LOCK.id to FIXES,
        DiscordPatches.SENTRY.id to CRASHES,
        DiscordPatches.TELEMETRY.id to TRACKING,
        DiscordNativePatches.SENTRY.id to CRASHES,
        DiscordNativePatches.DEEP_LINKS.id to TRACKING,
        DiscordNativePatches.WATCHDOG.id to BACKGROUND,
        DiscordNativePatches.TELEMETRY_MODULES.id to TRACKING,
        DiscordNativePatches.LOG_NOISE.id to BACKGROUND,
        DiscordNativePatches.AUDIT.id to BACKGROUND,
        DiscordNativePatches.OKHTTP_CACHE.id to FIXES,
        DiscordNativePatches.INTERCEPTORS.id to FIXES,
        DiscordNativePatches.JS_POLLS.id to BACKGROUND,
        DiscordNativePatches.SYSTRACE.id to BACKGROUND,
        DiscordNativePatches.CRASH_LOGCAT.id to CRASHES,
        DiscordNativePatches.STARTUP_CLASS_LOAD.id to FIXES,
        DiscordNativePatches.PRIVACY.id to TRACKING,
        DiscordNativePatches.RESOURCE_MONITORS.id to BACKGROUND,
        DiscordNativePatches.CALL_PATH.id to FIXES,
        DiscordNativePatches.MEDIA.id to FIXES,
        DiscordNativePatches.CAMERA_LOG.id to FIXES,
        DiscordNativePatches.FOREGROUND_SERVICE.id to FIXES,
        DiscordNativePatches.EXPERIMENTS.id to FIXES,
        DiscordNativePatches.EXTERNAL_BROWSER.id to FIXES,
        DiscordNativePatches.INCOMING_CALL.id to FIXES,
        DiscordBlocklistPatch.NETWORK_BLOCKLIST.id to NETWORK,
        OctoGramPatches.SPONSORED_MSGS.id to ADS,
        OctoGramPatches.PHOTO_VIEWER_ADS.id to ADS,
        OctoGramPatches.SEARCH_ADS.id to ADS,
        OctoGramPatches.PREMIUM_UPSELL.id to ADS,
        OctoGramPatches.PREMIUM_SETTINGS.id to ADS,
        OctoGramPatches.HIDE_BUSINESS.id to ADS,
        OctoGramPatches.PREMIUM_SHEETS.id to ADS,
        OctoGramPatches.FIREBASE_ABT.id to TRACKING,
        OctoGramPatches.FIREBASE_REMOTE_CONFIG.id to TRACKING,
        OctoGramPatches.FIREBASE_REMOTE_CONFIG_KTX.id to TRACKING,
        OctoGramPatches.FIREBASE_DATATRANSPORT.id to TRACKING,
        OctoGramPatches.OCTO_LOGGER.id to TRACKING,
        OctoGramPatches.LOGGING_GATE.id to TRACKING,
        OctoGramPatches.CRASH_REPORTER.id to CRASHES,
        OctoGramPatches.OTA_UPDATER.id to FIXES,
        OctoGramPatches.EXTERNAL_BROWSER.id to FIXES
    )

    /**
     * The section each Discord JavaScript feature group is listed under, by the group's own name.
     *
     * The names are the literals of [DiscordHermesFunctionCatalog.FEATURES] rather than a second
     * taxonomy, and a test asserts this table names every group that catalog declares, so a group
     * reworded there fails here rather than moving its items to another section in silence.
     */
    val HERMES_GROUP_SECTIONS: Map<String, String> = mapOf(
        "Shop and promotions" to ADS,
        "Gifts" to ADS,
        "Billing and subscriptions" to ADS,
        "App rating prompts" to ADS,
        "Profile decorations" to DECLUTTER,
        "Profile" to DECLUTTER,
        "Quests" to DECLUTTER,
        "Wishlists" to DECLUTTER,
        "Analytics" to TRACKING,
        "Session telemetry" to TRACKING,
        "Device fingerprinting" to TRACKING,
        "Spotify" to TRACKING,
        "Crash reporting" to CRASHES,
        "Performance metrics" to BACKGROUND,
        "Startup timing" to BACKGROUND,
        "Debug logs" to BACKGROUND,
        "Memory and stability" to FIXES,
        "Unused stubs" to FIXES
    )

    /** The section a JavaScript feature's group is listed under; [FIXES] when nothing names it. */
    fun forHermesGroup(group: String): String = HERMES_GROUP_SECTIONS[group] ?: FIXES

    /** The section a set's items are listed under; [FIXES] when nothing names the set. */
    fun forSet(setId: String): String = SET_SECTIONS[setId] ?: FIXES
}
