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
    private const val SHOP_GROUP = "Shop and offers"
    private const val NITRO_UPSELL_GROUP = "Nitro prompts"
    private const val UPSELL_GROUP = "Premium prompts"
    private const val STOREFRONT_GROUP = "Store pages"
    private const val TRIAL_GROUP = "Free trials"
    private const val LEADERBOARD_GROUP = "Leaderboards"
    private const val GAME_GROUP = "Game activity"
    private const val GIFTS_GROUP = "Gifts"
    private const val BILLING_GROUP = "Billing"
    private const val LOOK_GROUP = "Profile decorations"
    private const val PROFILE_GROUP = "Profile"
    private const val QUESTS_GROUP = "Quests"
    private const val WISHLIST_GROUP = "Wishlists"
    private const val SPOTIFY_GROUP = "Spotify"
    private const val SURVEYS_GROUP = "Rating prompts"
    private const val ANALYTICS_GROUP = "Usage analytics"
    private const val CRASH_GROUP = "Crash reports"
    private const val PERFORMANCE_GROUP = "Performance sampling"
    private const val VIDEO_GROUP = "Call quality"
    private const val TELEMETRY_GROUP = "Session reporting"
    private const val STARTUP_GROUP = "Startup timing"
    private const val LOGGING_GROUP = "Debug logging"
    private const val FINGERPRINT_GROUP = "Device fingerprint"
    private const val STABILITY_GROUP = "Memory use"
    private const val LEFTOVER_GROUP = "Miscellaneous fixes"
    private const val CHAT_GROUP = "Chat display"
    private const val PRIVACY_GROUP = "Privacy"
    private const val BOOST_GROUP = "Server boosts"

    /** Every patched function, in the order the patch table applies them. */
    val ENTRIES: List<Entry> = listOf(
        entry(1379, "Slows the process-usage poll from once a second to once an hour. " +
            "The same feature stubs the poll callback, so the timer only wakes the " +
            "JavaScript thread; at one second it did that 86,400 times a day for values " +
            "the app no longer collects."),
        entry(8464, "Clamps the entry count of the message-markup cache. It was built with no " +
            "limit and with its expiry clock refreshed on every read, so it grew for as long as " +
            "the app ran, on the path that draws every message."),
        entry(15383, "The bundle has no startup initializer at this id: it is the " +
            "module factory for modules/telemetry_ring/native/channels/NormalTelemetry.tsx. The " +
            "stub returns undefined without running the factory, so the module never assigns " +
            "its default export and an importer sees no NormalTelemetry channel."),
        entry(16275, "Gives the Settings Nitro / Manage Nitro row the always-false " +
            "predicate that hides it. Its backend is already answered with a 204, so the row led " +
            "to a screen that could do nothing."),
        entry(16281, "Gives the Settings Manage Plan row the always-false predicate " +
            "that hides it."),
        entry(16288, "Gives the Settings Server Boost row the always-false " +
            "predicate that hides it."),
        entry(17189, "Gives the Settings CollectiblesShop route the always-false predicate " +
            "that hides its row. This def had none of its own, so the route object itself is " +
            "edited, the same lever that removed the other billing rows."),
        entry(19571, "Stops the log aggregator from feeding the in-app debug panel's log " +
            "buffer."),
        entry(19600, "Stops the time-to-interactive tracker recording a " +
            "milestone's start."),
        entry(19602, "Stops the time-to-interactive tracker recording a " +
            "milestone's end."),
        entry(19604, "Stops the time-to-interactive tracker storing a value for " +
            "a milestone."),
        entry(19612, "Stops the time-to-interactive tracker recording a " +
            "measurement."),
        entry(19643, "Stops the tracing recorder appending its formatted line to " +
            "the in-memory startup log."),
        entry(19644, "Stops startup milestone marks being recorded, one of the " +
            "recorders called at every startup step."),
        entry(19645, "Stops the milestone mark-and-log recorder used by the " +
            "traced operations."),
        entry(19646, "Stops import-detail records being added to the startup " +
            "trace."),
        entry(19647, "Stops delta marks from being added to the startup trace."),
        entry(19648, "Stops timestamped marks from being recorded in the startup " +
            "trace."),
        entry(19649, "Stops detail records from being added to the startup " +
            "trace."),
        entry(19652, "Stops the server-issued trace id from being stored on the " +
            "startup trace."),
        entry(21184, "Stops the fingerprint handler, which computed the old and new " +
            "device fingerprint for every tracked event and reported the transition."),
        entry(21335, "Returns zero from the libdiscore action-telemetry sample rate, " +
            "which the server controls, so the client collects no action payloads whatever " +
            "treatment it is assigned."),
        entry(21337, "Returns false from the libdiscore action-telemetry gate, which " +
            "runs on every redux action and decides whether to collect that action's serialized " +
            "payload, so no payload is collected."),
        entry(22110, "Stops Sentry's own breadcrumb collector, which recorded one for " +
            "every Flux dispatcher action, so the collection stops at the source rather than at " +
            "the sender."),
        entry(24224, "Answers false, so whatever disableActivityHostLeftNitroUpsell gates stays off."),
        entry(24812, "Stops Sentry attaching the signed-in user's identity to error " +
            "reports."),
        entry(24813, "Stops Sentry clearing the user identity it would otherwise have " +
            "attached."),
        entry(24814, "Stops tags being attached to the Sentry scope, so error reports " +
            "carry no user-defined tags."),
        entry(24815, "Stops extra context being attached to the Sentry scope."),
        entry(24816, "Stops JavaScript exceptions being reported to Sentry, so stack " +
            "traces stay on the device."),
        entry(24817, "Stops JavaScript crash reports being sent to Sentry."),
        entry(24818, "Stops manually logged messages being sent to Sentry."),
        entry(24819, "Stops feature-flag names being recorded on the Sentry scope."),
        entry(24820, "Stops Sentry's JavaScript API adding breadcrumbs, which is what " +
            "recorded UI clicks and navigations."),
        entry(24822, "Stops the deliberate crash call from reaching Sentry."),
        entry(24823, "Stops the memory-warning report from reaching Sentry."),
        entry(24824, "Stops the crash-handled marker being recorded on the Sentry scope."),
        entry(24831, "Stops the Sentry JavaScript SDK from initializing at all, so no " +
            "client is bound and none of its automatic integrations (global error handlers, " +
            "breadcrumbs, promise-rejection tracking) are installed."),
        entry(24890, "Silences the [Analytics] debug reporter, so the client stops " +
            "writing its analytics events into the debug log."),
        entry(24953, "Silences the analytics store's own tracking closure, which emits " +
            "events independently of the central emitter."),
        entry(24961, "Silences the [Analytics] network-action reporter, so per-request " +
            "analytics are never emitted."),
        entry(26871, "Returns null from the avatar decoration data " +
            "transform, the choke point every caller that brings a decoration into the client " +
            "passes through. Those callers assign the result without reading it, and the " +
            "function already returns null on its own empty paths, so no decoration reaches " +
            "the client."),
        entry(26916, "Returns false from the analytics experiment flag, so the 500 ms " +
            "telemetry-ring export loop can never start."),
        entry(33515, "Does nothing, so the coachmark binding BadgesCoachmarkBindings never mounts."),
        entry(33516, "Does nothing, so the coachmark binding BadgesCoachmarkBindings never mounts."),
        entry(33517, "Returns null where BadgesCoachmarkRiveInner used to draw, so that surface never appears."),
        entry(33518, "Returns null where BadgesCoachmarkRiveInner used to draw, so that surface never appears."),
        entry(33519, "Returns null where BadgesCoachmarkRiveWithBoundary used to draw, so that surface never appears."),
        entry(33520, "Returns null where BadgesCoachmarkRiveWithBoundary used to draw, so that surface never appears."),
        entry(33453, "Answers false, so whatever ageEligibleForPremiumUpsell gates stays off."),
        entry(35380, "Silences the emitter behind the forum and clickstream trackers, " +
            "so those impressions are never sent."),
        entry(36044, "Does nothing, so a channel set to full quality cannot raise the " +
            "encoder above the lowered camera capture."),
        entry(36065, "Caps the camera capture at 640×360 at 24 frames per second and the " +
            "encoder's maximum bitrate at 1 Mbps, about four times fewer pixels than 720p."),
        entry(36070, "Builds the encoder ladder from a 640×360 pixel budget, so the encoder's " +
            "largest rung matches the camera instead of asking it to upscale."),
        entry(37500, "Stops the monitoring agent's counter, which about twenty-five " +
            "modules call as they do their work."),
        entry(37501, "Stops the monitoring agent's distribution sample."),
        entry(37502, "Stops the monitoring agent flushing what it accumulated to the " +
            "two-minute /metrics/v2 upload."),
        entry(38361, "Returns null where StageBoostingActionSheet used to draw, so that surface " +
            "never appears."),
        entry(38376, "Returns null where PremiumGuildSubscribeModal used to draw, so that surface " +
            "never appears."),
        entry(38377, "Returns null where PremiumGuildSubscribeModal used to draw, so that surface " +
            "never appears."),
        entry(41446, "Does nothing, so the action hideInlineGuildCapUpsell used to perform no longer happens."),
        entry(41543, "Returns false from the storefront capability check, which hides the " +
            "guild sidebar's game-shop row and blocks the redirect into it."),
        entry(41571, "Answers false, so whatever isOnSocialLayerStorefrontPage gates stays off."),
        entry(41835, "Returns false from the Guild Shop visibility hook, one of " +
            "the two visibility gates the creator-monetisation guild shop module holds. The " +
            "hook's callers test for exactly false, which is the shape this stub returns."),
        entry(41836, "Returns false from the Guild Shop visibility hook, one of " +
            "the two visibility gates the creator-monetisation guild shop module holds. The " +
            "hook's callers test for exactly false, which is the shape this stub returns."),
        entry(41837, "Returns false from the plain isGuildShopVisibleInGuild " +
            "predicate exported beside the visibility hook, so a caller that asks whether the " +
            "guild shop is visible is answered no."),
        entry(41838, "Returns false from the Guild Shop preview visibility hook, " +
            "the second of the module's two visibility gates, so the preview surface stays " +
            "hidden. Its callers test for exactly false."),
        entry(41839, "Returns false from the Guild Shop preview visibility hook, " +
            "the second of the module's two visibility gates, so the preview surface stays " +
            "hidden. Its callers test for exactly false."),
        entry(42629, "Returns null where GuildBoostingSubscribeButton used to draw, so that surface " +
            "never appears."),
        entry(42630, "Returns null where GuildBoostingSubscribeButton used to draw, so that surface " +
            "never appears."),
        entry(42687, "Returns null where PremiumModal used to draw, so that surface never appears."),
        entry(42688, "Returns null where PremiumModal used to draw, so that surface never appears."),
        entry(42775, "Does nothing, so the action openPremiumPlanSelectionActionSheet used to " +
            "perform no longer happens."),
        entry(42777, "Returns null where PremiumPlanSelectionActionSheet used to draw, so that " +
            "surface never appears."),
        entry(42785, "Returns null where PremiumPlanSelectionActionSheetCTA used to draw, so that " +
            "surface never appears."),
        entry(42786, "Returns null where PremiumPlanSelectionActionSheetCTA used to draw, so that " +
            "surface never appears."),
        entry(42787, "Returns null where PremiumPlanSelectionActionSheetWithOrderCTX used to draw, " +
            "so that surface never appears."),
        entry(42834, "Returns null where PremiumPlanActionSheetHeader used to draw, so that surface " +
            "never appears."),
        entry(42835, "Returns null where PremiumPlanActionSheetHeader used to draw, so that surface " +
            "never appears."),
        entry(42844, "Answers false, so whatever useShouldShowPremiumTrialUserSettingsAvatarBadge gates stays off."),
        entry(42845, "Answers false, so whatever useShouldShowPremiumTrialUserSettingsAvatarBadge gates stays off."),
        entry(43048, "Stops the network-statistics sampler restarting its " +
            "timers when the app changes state."),
        entry(43049, "Stops the network-statistics recorder writing its " +
            "accumulated events to device storage."),
        entry(43050, "Stops the network-statistics sampler's own track(), " +
            "which queued events locally."),
        entry(43052, "Stops the network-statistics sampler's app-state " +
            "listener, which reports to the sampler whether each app-state change leaves the app " +
            "active."),
        entry(43053, "Stops the network-statistics sampler's per-message " +
            "analytics accumulator, which adds each message's send-analytics duration and queue " +
            "size to its totals."),
        entry(43054, "Stops the network-statistics sampler's per-message " +
            "counter, whose whole body increments the sampler's running count."),
        entry(43081, "Stops the session heartbeat scheduler being initialized, " +
            "so its timer, its periodic API ping and its breadcrumb are never created."),
        entry(43237, "Stops the message-cache statistic that counted channel " +
            "fetches starting."),
        entry(43238, "Stops the message-cache statistic that counted a channel " +
            "being served locally."),
        entry(43239, "Stops the message-cache statistic that counted a channel " +
            "being fetched over the network."),
        entry(45205, "Stops the app-rating survey poll, which fetched the survey and " +
            "emitted analytics for it."),
        entry(47009, "Returns null where DoubleTapNitroAlert used to draw, so that surface never appears."),
        entry(47010, "Returns null where DoubleTapNitroAlert used to draw, so that surface never appears."),
        entry(48075, "Returns false from the guild-tag display hook, so callers " +
            "that decide whether to build a tag row build none. The nulled tag components hide " +
            "the chips themselves; this also stops them being constructed."),
        entry(48076, "Returns false from the guild-tag display hook, so callers " +
            "that decide whether to build a tag row build none. The nulled tag components hide " +
            "the chips themselves; this also stops them being constructed."),
        entry(48080, "Returns false from the plain guild-tag display predicate, " +
            "so a caller that asks whether to show a guild tag is answered no."),
        entry(48308, "Answers no from the Shop This Look experiment gate, the " +
            "check every entry point into the feature reads. Editing the sheet and the " +
            "coachmark alone leaves the opener, whose body is shared with an unrelated " +
            "display-name sheet, so the menu row was still built and still opened an empty " +
            "sheet. Nothing that renders Shop This Look is reachable once this says no."),
        entry(48309, "Answers no from the Shop This Look experiment gate, the " +
            "check every entry point into the feature reads. Editing the sheet and the " +
            "coachmark alone leaves the opener, whose body is shared with an unrelated " +
            "display-name sheet, so the menu row was still built and still opened an empty " +
            "sheet. Nothing that renders Shop This Look is reachable once this says no."),
        entry(48318, "Stops the storefront fetching products by SKU id. The " +
            "implementation is stubbed rather than the exported trampoline, whose body Hermes " +
            "shares with dozens of unrelated API calls."),
        entry(48346, "Returns null from the collectible profile frame " +
            "component, so no profile frame is drawn."),
        entry(48347, "Returns null from the collectible profile frame " +
            "component, so no profile frame is drawn."),
        entry(48407, "Returns an empty array from the profile badges hook, so the " +
            "badge row has no badges to render. The hook already returns an empty array on its " +
            "own empty paths, so its callers handle the shape."),
        entry(48408, "Returns an empty array from the profile badges hook, so the " +
            "badge row has no badges to render. The hook already returns an empty array on its " +
            "own empty paths, so its callers handle the shape."),
        entry(48753, "Stops the guild-affinity fetch that ran on every connection " +
            "open. It was found by logging what the blocklist answered with a 204, and it hits an " +
            "endpoint that is already blocked."),
        entry(50285, "Returns false from isPlayingGameActivity, the predicate in " +
            "modules/activities/utils/isPlayingGameActivity.tsx that decides whether an " +
            "activity is a game being played. Both of its callers ask it before they " +
            "collect anything - the voice-channel games hook behind the voice panel's " +
            "games section, and the hook that gathers the games people are playing in a " +
            "voice channel - so answering false leaves each with an empty list rather " +
            "than with games in it."),
        entry(50290, "Returns null from the guild tag badge component, so no " +
            "badge chip is drawn next to a name."),
        entry(50291, "Returns null from the guild tag badge component, so no " +
            "badge chip is drawn next to a name."),
        entry(50292, "Returns null from the base guild tag chiplet, the component " +
            "the guild tag chips build on, so no chip is drawn through it."),
        entry(50293, "Returns null from the base guild tag chiplet, the component " +
            "the guild tag chips build on, so no chip is drawn through it."),
        entry(50294, "Returns null from the guild tag component, so no guild tag " +
            "chip is drawn next to a name."),
        entry(50625, "Stubs the game-profile shop carousel, so the carousel a game " +
            "profile page renders draws nothing. The carousel sits outside the shop screen, " +
            "which is why stubbing the screen did not remove it."),
        entry(50626, "Stubs the game-profile shop carousel, so the carousel a game " +
            "profile page renders draws nothing. The carousel sits outside the shop screen, " +
            "which is why stubbing the screen did not remove it."),
        entry(50639, "Stops impression tracking: this path builds its own trackMaker " +
            "and posts to /science without going through the central emitter the other stubs " +
            "cover."),
        entry(50741, "Stops the storefront price fetch for an application."),
        entry(50742, "Stops the storefront price fetch for SKU ids."),
        entry(50823, "Returns null from the animated decoration layer that plays over " +
            "the profile card, so the looping video or composite is never drawn. The profile " +
            "picture decoration is a different module and is untouched."),
        entry(50824, "Returns null from the animated decoration layer that plays over " +
            "the profile card, so the looping video or composite is never drawn. The profile " +
            "picture decoration is a different module and is untouched."),
        entry(50864, "Returns null from the collectible nameplate component, " +
            "so no nameplate is drawn."),
        entry(50865, "Returns null from the collectible nameplate component, " +
            "so no nameplate is drawn."),
        entry(50874, "Returns null where SlayerStorefrontItemCard used to draw, so that surface never appears."),
        entry(50875, "Returns null where SlayerStorefrontItemCard used to draw, so that surface never appears."),
        entry(51094, "Stops the fetch of collections with their products."),
        entry(51199, "Rebuilds the You screen's floating navigation so the Quests button " +
            "is never built. The button is one element of a list rather than a function that can " +
            "return false, so the body is edited instead of stubbed."),
        entry(51569, "Returns a falsy value for the flag that gates quest items in the " +
            "activity panel."),
        entry(51593, "Stops the refresh of current quests that the UI drives on a " +
            "one-second timer while a quest is active."),
        entry(51594, "Stops the quest heartbeat being sent to the server."),
        entry(51602, "Stops the refresh of claimed quests."),
        entry(51603, "Stops the fetch of the quest waiting to be delivered."),
        entry(51604, "Stops the fetch of the earned quest waiting to be delivered."),
        entry(52193, "Does nothing, so the action handleShowUpsellAlert used to perform no longer happens."),
        entry(52195, "Answers false, so whatever maybeOpenPremiumUpsellActionSheet gates stays off."),
        entry(52196, "No-ops the action-sheet opener every upsell bottom sheet is " +
            "launched through, across its nine call sites."),
        entry(52201, "Returns null where PremiumUpsellHero used to draw, so that surface never appears."),
        entry(52202, "Returns null where PremiumUpsellHero used to draw, so that surface never appears."),
        entry(52203, "Returns null where PremiumUpsellActionSheet used to draw, so that surface never appears."),
        entry(52204, "Returns null where PremiumUpsellActionSheet used to draw, so that surface never appears."),
        entry(52253, "Returns null where ScheduledMessageNitroUpsellBar used to draw, so that surface never appears."),
        entry(52254, "Returns null where ScheduledMessageNitroUpsellBar used to draw, so that surface never appears."),
        entry(52694, "Returns null where SuperReactionCoachmarkActionSheet used to draw, so that " +
            "surface never appears."),
        entry(52695, "Returns null where SuperReactionCoachmarkActionSheet used to draw, so that " +
            "surface never appears."),
        entry(52696, "No-ops the modal opener every premium upsell modal is " +
            "launched through, across its sixteen call sites."),
        entry(52771, "Returns null where PremiumUpsellTooltipActionSheet used to draw, so that surface never appears."),
        entry(52772, "Returns null where PremiumUpsellTooltipActionSheet used to draw, so that surface never appears."),
        entry(52888, "Does nothing, so the action handleShowEmojiUpsellAlert used to perform no longer happens."),
        entry(52889, "Returns null where RoleSubscriptionEmojiUpsellAlert used to draw, so that surface never appears."),
        entry(52890, "Returns null where RoleSubscriptionEmojiUpsellAlert used to draw, so that surface never appears."),
        entry(52899, "Returns null where GlobalEmojiUpsell used to draw, so that surface never appears."),
        entry(52900, "Returns null where GlobalEmojiUpsell used to draw, so that surface never appears."),
        entry(52901, "Returns null where AnimatedEmojiUpsell used to draw, so that surface never appears."),
        entry(52902, "Returns null where AnimatedEmojiUpsell used to draw, so that surface never appears."),
        entry(52903, "Returns null where PremiumGuildIdentityUpsell used to draw, so that surface never appears."),
        entry(52904, "Returns null where PremiumGuildIdentityUpsell used to draw, so that surface never appears."),
        entry(52905, "Returns null where CustomProfilesUpsell used to draw, so that surface never appears."),
        entry(52906, "Returns null where CustomProfilesUpsell used to draw, so that surface never appears."),
        entry(52907, "Returns null where CustomAppIconsUpsell used to draw, so that surface never appears."),
        entry(52908, "Returns null where CustomAppIconsUpsell used to draw, so that surface never appears."),
        entry(52909, "Returns null where GlobalStickerUpsell used to draw, so that surface never appears."),
        entry(52910, "Returns null where GlobalStickerUpsell used to draw, so that surface never appears."),
        entry(52911, "Returns null where LongerMessageUpsell used to draw, so that surface never appears."),
        entry(52912, "Returns null where LongerMessageUpsell used to draw, so that surface never appears."),
        entry(52913, "Returns null where GuildCapUpsell used to draw, so that surface never appears."),
        entry(52914, "Returns null where GuildCapUpsell used to draw, so that surface never appears."),
        entry(52915, "Returns null where UploadUpsell used to draw, so that surface never appears."),
        entry(52916, "Returns null where UploadUpsell used to draw, so that surface never appears."),
        entry(52917, "Returns null where PremiumUpsellAlert used to draw, so that surface never appears."),
        entry(52918, "Returns null where PremiumUpsellAlert used to draw, so that surface never appears."),
        entry(52962, "Returns null where EmojiPickerListPremiumSearchUpsell used to draw, so that surface never appears."),
        entry(52963, "Returns null where EmojiPickerListPremiumSearchUpsell used to draw, so that surface never appears."),
        entry(52965, "Returns null where PremiumExpressionPickerSearchUpsell used to draw, so that surface never appears."),
        entry(52966, "Returns null where PremiumExpressionPickerSearchUpsell used to draw, so that surface never appears."),
        entry(52994, "Returns null where PremiumFeatureUpsellPill used to draw, so that surface never appears."),
        entry(52995, "Returns null where PremiumFeatureUpsellPill used to draw, so that surface never appears."),
        entry(52996, "Returns null where PremiumFeatureUpsell used to draw, so that surface never appears."),
        entry(52997, "Returns null where PremiumFeatureUpsell used to draw, so that surface never appears."),
        entry(53824, "Removes the inline 'Get Nitro' button, the widest-reaching " +
            "single upsell component: eleven consumers, from the Go Live sheet to the sticker " +
            "detail view."),
        entry(53825, "Removes the inline 'Get Nitro' button, the widest-reaching " +
            "single upsell component: eleven consumers, from the Go Live sheet to the sticker " +
            "detail view."),
        entry(53833, "Returns null where StickersPremiumUpsellAlert used to draw, so that surface never appears."),
        entry(53834, "Returns null where StickersPremiumUpsellAlert used to draw, so that surface never appears."),
        entry(53849, "Returns null where StickerPickerPremiumSearchUpsell used to draw, so that surface never appears."),
        entry(53850, "Returns null where StickerPickerPremiumSearchUpsell used to draw, so that surface never appears."),
        entry(54773, "Does nothing, so the action openSocialLayerStorefrontUnsupportedOnMobileAlert used to perform no longer happens."),
        entry(54774, "Does nothing, so the action openSocialLayerStorefrontProductDetailsModal used to perform no longer happens."),
        entry(54775, "Does nothing, so the action closeSocialLayerStorefrontProductDetailsModal used to perform no longer happens."),
        entry(54776, "Does nothing, so the action openSocialLayerStorefrontGiftModal used to perform no longer happens."),
        entry(54777, "Does nothing, so the action closeSocialLayerStorefrontGiftModal used to perform no longer happens."),
        entry(54789, "Stops the storefront configuration fetch."),
        entry(54793, "Stops the storefront SKU lookup for an application."),
        entry(54814, "Returns null where SocialLayerStorefrontProductDetailsModal used to draw, so that surface never appears."),
        entry(54815, "Returns null where SocialLayerStorefrontProductDetailsModal used to draw, so that surface never appears."),
        entry(54816, "Returns null where WrappedSocialLayerStorefrontProductDetailsModal used to draw, so that surface never appears."),
        entry(54817, "Returns null where WrappedSocialLayerStorefrontProductDetailsModal used to draw, so that surface never appears."),
        entry(54873, "Returns null where SocialLayerStorefrontGiftModal used to draw, so that surface never appears."),
        entry(54874, "Returns null where SocialLayerStorefrontGiftModal used to draw, so that surface never appears."),
        entry(54880, "Returns null where SocialLayerStorefrontGiftPurchaseSection used to draw, so that surface never appears."),
        entry(54881, "Returns null where SocialLayerStorefrontGiftPurchaseSection used to draw, so that surface never appears."),
        entry(55002, "Returns false from isGameActivity, the predicate in " +
            "modules/activities/utils/isGameActivity.tsx that every caller tests to ask " +
            "whether an activity is a game being played. The status drawn under a name, " +
            "the activity list on a profile, the label on a private channel and the You " +
            "bar's accessibility label all read it, so none of them finds a game to " +
            "draw."),
        entry(55011, "Returns a falsy value from the one real Spotify gate. Every call " +
            "site tests it in an if, so this removes the remaining Spotify branding: the " +
            "presence, 'Play on Spotify', the Spotify embed in activity cards and the outbound " +
            "Spotify activity."),
        entry(55041, "Returns null where LeaderboardWinnerBadge used to draw, so that surface never appears."),
        entry(55042, "Returns null where LeaderboardWinnerBadge used to draw, so that surface never appears."),
        entry(56011, "Does nothing, so the action openFavoritesGuildLimitUpsell used to perform no longer happens."),
        entry(56012, "Returns null where FavoritesGuildUpsellSheet used to draw, so that surface never appears."),
        entry(56013, "Returns null where FavoritesGuildUpsellSheet used to draw, so that surface never appears."),
        entry(56024, "Returns null where PromoSheet used to draw, so that surface never appears."),
        entry(56025, "Returns null where PromoSheet used to draw, so that surface never appears."),
        entry(56742, "Returns null where OrbsBadgeCoachmarkImg used to draw, so that surface never " +
            "appears."),
        entry(56743, "Returns null where OrbsBadgeCoachmarkImg used to draw, so that surface never " +
            "appears."),
        entry(56746, "Does nothing and returns undefined, so OrbsBadgeCoachmark no longer raises " +
            "its prompt."),
        entry(56749, "Does nothing and returns undefined, so TieredTenureBadgeCoachmark no longer " +
            "raises its prompt."),
        entry(56750, "Does nothing and returns undefined, so TieredTenureBadgeCoachmark no longer " +
            "raises its prompt."),
        entry(56807, "Returns null where BadgeDetailsSheetContent used to draw, so that surface never appears."),
        entry(56808, "Returns null where BadgeDetailsSheetContent used to draw, so that surface never appears."),
        entry(56809, "Returns null where BadgeDetailsPage used to draw, so that surface never appears."),
        entry(56810, "Returns null where BadgeDetailsPage used to draw, so that surface never appears."),
        entry(56811, "Returns null where BadgeDetailsSheet used to draw, so that surface never appears."),
        entry(56812, "Returns null where BadgeDetailsSheet used to draw, so that surface never appears."),
        entry(57365, "Does nothing, so the action openGroupDMNitroCapLimitSheet used to perform no longer happens."),
        entry(57366, "Returns null where GroupDMNitroCapLimitSheet used to draw, so that surface never appears."),
        entry(57367, "Returns null where GroupDMNitroCapLimitSheet used to draw, so that surface never appears."),
        entry(57369, "Does nothing, so the action navigateToNitroHomePage used to perform no longer happens."),
        entry(57374, "Does nothing, so the action showChatGDMUpsellActionSheet used to perform no longer happens."),
        entry(57375, "Returns null where ChatGDMUpsellActionSheet used to draw, so that surface never appears."),
        entry(57376, "Returns null where ChatGDMUpsellActionSheet used to draw, so that surface never appears."),
        entry(57446, "Returns null from the track renderer, so a friend's Spotify " +
            "presence renders nothing. Stubbing the listening check did not cover this, which is " +
            "why other people's songs stayed visible."),
        entry(57465, "Stops the subscription to Spotify player-state notifications, " +
            "which polled api.spotify.com on launch."),
        entry(57470, "Returns false from the check that asks the on-device Spotify app " +
            "whether Discord may talk to it over a local protocol. That path never touches the " +
            "network, so blocking Spotify hosts alone could not reach it; reporting it " +
            "unregistered leaves nothing to broadcast."),
        entry(60457, "Does nothing, so the action openGroupDMNitroCapInfoActionSheet used to perform no longer happens."),
        entry(60458, "Returns null where GroupDMNitroCapInfoActionSheet used to draw, so that surface never appears."),
        entry(60459, "Returns null where GroupDMNitroCapInfoActionSheet used to draw, so that surface never appears."),
        entry(63587, "Does nothing, so the action showNitroLockedToast used to perform no longer happens."),
        entry(63686, "The bundle has no gift purchase button at this id: it is " +
            "the uncompiled variant of the useTypingUserIdsForDisplay hook in " +
            "modules/chat/native/TypingIndicator.tsx, and the React Compiler experiment defaults " +
            "off, so this is the variant the app runs. The stub returns an empty array, the " +
            "hook's own shape with no typing users, so the chat input's " +
            "hasTypingIndicatorContent read of its length finds 0 instead of throwing. The " +
            "recorded build stubs this id to undefined, which that read turns into a TypeError."),
        entry(63926, "Removes the chat-input button that is a gift button or a thread " +
            "button depending on the conversation."),
        entry(63927, "Removes the chat-input button that is a gift button or a thread " +
            "button depending on the conversation."),
        entry(63929, "Removes the gift button in the chat input bar, which sat next to the " +
            "message box."),
        entry(63930, "Removes the gift button in the chat input bar, which sat next to the " +
            "message box."),
        entry(63932, "Returns null where PremiumAnimatedGiftButton used to draw, so that surface " +
            "never appears."),
        entry(63933, "Returns null where PremiumAnimatedGiftButton used to draw, so that surface " +
            "never appears."),
        entry(64595, "Returns null where EmojiPremiumUpsell used to draw, so that surface never appears."),
        entry(64596, "Returns null where EmojiPremiumUpsell used to draw, so that surface never appears."),
        entry(64609, "Returns null where ChannelAutocompleteEmojiUpsell used to draw, so that surface never appears."),
        entry(64610, "Returns null where ChannelAutocompleteEmojiUpsell used to draw, so that surface never appears."),
        entry(65292, "Returns null where GuildPowerupsBoostButton used to draw, so that surface " +
            "never appears."),
        entry(65293, "Returns null where GuildPowerupsBoostButton used to draw, so that surface " +
            "never appears."),
        entry(65516, "Does nothing, so the action dismissUpsellCTA used to perform no longer happens."),
        entry(65856, "Returns null where GuildDirectoryNicknameUpsell used to draw, so that surface never appears."),
        entry(65857, "Returns null where GuildDirectoryNicknameUpsell used to draw, so that surface never appears."),
        entry(65858, "Returns null where GuildDirectoryNicknameUpsellModal used to draw, so that surface never appears."),
        entry(65859, "Returns null where GuildDirectoryNicknameUpsellModal used to draw, so that surface never appears."),
        entry(83889, "The bundle has no profile edit screen at this id: it is the " +
            "compiled variant of the guild notification action sheet in " +
            "modules/notifications/settings/native/" +
            "NotificationSettingsMessageUnreadGuildActionSheet.tsx. The app selects that " +
            "variant only when the React Compiler experiment is enabled, and this build leaves " +
            "the experiment off by default, so the stub changes nothing for a stock client."),
        entry(66476, "Returns null where SocialLayerStorefrontProductSelfPurchaseSuccessModal used to draw, so that surface never appears."),
        entry(66477, "Returns null where SocialLayerStorefrontProductSelfPurchaseSuccessModal used to draw, so that surface never appears."),
        entry(66478, "Returns null where SocialLayerStorefrontProductGiftPurchaseSuccessModal used to draw, so that surface never appears."),
        entry(66479, "Returns null where SocialLayerStorefrontProductGiftPurchaseSuccessModal used to draw, so that surface never appears."),
        entry(66551, "Returns null where SocialLayerStorefrontWishlistItemCard used to draw, so that surface never appears."),
        entry(66552, "Returns null where SocialLayerStorefrontWishlistItemCard used to draw, so that surface never appears."),
        entry(66878, "Returns null where GroupDMNitroCapCoachmark used to draw, so that surface never appears."),
        entry(66879, "Returns null where GroupDMNitroCapCoachmark used to draw, so that surface never appears."),
        entry(66929, "Returns null where NitroLimitUpsellBar used to draw, so that surface never appears."),
        entry(66930, "Returns null where NitroLimitUpsellBar used to draw, so that surface never appears."),
        entry(66942, "Does nothing, so the action openScheduledMessagesLimitUpsell used to perform no longer happens."),
        entry(67227, "Removes the modal that awards orbs for a completed quest."),
        entry(67228, "Removes the modal that awards orbs for a completed quest."),
        entry(67250, "Removes the button that opens the collectibles shop."),
        entry(67251, "Removes the button that opens the collectibles shop."),
        entry(67252, "Removes the coachmark that points at the mobile shop button."),
        entry(67253, "Removes the coachmark that points at the mobile shop button."),
        entry(67275, "Returns null where GuildProfileCreateTagUpsell used to draw, so that surface never appears."),
        entry(67276, "Returns null where GuildProfileCreateTagUpsell used to draw, so that surface never appears."),
        entry(67336, "The same orb gate under its plain name. Hermes deduplicated the two " +
            "exports into one body, so patching either one turns orbs off; the alias set is " +
            "verified on every run."),
        entry(67337, "Returns {enabled: false} from the gate every orb surface reads, which " +
            "removes orbs at the source rather than hiding each screen that shows them. Hermes " +
            "shares this body with isVirtualCurrencyEnabled, so that name is disabled with it."),
        entry(67474, "Stubs the Shop This Look action sheet, so it opens empty if its " +
            "opener still runs. The opener is left as it is because its body is shared with an " +
            "unrelated display-name feature."),
        entry(67475, "Stubs the Shop This Look action sheet, so it opens empty if its " +
            "opener still runs. The opener is left as it is because its body is shared with an " +
            "unrelated display-name feature."),
        entry(67491, "Stubs the Shop This Look marketing coachmark, so the prompt that " +
            "points at the feature is not built. It lives outside the shop screen, so stubbing " +
            "the screen did not remove it."),
        entry(67492, "Stubs the Shop This Look marketing coachmark, so the prompt that " +
            "points at the feature is not built. It lives outside the shop screen, so stubbing " +
            "the screen did not remove it."),
        entry(67723, "Rebuilds the tab list the React Compiler copy of the other-profile " +
            "content component builds, so it builds Main and Activity only, with no Board and no " +
            "Wishlist tab. The app runs that copy when the React Compiler experiment is on; the " +
            "leaving it unpatched lets both tabs appear when the experiment is on."),
        entry(67749, "The bundle has no quest orb shop carousel at this id: it is " +
            "the compiled variant of the badge row in " +
            "modules/badges/native/BadgeDirectoryNuxCoachmark.tsx, which renders the game-time, " +
            "streaming and game-diversity tier badges. The app selects that variant only when " +
            "the React Compiler experiment is enabled, and this build leaves the experiment off " +
            "by default, so the stub changes nothing for a stock client."),
        entry(67753, "Returns null and never raises its coachmark, so BadgeDirectoryNuxCoachmark shows nothing."),
        entry(67754, "Returns null and never raises its coachmark, so BadgeDirectoryNuxCoachmark shows nothing."),
        entry(67983, "Forces the wishlist tab index to -1, so the profile's segmented " +
            "control has no Wishlist tab to select."),
        entry(68020, "Returns null from the wishlist grid a profile renders, so the " +
            "grid of wishlisted items shown as a profile section is not drawn. This is a " +
            "different surface from the profile's Wishlist tab."),
        entry(68025, "Returns null from the wishlist suggestions grid a profile " +
            "renders, so the suggested-items grid is not drawn. The component already returns " +
            "null when its own mobile gate is off, so callers handle the shape."),
        entry(68026, "Returns null from the wishlist suggestions grid a profile " +
            "renders, so the suggested-items grid is not drawn. The component already returns " +
            "null when its own mobile gate is off, so callers handle the shape."),
        entry(68037, "Removes the add-to-wishlist button in the grid layout."),
        entry(68038, "Removes the add-to-wishlist button in the grid layout."),
        entry(68042, "Removes the add-to-wishlist button on item cards."),
        entry(68127, "Returns null where FractionalNitroPreview used to draw, so that surface never appears."),
        entry(68128, "Returns null where FractionalNitroPreview used to draw, so that surface never appears."),
        entry(68145, "Returns null where ExpressiveNitroUpsell used to draw, so that surface never appears."),
        entry(68146, "Removes the orb price tag shown on items that can be bought with " +
            "orbs."),
        entry(68147, "Removes the orb price tag shown on items that can be bought with " +
            "orbs."),
        entry(68150, "Returns null where NitroUpsell used to draw, so that surface never appears."),
        entry(68151, "Returns null where NitroUpsell used to draw, so that surface never appears."),
        entry(68157, "Returns null where ShopNitroUpsellPromoSheet used to draw, so that surface never appears."),
        entry(68158, "Returns null where ShopNitroUpsellPromoSheet used to draw, so that surface never appears."),
        entry(68173, "Removes the standalone gift button component, one of the separate " +
            "components stubbing the inline Nitro button did not cover."),
        entry(68174, "Removes the standalone gift button component, one of the separate " +
            "components stubbing the inline Nitro button did not cover."),
        entry(68214, "Returns null where NitroAcquiredHeader used to draw, so that surface never appears."),
        entry(68215, "Returns null where NitroAcquiredHeader used to draw, so that surface never appears."),
        entry(68216, "Returns null where FractionalNitroCollectedActionSheet used to draw, so that surface never appears."),
        entry(68217, "Returns null where FractionalNitroCollectedActionSheet used to draw, so that surface never appears."),
        entry(68222, "Returns null where UnlockWithNitroButton used to draw, so that surface never appears."),
        entry(68223, "Returns null where UnlockWithNitroButton used to draw, so that surface never appears."),
        entry(68559, "Returns null where NitroOrbsDeliveredModal used to draw, so that surface never appears."),
        entry(68560, "Returns null where NitroOrbsDeliveredModal used to draw, so that surface never appears."),
        entry(68582, "Returns null where PremiumPlanWhatYouLoseActionSheet used to draw, so that " +
            "surface never appears."),
        entry(68583, "Returns null where PremiumPlanWhatYouLoseActionSheet used to draw, so that " +
            "surface never appears."),
        entry(68632, "Returns null where NitroHomeHeader used to draw, so that surface never appears."),
        entry(68633, "Returns null where NitroHomeHeader used to draw, so that surface never appears."),
        entry(68638, "Returns null where PremiumNitroHome used to draw, so that surface never " +
            "appears."),
        entry(68639, "Returns null where PremiumNitroHome used to draw, so that surface never " +
            "appears."),
        entry(68744, "Returns null where PremiumMarketingPage used to draw, so that surface never " +
            "appears."),
        entry(68745, "Returns null where PremiumMarketingPage used to draw, so that surface never " +
            "appears."),
        entry(68837, "Returns null where PremiumMarketingFloatingSubscribeButton used to draw, so " +
            "that surface never appears."),
        entry(68838, "Returns null where PremiumMarketingFloatingSubscribeButton used to draw, so " +
            "that surface never appears."),
        entry(68882, "Returns null where PremiumGroupEducationActionSheet used to draw, so that " +
            "surface never appears."),
        entry(68883, "Returns null where PremiumGroupEducationActionSheet used to draw, so that " +
            "surface never appears."),
        entry(68887, "Returns null where NitroCreditEducationActionSheet used to draw, so that surface never appears."),
        entry(68888, "Returns null where NitroCreditEducationActionSheet used to draw, so that surface never appears."),
        entry(68896, "Returns null where GuildBoostingUpsell used to draw, so that surface never appears."),
        entry(68897, "Returns null where GuildBoostingUpsell used to draw, so that surface never appears."),
        entry(68907, "Returns null where PremiumSubscriptionPricingUpsell used to draw, so that surface never appears."),
        entry(68908, "Returns null where PremiumSubscriptionPricingUpsell used to draw, so that surface never appears."),
        entry(68914, "Returns null where PremiumSubscriptionUpsell used to draw, so that surface never appears."),
        entry(68915, "Returns null where PremiumSubscriptionUpsell used to draw, so that surface never appears."),
        entry(68919, "Returns null where BoostPurchaseNitroBanner used to draw, so that surface never appears."),
        entry(68920, "Returns null where BoostPurchaseNitroBanner used to draw, so that surface never appears."),
        entry(68953, "Returns null where PremiumPlanSelectYearlyUpsellModal used to draw, so that surface never appears."),
        entry(68954, "Returns null where PremiumPlanSelectYearlyUpsellModal used to draw, so that surface never appears."),
        entry(68961, "Returns null where OutboundPromotionCard used to draw, so that surface never " +
            "appears."),
        entry(68964, "Returns null where OutboundPromotionClaimAlert used to draw, so that surface " +
            "never appears."),
        entry(68965, "Returns null where OutboundPromotionClaimAlert used to draw, so that surface " +
            "never appears."),
        entry(68981, "Removes the gift purchase button, so a gift cannot be bought from " +
            "the UI."),
        entry(68982, "Removes the gift purchase button, so a gift cannot be bought from " +
            "the UI."),
        entry(69276, "Stops the gateway READY payload being logged: the " +
            "payload carries the whole guild list, and it is recorded being stringified around " +
            "fourteen times on one connect in a hundred."),
        entry(69277, "Stops the connection-path lookup used to label the " +
            "gateway analytics event."),
        entry(69278, "Stops the READY payload being measured for its analytics " +
            "byte size."),
        entry(69279, "Stops the gateway-connected analytics event."),
        entry(70291, "Returns null where ContactSyncUpsellCTA used to draw, so that surface never appears."),
        entry(70292, "Returns null where ContactSyncUpsellCTA used to draw, so that surface never appears."),
        entry(71544, "Stops the analytics action handler, which built an event with " +
            "its key, properties, fingerprint and timestamp for every tracked action."),
        entry(72374, "Stubs the Edit User Profile entry in settings."),
        entry(72375, "Stubs the Edit User Profile entry in settings."),
        entry(72390, "Returns null where UserProfileUpsellButton used to draw, so that surface never appears."),
        entry(72391, "Returns null where UserProfileUpsellButton used to draw, so that surface never appears."),
        entry(72511, "Does nothing, so openCustomizeBadgesSheet never opens the customization sheet."),
        entry(72512, "Returns null where BadgeVisibilityMenu used to draw, so that surface never appears."),
        entry(72513, "Returns null where BadgeVisibilityMenu used to draw, so that surface never appears."),
        entry(72520, "Returns null where CustomizeBadgesSheet used to draw, so that surface never appears."),
        entry(72522, "Returns null where UserProfileUpsellCard used to draw, so that surface never appears."),
        entry(72523, "Returns null where UserProfileUpsellCard used to draw, so that surface never appears."),
        entry(72675, "Returns null where UserProfilePremiumTryItOutUpsell used to draw, so that surface never appears."),
        entry(72676, "Returns null where UserProfilePremiumTryItOutUpsell used to draw, so that surface never appears."),
        entry(72680, "Returns null where PreviewNitroCard used to draw, so that surface never appears."),
        entry(72681, "Returns null where PreviewNitroCard used to draw, so that surface never appears."),
        entry(72682, "Returns null where GetNitroCard used to draw, so that surface never appears."),
        entry(72683, "Returns null where GetNitroCard used to draw, so that surface never appears."),
        entry(72684, "Returns null where UserProfilePremiumUpsellCard used to draw, so that surface never appears."),
        entry(72685, "Returns null where UserProfilePremiumUpsellCard used to draw, so that surface never appears."),
        entry(72640, "Returns null where UserProfileFloatingUpsell used to draw, so that surface never appears."),
        entry(72641, "Returns null where UserProfileFloatingUpsell used to draw, so that surface never appears."),
        entry(72710, "Returns null where PasswordlessUpsell used to draw, so that surface never appears."),
        entry(72711, "Returns null where PasswordlessUpsell used to draw, so that surface never appears."),
        entry(73353, "Returns null where ProfileToActivityUpsellActionSheet used to draw, so that surface never appears."),
        entry(73354, "Returns null where ProfileToActivityUpsellActionSheet used to draw, so that surface never appears."),
        entry(73359, "Returns null where BaseUpsellActionSheet used to draw, so that surface never appears."),
        entry(73360, "Returns null where BaseUpsellActionSheet used to draw, so that surface never appears."),
        entry(73696, "Returns null where ConnectionsEmptyStateUpsell used to draw, so that surface never appears."),
        entry(73697, "Returns null where ConnectionsEmptyStateUpsell used to draw, so that surface never appears."),
        entry(73716, "Returns null where XboxTwoWayLinkUpsell used to draw, so that surface never appears."),
        entry(73717, "Returns null where XboxTwoWayLinkUpsell used to draw, so that surface never appears."),
        entry(73720, "Returns null where OneWayToTwoWayLinkUpsell used to draw, so that surface never appears."),
        entry(73721, "Returns null where OneWayToTwoWayLinkUpsell used to draw, so that surface never appears."),
        entry(73722, "Returns null where PlayStationTwoWayLinkUpsell used to draw, so that surface never appears."),
        entry(73723, "Returns null where PlayStationTwoWayLinkUpsell used to draw, so that surface never appears."),
        entry(73775, "Returns false from the Manage Subscriptions row's visibility " +
            "predicate."),
        entry(73776, "Returns false from the Manage Subscriptions row's visibility " +
            "predicate."),
        entry(73792, "Returns false from the Gift Inventory row's own visibility " +
            "predicate."),
        entry(73795, "Returns false from the Quests settings row's own visibility " +
            "predicate. This is what actually removes the row: the settings harness hides any " +
            "entry whose predicate returns exactly false."),
        entry(73800, "Stubs the Quest Home screen the Quests settings row navigates to."),
        entry(73801, "Stubs the Quest Home screen the Quests settings row navigates to."),
        entry(73828, "Returns null from the Quest Home screen, so a route that still " +
            "navigates there renders nothing. Every mobile entry point is gated separately; " +
            "this covers the screen itself, which a route name can still reach."),
        entry(73829, "Returns null from the Quest Home screen, so a route that still " +
            "navigates there renders nothing. Every mobile entry point is gated separately; " +
            "this covers the screen itself, which a route name can still reach."),
        entry(74250, "Returns false from the mobile quest-dock hook, so the dock is not " +
            "shown. The broad quest-eligibility check is the other lever, but its body is shared " +
            "with six unrelated capability checks and cannot be patched."),
        entry(74795, "Returns false from the Server Subscriptions row's visibility " +
            "predicate."),
        entry(74980, "Returns false from the Restore Subscription row's visibility " +
            "predicate."),
        entry(74981, "Returns false from the Restore Subscription row's visibility " +
            "predicate."),
        entry(75140, "Returns null where SettingsAppearanceChannelListPreviewNitroUpsellComponent used to draw, so that surface never appears."),
        entry(75141, "Returns null where SettingsAppearanceChannelListPreviewNitroUpsellComponent used to draw, so that surface never appears."),
        entry(75142, "Returns null where SettingsAppearanceChannelListPreviewNitroUpsell used to draw, so that surface never appears."),
        entry(75143, "Returns null where SettingsAppearanceChannelListPreviewNitroUpsell used to draw, so that surface never appears."),
        entry(75436, "Returns null where VideoUploadQualityNitroUpsell used to draw, so that surface never appears."),
        entry(75437, "Returns null where VideoUploadQualityNitroUpsell used to draw, so that surface never appears."),
        entry(76121, "Does nothing, so the action openPasskeyUpsell used to perform no longer happens."),
        entry(76122, "Does nothing, so the action openPasskeyUpsellPromoSheet used to perform no longer happens."),
        entry(76123, "Does nothing, so the action closePasskeyUpsellPromoSheet used to perform no longer happens."),
        entry(76124, "Returns null where PasswordlessUpsellPromoSheet used to draw, so that surface never appears."),
        entry(76125, "Returns null where PasswordlessUpsellPromoSheet used to draw, so that surface never appears."),
        entry(76162, "Returns null where PremiumTrialOfferActionSheetContent used to draw, so that " +
            "surface never appears."),
        entry(76163, "Returns null where PremiumTrialOfferActionSheetContent used to draw, so that " +
            "surface never appears."),
        entry(76188, "Removes the You-tab orb balance widget's menu, one of the screens the " +
            "orb gate sits in front of."),
        entry(76193, "Removes the wrapper component that builds the orb balance widget."),
        entry(76194, "Removes the wrapper component that builds the orb balance widget."),
        entry(76237, "Stops the survey override from taking effect, so a server-side " +
            "override cannot swap the app-rating survey the client is showing."),
        entry(76238, "Stops the survey-dismiss action, which both updated the store and " +
            "emitted its APP_NOTICE analytics event."),
        entry(76239, "Stops the survey-seen report, which told Discord the survey had " +
            "been shown and emitted analytics for it."),
        entry(72647, "Returns null where UserProfileTryItOutGetPremiumUpsell used to draw, so that surface never appears."),
        entry(72648, "Returns null where UserProfileTryItOutGetPremiumUpsell used to draw, so that surface never appears."),
        entry(76645, "Stubs the collectibles shop screen component the Settings route " +
            "renders, so the route draws nothing. The shop also has a deep-link entry the " +
            "route edit cannot close, which is why the screen component is stubbed."),
        entry(76646, "Stubs the collectibles shop screen component the Settings route " +
            "renders, so the route draws nothing. The shop also has a deep-link entry the " +
            "route edit cannot close, which is why the screen component is stubbed."),
        entry(76650, "Stubs the shop screen's internal component, leaving the screen with " +
            "nothing to render."),
        entry(76651, "Stubs the shop screen's internal component, leaving the screen with " +
            "nothing to render."),
        entry(76654, "Stubs the collectibles shop screen itself, so there is no shop tab to " +
            "browse."),
        entry(76655, "Stubs the collectibles shop screen itself, so there is no shop tab to " +
            "browse."),
        entry(76681, "Returns null where ShopNitroUpsellBanner used to draw, so that surface never appears."),
        entry(76804, "Returns false from the usePredicate closure the Quests toggles " +
            "in Data & Privacy carry, so those settings rows are hidden. The two toggles' " +
            "predicates are byte-identical and Hermes stores them once, so either id changes " +
            "both."),
        entry(76816, "Returns false from the usePredicate closure the Quests toggles " +
            "in Data & Privacy carry, so those settings rows are hidden. The two toggles' " +
            "predicates are byte-identical and Hermes stores them once, so either id changes " +
            "both."),
        entry(77011, "Returns null where ActivityPrivacyUpsellActionSheet used to draw, so that surface never appears."),
        entry(77012, "Returns null where ActivityPrivacyUpsellActionSheet used to draw, so that surface never appears."),
        entry(77841, "Returns null from the voice guild tag component, so no " +
            "guild tag chip is drawn in a voice channel."),
        entry(77842, "Returns null from the voice guild tag component, so no " +
            "guild tag chip is drawn in a voice channel."),
        entry(77991, "Does nothing and returns undefined, so BoostProgressBarCoachmark no longer " +
            "raises its prompt."),
        entry(77992, "Does nothing and returns undefined, so BoostProgressBarCoachmark no longer " +
            "raises its prompt."),
        entry(77995, "Does nothing and returns undefined, so GuildPowerupsBoostToUnlockCoachmark no " +
            "longer raises its prompt."),
        entry(77996, "Does nothing and returns undefined, so GuildPowerupsBoostToUnlockCoachmark no " +
            "longer raises its prompt."),
        entry(78239, "Returns null where GuildRoleSubscriptionsUpsellActionSheet used to draw, so that surface never appears."),
        entry(78240, "Returns null where GuildRoleSubscriptionsUpsellActionSheet used to draw, so that surface never appears."),
        entry(78241, "Returns null where IAPUpsellActionSheet used to draw, so that surface never appears."),
        entry(78242, "Returns null where IAPUpsellActionSheet used to draw, so that surface never appears."),
        entry(78243, "Returns null where GenericUpsellActionSheet used to draw, so that surface never appears."),
        entry(78244, "Returns null where GenericUpsellActionSheet used to draw, so that surface never appears."),
        entry(78245, "Returns null where CreatorMonetizationOnboardingV2UpsellActionSheet used to draw, so that surface never appears."),
        entry(78246, "Returns null where CreatorMonetizationOnboardingV2UpsellActionSheet used to draw, so that surface never appears."),
        entry(78247, "Returns null where GuildRoleSubscriptionTierTemplateUpsellActionSheet used to draw, so that surface never appears."),
        entry(78248, "Returns null where GuildRoleSubscriptionTierTemplateUpsellActionSheet used to draw, so that surface never appears."),
        entry(78252, "Answers false, so whatever useIsGuildEligibleForRoleSubscriptionsUpsell gates stays off."),
        entry(78253, "Answers false, so whatever useIsGuildEligibleForRoleSubscriptionsUpsell gates stays off."),
        entry(78300, "Returns null where GuildUpsellChannelList used to draw, so that surface never appears."),
        entry(78301, "Returns null where GuildUpsellChannelList used to draw, so that surface never appears."),
        entry(78314, "Returns null where OneColumnGuildUpsellList used to draw, so that surface never appears."),
        entry(78315, "Returns null where OneColumnGuildUpsellList used to draw, so that surface never appears."),
        entry(78316, "Returns null where GameCommunityMultiGuildUpsellCard used to draw, so that surface never appears."),
        entry(79943, "Returns null where GroupDMNitroUpsellBanner used to draw, so that surface never appears."),
        entry(79944, "Returns null where GroupDMNitroUpsellBanner used to draw, so that surface never appears."),
        entry(79945, "Returns null where GroupDMNitroCapBanner used to draw, so that surface never appears."),
        entry(79946, "Returns null where GroupDMNitroCapBanner used to draw, so that surface never appears."),
        entry(80103, "Stops the AutoAnalytics navigator hook that emitted an event " +
            "each time a screen was built."),
        entry(80104, "Stops the other half of the AutoAnalytics navigator hook, which " +
            "emitted an event each time the current screen changed."),
        entry(80254, "Rebuilds the profile section tab list the React Compiler copy of " +
            "the You screen content component builds, so it builds Main and Activity only, with " +
            "no Board and no Wishlist tab. The app runs that copy when the React Compiler " +
            "experiment is on; leaving it unpatched lets both tabs appear there too."),
        entry(80264, "Returns null and never raises its coachmark, so BadgeCustomizationProfileCoachmark shows nothing."),
        entry(80265, "Returns null and never raises its coachmark, so BadgeCustomizationProfileCoachmark shows nothing."),
        entry(80268, "Answers false, so whatever useShouldShowExpiringTrialOfferCard gates stays off."),
        entry(80269, "Answers false, so whatever useShouldShowExpiringTrialOfferCard gates stays off."),
        entry(80271, "Returns null where YouExpiringTrialOfferCard used to draw, so that surface never appears."),
        entry(80272, "Returns null where YouExpiringTrialOfferCard used to draw, so that surface never appears."),
        entry(80765, "Returns null where AppIconsCoachmarkActionSheet used to draw, so that surface " +
            "never appears."),
        entry(80766, "Returns null where AppIconsCoachmarkActionSheet used to draw, so that surface " +
            "never appears."),
        entry(80767, "Returns null where GiftingPromotionCoachmarkActionSheet used to draw, so that " +
            "surface never appears."),
        entry(80768, "Returns null where GiftingPromotionCoachmarkActionSheet used to draw, so that " +
            "surface never appears."),
        entry(80786, "Returns null where PremiumMarketingMomentActionSheet used to draw, so that " +
            "surface never appears."),
        entry(80787, "Returns null where PremiumMarketingMomentActionSheet used to draw, so that " +
            "surface never appears."),
        entry(80789, "Returns null where PremiumDiscountOfferActionSheetContent used to draw, so " +
            "that surface never appears."),
        entry(80790, "Returns null where PremiumDiscountOfferActionSheetContent used to draw, so " +
            "that surface never appears."),
        entry(80796, "Returns null where HasBadgeCoachmark used to draw, so that surface never " +
            "appears."),
        entry(80797, "Returns null where HasBadgeCoachmark used to draw, so that surface never " +
            "appears."),
        entry(80798, "Returns null where NewBadgeCoachmark used to draw, so that surface never " +
            "appears."),
        entry(80799, "Returns null where NewBadgeCoachmark used to draw, so that surface never " +
            "appears."),
        entry(80800, "Returns null where GiftingBadgesCoachmarkActionSheet used to draw, so that " +
            "surface never appears."),
        entry(80801, "Returns null where GiftingBadgesCoachmarkActionSheet used to draw, so that " +
            "surface never appears."),
        entry(80806, "Returns null where CollectiblesMobileAnnouncementActionSheet used to draw, so " +
            "that surface never appears."),
        entry(80807, "Returns null where CollectiblesMobileAnnouncementActionSheet used to draw, so " +
            "that surface never appears."),
        entry(80810, "Returns null where NitroFileUploadAnnouncementPromoSheet used to draw, so that surface never appears."),
        entry(80811, "Returns null where NitroFileUploadAnnouncementPromoSheet used to draw, so that surface never appears."),
        entry(80814, "Returns null where NitroFileUploadUpsellPromoSheet used to draw, so that surface never appears."),
        entry(80815, "Returns null where NitroFileUploadUpsellPromoSheet used to draw, so that surface never appears."),
        entry(81158, "Returns null where PremiumSoundboardFeatureUpsell used to draw, so that surface never appears."),
        entry(81159, "Returns null where PremiumSoundboardFeatureUpsell used to draw, so that surface never appears."),
        entry(81379, "Returns null from the voice panel's games section, the React Compiler " +
            "copy of modules/voice_panel/native/prejoin/VoicePanelGamesSection.tsx. The " +
            "section is the container the games list is drawn inside, so stubbing the " +
            "list alone would have left the container on screen; null removes both. The " +
            "app runs that copy only when the React Compiler experiment is on."),
        entry(81380, "Returns null from the voice panel's games section, the copy of " +
            "modules/voice_panel/native/prejoin/VoicePanelGamesSection.tsx this build " +
            "runs. The section is the container the games list is drawn inside, so " +
            "stubbing the list alone would have left the container on screen; null " +
            "removes both."),
        entry(81822, "Stops the per-request tracker, which ran URL matching and " +
            "appended to the telemetry ring for every HTTP request."),
        entry(82027, "Stops _trackStartSpeaking from computing game metadata and " +
            "packet stats when someone starts speaking in a voice call."),
        entry(82028, "Stops _trackStartListening from computing telemetry payloads " +
            "when someone starts listening in a voice call."),
        entry(82132, "Does nothing, so the action openDmSettingsUpsellModal used to perform no longer happens."),
        entry(82133, "Returns null where DmSettingsUpsellActionSheet used to draw, so that surface never appears."),
        entry(82134, "Returns null where DmSettingsUpsellActionSheet used to draw, so that surface never appears."),
        entry(83045, "Returns null where GuildSettingsServerTagUpsellCard used to draw, so that surface never appears."),
        entry(83046, "Returns null where GuildSettingsServerTagUpsellCard used to draw, so that surface never appears."),
        entry(83759, "Returns null where GuildPremiumRoleSubscribeButton used to draw, so that " +
            "surface never appears."),
        entry(83760, "Returns null where GuildPremiumRoleSubscribeButton used to draw, so that " +
            "surface never appears."),
        entry(84167, "Stops the app-state update handler listed with the " +
            "message-cache recorders, so it no longer runs on every app state change."),
        entry(84222, "Stops the patched global WebSocket being installed, " +
            "whose per-message handler parsed every gateway frame a second time and appended it " +
            "to the disabled telemetry ring."),
        entry(84264, "Stops the timer monitor that formatted slow-timer records into the " +
            "log, which only the stubbed analytics emitter read."),
        entry(84330, "Silences one of the logger's debug levels, so those lines no " +
            "longer enter the in-memory JavaScript debug log. Only warn and error are kept, so " +
            "genuine problems still surface."),
        entry(84331, "Silences log(), which is where most of the in-memory debug log was " +
            "coming from."),
        entry(84332, "Silences the logger's verbose level (the 'dangerously' variant), " +
            "keeping it out of the debug log."),
        entry(84333, "Silences verbose, one of the two levels that dominated the " +
            "in-memory debug log."),
        entry(84334, "Silences info, so informational lines no longer enter the debug " +
            "log."),
        entry(84337, "Silences trace, so trace lines no longer enter the debug log."),
        entry(84339, "Silences the file-only logger, so nothing is written to the " +
            "logger's file sink."),
        entry(88367, "Silences the central science-event emitter every feature calls, " +
            "so no event is built or sent and no per-event CPU/memory sample is taken. Its " +
            "callers await it, so the stub still returns a resolved promise."),
        entry(88372, "Stops the single POST behind the /science and /beaker queues, so " +
            "the events the stubs above would have queued are never uploaded either."),
        entry(88493, "Stops the once-a-second process sampler, which made three native " +
            "bridge calls per second for CPU and memory whose only consumers null-guard."),
        entry(95512, "Stops the voice-quality payload builder that assembled and sent " +
            "its statistics every five minutes."),
        entry(95863, "Stops the voice-quality sampler that ran once a second during a " +
            "call."),
        entry(95879, "Stops the system-responsiveness sampler, the second of the " +
            "voice-call samplers."),
        entry(95898, "Stops the video-effect and system-resource sampler that ran on " +
            "every call-statistics callback."),
        entry(115424, "Stops the 60-second session flusher that shipped session " +
            "aggregates."),
        entry(115535, "Stops the metrics aggregator adding samples to its buckets."),
        entry(115538, "Stops the metrics aggregator flushing its buckets on its " +
            "interval."),
        entry(115539, "Stops the metrics aggregator capturing a metrics snapshot."),
        entry(115560, "Stops the browser-side metrics aggregator adding samples to its " +
            "buckets."),
        entry(115561, "Stops the browser-side metrics aggregator flushing them."),
        entry(121505, "Rebuilds the profile activity list without friends' Spotify " +
            "listened-session entries. The bundle has a second copy of the filter in the same module, " +
            "reachable through a different parent, and both are edited so Spotify entries do " +
            "not survive on some surfaces."),
        entry(121628, "Rebuilds other users' profile tab list so it builds Main and " +
            "Activity only, with no Board and no Wishlist tab. It edits the " +
            "isReactCompilerEnabled() == false variant of the profile content component."),
        entry(128397, "Does nothing, so the action navigateToNitroPage used to perform no longer happens."),
        entry(128402, "Returns null where renderNitroUpsellText used to draw, so that surface never " +
            "appears."),
        entry(134750, "Rebuilds the profile section tab list so it builds Main and " +
            "Activity only, with no Board and no Wishlist tab. The tab is one element of a list, " +
            "not a function that can return false, so the body is edited rather than stubbed."),
        entry(140132, "Stops QuestFetchManager installing the recurring interval that " +
            "refetched quests forever, which against already-blocked endpoints was pure timer and " +
            "network churn."),
        entry(151330, "Rebuilds the profile activity list without friends' Spotify " +
            "listened-session entries."),
        entry(156514, "Stops the 60-second voice state interval callback that " +
            "repeatedly dispatches speaking and listening telemetry during calls."),
        entry(32823, "Answers false from isBlockedForMessage, so an already-received message " +
            "from a blocked user is drawn expanded instead of behind the blocked-message notice."),
        entry(37655, "Answers false from shouldAnimateSticker, so sticker previews and chat images " +
            "stay still instead of animating."),
        entry(48533, "Answers true from computeGlobalSpoilerDisplay, so spoiler text and " +
            "media are drawn revealed. Ported from Reseam's Reveal spoilers patch."),
        entry(48534, "Answers false from the spoiler-obscurity hook, so this copy of the " +
            "chat row does not obscure a spoiler. Ported from Reseam's Reveal spoilers patch."),
        entry(48535, "Answers false from the spoiler-obscurity hook, so this copy of the " +
            "chat row does not obscure a spoiler. Ported from Reseam's Reveal spoilers patch."),
        entry(48870, "Answers true from isLinkTrusted, so a masked link opens without the " +
            "destination warning. Ported from Reseam's Skip masked link warnings patch."),
        entry(53244, "Answers true from isLinkTrusted, so a masked link opens without the " +
            "destination warning. Ported from Reseam's Skip masked link warnings patch."),
        entry(46632, "Rebuilds createMessageContent with reaction chips, reply previews, embed and " +
            "link cards and activity invites turned off, and with custom emoji and GIFs not " +
            "animating. The option reads at the top of the function are forced false in place; " +
            "the rest of the renderer is unchanged."),
        entry(52769, "Returns null where SuperReactionLocalImageAnimation used to draw, so Super " +
            "Reaction decorations do not loop."),
        entry(52770, "Returns null where SuperReactionLocalImageAnimation used to draw, so Super " +
            "Reaction decorations do not loop."),
        entry(57030, "Answers false from useIsMessageSwipeActionsEnabled, so swiping a message no " +
            "longer starts a reply or an edit."),
        entry(57031, "Answers false from useIsMessageSwipeActionsEnabled, so swiping a message no " +
            "longer starts a reply or an edit."),
        entry(63701, "Answers false from handleTypingStartLocal, so the app does not send a typing " +
            "status when you type or record."),
        entry(75117, "Returns null where ActivityCardsItem used to draw, so the Appearance preview's " +
            "activity cards never appear."),
        entry(75118, "Returns null where ActivityCardsItem used to draw, so the Appearance preview's " +
            "activity cards never appear."),
        entry(53469, "Starts a reply with mentions turned off and the mention toggle shown, so a " +
            "reply does not ping the person it answers."),
        entry(53470, "Starts a shallow reply with mentions turned off and the mention toggle shown, " +
            "so the same rule applies to the short-lived reply state."),
        entry(27721, "Answers true from canStreamQuality, so screen sharing can use the high and " +
            "mid stream qualities Nitro otherwise gates."),
        entry(27709, "Answers true from canUseHighVideoUploadQuality, so media prepares at the " +
            "higher upload quality; upload limits are unchanged."),
        entry(51460, "Answers false from getIsEligibleForQuests, so no surface treats this account " +
            "as eligible to show quests."),
        entry(74695, "Returns null where QuestDockWithVisibilityContext used to draw, so the " +
            "mobile quest dock is never mounted."),
        entry(74696, "Returns null where QuestDockWithVisibilityContext used to draw, so the " +
            "mobile quest dock is never mounted."),
        entry(80963, "Returns null where QuestActivityButton used to draw, so the quest button " +
            "is not rendered."),
        entry(74252, "Answers false from useIsMobileQuestDockRenderedBase, so the rendered-dock " +
            "check never turns the quest dock on."),
        entry(74253, "Answers false from useIsMobileQuestDockRenderedBase, so the rendered-dock " +
            "check never turns the quest dock on."),
        entry(74246, "Returns zero from useMobileQuestDockHeight, so the quest dock reserves no " +
            "height and cannot appear."),
        entry(42625, "Returns null where the persistent boost marketing call to action used to " +
            "draw, so it is not shown on any screen."),
        entry(42626, "Returns null where the persistent boost marketing call to action used to " +
            "draw, so it is not shown on any screen."),
        entry(65194, "Returns null where GuildPowerupsBoostInfo used to draw, removing the boost " +
            "count and boost icon from the progress bar."),
        entry(65195, "Returns null where GuildPowerupsBoostInfo used to draw, removing the boost " +
            "count and boost icon from the progress bar."),
        entry(68872, "Returns null where BoostedGuildTierProgressCircle used to draw, so the " +
            "tier progress ring is not rendered."),
        entry(68873, "Returns null where BoostedGuildTierProgressCircle used to draw, so the " +
            "tier progress ring is not rendered."),
        entry(69000, "Returns null where the boost marketing call-to-action bar used to draw, " +
            "so the upgrade banner does not appear."),
        entry(69001, "Returns null where the boost marketing call-to-action bar used to draw, " +
            "so the upgrade banner does not appear."),
        entry(78157, "Returns null from the channel row indicator, so boost notifications and the " +
            "unread boost badge never appear beside a channel."),
        entry(78158, "Returns null from the channel row indicator, so boost notifications and the " +
            "unread boost badge never appear beside a channel."),
        entry(78159, "Returns null from the coachmark host, so the boost coachmark is never set " +
            "up or shown."),
        entry(78160, "Returns null from the coachmark host, so the boost coachmark is never set " +
            "up or shown."),
        entry(78161, "Returns null where GuildPowerupsCoachmark used to draw, so the boost " +
            "coachmark itself never appears."),
        entry(78162, "Returns null where GuildPowerupsCoachmark used to draw, so the boost " +
            "coachmark itself never appears."),
        entry(78163, "Returns null where GuildPowerupsChannelRow used to draw, so the boost " +
            "progress row is never added to the channel list."),
        entry(78164, "Returns null where GuildPowerupsChannelRow used to draw, so the boost " +
            "progress row is never added to the channel list."),
        entry(78171, "Returns null where GuildPowerupsProgressBar used to draw, so the boost " +
            "progress bar above the channel list is not rendered."),
        entry(78172, "Returns null where GuildPowerupsProgressBar used to draw, so the boost " +
            "progress bar above the channel list is not rendered."),
        entry(82740, "Returns null from renderBoostProgressBar, so the Settings overview does " +
            "not show its boost progress bar toggle row."),
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
            functionIds = listOf(50285, 55002, 81379, 81380)
        ),
        Feature(
            slug = "nitro_promotions",
            group = SHOP_GROUP,
            label = "Nitro promotions",
            description = "Removes the buttons, sheets and dialogs that advertise Nitro, including the " +
                "inline Get Nitro button in the chat bar.",
            functionIds = listOf(52196, 52696, 53824, 53825)
        ),
        Feature(
            slug = "collectibles_shop",
            group = SHOP_GROUP,
            label = "The collectibles shop",
            description = "Stops the shop screen drawing, so no tab or route opens it.",
            functionIds = listOf(76645, 76646, 76650, 76651, 76654, 76655)
        ),
        Feature(
            slug = "shop_entry_points",
            group = SHOP_GROUP,
            label = "Shop buttons and prompts",
            description = "Removes the button that opens the shop, the prompt that points at it, and " +
                "the settings row that leads there.",
            functionIds = listOf(17189, 67250, 67251, 67252, 67253)
        ),
        Feature(
            slug = "shop_this_look",
            group = SHOP_GROUP,
            label = "Shop This Look",
            description = "Answers no to the experiment that turns the feature on, so the row " +
                "that opens the sheet is never built, and empties the sheet and its prompt as " +
                "well for any path that reaches them.",
            functionIds = listOf(48308, 48309, 67474, 67475, 67491, 67492)
        ),
        Feature(
            slug = "game_profile_shop",
            group = SHOP_GROUP,
            label = "Game profile shop",
            description = "Stops the shop carousel drawing on a game's profile and removes the guild " +
                "sidebar's row for it.",
            functionIds = listOf(41543, 50625, 50626)
        ),
        Feature(
            slug = "guild_shop",
            group = SHOP_GROUP,
            label = "Guild shop",
            description = "Hides the guild shop and its preview by answering false to every check " +
                "that decides whether to show one.",
            functionIds = listOf(41835, 41836, 41837, 41838, 41839)
        ),
        Feature(
            slug = "orbs",
            group = SHOP_GROUP,
            label = "Orbs",
            description = "Turns orbs off at the gate every orb surface reads, and removes the orb " +
                "balance, price tags and reward dialogs.",
            functionIds = listOf(67227, 67228, 67336, 67337, 68146, 68147, 76188, 76193, 76194)
        ),
        Feature(
            slug = "storefront",
            group = SHOP_GROUP,
            label = "Store and product lookups",
            description = "Stops the app fetching store products, prices and collections, and the " +
                "guild data it loaded on every connection.",
            functionIds = listOf(48318, 48753, 50741, 50742, 51094, 54789, 54793)
        ),
        Feature(
            slug = "gift_buttons",
            group = GIFTS_GROUP,
            label = "Gift buttons",
            description = "Removes the gift button from the chat bar and the button that buys a gift.",
            functionIds = listOf(
                63926, 63927, 63929, 63930, 63932, 63933, 68173, 68174, 68981, 68982
            )
        ),
        Feature(
            slug = "billing_rows",
            group = BILLING_GROUP,
            label = "Subscription and billing rows",
            description = "Hides the settings rows for Nitro, managing a plan or subscription, server " +
                "boosts and subscriptions, the gift inventory and restoring a purchase.",
            functionIds = listOf(16275, 16281, 16288, 73775, 73776, 73792, 74795, 74980, 74981)
        ),
        Feature(
            slug = "avatar_decorations",
            group = LOOK_GROUP,
            label = "Avatar decorations",
            description = "Stops avatars carrying decorations, at the one transform every caller " +
                "passes a decoration through.",
            functionIds = listOf(26871)
        ),
        Feature(
            slug = "nameplates",
            group = LOOK_GROUP,
            label = "Nameplates",
            description = "Stops the collectible nameplate drawing next to a name.",
            functionIds = listOf(50864, 50865)
        ),
        Feature(
            slug = "profile_frames",
            group = LOOK_GROUP,
            label = "Profile frames",
            description = "Stops the collectible profile frame drawing around an avatar.",
            functionIds = listOf(48346, 48347)
        ),
        Feature(
            slug = "guild_tags",
            group = LOOK_GROUP,
            label = "Guild tags",
            description = "Stops guild tag chips, badges and voice-channel tags drawing, and answers " +
                "false to the checks that decide whether to show one.",
            functionIds = listOf(
                48075, 48076, 48080, 50290, 50291, 50292, 50293, 50294, 77841, 77842
            )
        ),
        Feature(
            slug = "profile_badges",
            group = PROFILE_GROUP,
            label = "Profile badges",
            description = "Returns no badges from the profile badge hook, so the badge row has nothing " +
                "to draw.",
            functionIds = listOf(48407, 48408)
        ),
        Feature(
            slug = "profile_effects",
            group = PROFILE_GROUP,
            label = "Animated profile effects",
            description = "Stops the animated layer that plays over a profile card.",
            functionIds = listOf(50823, 50824)
        ),
        Feature(
            slug = "edit_profile_row",
            group = PROFILE_GROUP,
            label = "The edit profile row",
            description = "Removes the Edit User Profile entry from settings.",
            functionIds = listOf(72374, 72375)
        ),
        Feature(
            slug = "quests_in_app",
            group = QUESTS_GROUP,
            label = "Quests in the app",
            description = "Removes the Quests screen, its dock, its navigation button, its settings row " +
                "and its toggles in Data & Privacy.",
            functionIds = listOf(
                51199, 51460, 51569, 73795, 73800, 73801, 73828, 73829, 74246, 74250,
                74252, 74253, 74695, 74696, 76804, 76816, 80963
            )
        ),
        Feature(
            slug = "quest_fetching",
            group = QUESTS_GROUP,
            label = "Quest fetching",
            description = "Stops the app fetching, refreshing and re-polling quests in the background.",
            functionIds = listOf(51593, 51594, 51602, 51603, 51604, 140132)
        ),
        Feature(
            slug = "wishlist_tabs",
            group = WISHLIST_GROUP,
            label = "Wishlist tabs",
            description = "Removes the Wishlist and Board tabs from your profile and from other " +
                "people's, in both copies of each profile component, the copy the app runs by " +
                "default and the React Compiler copy, so the removal holds whichever copy the " +
                "experiment selects.",
            functionIds = listOf(67723, 67983, 80254, 121628, 134750)
        ),
        Feature(
            slug = "wishlist_grids",
            group = WISHLIST_GROUP,
            label = "Wishlist grids and buttons",
            description = "Stops the wishlist and suggestion grids drawing, and removes the buttons " +
                "that add an item to a wishlist.",
            functionIds = listOf(68020, 68025, 68026, 68037, 68038, 68042)
        ),
        Feature(
            slug = "spotify",
            group = SPOTIFY_GROUP,
            label = "Spotify integration",
            description = "Removes Spotify from the app: the presence a friend shows, the track " +
                "renderer, the player subscription and the listened-session entries on a profile.",
            functionIds = listOf(55011, 57446, 57465, 57470, 121505, 151330)
        ),
        Feature(
            slug = "rating_prompts",
            group = SURVEYS_GROUP,
            label = "App rating prompts",
            description = "Stops the app asking you to rate it, and the reports that told Discord the " +
                "prompt was shown or dismissed.",
            functionIds = listOf(45205, 76237, 76238, 76239)
        ),
        Feature(
            slug = "analytics_events",
            group = ANALYTICS_GROUP,
            label = "Analytics events",
            description = "Stops analytics events being built and sent, at the emitters every feature " +
                "calls and at the single request behind them.",
            functionIds = listOf(24890, 24953, 24961, 71544, 88367, 88372)
        ),
        Feature(
            slug = "screen_tracking",
            group = ANALYTICS_GROUP,
            label = "Screen and navigation tracking",
            description = "Stops the event sent each time a screen is built and the per-request " +
                "tracker that matched URLs for it.",
            functionIds = listOf(35380, 80103, 80104, 81822)
        ),
        Feature(
            slug = "voice_tracking",
            group = ANALYTICS_GROUP,
            label = "Voice and call tracking",
            description = "Stops the events sent when someone starts or stops speaking or listening, " +
                "and the interval that reported voice state.",
            functionIds = listOf(82027, 82028, 156514)
        ),
        Feature(
            slug = "impression_tracking",
            group = ANALYTICS_GROUP,
            label = "Impression tracking",
            description = "Stops impressions being recorded, and the flag that exported the telemetry " +
                "ring every half second.",
            functionIds = listOf(26916, 50639)
        ),
        Feature(
            slug = "crash_upload",
            group = CRASH_GROUP,
            label = "Crash uploads",
            description = "Stops the crash reporter starting at all, and with it every crash, " +
                "exception and logged message it would have sent.",
            functionIds = listOf(24816, 24817, 24818, 24822, 24823, 24831)
        ),
        Feature(
            slug = "crash_context",
            group = CRASH_GROUP,
            label = "What a crash report would carry",
            description = "Stops the identity, tags, context, breadcrumbs and feature flags that would " +
                "be attached to a report.",
            functionIds = listOf(22110, 24812, 24813, 24814, 24815, 24819, 24820, 24824)
        ),
        Feature(
            slug = "performance_metrics",
            group = PERFORMANCE_GROUP,
            label = "Performance metrics",
            description = "Stops the app sampling its own performance and uploading the result. " +
                "The once-a-second process sampler is stubbed and its timer slowed to once " +
                "an hour, so it no longer wakes the JavaScript thread every second.",
            functionIds = listOf(
                1379, 21335, 21337, 37500, 37501, 37502, 88493, 115424, 115535, 115538, 115539, 115560,
                115561
            )
        ),
        Feature(
            slug = "call_sampling",
            group = PERFORMANCE_GROUP,
            label = "Call quality sampling",
            description = "Stops the samplers that run during a call to measure voice quality, " +
                "responsiveness and video effects.",
            functionIds = listOf(95512, 95863, 95879, 95898)
        ),
        Feature(
            slug = "session_telemetry",
            group = TELEMETRY_GROUP,
            label = "Session and network statistics",
            description = "Stops the app recording network, session and message-cache statistics, and " +
                "the heartbeat that reported them.",
            functionIds = listOf(
                43048, 43049, 43050, 43052, 43053, 43054, 43081, 43237, 43238, 43239, 84167
            )
        ),
        Feature(
            slug = "gateway_telemetry",
            group = TELEMETRY_GROUP,
            label = "Gateway connection reporting",
            description = "Stops the gateway connection being logged, measured and reported, and the " +
                "patched socket that read every message.",
            functionIds = listOf(69276, 69277, 69278, 69279, 84222)
        ),
        Feature(
            slug = "startup_timing",
            group = STARTUP_GROUP,
            label = "Startup timing",
            description = "Stops the app recording how long startup takes, milestone by milestone.",
            functionIds = listOf(
                19600, 19602, 19604, 19612, 19643, 19644, 19645, 19646, 19647, 19648, 19649, 19652
            )
        ),
        Feature(
            slug = "debug_logging",
            group = LOGGING_GROUP,
            label = "Debug logs",
            description = "Silences the in-app debug log at every level, the aggregator that fed it and " +
                "the file it wrote to.",
            functionIds = listOf(19571, 84264, 84330, 84331, 84332, 84333, 84334, 84337, 84339)
        ),
        Feature(
            slug = "device_fingerprint",
            group = FINGERPRINT_GROUP,
            label = "Device fingerprinting",
            description = "Stops the handler that computed a fingerprint of your device for every " +
                "tracked event.",
            functionIds = listOf(21184)
        ),
        Feature(
            slug = "markup_cache_bound",
            group = STABILITY_GROUP,
            label = "Bound the message cache",
            description = "Caps the cache the app fills while drawing messages. It had no limit " +
                "and its expiry was refreshed on every read, so it grew for as long as the app " +
                "ran, which is memory pressure the app cannot recover.",
            functionIds = listOf(8464)
        ),
        Feature(
            slug = "leftover_stubs",
            group = LEFTOVER_GROUP,
            label = "Stubs by function id",
            description = "Four functions stubbed by id. This release moved what " +
                "those ids hold, so three of the stubs change nothing you can see; the other " +
                "stands in for a typing-indicator hook. Switching this off leaves all four as " +
                "Discord wrote them.",
            functionIds = listOf(15383, 63686, 83889, 67749)
        ),
        Feature(
            slug = "nitro_upsells",
            group = NITRO_UPSELL_GROUP,
            label = "Nitro upsells",
            description = "Every Nitro upsell surface: the banners, promo sheets, action sheets, cards and the lock " +
                "toasts the app raises when something needs Nitro.",
            functionIds = listOf(
                24224, 47009, 47010, 52253, 52254, 52694, 52695, 57365, 57366, 57367, 57369, 60457,
                60458, 60459, 63587, 66878, 66879, 66929, 66930, 68127, 68128, 68145, 68150, 68151,
                68157, 68158, 68214, 68215, 68216, 68217, 68222, 68223, 68559, 68560, 68632, 68633,
                68638, 68639, 68744, 68745, 68837, 68838, 68887, 68888, 68919, 68920, 72680, 72681,
                72682, 72683, 75140, 75141, 75142, 75143, 75436, 75437, 76162, 76163, 76681, 79943,
                79944, 79945, 79946, 80765, 80766, 80767, 80768, 80786, 80787, 80810, 80811, 80814,
                80815, 128397, 128402
            )
        ),
        Feature(
            slug = "premium_upsells",
            group = UPSELL_GROUP,
            label = "Other upsell prompts",
            description = "The remaining upsell sheets, cards, alerts and the buttons that open them.",
            functionIds = listOf(
                33453, 42687, 42688, 42775, 42777, 42785, 42786, 42787, 42834, 42835, 52193, 52195,
                52201, 52202, 52203, 52204, 52771, 52772, 52905, 52906, 52907, 52908, 52911, 52912,
                52915, 52916, 52917, 52918, 52994, 52995, 52996, 52997, 56024, 56025, 57374, 57375,
                57376, 65516, 66942, 68582, 68583, 68882, 68883, 68907, 68908, 68914, 68915, 68953,
                68954, 68961, 68964, 68965, 70291, 70292, 72710, 72711, 73359, 73360, 73696, 73697,
                73716, 73717, 73720, 73721, 73722, 73723, 76121, 76122, 76123, 76124, 76125, 77011,
                77012, 78241, 78242, 78243, 78244, 78245, 78246, 80789, 80790, 80806, 80807, 81158,
                81159, 82132, 82133, 82134
            )
        ),
        Feature(
            slug = "guild_upsells",
            group = UPSELL_GROUP,
            label = "Guild and boost upsells",
            description = "The guild boosting, server tag and role-subscription upsells.",
            functionIds = listOf(
                38361, 38376, 38377, 41446, 42629, 42630, 52903, 52904, 52913, 52914, 56011, 56012,
                56013, 65292, 65293, 65856, 65857, 65858, 65859, 67275, 67276, 68896, 68897, 78239,
                78240, 78247, 78248, 78252, 78253, 78300, 78301, 78314,
                78315, 78316, 83045, 83046, 83759, 83760
            )
        ),
        Feature(
            slug = "storefront_screens",
            group = STOREFRONT_GROUP,
            label = "Storefront screens",
            description = "The storefront itself: the product page, the gift and purchase flows, the wishlist cards and " +
                "the store item card.",
            functionIds = listOf(
                41571, 50874, 50875, 54773, 54774, 54775, 54776, 54777, 54814, 54815, 54816, 54817,
                54873, 54874, 54880, 54881, 66476, 66477, 66478, 66479, 66551, 66552
            )
        ),
        Feature(
            slug = "trials",
            group = TRIAL_GROUP,
            label = "Free trials",
            description = "The trial offers: the card that counts down an expiring trial, the hooks that decide whether " +
                "to show it, and the reverse-trial prompts.",
            functionIds = listOf(42844, 42845, 80268, 80269, 80271, 80272)
        ),
        Feature(
            slug = "emoji_upsells",
            group = UPSELL_GROUP,
            label = "Emoji and sticker upsells",
            description = "The upsells inside the emoji and sticker pickers.",
            functionIds = listOf(
                52888, 52889, 52890, 52899, 52900, 52901, 52902, 52909, 52910, 52962, 52963, 52965,
                52966, 53833, 53834, 53849, 53850, 64595, 64596, 64609, 64610
            )
        ),
        Feature(
            slug = "leaderboards",
            group = LEADERBOARD_GROUP,
            label = "Leaderboard badges",
            description = "The badge a leaderboard winner carries on their profile.",
            functionIds = listOf(55041, 55042)
        ),
        Feature(
            slug = "profile_upsells",
            group = UPSELL_GROUP,
            label = "Profile upsells",
            description = "The upsells drawn on a profile and beside the activity card.",
            functionIds = listOf(
                72390, 72391, 72522, 72523, 72675, 72676, 72684, 72685, 72640, 72641, 73353, 73354,
                72647, 72648
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
                33515, 33516, 33517, 33518, 33519, 33520, 56742, 56743, 56746, 56749, 56750, 56807,
                56808, 56809, 56810, 56811, 56812, 67753, 67754, 72511, 72512, 72513, 72520, 80264,
                80265, 80796, 80797, 80798, 80799, 80800, 80801
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
            functionIds = listOf(36044, 36065, 36070)
        ),
        Feature(
            slug = "better_stream_quality",
            group = VIDEO_GROUP,
            label = "Higher stream quality",
            description = "Answers true from canStreamQuality, so screen sharing can use the " +
                "high and mid stream qualities the app otherwise gates. Ported from Reseam's " +
                "Better stream quality patch.",
            functionIds = listOf(27721)
        ),
        Feature(
            slug = "better_video_upload_quality",
            group = VIDEO_GROUP,
            label = "Higher video upload quality",
            description = "Answers true from canUseHighVideoUploadQuality, so video is " +
                "prepared at the higher upload quality. Upload size limits stay the same. " +
                "Ported from Reseam's Better video quality patch.",
            functionIds = listOf(27709)
        ),
        Feature(
            slug = "appearance_activity_cards",
            group = LOOK_GROUP,
            label = "Appearance activity cards",
            description = "Removes the activity cards from the Appearance preview, so the theme " +
                "preview stays a preview of the theme.",
            functionIds = listOf(75117, 75118)
        ),
        Feature(
            slug = "super_reaction_animation",
            group = CHAT_GROUP,
            label = "Super Reaction animations",
            description = "Stops the Super Reaction decoration from looping on a message.",
            functionIds = listOf(52769, 52770)
        ),
        Feature(
            slug = "static_stickers",
            group = CHAT_GROUP,
            label = "Animated stickers",
            description = "Makes sticker previews and chat stickers still instead of animated.",
            functionIds = listOf(37655)
        ),
        Feature(
            slug = "show_blocked_messages",
            group = CHAT_GROUP,
            label = "Messages from blocked users",
            description = "Draws an already-received message from a blocked user expanded instead " +
                "of collapsing it behind the blocked-message notice.",
            functionIds = listOf(32823)
        ),
        Feature(
            slug = "reveal_spoilers",
            group = CHAT_GROUP,
            label = "Spoiler text and media",
            description = "Shows spoiler text, images and embeds without the tap-to-reveal " +
                "obscuring. Ported from Reseam's Reveal spoilers patch.",
            functionIds = listOf(48533, 48534, 48535)
        ),
        Feature(
            slug = "skip_masked_link_warnings",
            group = CHAT_GROUP,
            label = "Masked link warnings",
            description = "Opens masked links without the destination warning. Ported from " +
                "Reseam's Skip masked link warnings patch.",
            functionIds = listOf(48870, 53244)
        ),
        Feature(
            slug = "typing_status_outgoing",
            group = PRIVACY_GROUP,
            label = "Typing status you send",
            description = "Stops the app sending a typing status when you type or record, so " +
                "other people do not see that you are typing.",
            functionIds = listOf(63701)
        ),
        Feature(
            slug = "message_swipe_actions",
            group = CHAT_GROUP,
            label = "Message swipe actions",
            description = "Stops swiping a message from starting a reply or an edit.",
            functionIds = listOf(57030, 57031)
        ),
        Feature(
            slug = "message_card_clutter",
            group = CHAT_GROUP,
            label = "Message cards and animation",
            description = "Hides reaction chips, reply previews, link and embed cards, gift cards " +
                "and activity invites in messages, and stops custom emoji and GIFs animating. " +
                "One switch for the message-card clutter the Reseam bundle spreads across several.",
            functionIds = listOf(46632)
        ),
        Feature(
            slug = "replies_without_pings",
            group = CHAT_GROUP,
            label = "Replies without pings",
            description = "Starts a reply with mentions turned off and the mention toggle shown, " +
                "so answering a message does not ping its author unless you turn it back on.",
            functionIds = listOf(53469, 53470)
        ),
        Feature(
            slug = "server_boost_displays",
            group = BOOST_GROUP,
            label = "Server boost displays",
            description = "Removes the boost progress bar above the channel list, the boost count " +
                "and tier circle, the boost channel row and its indicator, the boost coachmarks, " +
                "the Settings overview toggle, and the boost marketing banners, so boost surfaces " +
                "are not shown.",
            functionIds = listOf(
                42625, 42626, 65194, 65195, 68872, 68873, 69000, 69001, 77991, 77992,
                77995, 77996, 78157, 78158, 78159, 78160, 78161, 78162, 78163, 78164,
                78171, 78172, 82740
            )
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
