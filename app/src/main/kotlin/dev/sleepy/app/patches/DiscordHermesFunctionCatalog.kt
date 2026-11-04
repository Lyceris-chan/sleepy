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
 *
 * Retargeted to Discord 354.1 (versionCode 354201). Several 353.2.0 ids now resolve to one
 * function and are described once; thirteen entries were retired, ten with no 354.1 counterpart
 * and three replaced by the added route and list edits.
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
    private const val GAME_GROUP = "Friends' activity"
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
    private const val ROWS_GROUP = "Rows and dividers"
    private const val CALL_SCREEN_GROUP = "Call screen"
    private const val CONJURE_GROUP = "AI features"
    private const val THEME_GROUP = "Server themes"
    private const val HOME_GROUP = "Home feed"
    private const val SERVER_BAR_GROUP = "Server bar"

    /** Every patched function, in the order the patch table applies them. */
    val ENTRIES: List<Entry> = listOf(
        entry(1372, "Slows the process-usage poll from once a second to once an hour. The same " +
            "feature stubs the poll callback, so the timer only wakes the JavaScript thread; at " +
            "one second it did that 86,400 times a day for values the app no longer collects."),
        entry(8685, "Clamps the entry count of the message-markup cache. It was built with no " +
            "limit and with its expiry clock refreshed on every read, so it grew for as long as " +
            "the app ran, on the path that draws every message."),
        entry(16108, "The bundle has no startup initializer at this id: it is the module factory " +
            "for modules/telemetry_ring/native/channels/NormalTelemetry.tsx. The stub returns " +
            "undefined without running the factory, so the module never assigns its default " +
            "export and an importer sees no NormalTelemetry channel."),
        entry(17026, "Gives the Settings Nitro / Manage Nitro row the always-false predicate that " +
            "hides it. Its backend is already answered with a 204, so the row led to a screen " +
            "that could do nothing."),
        entry(17032, "Gives the Settings Manage Plan row the always-false predicate that hides " +
            "it."),
        entry(17039, "Gives the Settings Server Boost row the always-false predicate that hides " +
            "it."),
        entry(17970, "Gives the Settings CollectiblesShop route the always-false predicate that " +
            "hides its row. This def had none of its own, so the route object itself is edited, " +
            "the same lever that removed the other billing rows."),
        entry(20404, "Stops the log aggregator from feeding the in-app debug panel's log buffer."),
        entry(20433, "Stops the time-to-interactive tracker recording a milestone's start."),
        entry(20435, "Stops the time-to-interactive tracker recording a milestone's end."),
        entry(20437, "Stops the time-to-interactive tracker storing a value for a milestone."),
        entry(20445, "Stops the time-to-interactive tracker recording a measurement."),
        entry(20476, "Stops the tracing recorder appending its formatted line to the in-memory " +
            "startup log."),
        entry(20477, "Stops startup milestone marks being recorded, one of the recorders called " +
            "at every startup step."),
        entry(20478, "Stops the milestone mark-and-log recorder used by the traced operations."),
        entry(20479, "Stops import-detail records being added to the startup trace."),
        entry(20480, "Stops delta marks from being added to the startup trace."),
        entry(20481, "Stops timestamped marks from being recorded in the startup trace."),
        entry(20482, "Stops detail records from being added to the startup trace."),
        entry(20485, "Stops the server-issued trace id from being stored on the startup trace."),
        entry(22017, "Stops the fingerprint handler, which computed the old and new device " +
            "fingerprint for every tracked event and reported the transition."),
        entry(33136, "Returns zero from the libdiscore action-telemetry sample rate, which the " +
            "server controls, so the client collects no action payloads whatever treatment it is " +
            "assigned."),
        entry(33138, "Returns false from the libdiscore action-telemetry gate, which runs on " +
            "every redux action and decides whether to collect that action's serialized payload, " +
            "so no payload is collected."),
        entry(22882, "Stops Sentry's own breadcrumb collector, which recorded one for every Flux " +
            "dispatcher action, so the collection stops at the source rather than at the sender."),
        entry(24984, "Answers false, so whatever disableActivityHostLeftNitroUpsell gates stays " +
            "off."),
        entry(25572, "Stops Sentry attaching the signed-in user's identity to error reports."),
        entry(25573, "Stops Sentry clearing the user identity it would otherwise have attached."),
        entry(25574, "Stops tags being attached to the Sentry scope, so error reports carry no " +
            "user-defined tags."),
        entry(25575, "Stops extra context being attached to the Sentry scope."),
        entry(25576, "Stops JavaScript exceptions being reported to Sentry, so stack traces stay " +
            "on the device."),
        entry(25577, "Stops JavaScript crash reports being sent to Sentry."),
        entry(25578, "Stops manually logged messages being sent to Sentry."),
        entry(25579, "Stops feature-flag names being recorded on the Sentry scope."),
        entry(25580, "Stops Sentry's JavaScript API adding breadcrumbs, which is what recorded UI " +
            "clicks and navigations."),
        entry(25582, "Stops the deliberate crash call from reaching Sentry."),
        entry(25583, "Stops the memory-warning report from reaching Sentry."),
        entry(25584, "Stops the crash-handled marker being recorded on the Sentry scope."),
        entry(25591, "Stops the Sentry JavaScript SDK from initializing at all, so no client is " +
            "bound and none of its automatic integrations (global error handlers, breadcrumbs, " +
            "promise-rejection tracking) are installed."),
        entry(25650, "Silences the [Analytics] debug reporter, so the client stops writing its " +
            "analytics events into the debug log."),
        entry(25713, "Silences the analytics store's own tracking closure, which emits events " +
            "independently of the central emitter."),
        entry(25721, "Silences the [Analytics] network-action reporter, so per-request analytics " +
            "are never emitted."),
        entry(27751, "Returns null from the avatar decoration data transform, the choke point " +
            "every caller that brings a decoration into the client passes through. Those callers " +
            "assign the result without reading it, and the function already returns null on its " +
            "own empty paths, so no decoration reaches the client."),
        entry(27796, "Returns false from the analytics experiment flag, so the 500 ms " +
            "telemetry-ring export loop can never start."),
        entry(34462, "Does nothing, so the coachmark binding BadgesCoachmarkBindings never " +
            "mounts."),
        entry(34461, "Returns null where BadgesCoachmarkRiveInner used to draw, so that surface " +
            "never appears."),
        entry(34463, "Returns null where BadgesCoachmarkRiveWithBoundary used to draw, so that " +
            "surface never appears."),
        entry(34406, "Answers false, so whatever ageEligibleForPremiumUpsell gates stays off."),
        entry(36098, "Silences the emitter behind the forum and clickstream trackers, so those " +
            "impressions are never sent."),
        entry(36779, "Does nothing, so a channel set to full quality cannot raise the encoder " +
            "above the lowered camera capture."),
        entry(36800, "Caps the camera capture at 640×360 at 24 frames per second and the " +
            "encoder's maximum bitrate at 1 Mbps, about four times fewer pixels than 720p."),
        entry(36805, "Builds the encoder ladder from a 640×360 pixel budget, so the encoder's " +
            "largest rung matches the camera instead of asking it to upscale."),
        entry(38240, "Stops the monitoring agent's counter, which about twenty-five modules call " +
            "as they do their work."),
        entry(38241, "Stops the monitoring agent's distribution sample."),
        entry(38242, "Stops the monitoring agent flushing what it accumulated to the two-minute " +
            "/metrics/v2 upload."),
        entry(39065, "Returns null where StageBoostingActionSheet used to draw, so that surface " +
            "never appears."),
        entry(39075, "Returns null where PremiumGuildSubscribeModal used to draw, so that surface " +
            "never appears."),
        entry(41877, "Does nothing, so the action hideInlineGuildCapUpsell used to perform no " +
            "longer happens."),
        entry(41991, "Returns false from the storefront capability check, which hides the guild " +
            "sidebar's game-shop row and blocks the redirect into it."),
        entry(42015, "Answers false, so whatever isOnSocialLayerStorefrontPage gates stays off."),
        entry(42256, "Returns false from the Guild Shop visibility hook, one of the two " +
            "visibility gates the creator-monetisation guild shop module holds. The hook's " +
            "callers test for exactly false, which is the shape this stub returns."),
        entry(42257, "Returns false from the plain isGuildShopVisibleInGuild predicate exported " +
            "beside the visibility hook, so a caller that asks whether the guild shop is visible " +
            "is answered no."),
        entry(42258, "Returns false from the Guild Shop preview visibility hook, the second of " +
            "the module's two visibility gates, so the preview surface stays hidden. Its callers " +
            "test for exactly false."),
        entry(42965, "Returns null where GuildBoostingSubscribeButton used to draw, so that " +
            "surface never appears."),
        entry(43016, "Returns null where PremiumModal used to draw, so that surface never " +
            "appears."),
        entry(43099, "Does nothing, so the action openPremiumPlanSelectionActionSheet used to " +
            "perform no longer happens."),
        entry(43104, "Returns null where PremiumPlanSelectionActionSheet used to draw, so that " +
            "surface never appears."),
        entry(43103, "Returns null where PremiumPlanSelectionActionSheetCTA used to draw, so that " +
            "surface never appears."),
        entry(43106, "Returns null where PremiumPlanSelectionActionSheetWithOrderCTX used to " +
            "draw, so that surface never appears."),
        entry(43153, "Returns null where PremiumPlanActionSheetHeader used to draw, so that " +
            "surface never appears."),
        entry(43159, "Answers false, so whatever useShouldShowPremiumTrialUserSettingsAvatarBadge " +
            "gates stays off."),
        entry(43355, "Stops the network-statistics sampler restarting its timers when the app " +
            "changes state."),
        entry(43356, "Stops the network-statistics recorder writing its accumulated events to " +
            "device storage."),
        entry(43357, "Stops the network-statistics sampler's own track(), which queued events " +
            "locally."),
        entry(43359, "Stops the network-statistics sampler's app-state listener, which reports to " +
            "the sampler whether each app-state change leaves the app active."),
        entry(43360, "Stops the network-statistics sampler's per-message analytics accumulator, " +
            "which adds each message's send-analytics duration and queue size to its totals."),
        entry(43361, "Stops the network-statistics sampler's per-message counter, whose whole " +
            "body increments the sampler's running count."),
        entry(43388, "Stops the session heartbeat scheduler being initialized, so its timer, its " +
            "periodic API ping and its breadcrumb are never created."),
        entry(43544, "Stops the message-cache statistic that counted channel fetches starting."),
        entry(43545, "Stops the message-cache statistic that counted a channel being served " +
            "locally."),
        entry(43546, "Stops the message-cache statistic that counted a channel being fetched over " +
            "the network."),
        entry(45503, "Stops the app-rating survey poll, which fetched the survey and emitted " +
            "analytics for it."),
        entry(47292, "Returns null where DoubleTapNitroAlert used to draw, so that surface never " +
            "appears."),
        entry(48277, "Returns false from the guild-tag display hook, so callers that decide " +
            "whether to build a tag row build none. The nulled tag components hide the chips " +
            "themselves; this also stops them being constructed."),
        entry(48278, "Returns false from the plain guild-tag display predicate, so a caller that " +
            "asks whether to show a guild tag is answered no."),
        entry(48465, "Answers no from the Shop This Look experiment gate, the check every entry " +
            "point into the feature reads. Editing the sheet and the coachmark alone leaves the " +
            "opener, whose body is shared with an unrelated display-name sheet, so the menu row " +
            "was still built and still opened an empty sheet. Nothing that renders Shop This Look " +
            "is reachable once this says no."),
        entry(48471, "Stops the storefront fetching products by SKU id. The implementation is " +
            "stubbed rather than the exported trampoline, whose body Hermes shares with dozens of " +
            "unrelated API calls."),
        entry(48497, "Returns null from the collectible profile frame component, so no profile " +
            "frame is drawn."),
        entry(48538, "Returns an empty array from the profile badges hook, so the badge row has " +
            "no badges to render. The hook already returns an empty array on its own empty paths, " +
            "so its callers handle the shape."),
        entry(48843, "Stops the guild-affinity fetch that ran on every connection open. It was " +
            "found by logging what the blocklist answered with a 204, and it hits an endpoint " +
            "that is already blocked."),
        entry(50106, "Returns false from isPlayingGameActivity, the predicate in " +
            "modules/activities/utils/isPlayingGameActivity.tsx that decides whether an activity " +
            "is a game being played. Both of its callers ask it before they collect anything - " +
            "the voice-channel games hook behind the voice panel's games section, and the hook " +
            "that gathers the games people are playing in a voice channel - so answering false " +
            "leaves each with an empty list rather than with games in it."),
        entry(50111, "Returns null from the guild tag badge component, so no badge chip is drawn " +
            "next to a name."),
        entry(50112, "Returns null from the base guild tag chiplet, the component the guild tag " +
            "chips build on, so no chip is drawn through it."),
        entry(50113, "Returns null from the guild tag component, so no guild tag chip is drawn " +
            "next to a name."),
        entry(50529, "Stubs the game-profile shop carousel, so the carousel a game profile page " +
            "renders draws nothing. The carousel sits outside the shop screen, which is why " +
            "stubbing the screen did not remove it."),
        entry(37621, "Stops impression tracking: this path builds its own trackMaker and posts to " +
            "/science without going through the central emitter the other stubs cover."),
        entry(50618, "Stops the storefront price fetch for an application."),
        entry(50619, "Stops the storefront price fetch for SKU ids."),
        entry(50684, "Returns null from the animated decoration layer that plays over the profile " +
            "card, so the looping video or composite is never drawn. The profile picture " +
            "decoration is a different module and is untouched."),
        entry(50712, "Returns null from the collectible nameplate component, so no nameplate is " +
            "drawn."),
        entry(50717, "Returns null where SlayerStorefrontItemCard used to draw, so that surface " +
            "never appears."),
        entry(50897, "Stops the fetch of collections with their products."),
        entry(50969, "Rebuilds the You screen's floating navigation so the Quests button is never " +
            "built. The button is one element of a list rather than a function that can return " +
            "false, so the body is edited instead of stubbed."),
        entry(51310, "Answers zero from useShouldShowQuestsActivityPanelItem, so the Quests item " +
            "is not added to the activity panel."),
        entry(51322, "Stops the refresh of current quests that the UI drives on a one-second " +
            "timer while a quest is active."),
        entry(51323, "Stops the quest heartbeat being sent to the server."),
        entry(51332, "Stops the refresh of claimed quests."),
        entry(51333, "Stops the fetch of the quest waiting to be delivered."),
        entry(51334, "Stops the fetch of the earned quest waiting to be delivered."),
        entry(51873, "Does nothing, so the action handleShowUpsellAlert used to perform no longer " +
            "happens."),
        entry(51875, "Answers false, so whatever maybeOpenPremiumUpsellActionSheet gates stays " +
            "off."),
        entry(51876, "No-ops the action-sheet opener every upsell bottom sheet is launched " +
            "through, across its nine call sites."),
        entry(51878, "Returns null where PremiumUpsellHero used to draw, so that surface never " +
            "appears."),
        entry(51879, "Returns null where PremiumUpsellActionSheet used to draw, so that surface " +
            "never appears."),
        entry(51916, "Returns null where ScheduledMessageNitroUpsellBar used to draw, so that " +
            "surface never appears."),
        entry(52266, "Returns null where SuperReactionCoachmarkActionSheet used to draw, so that " +
            "surface never appears."),
        entry(52267, "No-ops the modal opener every premium upsell modal is launched through, " +
            "across its sixteen call sites."),
        entry(52318, "Returns null where PremiumUpsellTooltipActionSheet used to draw, so that " +
            "surface never appears."),
        entry(52400, "Does nothing, so the action handleShowEmojiUpsellAlert used to perform no " +
            "longer happens."),
        entry(52401, "Returns null where RoleSubscriptionEmojiUpsellAlert used to draw, so that " +
            "surface never appears."),
        entry(52405, "Returns null where GlobalEmojiUpsell used to draw, so that surface never " +
            "appears."),
        entry(52406, "Returns null where AnimatedEmojiUpsell used to draw, so that surface never " +
            "appears."),
        entry(52407, "Returns null where PremiumGuildIdentityUpsell used to draw, so that surface " +
            "never appears."),
        entry(52408, "Returns null where CustomProfilesUpsell used to draw, so that surface never " +
            "appears."),
        entry(52409, "Returns null where CustomAppIconsUpsell used to draw, so that surface never " +
            "appears."),
        entry(52410, "Returns null where LongerMessageUpsell used to draw, so that surface never " +
            "appears."),
        entry(52411, "Returns null where GuildCapUpsell used to draw, so that surface never " +
            "appears."),
        entry(52412, "Returns null where UploadUpsell used to draw, so that surface never " +
            "appears."),
        entry(52413, "Returns null where PremiumUpsellAlert used to draw, so that surface never " +
            "appears."),
        entry(52447, "Returns null where EmojiPickerListPremiumSearchUpsell used to draw, so that " +
            "surface never appears."),
        entry(52449, "Returns null where PremiumExpressionPickerSearchUpsell used to draw, so " +
            "that surface never appears."),
        entry(52467, "Returns null where PremiumFeatureUpsellPill used to draw, so that surface " +
            "never appears."),
        entry(52469, "Returns null where PremiumFeatureUpsell used to draw, so that surface never " +
            "appears."),
        entry(53085, "Removes the inline 'Get Nitro' button, the widest-reaching single upsell " +
            "component: eleven consumers, from the Go Live sheet to the sticker detail view."),
        entry(53092, "Returns null where StickersPremiumUpsellAlert used to draw, so that surface " +
            "never appears."),
        entry(53100, "Returns null where StickerPickerPremiumSearchUpsell used to draw, so that " +
            "surface never appears."),
        entry(53912, "Does nothing, so the action " +
            "openSocialLayerStorefrontUnsupportedOnMobileAlert used to perform no longer happens."),
        entry(53913, "Does nothing, so the action openSocialLayerStorefrontProductDetailsModal " +
            "used to perform no longer happens."),
        entry(53914, "Does nothing, so the action closeSocialLayerStorefrontProductDetailsModal " +
            "used to perform no longer happens."),
        entry(53915, "Does nothing, so the action openSocialLayerStorefrontGiftModal used to " +
            "perform no longer happens."),
        entry(53916, "Does nothing, so the action closeSocialLayerStorefrontGiftModal used to " +
            "perform no longer happens."),
        entry(53928, "Stops the storefront configuration fetch."),
        entry(53932, "Stops the storefront SKU lookup for an application."),
        entry(53947, "Returns null where SocialLayerStorefrontProductDetailsModal used to draw, " +
            "so that surface never appears."),
        entry(53948, "Returns null where WrappedSocialLayerStorefrontProductDetailsModal used to " +
            "draw, so that surface never appears."),
        entry(53997, "Returns null where SocialLayerStorefrontGiftModal used to draw, so that " +
            "surface never appears."),
        entry(63732, "Returns null where SocialLayerStorefrontGiftPurchaseSection used to draw, " +
            "so that surface never appears."),
        entry(54076, "Returns false from isGameActivity, the predicate in " +
            "modules/activities/utils/isGameActivity.tsx that every caller tests to ask whether " +
            "an activity is a game being played. The status drawn under a name, the activity list " +
            "on a profile, the label on a private channel and the You bar's accessibility label " +
            "all read it, so none of them finds a game to draw."),
        entry(54082, "Returns a falsy value from the one real Spotify gate. Every call site tests " +
            "it in an if, so this removes the remaining Spotify branding: the presence, 'Play on " +
            "Spotify', the Spotify embed in activity cards and the outbound Spotify activity."),
        entry(54103, "Returns null where LeaderboardWinnerBadge used to draw, so that surface " +
            "never appears."),
        entry(54211, "Does nothing, so the action openFavoritesGuildLimitUpsell used to perform " +
            "no longer happens."),
        entry(54212, "Returns null where FavoritesGuildUpsellSheet used to draw, so that surface " +
            "never appears."),
        entry(54214, "Returns null where PromoSheet used to draw, so that surface never appears."),
        entry(56116, "Returns null where OrbsBadgeCoachmarkImg used to draw, so that surface " +
            "never appears."),
        entry(56117, "Does nothing and returns undefined, so OrbsBadgeCoachmark no longer raises " +
            "its prompt."),
        entry(56120, "Does nothing and returns undefined, so TieredTenureBadgeCoachmark no longer " +
            "raises its prompt."),
        entry(56161, "Returns null where BadgeDetailsSheetContent used to draw, so that surface " +
            "never appears."),
        entry(56162, "Returns null where BadgeDetailsPage used to draw, so that surface never " +
            "appears."),
        entry(56163, "Returns null where BadgeDetailsSheet used to draw, so that surface never " +
            "appears."),
        entry(56599, "Does nothing, so the action openGroupDMNitroCapLimitSheet used to perform " +
            "no longer happens."),
        entry(56600, "Returns null where GroupDMNitroCapLimitSheet used to draw, so that surface " +
            "never appears."),
        entry(56602, "Does nothing, so the action navigateToNitroHomePage used to perform no " +
            "longer happens."),
        entry(56605, "Does nothing, so the action showChatGDMUpsellActionSheet used to perform no " +
            "longer happens."),
        entry(56606, "Returns null where ChatGDMUpsellActionSheet used to draw, so that surface " +
            "never appears."),
        entry(56663, "Returns null from the track renderer, so a friend's Spotify presence " +
            "renders nothing. Stubbing the listening check did not cover this, which is why other " +
            "people's songs stayed visible."),
        entry(56683, "Stops the subscription to Spotify player-state notifications, which polled " +
            "api.spotify.com on launch."),
        entry(56688, "Returns false from the check that asks the on-device Spotify app whether " +
            "Discord may talk to it over a local protocol. That path never touches the network, " +
            "so blocking Spotify hosts alone could not reach it; reporting it unregistered leaves " +
            "nothing to broadcast."),
        entry(59373, "Does nothing, so the action openGroupDMNitroCapInfoActionSheet used to " +
            "perform no longer happens."),
        entry(59374, "Returns null where GroupDMNitroCapInfoActionSheet used to draw, so that " +
            "surface never appears."),
        entry(61934, "Does nothing, so the action showNitroLockedToast used to perform no longer " +
            "happens."),
        entry(61987, "The bundle has no gift purchase button at this id: it is the uncompiled " +
            "variant of the useTypingUserIdsForDisplay hook in " +
            "modules/chat/native/TypingIndicator.tsx, and the React Compiler experiment defaults " +
            "off, so this is the variant the app runs. The stub returns an empty array, the " +
            "hook's own shape with no typing users, so the chat input's hasTypingIndicatorContent " +
            "read of its length finds 0 instead of throwing. The recorded build stubs this id to " +
            "undefined, which that read turns into a TypeError."),
        entry(62152, "Removes the chat-input button that is a gift button or a thread button " +
            "depending on the conversation."),
        entry(62154, "Removes the gift button in the chat input bar, which sat next to the " +
            "message box."),
        entry(62156, "Returns null where PremiumAnimatedGiftButton used to draw, so that surface " +
            "never appears."),
        entry(62695, "Returns null where EmojiPremiumUpsell used to draw, so that surface never " +
            "appears."),
        entry(62702, "Returns null where ChannelAutocompleteEmojiUpsell used to draw, so that " +
            "surface never appears."),
        entry(63160, "Returns null where GuildPowerupsBoostButton used to draw, so that surface " +
            "never appears."),
        entry(63349, "Does nothing, so the action dismissUpsellCTA used to perform no longer " +
            "happens."),
        entry(63297, "Returns null where GuildDirectoryNicknameUpsell used to draw, so that " +
            "surface never appears."),
        entry(63298, "Returns null where GuildDirectoryNicknameUpsellModal used to draw, so that " +
            "surface never appears."),
        entry(63735, "Returns null where SocialLayerStorefrontProductSelfPurchaseSuccessModal " +
            "used to draw, so that surface never appears."),
        entry(63736, "Returns null where SocialLayerStorefrontProductGiftPurchaseSuccessModal " +
            "used to draw, so that surface never appears."),
        entry(63782, "Returns null where SocialLayerStorefrontWishlistItemCard used to draw, so " +
            "that surface never appears."),
        entry(64002, "Returns null where GroupDMNitroCapCoachmark used to draw, so that surface " +
            "never appears."),
        entry(64043, "Returns null where NitroLimitUpsellBar used to draw, so that surface never " +
            "appears."),
        entry(64054, "Does nothing, so the action openScheduledMessagesLimitUpsell used to " +
            "perform no longer happens."),
        entry(64251, "Removes the modal that awards orbs for a completed quest."),
        entry(64273, "Removes the button that opens the collectibles shop."),
        entry(64274, "Removes the coachmark that points at the mobile shop button."),
        entry(64285, "Returns null where GuildProfileCreateTagUpsell used to draw, so that " +
            "surface never appears."),
        entry(64333, "The same orb gate under its plain name. Hermes deduplicated the two exports " +
            "into one body, so patching either one turns orbs off; the alias set is verified on " +
            "every run."),
        entry(64334, "Returns {enabled: false} from the gate every orb surface reads, which " +
            "removes orbs at the source rather than hiding each screen that shows them. Hermes " +
            "shares this body with isVirtualCurrencyEnabled, so that name is disabled with it."),
        entry(64422, "Stubs the Shop This Look action sheet, so it opens empty if its opener " +
            "still runs. The opener is left as it is because its body is shared with an unrelated " +
            "display-name feature."),
        entry(64437, "Stubs the Shop This Look marketing coachmark, so the prompt that points at " +
            "the feature is not built. It lives outside the shop screen, so stubbing the screen " +
            "did not remove it."),
        entry(64616, "The bundle has no quest orb shop carousel at this id: it is the compiled " +
            "variant of the badge row in modules/badges/native/BadgeDirectoryNuxCoachmark.tsx, " +
            "which renders the game-time, streaming and game-diversity tier badges. The app " +
            "selects that variant only when the React Compiler experiment is enabled, and this " +
            "build leaves the experiment off by default, so the stub changes nothing for a stock " +
            "client."),
        entry(64618, "Returns null and never raises its coachmark, so BadgeDirectoryNuxCoachmark " +
            "shows nothing."),
        entry(64806, "Forces the wishlist tab index to -1, so the profile's segmented control has " +
            "no Wishlist tab to select."),
        entry(64825, "Returns null from the wishlist grid a profile renders, so the grid of " +
            "wishlisted items shown as a profile section is not drawn. This is a different " +
            "surface from the profile's Wishlist tab."),
        entry(64834, "Returns null from the wishlist suggestions grid a profile renders, so the " +
            "suggested-items grid is not drawn. The component already returns null when its own " +
            "mobile gate is off, so callers handle the shape."),
        entry(64839, "Removes the add-to-wishlist button in the grid layout."),
        entry(64840, "Removes the add-to-wishlist button on item cards."),
        entry(64893, "Returns null where FractionalNitroPreview used to draw, so that surface " +
            "never appears."),
        entry(64905, "Returns null where ExpressiveNitroUpsell used to draw, so that surface " +
            "never appears."),
        entry(64903, "Removes the orb price tag shown on items that can be bought with orbs."),
        entry(64906, "Returns null where NitroUpsell used to draw, so that surface never appears."),
        entry(64910, "Returns null where ShopNitroUpsellPromoSheet used to draw, so that surface " +
            "never appears."),
        entry(64917, "Removes the standalone gift button component, one of the separate " +
            "components stubbing the inline Nitro button did not cover."),
        entry(64941, "Returns null where NitroAcquiredHeader used to draw, so that surface never " +
            "appears."),
        entry(64942, "Returns null where FractionalNitroCollectedActionSheet used to draw, so " +
            "that surface never appears."),
        entry(64945, "Returns null where UnlockWithNitroButton used to draw, so that surface " +
            "never appears."),
        entry(65195, "Returns null where NitroOrbsDeliveredModal used to draw, so that surface " +
            "never appears."),
        entry(65215, "Returns null where PremiumPlanWhatYouLoseActionSheet used to draw, so that " +
            "surface never appears."),
        entry(65249, "Returns null where NitroHomeHeader used to draw, so that surface never " +
            "appears."),
        entry(65252, "Returns null where PremiumNitroHome used to draw, so that surface never " +
            "appears."),
        entry(65322, "Returns null where PremiumMarketingPage used to draw, so that surface never " +
            "appears."),
        entry(65392, "Returns null where PremiumMarketingFloatingSubscribeButton used to draw, so " +
            "that surface never appears."),
        entry(65419, "Returns null where PremiumGroupEducationActionSheet used to draw, so that " +
            "surface never appears."),
        entry(65422, "Returns null where NitroCreditEducationActionSheet used to draw, so that " +
            "surface never appears."),
        entry(65427, "Returns null where GuildBoostingUpsell used to draw, so that surface never " +
            "appears."),
        entry(65433, "Returns null where PremiumSubscriptionPricingUpsell used to draw, so that " +
            "surface never appears."),
        entry(65436, "Returns null where PremiumSubscriptionUpsell used to draw, so that surface " +
            "never appears."),
        entry(65437, "Returns null where BoostPurchaseNitroBanner used to draw, so that surface " +
            "never appears."),
        entry(65459, "Returns null where PremiumPlanSelectYearlyUpsellModal used to draw, so that " +
            "surface never appears."),
        entry(65463, "Returns null where OutboundPromotionCard used to draw, so that surface " +
            "never appears."),
        entry(65465, "Returns null where OutboundPromotionClaimAlert used to draw, so that " +
            "surface never appears."),
        entry(65479, "Removes the gift purchase button, so a gift cannot be bought from the UI."),
        entry(65738, "Stops the gateway READY payload being logged: the payload carries the whole " +
            "guild list, and it is recorded being stringified around fourteen times on one " +
            "connect in a hundred."),
        entry(65739, "Stops the connection-path lookup used to label the gateway analytics event."),
        entry(65740, "Stops the READY payload being measured for its analytics byte size."),
        entry(65741, "Stops the gateway-connected analytics event."),
        entry(66787, "Returns null where ContactSyncUpsellCTA used to draw, so that surface never " +
            "appears."),
        entry(67877, "Stops the analytics action handler, which built an event with its key, " +
            "properties, fingerprint and timestamp for every tracked action."),
        entry(68751, "Stubs the Edit User Profile entry in settings."),
        entry(68760, "Returns null where UserProfileUpsellButton used to draw, so that surface " +
            "never appears."),
        entry(68836, "Does nothing, so openCustomizeBadgesSheet never opens the customization " +
            "sheet."),
        entry(68837, "Returns null where BadgeVisibilityMenu used to draw, so that surface never " +
            "appears."),
        entry(68841, "Returns null where CustomizeBadgesSheet used to draw, so that surface never " +
            "appears."),
        entry(68843, "Returns null where UserProfileUpsellCard used to draw, so that surface " +
            "never appears."),
        entry(68940, "Returns null where UserProfilePremiumTryItOutUpsell used to draw, so that " +
            "surface never appears."),
        entry(68942, "Returns null where PreviewNitroCard used to draw, so that surface never " +
            "appears."),
        entry(68943, "Returns null where GetNitroCard used to draw, so that surface never " +
            "appears."),
        entry(68945, "Returns null where UserProfilePremiumUpsellCard used to draw, so that " +
            "surface never appears."),
        entry(68924, "Returns null where UserProfileFloatingUpsell used to draw, so that surface " +
            "never appears."),
        entry(68963, "Returns null where PasswordlessUpsell used to draw, so that surface never " +
            "appears."),
        entry(69438, "Returns null where ProfileToActivityUpsellActionSheet used to draw, so that " +
            "surface never appears."),
        entry(69441, "Returns null where BaseUpsellActionSheet used to draw, so that surface " +
            "never appears."),
        entry(69626, "Returns null where XboxTwoWayLinkUpsell used to draw, so that surface never " +
            "appears."),
        entry(69628, "Returns null where OneWayToTwoWayLinkUpsell used to draw, so that surface " +
            "never appears."),
        entry(69629, "Returns null where PlayStationTwoWayLinkUpsell used to draw, so that " +
            "surface never appears."),
        entry(17034, "Gives the Manage Subscriptions settings route the always-false visibility " +
            "predicate that hides its row. The 354.1 bundle dropped the named predicate wrapper " +
            "this setting used, so the route body is edited instead."),
        entry(69713, "Returns false from the Gift Inventory row's own visibility predicate."),
        entry(69718, "Returns false from the Quests settings row's own visibility predicate. This " +
            "is what actually removes the row: the settings harness hides any entry whose " +
            "predicate returns exactly false."),
        entry(69721, "Stubs the Quest Home screen the Quests settings row navigates to."),
        entry(69779, "Returns null from the Quest Home screen, so a route that still navigates " +
            "there renders nothing. Every mobile entry point is gated separately; this covers the " +
            "screen itself, which a route name can still reach."),
        entry(70072, "Returns false from the mobile quest-dock hook, so the dock is not shown. " +
            "The broad quest-eligibility check is the other lever, but its body is shared with " +
            "six unrelated capability checks and cannot be patched."),
        entry(70458, "Returns false from the Server Subscriptions row's visibility predicate."),
        entry(70577, "Returns false from the Restore Subscription row's visibility predicate."),
        entry(70684, "Returns null where SettingsAppearanceChannelListPreviewNitroUpsellComponent " +
            "used to draw, so that surface never appears."),
        entry(70685, "Returns null where SettingsAppearanceChannelListPreviewNitroUpsell used to " +
            "draw, so that surface never appears."),
        entry(70890, "Returns null where VideoUploadQualityNitroUpsell used to draw, so that " +
            "surface never appears."),
        entry(71438, "Does nothing, so the action openPasskeyUpsell used to perform no longer " +
            "happens."),
        entry(71439, "Does nothing, so the action openPasskeyUpsellPromoSheet used to perform no " +
            "longer happens."),
        entry(71440, "Does nothing, so the action closePasskeyUpsellPromoSheet used to perform no " +
            "longer happens."),
        entry(71441, "Returns null where PasswordlessUpsellPromoSheet used to draw, so that " +
            "surface never appears."),
        entry(71473, "Returns null where PremiumTrialOfferActionSheetContent used to draw, so " +
            "that surface never appears."),
        entry(71490, "Removes the You-tab orb balance widget's menu, one of the screens the orb " +
            "gate sits in front of."),
        entry(71491, "Removes the wrapper component that builds the orb balance widget."),
        entry(71519, "Stops the survey override from taking effect, so a server-side override " +
            "cannot swap the app-rating survey the client is showing."),
        entry(71520, "Stops the survey-dismiss action, which both updated the store and emitted " +
            "its APP_NOTICE analytics event."),
        entry(71521, "Stops the survey-seen report, which told Discord the survey had been shown " +
            "and emitted analytics for it."),
        entry(68928, "Returns null where UserProfileTryItOutGetPremiumUpsell used to draw, so " +
            "that surface never appears."),
        entry(71771, "Stubs the collectibles shop screen component the Settings route renders, so " +
            "the route draws nothing. The shop also has a deep-link entry the route edit cannot " +
            "close, which is why the screen component is stubbed."),
        entry(71776, "Stubs the shop screen's internal component, leaving the screen with nothing " +
            "to render."),
        entry(71774, "Stubs the collectibles shop screen itself, so there is no shop tab to " +
            "browse."),
        entry(71791, "Returns null where ShopNitroUpsellBanner used to draw, so that surface " +
            "never appears."),
        entry(71873, "Returns false from the usePredicate closure the Quests toggles in Data & " +
            "Privacy carry, so those settings rows are hidden. The two toggles' predicates are " +
            "byte-identical and Hermes stores them once, so either id changes both."),
        entry(71882, "Returns false from the usePredicate closure the Quests toggles in Data & " +
            "Privacy carry, so those settings rows are hidden. The two toggles' predicates are " +
            "byte-identical and Hermes stores them once, so either id changes both."),
        entry(72026, "Returns null where ActivityPrivacyUpsellActionSheet used to draw, so that " +
            "surface never appears."),
        entry(72648, "Returns null from the voice guild tag component, so no guild tag chip is " +
            "drawn in a voice channel."),
        entry(72749, "Does nothing and returns undefined, so BoostProgressBarCoachmark no longer " +
            "raises its prompt."),
        entry(72751, "Does nothing and returns undefined, so GuildPowerupsBoostToUnlockCoachmark " +
            "no longer raises its prompt."),
        entry(72915, "Returns null where GuildRoleSubscriptionsUpsellActionSheet used to draw, so " +
            "that surface never appears."),
        entry(72916, "Returns null where IAPUpsellActionSheet used to draw, so that surface never " +
            "appears."),
        entry(72917, "Returns null where GenericUpsellActionSheet used to draw, so that surface " +
            "never appears."),
        entry(72918, "Returns null where CreatorMonetizationOnboardingV2UpsellActionSheet used to " +
            "draw, so that surface never appears."),
        entry(72919, "Returns null where GuildRoleSubscriptionTierTemplateUpsellActionSheet used " +
            "to draw, so that surface never appears."),
        entry(72922, "Answers false, so whatever useIsGuildEligibleForRoleSubscriptionsUpsell " +
            "gates stays off."),
        entry(72936, "Returns null where GuildUpsellChannelList used to draw, so that surface " +
            "never appears."),
        entry(72945, "Returns null where OneColumnGuildUpsellList used to draw, so that surface " +
            "never appears."),
        entry(72946, "Returns null where GameCommunityMultiGuildUpsellCard used to draw, so that " +
            "surface never appears."),
        entry(73979, "Returns null where GroupDMNitroUpsellBanner used to draw, so that surface " +
            "never appears."),
        entry(73980, "Returns null where GroupDMNitroCapBanner used to draw, so that surface " +
            "never appears."),
        entry(74076, "Stops the AutoAnalytics navigator hook that emitted an event each time a " +
            "screen was built."),
        entry(74077, "Stops the other half of the AutoAnalytics navigator hook, which emitted an " +
            "event each time the current screen changed."),
        entry(74169, "Returns null and never raises its coachmark, so " +
            "BadgeCustomizationProfileCoachmark shows nothing."),
        entry(74171, "Answers false, so whatever useShouldShowExpiringTrialOfferCard gates stays " +
            "off."),
        entry(74172, "Returns null where YouExpiringTrialOfferCard used to draw, so that surface " +
            "never appears."),
        entry(74525, "Returns null where AppIconsCoachmarkActionSheet used to draw, so that " +
            "surface never appears."),
        entry(74526, "Returns null where GiftingPromotionCoachmarkActionSheet used to draw, so " +
            "that surface never appears."),
        entry(74538, "Returns null where PremiumMarketingMomentActionSheet used to draw, so that " +
            "surface never appears."),
        entry(74540, "Returns null where PremiumDiscountOfferActionSheetContent used to draw, so " +
            "that surface never appears."),
        entry(74544, "Returns null where HasBadgeCoachmark used to draw, so that surface never " +
            "appears."),
        entry(74545, "Returns null where NewBadgeCoachmark used to draw, so that surface never " +
            "appears."),
        entry(74546, "Returns null where GiftingBadgesCoachmarkActionSheet used to draw, so that " +
            "surface never appears."),
        entry(74549, "Returns null where CollectiblesMobileAnnouncementActionSheet used to draw, " +
            "so that surface never appears."),
        entry(74551, "Returns null where NitroFileUploadAnnouncementPromoSheet used to draw, so " +
            "that surface never appears."),
        entry(74553, "Returns null where NitroFileUploadUpsellPromoSheet used to draw, so that " +
            "surface never appears."),
        entry(74806, "Returns null where PremiumSoundboardFeatureUpsell used to draw, so that " +
            "surface never appears."),
        entry(74975, "Returns null from the voice panel's games section, the copy of " +
            "modules/voice_panel/native/prejoin/VoicePanelGamesSection.tsx this build runs. The " +
            "section is the container the games list is drawn inside, so stubbing the list alone " +
            "would have left the container on screen; null removes both."),
        entry(75145, "Stops the per-request tracker, which ran URL matching and appended to the " +
            "telemetry ring for every HTTP request."),
        entry(75337, "Stops _trackStartSpeaking from computing game metadata and packet stats " +
            "when someone starts speaking in a voice call."),
        entry(75338, "Stops _trackStartListening from computing telemetry payloads when someone " +
            "starts listening in a voice call."),
        entry(75445, "Does nothing, so the action openDmSettingsUpsellModal used to perform no " +
            "longer happens."),
        entry(75446, "Returns null where DmSettingsUpsellActionSheet used to draw, so that " +
            "surface never appears."),
        entry(76247, "Returns null where GuildSettingsServerTagUpsellCard used to draw, so that " +
            "surface never appears."),
        entry(76740, "Returns null where GuildPremiumRoleSubscribeButton used to draw, so that " +
            "surface never appears."),
        entry(77056, "Stops the app-state update handler listed with the message-cache recorders, " +
            "so it no longer runs on every app state change."),
        entry(77122, "Stops the patched global WebSocket being installed, whose per-message " +
            "handler parsed every gateway frame a second time and appended it to the disabled " +
            "telemetry ring."),
        entry(77228, "Stops the timer monitor that formatted slow-timer records into the log, " +
            "which only the stubbed analytics emitter read."),
        entry(77289, "Silences one of the logger's debug levels, so those lines no longer enter " +
            "the in-memory JavaScript debug log. Only warn and error are kept, so genuine " +
            "problems still surface."),
        entry(77290, "Silences log(), which is where most of the in-memory debug log was coming " +
            "from."),
        entry(77291, "Silences the logger's verbose level (the 'dangerously' variant), keeping it " +
            "out of the debug log."),
        entry(77292, "Silences verbose, one of the two levels that dominated the in-memory debug " +
            "log."),
        entry(77293, "Silences info, so informational lines no longer enter the debug log."),
        entry(77296, "Silences trace, so trace lines no longer enter the debug log."),
        entry(77298, "Silences the file-only logger, so nothing is written to the logger's file " +
            "sink."),
        entry(81313, "Silences the central science-event emitter every feature calls, so no event " +
            "is built or sent and no per-event CPU/memory sample is taken. Its callers await it, " +
            "so the stub still returns a resolved promise."),
        entry(81318, "Stops the single POST behind the /science and /beaker queues, so the events " +
            "the stubs above would have queued are never uploaded either."),
        entry(81440, "Stops the once-a-second process sampler, which made three native bridge " +
            "calls per second for CPU and memory whose only consumers null-guard."),
        entry(88602, "Stops the voice-quality payload builder that assembled and sent its " +
            "statistics every five minutes."),
        entry(88911, "Stops the voice-quality sampler that ran once a second during a call."),
        entry(88931, "Stops the system-responsiveness sampler, the second of the voice-call " +
            "samplers."),
        entry(88987, "Stops the video-effect and system-resource sampler that ran on every " +
            "call-statistics callback."),
        entry(103875, "Stops the 60-second session flusher that shipped session aggregates."),
        entry(103986, "Stops the metrics aggregator adding samples to its buckets."),
        entry(103989, "Stops the metrics aggregator flushing its buckets on its interval."),
        entry(103990, "Stops the metrics aggregator capturing a metrics snapshot."),
        entry(104011, "Stops the browser-side metrics aggregator adding samples to its buckets."),
        entry(104012, "Stops the browser-side metrics aggregator flushing them."),
        entry(129392, "Rebuilds the profile activity list without friends' Spotify " +
            "listened-session entries. The bundle has a second copy of the filter in the same " +
            "module, reachable through a different parent, and both are edited so Spotify entries " +
            "do not survive on some surfaces."),
        entry(107579, "Rebuilds other users' profile tab list so it builds Main and Activity " +
            "only, with no Board and no Wishlist tab. It edits the isReactCompilerEnabled() == " +
            "false variant of the profile content component."),
        entry(115638, "Rebuilds the profile section tab list so it builds Main and Activity only, " +
            "with no Board and no Wishlist tab. The tab is one element of a list, not a function " +
            "that can return false, so the body is edited rather than stubbed."),
        entry(118774, "Stops QuestFetchManager installing the recurring interval that refetched " +
            "quests forever, which against already-blocked endpoints was pure timer and network " +
            "churn."),
        entry(133768, "Stops the 60-second voice state interval callback that repeatedly " +
            "dispatches speaking and listening telemetry during calls."),
        entry(33796, "Answers false from isBlockedForMessage, so an already-received message from " +
            "a blocked user is drawn expanded instead of behind the blocked-message notice."),
        entry(38394, "Answers false from shouldAnimateSticker, so sticker previews and chat " +
            "images stay still instead of animating."),
        entry(48637, "Answers true from computeGlobalSpoilerDisplay, so spoiler text and media " +
            "are drawn revealed. Ported from Reseam's Reveal spoilers patch."),
        entry(48638, "Answers false from the spoiler-obscurity hook, so this copy of the chat row " +
            "does not obscure a spoiler. Ported from Reseam's Reveal spoilers patch."),
        entry(48945, "Answers true from isLinkTrusted, so a masked link opens without the " +
            "destination warning. Ported from Reseam's Skip masked link warnings patch."),
        entry(52649, "Answers true from isLinkTrusted, so a masked link opens without the " +
            "destination warning. Ported from Reseam's Skip masked link warnings patch."),
        entry(47120, "Rebuilds createMessageContent with reaction chips, gift cards and activity " +
            "invites turned off, and with custom emoji and GIFs not animating. Embed and link " +
            "cards and reply previews stay on, so a message still shows what it answers. The " +
            "option reads at the top of the function are forced false in place; the rest of the " +
            "renderer is unchanged."),
        entry(52317, "Returns null where SuperReactionLocalImageAnimation used to draw, so Super " +
            "Reaction decorations do not loop."),
        entry(56346, "Answers false from useIsMessageSwipeActionsEnabled, so swiping a message no " +
            "longer starts a reply or an edit."),
        entry(62001, "Answers false from handleTypingStartLocal, so the app does not send a " +
            "typing status when you type or record."),
        entry(70671, "Returns null where ActivityCardsItem used to draw, so the Appearance " +
            "preview's activity cards never appear."),
        entry(52842, "Starts a reply with mentions turned off and the mention toggle shown, so a " +
            "reply does not ping the person it answers."),
        entry(52843, "Starts a shallow reply with mentions turned off and the mention toggle " +
            "shown, so the same rule applies to the short-lived reply state."),
        entry(67069, "Answers true from canStreamQuality, so screen sharing can use the high and " +
            "mid stream qualities Nitro otherwise gates."),
        entry(67057, "Answers true from canUseHighVideoUploadQuality, so media prepares at the " +
            "higher upload quality; upload limits are unchanged."),
        entry(51231, "Answers false from getIsEligibleForQuests, so no surface treats this " +
            "account as eligible to show quests."),
        entry(70401, "Returns null where QuestDockWithVisibilityContext used to draw, so the " +
            "mobile quest dock is never mounted."),
        entry(74713, "Returns null where QuestActivityButton used to draw, so the quest button is " +
            "not rendered."),
        entry(70068, "Answers false from useIsMobileQuestDockRenderedBase, so the rendered-dock " +
            "check never turns the quest dock on."),
        entry(70070, "Returns zero from useMobileQuestDockHeight, so the quest dock reserves no " +
            "height and cannot appear."),
        entry(42963, "Returns null where the persistent boost marketing call to action used to " +
            "draw, so it is not shown on any screen."),
        entry(63093, "Returns null where GuildPowerupsBoostInfo used to draw, removing the boost " +
            "count and boost icon from the progress bar."),
        entry(65413, "Returns null where BoostedGuildTierProgressCircle used to draw, so the tier " +
            "progress ring is not rendered."),
        entry(65491, "Returns null where the boost marketing call-to-action bar used to draw, so " +
            "the upgrade banner does not appear."),
        entry(72862, "Returns null from the channel row indicator, so boost notifications and the " +
            "unread boost badge never appear beside a channel."),
        entry(72863, "Returns null from the coachmark host, so the boost coachmark is never set " +
            "up or shown."),
        entry(72864, "Returns null where GuildPowerupsCoachmark used to draw, so the boost " +
            "coachmark itself never appears."),
        entry(72865, "Returns null where GuildPowerupsChannelRow used to draw, so the boost " +
            "progress row is never added to the channel list."),
        entry(72867, "Returns null where GuildPowerupsProgressBar used to draw, so the boost " +
            "progress bar above the channel list is not rendered."),
        entry(75994, "Returns null from renderBoostProgressBar, so the Settings overview does not " +
            "show its boost progress bar toggle row."),
        entry(40899, "Returns null where TableRowDivider used to draw, so the line between table " +
            "rows is not rendered."),
        entry(72530, "Returns zero from getMessagesItemHappeningNowHeight, so a direct-message " +
            "list reserves no space for the activity cards."),
        entry(72535, "Returns null where HappeningNow used to draw, so the activity cards above a " +
            "direct message are not rendered."),
        entry(49918, "Returns null where VoicePanelAudioConsoleSection used to draw, so the " +
            "console section does not appear in the audio output sheet."),
        entry(75018, "Returns null where VoicePanelFloatingCTAContainer used to draw, so the Add " +
            "people banner is not shown on the call screen."),
        entry(72860, "Returns null where ConjureChannelRow used to draw, so the Conjure row is " +
            "not added to the channel list."),
        entry(73178, "Returns null where ConjureYouBarButton used to draw, so the Conjure button " +
            "is not added to the you bar."),
        entry(38022, "Returns null from useDisplayNameStyles, so collectible display-name colors, " +
            "fonts and effects are not applied to names."),
        entry(34857, "Answers false from useServerThemeUserEnabled, so a server theme is not " +
            "enabled for this account."),
        entry(34856, "Answers false from getServerThemeUserEnabled, so a server theme is not " +
            "applied even where the hook is not used."),
        entry(63946, "Answers false from shouldShowEmojiRow, so the reaction row is not shown at " +
            "the top of a message's long-press menu."),
        entry(48903, "Answers false from getICYMIEnabled, so the ICYMI feed and the controls that " +
            "open it are disabled."),
        entry(48904, "Answers false from useICYMIEnabled, so a screen that reads the hook does " +
            "not treat the ICYMI feed as enabled."),
        entry(67024, "Returns null where ContextMenuDivider used to draw, so its context-menu " +
            "separator is not rendered."),
        entry(40887, "Returns null where FormSeparator used to draw, so its form separator is not " +
            "rendered."),
        entry(49512, "Returns null where FormSeparator used to draw, so its form separator is not " +
            "rendered."),
        entry(70499, "Returns null where FormSeparator used to draw, so its form separator is not " +
            "rendered."),
        entry(71824, "Returns null where ListItemSeparator used to draw, so its list-row " +
            "separator is not rendered."),
        entry(69896, "Returns null where ItemSeparator used to draw, so its item separator is not " +
            "rendered."),
        entry(72583, "Returns null where DottedLineSeparator used to draw, so its dotted " +
            "separator line is not rendered."),
        entry(76782, "Returns null where SectionSeparator used to draw, so its section separator " +
            "is not rendered."),
        entry(73861, "Returns null where MessageVerticalSeparator used to draw, so its " +
            "message-action separator is not rendered."),
        entry(73862, "Returns null where MediaVerticalSeparator used to draw, so its media-action " +
            "separator is not rendered."),
        entry(73863, "Returns null where CardVerticalSeparator used to draw, so its card-action " +
            "separator is not rendered."),
        entry(52927, "Returns null where AutocompleteFormDivider used to draw, so its " +
            "autocomplete form divider is not rendered."),
        entry(64067, "Returns null where AuthorizeFormSeparator used to draw, so its " +
            "authorization form separator is not rendered."),
        entry(73074, "Returns null where GuildsBarSeparator used to draw, so its server-bar " +
            "separator is not rendered."),
        entry(73072, "Returns null where GuildsBarHomeDrawerSeparator used to draw, so its " +
            "home-drawer separator is not rendered."),
        entry(72800, "Answers false from getChannelListSectionHasFooterDivider, so a channel-list " +
            "section does not draw its footer divider line."),
        entry(69868, "Returns null where QuestHomeBounties used to draw, so the Bounties section " +
            "is not added to Quest Home."),
        entry(62198, "Returns null where JumpToPresentButton used to draw, so the jump-to-present " +
            "button is not shown in a chat."),
        entry(46087, "Returns null where Divider used to draw, so the generic form divider line " +
            "is not rendered."),
        entry(73082, "Returns null where GuildsBarEmptyNUX used to draw, so the empty server bar " +
            "does not show its onboarding hint."),
        entry(68879, "Returns null where the premium try-it-out section used to draw, so that surface never appears."),
        entry(68920, "Returns null where the premium try-it-out modal used to draw, so that surface never appears."),
        entry(68919, "Does nothing, so the premium try-it-out opener no longer opens the modal."),
        entry(74554, "Returns null where the referral-programme entry sheet used to draw, so that surface never appears."),
        entry(69938, "Returns null where the Orbs Arcade quest-home section used to draw, so that surface never appears."),
        entry(69908, "Returns null where the Hexagon campaign quest-home header used to draw, so that surface never appears."),
        entry(71084, "Returns null where the Hexagon campaign app-icon section used to draw, so that surface never appears."),
        entry(68830, "Returns null where the Hexagon display-name edit button used to draw, so that surface never appears."),
        entry(68826, "Returns null where the Hexagon display-name promo used to draw, so that surface never appears."),
        entry(69911, "Returns null where the Hexagon campaign sheet used to draw, so that surface never appears."),
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
            label = "Games friends are playing",
            description = "Removes the game a friend is playing from their status line, profile " +
                "activity list and voice channel. Turn this switch off to see games again.",
            functionIds = listOf(50106, 54076, 74975)
        ),
        Feature(
            slug = "nitro_promotions",
            group = SHOP_GROUP,
            label = "Nitro promotions",
            description = "Removes the buttons, sheets and dialogs that advertise Nitro, " +
                "including the inline Get Nitro button in the chat bar.",
            functionIds = listOf(51876, 52267, 53085)
        ),
        Feature(
            slug = "collectibles_shop",
            group = SHOP_GROUP,
            label = "The collectibles shop",
            description = "Stops the shop screen drawing, so no tab or route opens it.",
            functionIds = listOf(71771, 71776, 71774)
        ),
        Feature(
            slug = "shop_entry_points",
            group = SHOP_GROUP,
            label = "Shop buttons and prompts",
            description = "Removes the button that opens the shop, the prompt that points at it, " +
                "and the settings row that leads there.",
            functionIds = listOf(17970, 64273, 64274)
        ),
        Feature(
            slug = "shop_this_look",
            group = SHOP_GROUP,
            label = "Shop This Look",
            description = "Answers no to the experiment that turns the feature on, so the row " +
                "that opens the sheet is never built, and empties the sheet and its prompt as " +
                "well for any path that reaches them.",
            functionIds = listOf(48465, 64422, 64437)
        ),
        Feature(
            slug = "game_profile_shop",
            group = SHOP_GROUP,
            label = "Game profile shop",
            description = "Stops the shop carousel drawing on a game's profile and removes the " +
                "guild sidebar's row for it.",
            functionIds = listOf(41991, 50529)
        ),
        Feature(
            slug = "guild_shop",
            group = SHOP_GROUP,
            label = "Guild shop",
            description = "Hides the guild shop and its preview by answering false to every check " +
                "that decides whether to show one.",
            functionIds = listOf(42256, 42257, 42258)
        ),
        Feature(
            slug = "orbs",
            group = SHOP_GROUP,
            label = "Orbs",
            description = "Turns orbs off at the gate every orb surface reads, and removes the " +
                "orb balance, price tags and reward dialogs.",
            functionIds = listOf(64251, 64333, 64334, 64903, 71490, 71491, 69938)
        ),
        Feature(
            slug = "storefront",
            group = SHOP_GROUP,
            label = "Store requests",
            description = "Stops the app fetching store products, prices and collections, and the " +
                "guild data it loaded on every connection.",
            functionIds = listOf(48471, 48843, 50618, 50619, 50897, 53928, 53932)
        ),
        Feature(
            slug = "gift_buttons",
            group = GIFTS_GROUP,
            label = "Gift buttons",
            description = "Removes the gift button from the chat bar and the button that buys a " +
                "gift.",
            functionIds = listOf(62152, 62154, 62156, 64917, 65479)
        ),
        Feature(
            slug = "billing_rows",
            group = BILLING_GROUP,
            label = "Subscription and billing rows",
            description = "Hides the settings rows for Nitro, managing a plan or subscription, " +
                "server boosts and subscriptions, the gift inventory and restoring a purchase.",
            functionIds = listOf(17026, 17032, 17039, 17034, 69713, 70458, 70577)
        ),
        Feature(
            slug = "avatar_decorations",
            group = LOOK_GROUP,
            label = "Avatar decorations",
            description = "Stops avatars carrying decorations, at the one transform every caller " +
                "passes a decoration through.",
            functionIds = listOf(27751)
        ),
        Feature(
            slug = "nameplates",
            group = LOOK_GROUP,
            label = "Nameplates",
            description = "Stops the collectible nameplate drawing next to a name.",
            functionIds = listOf(50712)
        ),
        Feature(
            slug = "profile_frames",
            group = LOOK_GROUP,
            label = "Profile frames",
            description = "Stops the collectible profile frame drawing around an avatar.",
            functionIds = listOf(48497)
        ),
        Feature(
            slug = "guild_tags",
            group = LOOK_GROUP,
            label = "Guild tags",
            description = "Stops guild tag chips, badges and voice-channel tags drawing, and " +
                "answers false to the checks that decide whether to show one.",
            functionIds = listOf(48277, 48278, 50111, 50112, 50113, 72648)
        ),
        Feature(
            slug = "profile_badges",
            group = PROFILE_GROUP,
            label = "Profile badges",
            description = "Returns no badges from the profile badge hook, so the badge row has " +
                "nothing to draw.",
            functionIds = listOf(48538)
        ),
        Feature(
            slug = "profile_effects",
            group = PROFILE_GROUP,
            label = "Animated profile effects",
            description = "Stops the animated layer that plays over a profile card.",
            functionIds = listOf(50684)
        ),
        Feature(
            slug = "edit_profile_row",
            group = PROFILE_GROUP,
            label = "The edit profile row",
            description = "Removes the Edit User Profile entry from settings.",
            functionIds = listOf(68751)
        ),
        Feature(
            slug = "quests_in_app",
            group = QUESTS_GROUP,
            label = "Quests in the app",
            description = "Removes the Quests screen, its dock, its navigation button, its " +
                "settings row and its toggles in Data & Privacy.",
            functionIds = listOf(
                50969, 51310, 69718, 69721, 69779, 70072, 71873, 71882, 51231, 70401, 74713, 70068,
                70070
            )
        ),
        Feature(
            slug = "quest_fetching",
            group = QUESTS_GROUP,
            label = "Quest fetching",
            description = "Stops the app fetching, refreshing and re-polling quests in the " +
                "background.",
            functionIds = listOf(51322, 51323, 51332, 51333, 51334, 118774)
        ),
        Feature(
            slug = "wishlist_tabs",
            group = WISHLIST_GROUP,
            label = "Wishlist tabs",
            description = "Removes the Wishlist and Board tabs from your profile and from other " +
                "people's, in both copies of each profile component, the copy the app runs by " +
                "default and the React Compiler copy, so the removal holds whichever copy the " +
                "experiment selects.",
            functionIds = listOf(64806, 107579, 115638)
        ),
        Feature(
            slug = "wishlist_grids",
            group = WISHLIST_GROUP,
            label = "Wishlist grids and buttons",
            description = "Stops the wishlist and suggestion grids drawing, and removes the " +
                "buttons that add an item to a wishlist.",
            functionIds = listOf(64825, 64834, 64839, 64840)
        ),
        Feature(
            slug = "spotify",
            group = SPOTIFY_GROUP,
            label = "Spotify integration",
            description = "Removes Spotify from the app: the presence a friend shows, the track " +
                "renderer, the player subscription and the listened-session entries on a profile.",
            functionIds = listOf(54082, 56663, 56683, 56688, 129392)
        ),
        Feature(
            slug = "rating_prompts",
            group = SURVEYS_GROUP,
            label = "App rating prompts",
            description = "Stops the app asking you to rate it, and the reports that told Discord " +
                "the prompt was shown or dismissed.",
            functionIds = listOf(45503, 71519, 71520, 71521)
        ),
        Feature(
            slug = "analytics_events",
            group = ANALYTICS_GROUP,
            label = "Analytics events",
            description = "Stops analytics events being built and sent, at the emitters every " +
                "feature calls and at the single request behind them.",
            functionIds = listOf(25650, 25713, 25721, 67877, 81313, 81318)
        ),
        Feature(
            slug = "screen_tracking",
            group = ANALYTICS_GROUP,
            label = "Screen and navigation tracking",
            description = "Stops the event sent each time a screen is built and the per-request " +
                "tracker that matched URLs for it.",
            functionIds = listOf(36098, 74076, 74077, 75145)
        ),
        Feature(
            slug = "voice_tracking",
            group = ANALYTICS_GROUP,
            label = "Voice and call tracking",
            description = "Stops the events sent when someone starts or stops speaking or " +
                "listening, and the interval that reported voice state.",
            functionIds = listOf(75337, 75338, 133768)
        ),
        Feature(
            slug = "impression_tracking",
            group = ANALYTICS_GROUP,
            label = "Impression tracking",
            description = "Stops impressions being recorded, and the flag that exported the " +
                "telemetry ring every half second.",
            functionIds = listOf(27796, 37621)
        ),
        Feature(
            slug = "crash_upload",
            group = CRASH_GROUP,
            label = "Crash uploads",
            description = "Stops the crash reporter starting at all, and with it every crash, " +
                "exception and logged message it would have sent.",
            functionIds = listOf(25576, 25577, 25578, 25582, 25583, 25591)
        ),
        Feature(
            slug = "crash_context",
            group = CRASH_GROUP,
            label = "Crash report contents",
            description = "Stops the identity, tags, context, breadcrumbs and feature flags that " +
                "would be attached to a report.",
            functionIds = listOf(22882, 25572, 25573, 25574, 25575, 25579, 25580, 25584)
        ),
        Feature(
            slug = "performance_metrics",
            group = PERFORMANCE_GROUP,
            label = "Performance metrics",
            description = "Stops the app sampling its own performance and uploading the result. " +
                "The once-a-second process sampler is stubbed and its timer slowed to once an " +
                "hour, so it no longer wakes the JavaScript thread every second.",
            functionIds = listOf(
                1372, 33136, 33138, 38240, 38241, 38242, 81440, 103875, 103986, 103989, 103990,
                104011, 104012
            )
        ),
        Feature(
            slug = "call_sampling",
            group = PERFORMANCE_GROUP,
            label = "Call quality sampling",
            description = "Stops the samplers that run during a call to measure voice quality, " +
                "responsiveness and video effects.",
            functionIds = listOf(88602, 88911, 88931, 88987)
        ),
        Feature(
            slug = "session_telemetry",
            group = TELEMETRY_GROUP,
            label = "Session and network telemetry",
            description = "Stops the app recording network, session and message-cache statistics, " +
                "and the heartbeat that reported them.",
            functionIds = listOf(43355, 43356, 43357, 43359, 43360, 43361, 43388, 43544, 43545, 43546, 77056)
        ),
        Feature(
            slug = "gateway_telemetry",
            group = TELEMETRY_GROUP,
            label = "Gateway connection logs",
            description = "Stops the gateway connection being logged, measured and reported, and " +
                "the patched socket that read every message.",
            functionIds = listOf(65738, 65739, 65740, 65741, 77122)
        ),
        Feature(
            slug = "startup_timing",
            group = STARTUP_GROUP,
            label = "Startup timing",
            description = "Stops the app recording how long startup takes, milestone by " +
                "milestone.",
            functionIds = listOf(
                20433, 20435, 20437, 20445, 20476, 20477, 20478, 20479, 20480, 20481, 20482, 20485
            )
        ),
        Feature(
            slug = "debug_logging",
            group = LOGGING_GROUP,
            label = "Debug logs",
            description = "Silences the in-app debug log at every level, the aggregator that fed " +
                "it and the file it wrote to.",
            functionIds = listOf(20404, 77228, 77289, 77290, 77291, 77292, 77293, 77296, 77298)
        ),
        Feature(
            slug = "device_fingerprint",
            group = FINGERPRINT_GROUP,
            label = "Device fingerprinting",
            description = "Stops the handler that computed a fingerprint of your device for every " +
                "tracked event.",
            functionIds = listOf(22017)
        ),
        Feature(
            slug = "markup_cache_bound",
            group = STABILITY_GROUP,
            label = "Message cache limit",
            description = "Caps the cache the app fills while drawing messages. It had no limit " +
                "and its expiry was refreshed on every read, so it grew for as long as the app " +
                "ran, which is memory pressure the app cannot recover.",
            functionIds = listOf(8685)
        ),
        Feature(
            slug = "leftover_stubs",
            group = LEFTOVER_GROUP,
            label = "Technical leftovers",
            description = "Four functions stubbed by id. This release moved what those ids hold, " +
                "so three of the stubs change nothing you can see; the other stands in for a " +
                "typing-indicator hook. Switching this off leaves all four as Discord wrote them.",
            functionIds = listOf(16108, 61987, 64616)
        ),
        Feature(
            slug = "nitro_upsells",
            group = NITRO_UPSELL_GROUP,
            label = "Nitro upsells",
            description = "Every Nitro upsell surface: the banners, promo sheets, action sheets, " +
                "cards and the lock toasts the app raises when something needs Nitro.",
            functionIds = listOf(
                24984, 47292, 51916, 52266, 56599, 56600, 56602, 59373, 59374, 61934, 64002, 64043,
                64893, 64905, 64906, 64910, 64941, 64942, 64945, 65195, 65249, 65252, 65322, 65392,
                65422, 65437, 68942, 68943, 70684, 70685, 70890, 71473, 71791, 73979, 73980, 74525,
                74526, 74538, 74551, 74553
            )
        ),
        Feature(
            slug = "premium_upsells",
            group = UPSELL_GROUP,
            label = "Remaining premium prompts",
            description = "The remaining upsell sheets, cards, alerts and the buttons that open " +
                "them.",
            functionIds = listOf(
                34406, 43016, 43099, 43104, 43103, 43106, 43153, 51873, 51875, 51878, 51879, 52318,
                52408, 52409, 52410, 52412, 52413, 52467, 52469, 54214, 56605, 56606, 63349, 64054,
                65215, 65419, 65433, 65436, 65459, 65463, 65465, 66787, 68963, 69441, 69626, 69628,
                69629, 71438, 71439, 71440, 71441, 72026, 72916, 72917, 72918, 74540, 74549, 74806,
                75445, 75446, 74554
            )
        ),
        Feature(
            slug = "guild_upsells",
            group = UPSELL_GROUP,
            label = "Guild and boost upsells",
            description = "The guild boosting, server tag and role-subscription upsells.",
            functionIds = listOf(
                39065, 39075, 41877, 42965, 52407, 52411, 54211, 54212, 63160, 63297, 63298, 64285,
                65427, 72915, 72919, 72922, 72936, 72945, 72946, 76247, 76740
            )
        ),
        Feature(
            slug = "storefront_screens",
            group = STOREFRONT_GROUP,
            label = "Storefront screens",
            description = "The storefront itself: the product page, the gift and purchase flows, " +
                "the wishlist cards and the store item card.",
            functionIds = listOf(
                42015, 50717, 53912, 53913, 53914, 53915, 53916, 53947, 53948, 53997, 63732, 63735,
                63736, 63782
            )
        ),
        Feature(
            slug = "trials",
            group = TRIAL_GROUP,
            label = "Free trials",
            description = "The trial offers: the card that counts down an expiring trial, the " +
                "hooks that decide whether to show it, and the reverse-trial prompts.",
            functionIds = listOf(43159, 74171, 74172)
        ),
        Feature(
            slug = "emoji_upsells",
            group = UPSELL_GROUP,
            label = "Emoji and sticker upsells",
            description = "The upsells inside the emoji and sticker pickers.",
            functionIds = listOf(52400, 52401, 52405, 52406, 52447, 52449, 53092, 53100, 62695, 62702)
        ),
        Feature(
            slug = "leaderboards",
            group = LEADERBOARD_GROUP,
            label = "Leaderboard badges",
            description = "The badge a leaderboard winner carries on their profile.",
            functionIds = listOf(54103)
        ),
        Feature(
            slug = "profile_upsells",
            group = UPSELL_GROUP,
            label = "Profile upsells",
            description = "The upsells drawn on a profile and beside the activity card.",
            functionIds = listOf(68760, 68843, 68940, 68945, 68924, 69438, 68928, 68879, 68920, 68919)
        ),
        Feature(
            slug = "badge_customization",
            group = PROFILE_GROUP,
            label = "Badge customization",
            description = "The badge prompts and popups: the coachmark that offers to customize " +
                "badges, the customization sheet, the badge-directory coachmark and its " +
                "animation, the visibility menu and the badge details sheet.",
            functionIds = listOf(
                34462, 34461, 34463, 56116, 56117, 56120, 56161, 56162, 56163, 64618, 68836, 68837,
                68841, 74169, 74544, 74545, 74546
            )
        ),
        Feature(
            slug = "lower_camera_resolution",
            group = VIDEO_GROUP,
            label = "Lower video call cost",
            description = "Sends 640×360 at 24 frames per second instead of 720p at 30, about " +
                "four times fewer pixels, and caps the encoder at 1 Mbps. The camera capture and " +
                "the encoder's resolution budget are lowered together, and a full-quality channel " +
                "setting cannot override them. Screen sharing keeps its own 720p path.",
            functionIds = listOf(36779, 36800, 36805)
        ),
        Feature(
            slug = "better_stream_quality",
            group = VIDEO_GROUP,
            label = "Higher stream quality",
            description = "Answers true from canStreamQuality, so screen sharing can use the high " +
                "and mid stream qualities the app otherwise gates. Ported from Reseam's Better " +
                "stream quality patch.",
            functionIds = listOf(67069)
        ),
        Feature(
            slug = "better_video_upload_quality",
            group = VIDEO_GROUP,
            label = "Higher video upload quality",
            description = "Answers true from canUseHighVideoUploadQuality, so video is prepared " +
                "at the higher upload quality. Upload size limits stay the same. Ported from " +
                "Reseam's Better video quality patch.",
            functionIds = listOf(67057)
        ),
        Feature(
            slug = "appearance_activity_cards",
            group = LOOK_GROUP,
            label = "Activity cards in theme preview",
            description = "Removes the activity cards from the Appearance preview, so the theme " +
                "preview stays a preview of the theme.",
            functionIds = listOf(70671)
        ),
        Feature(
            slug = "super_reaction_animation",
            group = CHAT_GROUP,
            label = "Super Reaction animations",
            description = "Stops the Super Reaction decoration from looping on a message.",
            functionIds = listOf(52317)
        ),
        Feature(
            slug = "static_stickers",
            group = CHAT_GROUP,
            label = "Animated stickers",
            description = "Makes sticker previews and chat stickers still instead of animated.",
            functionIds = listOf(38394)
        ),
        Feature(
            slug = "show_blocked_messages",
            group = CHAT_GROUP,
            label = "Messages from blocked users",
            description = "Draws an already-received message from a blocked user expanded instead " +
                "of collapsing it behind the blocked-message notice.",
            functionIds = listOf(33796)
        ),
        Feature(
            slug = "reveal_spoilers",
            group = CHAT_GROUP,
            label = "Spoiler text and media",
            description = "Shows spoiler text, images and embeds without the tap-to-reveal " +
                "obscuring. Ported from Reseam's Reveal spoilers patch.",
            functionIds = listOf(48637, 48638)
        ),
        Feature(
            slug = "skip_masked_link_warnings",
            group = CHAT_GROUP,
            label = "Masked link warnings",
            description = "Opens masked links without the destination warning. Ported from " +
                "Reseam's Skip masked link warnings patch.",
            functionIds = listOf(48945, 52649)
        ),
        Feature(
            slug = "typing_status_outgoing",
            group = PRIVACY_GROUP,
            label = "Typing status you send",
            description = "Stops the app sending a typing status when you type or record, so " +
                "other people do not see that you are typing.",
            functionIds = listOf(62001)
        ),
        Feature(
            slug = "message_swipe_actions",
            group = CHAT_GROUP,
            label = "Message swipe actions",
            description = "Stops swiping a message from starting a reply or an edit.",
            functionIds = listOf(56346)
        ),
        Feature(
            slug = "message_card_clutter",
            group = CHAT_GROUP,
            label = "Reaction chips and animation",
            description = "Hides reaction chips, gift cards and activity invites in messages, and " +
                "stops custom emoji and GIFs animating. Link and embed cards and reply previews " +
                "stay visible, and a message still shows which message it replies to. One switch " +
                "for the message-card clutter the Reseam bundle spreads across several.",
            functionIds = listOf(47120)
        ),
        Feature(
            slug = "replies_without_pings",
            group = CHAT_GROUP,
            label = "Replies without pings",
            description = "Starts a reply with mentions turned off and the mention toggle shown, " +
                "so answering a message does not ping its author unless you turn it back on.",
            functionIds = listOf(52842, 52843)
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
                72749, 72751, 42963, 63093, 65413, 65491, 72862, 72863, 72864, 72865, 72867, 75994
            )
        ),
        Feature(
            slug = "row_separators",
            group = ROWS_GROUP,
            label = "Separator lines",
            description = "Removes the thin divider lines between rows, menu items, form fields, " +
                "list items, sections, message actions and server-bar groups. Text and controls " +
                "stay.",
            functionIds = listOf(
                40899, 67024, 40887, 49512, 70499, 71824, 69896, 72583, 76782, 73861, 73862, 73863,
                52927, 64067, 73074, 73072, 72800, 46087
            )
        ),
        Feature(
            slug = "dm_activity_cards",
            group = GAME_GROUP,
            label = "Friends' activity in DMs",
            description = "Hides the happening-now strip above your DMs, where friends in calls, " +
                "games and other activities appear. Turn this switch off to see them again.",
            functionIds = listOf(72530, 72535)
        ),
        Feature(
            slug = "call_screen_extras",
            group = CALL_SCREEN_GROUP,
            label = "Call screen clutter",
            description = "Removes the Add people banner from the call screen and the " +
                "game-console transfer section from the audio output sheet.",
            functionIds = listOf(49918, 75018)
        ),
        Feature(
            slug = "conjure_ai",
            group = CONJURE_GROUP,
            label = "Conjure AI shortcuts",
            description = "Removes the Conjure AI row from the channel list and its wand button " +
                "from the You bar.",
            functionIds = listOf(72860, 73178)
        ),
        Feature(
            slug = "display_name_styles",
            group = LOOK_GROUP,
            label = "Display-name styles",
            description = "Stops collectible display-name colors, fonts and effects from being " +
                "applied to names.",
            functionIds = listOf(38022)
        ),
        Feature(
            slug = "server_themes",
            group = THEME_GROUP,
            label = "Server themes",
            description = "Stops a server's theme from replacing your own appearance.",
            functionIds = listOf(34857, 34856)
        ),
        Feature(
            slug = "message_menu_reactions",
            group = CHAT_GROUP,
            label = "Reaction bar in message menu",
            description = "Hides the emoji reaction row at the top of a message's long-press " +
                "menu. Every other action stays.",
            functionIds = listOf(63946)
        ),
        Feature(
            slug = "icymi_feed",
            group = HOME_GROUP,
            label = "ICYMI activity feed",
            description = "Disables Discord's ICYMI feed, which gathers activity you missed, and " +
                "the controls that open it.",
            functionIds = listOf(48903, 48904)
        ),
        Feature(
            slug = "quest_bounties",
            group = QUESTS_GROUP,
            label = "Quest bounties",
            description = "Removes the Bounties section from Quest Home.",
            functionIds = listOf(69868)
        ),
        Feature(
            slug = "jump_to_present_button",
            group = CHAT_GROUP,
            label = "Jump to present button",
            description = "Removes the jump-to-present button from a chat.",
            functionIds = listOf(62198)
        ),
        Feature(
            slug = "server_bar_onboarding",
            group = SERVER_BAR_GROUP,
            label = "Empty server bar hint",
            description = "Removes the onboarding hint shown when the server bar has nothing in " +
                "it.",
            functionIds = listOf(73082)
        ),
        Feature(
            slug = "hexagon_campaign",
            group = SHOP_GROUP,
            label = "Hexagon campaign",
            description = "Hides the sponsored Hexagon campaign surfaces: the quest-home header, " +
                "app-icon section, display-name promo and edit button, and campaign sheet.",
            functionIds = listOf(69908, 71084, 68830, 68826, 69911)
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