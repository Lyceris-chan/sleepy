package dev.sleepy.app.patches

import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.SmaliPatch

object OctoGramPatches {

    val SPONSORED_MSGS = PatchSet(
        id = "octogram_sponsored_msgs",
        label = "Block Channel Sponsored Messages",
        description = "Removes sponsored ad rows from Telegram channel message feeds and disables server-side sponsored message requests.",
        smaliPatches = listOf(
            // OctoGram 3.6.0 (classes4.dex: ChatActivity.ss -> return-void)
            SmaliPatch(
                dexName = "classes4.dex",
                smaliPath = "org/telegram/ui/o.smali",
                methodSignature = ".method public final ss(Z)V",
                replacementBody = """.method public final ss(Z)V
    .locals 0

    return-void
.end method"""
            ),
            // OctoGram 3.6.0 (classes3.dex: MessagesController.sc -> return null)
            SmaliPatch(
                dexName = "classes3.dex",
                smaliPath = "org/telegram/messenger/m0.smali",
                methodSignature = ".method public sc(J)Lorg/telegram/messenger/m0\$y;",
                replacementBody = """.method public sc(J)Lorg/telegram/messenger/m0${'$'}y;
    .locals 6

    const/4 v0, 0x0

    return-object v0
.end method"""
            ),
            // OctoGram 3.6.1 (classes3.dex: ChatActivity.If -> return-void)
            SmaliPatch(
                dexName = "classes3.dex",
                smaliPath = "org/telegram/ui/e6.smali",
                methodSignature = ".method public final If()V",
                replacementBody = """.method public final If()V
    .locals 0

    return-void
.end method"""
            ),
            // OctoGram 3.6.1 (classes3.dex: MessagesController.J1 -> return null)
            SmaliPatch(
                dexName = "classes3.dex",
                smaliPath = "a28.smali",
                methodSignature = ".method public J1(J)Lw18;",
                replacementBody = """.method public J1(J)Lw18;
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
            // OctoGram 3.6.0
            SmaliPatch(
                dexName = "classes3.dex",
                smaliPath = "y5l.smali",
                methodSignature = ".method public final Q()V",
                replacementBody = """.method public final Q()V
    .locals 0

    return-void
.end method"""
            ),
            // OctoGram 3.6.1
            SmaliPatch(
                dexName = "classes3.dex",
                smaliPath = "gpd.smali",
                methodSignature = ".method public final b(Lwz0;)V",
                replacementBody = """.method public final b(Lwz0;)V
    .locals 0

    return-void
.end method"""
            )
        )
    )

    val SEARCH_ADS = PatchSet(
        id = "octogram_search_ads",
        label = "Block Global Search Sponsored Channels",
        description = "Neutralizes the TL_contacts_sponsoredPeers handler in the search adapter, preventing commercial sponsored channels and promoted bots from appearing above real search results.",
        smaliPatches = listOf(
            // OctoGram 3.6.0
            SmaliPatch(
                dexName = "classes4.dex",
                smaliPath = "be6.smali",
                methodSignature = ".method public final synthetic m1(Lorg/telegram/tgnet/TLObject;)V",
                replacementBody = """.method public final synthetic m1(Lorg/telegram/tgnet/TLObject;)V
    .locals 3

    const/4 v0, 0x0

    iput v0, p0, Lbe6;->sponsoredReqId:I

    return-void
.end method"""
            ),
            // OctoGram 3.6.1
            SmaliPatch(
                dexName = "classes3.dex",
                smaliPath = "s04.smali",
                methodSignature = ".method public final synthetic k0(Lorg/telegram/tgnet/TLObject;)V",
                replacementBody = """.method public final synthetic k0(Lorg/telegram/tgnet/TLObject;)V
    .locals 3

    const/4 v0, 0x0

    iput v0, p0, Ls04;->sponsoredReqId:I

    return-void
.end method"""
            )
        )
    )

    val OTA_UPDATER = PatchSet(
        id = "octogram_ota_updater",
        label = "Disable Update Check Pings",
        description = "Prevents OctoGram's UpdatesManager from sending periodic network pings to external servers and GitHub for app updates, avoiding nag prompts that would overwrite this modded install.",
        smaliPatches = listOf(
            // OctoGram 3.6.0
            SmaliPatch(
                dexName = "classes3.dex",
                smaliPath = "hxk.smali",
                methodSignature = ".method public final synthetic o0(Lhxk\$i;)V",
                replacementBody = """.method public final synthetic o0(Lhxk${'$'}i;)V
    .locals 6

    const/4 v0, 0x0

    iput-object v0, p0, Lhxk;->e:Lorg/json/JSONObject;

    invoke-interface {p1}, Lhxk${'$'}i;->a()V

    return-void
.end method"""
            ),
            // OctoGram 3.6.1
            SmaliPatch(
                dexName = "classes3.dex",
                smaliPath = "uid.smali",
                methodSignature = ".method public final synthetic M(Lpid;)V",
                replacementBody = """.method public final synthetic M(Lpid;)V
    .locals 6

    const/4 v0, 0x0

    iput-object v0, p0, Luid;->e:Lorg/json/JSONObject;

    invoke-interface {p1}, Lpid;->a()V

    return-void
.end method"""
            )
        )
    )

    private fun firebaseRegistrarPatch(id: String, label: String, smaliPath: String, description: String) = PatchSet(
        id = id,
        label = label,
        description = description,
        smaliPatches = listOf(
            // 3.6.0: classes3.dex
            SmaliPatch(
                dexName = "classes3.dex",
                smaliPath = smaliPath,
                methodSignature = ".method public getComponents()Ljava/util/List;",
                replacementBody = """.method public getComponents()Ljava/util/List;
    .locals 1

    invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;

    move-result-object v0

    return-object v0
.end method"""
            ),
            // 3.6.1: classes.dex
            SmaliPatch(
                dexName = "classes.dex",
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
        FIREBASE_ABT,
        FIREBASE_REMOTE_CONFIG,
        FIREBASE_REMOTE_CONFIG_KTX,
        FIREBASE_DATATRANSPORT
    )
}
