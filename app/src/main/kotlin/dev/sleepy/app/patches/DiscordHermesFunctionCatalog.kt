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
     * One patched function's place in the user-facing list.
     *
     * @param functionId The function's id in [DiscordHermesBundlePatch.PATCHES].
     * @param group The feature group the item appears under.
     * @param description The user-facing line stating what patching the function does.
     */
    data class Entry(
        val functionId: Int,
        val group: String,
        val description: String
    )

    // Feature groups. Declared here rather than inline so each group is named once and a typo in
    // an entry fails to compile instead of creating a group of one item.
    private const val ANALYTICS = "Analytics event emitters"
    private const val FINGERPRINT = "Device fingerprint tracking"
    private const val SURVEYS = "App-rating survey pop-ups"
    private const val LOGGING = "Debug logging"
    private const val SENTRY = "Sentry crash reporting"
    private const val STARTUP_TRACING = "Startup tracing and time-to-interactive"
    private const val METRICS = "Client metrics and quality sampling"
    private const val SESSION_TELEMETRY = "Session, network and message-cache telemetry"
    private const val QUESTS = "Quests: entry points and refresh timers"
    private const val GIFTS = "Gift buttons"
    private const val NITRO_UPSELLS = "Nitro upsell buttons, sheets and modals"
    private const val ORBS = "Orbs virtual currency"
    private const val SHOP = "Collectibles shop screens and entry points"
    private const val GUILD_SHOP = "Guild shop visibility gates"
    private const val COLLECTIBLES = "Collectible nameplates and profile frames"
    private const val GUILD_TAGS = "Guild tag chips and visibility gates"
    private const val AVATAR_DECORATIONS = "Avatar decorations"
    private const val STOREFRONT = "Storefront, promotion and guild-affinity fetches"
    private const val WISHLIST = "Wishlist buttons and profile tab"
    private const val BILLING_ROWS = "Billing and premium settings rows"
    private const val PROFILE = "Profile editing and animated profile card effects"
    private const val SPOTIFY = "Spotify presence and integration"
    private const val STALE = "Stale reference stubs"

    /** Every patched function, in the order the patch table applies them. */
    val ENTRIES: List<Entry> = listOf(
        entry(13894, STALE, "349.5 no longer has a startup initializer at this id: it is the " +
            "module factory for modules/telemetry_ring/native/channels/NormalTelemetry.tsx. The " +
            "stub returns undefined without running the factory, so the module never assigns " +
            "its default export and an importer sees no NormalTelemetry channel."),
        entry(14786, BILLING_ROWS, "Gives the Settings Nitro / Manage Nitro row the always-false " +
            "predicate that hides it. Its backend is already answered with a 204, so the row led " +
            "to a screen that could do nothing."),
        entry(14790, BILLING_ROWS, "Gives the Settings Manage Plan row the always-false predicate " +
            "that hides it."),
        entry(14797, BILLING_ROWS, "Gives the Settings Server Boost row the always-false " +
            "predicate that hides it."),
        entry(15698, SHOP, "Gives the Settings CollectiblesShop route the always-false predicate " +
            "that hides its row. This def had none of its own, so the route object itself is " +
            "edited, the same lever that removed the other billing rows."),
        entry(18172, LOGGING, "Stops the log aggregator from feeding the in-app debug panel's log " +
            "buffer."),
        entry(18203, STARTUP_TRACING, "Stops the time-to-interactive tracker recording a " +
            "milestone's start."),
        entry(18205, STARTUP_TRACING, "Stops the time-to-interactive tracker recording a " +
            "milestone's end."),
        entry(18207, STARTUP_TRACING, "Stops the time-to-interactive tracker storing a value for " +
            "a milestone."),
        entry(18215, STARTUP_TRACING, "Stops the time-to-interactive tracker recording a " +
            "measurement."),
        entry(18245, STARTUP_TRACING, "Stops the tracing recorder appending its formatted line to " +
            "the in-memory startup log."),
        entry(18246, STARTUP_TRACING, "Stops startup milestone marks being recorded, one of the " +
            "recorders called at every startup step."),
        entry(18247, STARTUP_TRACING, "Stops the milestone mark-and-log recorder used by the " +
            "traced operations."),
        entry(18248, STARTUP_TRACING, "Stops import-detail records being added to the startup " +
            "trace."),
        entry(18249, STARTUP_TRACING, "Stops delta marks from being added to the startup trace."),
        entry(18250, STARTUP_TRACING, "Stops timestamped marks from being recorded in the startup " +
            "trace."),
        entry(18251, STARTUP_TRACING, "Stops detail records from being added to the startup " +
            "trace."),
        entry(18254, STARTUP_TRACING, "Stops the server-issued trace id from being stored on the " +
            "startup trace."),
        entry(19786, FINGERPRINT, "Stops the fingerprint handler, which computed the old and new " +
            "device fingerprint for every tracked event and reported the transition."),
        entry(19937, METRICS, "Returns zero from the libdiscore action-telemetry sample rate, " +
            "which the server controls, so the client collects no action payloads whatever " +
            "treatment it is assigned."),
        entry(19939, METRICS, "Returns false from the libdiscore action-telemetry gate, which " +
            "runs on every redux action and decides whether to collect that action's serialized " +
            "payload, so no payload is collected."),
        entry(20709, SENTRY, "Stops Sentry's own breadcrumb collector, which recorded one for " +
            "every Flux dispatcher action, so the collection stops at the source rather than at " +
            "the sender."),
        entry(23353, SENTRY, "Stops Sentry attaching the signed-in user's identity to error " +
            "reports."),
        entry(23354, SENTRY, "Stops Sentry clearing the user identity it would otherwise have " +
            "attached."),
        entry(23355, SENTRY, "Stops tags being attached to the Sentry scope, so error reports " +
            "carry no user-defined tags."),
        entry(23356, SENTRY, "Stops extra context being attached to the Sentry scope."),
        entry(23357, SENTRY, "Stops JavaScript exceptions being reported to Sentry, so stack " +
            "traces stay on the device."),
        entry(23358, SENTRY, "Stops JavaScript crash reports being sent to Sentry."),
        entry(23359, SENTRY, "Stops manually logged messages being sent to Sentry."),
        entry(23360, SENTRY, "Stops feature-flag names being recorded on the Sentry scope."),
        entry(23361, SENTRY, "Stops Sentry's JavaScript API adding breadcrumbs, which is what " +
            "recorded UI clicks and navigations."),
        entry(23363, SENTRY, "Stops the deliberate crash call from reaching Sentry."),
        entry(23364, SENTRY, "Stops the memory-warning report from reaching Sentry."),
        entry(23365, SENTRY, "Stops the crash-handled marker being recorded on the Sentry scope."),
        entry(23372, SENTRY, "Stops the Sentry JavaScript SDK from initializing at all, so no " +
            "client is bound and none of its automatic integrations (global error handlers, " +
            "breadcrumbs, promise-rejection tracking) are installed."),
        entry(23431, ANALYTICS, "Silences the [Analytics] debug reporter, so the client stops " +
            "writing its analytics events into the debug log."),
        entry(23494, ANALYTICS, "Silences the analytics store's own tracking closure, which emits " +
            "events independently of the central emitter."),
        entry(23502, ANALYTICS, "Silences the [Analytics] network-action reporter, so per-request " +
            "analytics are never emitted."),
        entry(25406, AVATAR_DECORATIONS, "Returns null from the avatar decoration data " +
            "transform, the choke point every caller that brings a decoration into the client " +
            "passes through. Those callers assign the result without reading it, and the " +
            "function already returns null on its own empty paths, so no decoration reaches " +
            "the client."),
        entry(25451, ANALYTICS, "Returns false from the analytics experiment flag, so the 500 ms " +
            "telemetry-ring export loop can never start."),
        entry(33861, ANALYTICS, "Silences the emitter behind the forum and clickstream trackers, " +
            "so those impressions are never sent."),
        entry(34036, SURVEYS, "Stops the app-rating survey poll, which fetched the survey and " +
            "emitted analytics for it."),
        entry(34868, METRICS, "Stops the monitoring agent's counter, which about twenty-five " +
            "modules call as they do their work."),
        entry(34869, METRICS, "Stops the monitoring agent's distribution sample."),
        entry(34870, METRICS, "Stops the monitoring agent flushing what it accumulated to the " +
            "two-minute /metrics/v2 upload."),
        entry(38875, SHOP, "Returns false from the storefront capability check, which hides the " +
            "guild sidebar's game-shop row and blocks the redirect into it."),
        entry(39222, GUILD_SHOP, "Returns false from the Guild Shop visibility hook, one of " +
            "the two visibility gates the creator-monetisation guild shop module holds. The " +
            "hook's callers test for exactly false, which is the shape this stub returns."),
        entry(39223, GUILD_SHOP, "Returns false from the Guild Shop visibility hook, one of " +
            "the two visibility gates the creator-monetisation guild shop module holds. The " +
            "hook's callers test for exactly false, which is the shape this stub returns."),
        entry(39224, GUILD_SHOP, "Returns false from the plain isGuildShopVisibleInGuild " +
            "predicate exported beside the visibility hook, so a caller that asks whether the " +
            "guild shop is visible is answered no."),
        entry(39225, GUILD_SHOP, "Returns false from the Guild Shop preview visibility hook, " +
            "the second of the module's two visibility gates, so the preview surface stays " +
            "hidden. Its callers test for exactly false."),
        entry(39226, GUILD_SHOP, "Returns false from the Guild Shop preview visibility hook, " +
            "the second of the module's two visibility gates, so the preview surface stays " +
            "hidden. Its callers test for exactly false."),
        entry(40422, SESSION_TELEMETRY, "Stops the network-statistics sampler restarting its " +
            "timers when the app changes state."),
        entry(40423, SESSION_TELEMETRY, "Stops the network-statistics recorder writing its " +
            "accumulated events to device storage."),
        entry(40424, SESSION_TELEMETRY, "Stops the network-statistics sampler's own track(), " +
            "which queued events locally."),
        entry(40426, SESSION_TELEMETRY, "Stops the network-statistics sampler's app-state " +
            "listener, which reports to the sampler whether each app-state change leaves the app " +
            "active."),
        entry(40427, SESSION_TELEMETRY, "Stops the network-statistics sampler's per-message " +
            "analytics accumulator, which adds each message's send-analytics duration and queue " +
            "size to its totals."),
        entry(40428, SESSION_TELEMETRY, "Stops the network-statistics sampler's per-message " +
            "counter, whose whole body increments the sampler's running count."),
        entry(40455, SESSION_TELEMETRY, "Stops the session heartbeat scheduler being initialized, " +
            "so its timer, its periodic API ping and its breadcrumb are never created."),
        entry(40613, SESSION_TELEMETRY, "Stops the message-cache statistic that counted channel " +
            "fetches starting."),
        entry(40614, SESSION_TELEMETRY, "Stops the message-cache statistic that counted a channel " +
            "being served locally."),
        entry(40615, SESSION_TELEMETRY, "Stops the message-cache statistic that counted a channel " +
            "being fetched over the network."),
        entry(43768, NITRO_UPSELLS, "No-ops the action-sheet opener every upsell bottom sheet is " +
            "launched through, across its nine call sites."),
        entry(45217, GUILD_TAGS, "Returns false from the guild-tag display hook, so callers " +
            "that decide whether to build a tag row build none. The nulled tag components hide " +
            "the chips themselves; this also stops them being constructed."),
        entry(45218, GUILD_TAGS, "Returns false from the guild-tag display hook, so callers " +
            "that decide whether to build a tag row build none. The nulled tag components hide " +
            "the chips themselves; this also stops them being constructed."),
        entry(45222, GUILD_TAGS, "Returns false from the plain guild-tag display predicate, " +
            "so a caller that asks whether to show a guild tag is answered no."),
        entry(45460, STOREFRONT, "Stops the storefront fetching products by SKU id. The " +
            "implementation is stubbed rather than the exported trampoline, whose body Hermes " +
            "shares with dozens of unrelated API calls."),
        entry(45488, COLLECTIBLES, "Returns null from the collectible profile frame " +
            "component, so no profile frame is drawn. The reference lists it by module and " +
            "name only, with no further detail."),
        entry(45489, COLLECTIBLES, "Returns null from the collectible profile frame " +
            "component, so no profile frame is drawn. The reference lists it by module and " +
            "name only, with no further detail."),
        entry(45549, PROFILE, "Returns an empty array from the profile badges hook, so the " +
            "badge row has no badges to render. The hook already returns an empty array on its " +
            "own empty paths, so its callers handle the shape."),
        entry(45550, PROFILE, "Returns an empty array from the profile badges hook, so the " +
            "badge row has no badges to render. The hook already returns an empty array on its " +
            "own empty paths, so its callers handle the shape."),
        entry(45901, STOREFRONT, "Stops the guild-affinity fetch that ran on every connection " +
            "open. It was found by logging what the blocklist answered with a 204, and it hits an " +
            "endpoint that is already blocked."),
        entry(47109, SHOP, "Stubs the game-profile shop carousel, so the carousel a game " +
            "profile page renders draws nothing. The carousel sits outside the shop screen, " +
            "which is why stubbing the screen did not remove it."),
        entry(47110, SHOP, "Stubs the game-profile shop carousel, so the carousel a game " +
            "profile page renders draws nothing. The carousel sits outside the shop screen, " +
            "which is why stubbing the screen did not remove it."),
        entry(47123, ANALYTICS, "Stops impression tracking: this path builds its own trackMaker " +
            "and posts to /science without going through the central emitter the other stubs " +
            "cover."),
        entry(47225, STOREFRONT, "Stops the storefront price fetch for an application."),
        entry(47226, STOREFRONT, "Stops the storefront price fetch for SKU ids."),
        entry(47308, PROFILE, "Returns null from the animated decoration layer that plays over " +
            "the profile card, so the looping video or composite is never drawn. The profile " +
            "picture decoration is a different module and is untouched."),
        entry(47309, PROFILE, "Returns null from the animated decoration layer that plays over " +
            "the profile card, so the looping video or composite is never drawn. The profile " +
            "picture decoration is a different module and is untouched."),
        entry(47349, COLLECTIBLES, "Returns null from the collectible nameplate component, " +
            "so no nameplate is drawn. The reference lists it by module and name only, with " +
            "no further detail."),
        entry(47350, COLLECTIBLES, "Returns null from the collectible nameplate component, " +
            "so no nameplate is drawn. The reference lists it by module and name only, with " +
            "no further detail."),
        entry(47611, STOREFRONT, "Stops the fetch of collections with their products."),
        entry(49760, NITRO_UPSELLS, "No-ops the modal opener every premium upsell modal is " +
            "launched through, across its sixteen call sites."),
        entry(49956, STALE, "349.5 no longer has the Nitro upsell button at this id: it is the " +
            "SHOW_CONFIRM_MODAL handler that " +
            "modules/vibegrations/lib/vibegrationsPreviewNativeSurfaces.tsx registers with its " +
            "RPC interceptor. The stub answers the command with an object carrying the " +
            "{confirmed: false} result the interceptor reads, so an agent that asks for a " +
            "confirmation is told it was not confirmed instead of the call throwing. The " +
            "reference build stubs this id to undefined, which the interceptor's .result read " +
            "turns into a TypeError."),
        entry(51631, GUILD_TAGS, "Returns null from the guild tag badge component, so no " +
            "badge chip is drawn next to a name."),
        entry(51632, GUILD_TAGS, "Returns null from the guild tag badge component, so no " +
            "badge chip is drawn next to a name."),
        entry(51633, GUILD_TAGS, "Returns null from the base guild tag chiplet, the component " +
            "the guild tag chips build on, so no chip is drawn through it."),
        entry(51634, GUILD_TAGS, "Returns null from the base guild tag chiplet, the component " +
            "the guild tag chips build on, so no chip is drawn through it."),
        entry(51635, GUILD_TAGS, "Returns null from the guild tag component, so no guild tag " +
            "chip is drawn next to a name."),
        entry(52476, NITRO_UPSELLS, "Removes the inline 'Get Nitro' button, the widest-reaching " +
            "single upsell component: eleven consumers, from the Go Live sheet to the sticker " +
            "detail view."),
        entry(52477, NITRO_UPSELLS, "Removes the inline 'Get Nitro' button, the widest-reaching " +
            "single upsell component: eleven consumers, from the Go Live sheet to the sticker " +
            "detail view."),
        entry(53460, QUESTS, "Stops the refresh of current quests that the UI drives on a " +
            "one-second timer while a quest is active."),
        entry(53461, QUESTS, "Stops the quest heartbeat being sent to the server."),
        entry(53469, QUESTS, "Stops the refresh of claimed quests."),
        entry(53470, QUESTS, "Stops the fetch of the quest waiting to be delivered."),
        entry(53471, QUESTS, "Stops the fetch of the earned quest waiting to be delivered."),
        entry(55149, STOREFRONT, "Stops the storefront configuration fetch."),
        entry(55153, STOREFRONT, "Stops the storefront SKU lookup for an application."),
        entry(55351, SPOTIFY, "Returns a falsy value from the one real Spotify gate. Every call " +
            "site tests it in an if, so this removes the remaining Spotify branding: the " +
            "presence, 'Play on Spotify', the Spotify embed in activity cards and the outbound " +
            "Spotify activity."),
        entry(56206, QUESTS, "Returns a falsy value for the flag that gates quest items in the " +
            "activity panel."),
        entry(56456, ORBS, "Removes the modal that awards orbs for a completed quest."),
        entry(56457, ORBS, "Removes the modal that awards orbs for a completed quest."),
        entry(57616, SPOTIFY, "Returns null from the track renderer, so a friend's Spotify " +
            "presence renders nothing. Stubbing the listening check did not cover this, which is " +
            "why other people's songs stayed visible."),
        entry(57635, SPOTIFY, "Stops the subscription to Spotify player-state notifications, " +
            "which polled api.spotify.com on launch."),
        entry(57640, SPOTIFY, "Returns false from the check that asks the on-device Spotify app " +
            "whether Discord may talk to it over a local protocol. That path never touches the " +
            "network, so blocking Spotify hosts alone could not reach it; reporting it " +
            "unregistered leaves nothing to broadcast."),
        entry(58239, STALE, "349.5 no longer has the gift purchase button at this id: it is " +
            "the uncompiled variant of the useTypingUserIdsForDisplay hook in " +
            "modules/chat/native/TypingIndicator.tsx, and the React Compiler experiment defaults " +
            "off, so this is the variant the app runs. The stub returns an empty array, the " +
            "hook's own shape with no typing users, so the chat input's " +
            "hasTypingIndicatorContent read of its length finds 0 instead of throwing. The " +
            "reference build stubs this id to undefined, which that read turns into a TypeError."),
        entry(59199, GIFTS, "Removes the chat-input button that is a gift button or a thread " +
            "button depending on the conversation."),
        entry(59200, GIFTS, "Removes the chat-input button that is a gift button or a thread " +
            "button depending on the conversation."),
        entry(59211, GIFTS, "Removes the gift button in the chat input bar, which sat next to the " +
            "message box."),
        entry(59212, GIFTS, "Removes the gift button in the chat input bar, which sat next to the " +
            "message box."),
        entry(61440, STALE, "349.5 no longer has the profile edit screen at this id: it is the " +
            "compiled variant of the guild notification action sheet in " +
            "modules/notifications/settings/native/" +
            "NotificationSettingsMessageUnreadGuildActionSheet.tsx. The app selects that " +
            "variant only when the React Compiler experiment is enabled, and this build leaves " +
            "the experiment off by default, so the stub changes nothing for a stock client."),
        entry(62045, ORBS, "The same orb gate under its plain name. Hermes deduplicated the two " +
            "exports into one body, so patching either one turns orbs off; the reference verifies " +
            "the alias set on every run."),
        entry(62046, ORBS, "Returns {enabled: false} from the gate every orb surface reads, which " +
            "removes orbs at the source rather than hiding each screen that shows them. Hermes " +
            "shares this body with isVirtualCurrencyEnabled, so that name is disabled with it."),
        entry(62162, SHOP, "Stubs the Shop This Look action sheet, so it opens empty if its " +
            "opener still runs. The opener is left as it is because its body is shared with an " +
            "unrelated display-name feature."),
        entry(62163, SHOP, "Stubs the Shop This Look action sheet, so it opens empty if its " +
            "opener still runs. The opener is left as it is because its body is shared with an " +
            "unrelated display-name feature."),
        entry(62179, SHOP, "Stubs the Shop This Look marketing coachmark, so the prompt that " +
            "points at the feature is not built. It lives outside the shop screen, so stubbing " +
            "the screen did not remove it."),
        entry(62180, SHOP, "Stubs the Shop This Look marketing coachmark, so the prompt that " +
            "points at the feature is not built. It lives outside the shop screen, so stubbing " +
            "the screen did not remove it."),
        entry(62435, STALE, "349.5 no longer has the quest orb shop carousel at this id: it is " +
            "the compiled variant of the badge row in " +
            "modules/badges/native/BadgeDirectoryNuxCoachmark.tsx, which renders the game-time, " +
            "streaming and game-diversity tier badges. The app selects that variant only when " +
            "the React Compiler experiment is enabled, and this build leaves the experiment off " +
            "by default, so the stub changes nothing for a stock client."),
        entry(62714, WISHLIST, "Forces the wishlist tab index to -1, so the profile's segmented " +
            "control has no Wishlist tab to select."),
        entry(62758, WISHLIST, "Returns null from the wishlist grid a profile renders, so the " +
            "grid of wishlisted items shown as a profile section is not drawn. This is a " +
            "different surface from the profile's Wishlist tab."),
        entry(62766, WISHLIST, "Returns null from the wishlist suggestions grid a profile " +
            "renders, so the suggested-items grid is not drawn. The component already returns " +
            "null when its own mobile gate is off, so callers handle the shape."),
        entry(62767, WISHLIST, "Returns null from the wishlist suggestions grid a profile " +
            "renders, so the suggested-items grid is not drawn. The component already returns " +
            "null when its own mobile gate is off, so callers handle the shape."),
        entry(62778, WISHLIST, "Removes the add-to-wishlist button in the grid layout."),
        entry(62779, WISHLIST, "Removes the add-to-wishlist button in the grid layout."),
        entry(62783, WISHLIST, "Removes the add-to-wishlist button on item cards."),
        entry(62880, ORBS, "Removes the orb price tag shown on items that can be bought with " +
            "orbs."),
        entry(62881, ORBS, "Removes the orb price tag shown on items that can be bought with " +
            "orbs."),
        entry(62907, GIFTS, "Removes the standalone gift button component, one of the separate " +
            "components stubbing the inline Nitro button did not cover."),
        entry(62908, GIFTS, "Removes the standalone gift button component, one of the separate " +
            "components stubbing the inline Nitro button did not cover."),
        entry(63721, GIFTS, "Removes the gift purchase button, so a gift cannot be bought from " +
            "the UI."),
        entry(63722, GIFTS, "Removes the gift purchase button, so a gift cannot be bought from " +
            "the UI."),
        entry(64027, SESSION_TELEMETRY, "Stops the gateway READY payload being logged: the " +
            "payload carries the whole guild list, and the reference records it being stringified " +
            "around fourteen times on one connect in a hundred."),
        entry(64028, SESSION_TELEMETRY, "Stops the connection-path lookup used to label the " +
            "gateway analytics event."),
        entry(64029, SESSION_TELEMETRY, "Stops the READY payload being measured for its analytics " +
            "byte size."),
        entry(64030, SESSION_TELEMETRY, "Stops the gateway-connected analytics event."),
        entry(66426, ANALYTICS, "Stops the analytics action handler, which built an event with " +
            "its key, properties, fingerprint and timestamp for every tracked action."),
        entry(67311, PROFILE, "Stubs the Edit User Profile entry in settings."),
        entry(67312, PROFILE, "Stubs the Edit User Profile entry in settings."),
        entry(68573, BILLING_ROWS, "Returns false from the Manage Subscriptions row's visibility " +
            "predicate."),
        entry(68574, BILLING_ROWS, "Returns false from the Manage Subscriptions row's visibility " +
            "predicate."),
        entry(68590, BILLING_ROWS, "Returns false from the Gift Inventory row's own visibility " +
            "predicate."),
        entry(68593, QUESTS, "Returns false from the Quests settings row's own visibility " +
            "predicate. This is what actually removes the row: the settings harness hides any " +
            "entry whose predicate returns exactly false."),
        entry(68600, QUESTS, "Stubs the Quest Home screen the Quests settings row navigates to."),
        entry(68601, QUESTS, "Stubs the Quest Home screen the Quests settings row navigates to."),
        entry(68628, QUESTS, "Returns null from the Quest Home screen, so a route that still " +
            "navigates there renders nothing. Every mobile entry point is gated separately; " +
            "this covers the screen itself, which a route name can still reach."),
        entry(68629, QUESTS, "Returns null from the Quest Home screen, so a route that still " +
            "navigates there renders nothing. Every mobile entry point is gated separately; " +
            "this covers the screen itself, which a route name can still reach."),
        entry(68858, QUESTS, "Returns false from the mobile quest-dock hook, so the dock is not " +
            "shown. The reference's alternative lever, the broad quest-eligibility check, shares " +
            "its body with six unrelated capability checks and cannot be patched."),
        entry(69421, BILLING_ROWS, "Returns false from the Server Subscriptions row's visibility " +
            "predicate."),
        entry(69606, BILLING_ROWS, "Returns false from the Restore Subscription row's visibility " +
            "predicate."),
        entry(69607, BILLING_ROWS, "Returns false from the Restore Subscription row's visibility " +
            "predicate."),
        entry(70912, ORBS, "Removes the You-tab orb balance widget's menu, one of the screens the " +
            "orb gate sits in front of."),
        entry(70917, ORBS, "Removes the wrapper component that builds the orb balance widget."),
        entry(70918, ORBS, "Removes the wrapper component that builds the orb balance widget."),
        entry(70961, SURVEYS, "Stops the survey override from taking effect, so a server-side " +
            "override cannot swap the app-rating survey the client is showing."),
        entry(70962, SURVEYS, "Stops the survey-dismiss action, which both updated the store and " +
            "emitted its APP_NOTICE analytics event."),
        entry(70963, SURVEYS, "Stops the survey-seen report, which told Discord the survey had " +
            "been shown and emitted analytics for it."),
        entry(71374, SHOP, "Stubs the collectibles shop screen component the Settings route " +
            "renders, so the route draws nothing. The shop also has a deep-link entry the " +
            "route edit cannot close, which is why the screen component is stubbed."),
        entry(71375, SHOP, "Stubs the collectibles shop screen component the Settings route " +
            "renders, so the route draws nothing. The shop also has a deep-link entry the " +
            "route edit cannot close, which is why the screen component is stubbed."),
        entry(71379, SHOP, "Stubs the shop screen's internal component, leaving the screen with " +
            "nothing to render."),
        entry(71380, SHOP, "Stubs the shop screen's internal component, leaving the screen with " +
            "nothing to render."),
        entry(71381, SHOP, "Stubs the collectibles shop screen itself, so there is no shop tab to " +
            "browse."),
        entry(71382, SHOP, "Stubs the collectibles shop screen itself, so there is no shop tab to " +
            "browse."),
        entry(71516, QUESTS, "Returns false from the usePredicate closure the Quests toggles " +
            "in Data & Privacy carry, so those settings rows are hidden. The two toggles' " +
            "predicates are byte-identical and Hermes stores them once, so either id changes " +
            "both."),
        entry(71528, QUESTS, "Returns false from the usePredicate closure the Quests toggles " +
            "in Data & Privacy carry, so those settings rows are hidden. The two toggles' " +
            "predicates are byte-identical and Hermes stores them once, so either id changes " +
            "both."),
        entry(72534, GUILD_TAGS, "Returns null from the voice guild tag component, so no " +
            "guild tag chip is drawn in a voice channel."),
        entry(72535, GUILD_TAGS, "Returns null from the voice guild tag component, so no " +
            "guild tag chip is drawn in a voice channel."),
        entry(75525, ANALYTICS, "Stops the AutoAnalytics navigator hook that emitted an event " +
            "each time a screen was built."),
        entry(75526, ANALYTICS, "Stops the other half of the AutoAnalytics navigator hook, which " +
            "emitted an event each time the current screen changed."),
        entry(75650, QUESTS, "Rebuilds the You screen's floating navigation so the Quests button " +
            "is never built. The button is one element of a list rather than a function that can " +
            "return false, so the body is edited instead of stubbed."),
        entry(75687, SHOP, "Removes the button that opens the collectibles shop."),
        entry(75688, SHOP, "Removes the button that opens the collectibles shop."),
        entry(75689, SHOP, "Removes the coachmark that points at the mobile shop button."),
        entry(75690, SHOP, "Removes the coachmark that points at the mobile shop button."),
        entry(77257, ANALYTICS, "Stops the per-request tracker, which ran URL matching and " +
            "appended to the telemetry ring for every HTTP request."),
        entry(77458, ANALYTICS, "Stops _trackStartSpeaking from computing game metadata and " +
            "packet stats when someone starts speaking in a voice call."),
        entry(77459, ANALYTICS, "Stops _trackStartListening from computing telemetry payloads " +
            "when someone starts listening in a voice call."),
        entry(79457, SESSION_TELEMETRY, "Stops the app-state update handler the reference lists " +
            "with the message-cache recorders, so it no longer runs on every app state change."),
        entry(79491, SESSION_TELEMETRY, "Stops the patched global WebSocket being installed, " +
            "whose per-message handler parsed every gateway frame a second time and appended it " +
            "to the disabled telemetry ring."),
        entry(79533, LOGGING, "Stops the timer monitor that formatted slow-timer records into the " +
            "log, which only the stubbed analytics emitter read."),
        entry(79599, LOGGING, "Silences one of the logger's debug levels, so those lines no " +
            "longer enter the in-memory JavaScript debug log. Only warn and error are kept, so " +
            "genuine problems still surface."),
        entry(79600, LOGGING, "Silences log(), which is where most of the in-memory debug log was " +
            "coming from."),
        entry(79601, LOGGING, "Silences the logger's verbose level (the 'dangerously' variant), " +
            "keeping it out of the debug log."),
        entry(79602, LOGGING, "Silences verbose, one of the two levels that dominated the " +
            "in-memory debug log."),
        entry(79603, LOGGING, "Silences info, so informational lines no longer enter the debug " +
            "log."),
        entry(79606, LOGGING, "Silences trace, so trace lines no longer enter the debug log."),
        entry(79608, LOGGING, "Silences the file-only logger, so nothing is written to the " +
            "logger's file sink."),
        entry(83581, ANALYTICS, "Silences the central science-event emitter every feature calls, " +
            "so no event is built or sent and no per-event CPU/memory sample is taken. Its " +
            "callers await it, so the stub still returns a resolved promise."),
        entry(83586, ANALYTICS, "Stops the single POST behind the /science and /beaker queues, so " +
            "the events the stubs above would have queued are never uploaded either."),
        entry(83707, METRICS, "Stops the once-a-second process sampler, which made three native " +
            "bridge calls per second for CPU and memory whose only consumers null-guard."),
        entry(89454, METRICS, "Stops the voice-quality payload builder that assembled and sent " +
            "its statistics every five minutes."),
        entry(112318, METRICS, "Stops the 60-second session flusher that shipped session " +
            "aggregates."),
        entry(112429, METRICS, "Stops the metrics aggregator adding samples to its buckets."),
        entry(112432, METRICS, "Stops the metrics aggregator flushing its buckets on its " +
            "interval."),
        entry(112433, METRICS, "Stops the metrics aggregator capturing a metrics snapshot."),
        entry(112454, METRICS, "Stops the browser-side metrics aggregator adding samples to its " +
            "buckets."),
        entry(112455, METRICS, "Stops the browser-side metrics aggregator flushing them."),
        entry(113052, SPOTIFY, "Rebuilds the profile activity list without friends' Spotify " +
            "listened-session entries. 349.5 has a second copy of the filter in the same module, " +
            "reachable through a different parent, and the reference edits both so Spotify " +
            "entries do not survive on some surfaces."),
        entry(113177, WISHLIST, "Rebuilds other users' profile tab list so it builds Main and " +
            "Activity only, with no Board and no Wishlist tab. The reference edits the " +
            "isReactCompilerEnabled() == false variant of the profile content component."),
        entry(115029, METRICS, "Stops the voice-quality sampler that ran once a second during a " +
            "call."),
        entry(115037, METRICS, "Stops the system-responsiveness sampler, the second of the " +
            "voice-call samplers."),
        entry(115050, METRICS, "Stops the video-effect and system-resource sampler that ran on " +
            "every call-statistics callback."),
        entry(127527, WISHLIST, "Rebuilds the profile section tab list as Main/Board/Activity and " +
            "never Wishlist. The tab is one element of a list, not a function that can return " +
            "false, so the body is edited rather than stubbed."),
        entry(132693, QUESTS, "Stops QuestFetchManager installing the recurring interval that " +
            "refetched quests forever, which against already-blocked endpoints was pure timer and " +
            "network churn."),
        entry(142676, SPOTIFY, "Rebuilds the profile activity list without friends' Spotify " +
            "listened-session entries."),
        entry(148336, ANALYTICS, "Stops the 60-second voice state interval callback that " +
            "repeatedly dispatches speaking and listening telemetry during calls."),
    )

    /** The feature groups, in the order they first appear in [ENTRIES]. */
    val GROUPS: List<String> = ENTRIES.map { it.group }.distinct()

    /**
     * The item key of one function, for example `discord_hermes:fn68593`.
     *
     * The function id rather than the name: it is the only thing about a patched function that
     * identifies it (see this file's header), and it is what a saved selection records.
     */
    fun itemKeyOf(functionId: Int): String =
        PatchItem.keyOf(DiscordPatches.HERMES.id, "fn$functionId")

    /** Every patched function as a selectable item, in [ENTRIES] order. */
    fun items(setId: String = DiscordPatches.HERMES.id): List<PatchItem> =
        ENTRIES.zip(DiscordHermesBundlePatch.PATCHES) { entry, patch ->
            PatchItem(
                setId = setId,
                identity = "fn${entry.functionId}",
                // Several of these share a name (three `track`, three `sampleStats`, and two each
                // of `add`, `addBreadcrumb`, `_flush`, `flush` and `usePredicate`), so the id is
                // added wherever it is needed to distinguish two rows, and a function with no name
                // is labeled by its id rather than shown as a blank.
                label = when {
                    patch.name.isBlank() -> "Unnamed function ${entry.functionId}"
                    patch.name in AMBIGUOUS_NAMES -> "${patch.name} (function ${entry.functionId})"
                    else -> patch.name
                },
                group = entry.group,
                description = entry.description
            )
        }

    /**
     * The subset of [patches] that [selection] switches on, in the table's own order.
     *
     * This is the whole mechanism for applying a subset of the JavaScript patches:
     * [dev.sleepy.app.engine.HermesBundlePatcher.apply] takes the list to apply, so the caller
     * honors a selection by passing a shorter list, and a function that is not selected is not
     * touched. A function with no entry here is not selectable, which is why a test asserts
     * there are none.
     */
    fun selectPatches(
        selection: PatchSelection,
        patches: List<FunctionPatch> = DiscordHermesBundlePatch.PATCHES
    ): List<FunctionPatch> = patches.filter { selection.contains(itemKeyOf(it.functionId)) }

    /** Names shared by more than one patched function, which a name alone does not distinguish. */
    private val AMBIGUOUS_NAMES: Set<String> =
        DiscordHermesBundlePatch.PATCHES
            .map { it.name }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys

    private fun entry(functionId: Int, group: String, description: String) =
        Entry(functionId = functionId, group = group, description = description)
}
