package dev.sleepy.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sleepy.app.engine.PackageNameRules
import dev.sleepy.app.engine.PatchingPipeline
import dev.sleepy.app.model.AppSource
import dev.sleepy.app.model.DeclarationSource
import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.model.PatchProgress
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.PermissionCheck
import dev.sleepy.app.model.PermissionScan
import dev.sleepy.app.model.StepResult
import dev.sleepy.app.patches.DeclaredPermissions
import dev.sleepy.app.patches.PatchItemCatalog
import dev.sleepy.app.patches.PermissionCatalog
import dev.sleepy.app.util.SourcesConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Holds what the selection screen is doing: which source is being patched, which of its patch
 * *items* are switched on, and how the result should be named.
 *
 * The selection is a [PatchSelection]—a set of item keys—rather than a set of patch set ids,
 * because a set is too coarse for some choices: "hide the gift button but keep quests" cannot be
 * expressed when both are behind one switch. The switch a section carries still exists; it selects
 * or clears every item of that section in one action ([setItemsEnabled]).
 *
 * A saved selection is read through [PatchSelection.fromSavedIds], which expands a patch set id
 * into every item of that set, so the ids a source declares keep the meaning they had before
 * items existed.
 */
class PatchViewModel(application: Application) : AndroidViewModel(application) {

    private val pipeline = PatchingPipeline(application)

    val progress: StateFlow<PatchProgress> = pipeline.progress
    val stepLog: StateFlow<List<StepResult>> = pipeline.stepLog

    private val _selectedSource = MutableStateFlow<AppSource?>(null)
    val selectedSource: StateFlow<AppSource?> = _selectedSource.asStateFlow()

    private val _selection = MutableStateFlow(PatchSelection())
    val selection: StateFlow<PatchSelection> = _selection.asStateFlow()

    private val _isCloneMode = MutableStateFlow(false)
    val isCloneMode: StateFlow<Boolean> = _isCloneMode.asStateFlow()

    private val _customPackageName = MutableStateFlow<String>("")
    val customPackageName: StateFlow<String> = _customPackageName.asStateFlow()

    private val _stopped = MutableStateFlow(false)
    /**
     * Whether the last run ended because the user stopped it.
     *
     * A stopped run leaves the pipeline idle, which is the same state a run that has not started
     * reports. The progress screen reads this flag to distinguish the two: without it, a stopped
     * run falls through to the "Getting ready" card and has no way out.
     */
    val stopped: StateFlow<Boolean> = _stopped.asStateFlow()

    private val _permissions = MutableStateFlow<PermissionScan>(PermissionScan.NotRead)
    /**
     * The permission section's state: which list it is showing, and what the cross-check found.
     */
    val permissions: StateFlow<PermissionScan> = _permissions.asStateFlow()

    /**
     * What each source's build declared, by source id, for as long as this ViewModel exists.
     *
     * This is the cross-check's result, not the list: the list is the one shipped for the
     * release, so a source that has been read once is not read again while the app is running. It
     * is a cache of a fact about a published build rather than of a choice, which is why
     * [selectSource] keeps it rather than clearing it with everything else.
     */
    private val declaredBySource = mutableMapOf<String, List<String>>()

    val isPatching: StateFlow<Boolean> = progress.map {
        it !is PatchProgress.Idle && it !is PatchProgress.Done && it !is PatchProgress.Failed
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Points the screen at one source: its patch sets, its permission rows, and the checks so far.
     *
     * The permission rows come from the list shipped for that release rather than from a read, so
     * they are on screen before anything is downloaded, and every one of them is seeded as kept—
     * a run removes only what the user switches off, which is what allows the list to be shown
     * before it has been checked.
     */
    fun selectSource(sourceId: String) {
        // The selection screen re-enters composition when the user comes back to it, and its
        // LaunchedEffect calls this again with the same id. The check keeps that second call from
        // rebuilding a selection the user has already made: the clone name, the clone switch and
        // every item switch stay as they were left.
        if (_selectedSource.value?.id == sourceId) return
        val sources = SourcesConfig.load(getApplication())
        val found = sources.find { it.id == sourceId }
        _selectedSource.value = found
        // A source declares patch set ids. fromSavedIds reads each as the set it names and expands
        // it to that set's items, which preserves the meaning of selecting the set; anything that
        // is already an item key is kept as it is.
        val patchSelection = found
            ?.let { PatchSelection.fromSavedIds(it.patchIds, PatchItemCatalog) }
            ?: PatchSelection()
        // The declarations shipped for this release fill the section in, so its rows are present
        // and the user can move its switches without downloading anything first. Seeding the
        // selection with every declaration as *kept* means a run removes only what is switched
        // off, and nothing is switched off yet, so the list can be shown before it has been
        // checked.
        val shipped = DeclaredPermissions.forPackage(found?.packageName)
        _selection.value = patchSelection.with(PermissionCatalog.itemsOf(shipped.orEmpty()))
        _isCloneMode.value = false
        _customPackageName.value = found?.let { "${it.packageName}.sleepy" } ?: ""
        // A different source declares different permissions, so nothing from the last one is
        // carried over except its own read: what a build declared when it was last checked is what
        // the check reports now, and re-showing it costs less than downloading the same archive
        // twice.
        _permissions.value = when {
            shipped == null -> PermissionScan.NotRead
            else -> PermissionScan.Read(
                declared = shipped,
                from = DeclarationSource.SHIPPED,
                check = found?.let { declaredBySource[it.id] }
                    ?.let { declared -> PermissionCheck.of(shipped, declared) }
                    ?: PermissionCheck.NotChecked
            )
        }
    }

    /**
     * Reads the selected build's own manifest, which answers two different questions.
     *
     * Where the release's declarations are shipped with the app, the list is already on screen and
     * this is a cross-check: it reports whether the build at the source's URL is still the build
     * the list describes. The list stands either way—a difference is reported next to it rather
     * than used to rewrite it, because a permission the build declares and the list does not name
     * has no description to show and no row to switch, and adopting a list that has not been read
     * without a report leaves the user with a change they were not told about. Where nothing is
     * shipped for the build, this is the only way to list anything at all, and what it reads
     * becomes the list.
     *
     * The read costs a download of the build, so its result is kept for as long as this ViewModel
     * exists, and the fallback list it seeds is seeded as *kept*—the run removes only what the
     * user switched off, so a list can be seeded before it has been read.
     */
    fun readPermissions() {
        val source = _selectedSource.value ?: return
        val shown = _permissions.value
        if (shown is PermissionScan.Reading) return
        if (shown is PermissionScan.Read && shown.check is PermissionCheck.Checking) return

        val shipped = (shown as? PermissionScan.Read)
            ?.takeIf { it.from == DeclarationSource.SHIPPED }
            ?.declared

        declaredBySource[source.id]?.let { declared ->
            _permissions.value = shipped?.let {
                PermissionScan.Read(it, DeclarationSource.SHIPPED, PermissionCheck.of(it, declared))
            } ?: PermissionScan.Read(declared, DeclarationSource.READ_FROM_BUILD)
            return
        }

        viewModelScope.launch {
            _permissions.value = shipped?.let {
                PermissionScan.Read(it, DeclarationSource.SHIPPED, PermissionCheck.Checking)
            } ?: PermissionScan.Reading
            try {
                val declared = pipeline.readDeclaredPermissions(source.url)
                declaredBySource[source.id] = declared
                _permissions.value = shipped?.let {
                    PermissionScan.Read(
                        it, DeclarationSource.SHIPPED, PermissionCheck.of(it, declared)
                    )
                } ?: PermissionScan.Read(declared, DeclarationSource.READ_FROM_BUILD)
                _selection.value = _selection.value.with(PermissionCatalog.itemsOf(declared))
            } catch (e: CancellationException) {
                _permissions.value = shipped?.let {
                    PermissionScan.Read(it, DeclarationSource.SHIPPED, PermissionCheck.NotChecked)
                } ?: PermissionScan.NotRead
                throw e
            } catch (e: Exception) {
                val reason = e.message
                    ?: "The build could not be read, so its permissions are unknown."
                // A failed read is not a reason to drop a shipped list: the list describes the
                // release, and the read was only ever a check on it, so the failure is reported
                // alongside the list.
                _permissions.value = shipped?.let {
                    PermissionScan.Read(
                        it, DeclarationSource.SHIPPED, PermissionCheck.Failed(reason)
                    )
                } ?: PermissionScan.Failed(reason)
            }
        }
    }

    /**
     * Sets whether the clone switch is on, and fills in the package name field with a default when
     * the switch is turned on and the field is empty.
     */
    fun setCloneMode(enabled: Boolean) {
        _isCloneMode.value = enabled
        if (enabled && _customPackageName.value.isBlank()) {
            _customPackageName.value = "${_selectedSource.value?.packageName ?: "app"}.sleepy"
        }
    }

    /** Sets the package name the clone build uses. */
    fun setCustomPackageName(name: String) {
        _customPackageName.value = name
    }

    /** Flips one item: the switch a row of an expanded set carries. */
    fun toggleItem(item: PatchItem) {
        _selection.value = _selection.value.toggle(item)
    }

    /**
     * Turns a list of items on or off at once—the switch a section carries.
     *
     * Takes the items rather than a section's name: which items are in a section is the catalog's
     * own answer, and the screen already holds the rows, so resolving a name here would be a second
     * way to ask a question the rows have already answered.
     */
    fun setItemsEnabled(items: List<PatchItem>, enabled: Boolean) {
        _selection.value = _selection.value.setEnabled(items, enabled)
    }

    /** Starts a run for the selected source and the current selection. */
    fun startPatch() {
        val source = _selectedSource.value ?: return
        // Clone mode is a request to rename, so a name that cannot be written stops the run here
        // rather than letting it finish without the rename—the screen reports the same rule on
        // the field and disables the start for it.
        val finalCustomPackage = if (_isCloneMode.value) _customPackageName.value.trim() else null
        if (finalCustomPackage != null &&
            PackageNameRules.validate(finalCustomPackage, source.packageName) != null
        ) {
            return
        }

        val selection = _selection.value
        // The engine receives the sets to run and the items that narrow them: a set with nothing
        // switched on is left out of the run entirely, and one that is in it applies only the items
        // the user selected. The order is the catalog's, so a run is reproducible.
        val selectedSetIds = PatchItemCatalog.all()
            .filter { selection.contains(it) }
            .map { it.setId }
            .distinct()

        _stopped.value = false
        pipeline.start(
            sourceUrl = source.url,
            originalPackageName = source.packageName,
            customPackageName = finalCustomPackage,
            selectedPatchIds = selectedSetIds,
            selection = selection,
            splits = source.splits,
            expectedSha256 = source.sha256Expected,
            scope = viewModelScope
        )
    }

    /** Stops the run in progress and marks the run as stopped. */
    fun cancel() {
        _stopped.value = true
        pipeline.cancel()
    }
}
