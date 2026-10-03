package dev.sleepy.app.patches

/**
 * The Discord JavaScript bundle patch set, extracted from the reference desktop build
 * (`quirky-noether/discord/dist/discord-alpha-348.5-patched.apk`).
 *
 * Every entry is a whole function body taken from the desktop build's paired base and
 * patched bundles: the base supplies the function's size, and the patched build supplies
 * its replacement bytes. The set covers the 145 functions that differ between the two, so
 * applying it reproduces the desktop bundle function for function. The bodies are the
 * reference build's own output, not a reconstruction of it.
 *
 * The set is encoded as data rather than derived from the shapes in [DiscordPatches] because
 * the code cannot synthesize two of the shapes. A promise-shaped stub must name
 * "Promise" and "resolve" through the bundle's own string identifiers, which are not the
 * string-table indices and are not resolvable without the identifier table; and the
 * feature-removal edits are whole-body replacements that were assembled by the reference's
 * own assembler. Everything else in the set is a 4-8 byte stub whose shape *is* derivable,
 * and [DiscordPatches] derives it.
 *
 * The four functions that grew are handled by relocation, not by this table alone: see
 * [dev.sleepy.app.engine.HermesBundlePatcher].
 */
object DiscordHermesBundlePatch {

    /** Byte length of the bundle this table was extracted from. */
    const val TARGET_BUNDLE_SIZE = 55720267

    /** Function count of that bundle. */
    const val TARGET_FUNCTION_COUNT = 128469

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

    /** Every function in the set, in function-id order. */
    val PATCHES: List<FunctionPatch> = listOf(
        FunctionPatch(13894, "", 143, "93007e7e7e7600"),
        FunctionPatch(
            14518, "", 280,
            "4008078904023708000489050337080105890206890307370802033d01480901001f00440709016c9006340002010400000091e20600700107090206015e06030093008901046e01010006370803015e0103016e01050001370804015e0103026e01050001370805015e0103036e0104000145090102eb715e0103046e01040001440101039a370806015e01030b6e060400014505060428660201221b000031a007008407083bf3520107005e07030c6e0704000745070705b867520107028407083cf3520107038407083df3520107040207be1900005ba5040045090906b510520709008408083ef3520708015201070584090856f356010906bcec6e010506015e03030e6e05040003440405077e9103b5e202006e030405034b0201006b007e7600"
        ),
        FunctionPatch(
            14522, "", 201,
            "40080289040237080004890206890307370801033d01480701001f00440607016c9005340002010400000091e20600700106070205015e01030093006e01040001450701024f465e0103016e0104000145090103eb715e0103026e0604000145050604286602016a1a000093a50400840a0843f352010a0045070705b510520107010207be1900005ba5040045090906304d5207090084080844f35207080152010702840a0856f356010a03bcec6e010506015e0303056e05040003440405077e9103da7802006e030405034b0201006b007e7600"
        ),
        FunctionPatch(
            14529, "", 195,
            "40080289040237080004890206890307370801033d01480701001f00440607016c9005340002010400000091e20600700106070205015e01030093006e0104000145090102eb715e0103016e060400014505060328660201181a0000bfa504008407084ef3520107005e0703036e0704000745070704872f520107020207be1900005ba50400450909057b1b520709008408084ff3520708015201070384090856f356010904bcec6e010506015e0303056e05040003440405067e9103b0e202006e030405034b0201006b007e7600"
        ),
        FunctionPatch(
            15420, "", 211,
            "4007038904023707000489010337070101890206890307370702033d01480801001f00440608016c9005340002010400000091e20600700106080205015e01030093006e0104000145090102eb715e0103016e060400014505060328660201371d0000efa10700840807a2fa520108005e0803036e0804000845080804d074520108020208be1900005ba5040045090905343852080900840907a3fa5208090152010803840707a4fa5201070484080756f356010805bcec6e010506015e0303076e05040003440405067e910339e202006e030405034b0201006b007e7600"
        ),
        FunctionPatch(17822, "report", 203, "93007e7e7e7600"),
        FunctionPatch(17853, "recordStart", 104, "93007600"),
        FunctionPatch(17855, "recordEnd", 201, "93007e7600"),
        FunctionPatch(17857, "set", 141, "93007e7600"),
        FunctionPatch(17865, "record", 148, "93007600"),
        FunctionPatch(17895, "resumeTracing", 150, "93007e7e7600"),
        FunctionPatch(17896, "mark", 124, "93007600"),
        FunctionPatch(17897, "markAndLog", 118, "93007e7e7600"),
        FunctionPatch(17898, "addImportLogDetail", 86, "93007e7e7600"),
        FunctionPatch(17899, "markWithDelta", 107, "93007e7e7e7600"),
        FunctionPatch(17900, "markAt", 187, "93007e7e7e7600"),
        FunctionPatch(17901, "addDetail", 132, "93007600"),
        FunctionPatch(17904, "setServerTrace", 28, "93007600"),
        FunctionPatch(19432, "handleFingerprint", 345, "93007e7600"),
        FunctionPatch(20310, "addBreadcrumb", 249, "93007e7600"),
        FunctionPatch(22939, "setUser", 104, "93007600"),
        FunctionPatch(22940, "clearUser", 78, "93007e7e7600"),
        FunctionPatch(22941, "setTags", 48, "93007600"),
        FunctionPatch(22942, "setExtra", 48, "93007600"),
        FunctionPatch(22943, "captureException", 87, "93007e7e7e7600"),
        FunctionPatch(22944, "captureCrash", 161, "93007e7600"),
        FunctionPatch(22945, "captureMessage", 90, "93007e7e7600"),
        FunctionPatch(22946, "addFeatureFlag", 106, "93007e7e7600"),
        FunctionPatch(22947, "addBreadcrumb", 54, "93007e7e7600"),
        FunctionPatch(22949, "crash", 27, "93007e7e7e7600"),
        FunctionPatch(22950, "triggerMemoryWarning", 27, "93007e7e7e7600"),
        FunctionPatch(22951, "markCrashHandled", 73, "93007e7600"),
        FunctionPatch(22958, "initSentry", 43, "93007e7e7e7600"),
        FunctionPatch(23017, "debugLogEvent", 79, "93007e7e7e7600"),
        FunctionPatch(23080, "track", 286, "93007e7e7600"),
        FunctionPatch(23088, "trackNetworkAction", 139, "93007e7e7e7600"),
        FunctionPatch(25056, "isZoomedExperimentEnabled", 35, "8b00007e7e7600"),
        FunctionPatch(33307, "trackWithMetadata", 369, "93007e7600"),
        FunctionPatch(33482, "overrideSurvey", 58, "93007e7e7600"),
        FunctionPatch(33483, "surveyHide", 157, "93007e7600"),
        FunctionPatch(33484, "surveyFetch", 196, "3d0048010000250044000100cf6c0000017e7600"),
        FunctionPatch(33485, "surveySeen", 241, "3d0048010000250044000100cf6c0000017e7e7600"),
        FunctionPatch(34274, "increment", 94, "93007e7e7600"),
        FunctionPatch(34275, "distribution", 112, "93007600"),
        FunctionPatch(34276, "_flush", 182, "93007e7e7600"),
        FunctionPatch(38577, "hasSocialLayerStorefront", 202, "96007e7e7600"),
        FunctionPatch(39932, "handleAppStateChange", 158, "93007e7e7600"),
        FunctionPatch(39933, "writeExistingEventStorage", 37, "93007e7600"),
        FunctionPatch(39934, "track", 37, "93007e7600"),
        FunctionPatch(39936, "", 38, "93007e7e7600"),
        FunctionPatch(39937, "", 137, "93007e7600"),
        FunctionPatch(39938, "", 18, "93007e7e7600"),
        FunctionPatch(39965, "initSessionHeartbeatScheduler", 290, "93007e7e7600"),
        FunctionPatch(40123, "recordChannelFetchStart", 181, "93007e7600"),
        FunctionPatch(40124, "recordChannelFetchedLocal", 200, "93007600"),
        FunctionPatch(40125, "recordChannelFetchedNetwork", 200, "93007600"),
        FunctionPatch(42692, "openPremiumUpsellActionSheet", 114, "93007e7e7600"),
        FunctionPatch(
            44014, "_maybeFetchProductsBySkuIds", 61,
            "3d0048010000250044000100cf6c0000017e7e7600"
        ),
        FunctionPatch(44387, "fetchGuildAffinities", 111, "3d0048010000250044000100cf6c0000017600"),
        FunctionPatch(45508, "trackImpression", 398, "93007e7e7600"),
        FunctionPatch(
            45591, "_fetchStorefrontPricesForApplicationId", 61,
            "3d0048010000250044000100cf6c0000017e7e7600"
        ),
        FunctionPatch(
            45592, "_fetchStorefrontPricesForSkuIds", 61,
            "3d0048010000250044000100cf6c0000017e7e7600"
        ),
        FunctionPatch(45655, "WrappedProfileEffect", 224, "94007600"),
        FunctionPatch(
            45873, "_maybeFetchCollectionsWithProducts", 61,
            "3d0048010000250044000100cf6c0000017e7e7600"
        ),
        FunctionPatch(47719, "openPremiumModal", 82, "93007e7e7600"),
        FunctionPatch(49956, "NitroUpsellButton", 221, "93007e7600"),
        FunctionPatch(
            51867, "_fetchSocialLayerStorefrontConfig", 61,
            "3d0048010000250044000100cf6c0000017e7e7600"
        ),
        FunctionPatch(
            51871, "fetchSocialLayerStorefrontSkuForApplication", 49,
            "3d0048010000250044000100cf6c0000017e7e7600"
        ),
        FunctionPatch(52007, "isListeningOnSpotify", 148, "8b00007e7e7e7600"),
        FunctionPatch(52667, "useShouldShowQuestsActivityPanelItem", 257, "8b00007600"),
        FunctionPatch(
            52680, "_fetchCurrentQuests", 61,
            "3d0048010000250044000100cf6c0000017e7e7600"
        ),
        FunctionPatch(52681, "_sendHeartbeat", 61, "3d0048010000250044000100cf6c0000017e7e7600"),
        FunctionPatch(
            52689, "_fetchClaimedQuests", 61,
            "3d0048010000250044000100cf6c0000017e7e7600"
        ),
        FunctionPatch(
            52690, "_fetchQuestToDeliver", 61,
            "3d0048010000250044000100cf6c0000017e7e7600"
        ),
        FunctionPatch(
            52691, "_fetchEarnedQuestToDeliver", 61,
            "3d0048010000250044000100cf6c0000017e7e7600"
        ),
        FunctionPatch(52968, "OrbsBalance", 179, "93007e7e7e7600"),
        FunctionPatch(52976, "QuestOrbsRewardModal", 1233, "93007e7600"),
        FunctionPatch(54101, "SpotifyTrack", 51, "96007e7e7e7600"),
        FunctionPatch(
            54121, "subscribePlayerStateNotifications", 122,
            "3d0048010000250044000100cf6c0000017e7e7e7600"
        ),
        FunctionPatch(54126, "fetchIsSpotifyProtocolRegistered", 101, "96007e7600"),
        FunctionPatch(55192, "ChatInputActionButtonGiftOrThread", 254, "93007e7e7600"),
        FunctionPatch(55199, "ChatInputActionButtonGift", 1107, "93007e7e7e7600"),
        FunctionPatch(
            57119, "isVirtualCurrencyEnabled", 51,
            "340200020121030000163102009600520100007e7e76013402003b0402013b0102025e03010093006e0504000345040500f544"
        ),
        FunctionPatch(
            57120, "useVirtualCurrencyMobileEnabled", 51,
            "340200020121030000163102009600520100007e7e7601"
        ),
        FunctionPatch(
            57553, "useProfileTabIndices", 79,
            "8b04018c02ffffffff890501100104100002b209058b01021000048906020205aa4d000093a5040052050000100001100302b20a061f00010410030189060352050301b20606100202520502027605"
        ),
        FunctionPatch(57595, "AddToWishlistGrid", 111, "93007e7e7e7600"),
        FunctionPatch(57596, "AddToWishlistItemCard", 422, "93007e7e7600"),
        FunctionPatch(57674, "OrbsPriceTag", 724, "93007600"),
        FunctionPatch(57688, "GiftButton", 261, "93007e7600"),
        FunctionPatch(58239, "GiftPurchaseButton", 565, "93007e7600"),
        FunctionPatch(58507, "logReadyPayloadReceived", 509, "93007e7600"),
        FunctionPatch(58508, "getConnectionPath", 101, "93007e7600"),
        FunctionPatch(58509, "getReadyPayloadByteSizeAnalytics", 892, "93007600"),
        FunctionPatch(58510, "logGatewayConnected", 184, "93007600"),
        FunctionPatch(60648, "handleTrack", 257, "93007e7600"),
        FunctionPatch(61440, "UserSettingsEditUserProfile", 221, "93007e7600"),
        FunctionPatch(62280, "useShowManageSubscriptionsSetting", 70, "96007e7e7600"),
        FunctionPatch(62289, "usePredicate", 34, "96007e7e7600"),
        FunctionPatch(62294, "usePredicate", 34, "96007e7e7600"),
        FunctionPatch(62298, "QuestHomeSetting", 387, "93007e7e7e7600"),
        FunctionPatch(62435, "QuestHomeOrbShopCarousel", 1030, "93007e7e7600"),
        FunctionPatch(62451, "useIsMobileQuestDockVisibleToUser", 208, "96007600"),
        FunctionPatch(62839, "useHasGuildRoleSubscriptionsSetting", 42, "96007e7e7600"),
        FunctionPatch(62954, "useHasPremiumRestoreSubscriptionSetting", 94, "96007e7e7600"),
        FunctionPatch(63907, "BalanceWidgetMenu", 237, "93007e7600"),
        FunctionPatch(63908, "BalanceWidgetMenuWrapper", 229, "93007e7600"),
        FunctionPatch(64167, "CollectiblesShopV2", 445, "93007e7600"),
        FunctionPatch(64168, "CollectiblesShopInternal", 2229, "93007e7600"),
        FunctionPatch(66872, "componentDidMount", 417, "93007e7600"),
        FunctionPatch(66873, "componentDidUpdate", 1515, "93007e7e7e7600"),
        FunctionPatch(
            66956, "", 1552,
            "4005098906014510060054544507060191db37050007451606028bdb37050116451506036f9245190604b4b446110605ae8e04004512060604ce9301d50612019712340600390502013905030139050401390505013905060139050701390508013b1406003b0806025e09080d6e0c140109440b0c07f53b090607080a01005a0a0900850906e69b01006f0d0b0c0a093b0906015e0a080e6e0b09010a940e16020d0e930ab00802440a0d08546e0c0b010a5e0a080f6e0b09010a020a563600005ba50400520a0d00520a0c016e0b0b010a45180b097c8a450a0b0a84d9450f0b0bfdc95e0b08106e0d14010b450c0d0c8aed020b4f36000093a50400520b1800520b0a01520b0f026e0c0c0d0b450b0c0d3245450d0c0ed902450c0c0f07c43705020c5e1308116e1714011345131710a6cf6e13131718b2151318020e0a940ab20f0218020e0f940ab20602100a0b3705030a3b0f060344130f11f2080b02005a0b0c005a0b0a01850a05e79b01006f21130f0a0b3b0a060d932310221210200c101f0d6a0c0a055e0a08136e0b14010a450a0b126bae6c0b0a0b5e0a08096e0d14010a450a0d1384ed6c0a0a0d440a0a14a597001c020a005e0a080a6e0d14010a450a0d1575ed3b1b06096e0a0a0d1b18040e0a5e0a080b6e1214010a450d121679535e0a080c6e0a14010a450a0a173746450a0a182b516e0a0d120a13030ab0060a100304b00602100203390504025e0a08146e0a09010a6c0a0a01450d0a19818f3705050d460a0a1a93b404003705060a5e1208156e131401124512131b59be6c1712135e1208166e181401124613181ce56004009112618402006e121318124413121d794512121e61855e18080a6e1a14011845181a1575ed6e18181a1b18030e1839050703441a0f1ff0081803005a1802005a1807015a180302850705e89b01006f071a0f0718441a0f1ff0081801005a181600851605e99b01006f161a0f1618441a0f1ff0081802005a180d005a180a01850a05ea9b01006f1b1a0f0a18940ab2700a3b1a060a5e17081a6e18090117021785600000bfa504005e1c081b6e1c14011c451c1c200d6f52171c005e1c081c6e1c14011c441e1c2197441d1e22e15e1c081c6e1c14011c441c1c230a451c1c244b0b6e1c1d1e1c52171c0152171b0252170d03900d3550700a1a0118170d080d04005a0d0a003b0a060a5e17081d6e180901170217866000005ba50400521719005217150190159b6c70150a011817155a0d1501b19d0000000b5e0b081a6e1709010b021587600000bfa504005e0b081f6e0b14010b450b0b25b86752150b005e0b081c6e0b14010b44190b219744181922e15e0b081c6e0b14010b440b0b230a450b0b2676386e0b18190b52150b015e0b081c6e0b14010b44190b219744181922e15e0b081c6e0b14010b440b0b230a450b0b2676386e0b18190b52150b0252151603900b00a6700b0a0117150bae32940eb22b135e13081e6e150901130213896000005ba5040052131600521312019112cf260200700e0a01151312100b0e5a0d0b025e0b081a6e0e09010b020b88600000efa10700520b11005e1108206e1114011145111127d264520b11015e11081c6e11140111441311219744121322e15e11081c6e11140111441111230a45111128720a6e11121311520b1102520b0703520b02049007c58970070a010e0b075a0d0703440b0d2936850701eb9b01006e110b0d073b07060c3b0d060bb239103b0e0604020be02300005ba5040045120c2a996b520b12003b13060502125d0e0000cf1e0b006f120a011312520b12016f100a010e0b020bef23000016310200080e02005a0e10003b0606040210d8230000f84e230046120c2b6fbe040052101200521011026f100a0106105a0e1001520b0e006f0b07010d0b450d0c2c577544100d2d5a37050810440e0f11f2080d01005a0d1000850505ec9b01006f210e0f050d0205d8230000f84e2300460e0c2e94da040052050e005e0808216e090901080208af01000016310200450e0c2c577552080e00102208730d2e03900d6a6956080d007f226f090a010908080802005a0809000209e02300005ba50400460c0c2fee6e040052090c0052090b016f090a0106095a080901520508026f05070106057605"
        ),
        FunctionPatch(66966, "CollectiblesShopEntryButton", 477, "93007e7600"),
        FunctionPatch(66967, "MobileShopButtonCoachmark", 349, "93007e7600"),
        FunctionPatch(66981, "EditSection", 944, "93007600"),
        FunctionPatch(67890, "trackHttpRequest", 220, "93007600"),
        FunctionPatch(68080, "_trackStartSpeaking", 236, "93007600"),
        FunctionPatch(68081, "_trackStartListening", 235, "93007e7e7e7600"),
        FunctionPatch(68460, "onPostConnectionOpen", 34, "93007e7e7600"),
        FunctionPatch(69656, "handleAppStateUpdate", 230, "93007e7e7600"),
        FunctionPatch(69681, "installWebsocketTelemetryHook", 182, "93007e7e7600"),
        FunctionPatch(69723, "setupLibdiscoreTimersMonitor", 39, "93007e7e7e7600"),
        FunctionPatch(69782, "logDangerously", 146, "93007e7e7600"),
        FunctionPatch(69783, "log", 206, "93007e7e7600"),
        FunctionPatch(69784, "verboseDangerously", 146, "93007e7e7600"),
        FunctionPatch(69785, "verbose", 206, "93007e7e7600"),
        FunctionPatch(69786, "info", 206, "93007e7e7600"),
        FunctionPatch(69789, "trace", 146, "93007e7e7600"),
        FunctionPatch(69791, "fileOnly", 148, "93007600"),
        FunctionPatch(73760, "track", 539, "3d0048010000250044000100cf6c0000017600"),
        FunctionPatch(
            73765, "submitEventsImmediately", 250,
            "3d0048010000250044000100cf6c0000017e7e7e7600"
        ),
        FunctionPatch(73887, "", 134, "93007e7e7600"),
        FunctionPatch(79521, "_handleVoiceQualityPeriodicsStats", 508, "93007600"),
        FunctionPatch(96053, "flush", 82, "93007e7e7600"),
        FunctionPatch(96164, "add", 511, "93007e7e7e7600"),
        FunctionPatch(96167, "_flush", 374, "93007e7e7600"),
        FunctionPatch(96168, "_captureMetrics", 97, "93007e7600"),
        FunctionPatch(96189, "add", 443, "93007e7e7e7600"),
        FunctionPatch(96190, "flush", 114, "93007e7e7600"),
        FunctionPatch(97875, "sampleStats", 319, "93007e7e7e7600"),
        FunctionPatch(97883, "sampleStats", 68, "93007600"),
        FunctionPatch(97896, "sampleStats", 112, "93007600"),
        FunctionPatch(
            105481, "", 594,
            "3404000202304b0000f26b2c003408013b0a08003b0508025e01051f93006e010a0001440601009744030601e15e01051f6e010a0001440101020a460101030cde04006e01030601520201013b07080e3b0608050201537200004bd007008503049dd80100520103013b03041e6c030300520103026f010700060152020102080101005a0102003b020411b3ad0000000244030104c80202304b0000455723005e09051f6e090a0009440c090097440b0c01e15e09051f6e090a0009440909020a46090905803604006e090b0c0952020901020b537200004bd007008509049ed80100520b09013b0c0812020906780000bfa504003b0d0400440d0d065452090d003b0d041952090d013b0d040b52090d023b0d040c52090d036f0907000c09520b09023b09041370090700060b09520209026e020301023b020412b3a00000000244030104c80202304b000089810c005e09051f6e090a0009440c090097440b0c01e15e09051f6e090a0009440909020a46090907db6204006e090b0c09520209010209537200004bd00700850b049fd8010052090b013b0b081302080778000093a504003b0c040052080c003b0c040b52080c013b0c040c52080c026f0807000b08520908023b08041470080700060908520208026e020301027601"
        ),
        FunctionPatch(108532, "handlePostConnectionOpen", 373, "93007e7600"),
        FunctionPatch(
            117345, "", 213,
            "34030142050301000000890701370500073b0803003b0403025e03040893016e0608010345030600cacc6e06030607130306b1a1000000063406005e0904096e0a08010945090a011dc06e09090a07b04b095e0904096e0a08010945090a0221d06e09090a07b019095e0404096e0808010445040803e24d6e04040807ae1b3b0a060045090a047a958508054eea01006e08090a08130408ae3845070705228245070706914644070707a597001c010000b21c013b070600450607047a958505054dea01006e050607051301051004011003047603"
        ),
        FunctionPatch(121591, "", 31, "93007e7e7e7600")
    )

    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            out[i] = ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }
}
