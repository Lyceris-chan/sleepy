package dev.sleepy.app.util

import android.content.Context
import dev.sleepy.app.model.ApkType
import dev.sleepy.app.model.AppSource
import dev.sleepy.app.model.SplitSource
import java.io.InputStreamReader
import org.json.JSONObject

/** Reads the list of downloadable sources from the bundled `sources.json` asset. */
object SourcesConfig {

    /**
     * Returns the sources declared in the bundled `sources.json` asset, or an empty list when the
     * asset cannot be read or parsed.
     */
    fun load(context: Context): List<AppSource> {
        return try {
            val jsonString = context.assets.open("sources.json").use { stream ->
                InputStreamReader(stream, Charsets.UTF_8).readText()
            }
            parseJson(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Parses [jsonString] as a sources document and returns the sources it declares.
     */
    fun parseJson(jsonString: String): List<AppSource> {
        val root = JSONObject(jsonString)
        val array = root.getJSONArray("sources")
        val list = mutableListOf<AppSource>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val patchIdsArray = obj.getJSONArray("patch_ids")
            val patchIds = mutableListOf<String>()
            for (j in 0 until patchIdsArray.length()) {
                patchIds.add(patchIdsArray.getString(j))
            }

            val apkTypeStr = obj.optString("apk_type", "universal")
            val apkType = if (apkTypeStr.equals("split_base", ignoreCase = true)) {
                ApkType.SPLIT_BASE
            } else {
                ApkType.UNIVERSAL
            }

            val splitsArray = obj.optJSONArray("splits")
            val splits = mutableListOf<SplitSource>()
            if (splitsArray != null) {
                for (j in 0 until splitsArray.length()) {
                    when (val entry = splitsArray.opt(j)) {
                        is JSONObject -> splits.add(
                            SplitSource(
                                url = entry.getString("url"),
                                sha256Expected = entry.optionalString("sha256_expected"),
                                sizeBytes = entry.optionalLong("size_bytes")
                            )
                        )
                        // A split listed as a plain URL is one the configuration publishes no
                        // integrity data for, so it parses as an unchecked split rather than
                        // hiding every source in the file behind a parse failure.
                        is String -> splits.add(
                            SplitSource(url = entry, sha256Expected = null, sizeBytes = null)
                        )
                    }
                }
            }

            list.add(
                AppSource(
                    id = obj.getString("id"),
                    displayName = obj.getString("display_name"),
                    packageName = obj.getString("package_name"),
                    versionName = obj.getString("version_name"),
                    versionCode = obj.getInt("version_code"),
                    url = obj.getString("url"),
                    apkType = apkType,
                    sha256Expected = obj.optionalString("sha256_expected"),
                    description = obj.getString("description"),
                    changelogUrl = obj.optString("changelog_url", ""),
                    patchIds = patchIds,
                    splits = splits
                )
            )
        }
        return list
    }

    /** The string at [key], or null when the field is absent or holds JSON null. */
    private fun JSONObject.optionalString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    /** The number at [key] as a Long, or null when the field is absent or holds JSON null. */
    private fun JSONObject.optionalLong(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null
}
