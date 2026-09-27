package dev.sleepy.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sleepy.app.engine.PatchingPipeline
import dev.sleepy.app.model.AppSource
import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.model.PatchProgress
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.StepResult
import dev.sleepy.app.patches.PatchItemCatalog
import dev.sleepy.app.util.SourcesConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
