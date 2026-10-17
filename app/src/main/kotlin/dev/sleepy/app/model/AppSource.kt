package dev.sleepy.app.model

/** The packaging of an APK offered as a source. */
enum class ApkType {
    /** A single APK that holds every ABI and every resource. */
    UNIVERSAL,

    /** The base APK of an app bundle: native libraries and some resources arrive in splits. */
    SPLIT_BASE
}

/**
 * One configuration split offered for a source, with the integrity data published for it.
 *
 * @property url The URL the split is downloaded from.
 * @property sha256Expected The SHA-256 the configuration publishes for the split, or null when
 *   it publishes no hash.
 * @property sizeBytes The size the configuration publishes for the split in bytes, or null when
 *   it publishes no size.
 */
data class SplitSource(
    val url: String,
    val sha256Expected: String?,
    val sizeBytes: Long?
)

/**
 * One downloadable build of an app, as the app's asset configuration declares it.
 *
 * @property id Identifier the configuration uses for this source.
 * @property displayName Name the UI shows.
 * @property packageName Application ID of the build.
 * @property versionName Version name recorded for the build.
 * @property versionCode Version code recorded for the build.
 * @property url URL the base APK is downloaded from.
 * @property apkType Packaging of the download.
 * @property sha256Expected Published SHA-256, or null when none is published.
 * @property description Description of the source.
 * @property changelogUrl Release-notes URL, or an empty string.
 * @property patchIds Patch set ids selected by default.
 * @property splits Configuration splits, empty for a universal APK.
 */
data class AppSource(
    val id: String,
    val displayName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val url: String,
    val apkType: ApkType,
    val sha256Expected: String?,
    val description: String,
    val changelogUrl: String,
    val patchIds: List<String>,
    val splits: List<SplitSource> = emptyList()
)
