package dev.sleepy.app.patches

import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.patches.DiscordHermesBundlePatch.FunctionPatch

/**
 * The description and feature group for every entry in [DiscordHermesBundlePatch.PATCHES], so
 * the set can be offered as switches a user can tell apart.
 *
 * An id and its description move together: a port resolves the function independently and the
 * entry follows the id. Entries are listed in patch-table order and cover every id, which a test
 * asserts. Names are labels, not keys — this bundle has several functions with the same name.
 */
object DiscordHermesFunctionCatalog {

    /**
     * What patching one function does, stated for a reader who is not looking at the bytecode.
     *
     * @param functionId The function's id in [DiscordHermesBundlePatch.PATCHES].
     * @param description The user-facing line stating what patching the function does. It is the
     *   detail behind a [Feature], shown in that item's technical panel, not a list row.
     */
    data class Entry(
        val functionId: Int,
        val description: String
    )

    /**
     * One thing a user can switch on, and the functions that make it happen.
     *
     * An item used to be one function, which put 209 rows in front of a user with 98 of them
     * unnamed and the rest named after minified JavaScript identifiers. A feature is the unit a
     * person actually chooses between—"guild tags" rather than the seven functions that draw one.
     * The functions stay addressable: they are listed in the item's technical panel, and the
     * selection keys on the feature.
     *
     * @param slug The feature's identity. A saved selection records this, so it has to stay stable
     *   across releases; it is not the label, which is free to be reworded.
     * @param group The heading the item is listed under.
     * @param label The item's name, as short as it can be and still say what it does.
     * @param description One or two sentences on what switching the item on does.
     * @param functionIds Every function this feature patches.
     */
    data class Feature(
        val slug: String,
        val group: String,
        val label: String,
        val description: String,
        val functionIds: List<Int>
    )

    // Feature groups, in the order the list shows them. Declared here rather than inline so each
    // is named once and a typo fails to compile instead of creating a heading of one item.
    private const val SHOP_GROUP = "Shop and promotions"
    private const val NITRO_UPSELL_GROUP = "Nitro upsells"
    private const val UPSELL_GROUP = "Premium upsells"
    private const val STOREFRONT_GROUP = "Storefront"
    private const val TRIAL_GROUP = "Trials"
    private const val LEADERBOARD_GROUP = "Leaderboards"
    private const val GAME_GROUP = "Game status"
    private const val GIFTS_GROUP = "Gifts"
    private const val BILLING_GROUP = "Billing and subscriptions"
    private const val LOOK_GROUP = "Profile decorations"
    private const val PROFILE_GROUP = "Profile"
    private const val QUESTS_GROUP = "Quests"
    private const val WISHLIST_GROUP = "Wishlists"
    private const val SPOTIFY_GROUP = "Spotify"
    private const val SURVEYS_GROUP = "App rating prompts"
    private const val ANALYTICS_GROUP = "Analytics"
    private const val CRASH_GROUP = "Crash reporting"
    private const val PERFORMANCE_GROUP = "Performance metrics"
    private const val VIDEO_GROUP = "Video calls"
    private const val TELEMETRY_GROUP = "Session telemetry"
    private const val STARTUP_GROUP = "Startup timing"
    private const val LOGGING_GROUP = "Debug logs"
    private const val FINGERPRINT_GROUP = "Device fingerprinting"
    private const val STABILITY_GROUP = "Memory and stability"
    private const val LEFTOVER_GROUP = "Unused stubs"

    /** Every patched function, in the order the patch table applies them. */
    val ENTRIES: List<Entry> = listOf(
        entry(1379, "Slows the process-usage poll from once a second to once an hour. " +
            "The same feature stubs the poll callback, so the timer only wakes the " +
            "JavaScript thread; at one second it did that 86,400 times a day for values " +
            "the app no longer collects."),
        entry(8346, "Clamps the entry count of the message-markup cache. It was built with no " +
            "limit and with its expiry clock refreshed on every read, so it grew for as long as " +
            "the app ran, on the path that draws every message."),
        entry(15089, "351.2.5 no longer has a startup initializer at this id: it is the " +
            "module factory for modules/telemetry_ring/native/channels/NormalTelemetry.tsx. The " +
            "stub returns undefined without running the factory, so the module never assigns " +
            "its default export and an importer sees no NormalTelemetry channel."),
        entry(15959, "Gives the Settings Nitro / Manage Nitro row the always-false " +
            "predicate that hides it. Its backend is already answered with a 204, so the row led " +
            "to a screen that could do nothing."),
        entry(15965, "Gives the Settings Manage Plan row the always-false predicate " +
            "that hides it."),
        entry(15972, "Gives the Settings Server Boost row the always-false " +
            "predicate that hides it."),
        entry(16829, "Gives the Settings CollectiblesShop route the always-false predicate " +
            "that hides its row. This def had none of its own, so the route object itself is " +
            "edited, the same lever that removed the other billing rows."),
        entry(19159, "Stops the log aggregator from feeding the in-app debug panel's log " +
            "buffer."),
        entry(19188, "Stops the time-to-interactive tracker recording a " +
            "milestone's start."),
        entry(19190, "Stops the time-to-interactive tracker recording a " +
            "milestone's end."),
        entry(19192, "Stops the time-to-interactive tracker storing a value for " +
            "a milestone."),
        entry(19200, "Stops the time-to-interactive tracker recording a " +
            "measurement."),
        entry(19231, "Stops the tracing recorder appending its formatted line to " +
            "the in-memory startup log."),
        entry(19232, "Stops startup milestone marks being recorded, one of the " +
            "recorders called at every startup step."),
        entry(19233, "Stops the milestone mark-and-log recorder used by the " +
            "traced operations."),
        entry(19234, "Stops import-detail records being added to the startup " +
            "trace."),
        entry(19235, "Stops delta marks from being added to the startup trace."),
        entry(19236, "Stops timestamped marks from being recorded in the startup " +
            "trace."),
        entry(19237, "Stops detail records from being added to the startup " +
            "trace."),
        entry(19240, "Stops the server-issued trace id from being stored on the " +
            "startup trace."),
        entry(20772, "Stops the fingerprint handler, which computed the old and new " +
            "device fingerprint for every tracked event and reported the transition."),
        entry(20923, "Returns zero from the libdiscore action-telemetry sample rate, " +
            "which the server controls, so the client collects no action payloads whatever " +
            "treatment it is assigned."),
        entry(20925, "Returns false from the libdiscore action-telemetry gate, which " +
            "runs on every redux action and decides whether to collect that action's serialized " +
            "payload, so no payload is collected."),
        entry(21696, "Stops Sentry's own breadcrumb collector, which recorded one for " +
            "every Flux dispatcher action, so the collection stops at the source rather than at " +
            "the sender."),
        entry(23808, "Answers false, so whatever disableActivityHostLeftNitroUpsell gates stays off."),
        entry(24395, "Stops Sentry attaching the signed-in user's identity to error " +
            "reports."),
        entry(24396, "Stops Sentry clearing the user identity it would otherwise have " +
            "attached."),
        entry(24397, "Stops tags being attached to the Sentry scope, so error reports " +
            "carry no user-defined tags."),
        entry(24398, "Stops extra context being attached to the Sentry scope."),
        entry(24399, "Stops JavaScript exceptions being reported to Sentry, so stack " +
            "traces stay on the device."),
        entry(24400, "Stops JavaScript crash reports being sent to Sentry."),
        entry(24401, "Stops manually logged messages being sent to Sentry."),
        entry(24402, "Stops feature-flag names being recorded on the Sentry scope."),
        entry(24403, "Stops Sentry's JavaScript API adding breadcrumbs, which is what " +
            "recorded UI clicks and navigations."),
        entry(24405, "Stops the deliberate crash call from reaching Sentry."),
        entry(24406, "Stops the memory-warning report from reaching Sentry."),
        entry(24407, "Stops the crash-handled marker being recorded on the Sentry scope."),
        entry(24414, "Stops the Sentry JavaScript SDK from initializing at all, so no " +
            "client is bound and none of its automatic integrations (global error handlers, " +
            "breadcrumbs, promise-rejection tracking) are installed."),
        entry(24473, "Silences the [Analytics] debug reporter, so the client stops " +
            "writing its analytics events into the debug log."),
        entry(24536, "Silences the analytics store's own tracking closure, which emits " +
            "events independently of the central emitter."),
        entry(24544, "Silences the [Analytics] network-action reporter, so per-request " +
            "analytics are never emitted."),
        entry(26452, "Returns null from the avatar decoration data " +
            "transform, the choke point every caller that brings a decoration into the client " +
            "passes through. Those callers assign the result without reading it, and the " +
            "function already returns null on its own empty paths, so no decoration reaches " +
            "the client."),
        entry(26497, "Returns false from the analytics experiment flag, so the 500 ms " +
            "telemetry-ring export loop can never start."),
        entry(32480, "Does nothing, so the coachmark binding BadgesCoachmarkBindings never mounts."),
        entry(32481, "Does nothing, so the coachmark binding BadgesCoachmarkBindings never mounts."),
        entry(32482, "Returns null where BadgesCoachmarkRiveInner used to draw, so that surface never appears."),
        entry(32483, "Returns null where BadgesCoachmarkRiveInner used to draw, so that surface never appears."),
        entry(32484, "Returns null where BadgesCoachmarkRiveWithBoundary used to draw, so that surface never appears."),
        entry(32485, "Returns null where BadgesCoachmarkRiveWithBoundary used to draw, so that surface never appears."),
        entry(32928, "Answers false, so whatever ageEligibleForPremiumUpsell gates stays off."),
        entry(33733, "Silences the emitter behind the forum and clickstream trackers, " +
            "so those impressions are never sent."),
        entry(34425, "Does nothing, so a channel set to full quality cannot raise the " +
            "encoder above the lowered camera capture."),
        entry(34446, "Caps the camera capture at 640×360 at 24 frames per second and the " +
            "encoder's maximum bitrate at 1 Mbps, about four times fewer pixels than 720p."),
        entry(34451, "Builds the encoder ladder from a 640×360 pixel budget, so the encoder's " +
            "largest rung matches the camera instead of asking it to upscale."),
        entry(36002, "Stops the monitoring agent's counter, which about twenty-five " +
            "modules call as they do their work."),
        entry(36003, "Stops the monitoring agent's distribution sample."),
        entry(36004, "Stops the monitoring agent flushing what it accumulated to the " +
            "two-minute /metrics/v2 upload."),
        entry(37159, "Returns null where StageBoostingActionSheet used to draw, so that surface " +
            "never appears."),
        entry(37174, "Returns null where PremiumGuildSubscribeModal used to draw, so that surface " +
            "never appears."),
        entry(37175, "Returns null where PremiumGuildSubscribeModal used to draw, so that surface " +
            "never appears."),
        entry(40558, "Does nothing, so the action hideInlineGuildCapUpsell used to perform no longer happens."),
        entry(40655, "Returns false from the storefront capability check, which hides the " +
            "guild sidebar's game-shop row and blocks the redirect into it."),
        entry(40682, "Answers false, so whatever isOnSocialLayerStorefrontPage gates stays off."),
        entry(40951, "Returns false from the Guild Shop visibility hook, one of " +
            "the two visibility gates the creator-monetisation guild shop module holds. The " +
            "hook's callers test for exactly false, which is the shape this stub returns."),
        entry(40952, "Returns false from the Guild Shop visibility hook, one of " +
            "the two visibility gates the creator-monetisation guild shop module holds. The " +
            "hook's callers test for exactly false, which is the shape this stub returns."),
        entry(40953, "Returns false from the plain isGuildShopVisibleInGuild " +
            "predicate exported beside the visibility hook, so a caller that asks whether the " +
            "guild shop is visible is answered no."),
        entry(40954, "Returns false from the Guild Shop preview visibility hook, " +
            "the second of the module's two visibility gates, so the preview surface stays " +
            "hidden. Its callers test for exactly false."),
        entry(40955, "Returns false from the Guild Shop preview visibility hook, " +
            "the second of the module's two visibility gates, so the preview surface stays " +
            "hidden. Its callers test for exactly false."),
        entry(41795, "Returns null where GuildBoostingSubscribeButton used to draw, so that surface " +
            "never appears."),
        entry(41796, "Returns null where GuildBoostingSubscribeButton used to draw, so that surface " +
            "never appears."),
        entry(41853, "Returns null where PremiumModal used to draw, so that surface never appears."),
        entry(41854, "Returns null where PremiumModal used to draw, so that surface never appears."),
        entry(41939, "Does nothing, so the action openPremiumPlanSelectionActionSheet used to " +
            "perform no longer happens."),
        entry(41941, "Returns null where PremiumPlanSelectionActionSheet used to draw, so that " +
            "surface never appears."),
        entry(41949, "Returns null where PremiumPlanSelectionActionSheetCTA used to draw, so that " +
            "surface never appears."),
        entry(41950, "Returns null where PremiumPlanSelectionActionSheetCTA used to draw, so that " +
            "surface never appears."),
        entry(41951, "Returns null where PremiumPlanSelectionActionSheetWithOrderCTX used to draw, " +
            "so that surface never appears."),
        entry(41998, "Returns null where PremiumPlanActionSheetHeader used to draw, so that surface " +
            "never appears."),
        entry(41999, "Returns null where PremiumPlanActionSheetHeader used to draw, so that surface " +
            "never appears."),
        entry(42008, "Answers false, so whatever useShouldShowPremiumTrialUserSettingsAvatarBadge gates stays off."),
        entry(42009, "Answers false, so whatever useShouldShowPremiumTrialUserSettingsAvatarBadge gates stays off."),
        entry(42212, "Stops the network-statistics sampler restarting its " +
            "timers when the app changes state."),
        entry(42213, "Stops the network-statistics recorder writing its " +
            "accumulated events to device storage."),
        entry(42214, "Stops the network-statistics sampler's own track(), " +
            "which queued events locally."),
        entry(42216, "Stops the network-statistics sampler's app-state " +
            "listener, which reports to the sampler whether each app-state change leaves the app " +
            "active."),
        entry(42217, "Stops the network-statistics sampler's per-message " +
            "analytics accumulator, which adds each message's send-analytics duration and queue " +
            "size to its totals."),
        entry(42218, "Stops the network-statistics sampler's per-message " +
            "counter, whose whole body increments the sampler's running count."),
        entry(42245, "Stops the session heartbeat scheduler being initialized, " +
            "so its timer, its periodic API ping and its breadcrumb are never created."),
        entry(42401, "Stops the message-cache statistic that counted channel " +
            "fetches starting."),
        entry(42402, "Stops the message-cache statistic that counted a channel " +
            "being served locally."),
        entry(42403, "Stops the message-cache statistic that counted a channel " +
            "being fetched over the network."),
        entry(44393, "Stops the app-rating survey poll, which fetched the survey and " +
            "emitted analytics for it."),
        entry(46252, "Returns null where DoubleTapNitroAlert used to draw, so that surface never appears."),
        entry(46253, "Returns null where DoubleTapNitroAlert used to draw, so that surface never appears."),
        entry(47318, "Returns false from the guild-tag display hook, so callers " +
            "that decide whether to build a tag row build none. The nulled tag components hide " +
            "the chips themselves; this also stops them being constructed."),
        entry(47319, "Returns false from the guild-tag display hook, so callers " +
            "that decide whether to build a tag row build none. The nulled tag components hide " +
            "the chips themselves; this also stops them being constructed."),
        entry(47323, "Returns false from the plain guild-tag display predicate, " +
            "so a caller that asks whether to show a guild tag is answered no."),
        entry(47551, "Answers no from the Shop This Look experiment gate, the " +
            "check every entry point into the feature reads. Editing the sheet and the " +
            "coachmark alone leaves the opener, whose body is shared with an unrelated " +
            "display-name sheet, so the menu row was still built and still opened an empty " +
            "sheet. Nothing that renders Shop This Look is reachable once this says no."),
        entry(47552, "Answers no from the Shop This Look experiment gate, the " +
            "check every entry point into the feature reads. Editing the sheet and the " +
            "coachmark alone leaves the opener, whose body is shared with an unrelated " +
            "display-name sheet, so the menu row was still built and still opened an empty " +
            "sheet. Nothing that renders Shop This Look is reachable once this says no."),
        entry(47561, "Stops the storefront fetching products by SKU id. The " +
            "implementation is stubbed rather than the exported trampoline, whose body Hermes " +
            "shares with dozens of unrelated API calls."),
        entry(47589, "Returns null from the collectible profile frame " +
            "component, so no profile frame is drawn."),
        entry(47590, "Returns null from the collectible profile frame " +
            "component, so no profile frame is drawn."),
        entry(47650, "Returns an empty array from the profile badges hook, so the " +
            "badge row has no badges to render. The hook already returns an empty array on its " +
            "own empty paths, so its callers handle the shape."),
        entry(47651, "Returns an empty array from the profile badges hook, so the " +
            "badge row has no badges to render. The hook already returns an empty array on its " +
            "own empty paths, so its callers handle the shape."),
        entry(47985, "Stops the guild-affinity fetch that ran on every connection " +
            "open. It was found by logging what the blocklist answered with a 204, and it hits an " +
            "endpoint that is already blocked."),
        entry(49519, "Returns false from isPlayingGameActivity, the predicate in " +
            "modules/activities/utils/isPlayingGameActivity.tsx that decides whether an " +
            "activity is a game being played. Both of its callers ask it before they " +
            "collect anything - the voice-channel games hook behind the voice panel's " +
            "games section, and the hook that gathers the games people are playing in a " +
            "voice channel - so answering false leaves each with an empty list rather " +
            "than with games in it."),
        entry(49524, "Returns null from the guild tag badge component, so no " +
            "badge chip is drawn next to a name."),
        entry(49525, "Returns null from the guild tag badge component, so no " +
            "badge chip is drawn next to a name."),
        entry(49526, "Returns null from the base guild tag chiplet, the component " +
            "the guild tag chips build on, so no chip is drawn through it."),
        entry(49527, "Returns null from the base guild tag chiplet, the component " +
            "the guild tag chips build on, so no chip is drawn through it."),
        entry(49528, "Returns null from the guild tag component, so no guild tag " +
            "chip is drawn next to a name."),
        entry(49812, "Stubs the game-profile shop carousel, so the carousel a game " +
            "profile page renders draws nothing. The carousel sits outside the shop screen, " +
            "which is why stubbing the screen did not remove it."),
        entry(49813, "Stubs the game-profile shop carousel, so the carousel a game " +
            "profile page renders draws nothing. The carousel sits outside the shop screen, " +
            "which is why stubbing the screen did not remove it."),
        entry(49826, "Stops impression tracking: this path builds its own trackMaker " +
            "and posts to /science without going through the central emitter the other stubs " +
            "cover."),
        entry(49928, "Stops the storefront price fetch for an application."),
        entry(49929, "Stops the storefront price fetch for SKU ids."),
        entry(50009, "Returns null from the animated decoration layer that plays over " +
            "the profile card, so the looping video or composite is never drawn. The profile " +
            "picture decoration is a different module and is untouched."),
        entry(50010, "Returns null from the animated decoration layer that plays over " +
            "the profile card, so the looping video or composite is never drawn. The profile " +
            "picture decoration is a different module and is untouched."),
        entry(50050, "Returns null from the collectible nameplate component, " +
            "so no nameplate is drawn."),
        entry(50051, "Returns null from the collectible nameplate component, " +
            "so no nameplate is drawn."),
        entry(50060, "Returns null where SlayerStorefrontItemCard used to draw, so that surface never appears."),
        entry(50061, "Returns null where SlayerStorefrontItemCard used to draw, so that surface never appears."),
        entry(50320, "Stops the fetch of collections with their products."),
        entry(50409, "Rebuilds the You screen's floating navigation so the Quests button " +
            "is never built. The button is one element of a list rather than a function that can " +
            "return false, so the body is edited instead of stubbed."),
        entry(50778, "Returns a falsy value for the flag that gates quest items in the " +
            "activity panel."),
        entry(50802, "Stops the refresh of current quests that the UI drives on a " +
            "one-second timer while a quest is active."),
        entry(50803, "Stops the quest heartbeat being sent to the server."),
        entry(50811, "Stops the refresh of claimed quests."),
        entry(50812, "Stops the fetch of the quest waiting to be delivered."),
        entry(50813, "Stops the fetch of the earned quest waiting to be delivered."),
        entry(51395, "Does nothing, so the action handleShowUpsellAlert used to perform no longer happens."),
        entry(51397, "Answers false, so whatever maybeOpenPremiumUpsellActionSheet gates stays off."),
        entry(51398, "No-ops the action-sheet opener every upsell bottom sheet is " +
            "launched through, across its nine call sites."),
        entry(51403, "Returns null where PremiumUpsellHero used to draw, so that surface never appears."),
        entry(51404, "Returns null where PremiumUpsellHero used to draw, so that surface never appears."),
        entry(51405, "Returns null where PremiumUpsellActionSheet used to draw, so that surface never appears."),
        entry(51406, "Returns null where PremiumUpsellActionSheet used to draw, so that surface never appears."),
        entry(51455, "Returns null where ScheduledMessageNitroUpsellBar used to draw, so that surface never appears."),
        entry(51456, "Returns null where ScheduledMessageNitroUpsellBar used to draw, so that surface never appears."),
        entry(51896, "Returns null where SuperReactionCoachmarkActionSheet used to draw, so that " +
            "surface never appears."),
        entry(51897, "Returns null where SuperReactionCoachmarkActionSheet used to draw, so that " +
            "surface never appears."),
        entry(51898, "No-ops the modal opener every premium upsell modal is " +
            "launched through, across its sixteen call sites."),
        entry(51973, "Returns null where PremiumUpsellTooltipActionSheet used to draw, so that surface never appears."),
        entry(51974, "Returns null where PremiumUpsellTooltipActionSheet used to draw, so that surface never appears."),
        entry(52095, "Does nothing, so the action handleShowEmojiUpsellAlert used to perform no longer happens."),
        entry(52096, "Returns null where RoleSubscriptionEmojiUpsellAlert used to draw, so that surface never appears."),
        entry(52097, "Returns null where RoleSubscriptionEmojiUpsellAlert used to draw, so that surface never appears."),
        entry(52106, "Returns null where GlobalEmojiUpsell used to draw, so that surface never appears."),
        entry(52107, "Returns null where GlobalEmojiUpsell used to draw, so that surface never appears."),
        entry(52108, "Returns null where AnimatedEmojiUpsell used to draw, so that surface never appears."),
        entry(52109, "Returns null where AnimatedEmojiUpsell used to draw, so that surface never appears."),
        entry(52110, "Returns null where PremiumGuildIdentityUpsell used to draw, so that surface never appears."),
        entry(52111, "Returns null where PremiumGuildIdentityUpsell used to draw, so that surface never appears."),
        entry(52112, "Returns null where CustomProfilesUpsell used to draw, so that surface never appears."),
        entry(52113, "Returns null where CustomProfilesUpsell used to draw, so that surface never appears."),
        entry(52114, "Returns null where CustomAppIconsUpsell used to draw, so that surface never appears."),
        entry(52115, "Returns null where CustomAppIconsUpsell used to draw, so that surface never appears."),
        entry(52116, "Returns null where GlobalStickerUpsell used to draw, so that surface never appears."),
        entry(52117, "Returns null where GlobalStickerUpsell used to draw, so that surface never appears."),
        entry(52118, "Returns null where LongerMessageUpsell used to draw, so that surface never appears."),
        entry(52119, "Returns null where LongerMessageUpsell used to draw, so that surface never appears."),
        entry(52120, "Returns null where GuildCapUpsell used to draw, so that surface never appears."),
        entry(52121, "Returns null where GuildCapUpsell used to draw, so that surface never appears."),
        entry(52122, "Returns null where UploadUpsell used to draw, so that surface never appears."),
        entry(52123, "Returns null where UploadUpsell used to draw, so that surface never appears."),
        entry(52124, "Returns null where PremiumUpsellAlert used to draw, so that surface never appears."),
        entry(52125, "Returns null where PremiumUpsellAlert used to draw, so that surface never appears."),
        entry(52167, "Returns null where EmojiPickerListPremiumSearchUpsell used to draw, so that surface never appears."),
        entry(52168, "Returns null where EmojiPickerListPremiumSearchUpsell used to draw, so that surface never appears."),
        entry(52170, "Returns null where PremiumExpressionPickerSearchUpsell used to draw, so that surface never appears."),
        entry(52171, "Returns null where PremiumExpressionPickerSearchUpsell used to draw, so that surface never appears."),
        entry(52199, "Returns null where PremiumFeatureUpsellPill used to draw, so that surface never appears."),
        entry(52200, "Returns null where PremiumFeatureUpsellPill used to draw, so that surface never appears."),
        entry(52201, "Returns null where PremiumFeatureUpsell used to draw, so that surface never appears."),
        entry(52202, "Returns null where PremiumFeatureUpsell used to draw, so that surface never appears."),
        entry(53029, "Removes the inline 'Get Nitro' button, the widest-reaching " +
            "single upsell component: eleven consumers, from the Go Live sheet to the sticker " +
            "detail view."),
        entry(53030, "Removes the inline 'Get Nitro' button, the widest-reaching " +
            "single upsell component: eleven consumers, from the Go Live sheet to the sticker " +
            "detail view."),
        entry(53038, "Returns null where StickersPremiumUpsellAlert used to draw, so that surface never appears."),
        entry(53039, "Returns null where StickersPremiumUpsellAlert used to draw, so that surface never appears."),
        entry(53054, "Returns null where StickerPickerPremiumSearchUpsell used to draw, so that surface never appears."),
        entry(53055, "Returns null where StickerPickerPremiumSearchUpsell used to draw, so that surface never appears."),
        entry(53980, "Does nothing, so the action openSocialLayerStorefrontUnsupportedOnMobileAlert used to perform no longer happens."),
        entry(53981, "Does nothing, so the action openSocialLayerStorefrontProductDetailsModal used to perform no longer happens."),
        entry(53982, "Does nothing, so the action closeSocialLayerStorefrontProductDetailsModal used to perform no longer happens."),
        entry(53983, "Does nothing, so the action openSocialLayerStorefrontGiftModal used to perform no longer happens."),
        entry(53984, "Does nothing, so the action closeSocialLayerStorefrontGiftModal used to perform no longer happens."),
        entry(53996, "Stops the storefront configuration fetch."),
        entry(54000, "Stops the storefront SKU lookup for an application."),
        entry(54021, "Returns null where SocialLayerStorefrontProductDetailsModal used to draw, so that surface never appears."),
        entry(54022, "Returns null where SocialLayerStorefrontProductDetailsModal used to draw, so that surface never appears."),
        entry(54023, "Returns null where WrappedSocialLayerStorefrontProductDetailsModal used to draw, so that surface never appears."),
        entry(54024, "Returns null where WrappedSocialLayerStorefrontProductDetailsModal used to draw, so that surface never appears."),
        entry(54080, "Returns null where SocialLayerStorefrontGiftModal used to draw, so that surface never appears."),
        entry(54081, "Returns null where SocialLayerStorefrontGiftModal used to draw, so that surface never appears."),
        entry(54087, "Returns null where SocialLayerStorefrontGiftPurchaseSection used to draw, so that surface never appears."),
        entry(54088, "Returns null where SocialLayerStorefrontGiftPurchaseSection used to draw, so that surface never appears."),
        entry(54209, "Returns false from isGameActivity, the predicate in " +
            "modules/activities/utils/isGameActivity.tsx that every caller tests to ask " +
            "whether an activity is a game being played. The status drawn under a name, " +
            "the activity list on a profile, the label on a private channel and the You " +
            "bar's accessibility label all read it, so none of them finds a game to " +
            "draw."),
        entry(54218, "Returns a falsy value from the one real Spotify gate. Every call " +
            "site tests it in an if, so this removes the remaining Spotify branding: the " +
            "presence, 'Play on Spotify', the Spotify embed in activity cards and the outbound " +
            "Spotify activity."),
        entry(54248, "Returns null where LeaderboardWinnerBadge used to draw, so that surface never appears."),
        entry(54249, "Returns null where LeaderboardWinnerBadge used to draw, so that surface never appears."),
        entry(54394, "Does nothing, so the action openFavoritesGuildLimitUpsell used to perform no longer happens."),
        entry(54395, "Returns null where FavoritesGuildUpsellSheet used to draw, so that surface never appears."),
        entry(54396, "Returns null where FavoritesGuildUpsellSheet used to draw, so that surface never appears."),
        entry(54407, "Returns null where PromoSheet used to draw, so that surface never appears."),
        entry(54408, "Returns null where PromoSheet used to draw, so that surface never appears."),
        entry(55054, "Returns null where OrbsBadgeCoachmarkImg used to draw, so that surface never " +
            "appears."),
        entry(55055, "Returns null where OrbsBadgeCoachmarkImg used to draw, so that surface never " +
            "appears."),
        entry(55058, "Does nothing and returns undefined, so OrbsBadgeCoachmark no longer raises " +
            "its prompt."),
        entry(55061, "Does nothing and returns undefined, so TieredTenureBadgeCoachmark no longer " +
            "raises its prompt."),
        entry(55062, "Does nothing and returns undefined, so TieredTenureBadgeCoachmark no longer " +
            "raises its prompt."),
        entry(55119, "Returns null where BadgeDetailsSheetContent used to draw, so that surface never appears."),
        entry(55120, "Returns null where BadgeDetailsSheetContent used to draw, so that surface never appears."),
        entry(55121, "Returns null where BadgeDetailsPage used to draw, so that surface never appears."),
        entry(55122, "Returns null where BadgeDetailsPage used to draw, so that surface never appears."),
        entry(55123, "Returns null where BadgeDetailsSheet used to draw, so that surface never appears."),
        entry(55124, "Returns null where BadgeDetailsSheet used to draw, so that surface never appears."),
        entry(56370, "Does nothing, so the action openGroupDMNitroCapLimitSheet used to perform no longer happens."),
        entry(56371, "Returns null where GroupDMNitroCapLimitSheet used to draw, so that surface never appears."),
        entry(56372, "Returns null where GroupDMNitroCapLimitSheet used to draw, so that surface never appears."),
        entry(56374, "Does nothing, so the action navigateToNitroHomePage used to perform no longer happens."),
        entry(56379, "Does nothing, so the action showChatGDMUpsellActionSheet used to perform no longer happens."),
        entry(56380, "Returns null where ChatGDMUpsellActionSheet used to draw, so that surface never appears."),
        entry(56381, "Returns null where ChatGDMUpsellActionSheet used to draw, so that surface never appears."),
        entry(56420, "Returns null from the track renderer, so a friend's Spotify " +
            "presence renders nothing. Stubbing the listening check did not cover this, which is " +
            "why other people's songs stayed visible."),
        entry(56439, "Stops the subscription to Spotify player-state notifications, " +
            "which polled api.spotify.com on launch."),
        entry(56444, "Returns false from the check that asks the on-device Spotify app " +
            "whether Discord may talk to it over a local protocol. That path never touches the " +
            "network, so blocking Spotify hosts alone could not reach it; reporting it " +
            "unregistered leaves nothing to broadcast."),
        entry(59727, "Does nothing, so the action openGroupDMNitroCapInfoActionSheet used to perform no longer happens."),
        entry(59728, "Returns null where GroupDMNitroCapInfoActionSheet used to draw, so that surface never appears."),
        entry(59729, "Returns null where GroupDMNitroCapInfoActionSheet used to draw, so that surface never appears."),
        entry(62030, "Does nothing, so the action showNitroLockedToast used to perform no longer happens."),
        entry(62137, "351.2.5 no longer has the gift purchase button at this id: it is " +
            "the uncompiled variant of the useTypingUserIdsForDisplay hook in " +
            "modules/chat/native/TypingIndicator.tsx, and the React Compiler experiment defaults " +
            "off, so this is the variant the app runs. The stub returns an empty array, the " +
            "hook's own shape with no typing users, so the chat input's " +
            "hasTypingIndicatorContent read of its length finds 0 instead of throwing. The " +
            "recorded build stubs this id to undefined, which that read turns into a TypeError."),
        entry(62377, "Removes the chat-input button that is a gift button or a thread " +
            "button depending on the conversation."),
        entry(62378, "Removes the chat-input button that is a gift button or a thread " +
            "button depending on the conversation."),
        entry(62380, "Removes the gift button in the chat input bar, which sat next to the " +
            "message box."),
        entry(62381, "Removes the gift button in the chat input bar, which sat next to the " +
            "message box."),
        entry(62383, "Returns null where PremiumAnimatedGiftButton used to draw, so that surface " +
            "never appears."),
        entry(62384, "Returns null where PremiumAnimatedGiftButton used to draw, so that surface " +
            "never appears."),
        entry(63103, "Returns null where EmojiPremiumUpsell used to draw, so that surface never appears."),
        entry(63104, "Returns null where EmojiPremiumUpsell used to draw, so that surface never appears."),
        entry(63117, "Returns null where ChannelAutocompleteEmojiUpsell used to draw, so that surface never appears."),
        entry(63118, "Returns null where ChannelAutocompleteEmojiUpsell used to draw, so that surface never appears."),
        entry(63804, "Returns null where GuildPowerupsBoostButton used to draw, so that surface " +
            "never appears."),
        entry(63805, "Returns null where GuildPowerupsBoostButton used to draw, so that surface " +
            "never appears."),
        entry(64027, "Does nothing, so the action dismissUpsellCTA used to perform no longer happens."),
        entry(64367, "Returns null where GuildDirectoryNicknameUpsell used to draw, so that surface never appears."),
        entry(64368, "Returns null where GuildDirectoryNicknameUpsell used to draw, so that surface never appears."),
        entry(64369, "Returns null where GuildDirectoryNicknameUpsellModal used to draw, so that surface never appears."),
        entry(64370, "Returns null where GuildDirectoryNicknameUpsellModal used to draw, so that surface never appears."),
        entry(64632, "351.2.5 no longer has the profile edit screen at this id: it is the " +
            "compiled variant of the guild notification action sheet in " +
            "modules/notifications/settings/native/" +
            "NotificationSettingsMessageUnreadGuildActionSheet.tsx. The app selects that " +
            "variant only when the React Compiler experiment is enabled, and this build leaves " +
            "the experiment off by default, so the stub changes nothing for a stock client."),
        entry(65015, "Returns null where SocialLayerStorefrontProductSelfPurchaseSuccessModal used to draw, so that surface never appears."),
        entry(65016, "Returns null where SocialLayerStorefrontProductSelfPurchaseSuccessModal used to draw, so that surface never appears."),
        entry(65017, "Returns null where SocialLayerStorefrontProductGiftPurchaseSuccessModal used to draw, so that surface never appears."),
        entry(65018, "Returns null where SocialLayerStorefrontProductGiftPurchaseSuccessModal used to draw, so that surface never appears."),
        entry(65090, "Returns null where SocialLayerStorefrontWishlistItemCard used to draw, so that surface never appears."),
        entry(65091, "Returns null where SocialLayerStorefrontWishlistItemCard used to draw, so that surface never appears."),
        entry(65408, "Returns null where GroupDMNitroCapCoachmark used to draw, so that surface never appears."),
        entry(65409, "Returns null where GroupDMNitroCapCoachmark used to draw, so that surface never appears."),
        entry(65459, "Returns null where NitroLimitUpsellBar used to draw, so that surface never appears."),
        entry(65460, "Returns null where NitroLimitUpsellBar used to draw, so that surface never appears."),
        entry(65472, "Does nothing, so the action openScheduledMessagesLimitUpsell used to perform no longer happens."),
        entry(65769, "Removes the modal that awards orbs for a completed quest."),
        entry(65770, "Removes the modal that awards orbs for a completed quest."),
        entry(65803, "Removes the button that opens the collectibles shop."),
        entry(65804, "Removes the button that opens the collectibles shop."),
        entry(65805, "Removes the coachmark that points at the mobile shop button."),
        entry(65806, "Removes the coachmark that points at the mobile shop button."),
        entry(65828, "Returns null where GuildProfileCreateTagUpsell used to draw, so that surface never appears."),
        entry(65829, "Returns null where GuildProfileCreateTagUpsell used to draw, so that surface never appears."),
        entry(65889, "The same orb gate under its plain name. Hermes deduplicated the two " +
            "exports into one body, so patching either one turns orbs off; the alias set is " +
            "verified on every run."),
        entry(65890, "Returns {enabled: false} from the gate every orb surface reads, which " +
            "removes orbs at the source rather than hiding each screen that shows them. Hermes " +
            "shares this body with isVirtualCurrencyEnabled, so that name is disabled with it."),
        entry(66010, "Stubs the Shop This Look action sheet, so it opens empty if its " +
            "opener still runs. The opener is left as it is because its body is shared with an " +
            "unrelated display-name feature."),
        entry(66011, "Stubs the Shop This Look action sheet, so it opens empty if its " +
            "opener still runs. The opener is left as it is because its body is shared with an " +
            "unrelated display-name feature."),
        entry(66027, "Stubs the Shop This Look marketing coachmark, so the prompt that " +
            "points at the feature is not built. It lives outside the shop screen, so stubbing " +
            "the screen did not remove it."),
        entry(66028, "Stubs the Shop This Look marketing coachmark, so the prompt that " +
            "points at the feature is not built. It lives outside the shop screen, so stubbing " +
            "the screen did not remove it."),
        entry(66259, "Rebuilds the tab list the React Compiler copy of the other-profile " +
            "content component builds, so it builds Main and Activity only, with no Board and no " +
            "Wishlist tab. The app runs that copy when the React Compiler experiment is on; the " +
            "leaving it unpatched lets both tabs appear when the experiment is on."),
        entry(66285, "351.2.5 no longer has the quest orb shop carousel at this id: it is " +
            "the compiled variant of the badge row in " +
            "modules/badges/native/BadgeDirectoryNuxCoachmark.tsx, which renders the game-time, " +
            "streaming and game-diversity tier badges. The app selects that variant only when " +
            "the React Compiler experiment is enabled, and this build leaves the experiment off " +
            "by default, so the stub changes nothing for a stock client."),
        entry(66289, "Returns null and never raises its coachmark, so BadgeDirectoryNuxCoachmark shows nothing."),
        entry(66290, "Returns null and never raises its coachmark, so BadgeDirectoryNuxCoachmark shows nothing."),
        entry(66459, "Forces the wishlist tab index to -1, so the profile's segmented " +
            "control has no Wishlist tab to select."),
        entry(66502, "Returns null from the wishlist grid a profile renders, so the " +
            "grid of wishlisted items shown as a profile section is not drawn. This is a " +
            "different surface from the profile's Wishlist tab."),
        entry(66507, "Returns null from the wishlist suggestions grid a profile " +
            "renders, so the suggested-items grid is not drawn. The component already returns " +
            "null when its own mobile gate is off, so callers handle the shape."),
        entry(66508, "Returns null from the wishlist suggestions grid a profile " +
            "renders, so the suggested-items grid is not drawn. The component already returns " +
            "null when its own mobile gate is off, so callers handle the shape."),
        entry(66519, "Removes the add-to-wishlist button in the grid layout."),
        entry(66520, "Removes the add-to-wishlist button in the grid layout."),
        entry(66524, "Removes the add-to-wishlist button on item cards."),
        entry(66599, "Returns null where FractionalNitroPreview used to draw, so that surface never appears."),
        entry(66600, "Returns null where FractionalNitroPreview used to draw, so that surface never appears."),
        entry(66617, "Returns null where ExpressiveNitroUpsell used to draw, so that surface never appears."),
        entry(66618, "Removes the orb price tag shown on items that can be bought with " +
            "orbs."),
        entry(66619, "Removes the orb price tag shown on items that can be bought with " +
            "orbs."),
        entry(66622, "Returns null where NitroUpsell used to draw, so that surface never appears."),
        entry(66623, "Returns null where NitroUpsell used to draw, so that surface never appears."),
        entry(66629, "Returns null where ShopNitroUpsellPromoSheet used to draw, so that surface never appears."),
        entry(66630, "Returns null where ShopNitroUpsellPromoSheet used to draw, so that surface never appears."),
        entry(66645, "Removes the standalone gift button component, one of the separate " +
            "components stubbing the inline Nitro button did not cover."),
        entry(66646, "Removes the standalone gift button component, one of the separate " +
            "components stubbing the inline Nitro button did not cover."),
        entry(66686, "Returns null where NitroAcquiredHeader used to draw, so that surface never appears."),
        entry(66687, "Returns null where NitroAcquiredHeader used to draw, so that surface never appears."),
        entry(66688, "Returns null where FractionalNitroCollectedActionSheet used to draw, so that surface never appears."),
        entry(66689, "Returns null where FractionalNitroCollectedActionSheet used to draw, so that surface never appears."),
        entry(66694, "Returns null where UnlockWithNitroButton used to draw, so that surface never appears."),
        entry(66695, "Returns null where UnlockWithNitroButton used to draw, so that surface never appears."),
        entry(67033, "Returns null where NitroOrbsDeliveredModal used to draw, so that surface never appears."),
        entry(67034, "Returns null where NitroOrbsDeliveredModal used to draw, so that surface never appears."),
        entry(67056, "Returns null where PremiumPlanWhatYouLoseActionSheet used to draw, so that " +
            "surface never appears."),
        entry(67057, "Returns null where PremiumPlanWhatYouLoseActionSheet used to draw, so that " +
            "surface never appears."),
        entry(67106, "Returns null where NitroHomeHeader used to draw, so that surface never appears."),
        entry(67107, "Returns null where NitroHomeHeader used to draw, so that surface never appears."),
        entry(67112, "Returns null where PremiumNitroHome used to draw, so that surface never " +
            "appears."),
        entry(67113, "Returns null where PremiumNitroHome used to draw, so that surface never " +
            "appears."),
        entry(67218, "Returns null where PremiumMarketingPage used to draw, so that surface never " +
            "appears."),
        entry(67219, "Returns null where PremiumMarketingPage used to draw, so that surface never " +
            "appears."),
        entry(67311, "Returns null where PremiumMarketingFloatingSubscribeButton used to draw, so " +
            "that surface never appears."),
        entry(67312, "Returns null where PremiumMarketingFloatingSubscribeButton used to draw, so " +
            "that surface never appears."),
        entry(67356, "Returns null where PremiumGroupEducationActionSheet used to draw, so that " +
            "surface never appears."),
        entry(67357, "Returns null where PremiumGroupEducationActionSheet used to draw, so that " +
            "surface never appears."),
        entry(67361, "Returns null where NitroCreditEducationActionSheet used to draw, so that surface never appears."),
        entry(67362, "Returns null where NitroCreditEducationActionSheet used to draw, so that surface never appears."),
        entry(67370, "Returns null where GuildBoostingUpsell used to draw, so that surface never appears."),
        entry(67371, "Returns null where GuildBoostingUpsell used to draw, so that surface never appears."),
        entry(67381, "Returns null where PremiumSubscriptionPricingUpsell used to draw, so that surface never appears."),
        entry(67382, "Returns null where PremiumSubscriptionPricingUpsell used to draw, so that surface never appears."),
        entry(67388, "Returns null where PremiumSubscriptionUpsell used to draw, so that surface never appears."),
        entry(67389, "Returns null where PremiumSubscriptionUpsell used to draw, so that surface never appears."),
        entry(67393, "Returns null where BoostPurchaseNitroBanner used to draw, so that surface never appears."),
        entry(67394, "Returns null where BoostPurchaseNitroBanner used to draw, so that surface never appears."),
        entry(67427, "Returns null where PremiumPlanSelectYearlyUpsellModal used to draw, so that surface never appears."),
        entry(67428, "Returns null where PremiumPlanSelectYearlyUpsellModal used to draw, so that surface never appears."),
        entry(67435, "Returns null where OutboundPromotionCard used to draw, so that surface never " +
            "appears."),
        entry(67438, "Returns null where OutboundPromotionClaimAlert used to draw, so that surface " +
            "never appears."),
        entry(67439, "Returns null where OutboundPromotionClaimAlert used to draw, so that surface " +
            "never appears."),
        entry(67455, "Removes the gift purchase button, so a gift cannot be bought from " +
            "the UI."),
        entry(67456, "Removes the gift purchase button, so a gift cannot be bought from " +
            "the UI."),
        entry(67750, "Stops the gateway READY payload being logged: the " +
            "payload carries the whole guild list, and it is recorded being stringified around " +
            "fourteen times on one connect in a hundred."),
        entry(67751, "Stops the connection-path lookup used to label the " +
            "gateway analytics event."),
        entry(67752, "Stops the READY payload being measured for its analytics " +
            "byte size."),
        entry(67753, "Stops the gateway-connected analytics event."),
        entry(68748, "Returns null where ContactSyncUpsellCTA used to draw, so that surface never appears."),
        entry(68749, "Returns null where ContactSyncUpsellCTA used to draw, so that surface never appears."),
        entry(70014, "Stops the analytics action handler, which built an event with " +
            "its key, properties, fingerprint and timestamp for every tracked action."),
        entry(70835, "Stubs the Edit User Profile entry in settings."),
        entry(70836, "Stubs the Edit User Profile entry in settings."),
        entry(70847, "Returns null where UserProfileUpsellButton used to draw, so that surface never appears."),
        entry(70848, "Returns null where UserProfileUpsellButton used to draw, so that surface never appears."),
        entry(70922, "Does nothing, so openCustomizeBadgesSheet never opens the customization sheet."),
        entry(70923, "Returns null where BadgeVisibilityMenu used to draw, so that surface never appears."),
        entry(70924, "Returns null where BadgeVisibilityMenu used to draw, so that surface never appears."),
        entry(70931, "Returns null where CustomizeBadgesSheet used to draw, so that surface never appears."),
        entry(70933, "Returns null where UserProfileUpsellCard used to draw, so that surface never appears."),
        entry(70934, "Returns null where UserProfileUpsellCard used to draw, so that surface never appears."),
        entry(71058, "Returns null where UserProfilePremiumTryItOutUpsell used to draw, so that surface never appears."),
        entry(71059, "Returns null where UserProfilePremiumTryItOutUpsell used to draw, so that surface never appears."),
        entry(71063, "Returns null where PreviewNitroCard used to draw, so that surface never appears."),
        entry(71064, "Returns null where PreviewNitroCard used to draw, so that surface never appears."),
        entry(71065, "Returns null where GetNitroCard used to draw, so that surface never appears."),
        entry(71066, "Returns null where GetNitroCard used to draw, so that surface never appears."),
        entry(71067, "Returns null where UserProfilePremiumUpsellCard used to draw, so that surface never appears."),
        entry(71068, "Returns null where UserProfilePremiumUpsellCard used to draw, so that surface never appears."),
        entry(71089, "Returns null where UserProfileFloatingUpsell used to draw, so that surface never appears."),
        entry(71090, "Returns null where UserProfileFloatingUpsell used to draw, so that surface never appears."),
        entry(71100, "Returns null where PasswordlessUpsell used to draw, so that surface never appears."),
        entry(71101, "Returns null where PasswordlessUpsell used to draw, so that surface never appears."),
        entry(71743, "Returns null where ProfileToActivityUpsellActionSheet used to draw, so that surface never appears."),
        entry(71744, "Returns null where ProfileToActivityUpsellActionSheet used to draw, so that surface never appears."),
        entry(71749, "Returns null where BaseUpsellActionSheet used to draw, so that surface never appears."),
        entry(71750, "Returns null where BaseUpsellActionSheet used to draw, so that surface never appears."),
        entry(72086, "Returns null where ConnectionsEmptyStateUpsell used to draw, so that surface never appears."),
        entry(72087, "Returns null where ConnectionsEmptyStateUpsell used to draw, so that surface never appears."),
        entry(72106, "Returns null where XboxTwoWayLinkUpsell used to draw, so that surface never appears."),
        entry(72107, "Returns null where XboxTwoWayLinkUpsell used to draw, so that surface never appears."),
        entry(72110, "Returns null where OneWayToTwoWayLinkUpsell used to draw, so that surface never appears."),
        entry(72111, "Returns null where OneWayToTwoWayLinkUpsell used to draw, so that surface never appears."),
        entry(72112, "Returns null where PlayStationTwoWayLinkUpsell used to draw, so that surface never appears."),
        entry(72113, "Returns null where PlayStationTwoWayLinkUpsell used to draw, so that surface never appears."),
        entry(72165, "Returns false from the Manage Subscriptions row's visibility " +
            "predicate."),
        entry(72166, "Returns false from the Manage Subscriptions row's visibility " +
            "predicate."),
        entry(72182, "Returns false from the Gift Inventory row's own visibility " +
            "predicate."),
        entry(72185, "Returns false from the Quests settings row's own visibility " +
            "predicate. This is what actually removes the row: the settings harness hides any " +
            "entry whose predicate returns exactly false."),
        entry(72190, "Stubs the Quest Home screen the Quests settings row navigates to."),
        entry(72191, "Stubs the Quest Home screen the Quests settings row navigates to."),
        entry(72218, "Returns null from the Quest Home screen, so a route that still " +
            "navigates there renders nothing. Every mobile entry point is gated separately; " +
            "this covers the screen itself, which a route name can still reach."),
        entry(72219, "Returns null from the Quest Home screen, so a route that still " +
            "navigates there renders nothing. Every mobile entry point is gated separately; " +
            "this covers the screen itself, which a route name can still reach."),
        entry(72444, "Returns false from the mobile quest-dock hook, so the dock is not " +
            "shown. The broad quest-eligibility check is the other lever, but its body is shared " +
            "with six unrelated capability checks and cannot be patched."),
        entry(73002, "Returns false from the Server Subscriptions row's visibility " +
            "predicate."),
        entry(73187, "Returns false from the Restore Subscription row's visibility " +
            "predicate."),
        entry(73188, "Returns false from the Restore Subscription row's visibility " +
            "predicate."),
        entry(73346, "Returns null where SettingsAppearanceChannelListPreviewNitroUpsellComponent used to draw, so that surface never appears."),
        entry(73347, "Returns null where SettingsAppearanceChannelListPreviewNitroUpsellComponent used to draw, so that surface never appears."),
        entry(73348, "Returns null where SettingsAppearanceChannelListPreviewNitroUpsell used to draw, so that surface never appears."),
        entry(73349, "Returns null where SettingsAppearanceChannelListPreviewNitroUpsell used to draw, so that surface never appears."),
        entry(73663, "Returns null where VideoUploadQualityNitroUpsell used to draw, so that surface never appears."),
        entry(73664, "Returns null where VideoUploadQualityNitroUpsell used to draw, so that surface never appears."),
        entry(74347, "Does nothing, so the action openPasskeyUpsell used to perform no longer happens."),
        entry(74348, "Does nothing, so the action openPasskeyUpsellPromoSheet used to perform no longer happens."),
        entry(74349, "Does nothing, so the action closePasskeyUpsellPromoSheet used to perform no longer happens."),
        entry(74350, "Returns null where PasswordlessUpsellPromoSheet used to draw, so that surface never appears."),
        entry(74351, "Returns null where PasswordlessUpsellPromoSheet used to draw, so that surface never appears."),
        entry(74388, "Returns null where PremiumTrialOfferActionSheetContent used to draw, so that " +
            "surface never appears."),
        entry(74389, "Returns null where PremiumTrialOfferActionSheetContent used to draw, so that " +
            "surface never appears."),
        entry(74414, "Removes the You-tab orb balance widget's menu, one of the screens the " +
            "orb gate sits in front of."),
        entry(74419, "Removes the wrapper component that builds the orb balance widget."),
        entry(74420, "Removes the wrapper component that builds the orb balance widget."),
        entry(74463, "Stops the survey override from taking effect, so a server-side " +
            "override cannot swap the app-rating survey the client is showing."),
        entry(74464, "Stops the survey-dismiss action, which both updated the store and " +
            "emitted its APP_NOTICE analytics event."),
        entry(74465, "Stops the survey-seen report, which told Discord the survey had " +
            "been shown and emitted analytics for it."),
        entry(74872, "Returns null where UserProfileTryItOutGetPremiumUpsell used to draw, so that surface never appears."),
        entry(74873, "Returns null where UserProfileTryItOutGetPremiumUpsell used to draw, so that surface never appears."),
        entry(74879, "Stubs the collectibles shop screen component the Settings route " +
            "renders, so the route draws nothing. The shop also has a deep-link entry the " +
            "route edit cannot close, which is why the screen component is stubbed."),
        entry(74880, "Stubs the collectibles shop screen component the Settings route " +
            "renders, so the route draws nothing. The shop also has a deep-link entry the " +
            "route edit cannot close, which is why the screen component is stubbed."),
        entry(74884, "Stubs the shop screen's internal component, leaving the screen with " +
            "nothing to render."),
        entry(74885, "Stubs the shop screen's internal component, leaving the screen with " +
            "nothing to render."),
        entry(74888, "Stubs the collectibles shop screen itself, so there is no shop tab to " +
            "browse."),
        entry(74889, "Stubs the collectibles shop screen itself, so there is no shop tab to " +
            "browse."),
        entry(74915, "Returns null where ShopNitroUpsellBanner used to draw, so that surface never appears."),
        entry(75038, "Returns false from the usePredicate closure the Quests toggles " +
            "in Data & Privacy carry, so those settings rows are hidden. The two toggles' " +
            "predicates are byte-identical and Hermes stores them once, so either id changes " +
            "both."),
        entry(75050, "Returns false from the usePredicate closure the Quests toggles " +
            "in Data & Privacy carry, so those settings rows are hidden. The two toggles' " +
            "predicates are byte-identical and Hermes stores them once, so either id changes " +
            "both."),
        entry(75245, "Returns null where ActivityPrivacyUpsellActionSheet used to draw, so that surface never appears."),
        entry(75246, "Returns null where ActivityPrivacyUpsellActionSheet used to draw, so that surface never appears."),
        entry(75926, "Returns null from the voice guild tag component, so no " +
            "guild tag chip is drawn in a voice channel."),
        entry(75927, "Returns null from the voice guild tag component, so no " +
            "guild tag chip is drawn in a voice channel."),
        entry(76063, "Does nothing and returns undefined, so BoostProgressBarCoachmark no longer " +
            "raises its prompt."),
        entry(76064, "Does nothing and returns undefined, so BoostProgressBarCoachmark no longer " +
            "raises its prompt."),
        entry(76067, "Does nothing and returns undefined, so GuildPowerupsBoostToUnlockCoachmark no " +
            "longer raises its prompt."),
        entry(76068, "Does nothing and returns undefined, so GuildPowerupsBoostToUnlockCoachmark no " +
            "longer raises its prompt."),
        entry(76312, "Returns null where GuildRoleSubscriptionsUpsellActionSheet used to draw, so that surface never appears."),
        entry(76313, "Returns null where GuildRoleSubscriptionsUpsellActionSheet used to draw, so that surface never appears."),
        entry(76314, "Returns null where IAPUpsellActionSheet used to draw, so that surface never appears."),
        entry(76315, "Returns null where IAPUpsellActionSheet used to draw, so that surface never appears."),
        entry(76316, "Returns null where GenericUpsellActionSheet used to draw, so that surface never appears."),
        entry(76317, "Returns null where GenericUpsellActionSheet used to draw, so that surface never appears."),
        entry(76318, "Returns null where CreatorMonetizationOnboardingV2UpsellActionSheet used to draw, so that surface never appears."),
        entry(76319, "Returns null where CreatorMonetizationOnboardingV2UpsellActionSheet used to draw, so that surface never appears."),
        entry(76320, "Returns null where GuildRoleSubscriptionTierTemplateUpsellActionSheet used to draw, so that surface never appears."),
        entry(76321, "Returns null where GuildRoleSubscriptionTierTemplateUpsellActionSheet used to draw, so that surface never appears."),
        entry(76325, "Answers false, so whatever useIsGuildEligibleForRoleSubscriptionsUpsell gates stays off."),
        entry(76326, "Answers false, so whatever useIsGuildEligibleForRoleSubscriptionsUpsell gates stays off."),
        entry(76373, "Returns null where GuildUpsellChannelList used to draw, so that surface never appears."),
        entry(76374, "Returns null where GuildUpsellChannelList used to draw, so that surface never appears."),
        entry(76387, "Returns null where OneColumnGuildUpsellList used to draw, so that surface never appears."),
        entry(76388, "Returns null where OneColumnGuildUpsellList used to draw, so that surface never appears."),
        entry(76389, "Returns null where GameCommunityMultiGuildUpsellCard used to draw, so that surface never appears."),
        entry(77958, "Returns null where GroupDMNitroUpsellBanner used to draw, so that surface never appears."),
        entry(77959, "Returns null where GroupDMNitroUpsellBanner used to draw, so that surface never appears."),
        entry(77960, "Returns null where GroupDMNitroCapBanner used to draw, so that surface never appears."),
        entry(77961, "Returns null where GroupDMNitroCapBanner used to draw, so that surface never appears."),
        entry(78118, "Stops the AutoAnalytics navigator hook that emitted an event " +
            "each time a screen was built."),
        entry(78119, "Stops the other half of the AutoAnalytics navigator hook, which " +
            "emitted an event each time the current screen changed."),
        entry(78269, "Rebuilds the profile section tab list the React Compiler copy of " +
            "the You screen content component builds, so it builds Main and Activity only, with " +
            "no Board and no Wishlist tab. The app runs that copy when the React Compiler " +
            "experiment is on; leaving it unpatched lets both tabs appear there too."),
        entry(78279, "Returns null and never raises its coachmark, so BadgeCustomizationProfileCoachmark shows nothing."),
        entry(78280, "Returns null and never raises its coachmark, so BadgeCustomizationProfileCoachmark shows nothing."),
        entry(78283, "Answers false, so whatever useShouldShowExpiringTrialOfferCard gates stays off."),
        entry(78284, "Answers false, so whatever useShouldShowExpiringTrialOfferCard gates stays off."),
        entry(78286, "Returns null where YouExpiringTrialOfferCard used to draw, so that surface never appears."),
        entry(78287, "Returns null where YouExpiringTrialOfferCard used to draw, so that surface never appears."),
        entry(78768, "Returns null where AppIconsCoachmarkActionSheet used to draw, so that surface " +
            "never appears."),
        entry(78769, "Returns null where AppIconsCoachmarkActionSheet used to draw, so that surface " +
            "never appears."),
        entry(78770, "Returns null where GiftingPromotionCoachmarkActionSheet used to draw, so that " +
            "surface never appears."),
        entry(78771, "Returns null where GiftingPromotionCoachmarkActionSheet used to draw, so that " +
            "surface never appears."),
        entry(78789, "Returns null where PremiumMarketingMomentActionSheet used to draw, so that " +
            "surface never appears."),
        entry(78790, "Returns null where PremiumMarketingMomentActionSheet used to draw, so that " +
            "surface never appears."),
        entry(78792, "Returns null where PremiumDiscountOfferActionSheetContent used to draw, so " +
            "that surface never appears."),
        entry(78793, "Returns null where PremiumDiscountOfferActionSheetContent used to draw, so " +
            "that surface never appears."),
        entry(78799, "Returns null where HasBadgeCoachmark used to draw, so that surface never " +
            "appears."),
        entry(78800, "Returns null where HasBadgeCoachmark used to draw, so that surface never " +
            "appears."),
        entry(78801, "Returns null where NewBadgeCoachmark used to draw, so that surface never " +
            "appears."),
        entry(78802, "Returns null where NewBadgeCoachmark used to draw, so that surface never " +
            "appears."),
        entry(78803, "Returns null where GiftingBadgesCoachmarkActionSheet used to draw, so that " +
            "surface never appears."),
        entry(78804, "Returns null where GiftingBadgesCoachmarkActionSheet used to draw, so that " +
            "surface never appears."),
        entry(78833, "Returns null where CollectiblesMobileAnnouncementActionSheet used to draw, so " +
            "that surface never appears."),
        entry(78834, "Returns null where CollectiblesMobileAnnouncementActionSheet used to draw, so " +
            "that surface never appears."),
        entry(78837, "Returns null where NitroFileUploadAnnouncementPromoSheet used to draw, so that surface never appears."),
        entry(78838, "Returns null where NitroFileUploadAnnouncementPromoSheet used to draw, so that surface never appears."),
        entry(78841, "Returns null where NitroFileUploadUpsellPromoSheet used to draw, so that surface never appears."),
        entry(78842, "Returns null where NitroFileUploadUpsellPromoSheet used to draw, so that surface never appears."),
        entry(79190, "Returns null where PremiumSoundboardFeatureUpsell used to draw, so that surface never appears."),
        entry(79191, "Returns null where PremiumSoundboardFeatureUpsell used to draw, so that surface never appears."),
        entry(79418, "Returns null from the voice panel's games section, the React Compiler " +
            "copy of modules/voice_panel/native/prejoin/VoicePanelGamesSection.tsx. The " +
            "section is the container the games list is drawn inside, so stubbing the " +
            "list alone would have left the container on screen; null removes both. The " +
            "app runs that copy only when the React Compiler experiment is on."),
        entry(79419, "Returns null from the voice panel's games section, the copy of " +
            "modules/voice_panel/native/prejoin/VoicePanelGamesSection.tsx this build " +
            "runs. The section is the container the games list is drawn inside, so " +
            "stubbing the list alone would have left the container on screen; null " +
            "removes both."),
        entry(79861, "Stops the per-request tracker, which ran URL matching and " +
            "appended to the telemetry ring for every HTTP request."),
        entry(80062, "Stops _trackStartSpeaking from computing game metadata and " +
            "packet stats when someone starts speaking in a voice call."),
        entry(80063, "Stops _trackStartListening from computing telemetry payloads " +
            "when someone starts listening in a voice call."),
        entry(80164, "Does nothing, so the action openDmSettingsUpsellModal used to perform no longer happens."),
        entry(80165, "Returns null where DmSettingsUpsellActionSheet used to draw, so that surface never appears."),
        entry(80166, "Returns null where DmSettingsUpsellActionSheet used to draw, so that surface never appears."),
        entry(81067, "Returns null where GuildSettingsServerTagUpsellCard used to draw, so that surface never appears."),
        entry(81068, "Returns null where GuildSettingsServerTagUpsellCard used to draw, so that surface never appears."),
        entry(81760, "Returns null where GuildPremiumRoleSubscribeButton used to draw, so that " +
            "surface never appears."),
        entry(81761, "Returns null where GuildPremiumRoleSubscribeButton used to draw, so that " +
            "surface never appears."),
        entry(82134, "Stops the app-state update handler listed with the " +
            "message-cache recorders, so it no longer runs on every app state change."),
        entry(82174, "Stops the patched global WebSocket being installed, " +
            "whose per-message handler parsed every gateway frame a second time and appended it " +
            "to the disabled telemetry ring."),
        entry(82216, "Stops the timer monitor that formatted slow-timer records into the " +
            "log, which only the stubbed analytics emitter read."),
        entry(82282, "Silences one of the logger's debug levels, so those lines no " +
            "longer enter the in-memory JavaScript debug log. Only warn and error are kept, so " +
            "genuine problems still surface."),
        entry(82283, "Silences log(), which is where most of the in-memory debug log was " +
            "coming from."),
        entry(82284, "Silences the logger's verbose level (the 'dangerously' variant), " +
            "keeping it out of the debug log."),
        entry(82285, "Silences verbose, one of the two levels that dominated the " +
            "in-memory debug log."),
        entry(82286, "Silences info, so informational lines no longer enter the debug " +
            "log."),
        entry(82289, "Silences trace, so trace lines no longer enter the debug log."),
        entry(82291, "Silences the file-only logger, so nothing is written to the " +
            "logger's file sink."),
        entry(86319, "Silences the central science-event emitter every feature calls, " +
            "so no event is built or sent and no per-event CPU/memory sample is taken. Its " +
            "callers await it, so the stub still returns a resolved promise."),
        entry(86324, "Stops the single POST behind the /science and /beaker queues, so " +
            "the events the stubs above would have queued are never uploaded either."),
        entry(86445, "Stops the once-a-second process sampler, which made three native " +
            "bridge calls per second for CPU and memory whose only consumers null-guard."),
        entry(92434, "Stops the voice-quality payload builder that assembled and sent " +
            "its statistics every five minutes."),
        entry(92783, "Stops the voice-quality sampler that ran once a second during a " +
            "call."),
        entry(92799, "Stops the system-responsiveness sampler, the second of the " +
            "voice-call samplers."),
        entry(92818, "Stops the video-effect and system-resource sampler that ran on " +
            "every call-statistics callback."),
        entry(112777, "Stops the 60-second session flusher that shipped session " +
            "aggregates."),
        entry(112888, "Stops the metrics aggregator adding samples to its buckets."),
        entry(112891, "Stops the metrics aggregator flushing its buckets on its " +
            "interval."),
        entry(112892, "Stops the metrics aggregator capturing a metrics snapshot."),
        entry(112913, "Stops the browser-side metrics aggregator adding samples to its " +
            "buckets."),
        entry(112914, "Stops the browser-side metrics aggregator flushing them."),
        entry(118763, "Rebuilds the profile activity list without friends' Spotify " +
            "listened-session entries. 351.2.5 has a second copy of the filter in the same module, " +
            "reachable through a different parent, and both are edited so Spotify entries do " +
            "not survive on some surfaces."),
        entry(118888, "Rebuilds other users' profile tab list so it builds Main and " +
            "Activity only, with no Board and no Wishlist tab. It edits the " +
            "isReactCompilerEnabled() == false variant of the profile content component."),
        entry(125330, "Does nothing, so the action navigateToNitroPage used to perform no longer happens."),
        entry(125335, "Returns null where renderNitroUpsellText used to draw, so that surface never " +
            "appears."),
        entry(131612, "Rebuilds the profile section tab list so it builds Main and " +
            "Activity only, with no Board and no Wishlist tab. The tab is one element of a list, " +
            "not a function that can return false, so the body is edited rather than stubbed."),
        entry(136939, "Stops QuestFetchManager installing the recurring interval that " +
            "refetched quests forever, which against already-blocked endpoints was pure timer and " +
            "network churn."),
        entry(147836, "Rebuilds the profile activity list without friends' Spotify " +
            "listened-session entries."),
        entry(152951, "Stops the 60-second voice state interval callback that " +
            "repeatedly dispatches speaking and listening telemetry during calls."),
    )

    /**
     * What a user chooses between, in the order the list shows it.
     *
     * Every function named in [ENTRIES] belongs to exactly one feature, which a test asserts: a
     * function in none could not be switched on, and one in two would be patched by either.
     */
    val FEATURES: List<Feature> = listOf(
        Feature(
            slug = "game_activity",
            group = GAME_GROUP,
            label = "Games being played",
            description = "Removes the game someone is playing from the app: the status line " +
                "under a name, the entry in a profile's activity list, and the games section in " +
                "a voice channel.",
            functionIds = listOf(49519, 54209, 79418, 79419)
        ),
        Feature(
            slug = "nitro_promotions",
            group = SHOP_GROUP,
            label = "Nitro promotions",
            description = "Removes the buttons, sheets and dialogs that advertise Nitro, including the " +
                "inline Get Nitro button in the chat bar.",
            functionIds = listOf(51398, 51898, 53029, 53030)
        ),
        Feature(
            slug = "collectibles_shop",
            group = SHOP_GROUP,
            label = "The collectibles shop",
            description = "Stops the shop screen drawing, so no tab or route opens it.",
            functionIds = listOf(74879, 74880, 74884, 74885, 74888, 74889)
        ),
        Feature(
            slug = "shop_entry_points",
            group = SHOP_GROUP,
            label = "Shop buttons and prompts",
            description = "Removes the button that opens the shop, the prompt that points at it, and " +
                "the settings row that leads there.",
            functionIds = listOf(16829, 65803, 65804, 65805, 65806)
        ),
        Feature(
            slug = "shop_this_look",
            group = SHOP_GROUP,
            label = "Shop This Look",
            description = "Answers no to the experiment that turns the feature on, so the row " +
                "that opens the sheet is never built, and empties the sheet and its prompt as " +
                "well for any path that reaches them.",
            functionIds = listOf(47551, 47552, 66010, 66011, 66027, 66028)
        ),
        Feature(
            slug = "game_profile_shop",
            group = SHOP_GROUP,
            label = "Game profile shop",
            description = "Stops the shop carousel drawing on a game's profile and removes the guild " +
                "sidebar's row for it.",
            functionIds = listOf(40655, 49812, 49813)
        ),
        Feature(
            slug = "guild_shop",
            group = SHOP_GROUP,
            label = "Guild shop",
            description = "Hides the guild shop and its preview by answering false to every check " +
                "that decides whether to show one.",
            functionIds = listOf(40951, 40952, 40953, 40954, 40955)
        ),
        Feature(
            slug = "orbs",
            group = SHOP_GROUP,
            label = "Orbs",
            description = "Turns orbs off at the gate every orb surface reads, and removes the orb " +
                "balance, price tags and reward dialogs.",
            functionIds = listOf(65769, 65770, 65889, 65890, 66618, 66619, 74414, 74419, 74420)
        ),
        Feature(
            slug = "storefront",
            group = SHOP_GROUP,
            label = "Store and product lookups",
            description = "Stops the app fetching store products, prices and collections, and the " +
                "guild data it loaded on every connection.",
            functionIds = listOf(47561, 47985, 49928, 49929, 50320, 53996, 54000)
        ),
        Feature(
            slug = "gift_buttons",
            group = GIFTS_GROUP,
            label = "Gift buttons",
            description = "Removes the gift button from the chat bar and the button that buys a gift.",
            functionIds = listOf(
                62377, 62378, 62380, 62381, 62383, 62384, 66645, 66646, 67455, 67456
            )
        ),
        Feature(
            slug = "billing_rows",
            group = BILLING_GROUP,
            label = "Subscription and billing rows",
            description = "Hides the settings rows for Nitro, managing a plan or subscription, server " +
                "boosts and subscriptions, the gift inventory and restoring a purchase.",
            functionIds = listOf(15959, 15965, 15972, 72165, 72166, 72182, 73002, 73187, 73188)
        ),
        Feature(
            slug = "avatar_decorations",
            group = LOOK_GROUP,
            label = "Avatar decorations",
            description = "Stops avatars carrying decorations, at the one transform every caller " +
                "passes a decoration through.",
            functionIds = listOf(26452)
        ),
        Feature(
            slug = "nameplates",
            group = LOOK_GROUP,
            label = "Nameplates",
            description = "Stops the collectible nameplate drawing next to a name.",
            functionIds = listOf(50050, 50051)
        ),
        Feature(
            slug = "profile_frames",
            group = LOOK_GROUP,
            label = "Profile frames",
            description = "Stops the collectible profile frame drawing around an avatar.",
            functionIds = listOf(47589, 47590)
        ),
        Feature(
            slug = "guild_tags",
            group = LOOK_GROUP,
            label = "Guild tags",
            description = "Stops guild tag chips, badges and voice-channel tags drawing, and answers " +
                "false to the checks that decide whether to show one.",
            functionIds = listOf(
                47318, 47319, 47323, 49524, 49525, 49526, 49527, 49528, 75926, 75927
            )
        ),
        Feature(
            slug = "profile_badges",
            group = PROFILE_GROUP,
            label = "Profile badges",
            description = "Returns no badges from the profile badge hook, so the badge row has nothing " +
                "to draw.",
            functionIds = listOf(47650, 47651)
        ),
        Feature(
            slug = "profile_effects",
            group = PROFILE_GROUP,
            label = "Animated profile effects",
            description = "Stops the animated layer that plays over a profile card.",
            functionIds = listOf(50009, 50010)
        ),
        Feature(
            slug = "edit_profile_row",
            group = PROFILE_GROUP,
            label = "The edit profile row",
            description = "Removes the Edit User Profile entry from settings.",
            functionIds = listOf(70835, 70836)
        ),
        Feature(
            slug = "quests_in_app",
            group = QUESTS_GROUP,
            label = "Quests in the app",
            description = "Removes the Quests screen, its dock, its navigation button, its settings row " +
                "and its toggles in Data & Privacy.",
            functionIds = listOf(
                50409, 50778, 72185, 72190, 72191, 72218, 72219, 72444, 75038, 75050
            )
        ),
        Feature(
            slug = "quest_fetching",
            group = QUESTS_GROUP,
            label = "Quest fetching",
            description = "Stops the app fetching, refreshing and re-polling quests in the background.",
            functionIds = listOf(50802, 50803, 50811, 50812, 50813, 136939)
        ),
        Feature(
            slug = "wishlist_tabs",
            group = WISHLIST_GROUP,
            label = "Wishlist tabs",
            description = "Removes the Wishlist and Board tabs from your profile and from other " +
                "people's, in both copies of each profile component, the copy the app runs by " +
                "default and the React Compiler copy, so the removal holds whichever copy the " +
                "experiment selects.",
            functionIds = listOf(66259, 66459, 78269, 118888, 131612)
        ),
        Feature(
            slug = "wishlist_grids",
            group = WISHLIST_GROUP,
            label = "Wishlist grids and buttons",
            description = "Stops the wishlist and suggestion grids drawing, and removes the buttons " +
                "that add an item to a wishlist.",
            functionIds = listOf(66502, 66507, 66508, 66519, 66520, 66524)
        ),
        Feature(
            slug = "spotify",
            group = SPOTIFY_GROUP,
            label = "Spotify integration",
            description = "Removes Spotify from the app: the presence a friend shows, the track " +
                "renderer, the player subscription and the listened-session entries on a profile.",
            functionIds = listOf(54218, 56420, 56439, 56444, 118763, 147836)
        ),
        Feature(
            slug = "rating_prompts",
            group = SURVEYS_GROUP,
            label = "App rating prompts",
            description = "Stops the app asking you to rate it, and the reports that told Discord the " +
                "prompt was shown or dismissed.",
            functionIds = listOf(44393, 74463, 74464, 74465)
        ),
        Feature(
            slug = "analytics_events",
            group = ANALYTICS_GROUP,
            label = "Analytics events",
            description = "Stops analytics events being built and sent, at the emitters every feature " +
                "calls and at the single request behind them.",
            functionIds = listOf(24473, 24536, 24544, 70014, 86319, 86324)
        ),
        Feature(
            slug = "screen_tracking",
            group = ANALYTICS_GROUP,
            label = "Screen and navigation tracking",
            description = "Stops the event sent each time a screen is built and the per-request " +
                "tracker that matched URLs for it.",
            functionIds = listOf(33733, 78118, 78119, 79861)
        ),
        Feature(
            slug = "voice_tracking",
            group = ANALYTICS_GROUP,
            label = "Voice and call tracking",
            description = "Stops the events sent when someone starts or stops speaking or listening, " +
                "and the interval that reported voice state.",
            functionIds = listOf(80062, 80063, 152951)
        ),
        Feature(
            slug = "impression_tracking",
            group = ANALYTICS_GROUP,
            label = "Impression tracking",
            description = "Stops impressions being recorded, and the flag that exported the telemetry " +
                "ring every half second.",
            functionIds = listOf(26497, 49826)
        ),
        Feature(
            slug = "crash_upload",
            group = CRASH_GROUP,
            label = "Crash reporting",
            description = "Stops the crash reporter starting at all, and with it every crash, " +
                "exception and logged message it would have sent.",
            functionIds = listOf(24399, 24400, 24401, 24405, 24406, 24414)
        ),
        Feature(
            slug = "crash_context",
            group = CRASH_GROUP,
            label = "What a crash report would carry",
            description = "Stops the identity, tags, context, breadcrumbs and feature flags that would " +
                "be attached to a report.",
            functionIds = listOf(21696, 24395, 24396, 24397, 24398, 24402, 24403, 24407)
        ),
        Feature(
            slug = "performance_metrics",
            group = PERFORMANCE_GROUP,
            label = "Performance metrics",
            description = "Stops the app sampling its own performance and uploading the result. " +
                "The once-a-second process sampler is stubbed and its timer slowed to once " +
                "an hour, so it no longer wakes the JavaScript thread every second.",
            functionIds = listOf(
                1379, 20923, 20925, 36002, 36003, 36004, 86445, 112777, 112888, 112891, 112892, 112913,
                112914
            )
        ),
        Feature(
            slug = "call_sampling",
            group = PERFORMANCE_GROUP,
            label = "Call quality sampling",
            description = "Stops the samplers that run during a call to measure voice quality, " +
                "responsiveness and video effects.",
            functionIds = listOf(92434, 92783, 92799, 92818)
        ),
        Feature(
            slug = "session_telemetry",
            group = TELEMETRY_GROUP,
            label = "Session and network statistics",
            description = "Stops the app recording network, session and message-cache statistics, and " +
                "the heartbeat that reported them.",
            functionIds = listOf(
                42212, 42213, 42214, 42216, 42217, 42218, 42245, 42401, 42402, 42403, 82134
            )
        ),
        Feature(
            slug = "gateway_telemetry",
            group = TELEMETRY_GROUP,
            label = "Gateway connection reporting",
            description = "Stops the gateway connection being logged, measured and reported, and the " +
                "patched socket that read every message.",
            functionIds = listOf(67750, 67751, 67752, 67753, 82174)
        ),
        Feature(
            slug = "startup_timing",
            group = STARTUP_GROUP,
            label = "Startup timing",
            description = "Stops the app recording how long startup takes, milestone by milestone.",
            functionIds = listOf(
                19188, 19190, 19192, 19200, 19231, 19232, 19233, 19234, 19235, 19236, 19237, 19240
            )
        ),
        Feature(
            slug = "debug_logging",
            group = LOGGING_GROUP,
            label = "Debug logs",
            description = "Silences the in-app debug log at every level, the aggregator that fed it and " +
                "the file it wrote to.",
            functionIds = listOf(19159, 82216, 82282, 82283, 82284, 82285, 82286, 82289, 82291)
        ),
        Feature(
            slug = "device_fingerprint",
            group = FINGERPRINT_GROUP,
            label = "Device fingerprinting",
            description = "Stops the handler that computed a fingerprint of your device for every " +
                "tracked event.",
            functionIds = listOf(20772)
        ),
        Feature(
            slug = "markup_cache_bound",
            group = STABILITY_GROUP,
            label = "Bound the message cache",
            description = "Caps the cache the app fills while drawing messages. It had no limit " +
                "and its expiry was refreshed on every read, so it grew for as long as the app " +
                "ran, which is memory pressure the app cannot recover.",
            functionIds = listOf(8346)
        ),
        Feature(
            slug = "leftover_stubs",
            group = LEFTOVER_GROUP,
            label = "Stubs by function id",
            description = "Four functions stubbed by id. This release moved what " +
                "those ids hold, so three of the stubs change nothing you can see; the other " +
                "stands in for a typing-indicator hook. Switching this off leaves all four as " +
                "Discord wrote them.",
            functionIds = listOf(15089, 62137, 64632, 66285)
        ),
        Feature(
            slug = "nitro_upsells",
            group = NITRO_UPSELL_GROUP,
            label = "Nitro upsells",
            description = "Every Nitro upsell surface: the banners, promo sheets, action sheets, cards and the lock " +
                "toasts the app raises when something needs Nitro.",
            functionIds = listOf(
                23808, 46252, 46253, 51455, 51456, 51896, 51897, 56370, 56371, 56372, 56374, 59727,
                59728, 59729, 62030, 65408, 65409, 65459, 65460, 66599, 66600, 66617, 66622, 66623,
                66629, 66630, 66686, 66687, 66688, 66689, 66694, 66695, 67033, 67034, 67106, 67107,
                67112, 67113, 67218, 67219, 67311, 67312, 67361, 67362, 67393, 67394, 71063, 71064,
                71065, 71066, 73346, 73347, 73348, 73349, 73663, 73664, 74388, 74389, 74915, 77958,
                77959, 77960, 77961, 78768, 78769, 78770, 78771, 78789, 78790, 78837, 78838, 78841,
                78842, 125330, 125335
            )
        ),
        Feature(
            slug = "premium_upsells",
            group = UPSELL_GROUP,
            label = "Other upsell prompts",
            description = "The remaining upsell sheets, cards, alerts and the buttons that open them.",
            functionIds = listOf(
                32928, 41853, 41854, 41939, 41941, 41949, 41950, 41951, 41998, 41999, 51395, 51397,
                51403, 51404, 51405, 51406, 51973, 51974, 52112, 52113, 52114, 52115, 52118, 52119,
                52122, 52123, 52124, 52125, 52199, 52200, 52201, 52202, 54407, 54408, 56379, 56380,
                56381, 64027, 65472, 67056, 67057, 67356, 67357, 67381, 67382, 67388, 67389, 67427,
                67428, 67435, 67438, 67439, 68748, 68749, 71100, 71101, 71749, 71750, 72086, 72087,
                72106, 72107, 72110, 72111, 72112, 72113, 74347, 74348, 74349, 74350, 74351, 75245,
                75246, 76314, 76315, 76316, 76317, 76318, 76319, 78792, 78793, 78833, 78834, 79190,
                79191, 80164, 80165, 80166
            )
        ),
        Feature(
            slug = "guild_upsells",
            group = UPSELL_GROUP,
            label = "Guild and boost upsells",
            description = "The guild boosting, server tag and role-subscription upsells.",
            functionIds = listOf(
                37159, 37174, 37175, 40558, 41795, 41796, 52110, 52111, 52120, 52121, 54394, 54395,
                54396, 63804, 63805, 64367, 64368, 64369, 64370, 65828, 65829, 67370, 67371, 76063,
                76064, 76067, 76068, 76312, 76313, 76320, 76321, 76325, 76326, 76373, 76374, 76387,
                76388, 76389, 81067, 81068, 81760, 81761
            )
        ),
        Feature(
            slug = "storefront_screens",
            group = STOREFRONT_GROUP,
            label = "Storefront screens",
            description = "The storefront itself: the product page, the gift and purchase flows, the wishlist cards and " +
                "the store item card.",
            functionIds = listOf(
                40682, 50060, 50061, 53980, 53981, 53982, 53983, 53984, 54021, 54022, 54023, 54024,
                54080, 54081, 54087, 54088, 65015, 65016, 65017, 65018, 65090, 65091
            )
        ),
        Feature(
            slug = "trials",
            group = TRIAL_GROUP,
            label = "Free trials",
            description = "The trial offers: the card that counts down an expiring trial, the hooks that decide whether " +
                "to show it, and the reverse-trial prompts.",
            functionIds = listOf(42008, 42009, 78283, 78284, 78286, 78287)
        ),
        Feature(
            slug = "emoji_upsells",
            group = UPSELL_GROUP,
            label = "Emoji and sticker upsells",
            description = "The upsells inside the emoji and sticker pickers.",
            functionIds = listOf(
                52095, 52096, 52097, 52106, 52107, 52108, 52109, 52116, 52117, 52167, 52168, 52170,
                52171, 53038, 53039, 53054, 53055, 63103, 63104, 63117, 63118
            )
        ),
        Feature(
            slug = "leaderboards",
            group = LEADERBOARD_GROUP,
            label = "Leaderboard badges",
            description = "The badge a leaderboard winner carries on their profile.",
            functionIds = listOf(54248, 54249)
        ),
        Feature(
            slug = "profile_upsells",
            group = UPSELL_GROUP,
            label = "Profile upsells",
            description = "The upsells drawn on a profile and beside the activity card.",
            functionIds = listOf(
                70847, 70848, 70933, 70934, 71058, 71059, 71067, 71068, 71089, 71090, 71743, 71744,
                74872, 74873
            )
        ),
        Feature(
            slug = "badge_customization",
            group = PROFILE_GROUP,
            label = "Badge customization",
            description = "The badge prompts and popups: the coachmark that offers to customize " +
                "badges, the customization sheet, the badge-directory coachmark and its animation, " +
                "the visibility menu and the badge details sheet.",
            functionIds = listOf(
                32480, 32481, 32482, 32483, 32484, 32485, 55054, 55055, 55058, 55061, 55062, 55119,
                55120, 55121, 55122, 55123, 55124, 66289, 66290, 70922, 70923, 70924, 70931, 78279,
                78280, 78799, 78800, 78801, 78802, 78803, 78804
            )
        ),
        Feature(
            slug = "lower_camera_resolution",
            group = VIDEO_GROUP,
            label = "Lower video call cost",
            description = "Sends 640×360 at 24 frames per second instead of 720p at 30, about " +
                "four times fewer pixels, and caps the encoder at 1 Mbps. The camera capture " +
                "and the encoder's resolution budget are lowered together, and a full-quality " +
                "channel setting cannot override them. Screen sharing keeps its own 720p path.",
            functionIds = listOf(34425, 34446, 34451)
        ),
    )

    /** The feature headings, in the order the list shows them. */
    val GROUPS: List<String> = FEATURES.map { it.group }.distinct()

    /** The feature with this slug, or null when no feature has it. */
    fun feature(slug: String): Feature? = FEATURES.firstOrNull { it.slug == slug }

    /** The feature a function belongs to, or null when no feature names it. */
    fun featureOf(functionId: Int): Feature? =
        FEATURES.firstOrNull { functionId in it.functionIds }

    /** The item key of one feature, for example `discord_hermes:guild_tags`. */
    fun itemKeyOf(slug: String): String = PatchItem.keyOf(DiscordPatches.HERMES.id, slug)

    /**
     * The items a saved selection wrote before features existed, for a key it may still hold.
     *
     * Selections saved by 3.2.0 and earlier name one function each, as `fn<functionId>`. Those
     * keys name no set, so [PatchItemCatalog.itemsOf] would otherwise keep them verbatim and the
     * user's choice would silently stop meaning anything. Resolving them here turns each into the
     * feature that now covers that function, which is what selecting it used to mean.
     *
     * Returns null for anything else, so a caller can tell "not this shape" from "matched nothing".
     */
    fun itemsOfLegacyKey(key: String): List<PatchItem>? {
        val prefix = "${DiscordPatches.HERMES.id}:fn"
        if (!key.startsWith(prefix)) return null
        val functionId = key.removePrefix(prefix).toIntOrNull() ?: return null
        val feature = featureOf(functionId) ?: return null
        return items().filter { it.identity == feature.slug }
    }

    /** Every feature as a selectable item, in [FEATURES] order. */
    fun items(setId: String = DiscordPatches.HERMES.id): List<PatchItem> =
        FEATURES.map { feature ->
            PatchItem(
                setId = setId,
                identity = feature.slug,
                label = feature.label,
                group = feature.group,
                section = PatchSections.forHermesGroup(feature.group),
                description = feature.description
            )
        }

    /**
     * The subset of [patches] that [selection] switches on, in the table's own order.
     *
     * This is the whole mechanism for applying a subset of the JavaScript patches:
     * [dev.sleepy.app.engine.HermesBundlePatcher.apply] takes the list to apply, so the caller
     * honors a selection by passing a shorter list, and a function that is not selected is not
     * touched. A function no feature names is not selectable, which is why a test asserts there
     * are none.
     */
    fun selectPatches(
        selection: PatchSelection,
        patches: List<FunctionPatch> = DiscordHermesBundlePatch.PATCHES
    ): List<FunctionPatch> = patches.filter { patch ->
        featureOf(patch.functionId)?.let { selection.contains(itemKeyOf(it.slug)) } == true
    }

    private fun entry(functionId: Int, description: String) =
        Entry(functionId = functionId, description = description)
}
