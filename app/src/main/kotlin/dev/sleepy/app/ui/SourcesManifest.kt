package dev.sleepy.app.ui

import android.content.Context
import org.json.JSONObject

/**
 * Provenance declared by the build manifest.
 *
 * @property manifestUrl Where `sources.json` is published, so the copy bundled with this build
 *   can be compared against the copy in the repository. `null` when the manifest predates the
 *   field.
 * @property projectUrl The repository this tool is built from. `null` when the manifest predates
 *   the field.
 */
data class ManifestProvenance(
    val manifestUrl: String?,
    val projectUrl: String?
)

/**
 * Reads the top-level provenance fields of the bundled `sources.json`.
 *
 * These fields are in the manifest rather than in the UI, so the links shown to a reader are the
 * same data that drove the build and can be changed without shipping a new screen. Only the
 * header is read here; the per-source entries are parsed by `dev.sleepy.app.util.SourcesConfig`.
 */
object SourcesManifest {

    private const val ASSET_NAME = "sources.json"
    private const val KEY_MANIFEST_URL = "manifest_url"
    private const val KEY_PROJECT_URL = "project_url"

    /**
     * Loads [ManifestProvenance] from the bundled manifest.
     *
     * @param context The context whose assets hold `sources.json`.
     * @return The provenance fields in the manifest, or a [ManifestProvenance] with null fields
     *   when the manifest cannot be read.
     */
    fun load(context: Context): ManifestProvenance = try {
        val json = context.assets.open(ASSET_NAME).use { stream ->
            stream.reader(Charsets.UTF_8).readText()
        }
        parse(json)
    } catch (e: Exception) {
        ManifestProvenance(manifestUrl = null, projectUrl = null)
    }

    /**
     * Parses [ManifestProvenance] from a manifest string.
     *
     * @param json The contents of a `sources.json` file.
     * @return The provenance fields present in the string.
     */
    fun parse(json: String): ManifestProvenance {
        val root = JSONObject(json)
        return ManifestProvenance(
            manifestUrl = root.optString(KEY_MANIFEST_URL).takeIf { it.isNotBlank() },
            projectUrl = root.optString(KEY_PROJECT_URL).takeIf { it.isNotBlank() }
        )
    }
}
