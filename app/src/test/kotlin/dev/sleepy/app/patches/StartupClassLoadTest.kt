package dev.sleepy.app.patches

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.testing.ComparisonApks
import dev.sleepy.app.testing.dexEntries
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What the launch path stops doing, applied to the real build and read back from the DEX.
 *
 * `MainApplication.performInitialization` reaches `JankSessionRecorder` through three lines - the
 * instance read that loads the class, the `init` call and the experiment-flag call - and removes
 * all three, along with the launch metric named after the call. The metric is a string rather
 * than a reference, which is why it outlived the call it described: a launch report is read as a
 * list of what startup did, so a label for work that no longer happens belongs with the call.
 *
 * The same method hands the crash reporter's setup to a thread of its own and waits for it. That
 * setup reads the stored OTA bundle for a release name, passes it to a call that ignores it,
 * records another metric and installs the foreground-service logger. It becomes the one object
 * its caller expects back.
 *
 * `CrashReporting.<clinit>` is rewritten to build its list of ignorable network exceptions from a
 * zero-length array. The assertions read the initializer back out of the class, so the reflective
 * load is checked where it was and not confused with the same Kotlin reflection call in the
 * method that reads the list, which the edit does not touch.
 */
class StartupClassLoadTest {

    private companion object {
        /** The application class whose launch path loads the recorder. */
        const val APPLICATION = "com/discord/MainApplication.smali"
        const val APPLICATION_DESCRIPTOR = "Lcom/discord/MainApplication;"

        /** The crash reporter whose static initializer loads the exception classes. */
        const val REPORTER = "com/discord/crash_reporting/CrashReporting.smali"
        const val REPORTER_DESCRIPTOR = "Lcom/discord/crash_reporting/CrashReporting;"

        /** The three lines the launch path uses to reach the recorder, to be removed. */
        val RECORDER_LINES = listOf(
            "    sget-object v0, Lcom/discord/jank_stats/JankSessionRecorder;->" +
                "INSTANCE:Lcom/discord/jank_stats/JankSessionRecorder;\n",
            "    invoke-virtual {v0, p0}, Lcom/discord/jank_stats/JankSessionRecorder;->" +
                "init(Landroid/content/Context;)V\n",
            "    invoke-virtual {v0, v6}, Lcom/discord/jank_stats/JankSessionRecorder;->" +
                "setPerScreenExperiment(Z)V\n"
        )

        /** The launch timing's label, which names the call but is not a class reference. */
        const val TIMING_LABEL = "\"JankSessionRecorder.init()\""

        /** What the reporter's initializer must stop doing. */
        const val CLASS_LITERAL = "const-class v"
        const val REFLECTION_LOAD = "Lkotlin/jvm/internal/Reflection;->getOrCreateKotlinClass"

        /** The crash-report setup the launch path runs on a thread of its own. */
        const val SETUP_SIGNATURE =
            ".method private static final performInitialization${'$'}lambda${'$'}10(" +
                "Lcom/discord/MainApplication;)" +
                "Lcom/discord/crash_reporting/CrashReporting${'$'}CrashReportingReady;"

        /** What that setup does, none of which anything reads in this build. */
        val SETUP_WORK = listOf(
            "Lcom/discord/bundle_updater/BundleUpdater${'$'}Companion;->instance()",
            "Lcom/discord/crash_reporting/CrashReporting;->init(",
            "\"CrashReporting.init()\"",
            "Lcom/discord/lifecycle/ForegroundServiceStartGuard;->setLogger("
        )

        /** What it must keep doing. */
        const val INSTANCE_FIELD =
            "INSTANCE:Lcom/discord/crash_reporting/CrashReporting;"
        const val IGNORE_LIST_FIELD = "ignoreNetworkExceptionList:Ljava/util/List;"
    }

    @Test
    fun theLaunchPathStopsDoingThisWork() = runBlocking {
        val baseApk = ComparisonApks.discordBaseApk
        assumeTrue("the Discord fixtures are not on this machine (${baseApk.path})", baseApk.isFile)

        val dexEntries = dexEntries(baseApk)
        val classToDex = DexProcessor.buildClassToDexIndex(dexEntries)
        val applicationDex = requireNotNull(classToDex[APPLICATION_DESCRIPTOR]) {
            "$APPLICATION_DESCRIPTOR is not in this build"
        }
        val reporterDex = requireNotNull(classToDex[REPORTER_DESCRIPTOR]) {
            "$REPORTER_DESCRIPTOR is not in this build"
        }
        assertEquals(
            "both classes must be in one DEX entry for this test's single apply",
            applicationDex,
            reporterDex
        )
        val dex = dexEntries.getValue(applicationDex)

        // The edits are set items, not something this test spells out.
        val patches = DiscordNativePatches.STARTUP_CLASS_LOAD.smaliPatches
            .filter { it.smaliPath == APPLICATION || it.smaliPath == REPORTER }
        assertEquals(
            "discord_native_startup_class_load must hold three recorder edits, its launch " +
                "metric, the crash reporter's initializer rewrite and the crash-report setup",
            6,
            patches.size
        )
        assertEquals(
            "four of the edits are anchor edits",
            4,
            patches.count { it.anchor != null && it.replacement != null }
        )
        assertEquals(
            "two of them rewrite a method body",
            2,
            patches.count { it.methodSignature != null && it.replacementBody != null }
        )

        // Before: the stock launch path, so the after-state is a change and not the input read
        // twice. Each anchor must occur exactly once, or the engine's replace would edit more
        // than the line the patch describes.
        val stock = StartupSmali(dex, APPLICATION_DESCRIPTOR, REPORTER_DESCRIPTOR)
        val stockApplication = stock.text(APPLICATION)
        val stockReporter = stock.text(REPORTER)
        RECORDER_LINES.forEach { line ->
            assertEquals(
                "the anchor must occur exactly once in the stock build:\n$line",
                1,
                occurrences(stockApplication, line)
            )
        }
        assertTrue(
            "stock must reference the recorder, or there is nothing to remove",
            stockApplication.contains("jank_stats/JankSessionRecorder")
        )
        assertEquals(
            "the timing label must occur once in the stock build, as the anchor it is removed " +
                "by",
            1,
            occurrences(stockApplication, TIMING_LABEL)
        )

        val stockClinit = clinitOf(stockReporter)
        assertEquals(
            "stock's initializer must load seven exception classes",
            7,
            occurrences(stockClinit, CLASS_LITERAL)
        )
        assertEquals(
            "each of the seven is wrapped for Kotlin reflection",
            7,
            occurrences(stockClinit, REFLECTION_LOAD)
        )

        // The crash-report setup, which the launch path runs on a thread of its own and waits
        // for. Every part of it is dead in this build, and the edit has to remove all of it.
        val stockSetup = methodBody(stockApplication, SETUP_SIGNATURE)
        assertTrue("the setup must be in the stock application class", stockSetup.isNotEmpty())
        SETUP_WORK.forEach { line ->
            assertTrue(
                "stock's setup must still do this, or the edit is describing work that is not " +
                    "there:\n$line",
                stockSetup.contains(line)
            )
        }

        // Apply the set's own edits with the engine the pipeline uses.
        val (patched, results) = DexProcessor.patchDexSurgically(dexBytes = dex, patches = patches)
        assertEquals("one step per edit", patches.size, results.size)
        results.forEach {
            assertEquals("${it.label} did not land: ${it.detail}", StepStatus.OK, it.status)
        }

        // After: nothing in the application class references the recorder, the timing label is
        // still there, and the initializer no longer resolves a single class.
        val after = StartupSmali(patched, APPLICATION_DESCRIPTOR, REPORTER_DESCRIPTOR)
        val afterApplication = after.text(APPLICATION)
        val afterReporter = after.text(REPORTER)
        assertFalse(
            "no reference to the recorder may survive in the application class",
            afterApplication.contains("jank_stats/JankSessionRecorder")
        )
        RECORDER_LINES.forEach { line ->
            assertFalse(
                "the removed line must not survive:\n$line",
                afterApplication.contains(line.trim())
            )
        }
        assertFalse(
            "the timing label must not survive: it named a call that is no longer made, so a " +
                "launch report would list work this build does not do",
            afterApplication.contains(TIMING_LABEL)
        )
        assertFalse(
            "and the recorder must not be named anywhere in the class, label included",
            afterApplication.contains("JankSessionRecorder")
        )

        val afterClinit = clinitOf(afterReporter)
        assertFalse(
            "the initializer must not resolve an exception class",
            afterClinit.contains(CLASS_LITERAL)
        )
        val afterSetup = methodBody(afterApplication, SETUP_SIGNATURE)
        assertTrue("the setup must survive the edit as a method", afterSetup.isNotEmpty())
        SETUP_WORK.forEach { line ->
            assertFalse(
                "nothing the setup used to do may survive:\n$line",
                afterSetup.contains(line)
            )
        }
        assertTrue(
            "and it must still hand its caller the object the caller expects",
            afterSetup.contains(
                "Lcom/discord/crash_reporting/CrashReporting${'$'}CrashReportingReady;"
            ) && afterSetup.contains("return-object v0")
        )

        assertFalse(
            "the initializer must not touch the Kotlin reflection stack",
            afterClinit.contains(REFLECTION_LOAD)
        )
        assertTrue(
            "the initializer must still create INSTANCE",
            afterClinit.contains(INSTANCE_FIELD)
        )
        assertTrue(
            "the field must still exist on the class",
            afterReporter.contains(IGNORE_LIST_FIELD)
        )
        assertTrue(
            "the initializer must still assign the field, so its one reader does not see null",
            afterClinit.contains("sput-object v0, Lcom/discord/crash_reporting/CrashReporting;->$IGNORE_LIST_FIELD")
        )
        assertTrue("the initializer must return", afterClinit.contains("return-void"))
    }

    /** The text of the class's static initializer, up to its closing `.end method`. */
    private fun clinitOf(classText: String): String =
        methodBody(classText, ".method static constructor <clinit>()V")

    /** The text of the one method [signature] names, up to its closing `.end method`. */
    private fun methodBody(classText: String, signature: String): String =
        classText.substringAfter(signature).substringBefore(".end method")

    /** How many times [needle] occurs in [haystack] - the engine's replace edits every one. */
    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1
}

/**
 * One or more classes of a DEX as the engine's own baksmali writes it.
 *
 * The anchors are written in this spelling - baksmali 3.0.10 labels branches by address rather
 * than sequentially - so the fixture has to be read back through the same tool the engine
 * disassembles with. A class that does not resolve is an empty string, which fails the
 * assertions above rather than throwing here.
 */
private class StartupSmali(dexBytes: ByteArray, vararg descriptors: String) {

    private companion object {
        /** The API level the pipeline disassembles its DEX files at. */
        const val API_LEVEL = 28
    }

    private val directory: File = File.createTempFile("startupSmali", "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

    init {
        val dexFile = File.createTempFile("startupSmaliIn", ".dex").apply {
            writeBytes(dexBytes)
            deleteOnExit()
        }
        val options = BaksmaliOptions().apply { apiLevel = API_LEVEL }
        val disassembled = Baksmali.disassembleDexFile(
            DexFileFactory.loadDexFile(dexFile, Opcodes.forApi(API_LEVEL)),
            directory,
            1,
            options,
            descriptors.toList()
        )
        check(disassembled) { "baksmali refused the fixture" }
    }

    /** The smali of the class at [path], or an empty string when it was not disassembled. */
    fun text(path: String): String =
        File(directory, path).takeIf { it.isFile }?.readText().orEmpty()
}
