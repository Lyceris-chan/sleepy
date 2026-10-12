package dev.sleepy.app.patches

import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.patches.DiscordHermesBundlePatch.FunctionPatch

/**
 * What every function in [DiscordHermesBundlePatch.PATCHES] does to the app, in the user's terms.
 *
 * The Hermes set is one switch over 208 functions, which is too coarse for the choices a user can
 * make: "hide the gift button but keep quests" is not expressible when both live behind the same
 * switch. This table supplies the per-item list for those functions: each function's feature
 * group, and a line stating what patching it does.
 *
 * ## Where the descriptions come from
 *
 * The set now targets Discord 351.2.0, whose function ids are its own; the descriptions were
 * written for the ids the same functions held in 349.5. The two are tied together by
 * [dev.sleepy.app.patches.DiscordHermesBundlePatch]'s migration, which moved each id the same way
 * here and there: by the module a function names itself with and its ordinal among that module's
 * closures, or - where a function no closure points at - by the strings its body reads. An id and
 * its description therefore move together or not at all, which is the property that makes this
 * table safe to remap.
 *
 * Four entries are grouped under [LEFTOVER_GROUP]: an earlier change set kept those ids from a
 * still earlier release without retargeting them at the functions the entries named, so the id
 * and the description had drifted apart. Those four state the function the id actually holds and
 * what the stub does to it, checked against the bundle's bytecode, rather than repeating an
 * explanation that no longer fits. The fifth of them named a function in
 * `modules/vibegrations/lib/vibegrationsPreviewNativeSurfaces.tsx`, and 351.2.0 ships no
 * `modules/vibegrations/` path at all, so it went with its target.
 *
 * A few entries state what the body at the id does because the earlier table lists them without
 * names.
 *
 * Entries are in [DiscordHermesBundlePatch.PATCHES] order and cover its function ids; a test
 * asserts both, because a function this table describes but the patch table does not carry, or
 * one the patch table carries and this table does not describe, is a mismatch the test reports.
 *
 * Function ids identify an entry, and names do not: this bundle has several functions with the
 * same name. The names also change on every release, so they are labels on an item and not its key
 * (see [PatchItem.identity]).
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
    private const val TELEMETRY_GROUP = "Session telemetry"
    private const val STARTUP_GROUP = "Startup timing"
    private const val LOGGING_GROUP = "Debug logs"
    private const val FINGERPRINT_GROUP = "Device fingerprinting"
    private const val STABILITY_GROUP = "Memory and stability"
    private const val LEFTOVER_GROUP = "Unused stubs"

    /** Every patched function, in the order the patch table applies them. */
    val ENTRIES: List<Entry> = listOf(
        entry(8121, "Clamps the entry count of the message-markup cache. It was built with no " +
            "limit and with its expiry clock refreshed on every read, so it grew for as long as " +
            "the app ran, on the path that draws every message."),
        entry(14391, "351.2.0 no longer has a startup initializer at this id: it is the " +
            "module factory for modules/telemetry_ring/native/channels/NormalTelemetry.tsx. The " +
            "stub returns undefined without running the factory, so the module never assigns " +
            "its default export and an importer sees no NormalTelemetry channel."),
        entry(15264, "Gives the Settings Nitro / Manage Nitro row the always-false " +
            "predicate that hides it. Its backend is already answered with a 204, so the row led " +
            "to a screen that could do nothing."),
        entry(15268, "Gives the Settings Manage Plan row the always-false predicate " +
            "that hides it."),
        entry(15275, "Gives the Settings Server Boost row the always-false " +
            "predicate that hides it."),
        entry(16197, "Gives the Settings CollectiblesShop route the always-false predicate " +
            "that hides its row. This def had none of its own, so the route object itself is " +
            "edited, the same lever that removed the other billing rows."),
        entry(18823, "Stops the log aggregator from feeding the in-app debug panel's log " +
            "buffer."),
        entry(18854, "Stops the time-to-interactive tracker recording a " +
            "milestone's start."),
        entry(18856, "Stops the time-to-interactive tracker recording a " +
            "milestone's end."),
        entry(18858, "Stops the time-to-interactive tracker storing a value for " +
            "a milestone."),
        entry(18866, "Stops the time-to-interactive tracker recording a " +
            "measurement."),
        entry(18894, "Stops the tracing recorder appending its formatted line to " +
            "the in-memory startup log."),
        entry(18895, "Stops startup milestone marks being recorded, one of the " +
            "recorders called at every startup step."),
        entry(18896, "Stops the milestone mark-and-log recorder used by the " +
            "traced operations."),
        entry(18897, "Stops import-detail records being added to the startup " +
            "trace."),
        entry(18898, "Stops delta marks from being added to the startup trace."),
        entry(18899, "Stops timestamped marks from being recorded in the startup " +
            "trace."),
        entry(18900, "Stops detail records from being added to the startup " +
            "trace."),
        entry(18903, "Stops the server-issued trace id from being stored on the " +
            "startup trace."),
        entry(20435, "Stops the fingerprint handler, which computed the old and new " +
            "device fingerprint for every tracked event and reported the transition."),
        entry(20586, "Returns zero from the libdiscore action-telemetry sample rate, " +
            "which the server controls, so the client collects no action payloads whatever " +
            "treatment it is assigned."),
        entry(20588, "Returns false from the libdiscore action-telemetry gate, which " +
            "runs on every redux action and decides whether to collect that action's serialized " +
            "payload, so no payload is collected."),
        entry(21358, "Stops Sentry's own breadcrumb collector, which recorded one for " +
            "every Flux dispatcher action, so the collection stops at the source rather than at " +
            "the sender."),
        entry(24043, "Stops Sentry attaching the signed-in user's identity to error " +
            "reports."),
        entry(24044, "Stops Sentry clearing the user identity it would otherwise have " +
            "attached."),
        entry(24045, "Stops tags being attached to the Sentry scope, so error reports " +
            "carry no user-defined tags."),
        entry(24046, "Stops extra context being attached to the Sentry scope."),
        entry(24047, "Stops JavaScript exceptions being reported to Sentry, so stack " +
            "traces stay on the device."),
        entry(24048, "Stops JavaScript crash reports being sent to Sentry."),
        entry(24049, "Stops manually logged messages being sent to Sentry."),
        entry(24050, "Stops feature-flag names being recorded on the Sentry scope."),
        entry(24051, "Stops Sentry's JavaScript API adding breadcrumbs, which is what " +
            "recorded UI clicks and navigations."),
        entry(24053, "Stops the deliberate crash call from reaching Sentry."),
        entry(24054, "Stops the memory-warning report from reaching Sentry."),
        entry(24055, "Stops the crash-handled marker being recorded on the Sentry scope."),
        entry(24062, "Stops the Sentry JavaScript SDK from initializing at all, so no " +
            "client is bound and none of its automatic integrations (global error handlers, " +
            "breadcrumbs, promise-rejection tracking) are installed."),
        entry(24121, "Silences the [Analytics] debug reporter, so the client stops " +
            "writing its analytics events into the debug log."),
        entry(24184, "Silences the analytics store's own tracking closure, which emits " +
            "events independently of the central emitter."),
        entry(24192, "Silences the [Analytics] network-action reporter, so per-request " +
            "analytics are never emitted."),
        entry(26098, "Returns null from the avatar decoration data " +
            "transform, the choke point every caller that brings a decoration into the client " +
            "passes through. Those callers assign the result without reading it, and the " +
            "function already returns null on its own empty paths, so no decoration reaches " +
            "the client."),
        entry(26143, "Returns false from the analytics experiment flag, so the 500 ms " +
            "telemetry-ring export loop can never start."),
        entry(33314, "Silences the emitter behind the forum and clickstream trackers, " +
            "so those impressions are never sent."),
        entry(35577, "Stops the monitoring agent's counter, which about twenty-five " +
            "modules call as they do their work."),
        entry(35578, "Stops the monitoring agent's distribution sample."),
        entry(35579, "Stops the monitoring agent flushing what it accumulated to the " +
            "two-minute /metrics/v2 upload."),
        entry(40190, "Returns false from the storefront capability check, which hides the " +
            "guild sidebar's game-shop row and blocks the redirect into it."),
        entry(40492, "Returns false from the Guild Shop visibility hook, one of " +
            "the two visibility gates the creator-monetisation guild shop module holds. The " +
            "hook's callers test for exactly false, which is the shape this stub returns."),
        entry(40493, "Returns false from the Guild Shop visibility hook, one of " +
            "the two visibility gates the creator-monetisation guild shop module holds. The " +
            "hook's callers test for exactly false, which is the shape this stub returns."),
        entry(40494, "Returns false from the plain isGuildShopVisibleInGuild " +
            "predicate exported beside the visibility hook, so a caller that asks whether the " +
            "guild shop is visible is answered no."),
        entry(40495, "Returns false from the Guild Shop preview visibility hook, " +
            "the second of the module's two visibility gates, so the preview surface stays " +
            "hidden. Its callers test for exactly false."),
        entry(40496, "Returns false from the Guild Shop preview visibility hook, " +
            "the second of the module's two visibility gates, so the preview surface stays " +
            "hidden. Its callers test for exactly false."),
        entry(41715, "Stops the network-statistics sampler restarting its " +
            "timers when the app changes state."),
        entry(41716, "Stops the network-statistics recorder writing its " +
            "accumulated events to device storage."),
        entry(41717, "Stops the network-statistics sampler's own track(), " +
            "which queued events locally."),
        entry(41719, "Stops the network-statistics sampler's app-state " +
            "listener, which reports to the sampler whether each app-state change leaves the app " +
            "active."),
        entry(41720, "Stops the network-statistics sampler's per-message " +
            "analytics accumulator, which adds each message's send-analytics duration and queue " +
            "size to its totals."),
        entry(41721, "Stops the network-statistics sampler's per-message " +
            "counter, whose whole body increments the sampler's running count."),
        entry(41748, "Stops the session heartbeat scheduler being initialized, " +
            "so its timer, its periodic API ping and its breadcrumb are never created."),
        entry(41904, "Stops the message-cache statistic that counted channel " +
            "fetches starting."),
        entry(41905, "Stops the message-cache statistic that counted a channel " +
            "being served locally."),
        entry(41906, "Stops the message-cache statistic that counted a channel " +
            "being fetched over the network."),
        entry(43895, "Stops the app-rating survey poll, which fetched the survey and " +
            "emitted analytics for it."),
        entry(46810, "Returns false from the guild-tag display hook, so callers " +
            "that decide whether to build a tag row build none. The nulled tag components hide " +
            "the chips themselves; this also stops them being constructed."),
        entry(46811, "Returns false from the guild-tag display hook, so callers " +
            "that decide whether to build a tag row build none. The nulled tag components hide " +
            "the chips themselves; this also stops them being constructed."),
        entry(46815, "Returns false from the plain guild-tag display predicate, " +
            "so a caller that asks whether to show a guild tag is answered no."),
        entry(47043, "Answers no from the Shop This Look experiment gate, the " +
            "check every entry point into the feature reads. Editing the sheet and the " +
            "coachmark alone leaves the opener, whose body is shared with an unrelated " +
            "display-name sheet, so the menu row was still built and still opened an empty " +
            "sheet. Nothing that renders Shop This Look is reachable once this says no."),
        entry(47044, "Answers no from the Shop This Look experiment gate, the " +
            "check every entry point into the feature reads. Editing the sheet and the " +
            "coachmark alone leaves the opener, whose body is shared with an unrelated " +
            "display-name sheet, so the menu row was still built and still opened an empty " +
            "sheet. Nothing that renders Shop This Look is reachable once this says no."),
        entry(47053, "Stops the storefront fetching products by SKU id. The " +
            "implementation is stubbed rather than the exported trampoline, whose body Hermes " +
            "shares with dozens of unrelated API calls."),
        entry(47081, "Returns null from the collectible profile frame " +
            "component, so no profile frame is drawn."),
        entry(47082, "Returns null from the collectible profile frame " +
            "component, so no profile frame is drawn."),
        entry(47142, "Returns an empty array from the profile badges hook, so the " +
            "badge row has no badges to render. The hook already returns an empty array on its " +
            "own empty paths, so its callers handle the shape."),
        entry(47143, "Returns an empty array from the profile badges hook, so the " +
            "badge row has no badges to render. The hook already returns an empty array on its " +
            "own empty paths, so its callers handle the shape."),
        entry(47477, "Stops the guild-affinity fetch that ran on every connection " +
            "open. It was found by logging what the blocklist answered with a 204, and it hits an " +
            "endpoint that is already blocked."),
        entry(49053, "Returns null from the guild tag badge component, so no " +
            "badge chip is drawn next to a name."),
        entry(49054, "Returns null from the guild tag badge component, so no " +
            "badge chip is drawn next to a name."),
        entry(49055, "Returns null from the base guild tag chiplet, the component " +
            "the guild tag chips build on, so no chip is drawn through it."),
        entry(49056, "Returns null from the base guild tag chiplet, the component " +
            "the guild tag chips build on, so no chip is drawn through it."),
        entry(49057, "Returns null from the guild tag component, so no guild tag " +
            "chip is drawn next to a name."),
        entry(49322, "Stubs the game-profile shop carousel, so the carousel a game " +
            "profile page renders draws nothing. The carousel sits outside the shop screen, " +
            "which is why stubbing the screen did not remove it."),
        entry(49323, "Stubs the game-profile shop carousel, so the carousel a game " +
            "profile page renders draws nothing. The carousel sits outside the shop screen, " +
            "which is why stubbing the screen did not remove it."),
        entry(49336, "Stops impression tracking: this path builds its own trackMaker " +
            "and posts to /science without going through the central emitter the other stubs " +
            "cover."),
        entry(49438, "Stops the storefront price fetch for an application."),
        entry(49439, "Stops the storefront price fetch for SKU ids."),
        entry(49519, "Returns null from the animated decoration layer that plays over " +
            "the profile card, so the looping video or composite is never drawn. The profile " +
            "picture decoration is a different module and is untouched."),
        entry(49520, "Returns null from the animated decoration layer that plays over " +
            "the profile card, so the looping video or composite is never drawn. The profile " +
            "picture decoration is a different module and is untouched."),
        entry(49560, "Returns null from the collectible nameplate component, " +
            "so no nameplate is drawn."),
        entry(49561, "Returns null from the collectible nameplate component, " +
            "so no nameplate is drawn."),
        entry(49828, "Stops the fetch of collections with their products."),
        entry(49896, "Rebuilds the You screen's floating navigation so the Quests button " +
            "is never built. The button is one element of a list rather than a function that can " +
            "return false, so the body is edited instead of stubbed."),
        entry(50253, "Returns a falsy value for the flag that gates quest items in the " +
            "activity panel."),
        entry(50277, "Stops the refresh of current quests that the UI drives on a " +
            "one-second timer while a quest is active."),
        entry(50278, "Stops the quest heartbeat being sent to the server."),
        entry(50286, "Stops the refresh of claimed quests."),
        entry(50287, "Stops the fetch of the quest waiting to be delivered."),
        entry(50288, "Stops the fetch of the earned quest waiting to be delivered."),
        entry(50875, "No-ops the action-sheet opener every upsell bottom sheet is " +
            "launched through, across its nine call sites."),
        entry(51395, "No-ops the modal opener every premium upsell modal is " +
            "launched through, across its sixteen call sites."),
        entry(52519, "Removes the inline 'Get Nitro' button, the widest-reaching " +
            "single upsell component: eleven consumers, from the Go Live sheet to the sticker " +
            "detail view."),
        entry(52520, "Removes the inline 'Get Nitro' button, the widest-reaching " +
            "single upsell component: eleven consumers, from the Go Live sheet to the sticker " +
            "detail view."),
        entry(53481, "Stops the storefront configuration fetch."),
        entry(53485, "Stops the storefront SKU lookup for an application."),
        entry(53703, "Returns a falsy value from the one real Spotify gate. Every call " +
            "site tests it in an if, so this removes the remaining Spotify branding: the " +
            "presence, 'Play on Spotify', the Spotify embed in activity cards and the outbound " +
            "Spotify activity."),
        entry(55262, "Returns null from the track renderer, so a friend's Spotify " +
            "presence renders nothing. Stubbing the listening check did not cover this, which is " +
            "why other people's songs stayed visible."),
        entry(55281, "Stops the subscription to Spotify player-state notifications, " +
            "which polled api.spotify.com on launch."),
        entry(55286, "Returns false from the check that asks the on-device Spotify app " +
            "whether Discord may talk to it over a local protocol. That path never touches the " +
            "network, so blocking Spotify hosts alone could not reach it; reporting it " +
            "unregistered leaves nothing to broadcast."),
        entry(57893, "351.2.0 no longer has the gift purchase button at this id: it is " +
            "the uncompiled variant of the useTypingUserIdsForDisplay hook in " +
            "modules/chat/native/TypingIndicator.tsx, and the React Compiler experiment defaults " +
            "off, so this is the variant the app runs. The stub returns an empty array, the " +
            "hook's own shape with no typing users, so the chat input's " +
            "hasTypingIndicatorContent read of its length finds 0 instead of throwing. The " +
            "recorded build stubs this id to undefined, which that read turns into a TypeError."),
        entry(58817, "Removes the chat-input button that is a gift button or a thread " +
            "button depending on the conversation."),
        entry(58818, "Removes the chat-input button that is a gift button or a thread " +
            "button depending on the conversation."),
        entry(58829, "Removes the gift button in the chat input bar, which sat next to the " +
            "message box."),
        entry(58830, "Removes the gift button in the chat input bar, which sat next to the " +
            "message box."),
        entry(61076, "351.2.0 no longer has the profile edit screen at this id: it is the " +
            "compiled variant of the guild notification action sheet in " +
            "modules/notifications/settings/native/" +
            "NotificationSettingsMessageUnreadGuildActionSheet.tsx. The app selects that " +
            "variant only when the React Compiler experiment is enabled, and this build leaves " +
            "the experiment off by default, so the stub changes nothing for a stock client."),
        entry(62072, "Removes the modal that awards orbs for a completed quest."),
        entry(62073, "Removes the modal that awards orbs for a completed quest."),
        entry(62187, "Removes the button that opens the collectibles shop."),
        entry(62188, "Removes the button that opens the collectibles shop."),
        entry(62189, "Removes the coachmark that points at the mobile shop button."),
        entry(62190, "Removes the coachmark that points at the mobile shop button."),
        entry(62264, "The same orb gate under its plain name. Hermes deduplicated the two " +
            "exports into one body, so patching either one turns orbs off; the alias set is " +
            "verified on every run."),
        entry(62265, "Returns {enabled: false} from the gate every orb surface reads, which " +
            "removes orbs at the source rather than hiding each screen that shows them. Hermes " +
            "shares this body with isVirtualCurrencyEnabled, so that name is disabled with it."),
        entry(62388, "Stubs the Shop This Look action sheet, so it opens empty if its " +
            "opener still runs. The opener is left as it is because its body is shared with an " +
            "unrelated display-name feature."),
        entry(62389, "Stubs the Shop This Look action sheet, so it opens empty if its " +
            "opener still runs. The opener is left as it is because its body is shared with an " +
            "unrelated display-name feature."),
        entry(62405, "Stubs the Shop This Look marketing coachmark, so the prompt that " +
            "points at the feature is not built. It lives outside the shop screen, so stubbing " +
            "the screen did not remove it."),
        entry(62406, "Stubs the Shop This Look marketing coachmark, so the prompt that " +
            "points at the feature is not built. It lives outside the shop screen, so stubbing " +
            "the screen did not remove it."),
        entry(62639, "Rebuilds the tab list the React Compiler copy of the other-profile " +
            "content component builds, so it builds Main and Activity only, with no Board and no " +
            "Wishlist tab. The app runs that copy when the React Compiler experiment is on; the " +
            "leaving it unpatched lets both tabs appear when the experiment is on."),
        entry(62665, "351.2.0 no longer has the quest orb shop carousel at this id: it is " +
            "the compiled variant of the badge row in " +
            "modules/badges/native/BadgeDirectoryNuxCoachmark.tsx, which renders the game-time, " +
            "streaming and game-diversity tier badges. The app selects that variant only when " +
            "the React Compiler experiment is enabled, and this build leaves the experiment off " +
            "by default, so the stub changes nothing for a stock client."),
        entry(64307, "Forces the wishlist tab index to -1, so the profile's segmented " +
            "control has no Wishlist tab to select."),
        entry(64351, "Returns null from the wishlist grid a profile renders, so the " +
            "grid of wishlisted items shown as a profile section is not drawn. This is a " +
            "different surface from the profile's Wishlist tab."),
        entry(64359, "Returns null from the wishlist suggestions grid a profile " +
            "renders, so the suggested-items grid is not drawn. The component already returns " +
            "null when its own mobile gate is off, so callers handle the shape."),
        entry(64360, "Returns null from the wishlist suggestions grid a profile " +
            "renders, so the suggested-items grid is not drawn. The component already returns " +
            "null when its own mobile gate is off, so callers handle the shape."),
        entry(64371, "Removes the add-to-wishlist button in the grid layout."),
        entry(64372, "Removes the add-to-wishlist button in the grid layout."),
        entry(64376, "Removes the add-to-wishlist button on item cards."),
        entry(64470, "Removes the orb price tag shown on items that can be bought with " +
            "orbs."),
        entry(64471, "Removes the orb price tag shown on items that can be bought with " +
            "orbs."),
        entry(64497, "Removes the standalone gift button component, one of the separate " +
            "components stubbing the inline Nitro button did not cover."),
        entry(64498, "Removes the standalone gift button component, one of the separate " +
            "components stubbing the inline Nitro button did not cover."),
        entry(65303, "Removes the gift purchase button, so a gift cannot be bought from " +
            "the UI."),
        entry(65304, "Removes the gift purchase button, so a gift cannot be bought from " +
            "the UI."),
        entry(65598, "Stops the gateway READY payload being logged: the " +
            "payload carries the whole guild list, and it is recorded being stringified around " +
            "fourteen times on one connect in a hundred."),
        entry(65599, "Stops the connection-path lookup used to label the " +
            "gateway analytics event."),
        entry(65600, "Stops the READY payload being measured for its analytics " +
            "byte size."),
        entry(65601, "Stops the gateway-connected analytics event."),
        entry(67883, "Stops the analytics action handler, which built an event with " +
            "its key, properties, fingerprint and timestamp for every tracked action."),
        entry(68674, "Stubs the Edit User Profile entry in settings."),
        entry(68675, "Stubs the Edit User Profile entry in settings."),
        entry(70011, "Returns false from the Manage Subscriptions row's visibility " +
            "predicate."),
        entry(70012, "Returns false from the Manage Subscriptions row's visibility " +
            "predicate."),
        entry(70028, "Returns false from the Gift Inventory row's own visibility " +
            "predicate."),
        entry(70031, "Returns false from the Quests settings row's own visibility " +
            "predicate. This is what actually removes the row: the settings harness hides any " +
            "entry whose predicate returns exactly false."),
        entry(70036, "Stubs the Quest Home screen the Quests settings row navigates to."),
        entry(70037, "Stubs the Quest Home screen the Quests settings row navigates to."),
        entry(70064, "Returns null from the Quest Home screen, so a route that still " +
            "navigates there renders nothing. Every mobile entry point is gated separately; " +
            "this covers the screen itself, which a route name can still reach."),
        entry(70065, "Returns null from the Quest Home screen, so a route that still " +
            "navigates there renders nothing. Every mobile entry point is gated separately; " +
            "this covers the screen itself, which a route name can still reach."),
        entry(70288, "Returns false from the mobile quest-dock hook, so the dock is not " +
            "shown. The broad quest-eligibility check is the other lever, but its body is shared " +
            "with six unrelated capability checks and cannot be patched."),
        entry(70848, "Returns false from the Server Subscriptions row's visibility " +
            "predicate."),
        entry(71033, "Returns false from the Restore Subscription row's visibility " +
            "predicate."),
        entry(71034, "Returns false from the Restore Subscription row's visibility " +
            "predicate."),
        entry(72410, "Removes the You-tab orb balance widget's menu, one of the screens the " +
            "orb gate sits in front of."),
        entry(72415, "Removes the wrapper component that builds the orb balance widget."),
        entry(72416, "Removes the wrapper component that builds the orb balance widget."),
        entry(72459, "Stops the survey override from taking effect, so a server-side " +
            "override cannot swap the app-rating survey the client is showing."),
        entry(72460, "Stops the survey-dismiss action, which both updated the store and " +
            "emitted its APP_NOTICE analytics event."),
        entry(72461, "Stops the survey-seen report, which told Discord the survey had " +
            "been shown and emitted analytics for it."),
        entry(72863, "Stubs the collectibles shop screen component the Settings route " +
            "renders, so the route draws nothing. The shop also has a deep-link entry the " +
            "route edit cannot close, which is why the screen component is stubbed."),
        entry(72864, "Stubs the collectibles shop screen component the Settings route " +
            "renders, so the route draws nothing. The shop also has a deep-link entry the " +
            "route edit cannot close, which is why the screen component is stubbed."),
        entry(72868, "Stubs the shop screen's internal component, leaving the screen with " +
            "nothing to render."),
        entry(72869, "Stubs the shop screen's internal component, leaving the screen with " +
            "nothing to render."),
        entry(72870, "Stubs the collectibles shop screen itself, so there is no shop tab to " +
            "browse."),
        entry(72871, "Stubs the collectibles shop screen itself, so there is no shop tab to " +
            "browse."),
        entry(73022, "Returns false from the usePredicate closure the Quests toggles " +
            "in Data & Privacy carry, so those settings rows are hidden. The two toggles' " +
            "predicates are byte-identical and Hermes stores them once, so either id changes " +
            "both."),
        entry(73034, "Returns false from the usePredicate closure the Quests toggles " +
            "in Data & Privacy carry, so those settings rows are hidden. The two toggles' " +
            "predicates are byte-identical and Hermes stores them once, so either id changes " +
            "both."),
        entry(74055, "Returns null from the voice guild tag component, so no " +
            "guild tag chip is drawn in a voice channel."),
        entry(74056, "Returns null from the voice guild tag component, so no " +
            "guild tag chip is drawn in a voice channel."),
        entry(77273, "Stops the AutoAnalytics navigator hook that emitted an event " +
            "each time a screen was built."),
        entry(77274, "Stops the other half of the AutoAnalytics navigator hook, which " +
            "emitted an event each time the current screen changed."),
        entry(77424, "Rebuilds the profile section tab list the React Compiler copy of " +
            "the You screen content component builds, so it builds Main and Activity only, with " +
            "no Board and no Wishlist tab. The app runs that copy when the React Compiler " +
            "experiment is on; leaving it unpatched lets both tabs appear there too."),
        entry(78981, "Stops the per-request tracker, which ran URL matching and " +
            "appended to the telemetry ring for every HTTP request."),
        entry(79182, "Stops _trackStartSpeaking from computing game metadata and " +
            "packet stats when someone starts speaking in a voice call."),
        entry(79183, "Stops _trackStartListening from computing telemetry payloads " +
            "when someone starts listening in a voice call."),
        entry(81241, "Stops the app-state update handler listed with the " +
            "message-cache recorders, so it no longer runs on every app state change."),
        entry(81275, "Stops the patched global WebSocket being installed, " +
            "whose per-message handler parsed every gateway frame a second time and appended it " +
            "to the disabled telemetry ring."),
        entry(81317, "Stops the timer monitor that formatted slow-timer records into the " +
            "log, which only the stubbed analytics emitter read."),
        entry(81383, "Silences one of the logger's debug levels, so those lines no " +
            "longer enter the in-memory JavaScript debug log. Only warn and error are kept, so " +
            "genuine problems still surface."),
        entry(81384, "Silences log(), which is where most of the in-memory debug log was " +
            "coming from."),
        entry(81385, "Silences the logger's verbose level (the 'dangerously' variant), " +
            "keeping it out of the debug log."),
        entry(81386, "Silences verbose, one of the two levels that dominated the " +
            "in-memory debug log."),
        entry(81387, "Silences info, so informational lines no longer enter the debug " +
            "log."),
        entry(81390, "Silences trace, so trace lines no longer enter the debug log."),
        entry(81392, "Silences the file-only logger, so nothing is written to the " +
            "logger's file sink."),
        entry(85406, "Silences the central science-event emitter every feature calls, " +
            "so no event is built or sent and no per-event CPU/memory sample is taken. Its " +
            "callers await it, so the stub still returns a resolved promise."),
        entry(85411, "Stops the single POST behind the /science and /beaker queues, so " +
            "the events the stubs above would have queued are never uploaded either."),
        entry(85532, "Stops the once-a-second process sampler, which made three native " +
            "bridge calls per second for CPU and memory whose only consumers null-guard."),
        entry(91461, "Stops the voice-quality payload builder that assembled and sent " +
            "its statistics every five minutes."),
        entry(91810, "Stops the voice-quality sampler that ran once a second during a " +
            "call."),
        entry(91826, "Stops the system-responsiveness sampler, the second of the " +
            "voice-call samplers."),
        entry(91845, "Stops the video-effect and system-resource sampler that ran on " +
            "every call-statistics callback."),
        entry(107938, "Stops the 60-second session flusher that shipped session " +
            "aggregates."),
        entry(108049, "Stops the metrics aggregator adding samples to its buckets."),
        entry(108052, "Stops the metrics aggregator flushing its buckets on its " +
            "interval."),
        entry(108053, "Stops the metrics aggregator capturing a metrics snapshot."),
        entry(108074, "Stops the browser-side metrics aggregator adding samples to its " +
            "buckets."),
        entry(108075, "Stops the browser-side metrics aggregator flushing them."),
        entry(114767, "Rebuilds the profile activity list without friends' Spotify " +
            "listened-session entries. 351.2.0 has a second copy of the filter in the same module, " +
            "reachable through a different parent, and both are edited so Spotify entries do " +
            "not survive on some surfaces."),
        entry(114892, "Rebuilds other users' profile tab list so it builds Main and " +
            "Activity only, with no Board and no Wishlist tab. It edits the " +
            "isReactCompilerEnabled() == false variant of the profile content component."),
        entry(130319, "Rebuilds the profile section tab list so it builds Main and " +
            "Activity only, with no Board and no Wishlist tab. The tab is one element of a list, " +
            "not a function that can return false, so the body is edited rather than stubbed."),
        entry(135593, "Stops QuestFetchManager installing the recurring interval that " +
            "refetched quests forever, which against already-blocked endpoints was pure timer and " +
            "network churn."),
        entry(145546, "Rebuilds the profile activity list without friends' Spotify " +
            "listened-session entries."),
        entry(151493, "Stops the 60-second voice state interval callback that " +
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
            slug = "nitro_promotions",
            group = SHOP_GROUP,
            label = "Nitro promotions",
            description = "Removes the buttons, sheets and dialogs that advertise Nitro, including the " +
                "inline Get Nitro button in the chat bar.",
            functionIds = listOf(50875, 51395, 52519, 52520)
        ),
        Feature(
            slug = "collectibles_shop",
            group = SHOP_GROUP,
            label = "The collectibles shop",
            description = "Stops the shop screen drawing, so no tab or route opens it.",
            functionIds = listOf(72863, 72864, 72868, 72869, 72870, 72871)
        ),
        Feature(
            slug = "shop_entry_points",
            group = SHOP_GROUP,
            label = "Shop buttons and prompts",
            description = "Removes the button that opens the shop, the prompt that points at it, and " +
                "the settings row that leads there.",
            functionIds = listOf(16197, 62187, 62188, 62189, 62190)
        ),
        Feature(
            slug = "shop_this_look",
            group = SHOP_GROUP,
            label = "Shop This Look",
            description = "Answers no to the experiment that turns the feature on, so the row " +
                "that opens the sheet is never built, and empties the sheet and its prompt as " +
                "well for any path that reaches them.",
            functionIds = listOf(47043, 47044, 62388, 62389, 62405, 62406)
        ),
        Feature(
            slug = "game_profile_shop",
            group = SHOP_GROUP,
            label = "Game profile shop",
            description = "Stops the shop carousel drawing on a game's profile and removes the guild " +
                "sidebar's row for it.",
            functionIds = listOf(40190, 49322, 49323)
        ),
        Feature(
            slug = "guild_shop",
            group = SHOP_GROUP,
            label = "Guild shop",
            description = "Hides the guild shop and its preview by answering false to every check " +
                "that decides whether to show one.",
            functionIds = listOf(40492, 40493, 40494, 40495, 40496)
        ),
        Feature(
            slug = "orbs",
            group = SHOP_GROUP,
            label = "Orbs",
            description = "Turns orbs off at the gate every orb surface reads, and removes the orb " +
                "balance, price tags and reward dialogs.",
            functionIds = listOf(62072, 62073, 62264, 62265, 64470, 64471, 72410, 72415, 72416)
        ),
        Feature(
            slug = "storefront",
            group = SHOP_GROUP,
            label = "Store and product lookups",
            description = "Stops the app fetching store products, prices and collections, and the " +
                "guild data it loaded on every connection.",
            functionIds = listOf(47053, 47477, 49438, 49439, 49828, 53481, 53485)
        ),
        Feature(
            slug = "gift_buttons",
            group = GIFTS_GROUP,
            label = "Gift buttons",
            description = "Removes the gift button from the chat bar and the button that buys a gift.",
            functionIds = listOf(58817, 58818, 58829, 58830, 64497, 64498, 65303, 65304)
        ),
        Feature(
            slug = "billing_rows",
            group = BILLING_GROUP,
            label = "Subscription and billing rows",
            description = "Hides the settings rows for Nitro, managing a plan or subscription, server " +
                "boosts and subscriptions, the gift inventory and restoring a purchase.",
            functionIds = listOf(15264, 15268, 15275, 70011, 70012, 70028, 70848, 71033, 71034)
        ),
        Feature(
            slug = "avatar_decorations",
            group = LOOK_GROUP,
            label = "Avatar decorations",
            description = "Stops avatars carrying decorations, at the one transform every caller " +
                "passes a decoration through.",
            functionIds = listOf(26098)
        ),
        Feature(
            slug = "nameplates",
            group = LOOK_GROUP,
            label = "Nameplates",
            description = "Stops the collectible nameplate drawing next to a name.",
            functionIds = listOf(49560, 49561)
        ),
        Feature(
            slug = "profile_frames",
            group = LOOK_GROUP,
            label = "Profile frames",
            description = "Stops the collectible profile frame drawing around an avatar.",
            functionIds = listOf(47081, 47082)
        ),
        Feature(
            slug = "guild_tags",
            group = LOOK_GROUP,
            label = "Guild tags",
            description = "Stops guild tag chips, badges and voice-channel tags drawing, and answers " +
                "false to the checks that decide whether to show one.",
            functionIds = listOf(46810, 46811, 46815, 49053, 49054, 49055, 49056, 49057, 74055, 74056)
        ),
        Feature(
            slug = "profile_badges",
            group = PROFILE_GROUP,
            label = "Profile badges",
            description = "Returns no badges from the profile badge hook, so the badge row has nothing " +
                "to draw.",
            functionIds = listOf(47142, 47143)
        ),
        Feature(
            slug = "profile_effects",
            group = PROFILE_GROUP,
            label = "Animated profile effects",
            description = "Stops the animated layer that plays over a profile card.",
            functionIds = listOf(49519, 49520)
        ),
        Feature(
            slug = "edit_profile_row",
            group = PROFILE_GROUP,
            label = "The edit profile row",
            description = "Removes the Edit User Profile entry from settings.",
            functionIds = listOf(68674, 68675)
        ),
        Feature(
            slug = "quests_in_app",
            group = QUESTS_GROUP,
            label = "Quests in the app",
            description = "Removes the Quests screen, its dock, its navigation button, its settings row " +
                "and its toggles in Data & Privacy.",
            functionIds = listOf(49896, 50253, 70031, 70036, 70037, 70064, 70065, 70288, 73022, 73034)
        ),
        Feature(
            slug = "quest_fetching",
            group = QUESTS_GROUP,
            label = "Quest fetching",
            description = "Stops the app fetching, refreshing and re-polling quests in the background.",
            functionIds = listOf(50277, 50278, 50286, 50287, 50288, 135593)
        ),
        Feature(
            slug = "wishlist_tabs",
            group = WISHLIST_GROUP,
            label = "Wishlist tabs",
            description = "Removes the Wishlist and Board tabs from your profile and from other " +
                "people's, in both copies of each profile component, the copy the app runs by " +
                "default and the React Compiler copy, so the removal holds whichever copy the " +
                "experiment selects.",
            functionIds = listOf(62639, 64307, 77424, 114892, 130319)
        ),
        Feature(
            slug = "wishlist_grids",
            group = WISHLIST_GROUP,
            label = "Wishlist grids and buttons",
            description = "Stops the wishlist and suggestion grids drawing, and removes the buttons " +
                "that add an item to a wishlist.",
            functionIds = listOf(64351, 64359, 64360, 64371, 64372, 64376)
        ),
        Feature(
            slug = "spotify",
            group = SPOTIFY_GROUP,
            label = "Spotify integration",
            description = "Removes Spotify from the app: the presence a friend shows, the track " +
                "renderer, the player subscription and the listened-session entries on a profile.",
            functionIds = listOf(53703, 55262, 55281, 55286, 114767, 145546)
        ),
        Feature(
            slug = "rating_prompts",
            group = SURVEYS_GROUP,
            label = "App rating prompts",
            description = "Stops the app asking you to rate it, and the reports that told Discord the " +
                "prompt was shown or dismissed.",
            functionIds = listOf(43895, 72459, 72460, 72461)
        ),
        Feature(
            slug = "analytics_events",
            group = ANALYTICS_GROUP,
            label = "Analytics events",
            description = "Stops analytics events being built and sent, at the emitters every feature " +
                "calls and at the single request behind them.",
            functionIds = listOf(24121, 24184, 24192, 67883, 85406, 85411)
        ),
        Feature(
            slug = "screen_tracking",
            group = ANALYTICS_GROUP,
            label = "Screen and navigation tracking",
            description = "Stops the event sent each time a screen is built and the per-request " +
                "tracker that matched URLs for it.",
            functionIds = listOf(33314, 77273, 77274, 78981)
        ),
        Feature(
            slug = "voice_tracking",
            group = ANALYTICS_GROUP,
            label = "Voice and call tracking",
            description = "Stops the events sent when someone starts or stops speaking or listening, " +
                "and the interval that reported voice state.",
            functionIds = listOf(79182, 79183, 151493)
        ),
        Feature(
            slug = "impression_tracking",
            group = ANALYTICS_GROUP,
            label = "Impression tracking",
            description = "Stops impressions being recorded, and the flag that exported the telemetry " +
                "ring every half second.",
            functionIds = listOf(26143, 49336)
        ),
        Feature(
            slug = "crash_upload",
            group = CRASH_GROUP,
            label = "Crash reporting",
            description = "Stops the crash reporter starting at all, and with it every crash, " +
                "exception and logged message it would have sent.",
            functionIds = listOf(24047, 24048, 24049, 24053, 24054, 24062)
        ),
        Feature(
            slug = "crash_context",
            group = CRASH_GROUP,
            label = "What a crash report would carry",
            description = "Stops the identity, tags, context, breadcrumbs and feature flags that would " +
                "be attached to a report.",
            functionIds = listOf(21358, 24043, 24044, 24045, 24046, 24050, 24051, 24055)
        ),
        Feature(
            slug = "performance_metrics",
            group = PERFORMANCE_GROUP,
            label = "Performance metrics",
            description = "Stops the app sampling its own performance and uploading the result, " +
                "including the once-a-second process sampler.",
            functionIds = listOf(20586, 20588, 35577, 35578, 35579, 85532, 107938, 108049, 108052, 108053, 108074, 108075)
        ),
        Feature(
            slug = "call_sampling",
            group = PERFORMANCE_GROUP,
            label = "Call quality sampling",
            description = "Stops the samplers that run during a call to measure voice quality, " +
                "responsiveness and video effects.",
            functionIds = listOf(91461, 91810, 91826, 91845)
        ),
        Feature(
            slug = "session_telemetry",
            group = TELEMETRY_GROUP,
            label = "Session and network statistics",
            description = "Stops the app recording network, session and message-cache statistics, and " +
                "the heartbeat that reported them.",
            functionIds = listOf(41715, 41716, 41717, 41719, 41720, 41721, 41748, 41904, 41905, 41906, 81241)
        ),
        Feature(
            slug = "gateway_telemetry",
            group = TELEMETRY_GROUP,
            label = "Gateway connection reporting",
            description = "Stops the gateway connection being logged, measured and reported, and the " +
                "patched socket that read every message.",
            functionIds = listOf(65598, 65599, 65600, 65601, 81275)
        ),
        Feature(
            slug = "startup_timing",
            group = STARTUP_GROUP,
            label = "Startup timing",
            description = "Stops the app recording how long startup takes, milestone by milestone.",
            functionIds = listOf(18854, 18856, 18858, 18866, 18894, 18895, 18896, 18897, 18898, 18899, 18900, 18903)
        ),
        Feature(
            slug = "debug_logging",
            group = LOGGING_GROUP,
            label = "Debug logs",
            description = "Silences the in-app debug log at every level, the aggregator that fed it and " +
                "the file it wrote to.",
            functionIds = listOf(18823, 81317, 81383, 81384, 81385, 81386, 81387, 81390, 81392)
        ),
        Feature(
            slug = "device_fingerprint",
            group = FINGERPRINT_GROUP,
            label = "Device fingerprinting",
            description = "Stops the handler that computed a fingerprint of your device for every " +
                "tracked event.",
            functionIds = listOf(20435)
        ),
        Feature(
            slug = "markup_cache_bound",
            group = STABILITY_GROUP,
            label = "Bound the message cache",
            description = "Caps the cache the app fills while drawing messages. It had no limit " +
                "and its expiry was refreshed on every read, so it grew for as long as the app " +
                "ran, which is memory pressure the app cannot recover.",
            functionIds = listOf(8121)
        ),
        Feature(
            slug = "leftover_stubs",
            group = LEFTOVER_GROUP,
            label = "Stubs by function id",
            description = "Four functions stubbed by id. This release moved what " +
                "those ids hold, so three of the stubs change nothing you can see; the other " +
                "stands in for a typing-indicator hook. Switching this off leaves all four as " +
                "Discord wrote them.",
            functionIds = listOf(14391, 57893, 61076, 62665)
        )
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
