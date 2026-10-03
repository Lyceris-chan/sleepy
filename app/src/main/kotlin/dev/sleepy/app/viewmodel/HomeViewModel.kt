package dev.sleepy.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.sleepy.app.model.AppSource
import dev.sleepy.app.util.SourcesConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the sources the home screen lists, read from the bundled configuration. */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _sources = MutableStateFlow<List<AppSource>>(emptyList())

    /** The sources the app can patch, in the order the configuration declares them. */
    val sources: StateFlow<List<AppSource>> = _sources.asStateFlow()

    init {
        loadSources()
    }

    /** Loads the sources from the bundled configuration and publishes them to [sources]. */
    fun loadSources() {
        val loaded = SourcesConfig.load(getApplication())
        _sources.value = loaded
    }
}
