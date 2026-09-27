package dev.sleepy.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
 * The selection is a [PatchSelection] — a set of item keys — rather than a set of patch set ids,
 * because a set is not the size a choice is: "hide the gift button but keep quests" is not
 * expressible when both live behind one switch. The set's own switch still exists; it is the set of
 * its items, selected or cleared in one action ([setPatchSetEnabled]).
 *
 * A saved selection is read through [PatchSelection.fromSavedIds], which expands a patch set id
 * into every item of that set, so the ids a source declares keep meaning exactly what they meant
 * before items existed.
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

    private val _permissions = MutableStateFlow<PermissionScan>(PermissionScan.NotRead)
    /**
     * The permission section's state: which list it is showing, and what the cross-check found.
     */
    val permissions: StateFlow<PermissionScan> = _permissions.asStateFlow()

    /**
     * What each source's build declared, by source id, for as long as this ViewModel lives.
     *
     * This is the cross-check's answer, never the list: the list is the one shipped for the
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
     * they are on screen before anything is downloaded, and every one of them is seeded as kept —
     * a run removes only what the user switches off, which is what makes showing the list before it
     * has been checked a safe thing to do.
     */
    fun selectSource(sourceId: String) {
        val sources = SourcesConfig.load(getApplication())
        val found = sources.find { it.id == sourceId }
        _selectedSource.value = found
        // A source declares patch set ids. fromSavedIds reads each as the set it names and expands
        // it to that set's items, which is what selecting the set has always meant; anything that
        // is already an item key is kept as it is.
        val patchSelection = found
            ?.let { PatchSelection.fromSavedIds(it.patchIds, PatchItemCatalog) }
            ?: PatchSelection()
        // The declarations shipped for this release fill the section in, so its rows are there and
        // its switches can be moved without anything being downloaded first. Seeding the selection
        // with every one of them as *kept* is what makes the list safe to show before it has been
        // checked: a run removes only what is switched off, and nothing is switched off yet.
        val shipped = DeclaredPermissions.forPackage(found?.packageName)
        _selection.value = patchSelection.with(PermissionCatalog.itemsOf(shipped.orEmpty()))
        _isCloneMode.value = false
        _customPackageName.value = found?.let { "${it.packageName}.sleepy" } ?: ""
        // A different source declares different permissions, so nothing from the last one is
        // carried over except its own read: what a build said when it was last checked is what the
        // check says now, and re-showing it is cheaper than downloading the same archive twice.
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
     * this is a cross-check: it says whether the build at the source's URL is still the build the
     * list describes. The list stands either way — a difference is reported next to it rather than
     * used to rewrite it, because a permission the build declares and the list does not name has no
     * description to show and no row to switch, and silently adopting a list nobody has read is how
     * the user ends up with a change they were never told about. Where nothing is shipped for the
     * build, this is the only way to list anything at all, and what it reads becomes the list.
     *
     * The read costs a download of the build, so its answer is kept for as long as this ViewModel
     * lives and the fallback list it seeds is seeded as *kept* — the run only removes what the user
     * switched off, which is what makes reading a list a safe thing to do at all.
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
                // release, and the read was only ever a check on it, so the failure rides on it.
                _permissions.value = shipped?.let {
                    PermissionScan.Read(
                        it, DeclarationSource.SHIPPED, PermissionCheck.Failed(reason)
                    )
                } ?: PermissionScan.Failed(reason)
            }
        }
    }

    fun setCloneMode(enabled: Boolean) {
        _isCloneMode.value = enabled
        if (enabled && _customPackageName.value.isBlank()) {
            _customPackageName.value = "${_selectedSource.value?.packageName ?: "app"}.sleepy"
        }
    }

    fun setCustomPackageName(name: String) {
        _customPackageName.value = name
    }

    /** Flips one item: the switch a row of an expanded set carries. */
    fun toggleItem(item: PatchItem) {
        _selection.value = _selection.value.toggle(item)
    }

    /**
     * Turns every item of the set with this id on or off at once — the set's own switch.
     *
     * The items are resolved through [PatchItemCatalog], so a set id that names no set leaves the
     * selection alone rather than clearing it.
     */
    fun setPatchSetEnabled(setId: String, enabled: Boolean) {
        _selection.value = _selection.value.setEnabled(PatchItemCatalog.itemsOf(setId), enabled)
    }

    fun startPatch() {
        val source = _selectedSource.value ?: return
        val finalCustomPackage = if (_isCloneMode.value && _customPackageName.value.isNotBlank()) {
            _customPackageName.value.trim()
        } else null

        val selection = _selection.value
        // The engine is handed the sets to run and the items that narrow them: a set with nothing
        // switched on is left out of the run entirely, and one that is in it applies only the items
        // the user selected. The order is the catalog's, so a run is reproducible.
        val selectedSetIds = PatchItemCatalog.all()
            .filter { selection.contains(it) }
            .map { it.setId }
            .distinct()

        pipeline.start(
            sourceUrl = source.url,
            originalPackageName = source.packageName,
            customPackageName = finalCustomPackage,
            selectedPatchIds = selectedSetIds,
            selection = selection,
            splitUrls = source.splitUrls,
            expectedSha256 = source.sha256Expected,
            scope = viewModelScope
        )
    }

    fun cancel() {
        pipeline.cancel()
    }
}
