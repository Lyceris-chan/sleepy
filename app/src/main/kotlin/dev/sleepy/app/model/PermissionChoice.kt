package dev.sleepy.app.model

/**
 * One permission sleepy can describe: what it lets the app do, and whether removing it is offered.
 *
 * @property name the permission as the manifest writes it, e.g. `android.permission.READ_CONTACTS`.
 *   This is also the entry's identity, because it is the only thing stable across releases: a
 *   permission's name is fixed by the platform, so a saved choice keeps meaning the same permission
 *   however the list it was made against was produced.
 * @property label the short name the UI shows.
 * @property description what the permission allows, and what stops working once the declaration is
 *   gone. It always states the consequence of removal in full, because that consequence is not
 *   something a user can infer — see [PermissionRow].
 * @property lockReason why removing this declaration is not offered at all, or null when the user
 *   may remove it. Non-null only where the app's *core* function depends on the declaration being
 *   there, not merely a feature: a feature that guards itself degrades, while one the app assumes
 *   throws.
 */
data class Permission(
    val name: String,
    val label: String,
    val description: String,
    val lockReason: String? = null
) {
    /** True when this permission is locked: the row is disabled and says why. */
    val essential: Boolean get() = lockReason != null

    /** The identity of this permission within the permission set, and the second half of its key. */
    val identity: String get() = name
}

/**
 * One permission as the list renders it: the entry, whether it is kept, and why the switch is
 * fixed when it is.
 *
 * @property kept whether the app keeps this permission. The switch reads as "kept" rather than
 *   "removed" because that is the state the build starts in: an app declares the permissions it
 *   wants, so every row starts switched on and switching one off is the change being made.
 * @property lockedReason why this row cannot be switched off, or null when it can be. Two claims
 *   share this field and both are the model's own sentence: the permission is one the app cannot
 *   work without ([Permission.lockReason]), or it is the only one left and a build with no
 *   permissions at all is not a state this offers ([PermissionCoverage.LAST_PERMISSION_REASON]).
 */
data class PermissionRow(
    val permission: Permission,
    val kept: Boolean,
    val lockedReason: String? = null
) {
    /** True when the user may move this row's switch. */
    val switchable: Boolean get() = lockedReason == null
}

/** Where the declarations a build is offered came from. */
enum class DeclarationSource {

    /**
     * The list sleepy ships for this exact release of the app, which is there before anything runs.
     */
    SHIPPED,

    /** The build's own manifest, read because sleepy ships no list for this build. */
    READ_FROM_BUILD
}

/**
 * One difference between the declarations sleepy ships and the ones a build makes.
 *
 * The two directions are not the same claim and are not treated as one. What a build declares and
 * sleepy does not list is a permission with no row: the user cannot see it, cannot read what it
 * does, and cannot switch it off, which is the case that has to be said out loud. What sleepy lists
 * and the build does not declare is a row for a declaration that is not there — harmless to leave,
 * wrong to act on.
 */
sealed interface DeclarationMismatch {

    /** The permission this difference is about. */
    val name: String

    /** The build declares this and sleepy does not list it, so the list shows no row for it. */
    data class Unlisted(override val name: String) : DeclarationMismatch

    /** sleepy lists this and the build does not declare it, so there is nothing here to remove. */
    data class Absent(override val name: String) : DeclarationMismatch
}

/**
 * What comparing the shipped list against the build's own manifest found.
 *
 * The build is read for this and for nothing else: the list the rows and the removals come from is
 * [DeclarationSource.SHIPPED], so a build that has changed under a source is described rather than
 * quietly re-specified. A comparison that cannot be made is its own state rather than an agreement,
 * because "the manifest could not be read" and "the manifest says the same thing" are answers a
 * reader would act on differently.
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
         * A permission declared twice counts once, because it is one declaration as far as a reader
         * is concerned; the order of the differences is the order each list writes them in, so the
         * report reads the way the manifest and the list are written.
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
 * The list has exactly one source and it is not the network: what sleepy ships for this release,
 * which is what makes the section reachable — a list behind a 96 MB download is a list most people
 * never see. A package nothing is shipped for is the exception, and it is read from the build
 * itself, because there is nothing else to offer and a build that declares permissions the app
 * cannot name is a build the user cannot choose about at all.
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
 * step. Unlocking happens by itself — the last permission starts refusing once it is the last, and
 * stops refusing the moment another one is switched back on.
 *
 * The two reasons a row can be locked are different claims and stay distinguishable: an
 * [Permission.lockReason] is about the app, and [LAST_PERMISSION_REASON] is about the state of the
 * list.
 */
object PermissionCoverage {

    /**
     * Why the last remaining permission cannot be removed.
     *
     * Written in the vocabulary the blocklist's gates established — see
     * [BlocklistCoverage.REQUIRED_REASON_PREFIX] — because it is the same claim: this row has no
     * choice in it.
     */
    const val LAST_PERMISSION_REASON: String =
        "Required: this is the last permission left in the list, and a build that declares none " +
            "is not something sleepy will produce. An app with no permission declarations cannot " +
            "ask for any of them later, so removing this one would leave a build with no way back."

    /**
     * [permissions] as rows, each locked when it cannot be removed.
     *
     * A permission is locked when the table says the app cannot work without it, or when it is the
     * last one that would survive. The second rule is computed from the same [isKept] the list
     * renders from, so it follows the user's choices rather than being a fixed property of a
     * permission: the last one standing starts refusing, and stops the moment another is switched
     * back on.
     *
     * The rule is stated over the *survivor* rather than over the kept count alone, so that "some
     * declaration always survives" holds for every input and not just the ones a user can reach by
     * tapping. A selection that names none of these permissions at all — a stale selection, or one
     * made against a different build — would otherwise leave every row switched off and every
     * declaration removable.
     *
     * It only has anything to say when the table locks nothing by itself: a locked permission is
     * kept whatever the selection says, so a build that has one always declares at least one
     * permission, and an app that declares `INTERNET` keeps declaring it however many of the rest
     * are removed.
     */
    fun rows(permissions: List<Permission>, isKept: (Permission) -> Boolean): List<PermissionRow> {
        val hasLocked = permissions.any { it.lockReason != null }
        val keptCount = permissions.count { it.lockReason == null && isKept(it) }
        // Identity, not equality: two entries that happen to read the same are still two rows, and
        // only the one standing last is the survivor.
        val survivor = permissions.lastOrNull { it.lockReason == null && isKept(it) }
            ?: permissions.firstOrNull { it.lockReason == null }

        return permissions.map { permission ->
            val lockedReason = permission.lockReason
                ?: if (!hasLocked && keptCount <= 1 && permission === survivor) {
                    LAST_PERMISSION_REASON
                } else {
                    null
                }
            PermissionRow(
                permission = permission,
                // A locked row reads as kept: it is not a permission the user is being asked
                // about, and showing it as switched off would say the opposite of what it is.
                kept = lockedReason != null || isKept(permission),
                lockedReason = lockedReason
            )
        }
    }
}
