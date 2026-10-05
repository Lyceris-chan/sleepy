package dev.sleepy.app.patches

import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.patches.DiscordHermesBundlePatch.FunctionPatch

/**
 * What every function in [DiscordHermesBundlePatch.PATCHES] does to the app, in the user's terms.
 *
 * The Hermes set is one switch over 204 functions, which is too coarse for the choices a
 * user can make: "hide the gift button but keep quests" is not expressible when both live behind
 * the same switch. This table supplies the per-item list for those functions: each function's
 * feature group, and a line stating what patching it does.
 *
 * The entries come from the desktop reference's own 349.5 tables in
 * `quirky-noether/discord/patches/core.py` (TARGETS, PROMISE_TARGETS, FALSE_TARGETS, ZERO_TARGETS,
 * NULL_TARGETS, OBJECT_FALSE_TARGETS and the EDITS list), whose comments describe what each id is
 * and what shape its stub has to return. The descriptions this file carried for 348.5 are mapped
 * onto the 349.5 ids by the reference's own id rewrites, by function name where the name survives,
 * and by module and closure chain for the components the React Compiler emitted twice; both halves
 * of a pair carry the same description. Entries are in [DiscordHermesBundlePatch.PATCHES] order
 * and cover its function ids; a test asserts both, because a function that this table describes
 * but the patch table does not carry, or one the patch table carries and this table does not
 * describe, is a mismatch the test reports.
 *
 * Five entries are grouped under STALE: the reference's 349.5 table kept those ids from 348.5
 * without retargeting them at the functions the 348.5 entries named, and the desktop build stubbed
 * whatever function sits at each id now. Those entries state the function the id actually holds in
 * 349.5 and what the stub does to it, checked against the bundle's bytecode, instead of repeating
 * an explanation that no longer fits.
 * Three more entries (the anonymous NetworkStats callbacks at 40426-40428) state what the body at
 * the id does, because the reference lists them without names.
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
     * An item used to be one function, which put 204 rows in front of a user with 95 of them
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
        entry(7762, "Clamps the entry count of the message-markup cache. It was built with no " +
            "limit and with its expiry clock refreshed on every read, so it grew for as long as " +
            "the app ran, on the path that draws every message."),
        entry(13894, "349.5 no longer has a startup initializer at this id: it is the " +
            "module factory for modules/telemetry_ring/native/channels/NormalTelemetry.tsx. The " +
            "stub returns undefined without running the factory, so the module never assigns " +
            "its default export and an importer sees no NormalTelemetry channel."),
        entry(14786, "Gives the Settings Nitro / Manage Nitro row the always-false " +
            "predicate that hides it. Its backend is already answered with a 204, so the row led " +
            "to a screen that could do nothing."),
        entry(14790, "Gives the Settings Manage Plan row the always-false predicate " +
            "that hides it."),
        entry(14797, "Gives the Settings Server Boost row the always-false " +
            "predicate that hides it."),
        entry(15698, "Gives the Settings CollectiblesShop route the always-false predicate " +
            "that hides its row. This def had none of its own, so the route object itself is " +
            "edited, the same lever that removed the other billing rows."),
        entry(18172, "Stops the log aggregator from feeding the in-app debug panel's log " +
            "buffer."),
        entry(18203, "Stops the time-to-interactive tracker recording a " +
            "milestone's start."),
        entry(18205, "Stops the time-to-interactive tracker recording a " +
            "milestone's end."),
        entry(18207, "Stops the time-to-interactive tracker storing a value for " +
            "a milestone."),
        entry(18215, "Stops the time-to-interactive tracker recording a " +
            "measurement."),
        entry(18245, "Stops the tracing recorder appending its formatted line to " +
            "the in-memory startup log."),
        entry(18246, "Stops startup milestone marks being recorded, one of the " +
            "recorders called at every startup step."),
        entry(18247, "Stops the milestone mark-and-log recorder used by the " +
            "traced operations."),
        entry(18248, "Stops import-detail records being added to the startup " +
            "trace."),
        entry(18249, "Stops delta marks from being added to the startup trace."),
        entry(18250, "Stops timestamped marks from being recorded in the startup " +
            "trace."),
        entry(18251, "Stops detail records from being added to the startup " +
            "trace."),
        entry(18254, "Stops the server-issued trace id from being stored on the " +
            "startup trace."),
        entry(19786, "Stops the fingerprint handler, which computed the old and new " +
            "device fingerprint for every tracked event and reported the transition."),
        entry(19937, "Returns zero from the libdiscore action-telemetry sample rate, " +
            "which the server controls, so the client collects no action payloads whatever " +
            "treatment it is assigned."),
        entry(19939, "Returns false from the libdiscore action-telemetry gate, which " +
            "runs on every redux action and decides whether to collect that action's serialized " +
            "payload, so no payload is collected."),
        entry(20709, "Stops Sentry's own breadcrumb collector, which recorded one for " +
            "every Flux dispatcher action, so the collection stops at the source rather than at " +
            "the sender."),
        entry(23353, "Stops Sentry attaching the signed-in user's identity to error " +
            "reports."),
        entry(23354, "Stops Sentry clearing the user identity it would otherwise have " +
            "attached."),
        entry(23355, "Stops tags being attached to the Sentry scope, so error reports " +
            "carry no user-defined tags."),
        entry(23356, "Stops extra context being attached to the Sentry scope."),
        entry(23357, "Stops JavaScript exceptions being reported to Sentry, so stack " +
            "traces stay on the device."),
        entry(23358, "Stops JavaScript crash reports being sent to Sentry."),
        entry(23359, "Stops manually logged messages being sent to Sentry."),
        entry(23360, "Stops feature-flag names being recorded on the Sentry scope."),
        entry(23361, "Stops Sentry's JavaScript API adding breadcrumbs, which is what " +
            "recorded UI clicks and navigations."),
        entry(23363, "Stops the deliberate crash call from reaching Sentry."),
        entry(23364, "Stops the memory-warning report from reaching Sentry."),
        entry(23365, "Stops the crash-handled marker being recorded on the Sentry scope."),
        entry(23372, "Stops the Sentry JavaScript SDK from initializing at all, so no " +
            "client is bound and none of its automatic integrations (global error handlers, " +
            "breadcrumbs, promise-rejection tracking) are installed."),
        entry(23431, "Silences the [Analytics] debug reporter, so the client stops " +
            "writing its analytics events into the debug log."),
        entry(23494, "Silences the analytics store's own tracking closure, which emits " +
            "events independently of the central emitter."),
        entry(23502, "Silences the [Analytics] network-action reporter, so per-request " +
            "analytics are never emitted."),
        entry(25406, "Returns null from the avatar decoration data " +
            "transform, the choke point every caller that brings a decoration into the client " +
            "passes through. Those callers assign the result without reading it, and the " +
            "function already returns null on its own empty paths, so no decoration reaches " +
            "the client."),
        entry(25451, "Returns false from the analytics experiment flag, so the 500 ms " +
            "telemetry-ring export loop can never start."),
        entry(33861, "Silences the emitter behind the forum and clickstream trackers, " +
            "so those impressions are never sent."),
        entry(34036, "Stops the app-rating survey poll, which fetched the survey and " +
            "emitted analytics for it."),
        entry(34868, "Stops the monitoring agent's counter, which about twenty-five " +
            "modules call as they do their work."),
        entry(34869, "Stops the monitoring agent's distribution sample."),
        entry(34870, "Stops the monitoring agent flushing what it accumulated to the " +
            "two-minute /metrics/v2 upload."),
        entry(38875, "Returns false from the storefront capability check, which hides the " +
            "guild sidebar's game-shop row and blocks the redirect into it."),
        entry(39222, "Returns false from the Guild Shop visibility hook, one of " +
            "the two visibility gates the creator-monetisation guild shop module holds. The " +
            "hook's callers test for exactly false, which is the shape this stub returns."),
        entry(39223, "Returns false from the Guild Shop visibility hook, one of " +
            "the two visibility gates the creator-monetisation guild shop module holds. The " +
            "hook's callers test for exactly false, which is the shape this stub returns."),
        entry(39224, "Returns false from the plain isGuildShopVisibleInGuild " +
            "predicate exported beside the visibility hook, so a caller that asks whether the " +
            "guild shop is visible is answered no."),
        entry(39225, "Returns false from the Guild Shop preview visibility hook, " +
            "the second of the module's two visibility gates, so the preview surface stays " +
            "hidden. Its callers test for exactly false."),
        entry(39226, "Returns false from the Guild Shop preview visibility hook, " +
            "the second of the module's two visibility gates, so the preview surface stays " +
            "hidden. Its callers test for exactly false."),
        entry(40422, "Stops the network-statistics sampler restarting its " +
            "timers when the app changes state."),
        entry(40423, "Stops the network-statistics recorder writing its " +
            "accumulated events to device storage."),
        entry(40424, "Stops the network-statistics sampler's own track(), " +
            "which queued events locally."),
        entry(40426, "Stops the network-statistics sampler's app-state " +
            "listener, which reports to the sampler whether each app-state change leaves the app " +
            "active."),
        entry(40427, "Stops the network-statistics sampler's per-message " +
            "analytics accumulator, which adds each message's send-analytics duration and queue " +
            "size to its totals."),
        entry(40428, "Stops the network-statistics sampler's per-message " +
            "counter, whose whole body increments the sampler's running count."),
        entry(40455, "Stops the session heartbeat scheduler being initialized, " +
            "so its timer, its periodic API ping and its breadcrumb are never created."),
        entry(40613, "Stops the message-cache statistic that counted channel " +
            "fetches starting."),
        entry(40614, "Stops the message-cache statistic that counted a channel " +
            "being served locally."),
        entry(40615, "Stops the message-cache statistic that counted a channel " +
            "being fetched over the network."),
        entry(43768, "No-ops the action-sheet opener every upsell bottom sheet is " +
            "launched through, across its nine call sites."),
        entry(45217, "Returns false from the guild-tag display hook, so callers " +
            "that decide whether to build a tag row build none. The nulled tag components hide " +
            "the chips themselves; this also stops them being constructed."),
        entry(45218, "Returns false from the guild-tag display hook, so callers " +
            "that decide whether to build a tag row build none. The nulled tag components hide " +
            "the chips themselves; this also stops them being constructed."),
        entry(45222, "Returns false from the plain guild-tag display predicate, " +
            "so a caller that asks whether to show a guild tag is answered no."),
        entry(45460, "Stops the storefront fetching products by SKU id. The " +
            "implementation is stubbed rather than the exported trampoline, whose body Hermes " +
            "shares with dozens of unrelated API calls."),
        entry(45488, "Returns null from the collectible profile frame " +
            "component, so no profile frame is drawn. The reference lists it by module and " +
            "name only, with no further detail."),
        entry(45489, "Returns null from the collectible profile frame " +
            "component, so no profile frame is drawn. The reference lists it by module and " +
            "name only, with no further detail."),
        entry(45549, "Returns an empty array from the profile badges hook, so the " +
            "badge row has no badges to render. The hook already returns an empty array on its " +
            "own empty paths, so its callers handle the shape."),
        entry(45550, "Returns an empty array from the profile badges hook, so the " +
            "badge row has no badges to render. The hook already returns an empty array on its " +
            "own empty paths, so its callers handle the shape."),
        entry(45901, "Stops the guild-affinity fetch that ran on every connection " +
            "open. It was found by logging what the blocklist answered with a 204, and it hits an " +
            "endpoint that is already blocked."),
        entry(47109, "Stubs the game-profile shop carousel, so the carousel a game " +
            "profile page renders draws nothing. The carousel sits outside the shop screen, " +
            "which is why stubbing the screen did not remove it."),
        entry(47110, "Stubs the game-profile shop carousel, so the carousel a game " +
            "profile page renders draws nothing. The carousel sits outside the shop screen, " +
            "which is why stubbing the screen did not remove it."),
        entry(47123, "Stops impression tracking: this path builds its own trackMaker " +
            "and posts to /science without going through the central emitter the other stubs " +
            "cover."),
        entry(47225, "Stops the storefront price fetch for an application."),
        entry(47226, "Stops the storefront price fetch for SKU ids."),
        entry(47308, "Returns null from the animated decoration layer that plays over " +
            "the profile card, so the looping video or composite is never drawn. The profile " +
            "picture decoration is a different module and is untouched."),
        entry(47309, "Returns null from the animated decoration layer that plays over " +
            "the profile card, so the looping video or composite is never drawn. The profile " +
            "picture decoration is a different module and is untouched."),
        entry(47349, "Returns null from the collectible nameplate component, " +
            "so no nameplate is drawn. The reference lists it by module and name only, with " +
            "no further detail."),
        entry(47350, "Returns null from the collectible nameplate component, " +
            "so no nameplate is drawn. The reference lists it by module and name only, with " +
            "no further detail."),
        entry(47611, "Stops the fetch of collections with their products."),
        entry(49760, "No-ops the modal opener every premium upsell modal is " +
            "launched through, across its sixteen call sites."),
        entry(49956, "349.5 no longer has the Nitro upsell button at this id: it is the " +
            "SHOW_CONFIRM_MODAL handler that " +
            "modules/vibegrations/lib/vibegrationsPreviewNativeSurfaces.tsx registers with its " +
            "RPC interceptor. The stub answers the command with an object carrying the " +
            "{confirmed: false} result the interceptor reads, so an agent that asks for a " +
            "confirmation is told it was not confirmed instead of the call throwing. The " +
            "reference build stubs this id to undefined, which the interceptor's .result read " +
            "turns into a TypeError."),
        entry(51631, "Returns null from the guild tag badge component, so no " +
            "badge chip is drawn next to a name."),
        entry(51632, "Returns null from the guild tag badge component, so no " +
            "badge chip is drawn next to a name."),
        entry(51633, "Returns null from the base guild tag chiplet, the component " +
            "the guild tag chips build on, so no chip is drawn through it."),
        entry(51634, "Returns null from the base guild tag chiplet, the component " +
            "the guild tag chips build on, so no chip is drawn through it."),
        entry(51635, "Returns null from the guild tag component, so no guild tag " +
            "chip is drawn next to a name."),
        entry(52476, "Removes the inline 'Get Nitro' button, the widest-reaching " +
            "single upsell component: eleven consumers, from the Go Live sheet to the sticker " +
            "detail view."),
        entry(52477, "Removes the inline 'Get Nitro' button, the widest-reaching " +
            "single upsell component: eleven consumers, from the Go Live sheet to the sticker " +
            "detail view."),
        entry(53460, "Stops the refresh of current quests that the UI drives on a " +
            "one-second timer while a quest is active."),
        entry(53461, "Stops the quest heartbeat being sent to the server."),
        entry(53469, "Stops the refresh of claimed quests."),
        entry(53470, "Stops the fetch of the quest waiting to be delivered."),
        entry(53471, "Stops the fetch of the earned quest waiting to be delivered."),
        entry(55149, "Stops the storefront configuration fetch."),
        entry(55153, "Stops the storefront SKU lookup for an application."),
        entry(55351, "Returns a falsy value from the one real Spotify gate. Every call " +
            "site tests it in an if, so this removes the remaining Spotify branding: the " +
            "presence, 'Play on Spotify', the Spotify embed in activity cards and the outbound " +
            "Spotify activity."),
        entry(56206, "Returns a falsy value for the flag that gates quest items in the " +
            "activity panel."),
        entry(56456, "Removes the modal that awards orbs for a completed quest."),
        entry(56457, "Removes the modal that awards orbs for a completed quest."),
        entry(57616, "Returns null from the track renderer, so a friend's Spotify " +
            "presence renders nothing. Stubbing the listening check did not cover this, which is " +
            "why other people's songs stayed visible."),
        entry(57635, "Stops the subscription to Spotify player-state notifications, " +
            "which polled api.spotify.com on launch."),
        entry(57640, "Returns false from the check that asks the on-device Spotify app " +
            "whether Discord may talk to it over a local protocol. That path never touches the " +
            "network, so blocking Spotify hosts alone could not reach it; reporting it " +
            "unregistered leaves nothing to broadcast."),
        entry(58239, "349.5 no longer has the gift purchase button at this id: it is " +
            "the uncompiled variant of the useTypingUserIdsForDisplay hook in " +
            "modules/chat/native/TypingIndicator.tsx, and the React Compiler experiment defaults " +
            "off, so this is the variant the app runs. The stub returns an empty array, the " +
            "hook's own shape with no typing users, so the chat input's " +
            "hasTypingIndicatorContent read of its length finds 0 instead of throwing. The " +
            "reference build stubs this id to undefined, which that read turns into a TypeError."),
        entry(59199, "Removes the chat-input button that is a gift button or a thread " +
            "button depending on the conversation."),
        entry(59200, "Removes the chat-input button that is a gift button or a thread " +
            "button depending on the conversation."),
        entry(59211, "Removes the gift button in the chat input bar, which sat next to the " +
            "message box."),
        entry(59212, "Removes the gift button in the chat input bar, which sat next to the " +
            "message box."),
        entry(61440, "349.5 no longer has the profile edit screen at this id: it is the " +
            "compiled variant of the guild notification action sheet in " +
            "modules/notifications/settings/native/" +
            "NotificationSettingsMessageUnreadGuildActionSheet.tsx. The app selects that " +
            "variant only when the React Compiler experiment is enabled, and this build leaves " +
            "the experiment off by default, so the stub changes nothing for a stock client."),
        entry(62045, "The same orb gate under its plain name. Hermes deduplicated the two " +
            "exports into one body, so patching either one turns orbs off; the reference verifies " +
            "the alias set on every run."),
        entry(62046, "Returns {enabled: false} from the gate every orb surface reads, which " +
            "removes orbs at the source rather than hiding each screen that shows them. Hermes " +
            "shares this body with isVirtualCurrencyEnabled, so that name is disabled with it."),
        entry(62162, "Stubs the Shop This Look action sheet, so it opens empty if its " +
            "opener still runs. The opener is left as it is because its body is shared with an " +
            "unrelated display-name feature."),
        entry(62163, "Stubs the Shop This Look action sheet, so it opens empty if its " +
            "opener still runs. The opener is left as it is because its body is shared with an " +
            "unrelated display-name feature."),
        entry(62179, "Stubs the Shop This Look marketing coachmark, so the prompt that " +
            "points at the feature is not built. It lives outside the shop screen, so stubbing " +
            "the screen did not remove it."),
        entry(62180, "Stubs the Shop This Look marketing coachmark, so the prompt that " +
            "points at the feature is not built. It lives outside the shop screen, so stubbing " +
            "the screen did not remove it."),
        entry(62435, "349.5 no longer has the quest orb shop carousel at this id: it is " +
            "the compiled variant of the badge row in " +
            "modules/badges/native/BadgeDirectoryNuxCoachmark.tsx, which renders the game-time, " +
            "streaming and game-diversity tier badges. The app selects that variant only when " +
            "the React Compiler experiment is enabled, and this build leaves the experiment off " +
            "by default, so the stub changes nothing for a stock client."),
        entry(62714, "Forces the wishlist tab index to -1, so the profile's segmented " +
            "control has no Wishlist tab to select."),
        entry(62758, "Returns null from the wishlist grid a profile renders, so the " +
            "grid of wishlisted items shown as a profile section is not drawn. This is a " +
            "different surface from the profile's Wishlist tab."),
        entry(62766, "Returns null from the wishlist suggestions grid a profile " +
            "renders, so the suggested-items grid is not drawn. The component already returns " +
            "null when its own mobile gate is off, so callers handle the shape."),
        entry(62767, "Returns null from the wishlist suggestions grid a profile " +
            "renders, so the suggested-items grid is not drawn. The component already returns " +
            "null when its own mobile gate is off, so callers handle the shape."),
        entry(62778, "Removes the add-to-wishlist button in the grid layout."),
        entry(62779, "Removes the add-to-wishlist button in the grid layout."),
        entry(62783, "Removes the add-to-wishlist button on item cards."),
        entry(62880, "Removes the orb price tag shown on items that can be bought with " +
            "orbs."),
        entry(62881, "Removes the orb price tag shown on items that can be bought with " +
            "orbs."),
        entry(62907, "Removes the standalone gift button component, one of the separate " +
            "components stubbing the inline Nitro button did not cover."),
        entry(62908, "Removes the standalone gift button component, one of the separate " +
            "components stubbing the inline Nitro button did not cover."),
        entry(63721, "Removes the gift purchase button, so a gift cannot be bought from " +
            "the UI."),
        entry(63722, "Removes the gift purchase button, so a gift cannot be bought from " +
            "the UI."),
        entry(64027, "Stops the gateway READY payload being logged: the " +
            "payload carries the whole guild list, and the reference records it being stringified " +
            "around fourteen times on one connect in a hundred."),
        entry(64028, "Stops the connection-path lookup used to label the " +
            "gateway analytics event."),
        entry(64029, "Stops the READY payload being measured for its analytics " +
            "byte size."),
        entry(64030, "Stops the gateway-connected analytics event."),
        entry(66426, "Stops the analytics action handler, which built an event with " +
            "its key, properties, fingerprint and timestamp for every tracked action."),
        entry(67311, "Stubs the Edit User Profile entry in settings."),
        entry(67312, "Stubs the Edit User Profile entry in settings."),
        entry(68573, "Returns false from the Manage Subscriptions row's visibility " +
            "predicate."),
        entry(68574, "Returns false from the Manage Subscriptions row's visibility " +
            "predicate."),
        entry(68590, "Returns false from the Gift Inventory row's own visibility " +
            "predicate."),
        entry(68593, "Returns false from the Quests settings row's own visibility " +
            "predicate. This is what actually removes the row: the settings harness hides any " +
            "entry whose predicate returns exactly false."),
        entry(68600, "Stubs the Quest Home screen the Quests settings row navigates to."),
        entry(68601, "Stubs the Quest Home screen the Quests settings row navigates to."),
        entry(68628, "Returns null from the Quest Home screen, so a route that still " +
            "navigates there renders nothing. Every mobile entry point is gated separately; " +
            "this covers the screen itself, which a route name can still reach."),
        entry(68629, "Returns null from the Quest Home screen, so a route that still " +
            "navigates there renders nothing. Every mobile entry point is gated separately; " +
            "this covers the screen itself, which a route name can still reach."),
        entry(68858, "Returns false from the mobile quest-dock hook, so the dock is not " +
            "shown. The reference's alternative lever, the broad quest-eligibility check, shares " +
            "its body with six unrelated capability checks and cannot be patched."),
        entry(69421, "Returns false from the Server Subscriptions row's visibility " +
            "predicate."),
        entry(69606, "Returns false from the Restore Subscription row's visibility " +
            "predicate."),
        entry(69607, "Returns false from the Restore Subscription row's visibility " +
            "predicate."),
        entry(70912, "Removes the You-tab orb balance widget's menu, one of the screens the " +
            "orb gate sits in front of."),
        entry(70917, "Removes the wrapper component that builds the orb balance widget."),
        entry(70918, "Removes the wrapper component that builds the orb balance widget."),
        entry(70961, "Stops the survey override from taking effect, so a server-side " +
            "override cannot swap the app-rating survey the client is showing."),
        entry(70962, "Stops the survey-dismiss action, which both updated the store and " +
            "emitted its APP_NOTICE analytics event."),
        entry(70963, "Stops the survey-seen report, which told Discord the survey had " +
            "been shown and emitted analytics for it."),
        entry(71374, "Stubs the collectibles shop screen component the Settings route " +
            "renders, so the route draws nothing. The shop also has a deep-link entry the " +
            "route edit cannot close, which is why the screen component is stubbed."),
        entry(71375, "Stubs the collectibles shop screen component the Settings route " +
            "renders, so the route draws nothing. The shop also has a deep-link entry the " +
            "route edit cannot close, which is why the screen component is stubbed."),
        entry(71379, "Stubs the shop screen's internal component, leaving the screen with " +
            "nothing to render."),
        entry(71380, "Stubs the shop screen's internal component, leaving the screen with " +
            "nothing to render."),
        entry(71381, "Stubs the collectibles shop screen itself, so there is no shop tab to " +
            "browse."),
        entry(71382, "Stubs the collectibles shop screen itself, so there is no shop tab to " +
            "browse."),
        entry(71516, "Returns false from the usePredicate closure the Quests toggles " +
            "in Data & Privacy carry, so those settings rows are hidden. The two toggles' " +
            "predicates are byte-identical and Hermes stores them once, so either id changes " +
            "both."),
        entry(71528, "Returns false from the usePredicate closure the Quests toggles " +
            "in Data & Privacy carry, so those settings rows are hidden. The two toggles' " +
            "predicates are byte-identical and Hermes stores them once, so either id changes " +
            "both."),
        entry(72534, "Returns null from the voice guild tag component, so no " +
            "guild tag chip is drawn in a voice channel."),
        entry(72535, "Returns null from the voice guild tag component, so no " +
            "guild tag chip is drawn in a voice channel."),
        entry(75525, "Stops the AutoAnalytics navigator hook that emitted an event " +
            "each time a screen was built."),
        entry(75526, "Stops the other half of the AutoAnalytics navigator hook, which " +
            "emitted an event each time the current screen changed."),
        entry(75650, "Rebuilds the You screen's floating navigation so the Quests button " +
            "is never built. The button is one element of a list rather than a function that can " +
            "return false, so the body is edited instead of stubbed."),
        entry(75687, "Removes the button that opens the collectibles shop."),
        entry(75688, "Removes the button that opens the collectibles shop."),
        entry(75689, "Removes the coachmark that points at the mobile shop button."),
        entry(75690, "Removes the coachmark that points at the mobile shop button."),
        entry(77257, "Stops the per-request tracker, which ran URL matching and " +
            "appended to the telemetry ring for every HTTP request."),
        entry(77458, "Stops _trackStartSpeaking from computing game metadata and " +
            "packet stats when someone starts speaking in a voice call."),
        entry(77459, "Stops _trackStartListening from computing telemetry payloads " +
            "when someone starts listening in a voice call."),
        entry(79457, "Stops the app-state update handler the reference lists " +
            "with the message-cache recorders, so it no longer runs on every app state change."),
        entry(79491, "Stops the patched global WebSocket being installed, " +
            "whose per-message handler parsed every gateway frame a second time and appended it " +
            "to the disabled telemetry ring."),
        entry(79533, "Stops the timer monitor that formatted slow-timer records into the " +
            "log, which only the stubbed analytics emitter read."),
        entry(79599, "Silences one of the logger's debug levels, so those lines no " +
            "longer enter the in-memory JavaScript debug log. Only warn and error are kept, so " +
            "genuine problems still surface."),
        entry(79600, "Silences log(), which is where most of the in-memory debug log was " +
            "coming from."),
        entry(79601, "Silences the logger's verbose level (the 'dangerously' variant), " +
            "keeping it out of the debug log."),
        entry(79602, "Silences verbose, one of the two levels that dominated the " +
            "in-memory debug log."),
        entry(79603, "Silences info, so informational lines no longer enter the debug " +
            "log."),
        entry(79606, "Silences trace, so trace lines no longer enter the debug log."),
        entry(79608, "Silences the file-only logger, so nothing is written to the " +
            "logger's file sink."),
        entry(83581, "Silences the central science-event emitter every feature calls, " +
            "so no event is built or sent and no per-event CPU/memory sample is taken. Its " +
            "callers await it, so the stub still returns a resolved promise."),
        entry(83586, "Stops the single POST behind the /science and /beaker queues, so " +
            "the events the stubs above would have queued are never uploaded either."),
        entry(83707, "Stops the once-a-second process sampler, which made three native " +
            "bridge calls per second for CPU and memory whose only consumers null-guard."),
        entry(89454, "Stops the voice-quality payload builder that assembled and sent " +
            "its statistics every five minutes."),
        entry(112318, "Stops the 60-second session flusher that shipped session " +
            "aggregates."),
        entry(112429, "Stops the metrics aggregator adding samples to its buckets."),
        entry(112432, "Stops the metrics aggregator flushing its buckets on its " +
            "interval."),
        entry(112433, "Stops the metrics aggregator capturing a metrics snapshot."),
        entry(112454, "Stops the browser-side metrics aggregator adding samples to its " +
            "buckets."),
        entry(112455, "Stops the browser-side metrics aggregator flushing them."),
        entry(113052, "Rebuilds the profile activity list without friends' Spotify " +
            "listened-session entries. 349.5 has a second copy of the filter in the same module, " +
            "reachable through a different parent, and the reference edits both so Spotify " +
            "entries do not survive on some surfaces."),
        entry(113177, "Rebuilds other users' profile tab list so it builds Main and " +
            "Activity only, with no Board and no Wishlist tab. The reference edits the " +
            "isReactCompilerEnabled() == false variant of the profile content component."),
        entry(115029, "Stops the voice-quality sampler that ran once a second during a " +
            "call."),
        entry(115037, "Stops the system-responsiveness sampler, the second of the " +
            "voice-call samplers."),
        entry(115050, "Stops the video-effect and system-resource sampler that ran on " +
            "every call-statistics callback."),
        entry(127527, "Rebuilds the profile section tab list as Main/Board/Activity and " +
            "never Wishlist. The tab is one element of a list, not a function that can return " +
            "false, so the body is edited rather than stubbed."),
        entry(132693, "Stops QuestFetchManager installing the recurring interval that " +
            "refetched quests forever, which against already-blocked endpoints was pure timer and " +
            "network churn."),
        entry(142676, "Rebuilds the profile activity list without friends' Spotify " +
            "listened-session entries."),
        entry(148336, "Stops the 60-second voice state interval callback that " +
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
            functionIds = listOf(43768, 49760, 52476, 52477)
        ),
        Feature(
            slug = "collectibles_shop",
            group = SHOP_GROUP,
            label = "The collectibles shop",
            description = "Stops the shop screen drawing, so no tab or route opens it.",
            functionIds = listOf(71374, 71375, 71379, 71380, 71381, 71382)
        ),
        Feature(
            slug = "shop_entry_points",
            group = SHOP_GROUP,
            label = "Shop buttons and prompts",
            description = "Removes the button that opens the shop, the prompt that points at it, and " +
                "the settings row that leads there.",
            functionIds = listOf(15698, 62179, 62180, 75687, 75688, 75689, 75690)
        ),
        Feature(
            slug = "shop_this_look",
            group = SHOP_GROUP,
            label = "Shop This Look",
            description = "Empties the Shop This Look sheet, so opening it shows nothing.",
            functionIds = listOf(62162, 62163)
        ),
        Feature(
            slug = "game_profile_shop",
            group = SHOP_GROUP,
            label = "Game profile shop",
            description = "Stops the shop carousel drawing on a game's profile and removes the guild " +
                "sidebar's row for it.",
            functionIds = listOf(38875, 47109, 47110)
        ),
        Feature(
            slug = "guild_shop",
            group = SHOP_GROUP,
            label = "Guild shop",
            description = "Hides the guild shop and its preview by answering false to every check " +
                "that decides whether to show one.",
            functionIds = listOf(39222, 39223, 39224, 39225, 39226)
        ),
        Feature(
            slug = "orbs",
            group = SHOP_GROUP,
            label = "Orbs",
            description = "Turns orbs off at the gate every orb surface reads, and removes the orb " +
                "balance, price tags and reward dialogs.",
            functionIds = listOf(56456, 56457, 62045, 62046, 62880, 62881, 70912, 70917, 70918)
        ),
        Feature(
            slug = "storefront",
            group = SHOP_GROUP,
            label = "Store and product lookups",
            description = "Stops the app fetching store products, prices and collections, and the " +
                "guild data it loaded on every connection.",
            functionIds = listOf(45460, 45901, 47225, 47226, 47611, 55149, 55153)
        ),
        Feature(
            slug = "gift_buttons",
            group = GIFTS_GROUP,
            label = "Gift buttons",
            description = "Removes the gift button from the chat bar and the button that buys a gift.",
            functionIds = listOf(59199, 59200, 59211, 59212, 62907, 62908, 63721, 63722)
        ),
        Feature(
            slug = "billing_rows",
            group = BILLING_GROUP,
            label = "Subscription and billing rows",
            description = "Hides the settings rows for Nitro, managing a plan or subscription, server " +
                "boosts and subscriptions, the gift inventory and restoring a purchase.",
            functionIds = listOf(14786, 14790, 14797, 68573, 68574, 68590, 69421, 69606, 69607)
        ),
        Feature(
            slug = "avatar_decorations",
            group = LOOK_GROUP,
            label = "Avatar decorations",
            description = "Stops avatars carrying decorations, at the one transform every caller " +
                "passes a decoration through.",
            functionIds = listOf(25406)
        ),
        Feature(
            slug = "nameplates",
            group = LOOK_GROUP,
            label = "Nameplates",
            description = "Stops the collectible nameplate drawing next to a name.",
            functionIds = listOf(47349, 47350)
        ),
        Feature(
            slug = "profile_frames",
            group = LOOK_GROUP,
            label = "Profile frames",
            description = "Stops the collectible profile frame drawing around an avatar.",
            functionIds = listOf(45488, 45489)
        ),
        Feature(
            slug = "guild_tags",
            group = LOOK_GROUP,
            label = "Guild tags",
            description = "Stops guild tag chips, badges and voice-channel tags drawing, and answers " +
                "false to the checks that decide whether to show one.",
            functionIds = listOf(45217, 45218, 45222, 51631, 51632, 51633, 51634, 51635, 72534, 72535)
        ),
        Feature(
            slug = "profile_badges",
            group = PROFILE_GROUP,
            label = "Profile badges",
            description = "Returns no badges from the profile badge hook, so the badge row has nothing " +
                "to draw.",
            functionIds = listOf(45549, 45550)
        ),
        Feature(
            slug = "profile_effects",
            group = PROFILE_GROUP,
            label = "Animated profile effects",
            description = "Stops the animated layer that plays over a profile card.",
            functionIds = listOf(47308, 47309)
        ),
        Feature(
            slug = "edit_profile_row",
            group = PROFILE_GROUP,
            label = "The edit profile row",
            description = "Removes the Edit User Profile entry from settings.",
            functionIds = listOf(67311, 67312)
        ),
        Feature(
            slug = "quests_in_app",
            group = QUESTS_GROUP,
            label = "Quests in the app",
            description = "Removes the Quests screen, its dock, its navigation button, its settings row " +
                "and its toggles in Data & Privacy.",
            functionIds = listOf(56206, 68593, 68600, 68601, 68628, 68629, 68858, 71516, 71528, 75650)
        ),
        Feature(
            slug = "quest_fetching",
            group = QUESTS_GROUP,
            label = "Quest fetching",
            description = "Stops the app fetching, refreshing and re-polling quests in the background.",
            functionIds = listOf(53460, 53461, 53469, 53470, 53471, 132693)
        ),
        Feature(
            slug = "wishlist_tabs",
            group = WISHLIST_GROUP,
            label = "Wishlist tabs",
            description = "Removes the Wishlist tab from your profile and from other people's.",
            functionIds = listOf(62714, 113177, 127527)
        ),
        Feature(
            slug = "wishlist_grids",
            group = WISHLIST_GROUP,
            label = "Wishlist grids and buttons",
            description = "Stops the wishlist and suggestion grids drawing, and removes the buttons " +
                "that add an item to a wishlist.",
            functionIds = listOf(62758, 62766, 62767, 62778, 62779, 62783)
        ),
        Feature(
            slug = "spotify",
            group = SPOTIFY_GROUP,
            label = "Spotify integration",
            description = "Removes Spotify from the app: the presence a friend shows, the track " +
                "renderer, the player subscription and the listened-session entries on a profile.",
            functionIds = listOf(55351, 57616, 57635, 57640, 113052, 142676)
        ),
        Feature(
            slug = "rating_prompts",
            group = SURVEYS_GROUP,
            label = "App rating prompts",
            description = "Stops the app asking you to rate it, and the reports that told Discord the " +
                "prompt was shown or dismissed.",
            functionIds = listOf(34036, 70961, 70962, 70963)
        ),
        Feature(
            slug = "analytics_events",
            group = ANALYTICS_GROUP,
            label = "Analytics events",
            description = "Stops analytics events being built and sent, at the emitters every feature " +
                "calls and at the single request behind them.",
            functionIds = listOf(23431, 23494, 23502, 66426, 83581, 83586)
        ),
        Feature(
            slug = "screen_tracking",
            group = ANALYTICS_GROUP,
            label = "Screen and navigation tracking",
            description = "Stops the event sent each time a screen is built and the per-request " +
                "tracker that matched URLs for it.",
            functionIds = listOf(75525, 75526, 77257, 33861)
        ),
        Feature(
            slug = "voice_tracking",
            group = ANALYTICS_GROUP,
            label = "Voice and call tracking",
            description = "Stops the events sent when someone starts or stops speaking or listening, " +
                "and the interval that reported voice state.",
            functionIds = listOf(77458, 77459, 148336)
        ),
        Feature(
            slug = "impression_tracking",
            group = ANALYTICS_GROUP,
            label = "Impression tracking",
            description = "Stops impressions being recorded, and the flag that exported the telemetry " +
                "ring every half second.",
            functionIds = listOf(25451, 47123)
        ),
        Feature(
            slug = "crash_upload",
            group = CRASH_GROUP,
            label = "Crash reporting",
            description = "Stops the crash reporter starting at all, and with it every crash, " +
                "exception and logged message it would have sent.",
            functionIds = listOf(23357, 23358, 23359, 23363, 23364, 23372)
        ),
        Feature(
            slug = "crash_context",
            group = CRASH_GROUP,
            label = "What a crash report would carry",
            description = "Stops the identity, tags, context, breadcrumbs and feature flags that would " +
                "be attached to a report.",
            functionIds = listOf(20709, 23353, 23354, 23355, 23356, 23360, 23361, 23365)
        ),
        Feature(
            slug = "performance_metrics",
            group = PERFORMANCE_GROUP,
            label = "Performance metrics",
            description = "Stops the app sampling its own performance and uploading the result, " +
                "including the once-a-second process sampler.",
            functionIds = listOf(
                19937, 19939, 34868, 34869, 34870, 83707,
                112318, 112429, 112432, 112433, 112454, 112455
            )
        ),
        Feature(
            slug = "call_sampling",
            group = PERFORMANCE_GROUP,
            label = "Call quality sampling",
            description = "Stops the samplers that run during a call to measure voice quality, " +
                "responsiveness and video effects.",
            functionIds = listOf(89454, 115029, 115037, 115050)
        ),
        Feature(
            slug = "session_telemetry",
            group = TELEMETRY_GROUP,
            label = "Session and network statistics",
            description = "Stops the app recording network, session and message-cache statistics, and " +
                "the heartbeat that reported them.",
            functionIds = listOf(
                40422, 40423, 40424, 40426, 40427, 40428, 40455,
                40613, 40614, 40615, 79457
            )
        ),
        Feature(
            slug = "gateway_telemetry",
            group = TELEMETRY_GROUP,
            label = "Gateway connection reporting",
            description = "Stops the gateway connection being logged, measured and reported, and the " +
                "patched socket that read every message.",
            functionIds = listOf(64027, 64028, 64029, 64030, 79491)
        ),
        Feature(
            slug = "startup_timing",
            group = STARTUP_GROUP,
            label = "Startup timing",
            description = "Stops the app recording how long startup takes, milestone by milestone.",
            functionIds = listOf(
                18203, 18205, 18207, 18215, 18245, 18246,
                18247, 18248, 18249, 18250, 18251, 18254
            )
        ),
        Feature(
            slug = "debug_logging",
            group = LOGGING_GROUP,
            label = "Debug logs",
            description = "Silences the in-app debug log at every level, the aggregator that fed it and " +
                "the file it wrote to.",
            functionIds = listOf(18172, 79533, 79599, 79600, 79601, 79602, 79603, 79606, 79608)
        ),
        Feature(
            slug = "device_fingerprint",
            group = FINGERPRINT_GROUP,
            label = "Device fingerprinting",
            description = "Stops the handler that computed a fingerprint of your device for every " +
                "tracked event.",
            functionIds = listOf(19786)
        ),
        Feature(
            slug = "markup_cache_bound",
            group = STABILITY_GROUP,
            label = "Bound the message cache",
            description = "Caps the cache the app fills while drawing messages. It had no limit " +
                "and its expiry was refreshed on every read, so it grew for as long as the app " +
                "ran, which is memory pressure the app cannot recover.",
            functionIds = listOf(7762)
        ),
        Feature(
            slug = "leftover_stubs",
            group = LEFTOVER_GROUP,
            label = "Stubs the reference still ships",
            description = "Five functions the desktop reference stubs by id. This release moved what " +
                "those ids hold, so three of the stubs change nothing you can see; the other two " +
                "stand in for a confirmation prompt and a typing-indicator hook. Switching this off " +
                "leaves all five as Discord wrote them.",
            functionIds = listOf(13894, 49956, 58239, 61440, 62435)
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
