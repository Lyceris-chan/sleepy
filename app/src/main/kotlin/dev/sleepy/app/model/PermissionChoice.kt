package dev.sleepy.app.model

/**
 * One permission sleepy can describe: what it lets the app do, and whether removing it is offered.
 *
 * @property name The permission as the manifest writes it, for example
 *   `android.permission.READ_CONTACTS`. This is also the entry's identity, because it is the only
 *   thing stable across releases: the platform fixes a permission's name, so a saved choice keeps
 *   meaning the same permission however the list it was made against was produced.
 * @property label The short name the UI shows.
 * @property description What the permission allows, and what stops working once the declaration
 *   is gone. It states the full consequence of removal, because the user cannot infer that
 *   consequence—see [PermissionRow].
 * @property lockReason Why removing this declaration is not offered at all, or null when the user
 *   can remove it. Non-null only where the app's *core* function depends on the declaration being
 *   there, not merely a feature: a feature that checks for its own permission degrades, while one
 *   the app assumes is present throws.
 */
data class Permission(
    val name: String,
    val label: String,
    val description: String,
    val lockReason: String? = null
) {
    /** True when this permission is locked: the row is disabled and shows the reason. */
    val essential: Boolean get() = lockReason != null

    /**
     * The identity of this permission within the permission set, and the second half of its key.
     */
    val identity: String get() = name
}

/**
 * One permission as the list renders it: the entry, whether it is kept, and why the switch is
 * fixed when it is.
 *
 * @property kept Whether the app keeps this permission. The switch is labeled "kept" rather than
 *   "removed" because that is the state the build starts in: an app declares the permissions it
 *   requests, so every row starts switched on, and switching one off is the change being made.
 * @property lockedReason Why this row cannot be switched off, or null when it can be. Three
 *   statements share this field and each is the model's own sentence: the permission is one the
 *   app cannot work without ([Permission.lockReason]), it is the only one left and sleepy does
 *   not produce a build with no permissions at all
 *   ([PermissionCoverage.LAST_PERMISSION_REASON]), or the build removes the declaration
 *   regardless of the choice ([PermissionCoverage.ALWAYS_REMOVED_REASON]).
 *
 *   [kept] identifies which of the three it is; use [kept] rather than the reason's text: a lock
 *   the user cannot change still keeps the declaration, while a declaration this build strips is
 *   absent from the build and is shown as removed.
 */
data class PermissionRow(
    val permission: Permission,
    val kept: Boolean,
    val lockedReason: String? = null
) {
    /** True when the user can move this row's switch. */
    val switchable: Boolean get() = lockedReason == null
}

/** Where the declarations a build is offered came from. */
enum class DeclarationSource {

    /**
     * The list sleepy ships for this release of the app, which is there before anything runs.
     */
    SHIPPED,

    /** The build's own manifest, read because sleepy ships no list for this build. */
    READ_FROM_BUILD
}

/**
 * One difference between the declarations sleepy ships and the ones a build makes.
 *
 * The two directions are not the same statement and are not treated as one. What a build declares
 * and sleepy does not list is a permission with no row: the user cannot see it, cannot read what
 * it does, and cannot switch it off, which is the case that must be reported. What sleepy lists
 * and the build does not declare is a row for a declaration that is not present; leaving it is
 * harmless, but acting on it is not.
 */
sealed interface DeclarationMismatch {

    /** The permission this difference is about. */
    val name: String

    /** The build declares this and sleepy does not list it, so the list shows no row for it. */
    data class Unlisted(override val name: String) : DeclarationMismatch

    /** sleepy lists this and the build does not declare it, so there is nothing to remove. */
    data class Absent(override val name: String) : DeclarationMismatch
}

/**
 * What comparing the shipped list against the build's own manifest found.
 *
 * The build is read for this comparison and for nothing else: the list the rows and the removals
 * come from is [DeclarationSource.SHIPPED], so a build that has changed under a source is
 * described rather than re-specified without a report. A comparison that cannot be made is its
 * own state rather than an agreement, because the user acts differently on "the manifest could
 * not be read" than on "the manifest declares the same permissions".
 */
sealed interface PermissionCheck {

    /** The list and the build have not been compared. */
    data object NotChecked : PermissionCheck

    /** The build is being downloaded and its manifest read, to compare the list against it. */
    data object Checking : PermissionCheck

    /** The build declares exactly the permissions the shipped list names. */
    data object Agrees : PermissionCheck

    /** The build and the shipped list differ, in [mismatches]. */
    data class Disagrees(val mismatches: List<DeclarationMismatch>) : PermissionCheck

    /** The manifest could not be read, so nothing was compared, for [reason]. */
    data class Failed(val reason: String) : PermissionCheck

    companion object {

        /**
         * [shipped] against [declared]: agreement, or every difference in both directions.
         *
         * A permission declared twice counts once, because it is one declaration. The differences
         * follow the order of the two lists, so the report matches the order of the manifest and
         * the list.
         */
        fun of(shipped: List<String>, declared: List<String>): PermissionCheck {
            val listed = shipped.toSet()
            val inBuild = declared.toSet()
            val mismatches = buildList {
                val seen = mutableSetOf<String>()
                for (name in declared) {
                    if (name !in listed && seen.add(name)) add(DeclarationMismatch.Unlisted(name))
                }
                for (name in shipped) {
                    if (name !in inBuild && seen.add(name)) add(DeclarationMismatch.Absent(name))
                }
            }
            return if (mismatches.isEmpty()) Agrees else Disagrees(mismatches)
        }
    }
}

/**
 * What is known about the selected build's permissions.
 *
 * The list has one source and it is not the network: what sleepy ships for this release, which is
 * what makes the section reachable, because a list behind a 96 MB download is a list many users
 * do not reach. A package nothing is shipped for is the exception, and it is read from the build
 * itself, because there is nothing else to offer and a build whose permissions the app cannot
 * name is a build the user cannot make any choice about.
 *
 * Because a shipped list describes a release and a source can be pointed elsewhere, the build is
 * still read on request, as a cross-check: [PermissionCheck] carries what that read found, and the
 * differences are reported rather than used to rewrite the list.
 */
sealed interface PermissionScan {

    /** No list is shipped for this build and nothing has been read; nothing can be chosen about. */
    data object NotRead : PermissionScan

    /** The build is being read, because no list is shipped for it. */
    data object Reading : PermissionScan

    /** The build could not be read, for [reason], and no list is shipped for it. */
    data class Failed(val reason: String) : PermissionScan

    /** The declarations on offer: [declared], where they came from, and the [check] on them. */
    data class Read(
        val declared: List<String>,
        val from: DeclarationSource,
        val check: PermissionCheck = PermissionCheck.NotChecked
    ) : PermissionScan
}

/**
 * Which permission rows are locked, recomputed from the current choice.
 *
 * This is the same shape as [BlocklistCoverage]: a pure function of the table and "is this one
 * kept", so the list re-derives itself on every toggle and there is no locked state to keep in
 * step. Unlocking follows from the state: the last permission becomes locked once it is the last
 * one, and becomes switchable again the moment another one is switched back on.
 *
 * The three reasons a row can be fixed are different statements and stay distinguishable: a
 * [Permission.lockReason] is about the app, [LAST_PERMISSION_REASON] is about the state of the
 * list, and [ALWAYS_REMOVED_REASON] is about the build being patched rather than about anything
 * the user did—a declaration sleepy removes from every build of that app. The third reason
 * means a fixed row is not necessarily a kept one: the other two keep the declaration in the
 * manifest, and the third removes it.
 */
object PermissionCoverage {

    /**
     * Why the last remaining permission cannot be removed.
     *
     * Written in the vocabulary the blocklist's gates established—see
     * [BlocklistCoverage.REQUIRED_REASON_PREFIX]—because it is the same statement: this row has
     * no choice in it.
     */
    const val LAST_PERMISSION_REASON: String =
        "Required: this is the last permission left in the list, and a build that declares none " +
            "is not something sleepy will produce. An app with no permission declarations cannot " +
            "ask for any of them later, so removing this one would leave a build with no way back."

    /**
     * Why a declaration the patch removes on its own has no switch to move either.
     *
     * The other two fixed rows prevent a removal; this one also removes the choice, because there
     * is no choice left in it: the declaration is not in the app the patch builds, whatever the
     * switch state is. A row that showed this as kept is a control that misreports the build it
     * describes, which is what a user acts on: they see the app as keeping a permission it does
     * not have.
     *
     * The text names *sleepy* rather than giving a reason for each permission, because the reason
     * a given name is unused is a fact about the app's code that takes several sentences, and that
     * reason is recorded where the fact is known: the entry for the permission describes what it
     * does, and the run's step log reports the group it was removed in.
     */
    const val ALWAYS_REMOVED_REASON: String =
        "Removed: sleepy deletes this declaration from every build of this app, whether or " +
            "not the switch is on, because nothing in the patched app still refers to it. The " +
            "switch is fixed on that answer rather than offering a change that would not reach " +
            "the build."

    /**
     * [permissions] as rows, each fixed when the user's choice cannot change what the build
     * declares.
     *
     * A permission is fixed when the table locks it ([Permission.lockReason]), when this build
     * removes the declaration anyway ([removedRegardless]—see [ALWAYS_REMOVED_REASON]), or when
     * it is the last permission the current choice keeps. The last rule is computed from the same
     * [isKept] the list renders from, so it follows the user's choices rather than being a fixed
     * property of a permission: the last remaining permission becomes locked, and becomes
     * switchable again the moment another one is switched on.
     *
     * The rule is stated over the survivor rather than over the kept count alone, so that at least
     * one declaration survives for every input, not only for the inputs a user can produce
     * through the UI. A selection that names none of these permissions at all—a stale selection,
     * or one made against a different build—otherwise leaves every row switched off and every
     * declaration removable. A row this build removes regardless is not a survivor either, so it
     * takes no part in that rule: it is not a declaration the user is choosing to keep.
     *
     * The rule applies only when the table locks nothing on its own: a locked permission is kept
     * regardless of the selection, so a build with a locked permission declares at least one
     * permission, and an app that declares `INTERNET` keeps declaring it however many of the rest
     * are removed.
     */
    fun rows(
        permissions: List<Permission>,
        isKept: (Permission) -> Boolean,
        removedRegardless: Set<String> = emptySet()
    ): List<PermissionRow> {
        // A row whose state the user does not control: locked by the table, or removed by the
        // build. Both are excluded from the survivor rule, for different reasons—one keeps the
        // declaration, the other does not have it to keep.
        fun fixed(permission: Permission): Boolean =
            permission.lockReason != null || permission.identity in removedRegardless

        val hasLocked = permissions.any { it.lockReason != null }
        val keptCount = permissions.count { !fixed(it) && isKept(it) }
        // Identity, not equality: two entries with the same text are still two rows, and only the
        // last one is the survivor.
        val survivor = permissions.lastOrNull { !fixed(it) && isKept(it) }
            ?: permissions.firstOrNull { !fixed(it) }

        return permissions.map { permission ->
            val alwaysRemoved =
                permission.lockReason == null && permission.identity in removedRegardless
            val lockedReason = permission.lockReason
                ?: if (alwaysRemoved) {
                    ALWAYS_REMOVED_REASON
                } else if (!hasLocked && keptCount <= 1 && permission === survivor) {
                    LAST_PERMISSION_REASON
                } else {
                    null
                }
            PermissionRow(
                permission = permission,
                // A locked row is shown as kept: the UI does not offer a choice on it, and the
                // switch reports the build's declaration rather than a choice. A row removed
                // regardless is shown as removed for the same reason: the build removes the
                // declaration, and the switch reports what the build does.
                kept = when {
                    alwaysRemoved -> false
                    lockedReason != null -> true
                    else -> isKept(permission)
                },
                lockedReason = lockedReason
            )
        }
    }
}
