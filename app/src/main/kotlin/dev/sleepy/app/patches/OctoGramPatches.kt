package dev.sleepy.app.patches

import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.SmaliPatch

/**
 * Bytecode patches for OctoGram (Telegram client fork).
 *
 * Transcribed from the local patch scripts:
 * - work361/patch_dex_361.py (OctoGram 3.6.1 Beta 2 / build 38275)
 * - work361/patch_premium_361.py (Paywall upsell removal)
 * - work361/patch_octolog_361.py (OctoGram logger emitters)
 * - work361/patch_logging_361.py (Diagnostic uploaders, and the global logging flag)
 *
 * patch_dex.py (OctoGram 3.6.0) is deliberately not transcribed: no source here offers a 3.6.0
 * build, so its entries could never run, and a patch that cannot run is a row in the selection
 * list that lies about what will happen. The edits it describes are noted where they would have
 * gone, so adding a 3.6.0 source is a matter of porting them rather than rediscovering them.
 *
 * Two later scripts in that directory are not transcribed either: patch_crashlog_361.py, which
 * disables OctoGram's own crash reporter, and patch_premium_settings.py, which drops the premium
 * rows out of Settings. What each leaves running is stated in the README's OctoGram limitations.
 *
 * Nothing here is invented: every entry resolves to a real edit in one of those scripts.
 * All DEX container assignments are resolved dynamically at runtime by inspecting the
 * target APK's DEX headers, so no patch hardcodes a container. Version tags ensure
 * version-specific obfuscated class names are targeted accurately without conflicting
 * with unrelated classes.
 *
 * Two deliberate divergences from the reference scripts, both behaviour-preserving:
 * stub bodies here use `.locals 0` where the scripts reuse the method's original
 * `.registers N`. With a body that only returns, the two assemble to equivalent code.
 * The reference's own line-anchored edits are expressed as unique-string anchors.
 *
 * An entry's `title` and `explanation` are read by the user: while it runs, and in the selection
 * screen's technical panel, which lists both for every entry under the class and method it touches.
 * They are written for that reader — what the entry suppresses, fetches or shows, in terms of what
 * would otherwise happen — while the class, method and version stay in the entry's own fields.
 *
 * The levels the [OCTO_LOGGER] emitters are described by were read from the 3.6.1 build's own
 * method bodies: R8's single-letter names carry no level of their own, and the bodies differ only
 * in which emitter they funnel into and at which level.
 */
object OctoGramPatches {

    /**
     * The build the manifest's OctoGram source carries — see `sources.json`.
     *
     * Every entry here is tagged for this one build or left untagged, because an entry tagged for
     * another build can never run on any source this app offers. The reference scripts' 3.6.0
     * entries are therefore not ported at all.
     *
     * The entries spell their tags out rather than reading this constant, and this is the value the
     * content test holds every tag to: a tag mistyped in one entry then fails that test, instead of
     * silently following whatever this constant was last changed to.
     */
    const val REGISTERED_BUILD = "3.6.1"

    val SPONSORED_MSGS = PatchSet(
        id = "octogram_sponsored_msgs",
        label = "Block sponsored posts in channels",
        description = "Stops the promoted posts OctoGram shows in public channels: the ad row is " +
            "never built into the chat list, and the request that would fill it is never made. Both " +
            "entry points are the ones on the build this app offers.",
        smaliPatches = listOf(
            // 3.6.1 (build 38275): ChatActivity.If -> return-void
            SmaliPatch(
                title = "Sponsored rows in the channel chat list (3.6.1)",
                explanation = "Stubs ChatActivity.If() so the sponsored rows and the bot banner are " +
                    "never inserted into a channel's chat list: the list is built without them, " +
                    "rather than with them hidden.",
                versionTag = "3.6.1",
                smaliPath = "org/telegram/ui/e6.smali",
                methodSignature = ".method public final If()V",
                replacementBody = """.method public final If()V
    .locals 0

    return-void
.end method"""
            ),
            // 3.6.1 (build 38275): MessagesController.J1 -> return null
            SmaliPatch(
                title = "The sponsored-message request (3.6.1)",
                explanation = "Returns null from MessagesController.J1(), so no cached sponsored-message holder is " +
                    "handed out and TL_messages_getSponsoredMessages is never fetched. Every caller tests the " +
                    "result for null straight away, which is what makes a null return safe here.",
                versionTag = "3.6.1",
                smaliPath = "a28.smali",
                methodSignature = ".method public final J1(J)Lw18;",
                replacementBody = """.method public final J1(J)Lw18;
    .locals 1

    const/4 v0, 0x0

    return-object v0
.end method"""
            ),
            // The reference's 3.6.0 pair for this set — org/telegram/ui/o.ss(Z)V and
            // org/telegram/messenger/m0.sc(J) — is not ported, for the reason in the header.
        )
    )

    val PHOTO_VIEWER_ADS = PatchSet(
        id = "octogram_photo_viewer_ads",
        label = "Remove the ads between photos",
        description = "No promotion appears full-screen while swiping through a channel's photos or media: the " +
            "viewer's ad callback is stubbed, so the interstitial is never presented and the request behind " +
            "it is never made.",
        smaliPatches = listOf(
            // 3.6.1: gpd.b(Lwz0;)V -> return-void
            SmaliPatch(
                title = "Full-screen photo viewer ads (3.6.1)",
                explanation = "No-ops PhotoViewer's ad callback, so no full-screen promotion is presented between " +
                    "media items and the sponsored-message request behind it is never issued.",
                versionTag = "3.6.1",
                smaliPath = "gpd.smali",
                methodSignature = ".method public final b(Lwz0;)V",
                replacementBody = """.method public final b(Lwz0;)V
    .locals 0

    return-void
.end method"""
            ),
            // The reference's 3.6.0 variant — y5l.Q() — is not ported, for the reason in the header.
        )
    )

    val SEARCH_ADS = PatchSet(
        id = "octogram_search_ads",
        label = "Filter sponsored channels out of search",
        description = "Search stops putting promoted channels and bots above real results: the response that " +
            "carries them is discarded and the pending-request id reset, so the search controller stays " +
            "consistent while the sponsored entries go nowhere.",
        smaliPatches = listOf(
            // 3.6.1: s04.k0 -> reset sponsoredReqId = 0 and return
            SmaliPatch(
                title = "Sponsored results in search (3.6.1)",
                explanation = "Resets sponsoredReqId to 0 and returns, discarding the TL_contacts_sponsoredPeers " +
                    "response, so search results contain only genuine matches.",
                versionTag = "3.6.1",
                smaliPath = "s04.smali",
                methodSignature = ".method public final synthetic k0(Lorg/telegram/tgnet/TLObject;)V",
                replacementBody = """.method public final synthetic k0(Lorg/telegram/tgnet/TLObject;)V
    .locals 1

    const/4 v0, 0x0

    iput v0, p0, Ls04;->sponsoredReqId:I

    return-void
.end method"""
            ),
            // The reference's 3.6.0 variant — be6.m1(TLObject) — is not ported, for the reason in
            // the header.
        )
    )

    val OTA_UPDATER = PatchSet(
        id = "octogram_ota_updater",
        label = "Stop the built-in update check",
        description = "OctoGram asks GitHub whether a newer release exists and puts a prompt up when it thinks " +
            "one does. This cuts the request off and signals the \"no update\" path instead, so nothing is " +
            "fetched and the update screen is not left waiting for a reply that will never arrive.",
        smaliPatches = listOf(
            // 3.6.1: j6d.smali case :pswitch_160 in packed-switch
            SmaliPatch(
                title = "The GitHub update request (3.6.1)",
                explanation = "Slices dispatcher case :pswitch_160 so the no-update callback is signalled directly and no request to GitHub is ever made. " +
                    "Calling the callback keeps the update UI from waiting forever on a reply that will not arrive.",
                versionTag = "3.6.1",
                smaliPath = "j6d.smali",
                switchCaseLabel = ":pswitch_160",
                switchCaseBody = """    :pswitch_160
    check-cast v5, Luid;

    check-cast v4, Lpid;

    iput-object v1, v5, Luid;->b:Lorg/json/JSONObject;

    invoke-virtual {v4}, Lpid;->a()V

    return-void
"""
            ),
            // The reference's 3.6.0 variant — hxk.o0(Lhxk$i) — is not ported, for the reason in the
            // header.
        )
    )

    val PREMIUM_UPSELL = PatchSet(
        id = "octogram_premium_upsell",
        label = "Reduce premium paywall entry points (partial)",
        description = "Drops navigation to the Premium upsell screen where the app builds its window stack, so the " +
            "paywall is unreachable from most of the app. This is a partial removal, as the reference documents " +
            "it: 53 of 61 presentation paths are blocked and the remaining 8 still open the screen as a sheet, " +
            "the profile's premium rows, PremiumFeatureCell and LimitPreviewView still render, and the buttons " +
            "that used to open the paywall are inert rather than repurposed. The screen still exists, so a " +
            "premium subscriber also loses the place where they manage their subscription.",
        smaliPatches = listOf(
            // ActionBarLayout.b(Ln16;)Z paywall drop guard
            SmaliPatch(
                title = "Dropping the paywall in the navigation router",
                explanation = "Injects an instance check for PremiumPreviewFragment into ActionBarLayout's router, " +
                    "so an attempt to present the paywall screen is dropped rather than shown.",
                versionTag = "3.6.1",
                smaliPath = "org/telegram/ui/ActionBar/ActionBarLayout.smali",
                anchor = "    iget-object v4, v0, Ln16;->a:Lorg/telegram/ui/ActionBar/p;\n",
                replacement = """    iget-object v4, v0, Ln16;->a:Lorg/telegram/ui/ActionBar/p;

    # --- OctoGram premium-upsell removal -------------------------------
    instance-of v2, v4, Lorg/telegram/ui/PremiumPreviewFragment;

    if-eqz v2, :cond_premium_upsell_skip

    const/4 v2, 0x0

    return v2

    :cond_premium_upsell_skip
    # -------------------------------------------------------------------
"""
            ),
            // t6.b(Ln16;)Z paywall drop guard in main window stack override
            SmaliPatch(
                title = "Dropping the paywall in the main window override",
                explanation = "Injects the same PremiumPreviewFragment check into the t6 override of the " +
                    "window-stack router, so the paywall is dropped on that path too.",
                versionTag = "3.6.1",
                smaliPath = "t6.smali",
                anchor = "    iget-object v0, p1, Ln16;->a:Lorg/telegram/ui/ActionBar/p;\n",
                replacement = """    iget-object v0, p1, Ln16;->a:Lorg/telegram/ui/ActionBar/p;

    # --- OctoGram premium-upsell removal (t6 override) -------------------
    instance-of v1, v0, Lorg/telegram/ui/PremiumPreviewFragment;

    if-eqz v1, :cond_premium_upsell_skip_t6

    const/4 v1, 0x0

    return v1

    :cond_premium_upsell_skip_t6
    # -------------------------------------------------------------------
"""
            )
        )
    )

    val OCTO_LOGGER = PatchSet(
        id = "octogram_logger",
        label = "Silence OctoGram's own diagnostic log",
        description = "OctoGram keeps a log of its own, written both to logcat and to a file, and can upload it. " +
            "This silences all twelve of the log class's emitters, so nothing further is written to either, and " +
            "stubs the five uploaders that would send a log file or an app event to Telegram's servers. The " +
            "helpers that list and delete the log files are left working, so anything already written stays " +
            "readable and deletable. Each emitter is listed below with what it would have written.",
        smaliPatches = listOf(
            // cn8's public log emitters, one entry each. The single letters are R8's names and say
            // nothing about the level; the level each funnels into, and whether it carries a
            // throwable, is what these descriptions carry instead, read from the bodies.
            SmaliPatch(
                title = "Log emitter a (debug, message only)",
                explanation = "Writes its message under OctoGram's fixed tag OctoLogging, with no caller tag of its " +
                    "own and no throwable.",
                versionTag = "3.6.1",
                smaliPath = "cn8.smali",
                methodSignature = ".method public static a(Ljava/lang/String;)V",
                replacementBody = ".method public static a(Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"
            ),
            SmaliPatch(
                title = "Log emitter b (debug, tag and message)",
                explanation = "Writes the caller's tag and message. The same two arguments as d, k and o — these four " +
                    "differ only in the level they write at.",
                versionTag = "3.6.1",
                smaliPath = "cn8.smali",
                methodSignature = ".method public static b(Ljava/lang/String;Ljava/lang/String;)V",
                replacementBody = ".method public static b(Ljava/lang/String;Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"
            ),
            SmaliPatch(
                title = "Log emitter d (error, tag and message)",
                explanation = "Writes the caller's tag and message. The same two arguments as b, k and o — these four " +
                    "differ only in the level they write at.",
                versionTag = "3.6.1",
                smaliPath = "cn8.smali",
                methodSignature = ".method public static d(Ljava/lang/String;Ljava/lang/String;)V",
                replacementBody = ".method public static d(Ljava/lang/String;Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"
            ),
            SmaliPatch(
                title = "Log emitter e (error, tag, message and Exception)",
                explanation = "Writes the caller's tag and message with an Exception appended, so the exception's own " +
                    "text and stack trace go into the log with the message.",
                versionTag = "3.6.1",
                smaliPath = "cn8.smali",
                methodSignature = ".method public static e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Exception;)V",
                replacementBody = ".method public static e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Exception;)V\n    .locals 0\n    return-void\n.end method"
            ),
            SmaliPatch(
                title = "Log emitter f (error, tag, message and Throwable)",
                explanation = "The same as e with the parameter typed as Throwable, so it takes any throwable rather " +
                    "than an Exception.",
                versionTag = "3.6.1",
                smaliPath = "cn8.smali",
                methodSignature = ".method public static f(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V",
                replacementBody = ".method public static f(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V\n    .locals 0\n    return-void\n.end method"
            ),
            SmaliPatch(
                title = "Log emitter g (error, message and throwable)",
                explanation = "Writes its string argument under the fixed tag OctoLogging and appends the throwable to " +
                    "it, so the stack trace is logged with whatever the caller passed as a message.",
                versionTag = "3.6.1",
                smaliPath = "cn8.smali",
                methodSignature = ".method public static g(Ljava/lang/String;Ljava/lang/Throwable;)V",
                replacementBody = ".method public static g(Ljava/lang/String;Ljava/lang/Throwable;)V\n    .locals 0\n    return-void\n.end method"
            ),
            SmaliPatch(
                title = "Log emitter h (error, throwable only)",
                explanation = "Writes the throwable and nothing else, under the fixed tag OctoLogging: no message of " +
                    "the caller's, so the throwable's own text is the whole entry.",
                versionTag = "3.6.1",
                smaliPath = "cn8.smali",
                methodSignature = ".method public static h(Ljava/lang/Throwable;)V",
                replacementBody = ".method public static h(Ljava/lang/Throwable;)V\n    .locals 0\n    return-void\n.end method"
            ),
            SmaliPatch(
                title = "Log emitter k (info, tag and message)",
                explanation = "Writes the caller's tag and message. The same two arguments as b, d and o — these four " +
                    "differ only in the level they write at.",
                versionTag = "3.6.1",
                smaliPath = "cn8.smali",
                methodSignature = ".method public static k(Ljava/lang/String;Ljava/lang/String;)V",
                replacementBody = ".method public static k(Ljava/lang/String;Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"
            ),
            SmaliPatch(
                title = "The log sink every emitter writes through",
                explanation = "Where the log is actually written: joins the message and the throwable into one line, " +
                    "sends it to logcat at the level it was handed, and queues the same text for OctoGram's log " +
                    "file. Stubbing it as well means a call reaching the log class by some path not listed here is " +
                    "still silenced.",
                versionTag = "3.6.1",
                smaliPath = "cn8.smali",
                methodSignature = ".method public static m(ILjava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V",
                replacementBody = ".method public static m(ILjava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V\n    .locals 0\n    return-void\n.end method"
            ),
            SmaliPatch(
                title = "Log emitter n (info, message only, redacted)",
                explanation = "The one emitter that redacts before it writes: the message is run through all five of " +
                    "the log class's patterns — tokens, secrets and passwords, ids, and JSON payloads — and then " +
                    "written under the fixed tag OctoLogging. Its job is to keep secrets out of a shared log, and " +
                    "nothing else in the class does that, so with it stubbed nothing is written at all.",
                versionTag = "3.6.1",
                smaliPath = "cn8.smali",
                methodSignature = ".method public static n(Ljava/lang/String;)V",
                replacementBody = ".method public static n(Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"
            ),
            SmaliPatch(
                title = "Log emitter o (warning, tag and message)",
                explanation = "Writes the caller's tag and message. The same two arguments as b, d and k — these four " +
                    "differ only in the level they write at.",
                versionTag = "3.6.1",
                smaliPath = "cn8.smali",
                methodSignature = ".method public static o(Ljava/lang/String;Ljava/lang/String;)V",
                replacementBody = ".method public static o(Ljava/lang/String;Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"
            ),
            SmaliPatch(
                title = "Log emitter p (stack trace into the log file)",
                explanation = "Writes a throwable's stack trace into the log-file writer it is handed rather than to " +
                    "logcat — \"Caused by: \" and each frame, following the cause chain. This is the path by which " +
                    "a crash reaches the log file on disk.",
                versionTag = "3.6.1",
                smaliPath = "cn8.smali",
                methodSignature = ".method public static p(Ljava/io/OutputStreamWriter;Ljava/lang/Throwable;)V",
                replacementBody = ".method public static p(Ljava/io/OutputStreamWriter;Ljava/lang/Throwable;)V\n    .locals 0\n    return-void\n.end method"
            ),

            // The uploaders on the app-log path. The reference describes all five as log-only, so
            // each becomes a return-void; three of them live in PremiumPreviewFragment.
            SmaliPatch(
                title = "App-log uploader A3 (no arguments)",
                explanation = "One of the three upload helpers behind help.saveAppLog: stubbed, so no log is shipped " +
                    "to Telegram's servers from it.",
                versionTag = "3.6.1",
                smaliPath = "org/telegram/ui/PremiumPreviewFragment.smali",
                methodSignature = ".method public static A3()V",
                replacementBody = ".method public static A3()V\n    .locals 0\n    return-void\n.end method"
            ),
            SmaliPatch(
                title = "App-log uploader B3 (two ints)",
                explanation = "The second of the three help.saveAppLog uploaders, taking two ints rather than a file: " +
                    "stubbed, so nothing is uploaded through it.",
                versionTag = "3.6.1",
                smaliPath = "org/telegram/ui/PremiumPreviewFragment.smali",
                methodSignature = ".method public static B3(II)V",
                replacementBody = ".method public static B3(II)V\n    .locals 0\n    return-void\n.end method"
            ),
            SmaliPatch(
                title = "App-log uploader C3 (a message)",
                explanation = "The third of the help.saveAppLog uploaders, taking a string: stubbed, so the text it " +
                    "passes is never uploaded.",
                versionTag = "3.6.1",
                smaliPath = "org/telegram/ui/PremiumPreviewFragment.smali",
                methodSignature = ".method public static C3(Ljava/lang/String;)V",
                replacementBody = ".method public static C3(Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"
            ),
            SmaliPatch(
                title = "App-log uploader in r44",
                explanation = "The log uploader R8 left in r44, taking a boolean: stubbed, so the diagnostic it " +
                    "would start never reaches the network.",
                versionTag = "3.6.1",
                smaliPath = "r44.smali",
                methodSignature = ".method public static R0(Z)V",
                replacementBody = ".method public static R0(Z)V\n    .locals 0\n    return-void\n.end method"
            ),
            SmaliPatch(
                title = "App-log uploader inside MessagesController",
                explanation = "The uploader living in MessagesController, behind the same help path: stubbed, so the " +
                    "diagnostic it would start in the background never runs.",
                versionTag = "3.6.1",
                smaliPath = "a28.smali",
                methodSignature = ".method public final b3()V",
                replacementBody = ".method public final b3()V\n    .locals 0\n    return-void\n.end method"
            )
        )
    )

    /**
     * Pins the client's global logging switch to `false` at every site that writes it.
     *
     * The switch is read in roughly 250 places, so forcing the single value it is written
     * with disables logging app-wide far more cheaply than silencing each reader. The four
     * writes live in four different classes, and each one is forced independently because
     * any of them can be the one that runs.
     */
    val LOGGING_GATE = PatchSet(
        id = "octogram_logging_gate",
        label = "Turn Telegram's logging off app-wide",
        description = "Telegram decides whether to write its own logs from a single flag, and reads it in hundreds " +
            "of places — the database layer, network buffers, VoIP among them. Pinning the flag to false at the " +
            "four sites that write it turns logging off everywhere at once, instead of editing every reader. " +
            "One consequence is part of the shipped behaviour: the same flag also decides whether a custom " +
            "uncaught-exception handler is installed, so with it false that handler is not installed either.",
        smaliPatches = listOf(
            // sx0.smali:182. Note the reference splices the constant before the store and the
            // register is then read again a few instructions later by the branch that installs
            // the custom uncaught-exception handler, so that handler is no longer installed.
            // That is the shipped reference behaviour, reproduced rather than "fixed".
            SmaliPatch(
                title = "Forcing the global logging switch off (sx0)",
                explanation = "Stores false instead of the value read from preferences, so logging stays off app-wide. " +
                    "As in the reference build, the same register is reused moments later to decide whether to install the " +
                    "custom crash handler, so that handler is skipped as a side effect.",
                versionTag = "3.6.1",
                smaliPath = "sx0.smali",
                anchor = "    sput-boolean v0, Lsx0;->a:Z",
                replacement = "    const/4 v0, 0x0\n    sput-boolean v0, Lsx0;->a:Z"
            ),
            SmaliPatch(
                title = "Forcing the global logging switch off (teb)",
                explanation = "Stores false where teb reads the value from preferences, so logging stays off " +
                    "app-wide. The store appears in four classes and any of them can be the one that runs.",
                versionTag = "3.6.1",
                smaliPath = "teb.smali",
                anchor = "    sput-boolean v0, Lsx0;->a:Z",
                replacement = "    const/4 v0, 0x0\n    sput-boolean v0, Lsx0;->a:Z"
            ),
            SmaliPatch(
                title = "Forcing the global logging switch off (b47)",
                explanation = "Stores false where b47 reads the value from preferences, so logging stays off " +
                    "app-wide. The store appears in four classes and any of them can be the one that runs.",
                versionTag = "3.6.1",
                smaliPath = "b47.smali",
                anchor = "    sput-boolean p2, Lsx0;->a:Z",
                replacement = "    const/4 p2, 0x0\n    sput-boolean p2, Lsx0;->a:Z"
            ),
            SmaliPatch(
                title = "Forcing the global logging switch off (nt9)",
                explanation = "Stores false where nt9 reads the value from preferences, so logging stays off " +
                    "app-wide. The store appears in four classes and any of them can be the one that runs.",
                versionTag = "3.6.1",
                smaliPath = "nt9.smali",
                anchor = "    sput-boolean v0, Lsx0;->a:Z",
                replacement = "    const/4 v0, 0x0\n    sput-boolean v0, Lsx0;->a:Z"
            )
        )
    )

    private fun firebaseRegistrarPatch(id: String, label: String, smaliPath: String, description: String) = PatchSet(
        id = id,
        label = label,
        description = description,
        smaliPatches = listOf(
            SmaliPatch(
                title = label,
                explanation = description,
                // Untagged: the registrar ships identically in every build, so it needs no version
                // to be found in. Only the obfuscated Telegram classes are renamed between builds.
                versionTag = null,
                smaliPath = smaliPath,
                methodSignature = ".method public getComponents()Ljava/util/List;",
                replacementBody = """.method public getComponents()Ljava/util/List;
    .locals 1

    invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;

    move-result-object v0

    return-object v0
.end method"""
            )
        )
    )

    val FIREBASE_ABT = firebaseRegistrarPatch(
        "octogram_firebase_abt",
        "Disable Firebase A/B testing",
        "com/google/firebase/abt/component/AbtRegistrar.smali",
        "Rewrites AbtRegistrar.getComponents() to return an empty list, so startup registers none of the A/B " +
            "testing components: no experiment is assigned and no experiment telemetry is produced."
    )

    val FIREBASE_REMOTE_CONFIG = firebaseRegistrarPatch(
        "octogram_firebase_remoteconfig",
        "Disable Firebase Remote Config",
        "com/google/firebase/remoteconfig/RemoteConfigRegistrar.smali",
        "Rewrites RemoteConfigRegistrar.getComponents() to return an empty list, so startup registers none of " +
            "its components and the app no longer downloads server-side experiment flags or telemetry parameters."
    )

    val FIREBASE_REMOTE_CONFIG_KTX = firebaseRegistrarPatch(
        "octogram_firebase_remoteconfig_ktx",
        "Disable the Remote Config Kotlin wrapper",
        "com/google/firebase/remoteconfig/FirebaseRemoteConfigKtxRegistrar.smali",
        "Rewrites FirebaseRemoteConfigKtxRegistrar.getComponents() to return an empty list, so the Kotlin " +
            "extension for Remote Config registers no lifecycle components and never subscribes on the app's " +
            "behalf."
    )

    val FIREBASE_DATATRANSPORT = firebaseRegistrarPatch(
        "octogram_firebase_datatransport",
        "Disable Google Play Services data transport",
        "com/google/firebase/datatransport/TransportRegistrar.smali",
        "Rewrites TransportRegistrar.getComponents() to return an empty list, so startup registers none of the " +
            "Google Play Services DataTransport components and no telemetry event can be queued, batched or " +
            "uploaded to Google's backends."
    )

    val ALL = listOf(
        SPONSORED_MSGS,
        PHOTO_VIEWER_ADS,
        SEARCH_ADS,
        OTA_UPDATER,
        PREMIUM_UPSELL,
        OCTO_LOGGER,
        LOGGING_GATE,
        FIREBASE_ABT,
        FIREBASE_REMOTE_CONFIG,
        FIREBASE_REMOTE_CONFIG_KTX,
        FIREBASE_DATATRANSPORT
    )

}
