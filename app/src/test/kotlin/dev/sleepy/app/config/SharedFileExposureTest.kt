package dev.sleepy.app.config

import dev.sleepy.app.testing.source
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app shares the finished APK and nothing else.
 *
 * The `FileProvider` declares one root per directory it may grant a URI from; a root over the
 * files directory would expose the signing material stored there. The tests pin that the cache
 * directory is the declared root and that the pipeline writes the shared APK into it.
 */
class SharedFileExposureTest {

    @Test
    fun theFilesDirectoryIsNotASharedRoot() {
        val paths = source("app/src/main/res/xml/file_paths.xml")

        assertFalse(
            "nothing in the files directory is shared, so that root must not be exposed",
            paths.contains("<files-path")
        )
        assertTrue(
            "the finished APK is handed out of the cache directory, so that root has to stay " +
                "declared",
            paths.contains("<cache-path")
        )
    }

    /**
     * The shared root has to be the directory that holds the shared file, and the two halves are
     * declared in different files: a pipeline that writes the APK into the files directory makes
     * the share fail, and a paths file narrowed past the cache directory does the same.
     */
    @Test
    fun theSharedRootIsWhereThePipelineWritesTheApk() {
        val pipeline = source("app/src/main/kotlin/dev/sleepy/app/engine/PatchingPipeline.kt")

        assertTrue(
            "the APK handed to the share sheet is the one written beside the cache directory's " +
                "other files",
            pipeline.contains("File(context.cacheDir, \"sleepy_patched_")
        )
    }
}
