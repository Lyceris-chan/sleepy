package dev.sleepy.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.sleepy.app.model.AppSource
import dev.sleepy.app.util.SourcesConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _sources = MutableStateFlow<List<AppSource>>(emptyList())
    val sources: StateFlow<List<AppSource>> = _sources.asStateFlow()

    init {
        loadSources()
    }

    fun loadSources() {
        val loaded = SourcesConfig.load(getApplication())
        _sources.value = loaded
    }
}
