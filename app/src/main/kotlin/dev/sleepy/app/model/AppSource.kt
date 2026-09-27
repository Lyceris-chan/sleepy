package dev.sleepy.app.model

enum class ApkType {
    UNIVERSAL,
    SPLIT_BASE
}

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
    val patchIds: List<String>
)
