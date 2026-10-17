package dev.sleepy.app.engine

import dev.sleepy.app.patches.DiscordNativePatches
import dev.sleepy.app.patches.DiscordPatches

/**
 * The `AndroidManifest.xml` edits a Discord run requests, selected from what the run does.
 *
 * The edits are selectors against the compiled document, because a patcher on a phone has no text
 * form. They fall into groups: the Sentry providers, Play split markers and attribution query
 * follow the switches they belong to, so manifest and code stay in step; the dead permissions and
 * inert Analytics components are not decisions and are requested on every run against the build
 * they were read from; closing the RPC service is not a preference either ([Plan.rpcService]).
 *
 * Keeping the decision in one pure function is what lets a test assert a plan without a device or
 * an APK.
 */
object DiscordManifestEdits {

    /**
     * The application these edits were written for.
     *
     * The edits with no switch behind them run on every job, so a job against another app needs a
     * way to determine that the edit was not written for it: this constant provides that answer.
     * It keeps an OctoGram job from reporting on a component that the build does not declare, and
     * it keeps the two edits that name a list read from this build from being applied to a build
     * that the list did not come from.
     */
    const val PACKAGE_NAME = "com.discord"

    /**
     * The declarations a job against [packageName] removes without a switch behind them.
     *
     * This function is the single source for the question "which of this build's permissions does
     * the patcher remove regardless", and it is a function rather than a list because the answer
     * is about the build and not about the list: [plan] edits the manifest with it, and the
     * permission list calls the same function so a row can report that its switch is not what
     * determines the removal. A second implementation lets a switch describe a build that it
     * does not produce.
     *
     * The result applies to the build these names were read from and to no other build. Each name
     * was checked against Discord's patched tree, and the list does not apply to other apps:
     * OctoGram declares READ_CONTACTS and syncs the address book through it, so an edit carried
     * across to another app removes a permission that the app uses.
     */
    fun deadPermissionsIn(packageName: String?): List<String> =
        if (packageName == PACKAGE_NAME) DiscordPatches.DEAD_PERMISSIONS else emptyList()

    /**
     * The work that one run requests from the manifest pass, with removals kept in the groups
     * that determined them.
     *
     * Grouped rather than pooled because each group has its own reason and its own switch, and the
     * step log reports each of them against the count it removed: one pass carries them all, so a
     * count taken from [removals] cannot be attributed to a single group.
     */
    data class Plan(
        /** Selectors for the declarations that the build does not use. */
        val deadPermissions: List<BinaryXmlEditor.ElementSelector>,
        /** Selectors for the declarations that the user switched off. */
        val permissions: List<BinaryXmlEditor.ElementSelector>,
        /** Selectors for the crash reporter's providers, when its patch set is active. */
        val sentryProviders: List<BinaryXmlEditor.ElementSelector>,
        /** Selectors for the markers that Play's split installer writes, when libraries merge. */
        val playSplitMarkers: List<BinaryXmlEditor.ElementSelector>,
        /** Selectors for the AppsFlyer package-visibility query, when its patch set is active. */
        val attributionQuery: List<BinaryXmlEditor.ElementSelector>,
        /** The override that closes the RPC service. */
        val rpcService: BinaryXmlEditor.AttributeOverride,
        /** Overrides that disable the inert Google Analytics components. */
        val googleAnalytics: List<BinaryXmlEditor.AttributeOverride>
    ) {
        /** Every element this plan removes, in the order of the preceding groups. */
        val removals: List<BinaryXmlEditor.ElementSelector>
            get() = deadPermissions + permissions + sentryProviders + playSplitMarkers +
                attributionQuery

        /**
         * Every attribute this plan rewrites, in the order of the preceding groups.
         *
         * [rpcService] is held apart from [googleAnalytics] for the same reason the removals are
         * grouped: the two are reported separately, and a caller that selects the override by
         * position reads a Google component when another override is added first.
         */
        val overrides: List<BinaryXmlEditor.AttributeOverride>
            get() = listOf(rpcService) + googleAnalytics

        /** Whether there is anything at all to do. */
        val isEmpty: Boolean get() = removals.isEmpty() && overrides.isEmpty()
    }

    /**
     * Builds the manifest-edit plan for a run against [packageName].
     *
     * @param packageName the application the source manifest belongs to—the one the job was
     *     pointed at, not the name a clone build renames it to, because the edits are made before
     *     that rename. It determines the two groups that are facts about one build rather than
     *     choices about a run.
     * @param activePatchIds the patch sets that are going to run, so an id is present only when
     *     something in its set runs: a user who turned every item of a set off has the set treated
     *     as absent, which keeps a component from being deleted while its code is still being
     *     installed.
     * @param mergedLibraries the number of native libraries that the split merge brought in.
     * @param removedPermissions the declarations that the user switched off.
     * @return the plan for the run.
     */
    fun plan(
        packageName: String?,
        activePatchIds: Set<String>,
        mergedLibraries: Int,
        removedPermissions: List<String>
    ): Plan {
        // The two following groups are facts about this build rather than choices about the run, so
        // the plan requests them only when the job targets this build.
        val isDiscordBuild = packageName == PACKAGE_NAME

        // Declarations this build never uses. No switch controls this group: the recorded change
        // set removes them from every build, and the permission list marks the same rows, so a
        // section offering a switch over one would describe a manifest this pass is about to
        // edit.
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
        // artifacts. The platform instantiates a declared provider while the process starts,
        // before any of the stubbed entry points is reached, so stubbing the SDK's Java without
        // removing the declaration still makes the platform instantiate the reporter, which then
        // discards what it collects.
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
        // App Bundle, which describes this file only before the merge: an APK with the splits'
        // libraries inside it is not a split, so the marker does not apply, and
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

        // The AppsFlyer visibility query, tied to the switch that no-ops its initializer: with
        // the initializer stubbed no caller reads the answer. The `<intent>` carries no
        // attributes, so its `<action>` is what identifies it.
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

        // Google Analytics: inert in this build, and switched off the way the recorded change
        // set switches it off. The declarations stay—the SDK's classes are still in the dex and
        // the manifest still describes them—but the platform does not instantiate a component
        // whose `android:enabled` is false, so the receiver does not receive a broadcast and the
        // JobService is not bound. Deleting the elements instead makes a claim about the code
        // the app contains; this makes a claim about what the app does, and it is the one the
        // recorded change set makes.
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

        // Closing the RPC service carries no switch either. The service is exported, requires no
        // permission and checks nothing about its caller, so any application on the device can
        // bind it and publish presence frames as the user. The other position of a switch over it
        // is "leave that open", which exposes the service to any app on the device rather than
        // changing how the app works, and this patcher does not offer such switches. The edit is
        // reported either way, so a build that does not declare the service produces a report
        // rather than no output.
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
