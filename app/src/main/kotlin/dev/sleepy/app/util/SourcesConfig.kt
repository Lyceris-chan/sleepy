package dev.sleepy.app.util

import android.content.Context
import dev.sleepy.app.model.ApkType
import dev.sleepy.app.model.AppSource
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

            val splitUrlsArray = obj.optJSONArray("split_urls")
            val splitUrls = mutableListOf<String>()
            if (splitUrlsArray != null) {
                for (j in 0 until splitUrlsArray.length()) {
                    splitUrls.add(splitUrlsArray.getString(j))
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
                    sha256Expected =
                        if (obj.has("sha256_expected") && !obj.isNull("sha256_expected")) {
                            obj.getString("sha256_expected")
                        } else null,
                    description = obj.getString("description"),
                    changelogUrl = obj.optString("changelog_url", ""),
                    patchIds = patchIds,
                    splitUrls = splitUrls
                )
            )
        }
        return list
    }
}
