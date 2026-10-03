package dev.sleepy.app.model

/** The packaging of an APK offered as a source. */
enum class ApkType {
    /** A single APK that holds every ABI and every resource. */
    UNIVERSAL,

    /** The base APK of an app bundle: native libraries and some resources arrive in splits. */
    SPLIT_BASE
}

/**
 * One downloadable build of an app, as the app's asset configuration declares it.
 *
 * @property id The identifier the configuration uses for this source.
 * @property displayName The name the UI shows for this source.
 * @property packageName The application ID of the build.
 * @property versionName The version name recorded for the build.
 * @property versionCode The version code recorded for the build.
 * @property url The URL the base APK is downloaded from.
 * @property apkType The packaging of the download.
 * @property sha256Expected The SHA-256 the configuration publishes for the download, or null when
 *   it publishes no hash.
 * @property description A description of the source.
 * @property changelogUrl The URL of the release notes, or an empty string when there are none.
 * @property patchIds The patch set ids selected for this source by default.
 * @property splitUrls The URLs of the configuration splits, empty for a universal APK.
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
    val splitUrls: List<String> = emptyList()
)
