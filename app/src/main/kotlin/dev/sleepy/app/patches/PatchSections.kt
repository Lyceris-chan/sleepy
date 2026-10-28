package dev.sleepy.app.patches

/**
 * The sections the patch list is organised by: the level above a patch set.
 *
 * The list used to be one card per set—thirty-eight of them, twenty-two holding a single switch—
 * under headings that named where a rule matched rather than what it blocked. A section is the
 * goal a user has instead of the set that implements it: the sets and feature groups that serve
 * one section are collected under short group headings inside it, so the screen reads as section,
 * group, row rather than as the structure of the app's code.
 *
 * The section is derived rather than written at each of the two hundred call sites:
 * [forHermesGroup] covers the JavaScript features, which already carry the group they belong to,
 * and [forSet] covers every set whose items all belong to one section. An id or group that
 * neither table names falls back to [FIXES] so a set added without an entry cannot break the
 * screen, and a test asserts that no registered set and no declared group actually relies on
 * that fallback.
 */
object PatchSections {

    const val ADS = "Ads and offers"
    const val DECLUTTER = "Interface cleanup"
    const val TRACKING = "Privacy and tracking"
    const val CRASHES = "Crash reports"
    const val BACKGROUND = "Performance and battery"
    const val CALLS = "Calls and media"
    const val FIXES = "App fixes"
    const val NETWORK = "Network blocking"

    /** Every section, in the order the list shows them. */
    val ALL: List<String> =
        listOf(ADS, DECLUTTER, TRACKING, CRASHES, BACKGROUND, CALLS, FIXES, NETWORK)

    private val DESCRIPTIONS: Map<String, String> = mapOf(
        ADS to "Removes the shops, promotions, subscription offers and upsells the app is built " +
            "to show you.",
        DECLUTTER to "Hides the cosmetic extras other people's accounts add to the interface.",
        TRACKING to "Stops the app recording what you do and sending it out, from usage events " +
            "to device identifiers.",
        CRASHES to "Stops crash and error reports leaving your device.",
        BACKGROUND to "Stops the timers, samplers and log writing the app runs while you are not " +
            "looking.",
        CALLS to "Fixes calls and media: camera and screen share, background calls, and the video " +
            "renderer.",
        FIXES to "Keeps the patched app working: updates, links, caching and the other fixes it " +
            "needs.",
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
        DiscordNativePatches.STARTUP_CLASS_LOAD.id to BACKGROUND,
        DiscordNativePatches.PRIVACY.id to TRACKING,
        DiscordNativePatches.RESOURCE_MONITORS.id to BACKGROUND,
        DiscordNativePatches.CALL_PATH.id to CALLS,
        DiscordNativePatches.MEDIA.id to CALLS,
        DiscordNativePatches.CAMERA_LOG.id to CALLS,
        DiscordNativePatches.FOREGROUND_SERVICE.id to CALLS,
        DiscordNativePatches.EXPERIMENTS.id to BACKGROUND,
        DiscordNativePatches.EXTERNAL_BROWSER.id to FIXES,
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
        OctoGramPatches.EXTERNAL_BROWSER.id to FIXES,
        OctoGramPatches.HIDE_TYPING.id to TRACKING,
        OctoGramPatches.ALLOW_SCREENSHOTS.id to FIXES,
        OctoGramPatches.BYPASS_INTEGRITY.id to FIXES,
        OctoGramPatches.UNLOCK_PREMIUM.id to ADS
    )

    /**
     * The section each Discord JavaScript feature group is listed under, by the group's own name.
     *
     * The names are the literals of [DiscordHermesFunctionCatalog.FEATURES] rather than a second
     * taxonomy, and a test asserts this table names every group that catalog declares, so a group
     * reworded there fails here rather than moving its items to another section in silence.
     */
    val HERMES_GROUP_SECTIONS: Map<String, String> = mapOf(
        "Shop and offers" to ADS,
        "Nitro prompts" to ADS,
        "Premium prompts" to ADS,
        "Store pages" to ADS,
        "Free trials" to ADS,
        "Server boosts" to ADS,
        "Leaderboards" to DECLUTTER,
        "Gifts" to ADS,
        "Billing" to ADS,
        "Rating prompts" to ADS,
        "Profile decorations" to DECLUTTER,
        "Chat display" to DECLUTTER,
        "Privacy" to TRACKING,
        "Profile" to DECLUTTER,
        "Game activity" to DECLUTTER,
        "Quests" to ADS,
        "Wishlists" to ADS,
        "Usage analytics" to TRACKING,
        "Session reporting" to TRACKING,
        "Device fingerprint" to TRACKING,
        "Spotify" to TRACKING,
        "Crash reports" to CRASHES,
        "Performance sampling" to BACKGROUND,
        "Call quality" to CALLS,
        "Startup timing" to BACKGROUND,
        "Debug logging" to BACKGROUND,
        "Memory use" to FIXES,
        "Miscellaneous fixes" to FIXES
    )

    /**
     * The group a whole-set row is listed under inside its section, by set id.
     *
     * A split set's items carry their own group. A set that is one row has no item to carry one,
     * so it takes a short group here rather than repeating its own name as a heading above every
     * row - which is what the row label already is. A set absent from this table gets its section
     * name, and the list then shows no group heading above it because the heading would repeat
     * the section.
     */
    private val GROUP_OF_SET: Map<String, String> = mapOf(
        DiscordPatches.BUNDLE_LOCK.id to "Updates",
        DiscordPatches.SENTRY.id to "Crash reports",
        DiscordPatches.TELEMETRY.id to "Telemetry",
        DiscordNativePatches.SENTRY.id to "Crash reports",
        DiscordNativePatches.DEEP_LINKS.id to "Tracking",
        DiscordNativePatches.WATCHDOG.id to "Timers",
        DiscordNativePatches.TELEMETRY_MODULES.id to "Telemetry",
        DiscordNativePatches.LOG_NOISE.id to "Logging",
        DiscordNativePatches.AUDIT.id to "Logs and metrics",
        DiscordNativePatches.OKHTTP_CACHE.id to "Networking",
        DiscordNativePatches.INTERCEPTORS.id to "Networking",
        DiscordNativePatches.JS_POLLS.id to "Timers",
        DiscordNativePatches.SYSTRACE.id to "Logging",
        DiscordNativePatches.CRASH_LOGCAT.id to "Crash reports",
        DiscordNativePatches.STARTUP_CLASS_LOAD.id to "Startup",
        DiscordNativePatches.PRIVACY.id to "Tracking",
        DiscordNativePatches.RESOURCE_MONITORS.id to "Performance",
        DiscordNativePatches.CALL_PATH.id to "Calls and media",
        DiscordNativePatches.MEDIA.id to "Calls and media",
        DiscordNativePatches.CAMERA_LOG.id to "Calls and media",
        DiscordNativePatches.FOREGROUND_SERVICE.id to "Calls and media",
        DiscordNativePatches.EXPERIMENTS.id to "Performance",
        DiscordNativePatches.EXTERNAL_BROWSER.id to "Links",
        OctoGramPatches.SPONSORED_MSGS.id to "Channel posts",
        OctoGramPatches.PHOTO_VIEWER_ADS.id to "Photo viewer",
        OctoGramPatches.SEARCH_ADS.id to "Search",
        OctoGramPatches.PREMIUM_UPSELL.id to "Premium prompts",
        OctoGramPatches.PREMIUM_SETTINGS.id to "Premium prompts",
        OctoGramPatches.HIDE_BUSINESS.id to "Telegram Business",
        OctoGramPatches.PREMIUM_SHEETS.id to "Premium prompts",
        OctoGramPatches.FIREBASE_ABT.id to "Firebase",
        OctoGramPatches.FIREBASE_REMOTE_CONFIG.id to "Firebase",
        OctoGramPatches.FIREBASE_REMOTE_CONFIG_KTX.id to "Firebase",
        OctoGramPatches.FIREBASE_DATATRANSPORT.id to "Firebase",
        OctoGramPatches.OCTO_LOGGER.id to "Logging",
        OctoGramPatches.LOGGING_GATE.id to "Logging",
        OctoGramPatches.CRASH_REPORTER.id to "Crash reports",
        OctoGramPatches.OTA_UPDATER.id to "Updates",
        OctoGramPatches.EXTERNAL_BROWSER.id to "Links",
        OctoGramPatches.HIDE_TYPING.id to "Typing",
        OctoGramPatches.ALLOW_SCREENSHOTS.id to "Screenshots",
        OctoGramPatches.BYPASS_INTEGRITY.id to "Integrity checks",
        OctoGramPatches.UNLOCK_PREMIUM.id to "Premium features"
    )

    /**
     * Short headings used by the list, by the catalog's own group name.
     *
     * The catalogs name a feature precisely - "Store pages", "Wishlists", "Free trials" - and
     * those names are worth keeping in the model. A list with eighteen headings over thirty-two
     * rows is not: several of those names describe the same thing to a reader, and a one-row
     * heading is a divider, not a group. The aliases fold those names into the handful of
     * headings the screen shows, without renaming the group an item records or moving it between
     * sections.
     */
    private val GROUP_ALIASES: Map<String, String> = mapOf(
        "Gifts" to "Shop and offers",
        "Billing" to "Shop and offers",
        "Store pages" to "Shop and offers",
        "Wishlists" to "Shop and offers",
        "Free trials" to "Shop and offers",
        "Nitro prompts" to "Premium and Nitro",
        "Premium prompts" to "Premium and Nitro",
        "Premium screens" to "Premium and Nitro",
        "Premium rows" to "Premium and Nitro",
        "Premium features" to "Premium and Nitro",
        "Rating prompts" to "Ratings and surveys",
        "Channel posts" to "Sponsored content",
        "Photo viewer" to "Sponsored content",
        "Search" to "Sponsored content",
        "Profile decorations" to "Profile and decorations",
        "Profile" to "Profile and decorations",
        "Game activity" to "Games and leaderboards",
        "Leaderboards" to "Games and leaderboards",
        "Usage analytics" to "Analytics",
        "Session reporting" to "Telemetry and sessions",
        "Telemetry" to "Telemetry and sessions",
        "Device fingerprint" to "Tracking and fingerprinting",
        "Tracking" to "Tracking and fingerprinting",
        "OctoGram logs" to "Logs",
        "Telegram logs" to "Logs",
        "Performance sampling" to "Performance",
        "Startup timing" to "Startup",
        "Debug logging" to "Logs and metrics",
        "Logging" to "Logs and metrics",
        "Call quality" to "Calls and media",
        "Screenshots" to "Compatibility",
        "Integrity checks" to "Compatibility",
        "Memory use" to "Stability",
        "Miscellaneous fixes" to "Stability",
        "Privacy" to "Typing"
    )

    /** The heading a group is shown under; the group's own name when no alias folds it. */
    fun displayGroup(group: String): String = GROUP_ALIASES[group] ?: group

    /** The section a JavaScript feature's group is listed under; [FIXES] when nothing names it. */
    fun forHermesGroup(group: String): String = HERMES_GROUP_SECTIONS[group] ?: FIXES

    /** The section a set's items are listed under; [FIXES] when nothing names the set. */
    fun forSet(setId: String): String = SET_SECTIONS[setId] ?: FIXES

    /**
     * The group a set's whole-set row is listed under, or its section when no group names it.
     *
     * The section is the fallback rather than the set's label so a row with no group of its own
     * does not get a heading that repeats the row.
     */
    fun groupForSet(setId: String): String = GROUP_OF_SET[setId] ?: forSet(setId)
}
