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
 * - work361/patch_crashlog_361.py (OctoGram's own crash reporter, which the logging flag misses)
 * - work361/patch_premium_settings.py (The premium rows in the profile's settings list)
 * - work361/patch_external_browser_361.py (The external-browser setting's default)
 * - work361/patch_hide_business_361.py (The Telegram Business upsell row, and its commands)
 * - work361/patch_premium_sheets_361.py (The paywall's remaining sheet presentations)
 *
 * patch_dex.py (OctoGram 3.6.0) is deliberately not transcribed: no source here offers a 3.6.0
 * build, and the engine patches only a build identified as [REGISTERED_BUILD], so its entries
 * cannot run on any build this app accepts. A patch that cannot run is a row in the selection
 * list that describes a change it does not make. The edits it describes are noted where they would
 * have gone, so adding a 3.6.0 source means porting them rather than rediscovering them.
 *
 * Every entry corresponds to an edit in one of those scripts. The engine resolves all DEX
 * container assignments at runtime by inspecting the target APK's DEX headers, so no patch
 * hardcodes a container. Version tags mark an entry for one build, so version-specific obfuscated
 * class names are targeted without conflicting with unrelated classes.
 *
 * Two deliberate divergences from the reference scripts, both behavior-preserving: stub bodies
 * here use `.locals 0` where the scripts reuse the method's original `.registers N`. With a body
 * that only returns, the two assemble to equivalent code. The reference's own line-anchored edits
 * are expressed as unique-string anchors.
 *
 * The user reads an entry's `title` and `explanation` while a patch runs and in the selection
 * screen's technical panel, which lists both for every entry under the class and method it
 * touches. They are written for that reader—what the entry suppresses, fetches or shows, in
 * terms of what would otherwise happen—while the class, method and version stay in the entry's
 * own fields.
 *
 * The levels of the [LOG_EMITTERS] entries come from the 3.6.1 build's own method bodies: R8's
 * single-letter names do not indicate a level, and the bodies differ only in which emitter they
 * funnel into and at which level.
 *
 * The following edits are grouped by the choice each belongs to, and [OctoGramPatchItems] is the table
 * that pairs a group with an item the user can switch: the item's name, the feature it belongs to
 * and the line describing what switching it on does. A set here therefore declares only its own
 * switch and passes the engine a generator rather than a patch list, because the engine applies a
 * set's static patches whether or not they are selected and calls a generator only for the subset
 * a selection names—which is the only way a set whose items are switched one at a time can
 * express that.
 */
object OctoGramPatches {

    /**
     * The build named by the manifest's OctoGram source, see `sources.json`.
     *
     * Every entry here is tagged for this one build or left untagged. A tagged entry runs only on
     * a build the engine identifies as this version: an APK whose classes identify no supported
     * build is refused every tagged entry, with the reason in the step log. The reference
     * scripts' 3.6.0 entries are therefore not ported at all.
     *
     * The entries spell their tags out rather than reading this constant, and this is the value the
     * content test holds every tag to: a tag mistyped in one entry then fails that test, instead of
     * matching whatever value this constant was last changed to.
     */
    const val REGISTERED_BUILD = "3.6.1"

    /**
     * The edit that stops the sponsored rows being built into a channel's chat list.
     *
     * 3.6.1 (build 38275): ChatActivity.If -> return-void. The row itself is not inserted, so the
     * list is built without it rather than with it hidden.
     */
    internal val SPONSORED_CHAT_LIST_ROWS = listOf(
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
        )
    )

    /**
     * The edit that stops the sponsored-message request being made.
     *
     * 3.6.1 (build 38275): MessagesController.J1 -> return null. It returns the holder of the
     * messages that would fill a sponsored row, and every caller tests its result for null
     * straight away, so a null return takes the callers' existing null branch.
     */
    internal val SPONSORED_REQUEST = listOf(
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
        )
    )

    // 3.6.1 (build 38275). The reference's 3.6.0 pair for this set—org/telegram/ui/o.ss(Z)V and
    // org/telegram/messenger/m0.sc(J)—is not ported, for the reason in the header.
    /** The set that blocks sponsored posts in channels. */
    val SPONSORED_MSGS = octoGramSet(
        id = "octogram_sponsored_msgs",
        label = "Block sponsored posts in channels",
        description = "Stops the promoted posts OctoGram shows in public channels: the ad row is " +
            "never built into the chat list, and the request that would fill it is never made. Both " +
            "entry points are the ones on the build this app offers.",
    )

    /**
     * The edit that stops the full-screen promotion between media items.
     *
     * 3.6.1: gpd.b(Lwz0;)V -> return-void. It is the viewer's ad callback, so stubbing it both
     * prevents the interstitial and stops the request behind it.
     */
    internal val PHOTO_VIEWER_CALLBACK = listOf(
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
        )
    )

    // The reference's 3.6.0 variant for this set—y5l.Q()—is not ported, for the reason in
    // the header.

    /** The set that removes the ads shown between photos. */
    val PHOTO_VIEWER_ADS = octoGramSet(
        id = "octogram_photo_viewer_ads",
        label = "Remove the ads between photos",
        description = "No promotion appears full-screen while swiping through a channel's photos or media: the " +
            "viewer's ad callback is stubbed, so the interstitial is never presented and the request behind " +
            "it is never made.",
    )

    /**
     * The edit that keeps promoted channels and bots out of search.
     *
     * 3.6.1: s04.k0 -> reset sponsoredReqId to 0 and return. The response the request would have
     * filled is discarded, and the pending-request id is reset so the search controller does not
     * keep waiting on it.
     */
    internal val SEARCH_SPONSORED_RESULTS = listOf(
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
        )
    )

    // The reference's 3.6.0 variant for this set—be6.m1(TLObject)—is not ported, for the reason
    // in the header.

    /** The set that filters sponsored channels and bots out of search results. */
    val SEARCH_ADS = octoGramSet(
        id = "octogram_search_ads",
        label = "Filter sponsored channels out of search",
        description = "Search stops putting promoted channels and bots above real results: the response that " +
            "carries them is discarded and the pending-request id reset, so the search controller stays " +
            "consistent while the sponsored entries go nowhere.",
    )

    /**
     * The edit that stops the built-in GitHub update check.
     *
     * 3.6.1: j6d.smali case :pswitch_160 in packed-switch. The case is sliced so it signals the
     * no-update callback directly, which leaves the update UI nothing to wait for.
     */
    internal val OTA_UPDATE_CHECK = listOf(
        SmaliPatch(
            title = "The GitHub update request (3.6.1)",
            explanation = "Slices dispatcher case :pswitch_160 so the no-update callback is signaled directly and no request to GitHub is ever made. " +
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
        )
    )

    // The reference's 3.6.0 variant for this set—hxk.o0(Lhxk$i)—is not ported, for the reason
    // in the header.

    /** The set that stops the built-in GitHub update check. */
    val OTA_UPDATER = octoGramSet(
        id = "octogram_ota_updater",
        label = "Stop the built-in update check",
        description = "OctoGram asks GitHub whether a newer release exists and puts a prompt up when it thinks " +
            "one does. This cuts the request off and signals the \"no update\" path instead, so nothing is " +
            "fetched and the update screen is not left waiting for a reply that will never arrive.",
    )

    /**
     * The edit that drops the paywall in the app's main navigation router.
     *
     * 3.6.1: ActionBarLayout.b(Ln16;)Z. The guard is spliced in front of the router's own body, so
     * an attempt to present the premium screen returns without presenting anything.
     */
    internal val PREMIUM_UPSELL_ROUTER = listOf(
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
        )
    )

    /**
     * The same drop on the window-stack override the main window uses.
     *
     * 3.6.1: t6.b(Ln16;)Z. The paywall is reachable through this router too, so one guard without
     * the other leaves that path open.
     */
    internal val PREMIUM_UPSELL_WINDOW_STACK = listOf(
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

    /** The set that drops navigation to the Premium upsell in the app's routers. */
    val PREMIUM_UPSELL = octoGramSet(
        id = "octogram_premium_upsell",
        label = "Stop the premium screen opening",
        description = "The premium screen no longer opens when the app navigates to it, which is most of the ways " +
            "it can be reached. The other two ways are covered too: the sheets it can open as are closed by " +
            "their own switch, and the rows that lead to it are hidden by theirs. Premium feature cells and " +
            "usage-limit screens still appear, because they show real account state rather than an advert, and " +
            "a subscriber loses the screen where they manage their subscription.",
    )

    /**
     * The twelve emitters of OctoGram's log class, `cn8`.
     *
     * The single letters are R8's names and do not indicate a level; the entries' own explanations
     * state the level each funnels into and whether it takes a throwable, read from the bodies in
     * the 3.6.1 build.
     */
    internal val LOG_EMITTERS = listOf(
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
            explanation = "Writes the caller's tag and message. The same two arguments as d, k and o—these four " +
                "differ only in the level they write at.",
            versionTag = "3.6.1",
            smaliPath = "cn8.smali",
            methodSignature = ".method public static b(Ljava/lang/String;Ljava/lang/String;)V",
            replacementBody = ".method public static b(Ljava/lang/String;Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"
        ),
        SmaliPatch(
            title = "Log emitter d (error, tag and message)",
            explanation = "Writes the caller's tag and message. The same two arguments as b, k and o—these four " +
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
            explanation = "Writes the caller's tag and message. The same two arguments as b, d and o—these four " +
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
                "the log class's patterns—tokens, secrets and passwords, ids, and JSON payloads—and then " +
                "written under the fixed tag OctoLogging. Its job is to keep secrets out of a shared log, and " +
                "nothing else in the class does that, so with it stubbed nothing is written at all.",
            versionTag = "3.6.1",
            smaliPath = "cn8.smali",
            methodSignature = ".method public static n(Ljava/lang/String;)V",
            replacementBody = ".method public static n(Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"
        ),
        SmaliPatch(
            title = "Log emitter o (warning, tag and message)",
            explanation = "Writes the caller's tag and message. The same two arguments as b, d and k—these four " +
                "differ only in the level they write at.",
            versionTag = "3.6.1",
            smaliPath = "cn8.smali",
            methodSignature = ".method public static o(Ljava/lang/String;Ljava/lang/String;)V",
            replacementBody = ".method public static o(Ljava/lang/String;Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"
        ),
        SmaliPatch(
            title = "Log emitter p (stack trace into the log file)",
            explanation = "Writes a throwable's stack trace into the log-file writer it is handed rather than to " +
                "logcat—\"Caused by: \" and each frame, following the cause chain. This is the path by which " +
                "a crash reaches the log file on disk.",
            versionTag = "3.6.1",
            smaliPath = "cn8.smali",
            methodSignature = ".method public static p(Ljava/io/OutputStreamWriter;Ljava/lang/Throwable;)V",
            replacementBody = ".method public static p(Ljava/io/OutputStreamWriter;Ljava/lang/Throwable;)V\n    .locals 0\n    return-void\n.end method"
        )
    )

    /**
     * The five uploaders on the app-log path.
     *
     * The reference describes all five as log-only, so each becomes a return-void; three of them
     * live in PremiumPreviewFragment.
     */
    internal val LOG_UPLOADERS = listOf(
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
            explanation = "The third of the three help.saveAppLog uploaders, taking a string: stubbed, so the text it " +
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

    /** The set that silences OctoGram's own diagnostic log and its uploaders. */
    val OCTO_LOGGER = octoGramSet(
        id = "octogram_logger",
        label = "Silence OctoGram's own diagnostic log",
        description = "OctoGram keeps a log of its own, written both to logcat and to a file, and can upload it. " +
            "This silences all twelve of the log class's emitters, so nothing further is written to either, and " +
            "stubs the five uploaders that would send a log file or an app event to Telegram's servers. The " +
            "helpers that list and delete the log files are left working, so anything already written stays " +
            "readable and deletable. Each emitter is listed below with what it would have written.",
    )

    /**
     * The four writes of the client's global logging switch, pinned to `false`.
     *
     * The switch is read in roughly 250 places, so forcing the single value it is written
     * with disables logging app-wide without editing every reader. The four writes live in
     * four different classes, and each one is forced independently because any of them can
     * be the one that runs.
     *
     * In sx0.smali:182 the reference splices the constant before the store, and the register is
     * then read again a few instructions later by the branch that installs the custom
     * uncaught-exception handler, so that handler is no longer installed. This side effect is the
     * shipped reference behavior, reproduced rather than "fixed".
     */
    internal val LOGGING_GATE_WRITES = listOf(
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

    /** The set that pins Telegram's global logging switch to false. */
    val LOGGING_GATE = octoGramSet(
        id = "octogram_logging_gate",
        label = "Turn Telegram's logging off app-wide",
        description = "Telegram decides whether to write its own logs from a single flag, and reads it in hundreds " +
            "of places—the database layer, network buffers, VoIP among them. Pinning the flag to false at the " +
            "four sites that write it turns logging off everywhere at once, instead of editing every reader. " +
            "One consequence is part of the shipped behavior: the same flag also decides whether a custom " +
            "uncaught-exception handler is installed, so with it false that handler is not installed either.",
    )

    /**
     * The one edit each Firebase set makes: its registrar's component list, emptied.
     *
     * Untagged, because the registrar's name does not change between releases and no version is
     * needed to find it. Only the obfuscated Telegram classes are renamed between builds. This is
     * also the only edit an unidentified build may receive, because the gate resolves it by class
     * name rather than by version.
     */
    private fun firebaseRegistrarEdit(
        smaliPath: String,
        title: String,
        explanation: String
    ) = SmaliPatch(
        title = title,
        explanation = explanation,
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

    internal val FIREBASE_ABT_REGISTRAR = listOf(
        firebaseRegistrarEdit(
            smaliPath = "com/google/firebase/abt/component/AbtRegistrar.smali",
            title = "Disable Firebase A/B testing",
            explanation = "Rewrites AbtRegistrar.getComponents() to return an empty list, so startup registers none of the A/B " +
                "testing components: no experiment is assigned and no experiment telemetry is produced."
        )
    )

    /** The set that disables Firebase A/B testing. */
    val FIREBASE_ABT = octoGramSet(
        id = "octogram_firebase_abt",
        label = "Disable Firebase A/B testing",
        description = "Rewrites AbtRegistrar.getComponents() to return an empty list, so startup registers none of the A/B " +
            "testing components: no experiment is assigned and no experiment telemetry is produced.",
    )

    internal val FIREBASE_REMOTE_CONFIG_REGISTRAR = listOf(
        firebaseRegistrarEdit(
            smaliPath = "com/google/firebase/remoteconfig/RemoteConfigRegistrar.smali",
            title = "Disable Firebase Remote Config",
            explanation = "Rewrites RemoteConfigRegistrar.getComponents() to return an empty list, so startup registers none of " +
                "its components and the app no longer downloads server-side experiment flags or telemetry parameters."
        )
    )

    /** The set that disables Firebase Remote Config. */
    val FIREBASE_REMOTE_CONFIG = octoGramSet(
        id = "octogram_firebase_remoteconfig",
        label = "Disable Firebase Remote Config",
        description = "Rewrites RemoteConfigRegistrar.getComponents() to return an empty list, so startup registers none of " +
            "its components and the app no longer downloads server-side experiment flags or telemetry parameters.",
    )

    internal val FIREBASE_REMOTE_CONFIG_KTX_REGISTRAR = listOf(
        firebaseRegistrarEdit(
            smaliPath = "com/google/firebase/remoteconfig/FirebaseRemoteConfigKtxRegistrar.smali",
            title = "Disable the Remote Config Kotlin wrapper",
            explanation = "Rewrites FirebaseRemoteConfigKtxRegistrar.getComponents() to return an empty list, so the Kotlin " +
                "extension for Remote Config registers no lifecycle components and never subscribes on the app's " +
                "behalf."
        )
    )

    /** The set that disables the Kotlin wrapper around Remote Config. */
    val FIREBASE_REMOTE_CONFIG_KTX = octoGramSet(
        id = "octogram_firebase_remoteconfig_ktx",
        label = "Disable the Remote Config Kotlin wrapper",
        description = "Rewrites FirebaseRemoteConfigKtxRegistrar.getComponents() to return an empty list, so the Kotlin " +
            "extension for Remote Config registers no lifecycle components and never subscribes on the app's " +
            "behalf.",
    )

    internal val FIREBASE_DATATRANSPORT_REGISTRAR = listOf(
        firebaseRegistrarEdit(
            smaliPath = "com/google/firebase/datatransport/TransportRegistrar.smali",
            title = "Disable Google Play Services data transport",
            explanation = "Rewrites TransportRegistrar.getComponents() to return an empty list, so startup registers none of the " +
                "Google Play Services DataTransport components and no telemetry event can be queued, batched or " +
                "uploaded to Google's backends."
        )
    )

    /** The set that disables Google Play Services data transport. */
    val FIREBASE_DATATRANSPORT = octoGramSet(
        id = "octogram_firebase_datatransport",
        label = "Disable Google Play Services data transport",
        description = "Rewrites TransportRegistrar.getComponents() to return an empty list, so startup registers none of the " +
            "Google Play Services DataTransport components and no telemetry event can be queued, batched or " +
            "uploaded to Google's backends.",
    )

    /**
     * OctoGram's own crash reporter, switched off at the call that installs it.
     *
     * `yb3` is OctoGram's crash-logging subsystem: the strings in it contain the word
     * "Crashlytics", but no Firebase library is behind them, and this APK contains no crashlytics
     * package at all. `g()` is its init, called from exactly one place, `LaunchActivity` at
     * startup, and it performs four steps: it logs a line, creates the crash-notification channel,
     * installs OctoGram's own `UncaughtExceptionHandler` (`rx0`), and posts a runnable (`wb3`) that
     * checks for a crash log left by an earlier run.
     *
     * This handler is why the edit is separate from [LOGGING_GATE]: the flag that gate pins false
     * determines whether the handler inside `sx0` is installed, and `yb3.g()` installs a second,
     * independent one on every launch that does not consult that flag. With `g()` stubbed no crash
     * is captured, so the file it would write and the "OctoGram just crashed!" notification it
     * would raise are both gone.
     *
     * Nothing else depends on `g()` having run: `yb3.<clinit>` sets its own statics, and
     * `yb3.e()`—the logs-directory getter the rest of the reporter uses—resolves that
     * directory itself.
     *
     * The reference keeps the method's original `.registers 5` line. A body that only returns reads
     * no register, so this entry uses `.locals 0` (see the header).
     */
    internal val CRASH_REPORTER_STARTUP = listOf(
        SmaliPatch(
            title = "OctoGram's crash reporter starting up (3.6.1)",
            explanation = "Stubs yb3.g(), the call that installs OctoGram's own uncaught-exception handler while the " +
                "app starts. A crash no longer writes pending_crash.txt into the app's files and no longer raises " +
                "the \"OctoGram just crashed!\" notification. This handler is installed regardless of the logging " +
                "flag this app pins false, which is why it takes an edit of its own.",
            versionTag = "3.6.1",
            smaliPath = "yb3.smali",
            methodSignature = ".method public static g()V",
            replacementBody = """.method public static g()V
    .locals 0

    return-void
.end method"""
        )
    )

    /** The set that stops OctoGram's own crash reporter from being installed. */
    val CRASH_REPORTER = octoGramSet(
        id = "octogram_crash_reporter",
        label = "Switch off OctoGram's own crash reporter",
        description = "OctoGram installs a crash reporter of its own while the app starts: it writes the stack trace " +
            "of a crash into the app's own storage and raises an \"OctoGram just crashed!\" notification the next " +
            "time the app opens. This stops that handler being installed, so a crash leaves no log behind and " +
            "raises nothing. The handler Telegram installs for itself is a different one and is not affected " +
            "here.",
    )

    /**
     * The four edits that take the Telegram Premium row out of the settings lists.
     *
     * Three are in `ProfileActivity.yd()`, which resets every row index to -1 and then inserts each
     * row by asking a condition and storing the row it took, so replacing the branch with an
     * unconditional `goto` to the same label leaves that row's index at -1—hidden, and unclickable
     * as well, because the click router matches rows by index and -1 matches nothing.
     *
     * The third of those is what makes the first two work. The combined premium-sections row is
     * inserted when *any* of premiumRow, starsRow, tonRow or businessRow is still -1, and skipped
     * only when all five premium-ish rows are present, so hiding the Premium row on its own would
     * have made the sections row appear in its place. Forcing the first of those checks to jump
     * past the insert keeps it out of the list altogether.
     *
     * The fourth is the same upsell on the app's own Settings screen, which builds its rows in a
     * class of its own rather than in the profile's list, so the three edits above never reached
     * it. Its row is drawn only while the account is not subscribed, and its tap opens the premium
     * screen.
     *
     * `starsRow` (Telegram Stars) and `tonRow` (TON) are separate paid products rather than
     * Telegram Premium and are deliberately left visible, so their checks and their rows are
     * untouched. `businessRow` is hidden too, by [BUSINESS_UPSELL] rather than here, because it is
     * the Business row on both screens that the one switch removes.
     */
    internal val PREMIUM_SETTINGS_ROWS = listOf(
        SmaliPatch(
            title = "Hiding the Telegram Premium row",
            explanation = "Skips the row insert for premiumRow, so the settings list on your profile has no " +
                "Telegram Premium row: its index stays at -1, which is also why the row cannot be tapped—the " +
                "click router matches rows by index.",
            versionTag = "3.6.1",
            smaliPath = "org/telegram/ui/ProfileActivity.smali",
            anchor = "    if-nez v4, :cond_2e8",
            replacement = "    goto :cond_2e8"
        ),
        SmaliPatch(
            title = "Hiding the Send a Gift row",
            explanation = "Skips the row insert for premiumGiftingRow, so the settings list on your profile has no " +
                "Send a Gift row and no way to open the gifting sheet from it.",
            versionTag = "3.6.1",
            smaliPath = "org/telegram/ui/ProfileActivity.smali",
            anchor = "    if-nez v4, :cond_332",
            replacement = "    goto :cond_332"
        ),
        SmaliPatch(
            title = "Keeping the premium-sections row out as well",
            explanation = "Forces the combined premium-sections row to skip. It is inserted whenever any of the " +
                "premium rows is missing, so without this the two edits above would have replaced the rows they " +
                "hide with a different premium row. The branch is the first of the five checks, and it jumps past " +
                "the insert to the label the skip path uses.",
            versionTag = "3.6.1",
            smaliPath = "org/telegram/ui/ProfileActivity.smali",
            // `if-gez v4, :cond_346` alone occurs four times in this class—once for each of the
            // four checks—so the anchor contains the lines the engine disassembles between the
            // row index being read and the check, which is what identifies this one as the
            // premiumRow check. The two `.line` directives are part of that text and are matched
            // exactly as the disassembler writes them.
            anchor = """    iget v4, v0, Lorg/telegram/ui/ProfileActivity;->premiumRow:I

    .line 820
    .line 821
    if-gez v4, :cond_346""",
            replacement = """    iget v4, v0, Lorg/telegram/ui/ProfileActivity;->premiumRow:I

    .line 820
    .line 821
    goto :cond_34e"""
        ),
        SmaliPatch(
            title = "Hiding the Telegram Premium row in Settings",
            explanation = "Skips the row insert for the same upsell on the app's own Settings screen, whose " +
                "rows are built in this class rather than in the profile's list. The branch guards the row " +
                "titled by string 0x7f0fd21d and drawn with drawable/settings_premium, so it is the premium " +
                "row and not one of the two products beside it.",
            versionTag = "3.6.1",
            smaliPath = "teb.smali",
            anchor = "    if-nez v3, :cond_2d7",
            replacement = "    goto :cond_2d7"
        )
    )

    /** The set that hides the premium rows in the profile's settings list. */
    val PREMIUM_SETTINGS = octoGramSet(
        id = "octogram_premium_settings",
        label = "Hide the Telegram Premium row",
        description = "Takes the Telegram Premium row out of both settings lists: the one on your profile, " +
            "together with the Send a Gift row and the combined premium-sections row that would otherwise " +
            "appear in its place, and the one on the app's Settings screen. The profile's rows share a list " +
            "and their conditions overlap, so the three there are one switch: hiding only the first would put " +
            "a different premium row on screen. Telegram Stars and TON are separate products and stay " +
            "visible; the Telegram Business row has its own switch.",
    )

    /**
     * The edit that registers OctoGram's external-browser setting as on.
     *
     * 3.6.1: `OctoConfig.<init>` registers the default for the `openLinksExternalBrowser` key. The
     * call reads `v4`, which holds `Boolean.FALSE`; `v3` holds `Boolean.TRUE` and is written once,
     * before this key, and never again. Passing `v3` ships the setting on.
     *
     * The default applies only where the key has never been stored: `OctoConfig.a(i43)` falls back
     * to it when SharedPreferences holds no value, so a device that has already chosen keeps that
     * choice.
     */
    internal val EXTERNAL_BROWSER_DEFAULT = listOf(
        SmaliPatch(
            title = "Opening links in the phone's browser by default (3.6.1)",
            explanation = "Registers Boolean.TRUE instead of Boolean.FALSE as this setting's default, so a " +
                "fresh install hands http and https links to the phone's browser rather than to the in-app " +
                "viewer. A device that has already changed the setting keeps its stored choice, and " +
                "Telegram's own links (t.me and the rest) still open inside the app.",
            versionTag = "3.6.1",
            smaliPath = "it/octogram/android/unsorted/OctoConfig.smali",
            // The invoke alone occurs 130 times in this class—one per setting—so the anchor
            // carries the key's `const-string` and the `.line` directives between them, which
            // identify the call this setting's default is registered by.
            anchor = """    const-string v0, "openLinksExternalBrowser"

    .line 1473
    .line 1474
    invoke-virtual {p0, v4, v0}, Lit/octogram/android/unsorted/OctoConfig;->g(Ljava/lang/Object;Ljava/lang/String;)Li43;""",
            replacement = """    const-string v0, "openLinksExternalBrowser"

    .line 1473
    .line 1474
    invoke-virtual {p0, v3, v0}, Lit/octogram/android/unsorted/OctoConfig;->g(Ljava/lang/Object;Ljava/lang/String;)Li43;"""
        )
    )

    /** The set that ships links opening in the phone's browser. */
    val EXTERNAL_BROWSER = octoGramSet(
        id = "octogram_external_browser",
        label = "Open links in the phone's browser",
        description = "OctoGram's setting for opening links outside the app ships off, so http and https links " +
            "go to its in-app viewer until the setting is found and changed. This registers the setting's " +
            "default as on, so a fresh install opens them in the phone's browser. A device that has already " +
            "touched the setting keeps its stored choice, and Telegram's own links still open inside the app.",
    )

    /**
     * The four edits that take the Telegram Business upsell row out and close its commands.
     *
     * 3.6.1: `ProfileActivity.yd()` inserts `businessRow` behind the same kind of condition as the
     * premium rows, so replacing the branch with an unconditional jump to the same label leaves the
     * row's index at -1—hidden, and unclickable as well, because the click router matches rows by
     * index. The row is an upsell for the premium screen rather than an entry to the Business
     * settings, and the navigation guard in [PREMIUM_UPSELL] already refuses that screen, which
     * would leave a visible row that does nothing.
     *
     * The same upsell is on the app's own Settings screen, built in a class of its own, and that row
     * is the fourth edit. Its tap opens the premium screen with the same `"settings"` source the
     * premium row beside it uses, so the two are one surface reached from two screens.
     *
     * The last two edits close the `/premium` and `/business` command slugs in `hq6.k(List)`, the
     * dispatcher that turns a typed slug into a fragment. Each slug's branch is replaced with a
     * jump to the next slug's check, so the cascade continues normally and no fragment is
     * constructed. The `/business` branch's fall-through also holds OctoGram's `do-not-hide-ads`
     * debug command, which the same jump makes unreachable: it only ran when the first slug was
     * `business`.
     *
     * The Telegram Business feature itself is not touched: its package ships unchanged, and only
     * this upsell row and the two commands that open the premium screen are edited.
     */
    internal val BUSINESS_UPSELL = listOf(
        SmaliPatch(
            title = "The Telegram Business upsell row",
            explanation = "Skips the row insert for businessRow, so the settings list on your profile has no " +
                "Telegram Business row. The row is a Telegram Premium upsell, not an entry to the Business " +
                "settings, and the paywall guard leaves it with nowhere to go.",
            versionTag = "3.6.1",
            smaliPath = "org/telegram/ui/ProfileActivity.smali",
            anchor = "    if-nez v4, :cond_322",
            replacement = "    goto :cond_322"
        ),
        SmaliPatch(
            title = "The /premium command",
            explanation = "Replaces the /premium command's branch with a jump to the next command in the " +
                "dispatcher, so the command constructs no premium fragment and shows nothing.",
            versionTag = "3.6.1",
            smaliPath = "hq6.smali",
            anchor = "    if-eqz v0, :cond_f20",
            replacement = "    goto :cond_f20"
        ),
        SmaliPatch(
            title = "The /business command",
            explanation = "The same jump on the /business command, which opens the premium screen. The " +
                "dispatcher's cascade continues at the next command instead, so no fragment is constructed.",
            versionTag = "3.6.1",
            smaliPath = "hq6.smali",
            anchor = "    if-eqz v0, :cond_f40",
            replacement = "    goto :cond_f40"
        ),
        SmaliPatch(
            title = "The Telegram Business row in Settings",
            explanation = "Skips the row insert for the same upsell on the app's own Settings screen, whose rows " +
                "are built in this class rather than in the profile's list. The branch guards the row titled by " +
                "string 0x7f0fd232 and drawn with drawable/settings_business, and its tap opens the premium " +
                "screen, so this is the upsell and not the Business feature.",
            versionTag = "3.6.1",
            smaliPath = "teb.smali",
            anchor = "    if-nez v2, :cond_40b",
            replacement = "    goto :cond_40b"
        )
    )

    /** The set that hides the Telegram Business upsell row and closes its commands. */
    val HIDE_BUSINESS = octoGramSet(
        id = "octogram_hide_business",
        label = "Hide the Telegram Business upsell row and its commands",
        description = "Takes the Telegram Business row out of both settings lists, the one on your profile and " +
            "the one on the app's Settings screen, and closes the /premium and /business commands. The row is " +
            "a Telegram Premium upsell rather than an entry to the Business settings, and the commands open " +
            "the same premium screen, so all four are one switch. The Telegram Business feature itself, and " +
            "its own settings screens, are untouched.",
    )

    /**
     * The edit that closes the paywall's remaining sheet presentations.
     *
     * 3.6.1: `BaseFragment.E2(...)` builds the cells a bottom-sheet dialog is presented with. The
     * eight PremiumPreviewFragment constructions that open as a sheet all go through it, and the
     * guards in [PREMIUM_UPSELL] never see them because those paths do not call the navigation
     * router the guards sit in. The guard inserted here returns null for a PremiumPreviewFragment
     * before the dialog is built.
     *
     * Returning null is the method's own no-parent-activity path: its first branch already returns
     * null when the fragment's parent activity is absent, and none of the eight callers reads the
     * return value. Every other sheet keeps building, because only a PremiumPreviewFragment is
     * refused.
     */
    internal val PREMIUM_UPSELL_SHEETS = listOf(
        SmaliPatch(
            title = "The paywall's sheet presentations (3.6.1)",
            explanation = "Refuses PremiumPreviewFragment in the helper every sheet presentation goes through, " +
                "so the eight premium paths that open the paywall as a bottom sheet stop before the dialog is " +
                "built. Other sheets are unaffected, because only a PremiumPreviewFragment is refused.",
            versionTag = "3.6.1",
            smaliPath = "org/telegram/ui/ActionBar/p.smali",
            anchor = """.method public final E2(Lorg/telegram/ui/ActionBar/p;Ldd0;)[Lp16;
    .registers 12
""",
            replacement = """.method public final E2(Lorg/telegram/ui/ActionBar/p;Ldd0;)[Lp16;
    .registers 12
    # --- OctoGram premium-upsell removal -------------------------------
    # Every premium sheet is presented through this helper, and no caller reads the
    # return value, so returning null suppresses the sheet before the dialog is built.
    instance-of v0, p1, Lorg/telegram/ui/PremiumPreviewFragment;

    if-eqz v0, :cond_premium_sheet_skip

    const/4 p1, 0x0

    return-object p1

    :cond_premium_sheet_skip
    # -------------------------------------------------------------------
"""
        )
    )

    /** The set that closes the paywall's remaining sheet presentations. */
    val PREMIUM_SHEETS = octoGramSet(
        id = "octogram_premium_sheets",
        label = "Close the paywall's remaining sheets",
        description = "The navigation-router switch blocks the paywall for 53 of the 61 places the app can " +
            "present it; the other eight open it as a bottom sheet through a different helper, which that " +
            "switch never sees. This refuses a PremiumPreviewFragment in that helper, so those eight stop " +
            "before the dialog is built. Every other sheet still opens.",
    )

    /**
     * One OctoGram set: its own switch, and the edits [OctoGramPatchItems] offers as its items.
     *
     * The set declares no patches of its own. The engine applies a set's patches whether or not
     * they are selected and calls a generator only for the subset a selection names, so a set whose
     * edits are switched one at a time has to pass the engine a generator instead. The generator is
     * built here rather than asked for by name, because asking [OctoGramPatchItems] for anything
     * would initialize it, and its entries read the preceding edit groups—see that file's header for
     * why the two must not reach into each other while building.
     */
    private fun octoGramSet(id: String, label: String, description: String) = PatchSet(
        id = id,
        label = label,
        description = description,
        generator = OctoGramItemGenerator(id),
    )

    /** Every OctoGram set, in the order the selection screen lists them. */
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
        FIREBASE_DATATRANSPORT,
        CRASH_REPORTER,
        PREMIUM_SETTINGS,
        EXTERNAL_BROWSER,
        HIDE_BUSINESS,
        PREMIUM_SHEETS
    )

}
