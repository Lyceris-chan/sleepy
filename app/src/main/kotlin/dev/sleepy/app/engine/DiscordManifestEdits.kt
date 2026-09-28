package dev.sleepy.app.engine

import dev.sleepy.app.patches.DiscordNativePatches
import dev.sleepy.app.patches.DiscordPatches

/**
 * Which `AndroidManifest.xml` edits a Discord run asks for, decided from what the run is doing
 * rather than from a fixed list.
 *
 * The reference suite rewrites the manifest as text, in a script that runs once against one
 * release. This is the same set of edits expressed as selectors against the compiled document — a
 * patcher on a phone never has the text form — and every one of them is decided here rather than
 * applied unconditionally, because the edits are not all the same kind of thing:
 *
 * - The Sentry providers, the Play split markers and the attribution query each belong to
 *   something the user switched on or off. Removing a component whose code is still being
 *   installed, or leaving one whose code has been stubbed out, are both wrong, so each is tied to
 *   the switch it goes with.
 * - The dead permissions and the inert Google Analytics components are not decisions about a run
 *   at all — no switch makes either of them true or false — so they are asked for on every run
 *   against the build they were read from, and on no other.
 * - Closing the RPC service is not a preference. [Plan.rpcService] carries it and says why.
 *
 * Keeping the decision in one pure function is what makes it checkable: the pipeline applies the
 * plan and reports it, and a test can assert the plan for a given run without a device, an APK or
 * a manifest.
 */
object DiscordManifestEdits {

    /**
     * The application these edits were written for.
     *
     * The edits with no switch behind them run on every job, so a job against another app has to
     * be able to tell that the edit was never about it: this is that answer. It is what keeps an
     * OctoGram job from reporting on a component it has never declared — and what keeps the two
     * edits that name a list read off this build from landing on a build nothing was read from.
     */
    const val PACKAGE_NAME = "com.discord"

    /**
     * The declarations a job against [packageName] removes without a switch behind them.
     *
     * This is the one answer to "which of this build's permissions does sleepy take out anyway",
     * and it is a function rather than a list because the answer is about the build and not about
     * the list: [plan] edits the manifest with it, and the permission list asks the same question
     * so a row can say that its switch is not what decides. Two places deciding it separately is
     * how a switch ends up describing a build it does not produce.
     *
     * Asked for on the build these names were read from and on no other. Each was checked against
     * Discord's patched tree, and a list of names does not travel: OctoGram declares
     * READ_CONTACTS and syncs the address book through it, so an edit carried across to another app
     * would take away a permission that app is using.
     */
    fun deadPermissionsIn(packageName: String?): List<String> =
        if (packageName == PACKAGE_NAME) DiscordPatches.DEAD_PERMISSIONS else emptyList()

    /**
     * What one run asks the manifest pass to do, with the removals kept in the groups they were
     * decided in.
     *
     * Grouped rather than pooled because each group has its own reason and its own switch, and the
     * step log reports each of them against the count it actually removed: one pass carries them
     * all, so a count taken from [removals] would credit every group with the others' work.
     */
    data class Plan(
        val deadPermissions: List<BinaryXmlEditor.ElementSelector>,
        val permissions: List<BinaryXmlEditor.ElementSelector>,
        val sentryProviders: List<BinaryXmlEditor.ElementSelector>,
        val playSplitMarkers: List<BinaryXmlEditor.ElementSelector>,
        val attributionQuery: List<BinaryXmlEditor.ElementSelector>,
        val rpcService: BinaryXmlEditor.AttributeOverride,
        val googleAnalytics: List<BinaryXmlEditor.AttributeOverride>
    ) {
        /** Every element this plan removes, in the order it was decided. */
        val removals: List<BinaryXmlEditor.ElementSelector>
            get() = deadPermissions + permissions + sentryProviders + playSplitMarkers + attributionQuery

        /**
         * Every attribute this plan rewrites, in the order it was decided.
         *
         * [rpcService] is held apart from [googleAnalytics] for the same reason the removals are
         * grouped: the two are reported separately, and a caller reaching for "the override" by
         * position would be reading a Google component the day another one is added in front of it.
         */
        val overrides: List<BinaryXmlEditor.AttributeOverride>
            get() = listOf(rpcService) + googleAnalytics

        /** Whether there is anything at all to do. */
        val isEmpty: Boolean get() = removals.isEmpty() && overrides.isEmpty()
    }

    /**
     * The plan for a run against [packageName], where [activePatchIds] are the patch sets that will
     * actually run, [mergedLibraries] is how many native libraries the split merge brought in, and
     * [removedPermissions] are the declarations the user switched off.
     *
     * [packageName] is the application the source manifest belongs to — the one the job was pointed
     * at, not the name a clone build renames it to, because the edits are made before that rename.
     * It decides the two groups that are facts about one build rather than choices about a run.
     *
     * [activePatchIds] is the set of sets that survived selection, so an id is present only when
     * something in its set is going to run: a user who turned every item of a set off has the set
     * treated as absent, which is what keeps a component from being deleted while its code is
     * still being installed.
     */
    fun plan(
        packageName: String?,
        activePatchIds: Set<String>,
        mergedLibraries: Int,
        removedPermissions: List<String>
    ): Plan {
        // The two groups below are read off this build rather than chosen about it, so they are
        // asked for only when the job is about it.
        val isDiscordBuild = packageName == PACKAGE_NAME

        // The declarations this build has no code behind. No switch reaches this group: nothing in
        // the app makes one of these permissions live again, so unlike the Sentry providers and the
        // attribution query there is no second position for a run to hold — the reference strips
        // them from every build it makes.
        //
        // The list is asked for rather than spelled out here, because it is also what the permission
        // list marks its rows with: a permission section that offered a switch over one of these
        // would be describing a manifest this pass is about to edit.
        val deadPermissionSelectors = deadPermissionsIn(packageName).map { permission ->
            BinaryXmlEditor.ElementSelector(
                namePrefix = BinaryXmlEditor.ELEMENT_USES_PERMISSION,
                attributeId = BinaryXmlEditor.ATTR_NAME,
                attributeValue = permission
            )
        }

        val permissionSelectors = removedPermissions.map { permission ->
            BinaryXmlEditor.ElementSelector(
                namePrefix = BinaryXmlEditor.ELEMENT_USES_PERMISSION,
                attributeId = BinaryXmlEditor.ATTR_NAME,
                attributeValue = permission
            )
        }

        // The crash reporter's own `<provider>`s, on the same switch that drops its native
        // artefacts. The platform instantiates a declared provider while the process starts,
        // before any of the stubbed entry points is reached, so stubbing the SDK's Java without
        // removing the declaration leaves the reporter starting and then discarding what it
        // collects — which is not the same thing as it not running.
        val sentryProviderSelectors = if (DiscordPatches.SENTRY.id in activePatchIds) {
            DiscordPatches.SENTRY_PROVIDERS.map { provider ->
                BinaryXmlEditor.ElementSelector(
                    namePrefix = BinaryXmlEditor.ELEMENT_PROVIDER,
                    attributeId = BinaryXmlEditor.ATTR_NAME,
                    attributeValue = provider
                )
            }
        } else {
            emptyList()
        }

        // The markers Play's split installer writes. They describe an APK that is one split of an
        // App Bundle, which is only what this file was before the merge: an APK with the splits'
        // libraries inside it is not a split and must not claim to be one, and
        // `com.android.vending.splits.required` is read by the Play Store as an assertion that the
        // app is missing the rest of its splits.
        val playSplitSelectors = if (mergedLibraries > 0) {
            DiscordPatches.PLAY_SPLIT_MARKERS.map { marker ->
                BinaryXmlEditor.ElementSelector(
                    namePrefix = BinaryXmlEditor.ELEMENT_META_DATA,
                    attributeId = BinaryXmlEditor.ATTR_NAME,
                    attributeValue = marker
                )
            }
        } else {
            emptyList()
        }

        // The AppsFlyer package-visibility query, tied to the switch that no-ops that SDK's only
        // initialiser. The query is how the app asks the platform whether the install-referrer
        // provider is present; with the initialiser stubbed the answer goes nowhere, and a
        // visibility declaration left behind is a capability the app no longer has a use for. A
        // build still running the initialiser keeps the query it still uses.
        //
        // `<intent>` carries no attributes of its own, so what tells this one from the others is
        // the `<action>` inside it.
        val attributionQuerySelectors = if (DiscordNativePatches.DEEP_LINKS.id in activePatchIds) {
            listOf(
                BinaryXmlEditor.ElementSelector(
                    namePrefix = BinaryXmlEditor.ELEMENT_INTENT,
                    contains = BinaryXmlEditor.ElementSelector(
                        namePrefix = BinaryXmlEditor.ELEMENT_ACTION,
                        attributeId = BinaryXmlEditor.ATTR_NAME,
                        attributeValue = DiscordPatches.APPSFLYER_INSTALL_PROVIDER_ACTION
                    )
                )
            )
        } else {
            emptyList()
        }

        // Google Analytics: inert in this build, and switched off the way the reference switches it
        // off. The declarations stay — the SDK's classes are still in the dex and the manifest
        // still describes them — but the platform never instantiates a component whose
        // `android:enabled` is false, so the receiver never sees a broadcast and the JobService is
        // never bound. Deleting the elements instead would be a claim about the code the app holds;
        // this is a claim about what the app does, and it is the one the reference makes.
        val analyticsOverrides = if (isDiscordBuild) {
            DiscordPatches.GOOGLE_ANALYTICS_COMPONENTS.map { component ->
                BinaryXmlEditor.AttributeOverride(
                    element = BinaryXmlEditor.ElementSelector(
                        namePrefix = component.element,
                        attributeId = BinaryXmlEditor.ATTR_NAME,
                        attributeValue = component.name
                    ),
                    attributeId = BinaryXmlEditor.ATTR_ENABLED,
                    value = false
                )
            }
        } else {
            emptyList()
        }

        // Closing the RPC service carries no switch either, and that is the point of it. The
        // service is exported, requires no permission and checks nothing about its caller, so any
        // application on the device can bind it and publish presence frames as the user. The other
        // position of a switch over it would be "leave that open", which is a choice to make the
        // user less safe rather than a preference about how the app works, and this patcher does
        // not offer those. It is reported either way, so a build that no longer declares the
        // service says so instead of passing quietly.
        val rpcServiceOverride = BinaryXmlEditor.AttributeOverride(
            element = BinaryXmlEditor.ElementSelector(
                namePrefix = BinaryXmlEditor.ELEMENT_SERVICE,
                attributeId = BinaryXmlEditor.ATTR_NAME,
                attributeValue = DiscordPatches.RPC_SERVICE_NAME
            ),
            attributeId = BinaryXmlEditor.ATTR_EXPORTED,
            value = false
        )

        return Plan(
            deadPermissions = deadPermissionSelectors,
            permissions = permissionSelectors,
            sentryProviders = sentryProviderSelectors,
            playSplitMarkers = playSplitSelectors,
            attributionQuery = attributionQuerySelectors,
            rpcService = rpcServiceOverride,
            googleAnalytics = analyticsOverrides
        )
    }
}
