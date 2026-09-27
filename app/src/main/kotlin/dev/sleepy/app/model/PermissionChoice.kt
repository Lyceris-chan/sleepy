package dev.sleepy.app.model

/**
 * One permission sleepy can describe: what it lets the app do, and whether removing it is offered.
 *
 * @property name the permission as the manifest writes it, e.g. `android.permission.READ_CONTACTS`.
 *   This is also the entry's identity, because it is the only thing stable across releases: a
 *   permission's name is fixed by the platform, so a saved choice keeps meaning the same
 *   permission even though it is matched against a build's own declarations rather than a list.
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

/**
 * What is known about the selected build's permissions.
 *
 * The list is read from the APK being patched — from its own manifest — because that is the only
 * thing that cannot go stale: a table of names shipped here would be wrong within a release of
 * either app. Reading it costs a download of that build, so the state is explicit and the list
 * only appears once it has been read: until then there is nothing to show and nothing to remove.
 */
sealed interface PermissionScan {

    /** The build has not been read. No permission is known, and none can be chosen about. */
    data object NotRead : PermissionScan

    /** The build is being downloaded and its manifest read. */
    data object Reading : PermissionScan

    /** The build declares [declared], in the order its manifest declares them. */
    data class Read(val declared: List<String>) : PermissionScan

    /** The build could not be read, for [reason]. Nothing is known and nothing can be removed. */
    data class Failed(val reason: String) : PermissionScan
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
