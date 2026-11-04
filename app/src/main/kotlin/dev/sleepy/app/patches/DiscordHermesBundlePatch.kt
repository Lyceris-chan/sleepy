package dev.sleepy.app.patches

/**
 * The Discord JavaScript bundle patch set for Discord 354.1 (versionCode 354201).
 *
 * Each entry names one function of one exact bundle and the body that replaces it: constant
 * stubs carried by id, promise stubs re-assembled against this bundle's string indices, and
 * size-preserving body edits. A declared size that does not match the bundle makes the patcher
 * skip the entry rather than write it. The game-activity and declutter entries added in 3.8.0
 * are described in CHANGELOG.md.
 *
 * The retarget from 353.2.0 lists a function once when several old ids resolve to it, and
 * retired thirteen old entries: ten have no 354.1 counterpart, and three were replaced by the
 * added Manage Subscriptions route and profile-tab edits.
 *
 * [dev.sleepy.app.engine.HermesBundleApplicationTest] applies the table to the build it names
 * and reads every patched function back with a real Hermes disassembler.
 */
object DiscordHermesBundlePatch {

    /** Byte length of the bundle this table was extracted from. */
    const val TARGET_BUNDLE_SIZE = 50882260

    /** Function count of that bundle. */
    const val TARGET_FUNCTION_COUNT = 141898

    /**
     * One function to replace.
     *
     * @param functionId The bundle's identifier for the function.
     * @param name The function's name, or an empty string when the bundle does not name it.
     * @param originalSize The function's bytecode size in the unpatched bundle; the
     *   replacement is written over it and any remainder padded with AsyncBreakCheck.
     * @param replacementHex The replacement body, hex encoded.
     */
    data class FunctionPatch(
        val functionId: Int,
        val name: String,
        val originalSize: Int,
        val replacementHex: String
    ) {
        /** The replacement body, decoded from the hex in [replacementHex]. */
        val replacement: ByteArray get() = replacementHex.hexToBytes()
    }

    /** Every function in the set, in the order the table applies them. */
    val PATCHES: List<FunctionPatch> = listOf(
        FunctionPatch(
            1372, "", 699,
            "40040389070289050689060793018408010a66370401083d09480c09001e00440b0c016c900a3300020804000000a468080070080b0c050a085e0806006e08070108450a08027e613704000a5e0806016e08070108450b08033a69820d08040b0b66100c08900bfa558408010c665c0c080b00900b1cba8408010d665c0c080b00900b28ba8408010e665c0c080b00900bb1aa8408010f665c0c080b00900b7a3e84080410665c0c080b00900b31ba84080111665c0c080b00900b912984080112665c0c080b00900b8f9284080113665c0c080b00900b95be84080114665c0c080b00900b96be84080115665c0c080b00900b9abe84080116665c0c080b00900b44be84080117665c0c080b00900b146a84080118665c0c080b00910b4fa2010084080119665c0c080b00910beb0002008408011a665c0c080b00910b0ee402008408011b665c0c080b00910b0fe402008408011c665c0c080b00910b09e402008408011d665c0c080b00910bab4f01008408011e665c0c080b00910b6a0201008408011f665c0c080b00910b1de4020084080120665c0c080b00910b8d0f010084080121665c0c080b00910bc6a1010084080122665c0c080b00910be7c4010084080123665c0c080b00910bc7a1010084080124665c0c080b00910b67b4010084080125665c0c080b00910baf08010084080126665c0c080b00910b869c010084080127665c0c080b00910bd2ae020084080128665c0c080b003704020d4208040200000039080001920b3708010b3f040d870c040d0410110c6d04040d0188040c047c0b37080104960256040200a1e1940216030a02930cb00903450c0a056d0c3708000ccf1a0c02450b0c0617bacf100b02850a081f3e01006e0a0b0c0a4809090715a08c0080ee3600850808203e01006f08090108005e0606026e08070106440708087f910687a702006e060708064b0504016b007601"
        ),
        FunctionPatch(
            8685, "", 343,
            "4003088906023703000689080337030108890406890507370302053d07480b07001e00440a0b016c90093300020704000000a468080070070a0b0409075e07050093016e0706010745070702b63e370303070209a8090000ae3409005e0705016e07080107450707031361450707043b598b000f21000007520900018c00d0070000520900005e0705026e07080107860a0705100f0a100e096b07070288070a07370304075e0705026e07080107860a0705100f0a100e096b07070288070a07370305075e0705026e0b0801070407100e07100d09730a2e039602560702006254860a0b05100f0a100e076b070b0288070a07370306075e0705026e0708010786080705100f08100e096b07070288070807370307075e0505096e07060105440607067f9105820a03006e05060705840503ecb94b040501a6dd840503edb94d04050206e10400840503eeb94b040503a5dd840303efb94d0403044eb704007e7e7601"
        ),
        FunctionPatch(16108, "", 186, "93007e7e7600"),
        FunctionPatch(
            17026, "", 288,
            "4008078904023708000489050337080105890206890307370802033d01480901001e00440709016c90063300020104000000a4680800700107090206015e06030093008901046e01010006370803015e0103016e01050001370804015e0103026e01050001370805015e0103036e01040001450901026f2b5e0103046e01040001440101039c370806015e01030a6e060400014505060480a70201261d0000ad68080085070838100100520107005e07030b6e0704000745070705b7635201070285070839100100520107038507083a100100520107040207b71b0000a7320400450909063f0e520709008508083b10010052070801520107058509085610010056010906b9ec6e010506015e03030d6e05040003440405077f9103511303006e030405034b0201006b007e7e7e7600"
        ),
        FunctionPatch(
            17032, "", 205,
            "40080289040237080004890206890307370801033d01480701001e00440607016c90053300020104000000a4680800700106070205015e01030093006e0104000145070102933a5e0103016e01040001450901036f2b5e0103026e060400014505060480a702016b1c00002d330400850a084410010052010a00450707053f0e520107010207b71b0000a732040045090906824552070900850808451001005207080152010702850a085610010056010a03b9ec6e010506015e0303056e05040003440405077f9103e4a602006e030405034b0201006b007e7e7e7600"
        ),
        FunctionPatch(
            17039, "", 199,
            "40080289040237080004890206890307370801033d01480701001e00440607016c90053300020104000000a4680800700106070205015e01030093006e01040001450901026f2b5e0103016e060400014505060380a702012a1c0000313304008507084e100100520107005e0703036e0704000745070704692e520107020207b71b0000a732040045090905701a520709008508084f10010052070801520107038509085610010056010904b9ec6e010506015e0303056e05040003440405067f91034c1303006e030405034b0201006b007e7e7e7600"
        ),
        FunctionPatch(
            17970, "", 217,
            "4007038904023707000489010337070101890206890307370702033d01480801001e00440608016c90053300020104000000a4680800700106080205015e01030093006e01040001450901026f2b5e0103016e060400014505060380a70201531f00009e69080085080758180100520108005e0803036e08040008450808043070520108020208b71b0000a7320400450909056736520809008509075918010052080901520108038507075a180100520107048509075610010056010905b9ec6e010506015e0303076e05040003440405067f9103d11203006e030405034b0201006b007e7e7e7600"
        ),
        FunctionPatch(20404, "report", 203, "93007e7e7e7600"),
        FunctionPatch(20433, "recordStart", 104, "93007e7e7e7600"),
        FunctionPatch(20435, "recordEnd", 201, "93007e7e7e7600"),
        FunctionPatch(20437, "set", 164, "93007600"),
        FunctionPatch(20445, "record", 148, "93007e7e7e7600"),
        FunctionPatch(20476, "resumeTracing", 150, "93007e7e7600"),
        FunctionPatch(20477, "mark", 124, "93007600"),
        FunctionPatch(20478, "markAndLog", 118, "93007e7e7600"),
        FunctionPatch(20479, "addImportLogDetail", 86, "93007e7e7600"),
        FunctionPatch(20480, "markWithDelta", 107, "93007e7e7e7600"),
        FunctionPatch(20481, "markAt", 187, "93007e7e7e7600"),
        FunctionPatch(20482, "addDetail", 130, "93007e7e7e7600"),
        FunctionPatch(20485, "setServerTrace", 28, "93007600"),
        FunctionPatch(22017, "handleFingerprint", 345, "93007e7600"),
        FunctionPatch(33136, "getMetricsSampleRate", 81, "8b00007600"),
        FunctionPatch(33138, "shouldCollectMetrics", 86, "96007e7e7600"),
        FunctionPatch(22882, "addBreadcrumb", 242, "93007e7e7600"),
        FunctionPatch(24984, "disableActivityHostLeftNitroUpsell", 23, "96007e7600"),
        FunctionPatch(25572, "setUser", 104, "93007600"),
        FunctionPatch(25573, "clearUser", 78, "93007e7e7600"),
        FunctionPatch(25574, "setTags", 48, "93007600"),
        FunctionPatch(25575, "setExtra", 48, "93007600"),
        FunctionPatch(25576, "captureException", 87, "93007e7e7e7600"),
        FunctionPatch(25577, "captureCrash", 161, "93007e7e7600"),
        FunctionPatch(25578, "captureMessage", 90, "93007e7e7600"),
        FunctionPatch(25579, "addFeatureFlag", 106, "93007e7e7600"),
        FunctionPatch(25580, "addBreadcrumb", 54, "93007e7e7600"),
        FunctionPatch(25582, "crash", 27, "93007e7e7e7600"),
        FunctionPatch(25583, "triggerMemoryWarning", 27, "93007e7e7e7600"),
        FunctionPatch(25584, "markCrashHandled", 73, "93007e7e7600"),
        FunctionPatch(25591, "initSentry", 43, "93007e7e7e7600"),
        FunctionPatch(25650, "debugLogEvent", 79, "93007e7e7e7600"),
        FunctionPatch(25713, "track", 286, "93007600"),
        FunctionPatch(25721, "trackNetworkAction", 135, "93007e7e7e7600"),
        FunctionPatch(27751, "parseAvatarDecorationData", 189, "94007e7600"),
        FunctionPatch(27796, "isZoomedExperimentEnabled", 35, "8b00007e7e7600"),
        FunctionPatch(34462, "BadgesCoachmarkBindings", 155, "93007e7600"),
        FunctionPatch(34461, "BadgesCoachmarkRiveInner", 244, "94007e7600"),
        FunctionPatch(34463, "BadgesCoachmarkRiveWithBoundary", 89, "94007e7600"),
        FunctionPatch(34406, "ageEligibleForPremiumUpsell", 90, "96007e7600"),
        FunctionPatch(36098, "trackWithMetadata", 365, "93007e7600"),
        FunctionPatch(36779, "applyVideoQualityMode", 88, "93007e7600"),
        FunctionPatch(
            36800, "getVideoQuality", 297,
            "89040089030145020400c3d0450102019f866e0801020344010402c24501010321ee44020104b645010805094e210a020144010402c24501010321ee44020106af45010805094e2107020145010407df8ab00b01450608086cb6ae0845060809f9d93401003b0201060201962d00009e6908000405100c05100b0873082e03560506006cb6520105000205140500002d3304008c0680020000520506008c0668010000520506018c061800000052050602520105013d054809050d1b0044080906af44060402c24506060e22ee6f0608090a06520106024806050d1b0044050606af44040402c24504040e22ee8c0740420f006f040506070452010403520103048603020f930d100c016b0102027e7601"
        ),
        FunctionPatch(
            36805, "MediaSinkWantsLadder", 145,
            "3403008904019301d50804013b0403003e0244020200c845040401e3b744050402fd44040403939700bf490500bf4504000502028c04008403004b020400ddb73b05030445040504939145030205ddb76e030405034b020301c3d045040506cb9a45030207c3d06e030405034b0203023ba0760276023d02480402080e008603040091066f9002001007036b020402880203027902"
        ),
        FunctionPatch(38240, "increment", 94, "93007600"),
        FunctionPatch(38241, "distribution", 112, "93007e7e7600"),
        FunctionPatch(38242, "_flush", 182, "93007e7e7e7600"),
        FunctionPatch(39065, "StageBoostingActionSheet", 1179, "94007e7600"),
        FunctionPatch(39075, "PremiumGuildSubscribeModal", 299, "94007e7600"),
        FunctionPatch(41877, "hideInlineGuildCapUpsell", 56, "93007e7600"),
        FunctionPatch(41991, "hasSocialLayerStorefront", 204, "96007600"),
        FunctionPatch(42015, "isOnSocialLayerStorefrontPage", 159, "96007e7600"),
        FunctionPatch(42256, "useGuildShopVisibleInGuild", 154, "96007600"),
        FunctionPatch(42257, "isGuildShopVisibleInGuild", 96, "96007600"),
        FunctionPatch(42258, "useGuildShopPreviewVisible", 272, "96007600"),
        FunctionPatch(42965, "GuildBoostingSubscribeButton", 613, "94007e7600"),
        FunctionPatch(43016, "PremiumModal", 823, "94007e7600"),
        FunctionPatch(43099, "openPremiumPlanSelectionActionSheet", 167, "93007e7600"),
        FunctionPatch(43104, "PremiumPlanSelectionActionSheet", 3542, "94007e7600"),
        FunctionPatch(43103, "PremiumPlanSelectionActionSheetCTA", 278, "94007e7600"),
        FunctionPatch(43106, "PremiumPlanSelectionActionSheetWithOrderCTX", 684, "94007e7600"),
        FunctionPatch(43153, "PremiumPlanActionSheetHeader", 890, "94007e7600"),
        FunctionPatch(43159, "useShouldShowPremiumTrialUserSettingsAvatarBadge", 114, "96007e7600"),
        FunctionPatch(43355, "handleAppStateChange", 158, "93007e7e7600"),
        FunctionPatch(43356, "writeExistingEventStorage", 37, "93007e7600"),
        FunctionPatch(43357, "track", 37, "93007e7600"),
        FunctionPatch(43359, "", 38, "93007e7e7600"),
        FunctionPatch(43360, "", 137, "93007e7600"),
        FunctionPatch(43361, "", 18, "93007e7e7600"),
        FunctionPatch(43388, "initSessionHeartbeatScheduler", 292, "93007600"),
        FunctionPatch(43544, "recordChannelFetchStart", 181, "93007e7600"),
        FunctionPatch(43545, "recordChannelFetchedLocal", 200, "93007600"),
        FunctionPatch(43546, "recordChannelFetchedNetwork", 200, "93007600"),
        FunctionPatch(45503, "surveyFetch", 196, "3d0048010000240045000100d1006c0000017600"),
        FunctionPatch(47292, "DoubleTapNitroAlert", 532, "94007e7600"),
        FunctionPatch(48277, "useShouldDisplayGuildTag", 247, "96007e7600"),
        FunctionPatch(48278, "shouldDisplayGuildTag", 201, "96007e7600"),
        FunctionPatch(48465, "useIsShopThisLookMobileEnabled", 39, "96007e7600"),
        FunctionPatch(48471, "_maybeFetchProductsBySkuIds", 61, "3d0048010000240045000100d1006c0000017e7600"),
        FunctionPatch(48497, "ProfileFrame", 107, "94007600"),
        FunctionPatch(48538, "useBadges", 243, "080000007e7600"),
        FunctionPatch(48843, "fetchGuildAffinities", 111, "3d0048010000240045000100d1006c0000017e7e7e7600"),
        FunctionPatch(50106, "isPlayingGameActivity", 120, "96007600"),
        FunctionPatch(50111, "GuildTagBadge", 160, "94007e7e7e7600"),
        FunctionPatch(50112, "BaseGuildTagChiplet", 311, "94007e7600"),
        FunctionPatch(50113, "GuildTagChiplet", 301, "94007e7600"),
        FunctionPatch(50529, "GameProfileShopCarousel", 105, "93007600"),
        FunctionPatch(37621, "trackImpression", 398, "93007e7e7600"),
        FunctionPatch(
            50618, "_fetchStorefrontPricesForApplicationId", 61,
            "3d0048010000240045000100d1006c0000017e7600"
        ),
        FunctionPatch(
            50619, "_fetchStorefrontPricesForSkuIds", 61,
            "3d0048010000240045000100d1006c0000017e7600"
        ),
        FunctionPatch(50684, "WrappedProfileEffect", 220, "94007600"),
        FunctionPatch(50712, "Nameplate", 87, "94007600"),
        FunctionPatch(50717, "SlayerStorefrontItemCard", 649, "94007e7600"),
        FunctionPatch(
            50897, "_maybeFetchCollectionsWithProducts", 61,
            "3d0048010000240045000100d1006c0000017e7600"
        ),
        FunctionPatch(
            50969, "YouBannerDecorations", 1610,
            "40060989050145070500f5c037060007450c050166da3706010c451505026ada451805034521461205042cc90400451405054edd9301d50614019714340500390602013906030139060401390605013906060139060701390608013b0905068508059e7401006e100901083b0e05003b0805025e09080f6e0d0e0109440b0d06f63b090507080a01005a0a09008509059f7401006f110b0d0a093b0905015e0a08106e0d09010a940a1603110a930bb00803440b1107526e0f0d010b5e0b08116e0d09010b020b58380000a7320400520b1100520b0f016e0d0d010b45190d08ac86450b0d0957b3450f0d0a05e55e0d08126e130e010d4511130b89ed020d513800002d330400520d1900520d0b01520d0f026e1111130d450d110cd05f4513110dc3024511110e23c2370602115e1608136e170e01164516170f0d046e16161719b2151618030a0b940bb20f0318030a0f940bb20603100b0d3706030b3b0f050344160f10f3080d02005a0d11005a0d0b01850b06a07401006f21160f0b0d3b0b050c9323102214102011101f136a0d0b055e0b08156e110e010b450b1111bc206c140b115e0b08166e110e010b450b1112867691190ab202006e130b11195e0b080a6e110e010b450b111383ed6c0b0b11440b0b14a797001c030b005e0b080b6e110e010b450b11157bed3b1b05096e0b0b111b18040a0b5e0b080c6e160e010b4511161684285e0b080d6e0b0e010b450b0b173c44450b0b18bb286e0b11160b13020bb0060b100204b00603100302390604035e0b08176e0b09010b6c0b0b0145160b19df0537060516460b0b1aecfe04003706060b5e1108186e170e01114511171ba3bc6c1711175e1108196e110901116e111101191602110a9319b009024519111c90385e11080b6e1a0e011145111a157bed6e11111a1b18020a1139060702441a0f1df1081103005a1103005a1107015a110202850706a17401006f071a0f0711441a0f1df1081101005a110c00850c06a27401006f111a0f0c11441a0f1df1080c02005a0c16005a0c0b01850b06a37401006f1b1a0f0b0c910b3df20200940cd51e0b193b1c050a5e0b081d6e1a09010b9019e36d040b700c1c011a0b19080b05005a0b0c00940cb2700c3b1a050a5e17081e6e1909011702175e3c0000313304005e1c081f6e1c0e011c451c1c1e5e6a52171c005e1c080e6e1c0e011c441e1c1f99441d1e20e35e1c080e6e1c0e011c441c1c210a451c1c22565b6e1c1d1e1c52171c0152171b02521716039016764d700c1a011917165a0b0c013b0c050a5e1608206e1709011602165f3c0000a732040052161800521615019015366870150c011716155a0b1502b20b14940ab39b000000135e13081e6e140901130213603c0000313304005e1508216e150e011545151523b763521315005e15080e6e150e01154417151f9944161720e35e15080e6e150e0115441515210a45151524de366e15161715521315015e15080e6e150e01154417151f9944161720e35e15080e6e150e0115441515210a45151524de366e1516171552131502521311039011b7da700a0c011413115a0b0a035e0a081e6e1109010a020a613c000010b41300520a12005e1208226e120e0112451212254961520a12015e12080e6e120e01124414121f9944131420e35e12080e6e120e0112441212210a45121226b00a6e12131412520a1202520a0703520a03049007ca8570070c01110a075a0b0704440a0b2736850701a47401006e110a0b073b070504020a9425000051f72c00460b0d2845000500520a0b005e0b08236e0b0e010b450e0b294478010bae13e9de520b1100700b0c010e0b10520a0b026f0e0c01070a450a0d2a3a5044100a2b5837060810440b0f10f3080a01005a0a1000850606a57401006f210b0f060a3b06050b02059425000051f72c00460b0d2cff0b050052050b005e0808246e090901080108af01e9de450b0d2a3a5052080b00102208730a2e03900abd1656080a0073226f090c010908080802005a08090002099c250000a7320400460a0d2d2a8c040052090a00020a682c00009c370e00450b0d2e323b520a0b006f0b0c01070a080a03005a0a0b00020b9c250000a7320400460f0d2f4d3c0500520b0f00520b0e016f0b0c01070b5a0a0b01020b682c00009c370e00450d0d2e323b520b0d006f0b0c01070b5a0a0b0252090a016f09060107095a080901520508026f05060107057605"
        ),
        // Verification pass: the second Quests activity-panel variant, the generic form
        // Divider, and the empty server bar's onboarding hint.
        FunctionPatch(51310, "useShouldShowQuestsActivityPanelItem", 257, "8b00007600"),
        FunctionPatch(51322, "_fetchCurrentQuests", 61, "3d0048010000240045000100d1006c0000017e7600"),
        FunctionPatch(51323, "_sendHeartbeat", 61, "3d0048010000240045000100d1006c0000017e7600"),
        FunctionPatch(51332, "_fetchClaimedQuests", 61, "3d0048010000240045000100d1006c0000017e7600"),
        FunctionPatch(51333, "_fetchQuestToDeliver", 61, "3d0048010000240045000100d1006c0000017e7600"),
        FunctionPatch(51334, "_fetchEarnedQuestToDeliver", 61, "3d0048010000240045000100d1006c0000017e7600"),
        FunctionPatch(51873, "handleShowUpsellAlert", 842, "93007e7600"),
        FunctionPatch(51875, "maybeOpenPremiumUpsellActionSheet", 554, "96007e7600"),
        FunctionPatch(51876, "openPremiumUpsellActionSheet", 107, "93007e7e7600"),
        FunctionPatch(51878, "PremiumUpsellHero", 403, "94007e7600"),
        FunctionPatch(51879, "PremiumUpsellActionSheet", 3363, "94007e7600"),
        FunctionPatch(51916, "ScheduledMessageNitroUpsellBar", 331, "94007e7600"),
        FunctionPatch(52266, "SuperReactionCoachmarkActionSheet", 504, "94007e7600"),
        FunctionPatch(52267, "openPremiumModal", 82, "93007e7e7600"),
        FunctionPatch(52318, "PremiumUpsellTooltipActionSheet", 649, "94007e7600"),
        FunctionPatch(52400, "handleShowEmojiUpsellAlert", 95, "93007e7600"),
        FunctionPatch(52401, "RoleSubscriptionEmojiUpsellAlert", 371, "94007e7600"),
        FunctionPatch(52405, "GlobalEmojiUpsell", 553, "94007e7600"),
        FunctionPatch(52406, "AnimatedEmojiUpsell", 251, "94007e7600"),
        FunctionPatch(52407, "PremiumGuildIdentityUpsell", 264, "94007e7600"),
        FunctionPatch(52408, "CustomProfilesUpsell", 219, "94007e7600"),
        FunctionPatch(52409, "CustomAppIconsUpsell", 316, "94007e7600"),
        FunctionPatch(52410, "LongerMessageUpsell", 288, "94007e7600"),
        FunctionPatch(52411, "GuildCapUpsell", 267, "94007e7600"),
        FunctionPatch(52412, "UploadUpsell", 332, "94007e7600"),
        FunctionPatch(52413, "PremiumUpsellAlert", 1439, "94007e7600"),
        FunctionPatch(52447, "EmojiPickerListPremiumSearchUpsell", 602, "94007e7600"),
        FunctionPatch(52449, "PremiumExpressionPickerSearchUpsell", 268, "94007e7600"),
        FunctionPatch(52467, "PremiumFeatureUpsellPill", 1290, "94007e7600"),
        FunctionPatch(52469, "PremiumFeatureUpsell", 289, "94007e7600"),
        FunctionPatch(53085, "NitroUpsellButton", 221, "93007e7600"),
        FunctionPatch(53092, "StickersPremiumUpsellAlert", 790, "94007e7600"),
        FunctionPatch(53100, "StickerPickerPremiumSearchUpsell", 450, "94007e7600"),
        FunctionPatch(53912, "openSocialLayerStorefrontUnsupportedOnMobileAlert", 139, "93007e7600"),
        FunctionPatch(53913, "openSocialLayerStorefrontProductDetailsModal", 101, "93007e7600"),
        FunctionPatch(53914, "closeSocialLayerStorefrontProductDetailsModal", 39, "93007e7600"),
        FunctionPatch(53915, "openSocialLayerStorefrontGiftModal", 168, "93007e7600"),
        FunctionPatch(53916, "closeSocialLayerStorefrontGiftModal", 39, "93007e7600"),
        FunctionPatch(
            53928, "_fetchSocialLayerStorefrontConfig", 61,
            "3d0048010000240045000100d1006c0000017e7600"
        ),
        FunctionPatch(
            53932, "fetchSocialLayerStorefrontSkuForApplication", 49,
            "3d0048010000240045000100d1006c0000017e7600"
        ),
        FunctionPatch(53947, "SocialLayerStorefrontProductDetailsModal", 3069, "94007e7600"),
        FunctionPatch(53948, "WrappedSocialLayerStorefrontProductDetailsModal", 84, "94007e7600"),
        FunctionPatch(53997, "SocialLayerStorefrontGiftModal", 664, "94007e7600"),
        FunctionPatch(63732, "SocialLayerStorefrontGiftPurchaseSection", 785, "94007e7600"),
        FunctionPatch(54076, "isGameActivity", 57, "96007600"),
        FunctionPatch(54082, "isListeningOnSpotify", 169, "8b00007e7e7e7600"),
        FunctionPatch(54103, "LeaderboardWinnerBadge", 324, "94007e7600"),
        FunctionPatch(54211, "openFavoritesGuildLimitUpsell", 82, "93007e7600"),
        FunctionPatch(54212, "FavoritesGuildUpsellSheet", 519, "94007e7600"),
        FunctionPatch(54214, "PromoSheet", 468, "94007e7600"),
        FunctionPatch(56116, "OrbsBadgeCoachmarkImg", 119, "94007e7600"),
        FunctionPatch(56117, "OrbsBadgeCoachmark", 67, "93007e7600"),
        FunctionPatch(56120, "TieredTenureBadgeCoachmark", 250, "93007e7600"),
        FunctionPatch(56161, "BadgeDetailsSheetContent", 2299, "94007e7600"),
        FunctionPatch(56162, "BadgeDetailsPage", 268, "94007e7600"),
        FunctionPatch(56163, "BadgeDetailsSheet", 963, "94007e7600"),
        FunctionPatch(56599, "openGroupDMNitroCapLimitSheet", 84, "93007e7600"),
        FunctionPatch(56600, "GroupDMNitroCapLimitSheet", 895, "94007e7600"),
        FunctionPatch(56602, "navigateToNitroHomePage", 79, "93007e7600"),
        FunctionPatch(56605, "showChatGDMUpsellActionSheet", 74, "93007e7600"),
        FunctionPatch(56606, "ChatGDMUpsellActionSheet", 1145, "94007e7600"),
        FunctionPatch(56663, "SpotifyTrack", 51, "96007e7e7e7600"),
        FunctionPatch(
            56683, "subscribePlayerStateNotifications", 118,
            "3d0048010000240045000100d1006c0000017e7e7600"
        ),
        FunctionPatch(56688, "fetchIsSpotifyProtocolRegistered", 101, "96007e7600"),
        FunctionPatch(59373, "openGroupDMNitroCapInfoActionSheet", 70, "93007e7600"),
        FunctionPatch(59374, "GroupDMNitroCapInfoActionSheet", 446, "94007e7600"),
        FunctionPatch(61934, "showNitroLockedToast", 64, "93007e7600"),
        FunctionPatch(61987, "useTypingUserIdsForDisplay", 173, "080000007e7600"),
        FunctionPatch(62152, "ChatInputActionButtonGiftOrThread", 254, "93007e7600"),
        FunctionPatch(62154, "ChatInputActionButtonGift", 1193, "93007e7600"),
        FunctionPatch(62156, "PremiumAnimatedGiftButton", 605, "94007e7600"),
        FunctionPatch(62695, "EmojiPremiumUpsell", 101, "94007e7600"),
        FunctionPatch(62702, "ChannelAutocompleteEmojiUpsell", 306, "94007e7600"),
        FunctionPatch(63160, "GuildPowerupsBoostButton", 348, "94007e7600"),
        FunctionPatch(63349, "dismissUpsellCTA", 74, "93007e7600"),
        FunctionPatch(63297, "GuildDirectoryNicknameUpsell", 1037, "94007e7600"),
        FunctionPatch(63298, "GuildDirectoryNicknameUpsellModal", 97, "94007e7600"),
        FunctionPatch(63735, "SocialLayerStorefrontProductSelfPurchaseSuccessModal", 909, "94007e7600"),
        FunctionPatch(63736, "SocialLayerStorefrontProductGiftPurchaseSuccessModal", 302, "94007e7600"),
        FunctionPatch(63782, "SocialLayerStorefrontWishlistItemCard", 428, "94007e7600"),
        FunctionPatch(64002, "GroupDMNitroCapCoachmark", 463, "94007e7600"),
        FunctionPatch(64043, "NitroLimitUpsellBar", 475, "94007e7600"),
        FunctionPatch(64054, "openScheduledMessagesLimitUpsell", 58, "93007e7600"),
        FunctionPatch(64251, "QuestOrbsRewardModal", 1537, "93007e7600"),
        FunctionPatch(64273, "CollectiblesShopEntryButton", 477, "93007e7600"),
        FunctionPatch(64274, "MobileShopButtonCoachmark", 349, "93007e7600"),
        FunctionPatch(64285, "GuildProfileCreateTagUpsell", 235, "94007e7600"),
        FunctionPatch(64333, "isVirtualCurrencyEnabled", 47, "3402000101b102e9de9600520100007e7e7601"),
        FunctionPatch(64334, "useVirtualCurrencyMobileEnabled", 47, "3402000101b102e9de9600520100007e7e7601"),
        FunctionPatch(64422, "ShopThisLookActionSheet", 560, "93007600"),
        FunctionPatch(64437, "ShopThisLookMarketingCoachmark", 265, "93007600"),
        FunctionPatch(64616, "NoProgressGraphic", 198, "93007e7600"),
        FunctionPatch(64618, "BadgeDirectoryNuxCoachmark", 131, "94007e7600"),
        FunctionPatch(
            64806, "useProfileTabIndices", 79,
            "8b04018c02ffffffff890501100104100002b209058b01021000048906020205975300002d33040052050000100001100302b20a061f00010410030189060352050301b20606100202520502027605"
        ),
        FunctionPatch(64825, "UserProfileWishlistGrid", 2046, "94007e7e7e7600"),
        FunctionPatch(64834, "UserProfileWishlistSuggestionsGrid", 33, "94007600"),
        FunctionPatch(64839, "AddToWishlistGrid", 111, "93007e7600"),
        FunctionPatch(64840, "AddToWishlistItemCard", 418, "93007e7600"),
        FunctionPatch(64893, "FractionalNitroPreview", 708, "94007e7600"),
        FunctionPatch(64905, "ExpressiveNitroUpsell", 854, "94007e7600"),
        FunctionPatch(64903, "PriceTag", 185, "93007e7600"),
        FunctionPatch(64906, "NitroUpsell", 327, "94007e7600"),
        FunctionPatch(64910, "ShopNitroUpsellPromoSheet", 466, "94007e7600"),
        FunctionPatch(64917, "GiftButton", 261, "93007600"),
        FunctionPatch(64941, "NitroAcquiredHeader", 239, "94007e7600"),
        FunctionPatch(64942, "FractionalNitroCollectedActionSheet", 912, "94007e7600"),
        FunctionPatch(64945, "UnlockWithNitroButton", 331, "94007e7600"),
        FunctionPatch(65195, "NitroOrbsDeliveredModal", 1111, "94007e7600"),
        FunctionPatch(65215, "PremiumPlanWhatYouLoseActionSheet", 980, "94007e7600"),
        FunctionPatch(65249, "NitroHomeHeader", 488, "94007e7600"),
        FunctionPatch(65252, "PremiumNitroHome", 2161, "94007e7600"),
        FunctionPatch(65322, "PremiumMarketingPage", 1835, "94007e7600"),
        FunctionPatch(65392, "PremiumMarketingFloatingSubscribeButton", 1001, "94007e7600"),
        FunctionPatch(65419, "PremiumGroupEducationActionSheet", 367, "94007e7600"),
        FunctionPatch(65422, "NitroCreditEducationActionSheet", 398, "94007e7600"),
        FunctionPatch(65427, "GuildBoostingUpsell", 2086, "94007e7600"),
        FunctionPatch(65433, "PremiumSubscriptionPricingUpsell", 204, "94007e7600"),
        FunctionPatch(65436, "PremiumSubscriptionUpsell", 1239, "94007e7600"),
        FunctionPatch(65437, "BoostPurchaseNitroBanner", 524, "94007e7600"),
        FunctionPatch(65459, "PremiumPlanSelectYearlyUpsellModal", 1159, "94007e7600"),
        FunctionPatch(65463, "OutboundPromotionCard", 911, "94007e7600"),
        FunctionPatch(65465, "OutboundPromotionClaimAlert", 1570, "94007e7600"),
        FunctionPatch(65479, "GiftPurchaseButton", 565, "93007600"),
        FunctionPatch(65738, "logReadyPayloadReceived", 509, "93007e7600"),
        FunctionPatch(65739, "getConnectionPath", 101, "93007e7600"),
        FunctionPatch(65740, "getReadyPayloadByteSizeAnalytics", 892, "93007600"),
        FunctionPatch(65741, "logGatewayConnected", 150, "93007600"),
        FunctionPatch(66787, "ContactSyncUpsellCTA", 238, "94007e7600"),
        FunctionPatch(67877, "handleTrack", 257, "93007e7600"),
        FunctionPatch(68751, "UserSettingsEditUserProfile", 217, "93007600"),
        FunctionPatch(68760, "UserProfileUpsellButton", 286, "94007e7600"),
        FunctionPatch(68836, "openCustomizeBadgesSheet", 90, "93007e7600"),
        FunctionPatch(68837, "BadgeVisibilityMenu", 320, "94007e7600"),
        FunctionPatch(68841, "CustomizeBadgesSheet", 3165, "94007e7600"),
        FunctionPatch(68843, "UserProfileUpsellCard", 572, "94007e7600"),
        FunctionPatch(68940, "UserProfilePremiumTryItOutUpsell", 803, "94007e7600"),
        FunctionPatch(68942, "PreviewNitroCard", 213, "94007e7600"),
        FunctionPatch(68943, "GetNitroCard", 254, "94007e7600"),
        FunctionPatch(68945, "UserProfilePremiumUpsellCard", 89, "94007e7600"),
        FunctionPatch(68924, "UserProfileFloatingUpsell", 86, "94007e7600"),
        FunctionPatch(68963, "PasswordlessUpsell", 652, "94007e7600"),
        FunctionPatch(69438, "ProfileToActivityUpsellActionSheet", 249, "94007e7600"),
        FunctionPatch(69441, "BaseUpsellActionSheet", 518, "94007e7600"),
        FunctionPatch(69626, "XboxTwoWayLinkUpsell", 291, "94007e7600"),
        FunctionPatch(69628, "OneWayToTwoWayLinkUpsell", 450, "94007e7600"),
        FunctionPatch(69629, "PlayStationTwoWayLinkUpsell", 291, "94007e7600"),
        FunctionPatch(
            17034, "", 254,
            "4008048904023708000489010337080101890206890307370802033d01480701001e00440607016c90053300020104000000a4680800700106070205015e05030093008901046e01010005370803015e0103016e01040001450901026f2b5e0103066e060400014505060380a702012c1d0000ad68080085070847100100520107005e0703076e0704000746070704c41005005201070285070848100100520107035e0703086e0704000745070705bc20520107040207b71b0000a7320400450909068245520709008508084910010052070801520107058509085610010056010904b9ec6e010506015e03030a6e05040003440405077f91034d1303006e030405034b0201006b007e7e7e7600"
        ),
        FunctionPatch(69713, "usePredicate", 34, "96007e7e7600"),
        FunctionPatch(69718, "usePredicate", 34, "96007e7e7600"),
        FunctionPatch(69721, "QuestHomeSetting", 387, "93007600"),
        FunctionPatch(69779, "QuestHome", 2723, "94007600"),
        FunctionPatch(70072, "useIsMobileQuestDockVisibleToUser", 204, "96007e7e7600"),
        FunctionPatch(70458, "useHasGuildRoleSubscriptionsSetting", 42, "96007e7e7600"),
        FunctionPatch(70577, "useHasPremiumRestoreSubscriptionSetting", 94, "96007600"),
        FunctionPatch(70684, "SettingsAppearanceChannelListPreviewNitroUpsellComponent", 714, "94007e7600"),
        FunctionPatch(70685, "SettingsAppearanceChannelListPreviewNitroUpsell", 158, "94007e7600"),
        FunctionPatch(70890, "VideoUploadQualityNitroUpsell", 555, "94007e7600"),
        FunctionPatch(71438, "openPasskeyUpsell", 113, "93007e7600"),
        FunctionPatch(71439, "openPasskeyUpsellPromoSheet", 68, "93007e7600"),
        FunctionPatch(71440, "closePasskeyUpsellPromoSheet", 39, "93007e7600"),
        FunctionPatch(71441, "PasswordlessUpsellPromoSheet", 514, "94007e7600"),
        FunctionPatch(71473, "PremiumTrialOfferActionSheetContent", 1100, "94007e7600"),
        FunctionPatch(71490, "BalanceWidgetMenu", 342, "93007e7600"),
        FunctionPatch(71491, "BalanceWidgetMenuWrapper", 229, "93007e7600"),
        FunctionPatch(71519, "overrideSurvey", 58, "93007e7e7600"),
        FunctionPatch(71520, "surveyHide", 153, "93007e7600"),
        FunctionPatch(71521, "surveySeen", 243, "3d0048010000240045000100d1006c0000017e7e7e7600"),
        FunctionPatch(68928, "UserProfileTryItOutGetPremiumUpsell", 266, "94007e7600"),
        FunctionPatch(71771, "CollectiblesShopScreen", 225, "93007e7600"),
        FunctionPatch(71776, "CollectiblesShopInternal", 3132, "93007600"),
        FunctionPatch(71774, "CollectiblesShopV2", 542, "93007e7600"),
        FunctionPatch(71791, "ShopNitroUpsellBanner", 909, "94007e7600"),
        FunctionPatch(71873, "usePredicate", 37, "96007e7600"),
        FunctionPatch(71882, "usePredicate", 37, "96007e7600"),
        FunctionPatch(72026, "ActivityPrivacyUpsellActionSheet", 213, "94007e7600"),
        FunctionPatch(72648, "VoiceGuildTagChiplet", 373, "94007e7600"),
        FunctionPatch(72749, "BoostProgressBarCoachmark", 206, "93007e7600"),
        FunctionPatch(72751, "GuildPowerupsBoostToUnlockCoachmark", 104, "93007e7600"),
        FunctionPatch(72915, "GuildRoleSubscriptionsUpsellActionSheet", 556, "94007e7600"),
        FunctionPatch(72916, "IAPUpsellActionSheet", 249, "94007e7600"),
        FunctionPatch(72917, "GenericUpsellActionSheet", 465, "94007e7600"),
        FunctionPatch(72918, "CreatorMonetizationOnboardingV2UpsellActionSheet", 599, "94007e7600"),
        FunctionPatch(72919, "GuildRoleSubscriptionTierTemplateUpsellActionSheet", 912, "94007e7600"),
        FunctionPatch(72922, "useIsGuildEligibleForRoleSubscriptionsUpsell", 317, "96007e7600"),
        FunctionPatch(72936, "GuildUpsellChannelList", 603, "94007e7600"),
        FunctionPatch(72945, "OneColumnGuildUpsellList", 570, "94007e7600"),
        FunctionPatch(72946, "GameCommunityMultiGuildUpsellCard", 1927, "94007e7600"),
        FunctionPatch(73979, "GroupDMNitroUpsellBanner", 685, "94007e7600"),
        FunctionPatch(73980, "GroupDMNitroCapBanner", 593, "94007e7600"),
        FunctionPatch(74076, "componentDidMount", 405, "93007e7600"),
        FunctionPatch(74077, "componentDidUpdate", 1499, "93007e7e7e7600"),
        FunctionPatch(74169, "BadgeCustomizationProfileCoachmark", 439, "94007e7600"),
        FunctionPatch(74171, "useShouldShowExpiringTrialOfferCard", 338, "96007e7600"),
        FunctionPatch(74172, "YouExpiringTrialOfferCard", 1160, "94007e7600"),
        FunctionPatch(74525, "AppIconsCoachmarkActionSheet", 830, "94007e7600"),
        FunctionPatch(74526, "GiftingPromotionCoachmarkActionSheet", 1061, "94007e7600"),
        FunctionPatch(74538, "PremiumMarketingMomentActionSheet", 1052, "94007e7600"),
        FunctionPatch(74540, "PremiumDiscountOfferActionSheetContent", 1020, "94007e7600"),
        FunctionPatch(74544, "HasBadgeCoachmark", 720, "94007e7600"),
        FunctionPatch(74545, "NewBadgeCoachmark", 661, "94007e7600"),
        FunctionPatch(74546, "GiftingBadgesCoachmarkActionSheet", 153, "94007e7600"),
        FunctionPatch(74549, "CollectiblesMobileAnnouncementActionSheet", 890, "94007e7600"),
        FunctionPatch(74551, "NitroFileUploadAnnouncementPromoSheet", 438, "94007e7600"),
        FunctionPatch(74553, "NitroFileUploadUpsellPromoSheet", 572, "94007e7600"),
        FunctionPatch(74806, "PremiumSoundboardFeatureUpsell", 165, "94007e7600"),
        FunctionPatch(74975, "VoicePanelGamesSection", 167, "94007600"),
        FunctionPatch(75145, "trackHttpRequest", 220, "93007600"),
        FunctionPatch(75337, "_trackStartSpeaking", 236, "93007e7e7600"),
        FunctionPatch(75338, "_trackStartListening", 235, "93007e7600"),
        FunctionPatch(75445, "openDmSettingsUpsellModal", 220, "93007e7600"),
        FunctionPatch(75446, "DmSettingsUpsellActionSheet", 1028, "94007e7600"),
        FunctionPatch(76247, "GuildSettingsServerTagUpsellCard", 1005, "94007e7600"),
        FunctionPatch(76740, "GuildPremiumRoleSubscribeButton", 132, "94007e7600"),
        FunctionPatch(77056, "handleAppStateUpdate", 230, "93007e7e7600"),
        FunctionPatch(77122, "installWebsocketTelemetryHook", 181, "93007e7e7e7600"),
        FunctionPatch(77228, "setupLibdiscoreTimersMonitor", 39, "93007e7e7e7600"),
        FunctionPatch(77289, "logDangerously", 146, "93007e7e7600"),
        FunctionPatch(77290, "log", 206, "93007e7e7600"),
        FunctionPatch(77291, "verboseDangerously", 146, "93007e7e7600"),
        FunctionPatch(77292, "verbose", 206, "93007e7e7600"),
        FunctionPatch(77293, "info", 206, "93007e7e7600"),
        FunctionPatch(77296, "trace", 146, "93007e7e7600"),
        FunctionPatch(77298, "fileOnly", 148, "93007600"),
        FunctionPatch(81313, "track", 539, "3d0048010000240045000100d1006c0000017e7e7e7600"),
        FunctionPatch(81318, "submitEventsImmediately", 250, "3d0048010000240045000100d1006c0000017e7e7600"),
        FunctionPatch(81440, "", 134, "93007e7e7600"),
        FunctionPatch(88602, "_handleVoiceQualityPeriodicsStats", 502, "93007600"),
        FunctionPatch(88911, "sampleStats", 500, "93007e7e7e7600"),
        FunctionPatch(88931, "sampleStats", 68, "93007600"),
        FunctionPatch(88987, "sampleStats", 106, "93007600"),
        FunctionPatch(103875, "flush", 82, "93007e7e7600"),
        FunctionPatch(103986, "add", 511, "93007e7e7e7600"),
        FunctionPatch(103989, "_flush", 374, "93007e7e7600"),
        FunctionPatch(103990, "_captureMetrics", 97, "93007e7600"),
        FunctionPatch(104011, "add", 443, "93007e7e7e7600"),
        FunctionPatch(104012, "flush", 114, "93007e7e7600"),
        FunctionPatch(
            129392, "", 215,
            "34030142050301000000890701370500073b0803003b0403025e03040893016e0608010346030600f84a05006e06030607130306b1a1000000063406005e0904096e0a08010945090a0182ca6e09090a07b04b095e0904096e0a08010945090a02eece6e09090a07b019095e0404096e080801044504080394656e04040807ae1b3b0a060045090a04db28850805261c02006e08090a08130408ae3845070705237e45070706d87d44070707a797001c010000b21c013b07060045060704db28850505251c02006e050607051301051004011003047603"
        ),
        FunctionPatch(
            107579, "", 578,
            "3405000203e74f0000d3fa2d003406013b0a06003b0706025e02071e93006e020a0002440802009944040801e35e02071e6e020a0002440202020a45020203330b6e02040802520302013b0205276c02020052030202080201005a0203003b030519b38b0000000344040204ca0203e74f000072580a005e08071e6e080a0008440b08009944090b01e35e08071e6e080a0008440808020a45080805c0096e08090b08520308013b0b06133b09061c020893760000313304003b0c0500440c0c065252080c003b010524520801013b01050b520801023b0c051552080c036f080b000908520308026e030402033b03051ab20d033b04050a940118030104b3a00000000344040204ca0203e74f00005a9e0e005e08071e6e080a0008440b08009944090b01e35e08071e6e080a0008440808020a4608080728c804006e08090b08520308013b0906133b08061d0206947600009e6908003b0b050052060b003b0b050a52060b013b0b050252060b023b0c0501940116010c01930bb00801440b0c065252060b033b0b051552060b046f0609000806520306026e030402033b010518b39e0000000144040204ca0203957600002c0a16005e06071e6e060a0006440906009944080901e35e06071e6e060a0006440606020a45060608ef0b6e06080906520306013b0605286c060600520306023b06051b9305b23e060106625ee9de5e08071e6e080a0008440908009944080901e35e07071e6e070a0007440707020a4607070997c904006e070809075206070010050652030503af050000007602"
        ),
        FunctionPatch(
            115638, "", 451,
            "3404000202e74f0000d3fa2d003406013b0806003b0506025e01051c93006e01080001440701009944030701e35e01051c6e01080001440101020a45010103330b6e01030701520201013b01041b6c01010052020102080101005a0102003b020411b38b0000000244030104ca0202e74f000072580a005e07051c6e07080007440a07009944090a01e35e07051c6e07080007440707020a45070705c0096e07090a07520207013b0a060d3b09061102074b7b0000313304003b0b0400440b0b065252070b003b0b041652070b013b0b040b52070b023b0b040c52070b036f070a000907520207026e020301023b020412b3800000000244030104ca0202e74f00005a9e0e005e07051c6e07080007440a07009944090a01e35e07051c6e07080007440707020a4607070728c804006e07090a07520207013b09060d3b07061202064c7b00002d3304003b0a040052060a003b0a040b52060a013b0a040c52060a026f0609000706520206026e0203010244030104ca0202e74f0000979316005e06051c6e06080006440706009944060701e35e05051c6e05080005440505020a45050508ef0b6e05060705520205013b04041c6c04040052020402af050000007601"
        ),
        // The Settings overview builds its section list here. Its Typing Indicator entry is
        // replaced with null so the list's own isNotNullish filter drops the row; the stored
        // setting and its editor are untouched, but Settings no longer offers either.
        FunctionPatch(116092, "", 1259, "3405003404013b0a04003b0604015e01060a93006e030a00014502030028a73b0d05003b0105013b0c05020205235a0000a73204005e0706046e070a0007440907019944080902e35e0706046e070a0007440707030a46070704349c04006e07080907520507009407b00d0d3b090404450709053f0e080b0a005a0b07003b07040445040706c90d5a0b04014504070773305a0b040245040708450a5a0b040345040709776d5a0b04044504070a34315a0b04054504070bf3025a0b04064504070c210b5a0b04074504070d4b175a0b04084504070e8a595a0b040944090b0f365e0406056e040a000445040410e7cc6e04090b045205040108040a005a0405000205235a0000a73204005e0906046e090a0009440e090199440b0e02e35e0906046e090a0009440909030a4609091172fd04006e090b0e0952050900450907126736080b09005a0b090045090713f7195a0b09019409b20e0d9409b0090c450907053f0e5a0b09029409b0090145090714f6575a0b09034509071566175a0b0904450907165f4b5a0b09054509071762305a0b09064509071890665a0b09079408b2090145080714f6570101215ae9de5a0b080844090b0f365e0806056e080a000845080810e7cc6e08090b08520508015a0405010205235a0000a73204005e0806046e080a0008440b08019944090b02e35e0806046e080a0008440808030a46080819767b04006e08090b08520508004508071a2f10080b0b005a0b08004508071bb3155a0b08014508071ca30d5a0b08024508071dee555a0b08034508071e640f5a0b080494087e7e7e7e5a0b08054508072041355a0b080645080721f6175a0b08075e0806066e080a0008450808224361450808235a285a0b0808450807246f1c5a0b0809450807251e145a0b080a44090b0f365e0806056e080a000845080810e7cc6e08090b08520508015a0405020205235a0000a73204005e0806046e080a0008440b08019944090b02e35e0806046e080a0008440808030a450808264c5c6e08090b085205080045090727c511080803005a0809004509072823145a080901450907293f0f5a080902520508015a0405030205235a0000a73204005e0806046e080a0008440b08019944090b02e35e0806046e080a0008440808030a4508082acc5c6e08090b08520508004509072b6931080801005a080900520508015a0405040105c00ee9de4509072c0c09080801005a080900520508005a0405050205235a0000a73204005e0806046e080a0008440b08019944090b02e35e0806046e080a0008440808030a4608082d0f9e04006e08090b08520508004509072e991d080807005a0809004509072f71415a08090145090730ec315a080902450907319c365a08090345090732db2f5a08090445090733e6495a080905450907348b515a080906520508015a0405060205235a0000a73204005e0806046e080a0008440908019944080902e35e0606046e060a0006440606030a46060635b26404006e0608090652050600450807362c2c080602005a06080045080737aa195a060801520506015a0405070205235a00009a022e00450807381043080604005a060800450807396a345a0608014508073af00f5a0608024508073b8b1f5a060803520506015a0405080205235a0000a0022e004508073c3634080603005a0608004508073d26165a0608014507073ebf405a060702520506015a040509520104006e010203017601"),
        FunctionPatch(118774, "handlePostConnectionOpen", 314, "93007e7600"),
        FunctionPatch(133768, "", 31, "93007e7e7e7600"),
        FunctionPatch(33796, "isBlockedForMessage", 297, "96007e7e7600"),
        FunctionPatch(38394, "shouldAnimateSticker", 35, "96007e7e7600"),
        // Reseam's Reveal spoilers and Skip masked link warnings. These are pure predicates:
        // true reveals, false stops obscuring, true trusts the link.
        FunctionPatch(48637, "computeGlobalSpoilerDisplay", 49, "95007e7e7600"),
        FunctionPatch(48638, "useShouldDisplaySpoilerObscurity", 129, "96007e7e7600"),
        FunctionPatch(48945, "isLinkTrusted", 116, "95007e7e7600"),
        FunctionPatch(52649, "isLinkTrusted", 114, "95007e7e7600"),
        FunctionPatch(
            47120, "createMessageContent", 8346,
            "40240b890a01440b0a00b23724000b466b0a01ce6a040045140a0236e445130a03e76b45120a0438cb45110a05d39b45100a064442450e0a073bcc9303d5060e03960e340c00440f0a08c2454d0a0964e1453f0a0a15c745260a0b422139240103392402033924030339240403392405033924060339240703392408033924090339240a0345170f0ceae2960a7e7e7e7e45150f0e49c9450d0f0f46c9455a0f10f99345690f110eb996597e7e7e7e45330f13d67796407e7e7e7e3724014045230f15d79145390f16afa1963c7e7e7e7e963b7e7e7e7e963a7e7e7e7e45410f1ae9e2451d0f1b0fe3451f0f1c07e345290f1d530345360f1ee4e245420f1f1cbf451c0f20f28f45180f2102e2452c0f22a06445190f239ab5452b0f24de9e452e0f2577b545460f26ffda3724024645470f27bee637240347451b0f284eeb452f0f29c2e3b224593b1a0c003b160c025e1616256e161a0316451a162ab32345161a2bed4f6c59161a9405d10e19053b160c104519162cac863b2d0c013b160c025e1a16266e1a2d031a6f251a03191b3724042595081707081139240507101a19102807b20607102817b2162844190b2dc93b170c1c4517172e373a19281917102a07b20607102a15103507b2063510350d3b300c124415302f87450d0b3088b96c0d0d0b6e1e15300d4415302f875e0d161a6e192d030d45171931469d440d0b32526e0d17190d6e1b15300d16061e05930db00d0645151e33e1bb6c0d151e3724060d45150b34de7a44201532523b170c005e1516276e191703154515193520bc6f5415190d2010490dd1186b055e0d16286e1517030d450d153680b86e6b0d150b10320bcf0f056b450d6b378dc744320d00b208190000b2290a5e0a16296e0d2d030a020a5a360000a732040045150b3804e2520a1500520a59016e190d030a44200b2dc93b0a0c1c450d0a3915289415d536200d3b210c0c4520213a6dbd450d0b3b05d96e2020210d4422203ce03b0d0c0d45210d3dd52f940dd5092221440d2000b210150dd24c1d000005155e0d162a6e0d2d030d6e0d0d030bb1811c00000d134e13b20613104e3f45530b34de7a5e0d162c6e2117030d4620213ea6b9040045220b3f1525d10a220545220b40a1a0450d22415a766c0d0d226e2720210d440d32425fcf11050d442032425f900dff00d5100d20020d5f3600003bcc0900ae505e20162d6e22170320462122434eb70400b00f2e102028b2062810202a102e201820051bb20e2045310b44284d6e20310b1b107b22107a0b10793210782e10770e1076201075271074276a0d210844220d425f452e0d45e7c5450d0d460cc510202fb2072018200522102122b211205e20162e6e202d03206e212003225e20162f6e221703204520224725bb6e4520220b372407455e2016306e2217032045202248e55f6c442022372408445e2016256e2017032045222049c61d4520222bed4f6c43202237240943b2062813280db2062813282f3b200c09450d204a75bc6e480d200b37240a489320b384000000285e0d16316e222d030d020d60360000117208004531324b34ae520d310044310b4c51520d3101520d4002b2062e102e46520d2e03520d2b04520d2a05520d2506520d2707520d27084527254d7a17520d2709520d450a520d440b45270b34de7a4527274e6f02520d270c520d260d852424d86b0100520d240e6e2022030d5e0d16336e222d030d020d6136000031330400520d0b00520d1001520d1e02520d25036e0d22030d45650d4fede845640d50b39745630d51a1a345620d52eee845610d5354c845600d547db2455f0d55b37c455e0d560973455d0d570a73455c0d587adc3b270c16452227590ac1440d0b32526e2222270d1806052244240b3ce03b310c1d450d315af01f190d240db00d0d45240b5bc4936c0d240b102420b03f0d100d20d1082005080d000044260d5cca5e2016346e2a2d03200220623600002d3304005220220052200601522025026e202a03206e20260d2010240d3b260c084520265dec7e440d0b32526e2020260d103d24cf4a0520100d24d1082405080d0000452b205ee69e44240d5cca5e2016346e2a17032046262a5f9f5e0400022063360000a732040052202b00522025016e20262a206e20240d20103d0d5e0d16356e2417030d4520246012c1450d0b34de7a6f0d20240d1e45370d61afc246260d62de450500452e0d63f3c75e0d16366e2417030d45202464e2af450d0b65238b6c0d0d0bb22f0d450d0b34de7a450d0d66de74d120050dd11c0537452a0b67320216042a05930db00804440d2a6895d14b050dcf080537d1110549450d5369246e6e0d0d5303ae325e2a16376e342d032a452b346ae8bb022a653600002d3304004438533252522a3800522a3701522a49026f0d2b342a53ae4e5e2a16376e342d032a452b346b93b8022a643600002d33040045370b6732024437373252522a370045370b6732024437376895522a370145370b6732024537374e6f02522a37026e0d2b342a6e5820240d5e0d16376e2417030d4520246cd5b8d10a05264526536d5394020d66360000a7320400520d26005e2616386e2b170326452a2b6ee1785e2616396e261703264526266f1f27452626709e276e262a2b26520d26016e5720240d180405499420b218043b260c1345242671aa44440d5332526f202426490d5e0d163a6e2617030d4624267204f8040016042005930db00904450d207360b76e0d24260d1804050d932ab2330445240d74707118040524932ab2240445240d755ea418040524932ab2150445240d769b0b18040524932ab20604102a0d5e0d163a6e2617030d462426773a36050016042005930db00904450d207360b76e2b24260dd29f000000052ad13e052b1804052e9350b23204180405499350b229045e0d163f6e2617030d4524267871bf020d223200009aff0d00520d4900520d2e016e5024260dae57020d673600009aff0d005e24163e6e242d0324520d24005e24163d6e2417032445262479a03045242b7af35dd106240597241e242624520d24015e24163d6e261703244524267b11b16e2424262b520d240310500daf82000000450d2a747071d1060d05970d8b0001c70f0d005e0d163c6e242d030dae0b5e0d163b6e242d030d020d673600009aff0d00520d24005e24163d6e241703244526247c021b5e24163d6e2b17032446242b7d86c504006e24242b2a1e242624520d24015e24163d6e261703244524267eda946e2424262a520d240310500d45260b7f5bc53b0d0c1e45240d80f40f6e24260b24b231245e24161d6e24170324442a24819944262a82e35e24161d6e24170324442424830a4624248454c504006e21262a245e2416356e26170324452426852e846e2424260b45552486173c45512487208945562488218944260b2dc945240a895f18d53d26245e24161d6e24170324442b248199442a2b8a295e24161d6e24170324442424830a4626248b954c050001246836e9de522455006f212a2b262444260b2dc945240a8c871e936cd6be0500002624936cb3b50500001f3b260c0c4524263a6dbd451f0b3b05d96e1f24261f44261f3ce03b240c0d452a243dd52fd4d80000002a26452a248d0a1ad3792a264524248e4c1ed31f24265e2416416e261703244524268f5f926e2424261f936caf5a050000022469360000a73204003b260c1b45262690190b522426005e26161d6e26170326442b268199442a2b82e35e26161d6e26170326442626830a4526269154346e262a2b2652242601106c24af0a050000022469360000a73204003b260c1b45262690190b522426005e26161d6e26170326442b268199442a2b82e35e26161d6e26170326442626830a462626928f7504006e262a2b2652242601106c24afb8040000441f1f00b25e2416286e261703244524263680b86e2424261f3b2a0c1545262a93afc36e26262a1fb1400400002645262a9464b86e26262a1fb1e2030000263b2a0c2202266a360000cf062d0052261f005226240152261402042b107a2b10790f732e2e039604562b040007e352262b086e262a0326d0530300000526902ac82631042a26b392000000045e2a16356e2b17032a452a2b852e846e2b2a2b1f452a2b86173c452b2b872089d1102a05452e1f34de7a452a2e95c826d1062a05932a4b262a01c826b01e4e3b2a0c03d1062b05932b6e2a2a032bd10a2a05452a26872089ae08452a268720894b262a0220895e2a16406e2a2d032a6f2a2a030b1fb2172a452b2695c826902aa10c1e2a2a2b4b262a01c8263d2a482e2a960100442b2e9700442a26425f6e2a2b2e2ab2132a442b26425f442b2b98a79700172a002bb2092a4b2603035f0016042405932eb00e04452424378dc7442e2400b2d1072e05102e1f442a26425f902415e83104242eb0090408240000ae165e2b16216e3417032b452b349980bd6e242b342e44242498a79700c8be0100002400902428803104242eb21d0445242e9a2880cf140524442b2e425f9024ff00d46b010000242b5e2416226e371703244534377f5bc5442b2e9b8345240d9cc9596f2434372b24b11601000024442b2e2dc945240a9df556d4ea0000002b245e2416226e371703244534377f5bc5442b2e9b8345240d9eeb366f2434372b24b19200000024442b2e2dc945240a9f4d13d5372b24902482283104242eb22b04cf28051e5e2416246e2b17032445242ba0e8966f24242b2e1ecf0f0524902bff00d6250100002b2444342e425f902bff00d30d2b341604052a942ab22f045e2b161d6e2b17032b44372b819944343782e35e2b161d6e2b17032b442b2b830a452b2ba1555b6e2a34372b10242aafd90000005e2a161d6e2a17032a44342a8199442b3482e35e2a161d6e2a17032a442a2a830a452a2aa209786e242b342aafa80000005e2a16236e2b17032a462a2ba3257b04006e242a2b2eaf8d0000005e2a161d6e2a17032a442e2a8199442b2e82e35e2a161d6e2a17032a442a2a830a452a2aa49f0b6e242b2e2aae5c5e2a161d6e2a17032a442e2a8199442b2e82e35e2a161d6e2a17032a442a2a830a452a2aa5d9086e242b2e2aae2e5e2a161d6e2a17032a442e2a8199442b2e82e35e2a161d6e2a17032a442a2a830a452a2aa6d50b6e242b2e2a442a1f2dc9451f0a9df556d50a2a1f4b2624035f00021fdb330000a73204003b2a0c1b452a2a3dd52f521f2a00521f2601106c1fd0ff00000005244b1f2404b68c106c1fafef000000021f69360000a73204003b240c1b45242490190b521f24005e24161d6e24170324442a24819944262a82e35e24161d6e24170324442424830a462424928f7504006e24262a24521f2401106c1faf9d000000021f69360000a73204003b240c1b45242490190b521f24005e24161d6e24170324442a24819944262a82e35e24161d6e24170324442424830a452424a7a04d6e24262a24521f2401106c1fae4d021f69360000a73204003b240c1b45242490190b521f24005e24161d6e24170324442a24819944262a82e35e24161d6e24170324442424830a452424a8c7776e24262a24521f2401106c1f9367b21f1d3b1d0c21977b107a0b10791410780e10771b10760f10751a6a671d075e1d16426e1f17031d461d1fa93dec04006f3e1d1f0b485e1d16436e1f17031d451d1faa00c86e2e1d1f203b6d0c04452b6dabdad7454b6daca08e16041e05931db00904451d1ead90dd10261ecf2d051d16041e05931db00d04451f1eaeba806c1d1f1e10261eb2131d441f302f87451d1ead90dd6e261f301d5e1d16446e2417031d451f24af5ac1021d6b3600008ac10900521d2000521d26016e521f241d451d32b0d97a44381d98a7451d324b34ae44371d98a7100406b21406451d0bb0d97a441d1d98a797001704001db2070418040522b0720408340000b39e000000365e1d16466e1f2d031d021d6d36000062730800452432b0d97a521d2400462627b1e744050045240bb2de036e24262724521d2401521d3502521d4003521d4304521d2b05521d46064524254d7a17521d2407521d4508521d4409521d250a6e341f031dae355e1d16456e1f2d031d021d6c3600002d330400521d220044240b3ce04522315af01f17092422521d0901521d35026e341f031db21204441f0b3ce0451d315af01fd5221f1d5e1d16476e2217031d451f22b3ea9a451d0b40a1a0704f1f221d0823ae305e1d161d6e1d17031d44221d8199441f2282e35e1d161d6e1d17031d441d1d830a461d1db45d7004006e4f1f221db21204441f0b3ce0451d315af01fd5201f1d5e1d16476e2217031d451f22b5f701451d0b40a1a06e4c1f221dae305e1d161d6e1d17031d44221d8199441f2282e35e1d161d6e1d17031d441d1d830a461d1db45d7004006e4c1f221d3b1f0c11451d1fb624a26c2a1d1f451d0bb7a2ce104a21b2311d5e1d161d6e1d17031d44221d8199441f2282e35e1d161d6e1d17031d441d1d830a461d1db82ec904006e4a1f221d451d0bb93bcd6c1f1d0b131d1fb20f1f131f0eb0060e101f1c101d1fb0151d18040521b20b04901fff0019041f21101d04b0191d5e1d16236e1f17031d461d1fbaefd804006e4a1d1f0b9327b2251c5e1c16486e1d2d031c021c6e360000a7320400521c1a00521c590170271d030b031c9324b21b185e1816496e1c17031846181cbb441b05007024181c0b58555e18164a6e1f170318461d1fbcfbf80400441c533252101849d106490593186f181d1f1c18932193229323b269185e18164a6e1d170318451c1dbde3be451853be9eb06e1f1c1d1844181fbf8c441c1fc0a3441d1fbf8c1804051d931db22e045e26164a6e68170326456668c12c8e445b1fbf8c45261fc2ef7a3b1f0c20451f1fc3730d701d66685b261f10211d10221c10231816041e05931cb00904451c1ec432351804051c931d931fb3940000000445180bc5577e18040518931d931fb3800000000445260bc5577e9018ff00931d931fd36c182645180bc5577e3b5b0c0545265bc65709451c1cc7a2906e68265b1c16046805931cb00804441c6868951804051c931cb233045e2616376e662d0326455b66c895b802264a28000049450200446a68325252266a004468686895522668016e1c5b6626101d1c101f185e1816226e5b17031845265b7f5bc5101832d107320510180b441c189b8345180d9cc9596f26265b1c183b5b0c05451c5bc6570944180bc943d10818059018ff006e181c5b18185b0518b2235b5e1c16226e6a17031c45686a7f5bc544660b9b83451c0dca4d1c6f5b686a661c931cb2085b441c183252160432059318b014044566327f5bc5455b0dcbe5516e1866325b086a000093689366b34b010000183b5b0c1444185bcc896e18185b491804051893689366b32f010000045e5b164b6e6f17035b456e6fcd7884915b82d402006f5b6e6f185b93689366b30a0100005b457218cec38cd10872053b720c18456f6dcfe9c15e18164b6e5b17031845185bd0e18c6e73185b6f9318b20b733b5b0c1a2d18725b5e5b164b6e6e17035b465b6ed10afc04006e6e5b6e6f935bb2796e5e6e164c6e6e2d036e45706ed23204456f70d3c86f5e6e164c6e6e2d036e446e6ed459456e6ed5e91b6f716f701a6e456e6dd60cab8b7001b2096e45706dd798e45e6d164b6e6f17036d456e6fd8deb7976db207733b6d0c19107b6f107a7210797110787010776d6a6e6e05456d6ed97c036c5b6d6e446e6a5cca5e6d161d6e6d17036d44706d8199446f7082e35e6d161d6e6d17036d446d6d830a466d6dda857104006e6d6f706d6e6d6e6a6d10685b106618105b32d1073205105b0b45185b7f5bc5450d0ddba1656e0d185b0db23b0d44186a5cca5e0d161d6e0d17030d446d0d8199445b6d82e35e0d161d6e0d17030d440d0d830a460d0ddcaa6d04006e0d5b6d0d6e0d186a0d440d6a98a797001c040d00935bb2130444186add9b910dc40801006e5b186a0d5e0d164d6e6a17030d46186ade46800400450d0b34de7a440d0d32526f18186a0d49020d6f3600007f100c00446a0b3252520d6a00446a0b4c51520d6a01106a49d1066a05936a520d6a02106a32d1076a05106a0b446a6a9b83520d6a03446a0b2dc9520d6a04456a0bb2de03cf12056a456a0bb2de03b6100000006afb01456a0bb2de03ae153d6d486e6ddf2a00456d0bb2de036e6a6e036d520d6a05446a0b3ce0520d6a06520d1907936ab0063f106a6c520d6a08520d67091804056b9367b20f04466a6be05cc104006c676a6b520d670a136769b0096945670be1ddd8520d670b45670be259ca6c6a670b9069ff00106769b2376a106769b0313f5e69161d6e69170369446b698199446a6b82e35e69161d6e69170369446969830a466969e34b2805006e676a6b69520d670c45670be259ca6c69670b9367b236699367b0313f45690b3f1525180405699367b222045e6916476e6c170369456b6ce40a70456a0b3f1525906916326f676b6c6a69520d670d456725e5bcad520d670e45670bb7a2ceb01f67d10c0568456725e62b54ae108d010000000000e0ef412d670168ae0a466725e737550500520d670f520d6610456625e8d4d1520d6611520d6512520d6413520d6314520d6215520d6116520d6017520d5f18520d5e19520d5d1a520d5c1b520d5b1c520d5a1d520d401e520d591fb2094e45555395c826d10655059355520d55209355b0084e445558e9ef520d55219455b0064e105557520d55224453533252520d5323b0294e9053c826d52253143b550c03105351d106510593536e53550353d10a5305455325ea27a8ae08455325ea27a8520d53249453b01e4e3b570c03105551d106510593556e55570355d10655059455105355520d53259453b21c549453b0174e5e54164e6e55170354455455ebe3e06e53545556520d532690539cab17045314520d04279053c82617045314520d0428520d4b29130446520d042ab01e4e3b4b0c03d106510593516e4b4b0351d10a4b05454b25ea27a8ae08454b25ea27a8520d4b2b934bb0064e104b50520d4b2c18040552934bb219045e50164f6e51170350465051ec581305006e4b505152520d4b2d934bb0064e104b4f520d4b2e934bb0224e5e4f16476e5217034f455152e40a7045500b40a1a0904f16326f4b5152504f520d4b2f934bb0094e454b25ed7896520d4b30934bb0064e104b4c520d4b31520d4a32520d1233520d3f34934ad31c034d5e4b16506e4c17034b464b4cee253d0500704a4b4c0b1a4d520d4a355e4a16516e4b17034a454a4befd6a66e4a4a4b0b520d4a36520d3e37933eb22d425e4216526e4b170342464a4bf091ff0400457625ea27a8107b4b107a0b10791e10781410771a6a3e4a06520d3e38453e32f1b8a2443e3e98a71c043e00933eb25304933eb24e415e4116326e422d03410241703600006273080052410b005241490152414802524147035241070452414605524145065241440752414308524140095241250a454032f1b8a26f3e42034140520d3e39973eb0093f453e25f2ff27520d3e3a973eb0093f453e25f355c7520d3e3bd1063d05933d520d3d3cb0093c083c0000ae195e3d16536e3e17033d463d3ef4f53c05006f3c3d3e0b1a520d3c3db0093908390000ae265e3c16546e3d17033c463c3df5d93c0500107b3d107a0b10793210781e10771a6a393c05520d393e9339b2193b5e3b16556e3c17033b463b3cf6ce3c05006e393b3c0b520d393f9339b21b3a5e3a16566e3b17033a463a3bf7cf3c050070393a3b0b1e1a520d3940b21d35b2113619070038b0070719070037103607b00636103606103536520d3541520d0642520d34438b0201100102b21f0644340b3ce04531315af01f100102d50e34318d019a9999999999c93f520d0144b04b2f5e2f16576e312d032f022f713600002d330400522f32005e3216256e32170332453432f8b4234532342bed4f6c323234522f320144320b325217063233522f06026e2f31032fae06082f0000520d2f45b2062910292e520d294616061e059329b00d06452e1ef9afcb6c292e1e130629130606b21106442e0b325244290b4c5117062e29520d064716061e05932eb00d0645291ef9afcb6c2e291e9329b3290100002e9329b3210100002c442f0b3252442e0b4c51932cd60c0100002f2e452e1e33e1bb6c312e1ecf1b05313b2f0c0a452e2ffa7fbd6e2e2e2f31932cb1e60000002e3b310c0e452f31fb78c5442e0b4c516e332f312e452e0b3804e2442e2e98a71906002e9332b07706442f302f87452e1ead90dd6e2e2f302e16072e059334b0090745342efcdf4716073405932eb00907452e34fd3f691807052e9431b218073b300c07452f30fe114d452e34fd3f696e312f302e5e2e16206e3017032e462f30ffe43c0500022e72360000a7320400522e3400522e31016e322f302e3b300c0b452f30ff11c3442e1e32526e312f302e5e2e16206e3017032e462f30fff13c0500022e7336000031330400522e3300522e0601522e3202522e31036e2c2f302e10292c520d294845290b34de7a44292932521706292a520d06493b290c06452929ff17c218060529520d064a5e29161e6e2c170329452a2cff33265e2916586e292d03296e292a2c29520d294b5e29161e6e2c170329452a2cff33265e2916596e292d03296e292a2c29520d294cb0092808280000ae1a5e29165a6e2a17032946292aff123d05007028292a0b142b520d284d5e28161d6e28170328442a28819944292a82e35e28161d6e28170328442828830a462828fffef104006e28292a28520d284e5e28165b6e29170328462829ff1c3d05006e2828290b520d284f520d2750520d24515e24165c6e27170324462427ff893305006e2424270b520d24525e24165d6e29170324462829ffe03c050044270b325244240b4c5170792829272425107a0d73242e039324b209264524254d7a17570d2405a23705005e24165e6e25170324452425ffa8606f2424250b1e560d24065189570d2307a43a0500570d2208a33a0500570d2109a23a0500570d1f0abebc0400570d1d0ba9f90400560d130ce76b560d1c0d2fb716061e05931db00806441d1e2dc93b1c0c1f451c1cff8a5017061d1c570d060ea42005005e1c165f6e1f17031c461d1fffa21b0500451c0b34de7a6f1c1d1f1c20560d1c0fd3a7570d18102fce0400760d440d0b2dc9450a0aff0517d34b0d0a5e0a162b6e1817030a450d18ffaaa7020a5c3600009e690800520a0b00520a1a01520a1902520a140316051e05931cb00d05451d1ef9afcb6c1c1d1e520a1c046e0a0d180aae60010d5d36e9de3b180c21977b107a0b10791410780e10771b10760f10751a6a181807520d18005e16162b6e18170316451718ffaaa702165e3600003133040052160b0052161a0152161902521614036e79171816107a0d73162e03100a0d760a040a3b0d0c22010c5b360100520c1500520c1401520c1302520c1203520c1104520c1005520c0e06040e107a0e10790f730f2e039605560e05110fe3560e051206e3560e0813bee6520c0e076e790d030c107a0a730c2e03440b0b3252560a0b145200760a"
        ),
        FunctionPatch(52317, "SuperReactionLocalImageAnimation", 144, "94007e7e7600"),
        FunctionPatch(56346, "useIsMessageSwipeActionsEnabled", 74, "96007e7e7600"),
        FunctionPatch(62001, "handleTypingStartLocal", 401, "96007e7e7600"),
        FunctionPatch(70671, "ActivityCardsItem", 162, "94007e7e7600"),
        FunctionPatch(
            52842, "createPendingReply", 104,
            "34010089020144090200b2440802014f9607950644050204da45040205d93a3b0201003b0101015e01010093006e0302000144020306730201583f000029462d005201090152010802520107035201060452010505520104066e010203017600"
        ),
        FunctionPatch(
            52843, "createShallowPendingReply", 85,
            "34010089020144070200b3440602014f960595043b0201003b0101015e01010093006e0302000144020304730201593f00002f462d00520107015201060252010503520104046e010203017601"
        ),
        // Reseam's remaining applicable feature predicates: higher stream quality, higher video
        // upload quality, and the quest surfaces its Hide quests patch covers beyond the ones
        // already stubbed above.
        FunctionPatch(67069, "canStreamQuality", 119, "95007e7e7600"),
        FunctionPatch(67057, "canUseHighVideoUploadQuality", 54, "95007e7e7600"),
        FunctionPatch(51231, "getIsEligibleForQuests", 37, "96007e7e7600"),
        FunctionPatch(70401, "QuestDockWithVisibilityContext", 371, "94007e7e7600"),
        FunctionPatch(74713, "QuestActivityButton", 276, "94007e7e7600"),
        FunctionPatch(70068, "useIsMobileQuestDockRenderedBase", 582, "96007e7e7600"),
        // 74246 and 74247 shared one body in 353.2.0; 354.1 merged them into 70070.
        FunctionPatch(70070, "useMobileQuestDockHeight", 116, "8b00007600"),
        // The server-boost surfaces. Discord renders these from the powerups and guild-boosting
        // modules: the progress bar above the channel list, the boost count and tier circle, the
        // channel list row and its unread/warning indicator, the coachmarks, the Settings overview
        // toggle, and the two marketing calls to action. Each is a React component (or a render
        // method that returns one), so returning null removes it without a caller having to
        // handle a missing value.
        FunctionPatch(42963, "GuildBoostingMarketingPersistentCta", 970, "94007e7600"),
        FunctionPatch(63093, "GuildPowerupsBoostInfo", 382, "94007e7600"),
        FunctionPatch(65413, "BoostedGuildTierProgressCircle", 593, "94007e7600"),
        FunctionPatch(65491, "GuildBoostingMarketingCtaBar", 2261, "94007e7600"),
        FunctionPatch(72862, "GuildPowerupsChannelRowIndicator", 201, "94007e7600"),
        // 78159 and 78160 shared one body in 353.2.0; 354.1 merged them into 72863.
        FunctionPatch(72863, "GuildPowerupsCoachmarkHost", 53, "94007e7600"),
        FunctionPatch(72864, "GuildPowerupsCoachmark", 157, "94007e7600"),
        FunctionPatch(72865, "GuildPowerupsChannelRow", 688, "94007e7600"),
        FunctionPatch(72867, "GuildPowerupsProgressBar", 1323, "94007e7600"),
        FunctionPatch(75994, "renderBoostProgressBar", 283, "94007e7600"),
        // Hide-only ideas from the Valenced plugin repositories: the table divider, the DM
        // "happening now" cards and their reserved height, the call screen's add-people banner,
        // the game-console section of the audio sheet, and the Conjure row and you-bar button.
        // All are components or a height function, so a null or zero return removes them.
        FunctionPatch(40899, "TableRowDivider", 150, "94007e7600"),
        FunctionPatch(72530, "getMessagesItemHappeningNowHeight", 74, "8b00007600"),
        FunctionPatch(72535, "HappeningNow", 1041, "94007e7600"),
        FunctionPatch(49918, "VoicePanelAudioConsoleSection", 519, "94007e7600"),
        FunctionPatch(75018, "VoicePanelFloatingCTAContainer", 1284, "94007e7600"),
        FunctionPatch(72860, "ConjureChannelRow", 508, "94007e7600"),
        FunctionPatch(73178, "ConjureYouBarButton", 272, "94007e7600"),
        // Declutter's display-name styles and server-theme switches: the style hook already
        // returns null when the feature is off, and the two server-theme predicates are read as
        // booleans by every caller, so false restores the personal theme.
        FunctionPatch(38022, "useDisplayNameStyles", 303, "94007e7600"),
        FunctionPatch(34857, "useServerThemeUserEnabled", 38, "96007e7600"),
        FunctionPatch(34856, "getServerThemeUserEnabled", 38, "96007e7600"),
        // Junk Cleanup's reaction-row switch gates the row at its own predicate rather than
        // hiding the whole long-press sheet, so the sheet keeps every other action.
        FunctionPatch(63946, "shouldShowEmojiRow", 135, "96007e7600"),
        // Server Drawer's ICYMI switch force-returns false from the getter and the hook. The
        // alias 48905 shares the getter's body, so patching 48903 covers both call forms.
        FunctionPatch(48903, "getICYMIEnabled", 109, "96007e7600"),
        FunctionPatch(48904, "useICYMIEnabled", 141, "96007e7600"),
        // The rest of the visual separator components. No Dividers replaces TableRowDivider
        // alone; the same one-line null shape covers the other line-drawing components the
        // bundle exports, and the section-footer predicate answers false. Text, controls and
        // spacing stay.
        FunctionPatch(67024, "ContextMenuDivider", 45, "94007e7600"),
        FunctionPatch(40887, "FormSeparator", 73, "94007e7600"),
        FunctionPatch(49512, "FormSeparator", 41, "94007e7600"),
        FunctionPatch(70499, "FormSeparator", 117, "94007e7600"),
        FunctionPatch(71824, "ListItemSeparator", 47, "94007e7600"),
        FunctionPatch(69896, "ItemSeparator", 45, "94007e7600"),
        FunctionPatch(72583, "DottedLineSeparator", 107, "94007e7600"),
        FunctionPatch(76782, "SectionSeparator", 63, "94007e7600"),
        FunctionPatch(73861, "MessageVerticalSeparator", 47, "94007e7600"),
        FunctionPatch(73862, "MediaVerticalSeparator", 47, "94007e7600"),
        FunctionPatch(73863, "CardVerticalSeparator", 47, "94007e7600"),
        FunctionPatch(52927, "AutocompleteFormDivider", 60, "94007e7600"),
        FunctionPatch(64067, "AuthorizeFormSeparator", 45, "94007e7600"),
        FunctionPatch(73074, "GuildsBarSeparator", 154, "94007e7600"),
        FunctionPatch(73072, "GuildsBarHomeDrawerSeparator", 322, "94007e7600"),
        FunctionPatch(72800, "getChannelListSectionHasFooterDivider", 120, "96007e7600"),
        // Questify's bounty surface: a whole section of Quest Home, hidden at the component.
        FunctionPatch(69868, "QuestHomeBounties", 750, "94007e7600"),
        // JumpToTop uses this button only as its anchor; hiding the button itself is the one
        // declutter surface the plugin reveals.
        FunctionPatch(62198, "JumpToPresentButton", 468, "94007e7600"),
        FunctionPatch(46087, "Divider", 308, "94007e7600"),
        FunctionPatch(73082, "GuildsBarEmptyNUX", 1248, "94007e7600"),
        // 354.1 surfaces added after the 353.2.0 change set: the premium try-it-out
        // funnel, the referral sheet, the Orbs Arcade quest section, and the Hexagon
        // campaign components. Their replacements are the verified 354.1 bodies.
        FunctionPatch(68879, "UserProfilePremiumTryItOutSection", 502, "94007e7e7600"),
        FunctionPatch(68920, "PremiumTryItOutModal", 378, "94007e7e7600"),
        FunctionPatch(68919, "openPremiumTryItOutModal", 170, "93007e7e7600"),
        FunctionPatch(74554, "ReferralProgramEntryPointActionSheet", 549, "94007e7600"),
        FunctionPatch(69938, "OrbsArcadeQuestHomeSection", 614, "94007e7e7600"),
        FunctionPatch(69908, "HexagonCampaignQuestHomeHeader", 1307, "94007e7e7e7600"),
        FunctionPatch(71084, "HexagonCampaignAppIconSection", 1036, "94007600"),
        FunctionPatch(68830, "UserProfileHexagonCampaignDisplayNameStyleEditButton", 1499, "94007e7e7e7600"),
        FunctionPatch(68826, "HexagonCampaignDisplayNameStylePromo", 319, "94007e7e7e7600"),
        FunctionPatch(69911, "HexagonCampaignSheet", 2424, "94007600"),
    )

    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            out[i] = ((this[i * 2].digitToInt(16) shl 4) or (this[i * 2 + 1].digitToInt(16))).toByte()
        }
        return out
    }
}
