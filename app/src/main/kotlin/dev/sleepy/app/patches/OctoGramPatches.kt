package dev.sleepy.app.patches

import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.SmaliPatch

object OctoGramPatches {

    val SPONSORED_MSGS = PatchSet(
        id = "octogram_sponsored_msgs",
        label = "No Sponsored Messages",
        description = "Removes sponsored ads from channel message feeds and suppresses server-side sponsored fetches.",
        smaliPatches = listOf(
            SmaliPatch(
                dexName = "classes4.dex",
                smaliPath = "org/telegram/ui/o.smali",
                methodSignature = ".method public final ss(Z)V",
                replacementBody = """.method public final ss(Z)V
    .locals 0

    return-void
.end method"""
            ),
            SmaliPatch(
                dexName = "classes3.dex",
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
        label = "No Photo Viewer Ads",
        description = "Removes sponsored advertisements between media items in PhotoViewer.",
        smaliPatches = listOf(
            SmaliPatch(
                dexName = "classes3.dex",
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
        label = "No Search Sponsored Peers",
        description = "Suppresses paid sponsored channel placements in global search results.",
        smaliPatches = listOf(
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
            )
        )
    )

    val OTA_UPDATER = PatchSet(
        id = "octogram_ota_updater",
        label = "Disable Updater Pings",
        description = "Stops periodic background pings to external update servers and GitHub raw assets.",
        smaliPatches = listOf(
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
            )
        )
    )

    private fun firebaseRegistrarPatch(id: String, label: String, smaliPath: String) = PatchSet(
        id = id,
        label = label,
        description = "Neutering Firebase ComponentRegistrar in DEX so telemetry components never register.",
        smaliPatches = listOf(
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
            )
        )
    )

    val FIREBASE_ABT = firebaseRegistrarPatch(
        "octogram_firebase_abt", "Neutralize Firebase A/B Testing",
        "com/google/firebase/abt/component/AbtRegistrar.smali"
    )
    val FIREBASE_REMOTE_CONFIG = firebaseRegistrarPatch(
        "octogram_firebase_remoteconfig", "Neutralize Firebase RemoteConfig",
        "com/google/firebase/remoteconfig/RemoteConfigRegistrar.smali"
    )
    val FIREBASE_REMOTE_CONFIG_KTX = firebaseRegistrarPatch(
        "octogram_firebase_remoteconfig_ktx", "Neutralize Firebase RemoteConfig KTX",
        "com/google/firebase/remoteconfig/FirebaseRemoteConfigKtxRegistrar.smali"
    )
    val FIREBASE_DATATRANSPORT = firebaseRegistrarPatch(
        "octogram_firebase_datatransport", "Neutralize Firebase DataTransport",
        "com/google/firebase/datatransport/TransportRegistrar.smali"
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
