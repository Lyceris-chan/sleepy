package dev.sleepy.app.patches

import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.SmaliPatch

/**
 * Bytecode patches for OctoGram (Telegram client fork).
 *
 * Implements 1-to-1 parity with local patch definitions in:
 * - work361/patch_dex_361.py (OctoGram 3.6.1 Beta 2 / build 38275)
 * - work361/patch_premium_361.py (Paywall upsell removal)
 * - work361/patch_octolog_361.py (OctoGram logger emitters)
 * - work361/patch_logging_361.py (Diagnostic crash/event uploaders)
 * - dexwork/patch_dex.py (OctoGram 3.6.0)
 *
 * All DEX container assignments are resolved dynamically at runtime by inspecting
 * the target APK's DEX headers. Version tags ensure version-specific obfuscated
 * class names are targeted accurately without conflicting with unrelated classes.
 */
object OctoGramPatches {

    val SPONSORED_MSGS = PatchSet(
        id = "octogram_sponsored_msgs",
        label = "Block Channel Sponsored Messages",
        description = "Removes sponsored advertisement cards from Telegram public channels and neutralizes server-side ad requests in ChatActivity and MessagesController.",
        smaliPatches = listOf(
            // 3.6.1 (build 38275): ChatActivity.If -> return-void
            SmaliPatch(
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
                versionTag = "3.6.1",
                smaliPath = "a28.smali",
                methodSignature = ".method public final J1(J)Lw18;",
                replacementBody = """.method public final J1(J)Lw18;
    .locals 1

    const/4 v0, 0x0

    return-object v0
.end method"""
            ),
            // 3.6.0: ChatActivity.ss -> return-void
            SmaliPatch(
                versionTag = "3.6.0",
                smaliPath = "org/telegram/ui/o.smali",
                methodSignature = ".method public final ss(Z)V",
                replacementBody = """.method public final ss(Z)V
    .locals 0

    return-void
.end method"""
            ),
            // 3.6.0: MessagesController.sc -> return null
            SmaliPatch(
                versionTag = "3.6.0",
                smaliPath = "org/telegram/messenger/m0.smali",
                methodSignature = ".method public sc(J)Lorg/telegram/messenger/m0\$y;",
                replacementBody = """.method public sc(J)Lorg/telegram/messenger/m0${'$'}y;
    .locals 6

    const/4 v0, 0x0

    return-object v0
.end method"""
            )
        )
    )

    val PHOTO_VIEWER_ADS = PatchSet(
        id = "octogram_photo_viewer_ads",
        label = "Block PhotoViewer Interstitial Ads",
        description = "Stubs the ad presentation callback in PhotoViewer to eliminate full-screen ads and sponsored prompts when swiping through channel photos or media galleries.",
        smaliPatches = listOf(
            // 3.6.1: gpd.b(Lwz0;)V -> return-void
            SmaliPatch(
                versionTag = "3.6.1",
                smaliPath = "gpd.smali",
                methodSignature = ".method public final b(Lwz0;)V",
                replacementBody = """.method public final b(Lwz0;)V
    .locals 0

    return-void
.end method"""
            ),
            // 3.6.0: y5l.Q()V -> return-void
            SmaliPatch(
                versionTag = "3.6.0",
                smaliPath = "y5l.smali",
                methodSignature = ".method public final Q()V",
                replacementBody = """.method public final Q()V
    .locals 0

    return-void
.end method"""
            )
        )
    )

    val SEARCH_ADS = PatchSet(
        id = "octogram_search_ads",
        label = "Block Global Search Sponsored Channels",
        description = "Neutralizes the TL_contacts_sponsoredPeers response handler in search, preventing promoted channels and bots from appearing above genuine search results.",
        smaliPatches = listOf(
            // 3.6.1: s04.k0 -> reset sponsoredReqId = 0 and return
            SmaliPatch(
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
            // 3.6.0: be6.m1 -> reset sponsoredReqId = 0 and return
            SmaliPatch(
                versionTag = "3.6.0",
                smaliPath = "be6.smali",
                methodSignature = ".method public final synthetic m1(Lorg/telegram/tgnet/TLObject;)V",
                replacementBody = """.method public final synthetic m1(Lorg/telegram/tgnet/TLObject;)V
    .locals 3

    const/4 v0, 0x0

    iput v0, p0, Lbe6;->sponsoredReqId:I

    return-void
.end method"""
            )
        )
    )

    val OTA_UPDATER = PatchSet(
        id = "octogram_ota_updater",
        label = "Disable Update Check Pings",
        description = "Prevents UpdatesManager from sending network requests to GitHub for update manifests, preserving callback dispatching to prevent UI hangs while blocking update prompts.",
        smaliPatches = listOf(
            // 3.6.1: j6d.smali case :pswitch_160 in packed-switch
            SmaliPatch(
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
            // 3.6.0: hxk.o0 -> nullify e and invoke failure callback
            SmaliPatch(
                versionTag = "3.6.0",
                smaliPath = "hxk.smali",
                methodSignature = ".method public final synthetic o0(Lhxk\$i;)V",
                replacementBody = """.method public final synthetic o0(Lhxk${'$'}i;)V
    .locals 6

    const/4 v0, 0x0

    iput-object v0, p0, Lhxk;->e:Lorg/json/JSONObject;

    invoke-interface {p1}, Lhxk${'$'}i;->a()V

    return-void
.end method"""
            )
        )
    )

    val PREMIUM_UPSELL = PatchSet(
        id = "octogram_premium_upsell",
        label = "Disable Premium Upsell Paywall Screens",
        description = "Injects guards into ActionBarLayout and LaunchActivity's navigation router to drop navigation attempts to PremiumPreviewFragment, eliminating paywall nag screens.",
        smaliPatches = listOf(
            // ActionBarLayout.b(Ln16;)Z paywall drop guard
            SmaliPatch(
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
        label = "Silence OctoGram & Diagnostic Loggers",
        description = "Stubs all 12 public log emitters in OctoGram's cn8 logger and neutralizes diagnostic telemetry uploaders in PremiumPreviewFragment, r44, and a28.",
        smaliPatches = listOf(
            // cn8 public log emitters
            SmaliPatch(versionTag = "3.6.1", smaliPath = "cn8.smali", methodSignature = ".method public static a(Ljava/lang/String;)V", replacementBody = ".method public static a(Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"),
            SmaliPatch(versionTag = "3.6.1", smaliPath = "cn8.smali", methodSignature = ".method public static b(Ljava/lang/String;Ljava/lang/String;)V", replacementBody = ".method public static b(Ljava/lang/String;Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"),
            SmaliPatch(versionTag = "3.6.1", smaliPath = "cn8.smali", methodSignature = ".method public static d(Ljava/lang/String;Ljava/lang/String;)V", replacementBody = ".method public static d(Ljava/lang/String;Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"),
            SmaliPatch(versionTag = "3.6.1", smaliPath = "cn8.smali", methodSignature = ".method public static e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Exception;)V", replacementBody = ".method public static e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Exception;)V\n    .locals 0\n    return-void\n.end method"),
            SmaliPatch(versionTag = "3.6.1", smaliPath = "cn8.smali", methodSignature = ".method public static f(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V", replacementBody = ".method public static f(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V\n    .locals 0\n    return-void\n.end method"),
            SmaliPatch(versionTag = "3.6.1", smaliPath = "cn8.smali", methodSignature = ".method public static g(Ljava/lang/String;Ljava/lang/Throwable;)V", replacementBody = ".method public static g(Ljava/lang/String;Ljava/lang/Throwable;)V\n    .locals 0\n    return-void\n.end method"),
            SmaliPatch(versionTag = "3.6.1", smaliPath = "cn8.smali", methodSignature = ".method public static h(Ljava/lang/Throwable;)V", replacementBody = ".method public static h(Ljava/lang/Throwable;)V\n    .locals 0\n    return-void\n.end method"),
            SmaliPatch(versionTag = "3.6.1", smaliPath = "cn8.smali", methodSignature = ".method public static k(Ljava/lang/String;Ljava/lang/String;)V", replacementBody = ".method public static k(Ljava/lang/String;Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"),
            SmaliPatch(versionTag = "3.6.1", smaliPath = "cn8.smali", methodSignature = ".method public static m(ILjava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V", replacementBody = ".method public static m(ILjava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V\n    .locals 0\n    return-void\n.end method"),
            SmaliPatch(versionTag = "3.6.1", smaliPath = "cn8.smali", methodSignature = ".method public static n(Ljava/lang/String;)V", replacementBody = ".method public static n(Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"),
            SmaliPatch(versionTag = "3.6.1", smaliPath = "cn8.smali", methodSignature = ".method public static o(Ljava/lang/String;Ljava/lang/String;)V", replacementBody = ".method public static o(Ljava/lang/String;Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"),
            SmaliPatch(versionTag = "3.6.1", smaliPath = "cn8.smali", methodSignature = ".method public static p(Ljava/io/OutputStreamWriter;Ljava/lang/Throwable;)V", replacementBody = ".method public static p(Ljava/io/OutputStreamWriter;Ljava/lang/Throwable;)V\n    .locals 0\n    return-void\n.end method"),

            // Diagnostic uploaders in Telegram code
            SmaliPatch(versionTag = "3.6.1", smaliPath = "org/telegram/ui/PremiumPreviewFragment.smali", methodSignature = ".method public static A3()V", replacementBody = ".method public static A3()V\n    .locals 0\n    return-void\n.end method"),
            SmaliPatch(versionTag = "3.6.1", smaliPath = "org/telegram/ui/PremiumPreviewFragment.smali", methodSignature = ".method public static B3(II)V", replacementBody = ".method public static B3(II)V\n    .locals 0\n    return-void\n.end method"),
            SmaliPatch(versionTag = "3.6.1", smaliPath = "org/telegram/ui/PremiumPreviewFragment.smali", methodSignature = ".method public static C3(Ljava/lang/String;)V", replacementBody = ".method public static C3(Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method"),
            SmaliPatch(versionTag = "3.6.1", smaliPath = "r44.smali", methodSignature = ".method public static R0(Z)V", replacementBody = ".method public static R0(Z)V\n    .locals 0\n    return-void\n.end method"),
            SmaliPatch(versionTag = "3.6.1", smaliPath = "a28.smali", methodSignature = ".method public final b3()V", replacementBody = ".method public final b3()V\n    .locals 0\n    return-void\n.end method")
        )
    )

    private fun firebaseRegistrarPatch(id: String, label: String, smaliPath: String, description: String) = PatchSet(
        id = id,
        label = label,
        description = description,
        smaliPatches = listOf(
            SmaliPatch(
                versionTag = null, // Universal across 3.6.0 and 3.6.1
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
        "Neutralize Firebase A/B Testing",
        "com/google/firebase/abt/component/AbtRegistrar.smali",
        "Neutering AbtRegistrar.getComponents() in bytecode causes it to register zero components at startup, completely disabling Firebase A/B experiment telemetry."
    )

    val FIREBASE_REMOTE_CONFIG = firebaseRegistrarPatch(
        "octogram_firebase_remoteconfig",
        "Neutralize Firebase Remote Config",
        "com/google/firebase/remoteconfig/RemoteConfigRegistrar.smali",
        "Stubs RemoteConfigRegistrar.getComponents() to return an empty list, stopping the app from downloading server-side experiment flags and telemetry parameters."
    )

    val FIREBASE_REMOTE_CONFIG_KTX = firebaseRegistrarPatch(
        "octogram_firebase_remoteconfig_ktx",
        "Neutralize Firebase Remote Config KTX",
        "com/google/firebase/remoteconfig/FirebaseRemoteConfigKtxRegistrar.smali",
        "Prevents the Kotlin extension wrapper for RemoteConfig from registering any lifecycle components."
    )

    val FIREBASE_DATATRANSPORT = firebaseRegistrarPatch(
        "octogram_firebase_datatransport",
        "Neutralize Firebase DataTransport",
        "com/google/firebase/datatransport/TransportRegistrar.smali",
        "Stubs Google Play Services DataTransport registrar in DEX so no telemetry events can be queued or batched to Google backends."
    )

    val ALL = listOf(
        SPONSORED_MSGS,
        PHOTO_VIEWER_ADS,
        SEARCH_ADS,
        OTA_UPDATER,
        PREMIUM_UPSELL,
        OCTO_LOGGER,
        FIREBASE_ABT,
        FIREBASE_REMOTE_CONFIG,
        FIREBASE_REMOTE_CONFIG_KTX,
        FIREBASE_DATATRANSPORT
    )
}
