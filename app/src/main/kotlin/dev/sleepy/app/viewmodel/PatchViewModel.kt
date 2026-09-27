package dev.sleepy.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sleepy.app.engine.PatchingPipeline
import dev.sleepy.app.model.AppSource
import dev.sleepy.app.model.PatchProgress
import dev.sleepy.app.model.StepResult
import dev.sleepy.app.util.SourcesConfig
import kotlinx.coroutines.flow.*

class PatchViewModel(application: Application) : AndroidViewModel(application) {

    private val pipeline = PatchingPipeline(application)

    val progress: StateFlow<PatchProgress> = pipeline.progress
    val stepLog: StateFlow<List<StepResult>> = pipeline.stepLog

    private val _selectedSource = MutableStateFlow<AppSource?>(null)
    val selectedSource: StateFlow<AppSource?> = _selectedSource.asStateFlow()

    private val _selectedPatchIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedPatchIds: StateFlow<Set<String>> = _selectedPatchIds.asStateFlow()

    val isPatching: StateFlow<Boolean> = progress.map {
        it !is PatchProgress.Idle && it !is PatchProgress.Done && it !is PatchProgress.Failed
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun selectSource(sourceId: String) {
        val sources = SourcesConfig.load(getApplication())
        val found = sources.find { it.id == sourceId }
        _selectedSource.value = found
        _selectedPatchIds.value = found?.patchIds?.toSet() ?: emptySet()
    }

    fun togglePatch(patchId: String) {
        val current = _selectedPatchIds.value.toMutableSet()
        if (current.contains(patchId)) {
            current.remove(patchId)
        } else {
            current.add(patchId)
        }
        _selectedPatchIds.value = current
    }

    fun startPatch() {
        val source = _selectedSource.value ?: return
        pipeline.start(
            sourceUrl = source.url,
            selectedPatchIds = _selectedPatchIds.value.toList(),
            scope = viewModelScope
        )
    }

    fun cancel() {
        pipeline.cancel()
    }
}
