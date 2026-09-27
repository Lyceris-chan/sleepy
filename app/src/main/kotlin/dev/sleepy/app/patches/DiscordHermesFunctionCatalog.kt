package dev.sleepy.app.patches

import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.patches.DiscordHermesBundlePatch.FunctionPatch
import dev.sleepy.app.model.PatchSelection

/**
 * What every function in [DiscordHermesBundlePatch.PATCHES] does to the app, in the user's terms.
 *
 * The Hermes set is one switch over ${DiscordHermesBundlePatch.PATCHES.size} functions: the wrong size for a choice
 * anyone actually has — "hide the gift button but keep quests" is not expressible when both live
 * behind the same switch. This table is the missing half of a per-item list: each function's
 * feature group, and a line saying what patching it does.
 *
 * Sourced from the desktop reference's own tables in `quirky-noether/discord/patches/core.py`
 * (TARGETS, PROMISE_TARGETS, FALSE_TARGETS, ZERO_TARGETS, NULL_TARGETS, OBJECT_FALSE_TARGETS and
 * the EDITS list), whose comments say what each id is and what shape its stub has to return.
 * Entries are in [DiscordHermesBundlePatch.PATCHES] order and cover exactly its function ids; a
 * test asserts both, because a function that this table describes but the patch table does not
 * carry, or one the patch table carries and this table does not describe, is precisely the gap it
 * exists to close.
 *
 * Function ids identify an entry, and names do not: this bundle has three functions called
 * `track`, two called `sampleStats` and ten with no name at all. The names also move on every
 * release, so they are labels on an item and never its key — see [PatchItem.identity].
 *
 * Five entries the reference lists anonymously (13894 and the three NetStats closures at
 * 39936-39938, plus the one at 73887) are described from the reference's own note about them; the
 * ones the reference describes only as a group say so in their description rather than inventing
 * a distinction the reference does not make.
 */
object DiscordHermesFunctionCatalog {

    /** One patched function's place in the user-facing list. */
    data class Entry(
        val functionId: Int,
        val group: String,
        val description: String
    )

    // Feature groups. Declared here rather than inline so a group is named once and a typo
    // in one of the 142 entries cannot quietly create a fifteenth group of one item.
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
    private const val STOREFRONT = "Storefront, promotion and guild-affinity fetches"
    private const val WISHLIST = "Wishlist buttons and profile tab"
    private const val BILLING_ROWS = "Billing and premium settings rows"
    private const val PROFILE = "Profile editing and animated profile card effects"
    private const val SPOTIFY = "Spotify presence and integration"

    /** Every patched function, in the order the patch table applies them. */
    val ENTRIES: List<Entry> = listOf(
        entry(13894, STARTUP_TRACING, "Stops one of the two anonymous module-scope initialisers the reference lists here: a " +
            "function called at import by roughly ten thousand modules to fill a Set that only the " +
            "stubbed TTI tracker reads, or MemoryExperiment's 60-second timer that never clears. The " +
            "reference does not say which of the two this id is."),
        entry(14518, BILLING_ROWS, "Gives the Settings Nitro / Manage Nitro row the always-false predicate that hides it. Its " +
            "backend is already answered with a 204, so the row led to a screen that could do nothing."),
        entry(14522, BILLING_ROWS, "Gives the Settings Manage Plan row the always-false predicate that hides it."),
        entry(14529, BILLING_ROWS, "Gives the Settings Server Boost row the always-false predicate that hides it."),
        entry(15420, SHOP, "Gives the Settings CollectiblesShop route the always-false predicate that hides its row. " +
            "This def had none of its own, so the route object itself is edited, the same lever that " +
            "removed the other billing rows."),
        entry(17822, LOGGING, "Stops the log aggregator from feeding the in-app debug panel's log buffer."),
        entry(17853, STARTUP_TRACING, "Stops the time-to-interactive tracker recording a milestone's start."),
        entry(17855, STARTUP_TRACING, "Stops the time-to-interactive tracker recording a milestone's end."),
        entry(17857, STARTUP_TRACING, "Stops the time-to-interactive tracker storing a value for a milestone."),
        entry(17865, STARTUP_TRACING, "Stops the time-to-interactive tracker recording a measurement."),
        entry(17895, STARTUP_TRACING, "Stops the tracing recorder appending its formatted line to the in-memory startup log."),
        entry(17896, STARTUP_TRACING, "Stops startup milestone marks being recorded, one of the recorders called at every startup " +
            "step."),
        entry(17897, STARTUP_TRACING, "Stops the milestone mark-and-log recorder used by the traced operations."),
        entry(17898, STARTUP_TRACING, "Stops import-detail records being added to the startup trace."),
        entry(17899, STARTUP_TRACING, "Stops delta marks from being added to the startup trace."),
        entry(17900, STARTUP_TRACING, "Stops timestamped marks from being recorded in the startup trace."),
        entry(17901, STARTUP_TRACING, "Stops detail records from being added to the startup trace."),
        entry(17904, STARTUP_TRACING, "Stops the server-issued trace id from being stored on the startup trace."),
        entry(19432, FINGERPRINT, "Stops the fingerprint handler, which computed the old and new device fingerprint for every " +
            "tracked event and reported the transition."),
        entry(20310, SENTRY, "Stops Sentry's own breadcrumb collector, which recorded one for every Flux dispatcher " +
            "action, so the collection stops at the source rather than at the sender."),
        entry(22939, SENTRY, "Stops Sentry attaching the signed-in user's identity to error reports."),
        entry(22940, SENTRY, "Stops Sentry clearing the user identity it would otherwise have attached."),
        entry(22941, SENTRY, "Stops tags being attached to the Sentry scope, so error reports carry no user-defined tags."),
        entry(22942, SENTRY, "Stops extra context being attached to the Sentry scope."),
        entry(22943, SENTRY, "Stops JavaScript exceptions being reported to Sentry, so stack traces stay on the device."),
        entry(22944, SENTRY, "Stops JavaScript crash reports being sent to Sentry."),
        entry(22945, SENTRY, "Stops manually logged messages being sent to Sentry."),
        entry(22946, SENTRY, "Stops feature-flag names being recorded on the Sentry scope."),
        entry(22947, SENTRY, "Stops Sentry's JavaScript API adding breadcrumbs, which is what recorded UI clicks and " +
            "navigations."),
        entry(22949, SENTRY, "Stops the deliberate crash call from reaching Sentry."),
        entry(22950, SENTRY, "Stops the memory-warning report from reaching Sentry."),
        entry(22951, SENTRY, "Stops the crash-handled marker being recorded on the Sentry scope."),
        entry(22958, SENTRY, "Stops the Sentry JavaScript SDK from initialising at all, so no client is bound and none of " +
            "its automatic integrations (global error handlers, breadcrumbs, promise-rejection tracking) " +
            "are installed."),
        entry(23017, ANALYTICS, "Silences the [Analytics] debug reporter, so the client stops writing its analytics events " +
            "into the debug log."),
        entry(23080, ANALYTICS, "Silences the analytics store's own tracking closure, which emits events independently of the " +
            "central emitter."),
        entry(23088, ANALYTICS, "Silences the [Analytics] network-action reporter, so per-request analytics are never " +
            "emitted."),
        entry(25056, ANALYTICS, "Returns false from the analytics experiment flag, so the 500 ms telemetry-ring export loop " +
            "can never start."),
        entry(33307, ANALYTICS, "Silences the emitter behind the forum and clickstream trackers, so those impressions are " +
            "never sent."),
        entry(33482, SURVEYS, "Stops the survey override from taking effect, so a server-side override cannot swap the " +
            "app-rating survey the client is showing."),
        entry(33483, SURVEYS, "Stops the survey-dismiss action, which both updated the store and emitted its APP_NOTICE " +
            "analytics event."),
        entry(33484, SURVEYS, "Stops the app-rating survey poll, which fetched the survey and emitted analytics for it."),
        entry(33485, SURVEYS, "Stops the survey-seen report, which told Discord the survey had been shown and emitted " +
            "analytics for it."),
        entry(34274, METRICS, "Stops the monitoring agent's counter, which about twenty-five modules call as they do their " +
            "work."),
        entry(34275, METRICS, "Stops the monitoring agent's distribution sample."),
        entry(34276, METRICS, "Stops the monitoring agent flushing what it accumulated to the two-minute /metrics/v2 " +
            "upload."),
        entry(38577, SHOP, "Returns false from the storefront capability check, which hides the guild sidebar's " +
            "game-shop row and blocks the redirect into it."),
        entry(39932, SESSION_TELEMETRY, "Stops the network-statistics sampler restarting its timers when the app changes state."),
        entry(39933, SESSION_TELEMETRY, "Stops the network-statistics recorder writing its accumulated events to device storage."),
        entry(39934, SESSION_TELEMETRY, "Stops the network-statistics sampler's own track(), which queued events locally."),
        entry(39936, SESSION_TELEMETRY, "Stops one of the network-statistics sampler's three anonymous local recorders: the " +
            "five-second storage write, the sixty-second radio sample, or the per-message counter. The " +
            "reference lists them without names."),
        entry(39937, SESSION_TELEMETRY, "Stops one of the network-statistics sampler's three anonymous local recorders: the " +
            "five-second storage write, the sixty-second radio sample, or the per-message counter. The " +
            "reference lists them without names."),
        entry(39938, SESSION_TELEMETRY, "Stops one of the network-statistics sampler's three anonymous local recorders: the " +
            "five-second storage write, the sixty-second radio sample, or the per-message counter. The " +
            "reference lists them without names."),
        entry(39965, SESSION_TELEMETRY, "Stops the session heartbeat scheduler being initialised, so its timer, its periodic API ping " +
            "and its breadcrumb are never created."),
        entry(40123, SESSION_TELEMETRY, "Stops the message-cache statistic that counted channel fetches starting."),
        entry(40124, SESSION_TELEMETRY, "Stops the message-cache statistic that counted a channel being served locally."),
        entry(40125, SESSION_TELEMETRY, "Stops the message-cache statistic that counted a channel being fetched over the network."),
        entry(42692, NITRO_UPSELLS, "No-ops the action-sheet opener every upsell bottom sheet is launched through, across its " +
            "nine call sites."),
        entry(44014, STOREFRONT, "Stops the storefront fetching products by SKU id. The implementation is stubbed rather than " +
            "the exported trampoline, whose body Hermes shares with dozens of unrelated API calls."),
        entry(44387, STOREFRONT, "Stops the guild-affinity fetch that ran on every connection open. It was found by logging " +
            "what the blocklist answered with a 204, and it hits an endpoint that is already blocked."),
        entry(45508, ANALYTICS, "Stops impression tracking: this path builds its own trackMaker and posts to /science without " +
            "going through the central emitter the other stubs cover."),
        entry(45591, STOREFRONT, "Stops the storefront price fetch for an application."),
        entry(45592, STOREFRONT, "Stops the storefront price fetch for SKU ids."),
        entry(45655, PROFILE, "Returns null from the animated decoration layer that plays over the profile card, so the " +
            "looping video or composite is never drawn. The profile picture decoration is a different " +
            "module and is untouched."),
        entry(45873, STOREFRONT, "Stops the fetch of collections with their products."),
        entry(47719, NITRO_UPSELLS, "No-ops the modal opener every premium upsell modal is launched through, across its sixteen " +
            "call sites."),
        entry(49956, NITRO_UPSELLS, "Removes the inline 'Get Nitro' button, the widest-reaching single upsell component: eleven " +
            "consumers, from the Go Live sheet to the sticker detail view."),
        entry(51867, STOREFRONT, "Stops the storefront configuration fetch."),
        entry(51871, STOREFRONT, "Stops the storefront SKU lookup for an application."),
        entry(52007, SPOTIFY, "Returns a falsy value from the one real Spotify gate. Every call site tests it in an if, so " +
            "this removes the remaining Spotify branding: the presence, 'Play on Spotify', the Spotify " +
            "embed in activity cards and the outbound Spotify activity."),
        entry(52667, QUESTS, "Returns a falsy value for the flag that gates quest items in the activity panel."),
        entry(52680, QUESTS, "Stops the refresh of current quests that the UI drives on a one-second timer while a quest " +
            "is active."),
        entry(52681, QUESTS, "Stops the quest heartbeat being sent to the server."),
        entry(52689, QUESTS, "Stops the refresh of claimed quests."),
        entry(52690, QUESTS, "Stops the fetch of the quest waiting to be delivered."),
        entry(52691, QUESTS, "Stops the fetch of the earned quest waiting to be delivered."),
        entry(52968, ORBS, "Removes the orb balance display."),
        entry(52976, ORBS, "Removes the modal that awards orbs for a completed quest."),
        entry(54101, SPOTIFY, "Returns null from the track renderer, so a friend's Spotify presence renders nothing. " +
            "Stubbing the listening check did not cover this, which is why other people's songs stayed " +
            "visible."),
        entry(54121, SPOTIFY, "Stops the subscription to Spotify player-state notifications, which polled api.spotify.com " +
            "on launch."),
        entry(54126, SPOTIFY, "Returns false from the check that asks the on-device Spotify app whether Discord may talk to " +
            "it over a local protocol. That path never touches the network, so blocking Spotify hosts " +
            "alone could not reach it; reporting it unregistered leaves nothing to broadcast."),
        entry(55192, GIFTS, "Removes the chat-input button that is a gift button or a thread button depending on the " +
            "conversation."),
        entry(55199, GIFTS, "Removes the gift button in the chat input bar, which sat next to the message box."),
        entry(57119, ORBS, "The same orb gate under its plain name. Hermes deduplicated the two exports into one body, " +
            "so patching either one turns orbs off; the reference verifies the alias set on every run."),
        entry(57120, ORBS, "Returns {enabled: false} from the gate every orb surface reads, which removes orbs at the " +
            "source rather than hiding each screen that shows them. Hermes shares this body with " +
            "isVirtualCurrencyEnabled, so that name is disabled with it."),
        entry(57553, WISHLIST, "Forces the wishlist tab index to -1, so the profile's segmented control has no Wishlist tab " +
            "to select."),
        entry(57595, WISHLIST, "Removes the add-to-wishlist button in the grid layout."),
        entry(57596, WISHLIST, "Removes the add-to-wishlist button on item cards."),
        entry(57674, ORBS, "Removes the orb price tag shown on items that can be bought with orbs."),
        entry(57688, GIFTS, "Removes the standalone gift button component, one of the separate components stubbing the " +
            "inline Nitro button did not cover."),
        entry(58239, GIFTS, "Removes the gift purchase button, so a gift cannot be bought from the UI."),
        entry(58507, SESSION_TELEMETRY, "Stops the gateway READY payload being logged: the payload carries the whole guild list, and " +
            "the reference records it being stringified around fourteen times on one connect in a " +
            "hundred."),
        entry(58508, SESSION_TELEMETRY, "Stops the connection-path lookup used to label the gateway analytics event."),
        entry(58509, SESSION_TELEMETRY, "Stops the READY payload being measured for its analytics byte size."),
        entry(58510, SESSION_TELEMETRY, "Stops the gateway-connected analytics event."),
        entry(60648, ANALYTICS, "Stops the analytics action handler, which built an event with its key, properties, " +
            "fingerprint and timestamp for every tracked action."),
        entry(61440, PROFILE, "Stubs the Edit User Profile entry in settings."),
        entry(62280, BILLING_ROWS, "Returns false from the Manage Subscriptions row's visibility predicate."),
        entry(62289, BILLING_ROWS, "Returns false from the Gift Inventory row's own visibility predicate."),
        entry(62294, QUESTS, "Returns false from the Quests settings row's own visibility predicate. This is what actually " +
            "removes the row: the settings harness hides any entry whose predicate returns exactly false."),
        entry(62298, QUESTS, "Stubs the Quest Home screen the Quests settings row navigates to."),
        entry(62435, ORBS, "Removes the in-quest carousel that sells orbs."),
        entry(62451, QUESTS, "Returns false from the mobile quest-dock hook, so the dock is not shown. The reference's " +
            "alternative lever, the broad quest-eligibility check, shares its body with six unrelated " +
            "capability checks and cannot be patched."),
        entry(62839, BILLING_ROWS, "Returns false from the Server Subscriptions row's visibility predicate."),
        entry(62954, BILLING_ROWS, "Returns false from the Restore Subscription row's visibility predicate."),
        entry(63907, ORBS, "Removes the You-tab orb balance widget's menu, one of the screens the orb gate sits in front " +
            "of."),
        entry(63908, ORBS, "Removes the wrapper component that builds the orb balance widget."),
        entry(64167, SHOP, "Stubs the collectibles shop screen itself, so there is no shop tab to browse."),
        entry(64168, SHOP, "Stubs the shop screen's internal component, leaving the screen with nothing to render."),
        entry(66872, ANALYTICS, "Stops the AutoAnalytics navigator hook that emitted an event each time a screen was built."),
        entry(66873, ANALYTICS, "Stops the other half of the AutoAnalytics navigator hook, which emitted an event each time " +
            "the current screen changed."),
        entry(66956, QUESTS, "Rebuilds the You screen's floating navigation so the Quests button is never built. The " +
            "button is one element of a list rather than a function that can return false, so the body is " +
            "edited instead of stubbed."),
        entry(66966, SHOP, "Removes the button that opens the collectibles shop."),
        entry(66967, SHOP, "Removes the coachmark that points at the mobile shop button."),
        entry(66981, PROFILE, "Stubs the You-tab 'Edit Profile' entry. The reference uses the entry point rather than the " +
            "premium-profile-customization gate, which covers only one section of five."),
        entry(67890, ANALYTICS, "Stops the per-request tracker, which ran URL matching and appended to the telemetry ring for " +
            "every HTTP request."),
        entry(68460, STOREFRONT, "Stops PromotionsManager fetching /promotions on every launch, which ran without the user " +
            "opening anything."),
        entry(69656, SESSION_TELEMETRY, "Stops the app-state update handler the reference lists with the message-cache recorders, so " +
            "it no longer runs on every app state change."),
        entry(69681, SESSION_TELEMETRY, "Stops the patched global WebSocket being installed, whose per-message handler parsed every " +
            "gateway frame a second time and appended it to the disabled telemetry ring."),
        entry(69723, LOGGING, "Stops the timer monitor that formatted slow-timer records into the log, which only the " +
            "stubbed analytics emitter read."),
        entry(69782, LOGGING, "Silences one of the logger's debug levels, so those lines no longer enter the in-memory " +
            "JavaScript debug log. Only warn and error are kept, so genuine problems still surface."),
        entry(69783, LOGGING, "Silences log(), which is where most of the in-memory debug log was coming from."),
        entry(69784, LOGGING, "Silences the logger's verbose level (the 'dangerously' variant), keeping it out of the debug " +
            "log."),
        entry(69785, LOGGING, "Silences verbose, one of the two levels that dominated the in-memory debug log."),
        entry(69786, LOGGING, "Silences info, so informational lines no longer enter the debug log."),
        entry(69789, LOGGING, "Silences trace, so trace lines no longer enter the debug log."),
        entry(69791, LOGGING, "Silences the file-only logger, so nothing is written to the logger's file sink."),
        entry(73760, ANALYTICS, "Silences the central science-event emitter every feature calls, so no event is built or sent " +
            "and no per-event CPU/memory sample is taken. Its callers await it, so the stub still returns " +
            "a resolved promise."),
        entry(73765, ANALYTICS, "Stops the single POST behind the /science and /beaker queues, so the events the stubs above " +
            "would have queued are never uploaded either."),
        entry(73887, METRICS, "Stops the once-a-second process sampler, which made three native bridge calls per second for " +
            "CPU and memory whose only consumers null-guard."),
        entry(79521, METRICS, "Stops the voice-quality payload builder that assembled and sent its statistics every five " +
            "minutes."),
        entry(96053, METRICS, "Stops the 60-second session flusher that shipped session aggregates."),
        entry(96164, METRICS, "Stops the metrics aggregator adding samples to its buckets."),
        entry(96167, METRICS, "Stops the metrics aggregator flushing its buckets on its interval."),
        entry(96168, METRICS, "Stops the metrics aggregator capturing a metrics snapshot."),
        entry(96189, METRICS, "Stops the browser-side metrics aggregator adding samples to its buckets."),
        entry(96190, METRICS, "Stops the browser-side metrics aggregator flushing them."),
        entry(97875, METRICS, "Stops the voice-quality sampler that ran once a second during a call."),
        entry(97883, METRICS, "Stops the system-responsiveness sampler, the second of the voice-call samplers."),
        entry(97896, METRICS, "Stops the video-effect and system-resource sampler that ran on every call-statistics " +
            "callback."),
        entry(105481, WISHLIST, "Rebuilds the profile section tab list as Main/Board/Activity and never Wishlist. The tab is " +
            "one element of a list, not a function that can return false, so the body is edited rather " +
            "than stubbed."),
        entry(108532, QUESTS, "Stops QuestFetchManager installing the recurring interval that refetched quests forever, " +
            "which against already-blocked endpoints was pure timer and network churn."),
        entry(117345, SPOTIFY, "Rebuilds the profile activity list without friends' Spotify listened-session entries."),
    )

    /** The feature groups, in the order they first appear above. */
    val GROUPS: List<String> = ENTRIES.map { it.group }.distinct()

    /**
     * The item key of one function, e.g. `discord_hermes:fn62294`.
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
                // Three of these are called `track`, three `sampleStats`, two `usePredicate`, and
                // ten have no name at all - so the id is added wherever it is needed to tell two
                // rows apart, and a nameless one says what it is rather than showing a blank.
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
     * [dev.sleepy.app.engine.HermesBundlePatcher.apply] takes the list to apply, so a selection
     * is honoured by handing it a shorter list, and a function that is not selected is never
     * touched. A function with no entry here cannot be selected - which is why a test asserts
     * there are none.
     */
    fun selectPatches(
        selection: PatchSelection,
        patches: List<FunctionPatch> = DiscordHermesBundlePatch.PATCHES
    ): List<FunctionPatch> = patches.filter { selection.contains(itemKeyOf(it.functionId)) }

    /** Names shared by more than one patched function, and so not enough to tell them apart. */
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
