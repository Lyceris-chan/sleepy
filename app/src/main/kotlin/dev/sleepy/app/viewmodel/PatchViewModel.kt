package dev.sleepy.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sleepy.app.engine.PatchingPipeline
import dev.sleepy.app.model.AppSource
import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.model.PatchProgress
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.PermissionScan
import dev.sleepy.app.model.StepResult
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
    val permissions: StateFlow<PermissionScan> = _permissions.asStateFlow()

    /**
     * What each source's build declared, by source id, for as long as this ViewModel lives.
     *
     * Reading it costs a download of the build, so a source that has been read once is not read
     * again while the app is running. It is a cache of a fact about a published build rather than
     * of a choice, which is why [selectSource] clears the shown list but not this.
     */
    private val declaredBySource = mutableMapOf<String, List<String>>()

    val isPatching: StateFlow<Boolean> = progress.map {
        it !is PatchProgress.Idle && it !is PatchProgress.Done && it !is PatchProgress.Failed
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun selectSource(sourceId: String) {
        val sources = SourcesConfig.load(getApplication())
        val found = sources.find { it.id == sourceId }
        _selectedSource.value = found
        // A source declares patch set ids. fromSavedIds reads each as the set it names and expands
        // it to that set's items, which is what selecting the set has always meant; anything that
        // is already an item key is kept as it is.
        _selection.value = found
            ?.let { PatchSelection.fromSavedIds(it.patchIds, PatchItemCatalog) }
            ?: PatchSelection()
        _isCloneMode.value = false
        _customPackageName.value = found?.let { "${it.packageName}.sleepy" } ?: ""
        // A different source declares different permissions, so the list from the last one is
        // dropped rather than carried over; what it declared is remembered, not re-downloaded.
        _permissions.value = found?.id?.let { declaredBySource[it] }?.let { PermissionScan.Read(it) }
            ?: PermissionScan.NotRead
    }

    /**
     * Reads the selected build's own manifest and lists the permissions it declares.
     *
     * Nothing is known until this has run, and nothing can be removed until it has: the section
     * shows what the APK says rather than what a table here claims, and [startPatch] hands the
     * engine the same selection this seeds. Every declared permission is seeded as *kept*, so the
     * list starts with nothing switched off — the run only removes what the user switched off,
     * which is what makes reading the list a safe thing to do at all.
     */
    fun readPermissions() {
        val source = _selectedSource.value ?: return
        if (_permissions.value is PermissionScan.Reading) return

        declaredBySource[source.id]?.let {
            _permissions.value = PermissionScan.Read(it)
            return
        }

        viewModelScope.launch {
            _permissions.value = PermissionScan.Reading
            try {
                val declared = pipeline.readDeclaredPermissions(source.url)
                declaredBySource[source.id] = declared
                _permissions.value = PermissionScan.Read(declared)
                _selection.value = _selection.value.with(PermissionCatalog.itemsOf(declared))
            } catch (e: CancellationException) {
                _permissions.value = PermissionScan.NotRead
                throw e
            } catch (e: Exception) {
                _permissions.value = PermissionScan.Failed(
                    e.message ?: "The build could not be read, so its permissions are unknown."
                )
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
