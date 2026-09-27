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
 * - Closing the RPC service is not a preference. [Plan.overrides] carries it and says why.
 *
 * Keeping the decision in one pure function is what makes it checkable: the pipeline applies the
 * plan and reports it, and a test can assert the plan for a given run without a device, an APK or
 * a manifest.
 */
object DiscordManifestEdits {

    /**
     * The application these edits were written for.
     *
     * The one edit with no switch behind it runs on every job, so a job against another app has to
     * be able to tell that the edit was never about it: this is that answer, and it is what keeps
     * an OctoGram job from reporting on a component it has never declared.
     */
    const val PACKAGE_NAME = "com.discord"

    /**
     * What one run asks the manifest pass to do, with the removals kept in the groups they were
     * decided in.
     *
     * Grouped rather than pooled because each group has its own reason and its own switch, and the
     * step log reports each of them against the count it actually removed: one pass carries all
     * four, so a count taken from [removals] would credit every group with the others' work.
     */
    data class Plan(
        val permissions: List<BinaryXmlEditor.ElementSelector>,
        val sentryProviders: List<BinaryXmlEditor.ElementSelector>,
        val playSplitMarkers: List<BinaryXmlEditor.ElementSelector>,
        val attributionQuery: List<BinaryXmlEditor.ElementSelector>,
        val overrides: List<BinaryXmlEditor.AttributeOverride>
    ) {
        /** Every element this plan removes, in the order it was decided. */
        val removals: List<BinaryXmlEditor.ElementSelector>
            get() = permissions + sentryProviders + playSplitMarkers + attributionQuery

        /** Whether there is anything at all to do. */
        val isEmpty: Boolean get() = removals.isEmpty() && overrides.isEmpty()
    }

    /**
     * The plan for a run where [activePatchIds] are the patch sets that will actually run,
     * [mergedLibraries] is how many native libraries the split merge brought in, and
     * [removedPermissions] are the declarations the user switched off.
     *
     * [activePatchIds] is the set of sets that survived selection, so an id is present only when
     * something in its set is going to run: a user who turned every item of a set off has the set
     * treated as absent, which is what keeps a component from being deleted while its code is
     * still being installed.
     */
    fun plan(
        activePatchIds: Set<String>,
        mergedLibraries: Int,
        removedPermissions: List<String>
    ): Plan {
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

        // Closing the RPC service is the one edit with no switch behind it, and that is the point
        // of it. The service is exported, requires no permission and checks nothing about its
        // caller, so any application on the device can bind it and publish presence frames as the
        // user. The other position of a switch over it would be "leave that open", which is a
        // choice to make the user less safe rather than a preference about how the app works, and
        // this patcher does not offer those. It is reported either way, so a build that no longer
        // declares the service says so instead of passing quietly.
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
            permissions = permissionSelectors,
            sentryProviders = sentryProviderSelectors,
            playSplitMarkers = playSplitSelectors,
            attributionQuery = attributionQuerySelectors,
            overrides = listOf(rpcServiceOverride)
        )
    }
}
