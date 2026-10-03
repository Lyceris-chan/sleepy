package dev.sleepy.app.patches

import dev.sleepy.app.model.GeneratedPatches
import dev.sleepy.app.model.PatchGenerator
import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.SelectivePatchGenerator
import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.model.TargetApk

/**
 * What every OctoGram edit is, in the user's terms: the switch it belongs to, the feature it sits
 * under, and the line describing what switching it on does.
 *
 * One switch per set does not fit a set that holds more than one decision. The clearest case is
 * the logger: silencing the emitters and silencing the uploaders are two choices, and a user who
 * wants the log written to logcat but nothing shipped to Telegram's servers cannot express that
 * behind one switch. The premium rows are the opposite case and are deliberately *one* item over
 * three edits—hiding the premium row alone makes the sections row appear in its place, so a list
 * offering that edit on its own would offer a state the reference scripts document as incorrect.
 *
 * The patches themselves stay in [OctoGramPatches], grouped one list per item, next to the set they
 * belong to: this table pairs a group with the switch that turns it on, and owns the wording. An
 * entry's patches are the same objects the engine applies, so there is one definition of each edit
 * and no second copy to fall out of step.
 *
 * Every entry names a group of patches that exists in the reference scripts, and a test asserts
 * that the entries and the sets cover each other exactly—a set with no entry would be a set that
 * cannot be selected at all, and an entry with no patch would be a row describing a change it does
 * not make.
 *
 * The per-entry wording matches the sets' own descriptions in [OctoGramPatches]: it describes how
 * the app stops spending the user's data and attention rather than which method a letter-name
 * replaced this release. The class, the method and the version tag stay in the entry's own fields,
 * where the technical panel reads them from.
 */
object OctoGramPatchItems {

    /**
     * One item: the switch, and the edits it applies.
     *
     * @property identity the item's identity within its set—see [PatchItem.identity]. It is
     *     what a saved selection records, so it is a name for the thing being switched off and
     *     not an index: these have to survive a release that reorders an entry's patches or
     *     splits an item into two.
     * @property label the row's name as the list shows it.
     * @property group the feature heading the row is listed under.
     * @property description one line describing what switching the item on does to the app.
     * @property patches the edits this item applies, in the order they are applied.
     */
    data class Entry(
        val identity: String,
        val label: String,
        val group: String,
        val description: String,
        val patches: List<SmaliPatch>
    )

    // The feature headings, declared once. A heading repeated inline at each use is a heading that
    // can be spelled two ways, which would list one feature twice.
    private const val CHANNEL_POSTS = "Sponsored posts in channels"
    private const val MEDIA_VIEWER = "Ads in the media viewer"
    private const val SEARCH = "Sponsored search results"
    private const val UPDATE_CHECK = "The built-in update check"
    private const val PAYWALL = "Premium paywall screens"
    private const val OCTO_LOG = "OctoGram's own diagnostic log"
    private const val LOGGING = "Telegram's own logging"
    private const val FIREBASE = "Firebase components registered at startup"
    private const val CRASH_REPORTER = "OctoGram's own crash reporter"
    private const val PREMIUM_ROWS = "Premium rows in the profile's settings list"

    /** The identity of the one Firebase item every Firebase set has. */
    private const val REGISTRAR = "registrar"

    /**
     * Every set's entries, by set id.
     *
     * Keyed by the set's own id (`OctoGramPatches.SPONSORED_MSGS.id` rather than the string again),
     * so an id renamed in one place cannot leave a set with no items and an entry no set can
     * select. Reading those ids here works in either initialization order because nothing in
     * [OctoGramPatches] reads this table while building its sets—see [OctoGramItemGenerator].
     */
    private val ENTRIES: Map<String, List<Entry>> = linkedMapOf(
        OctoGramPatches.SPONSORED_MSGS.id to listOf(
            Entry(
                identity = "chatListRows",
                label = "The sponsored rows in a channel's chat list",
                group = CHANNEL_POSTS,
                description = "A channel's chat list is built without the sponsored rows and the bot banner, " +
                    "rather than built with them and then hidden. Only the rows inside a channel are affected; " +
                    "the sponsored channels Telegram puts in search are a separate choice.",
                patches = OctoGramPatches.SPONSORED_CHAT_LIST_ROWS
            ),
            Entry(
                identity = "sponsoredRequest",
                label = "The request that would fill a sponsored row",
                group = CHANNEL_POSTS,
                description = "The request that fetches the posts a sponsored row would show is never made, so " +
                    "there is nothing to display even where a row exists. This is the half that stops the " +
                    "traffic; the rows above are the half that stops them being built.",
                patches = OctoGramPatches.SPONSORED_REQUEST
            )
        ),
        OctoGramPatches.PHOTO_VIEWER_ADS.id to listOf(
            Entry(
                identity = "viewerCallback",
                label = "The full-screen promotion between photos",
                group = MEDIA_VIEWER,
                description = "Swiping through a channel's photos no longer stops on a full-screen promotion, and " +
                    "the request behind it is not made. Photos, videos and GIFs themselves are untouched.",
                patches = OctoGramPatches.PHOTO_VIEWER_CALLBACK
            )
        ),
        OctoGramPatches.SEARCH_ADS.id to listOf(
            Entry(
                identity = "sponsoredResponse",
                label = "Promoted channels and bots in search",
                group = SEARCH,
                description = "Search results hold only what matched what you typed: the response carrying " +
                    "promoted peers is discarded and the request it belongs to is closed out, so nothing is " +
                    "left waiting.",
                patches = OctoGramPatches.SEARCH_SPONSORED_RESULTS
            )
        ),
        OctoGramPatches.OTA_UPDATER.id to listOf(
            Entry(
                identity = "updateRequest",
                label = "The GitHub release request",
                group = UPDATE_CHECK,
                description = "The app stops asking GitHub whether a newer OctoGram exists. The \"you are up to " +
                    "date\" path is signaled instead, so the update screen still finishes rather than waiting " +
                    "on a reply that will never arrive.",
                patches = OctoGramPatches.OTA_UPDATE_CHECK
            )
        ),
        OctoGramPatches.PREMIUM_UPSELL.id to listOf(
            Entry(
                identity = "routerGuard",
                label = "The navigation router",
                group = PAYWALL,
                description = "Navigation into the Premium screen is dropped at the router, so the paywall does " +
                    "not open when something in the app tries to show it. This is the path most of the app's " +
                    "paywall entry points go through.",
                patches = OctoGramPatches.PREMIUM_UPSELL_ROUTER
            ),
            Entry(
                identity = "windowStackOverride",
                label = "The main window's override",
                group = PAYWALL,
                description = "The same drop on the window-stack override the main window uses. The paywall is " +
                    "reachable that way too, so the router guard alone would leave those paths open—the two " +
                    "are separate switches because they are separate code paths, and switching both on is the " +
                    "usual choice.",
                patches = OctoGramPatches.PREMIUM_UPSELL_WINDOW_STACK
            )
        ),
        OctoGramPatches.OCTO_LOGGER.id to listOf(
            Entry(
                identity = "emitters",
                label = "The log itself (12 entry points)",
                group = OCTO_LOG,
                description = "All twelve entry points of OctoGram's log class become no-ops: the eleven emitters " +
                    "and the sink they all write through. Nothing further reaches logcat or OctoGram's own log " +
                    "file, including the one emitter that redacts secrets before writing. What is already in the " +
                    "log file stays readable, and the screens that list and delete it keep working.",
                patches = OctoGramPatches.LOG_EMITTERS
            ),
            Entry(
                identity = "uploaders",
                label = "Sending a log to Telegram's servers",
                group = OCTO_LOG,
                description = "The five places that would ship a diagnostic off the device become no-ops: the " +
                    "three behind the app's own \"send log\" action, the one in the app's message controller, " +
                    "and the one in the resource-usage helper. Nothing is uploaded through any of them.",
                patches = OctoGramPatches.LOG_UPLOADERS
            )
        ),
        OctoGramPatches.LOGGING_GATE.id to listOf(
            Entry(
                identity = "globalFlag",
                label = "The global logging flag",
                group = LOGGING,
                description = "Telegram decides whether to write its own logs from a single flag read in hundreds " +
                    "of places; pinning it to false at the four sites that write it turns logging off " +
                    "everywhere at once, rather than editing every reader. One consequence is part of the " +
                    "shipped behavior: the same flag also decides whether a custom uncaught-exception handler " +
                    "is installed, so that handler is not installed either.",
                patches = OctoGramPatches.LOGGING_GATE_WRITES
            )
        ),
        OctoGramPatches.FIREBASE_ABT.id to listOf(
            Entry(
                identity = REGISTRAR,
                label = "A/B testing components",
                group = FIREBASE,
                description = "Startup registers nothing for Firebase A/B testing: no experiment is assigned and no " +
                    "experiment telemetry is produced.",
                patches = OctoGramPatches.FIREBASE_ABT_REGISTRAR
            )
        ),
        OctoGramPatches.FIREBASE_REMOTE_CONFIG.id to listOf(
            Entry(
                identity = REGISTRAR,
                label = "Remote Config components",
                group = FIREBASE,
                description = "Startup registers nothing for Firebase Remote Config, so the app no longer downloads " +
                    "server-side experiment flags or telemetry parameters.",
                patches = OctoGramPatches.FIREBASE_REMOTE_CONFIG_REGISTRAR
            )
        ),
        OctoGramPatches.FIREBASE_REMOTE_CONFIG_KTX.id to listOf(
            Entry(
                identity = REGISTRAR,
                label = "Remote Config's Kotlin wrapper",
                group = FIREBASE,
                description = "Startup registers nothing for the Kotlin extension that wraps Remote Config, so it " +
                    "never subscribes to an app's lifecycle on the app's behalf.",
                patches = OctoGramPatches.FIREBASE_REMOTE_CONFIG_KTX_REGISTRAR
            )
        ),
        OctoGramPatches.FIREBASE_DATATRANSPORT.id to listOf(
            Entry(
                identity = REGISTRAR,
                label = "Google Play Services data transport",
                group = FIREBASE,
                description = "Startup registers nothing for Google Play Services' DataTransport, so no telemetry " +
                    "event can be queued, batched or uploaded to Google's backends.",
                patches = OctoGramPatches.FIREBASE_DATATRANSPORT_REGISTRAR
            )
        ),
        OctoGramPatches.CRASH_REPORTER.id to listOf(
            Entry(
                identity = "startup",
                label = "The crash handler installed while the app starts",
                group = CRASH_REPORTER,
                description = "OctoGram's own crash reporter is not installed, so a crash writes no crash log into " +
                    "the app's storage and raises no \"OctoGram just crashed!\" notification next time the app " +
                    "opens. It would be installed regardless of Telegram's logging flag, which is why it takes " +
                    "a switch of its own. Telegram's own crash handling is a different one and is untouched.",
                patches = OctoGramPatches.CRASH_REPORTER_STARTUP
            )
        ),
        OctoGramPatches.PREMIUM_SETTINGS.id to listOf(
            Entry(
                identity = "premiumRows",
                label = "The premium rows in the settings list",
                group = PREMIUM_ROWS,
                description = "The settings list on your profile no longer carries the Telegram Premium row or the " +
                    "Send a Gift row, and the combined premium-sections row that would appear in their place is " +
                    "kept out as well. The three are one switch: the rows share one list and their conditions " +
                    "overlap, so hiding only the first would put a different premium row on screen. Telegram " +
                    "Stars, TON and Telegram Business are separate products and stay visible.",
                patches = OctoGramPatches.PREMIUM_SETTINGS_ROWS
            )
        )
    )

    /** Every set id this table has entries for, in table order. */
    val SET_IDS: List<String> = ENTRIES.keys.toList()

    /** The entries of [setId], or an empty list when no set has that id. */
    fun entries(setId: String): List<Entry> = ENTRIES[setId].orEmpty()

    /**
     * Every item of [setId], in the order the UI should list them.
     *
     * The identity is the entry's own, so it is stable across releases: a saved selection names the
     * thing being switched off, not its position in this list.
     */
    fun items(setId: String): List<PatchItem> = entries(setId).map { entry ->
        PatchItem(
            setId = setId,
            identity = entry.identity,
            label = entry.label,
            group = entry.group,
            description = entry.description
        )
    }

    /**
     * The item key of one entry, for example `octogram_logger:emitters`—what a saved selection
     * records.
     */
    fun itemKeyOf(setId: String, identity: String): String = PatchItem.keyOf(setId, identity)

    /**
     * Every patch of a set's items, in item order—what the set applies when nothing is
     * narrowed.
     */
    fun patches(setId: String): List<SmaliPatch> = entries(setId).flatMap { it.patches }

    /** The patches of [setId] whose items [selection] switches on, in item order. */
    fun patches(setId: String, selection: PatchSelection): List<SmaliPatch> =
        entries(setId)
            .filter { selection.contains(itemKeyOf(setId, it.identity)) }
            .flatMap { it.patches }

    /**
     * The exact thing one item touches, in the shape the row's technical target takes.
     *
     * A set that applies one edit names that edit; an item holding several names the class they are
     * in and how many there are, because a row is one line and the twelve emitters are not a line.
     * The full list is one tap further on, in the set's own technical panel.
     */
    fun technicalTarget(item: PatchItem): String? {
        val entry = entryOf(item) ?: return null
        val classes = entry.patches.map { it.smaliPath }.distinct()
        if (entry.patches.size == 1) {
            return "${classes.single()} · ${shapeOf(entry.patches.single())}"
        }
        return if (classes.size == 1) {
            "${classes.single()} · ${entry.patches.size} smali entries"
        } else {
            "${entry.patches.size} smali entries in ${classes.size} classes: ${classes.joinToString(", ")}"
        }
    }

    /**
     * The longer text behind one item's technical target.
     *
     * An item with a single edit spells that edit out here, because its row is the only place the
     * user can read it before patching. An item with several lists their titles and points at the
     * set's technical panel for the full text of each, which is where the panel already prints
     * them: the explanations are moved rather than dropped.
     */
    fun detail(item: PatchItem): String? {
        val entry = entryOf(item) ?: return null
        val single = entry.patches.singleOrNull()
        if (single != null) {
            val title = single.title?.takeIf { it.isNotBlank() }
            val explanation = single.explanation?.takeIf { it.isNotBlank() }
            val text = listOfNotNull(title, explanation).joinToString(" ")
            return "$text Selection key ${item.key}."
        }
        val titles = entry.patches.mapNotNull { it.title?.takeIf(String::isNotBlank) }
        return "${entry.patches.size} entries in this item: ${titles.joinToString("; ")}. " +
            "Each is listed in full under this set's technical details. Selection key ${item.key}."
    }

    /** The entry an item stands for, or null when no entry of its set has that identity. */
    private fun entryOf(item: PatchItem): Entry? =
        entries(item.setId).firstOrNull { it.identity == item.identity }

    /** What one edit does to the smali file it names: a whole method, a case, or a splice. */
    private fun shapeOf(patch: SmaliPatch): String = when {
        !patch.methodSignature.isNullOrBlank() -> "replaces ${patch.methodSignature}"
        !patch.switchCaseLabel.isNullOrBlank() -> "slices switch case ${patch.switchCaseLabel}"
        !patch.anchor.isNullOrBlank() ->
            "splices around: " +
                patch.anchor.lines().joinToString(" ; ") { it.trim() }.trim(' ', ';')

        else -> "one smali entry"
    }
}

/**
 * The generator one OctoGram set passes the engine, so the set applies the edits of the items the
 * user has switched on and nothing else.
 *
 * The engine applies a set's own patches whether or not they are selected, and calls a generator
 * only for the subset a selection names—so a set whose edits are switched one at a time has to
 * reach the engine this way. Each set gets its own instance because the engine passes a generator
 * nothing but the target APK and the selection: the set it belongs to is the one value it has to
 * hold itself.
 *
 * It is a class of its own rather than a member of [OctoGramPatchItems] on purpose: a member would
 * be reached through that object's instance, so building the patch table would initialize the item
 * table, and initializing the item table reads the patch table. Nothing here reads either at
 * construction time, which keeps the two files' initializers independent of each other.
 */
internal class OctoGramItemGenerator(private val setId: String) :
    PatchGenerator, SelectivePatchGenerator {

    override fun generate(target: TargetApk): GeneratedPatches =
        GeneratedPatches(patches = OctoGramPatchItems.patches(setId))

    override fun generate(target: TargetApk, selection: PatchSelection): GeneratedPatches {
        val patches = OctoGramPatchItems.patches(setId, selection)
        // The pipeline drops a set with no selected item before it reaches a generator, so this
        // check is the second line of defense rather than the first: it exists so that a set
        // that somehow arrives with nothing to apply is reported as skipped with a reason, rather
        // than reported as patched having done nothing.
        if (patches.isEmpty()) {
            return GeneratedPatches(
                patches = emptyList(),
                skipReason = "No item of this set is selected, so this set has nothing to apply."
            )
        }
        return GeneratedPatches(patches = patches)
    }
}
