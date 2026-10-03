package dev.sleepy.app.patches

/**
 * The Discord JavaScript bundle patch set, extracted from the reference desktop build's paired
 * Discord 349.5 APKs: the base split at `build/alpha3495/apk/extracted/base.apk` and the patched
 * `build/alpha3495/out/discord-alpha-349.5-patched-unsigned.apk`, both under the
 * `quirky-noether/discord` checkout.
 *
 * Every entry is a whole function body taken from that pair: the base supplies the function's
 * size, and the patched build supplies its replacement bytes. The set covers the 166 functions
 * that differ between the two, and applying it reproduces 164 of them byte for byte. The other
 * two, 49956 and 58239, carry a deliberate divergence. The reference stubs both to `undefined`,
 * and the one caller of each function dereferences that return: 49956's caller reads `.result`
 * and 58239's caller reads `.length`. Both reads throw a TypeError on `undefined`, so the
 * reference's own stub throws in the shipped app wherever those paths run. The replacements here
 * return a value of the shape the caller reads instead, and `HermesBundleParityTest` carries a
 * named exception for the two ids that checks each divergence in both directions. The bodies are
 * otherwise the reference build's own output, not a reconstruction of it.
 *
 * The set is encoded as data rather than derived from the shapes in [DiscordPatches]. A
 * promise-shaped stub must name "Promise" and "resolve" through the bundle's own string
 * identifiers, which are not the string-table indices and are not resolvable without the
 * identifier table, and the feature-removal edits are whole-body replacements that the reference's
 * own assembler produced. [DiscordPatches] derives the smaller stubs, whose shape the reference's
 * tables describe.
 *
 * Five entries need relocation rather than an in-place write: the replacement bodies at 14786,
 * 14790, 14797 and 15698 are longer than the body they replace, and 62046 shares its body with
 * 62045, which takes the shared region. See [dev.sleepy.app.engine.HermesBundlePatcher] for how
 * relocation works.
 */
object DiscordHermesBundlePatch {

    /** Byte length of the bundle this table was extracted from. */
    const val TARGET_BUNDLE_SIZE = 67299195

    /** Function count of that bundle. */
    const val TARGET_FUNCTION_COUNT = 155429

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
        FunctionPatch(13894, "", 186, "93007e7e7600"),
        FunctionPatch(
            14786, "", 348,
            "4008078904023708000489050337080105890206890307370802033d01480901001800440709016690063000020104000000674e0800700107090206015e06030093008901046e01010006370803015e0103016e01050001370804015e0103026e01050001370805015e0103036e010400014509010204775e0103046e01040001440101039a370806015e0103086e0504000144010504966c010105b00c01850a08ca0b0100ae09850a08c90b01005e0103086e0504000144010504966c010105b00c01850708cc0b0100ae09850708cb0b01005e01030d6e0604000145050605fdaa0201371b00006f4e0800850b08cd0b010052010b005e0b030e6e0b04000b450b0b06996352010b0252010a03520107040207d5190000b2cc040045090907090952070900850808ce0b01005207080152010705850b08f10b010056010b06edef6e010506015e0303106e05040003440405087991035edc02006e030405034b02010065007e7e7e7600"
        ),
        FunctionPatch(
            14790, "", 205,
            "40080289040237080004890206890307370801033d01480701001800440607016690053000020104000000674e0800700106070205015e01030093006e01040001450701028b425e0103016e010400014509010304775e0103026e0604000145050604fdaa0201771a0000f7c80400850a08d70b010052010a00450707050909520107010207d5190000b2cc040045090906a06652070900850808d80b01005207080152010702850a08f10b010056010a03edef6e010506015e0303056e0504000344040507799103cf7002006e030405034b02010065007e7e7e7600"
        ),
        FunctionPatch(
            14797, "", 199,
            "40080289040237080004890206890307370801033d01480701001800440607016690053000020104000000674e0800700106070205015e01030093006e010400014509010204775e0103016e0604000145050603fdaa0201341a0000fbc80400850708e70b0100520107005e0703036e0704000745070704872e520107020207d5190000b2cc040045090905fe4952070900850808e80b01005207080152010703850908f10b010056010904edef6e010506015e0303056e050400034404050679910359dc02006e030405034b02010065007e7e7e7600"
        ),
        FunctionPatch(
            15698, "", 217,
            "4007038904023707000489010337070101890206890307370702033d01480801001800440608016690053000020104000000674e0800700106080205015e01030093006e010400014509010204775e0103016e0604000145050603fdaa02014f1d0000bf4f0800850807cb160100520108005e0803036e08040008450808045f71520108020208d5190000b2cc040045090905d62a52080900850907cc1601005208090152010803850707cd16010052010704850907f10b010056010905edef6e010506015e0303076e0504000344040506799103e0db02006e030405034b02010065007e7e7e7600"
        ),
        FunctionPatch(18172, "report", 203, "93007e7e7e7600"),
        FunctionPatch(18203, "recordStart", 103, "93007e7e7e7600"),
        FunctionPatch(18205, "recordEnd", 199, "93007e7e7e7600"),
        FunctionPatch(18207, "set", 140, "93007600"),
        FunctionPatch(18215, "record", 147, "93007e7e7e7600"),
        FunctionPatch(18245, "resumeTracing", 150, "93007e7e7600"),
        FunctionPatch(18246, "mark", 124, "93007600"),
        FunctionPatch(18247, "markAndLog", 118, "93007e7e7600"),
        FunctionPatch(18248, "addImportLogDetail", 86, "93007e7e7600"),
        FunctionPatch(18249, "markWithDelta", 107, "93007e7e7e7600"),
        FunctionPatch(18250, "markAt", 187, "93007e7e7e7600"),
        FunctionPatch(18251, "addDetail", 131, "93007e7e7e7600"),
        FunctionPatch(18254, "setServerTrace", 28, "93007600"),
        FunctionPatch(19786, "handleFingerprint", 345, "93007e7600"),
        FunctionPatch(19937, "getMetricsSampleRate", 81, "8b00007600"),
        FunctionPatch(19939, "shouldCollectMetrics", 86, "96007e7e7600"),
        FunctionPatch(20709, "addBreadcrumb", 242, "93007e7e7600"),
        FunctionPatch(23353, "setUser", 104, "93007600"),
        FunctionPatch(23354, "clearUser", 78, "93007e7e7600"),
        FunctionPatch(23355, "setTags", 48, "93007600"),
        FunctionPatch(23356, "setExtra", 48, "93007600"),
        FunctionPatch(23357, "captureException", 87, "93007e7e7e7600"),
        FunctionPatch(23358, "captureCrash", 162, "93007e7e7600"),
        FunctionPatch(23359, "captureMessage", 90, "93007e7e7600"),
        FunctionPatch(23360, "addFeatureFlag", 106, "93007e7e7600"),
        FunctionPatch(23361, "addBreadcrumb", 54, "93007e7e7600"),
        FunctionPatch(23363, "crash", 27, "93007e7e7e7600"),
        FunctionPatch(23364, "triggerMemoryWarning", 27, "93007e7e7e7600"),
        FunctionPatch(23365, "markCrashHandled", 74, "93007e7e7600"),
        FunctionPatch(23372, "initSentry", 43, "93007e7e7e7600"),
        FunctionPatch(23431, "debugLogEvent", 79, "93007e7e7e7600"),
        FunctionPatch(23494, "track", 288, "93007600"),
        FunctionPatch(23502, "trackNetworkAction", 135, "93007e7e7e7600"),
        FunctionPatch(25451, "isZoomedExperimentEnabled", 35, "8b00007e7e7600"),
        FunctionPatch(33861, "trackWithMetadata", 365, "93007e7600"),
        FunctionPatch(34036, "surveyFetch", 198, "3d00480100001e00450001008c016c0000017e7e7600"),
        FunctionPatch(34868, "increment", 96, "93007600"),
        FunctionPatch(34869, "distribution", 114, "93007e7e7600"),
        FunctionPatch(34870, "_flush", 183, "93007e7e7e7600"),
        FunctionPatch(38875, "hasSocialLayerStorefront", 204, "96007600"),
        FunctionPatch(40422, "handleAppStateChange", 158, "93007e7e7600"),
        FunctionPatch(40423, "writeExistingEventStorage", 37, "93007e7600"),
        FunctionPatch(40424, "track", 37, "93007e7600"),
        FunctionPatch(40426, "", 38, "93007e7e7600"),
        FunctionPatch(40427, "", 137, "93007e7600"),
        FunctionPatch(40428, "", 18, "93007e7e7600"),
        FunctionPatch(40455, "initSessionHeartbeatScheduler", 292, "93007600"),
        FunctionPatch(40613, "recordChannelFetchStart", 181, "93007e7600"),
        FunctionPatch(40614, "recordChannelFetchedLocal", 200, "93007600"),
        FunctionPatch(40615, "recordChannelFetchedNetwork", 200, "93007600"),
        FunctionPatch(43768, "openPremiumUpsellActionSheet", 114, "93007e7e7600"),
        FunctionPatch(
            45460, "_maybeFetchProductsBySkuIds", 61,
            "3d00480100001e00450001008c016c0000017e7600"
        ),
        FunctionPatch(
            45901, "fetchGuildAffinities", 112,
            "3d00480100001e00450001008c016c0000017600"
        ),
        FunctionPatch(47123, "trackImpression", 398, "93007e7e7600"),
        FunctionPatch(
            47225, "_fetchStorefrontPricesForApplicationId", 61,
            "3d00480100001e00450001008c016c0000017e7600"
        ),
        FunctionPatch(
            47226, "_fetchStorefrontPricesForSkuIds", 61,
            "3d00480100001e00450001008c016c0000017e7600"
        ),
        FunctionPatch(47308, "", 468, "94007600"),
        FunctionPatch(47309, "", 218, "94007e7e7600"),
        FunctionPatch(
            47611, "_maybeFetchCollectionsWithProducts", 61,
            "3d00480100001e00450001008c016c0000017e7600"
        ),
        FunctionPatch(49760, "openPremiumModal", 82, "93007e7e7600"),
        // The id held a Nitro upsell button in 348.5 and the confirm-modal RPC handler in 349.5.
        // The caller reads .result off the return and the RPC client reads the response this
        // object carries, so the body returns the object that read expects, built from the
        // object templates of the body it replaces: {result: {confirmed: false}, answered: null,
        // subject: null}. The SHOW_CONFIRM_MODAL response schema takes an optional confirmed
        // boolean, which this answers with false: the modal was not confirmed.
        FunctionPatch(49956, "", 133, "0100563b78cd9601520001000201573b0000f7c80400520100007e7601"),
        FunctionPatch(52476, "", 442, "93007e7e7600"),
        FunctionPatch(52477, "", 217, "93007e7600"),
        FunctionPatch(
            53460, "_fetchCurrentQuests", 61,
            "3d00480100001e00450001008c016c0000017e7600"
        ),
        FunctionPatch(53461, "_sendHeartbeat", 61, "3d00480100001e00450001008c016c0000017e7600"),
        FunctionPatch(
            53469, "_fetchClaimedQuests", 61,
            "3d00480100001e00450001008c016c0000017e7600"
        ),
        FunctionPatch(
            53470, "_fetchQuestToDeliver", 61,
            "3d00480100001e00450001008c016c0000017e7600"
        ),
        FunctionPatch(
            53471, "_fetchEarnedQuestToDeliver", 61,
            "3d00480100001e00450001008c016c0000017e7600"
        ),
        FunctionPatch(
            55149, "_fetchSocialLayerStorefrontConfig", 61,
            "3d00480100001e00450001008c016c0000017e7600"
        ),
        FunctionPatch(
            55153, "fetchSocialLayerStorefrontSkuForApplication", 49,
            "3d00480100001e00450001008c016c0000017e7600"
        ),
        FunctionPatch(55351, "isListeningOnSpotify", 148, "8b00007e7e7e7600"),
        FunctionPatch(56206, "", 337, "8b00007600"),
        FunctionPatch(56456, "", 1721, "93007e7600"),
        FunctionPatch(56457, "", 1221, "93007e7600"),
        FunctionPatch(57616, "SpotifyTrack", 51, "96007e7e7e7600"),
        FunctionPatch(
            57635, "subscribePlayerStateNotifications", 118,
            "3d00480100001e00450001008c016c0000017e7e7600"
        ),
        FunctionPatch(57640, "fetchIsSpotifyProtocolRegistered", 101, "96007e7600"),
        // The id held a gift purchase button in 348.5 and the uncompiled variant of
        // useTypingUserIdsForDisplay in 349.5. The caller passes the return to
        // hasTypingIndicatorContent, which reads .length, so the body returns an empty array:
        // the hook's own shape, holding no typing users.
        FunctionPatch(58239, "", 173, "080000007e7600"),
        FunctionPatch(59199, "", 365, "93007e7600"),
        FunctionPatch(59200, "", 254, "93007e7e7600"),
        FunctionPatch(59211, "", 1827, "93007e7e7e7600"),
        FunctionPatch(59212, "", 1089, "93007e7600"),
        FunctionPatch(61440, "", 328, "93007600"),
        FunctionPatch(
            62045, "isVirtualCurrencyEnabled", 47,
            "34020001011f0378cd9600520100007e7e76013402003b0402013b0102025e03010093006e05040003450405003060"
        ),
        FunctionPatch(
            62046, "useVirtualCurrencyMobileEnabled", 47,
            "34020001011f0378cd9600520100007e7e7601"
        ),
        FunctionPatch(62435, "", 393, "93007e7600"),
        FunctionPatch(
            62714, "useProfileTabIndices", 79,
            "8b04018c02ffffffff890501100104100002b209058b01021000048906020205994f0000f7c8040052050000100001100302b20a061f00010410030189060352050301b20606100202520502027605"
        ),
        FunctionPatch(62778, "", 401, "93007e7600"),
        FunctionPatch(62779, "", 110, "93007e7e7600"),
        FunctionPatch(62783, "AddToWishlistItemCard", 417, "93007e7600"),
        FunctionPatch(62880, "", 405, "93007e7600"),
        FunctionPatch(62881, "", 183, "93007e7e7e7600"),
        FunctionPatch(62907, "", 498, "93007e7e7600"),
        FunctionPatch(62908, "", 260, "93007600"),
        FunctionPatch(63721, "", 1101, "93007e7600"),
        FunctionPatch(63722, "", 564, "93007600"),
        FunctionPatch(64027, "logReadyPayloadReceived", 509, "93007e7600"),
        FunctionPatch(64028, "getConnectionPath", 101, "93007e7600"),
        FunctionPatch(64029, "getReadyPayloadByteSizeAnalytics", 892, "93007600"),
        FunctionPatch(64030, "logGatewayConnected", 184, "93007600"),
        FunctionPatch(66426, "handleTrack", 257, "93007e7600"),
        FunctionPatch(67311, "", 449, "93007e7600"),
        FunctionPatch(67312, "", 216, "93007600"),
        FunctionPatch(68573, "", 139, "96007e7e7e7600"),
        FunctionPatch(68574, "", 70, "96007e7e7600"),
        FunctionPatch(68590, "usePredicate", 34, "96007e7e7600"),
        FunctionPatch(68593, "usePredicate", 34, "96007e7e7600"),
        FunctionPatch(68600, "", 688, "93007600"),
        FunctionPatch(68601, "", 298, "93007e7e7600"),
        FunctionPatch(68858, "", 426, "96007e7e7600"),
        FunctionPatch(69421, "", 42, "96007e7e7600"),
        FunctionPatch(69606, "", 208, "96007600"),
        FunctionPatch(69607, "", 94, "96007e7e7600"),
        FunctionPatch(70912, "BalanceWidgetMenu", 233, "93007e7600"),
        FunctionPatch(70917, "", 418, "93007e7e7600"),
        FunctionPatch(70918, "", 229, "93007e7600"),
        FunctionPatch(70961, "overrideSurvey", 58, "93007e7e7600"),
        FunctionPatch(70962, "surveyHide", 153, "93007e7600"),
        FunctionPatch(70963, "surveySeen", 243, "3d00480100001e00450001008c016c0000017e7e7e7600"),
        FunctionPatch(71379, "", 4164, "93007600"),
        FunctionPatch(71380, "", 2196, "93007600"),
        FunctionPatch(71381, "", 662, "93007e7e7600"),
        FunctionPatch(71382, "", 445, "93007e7600"),
        FunctionPatch(75525, "componentDidMount", 405, "93007e7600"),
        FunctionPatch(75526, "componentDidUpdate", 1503, "93007e7e7e7600"),
        FunctionPatch(
            75650, "", 1566,
            "400409890301450f0300bfdc3704000f450a0301bcdc3704010a45130302c0dc45160303d4b746110304148b040045120305d46d9301d50612019712340300390402013904030139040401390405013904060139040701390408013b060306850503bdf101006e0e0601053b0c03003b0603025e0506106e090c010544080906f53b050307080701005a070500850503bef101006f0d080907053b0703015e0506116e09070105940816020d089305b0080244050d074e6e0b0901055e0506126e090701050205d4360000b2cc040052050d0052050b016e0909010544170908e54505090904db450d090a08db5e0906136e100c0109450b100b05f10209ce360000f7c80400520917005209050152090d026e0b0b100945090b0c4d5f45100b0db602450b0b0e16c73704020b5e1406146e150c01144514150f6f5c6e14141517b21514180208059405b20f021802080d9405b20602100509370403053b0d030344140d10f1080902005a090b005a090501850504bff101006f20140d05093b05030c9322102112101f0b101e106a0b05055e0506166e090c0105450509116ee16c1205093b05030d6c050501370404055e0906176e090701096c090901451409122d90370405144509091373b0370406095e1006186e150c0110451015145fc16c1510155e1006196e180c0110461718152d0405009110ae7802006e171718105e10060c6e190c011045181916f7f03b1003096e10181910180208103904070244180d17f0081003005a1005005a100f015a100202850f04c0f101006f10180d0f1044180d17f0080f01005a0f0a00850a04c1f101006f0f180d0a0f44180d17f0080a02005a0a14005a0a0901850904c2f101006f19180d090a44180d17f0080a0000850903c3f101006f09180d090a940ab267173b1a030a5e1706206e180701170217f7630000f7c804005e1b06216e1b0c011b451b1b188c5f52171b005e1b060f6e1b0c011b441d1b1995441c1d1ae05e1b06226e1b07011b451b1b1b6d196e1b1c1d1b52171b01521709029009430a700a1a01181709080905005a090a00940ab2700a3b18030a5e1506206e170701150215f8630000fbc804005e1a06236e1a0c011a451a1a1c5b6b52151a005e1a060f6e1a0c011a441c1a1995441b1c1ae05e1a060f6e1a0c011a441a1a1d0a451a1a1ebd0d6e1a1b1c1a52151a01521519025215140390142b2f700a18011715145a090a013b0a030a5e1406246e150701140214f9630000b2cc040052141600521413019013a84170130a011514135a0913029408b19b000000125e1206206e130701120212fa630000fbc804005e1406256e140c01144514141f9963521214005e14060f6e140c011444161419954415161ae05e14060f6e140c01144414141d0a4514142038366e14151614521214015e14060f6e140c011444161419954415161ae05e14060f6e140c01144414141d0a4514142038366e141516145212140252120f03900f09dd70080a0113120f5a0908035e0806206e0f0701080208fb630000bf4f0800520811005e1106266e110c011145111121d160520811015e11060f6e110c011144131119954412131ae05e11060f6e110c01114411111d0a451111223e0c6e111213115208110252081003520805049005ec8670050a010f08055a0905044408092332850501c4f101006e0f0809053b0503040208072400005b30240046090b24702a0400520809005e0906276e090c0109460c0925d8cf04000109252178cd52090f0070090a010c090e520809026f0c0a01050845080b26079e440e0827543704080e44090d10f1080801005a080e00850404c5f101006f20090d04083b04030b0203072400005b30240046090b28bc880400520309005e0606286e070701060106ae0178cd45090b26079e5206090010210673082e0390088c1156060800d1226f070a010706080602005a06070002070f240000b2cc040046080b29fb7e0400520708000208d02f0000229a1e0045090b2a639f520809006f090a010508080803005a08090002090f240000b2cc0400460d0b2bdbea040052090d0052090c016f090a0105095a0809010209d02f0000229a1e00450b0b2a639f52090b006f090a0105095a080902520708016f07040105075a060701520306026f03040105037603"
        ),
        FunctionPatch(75687, "", 731, "93007e7e7e7600"),
        FunctionPatch(75688, "", 477, "93007e7600"),
        FunctionPatch(75689, "", 735, "93007e7e7e7600"),
        FunctionPatch(75690, "", 349, "93007e7600"),
        FunctionPatch(77257, "trackHttpRequest", 220, "93007600"),
        FunctionPatch(77458, "_trackStartSpeaking", 238, "93007e7e7600"),
        FunctionPatch(77459, "_trackStartListening", 237, "93007e7600"),
        FunctionPatch(79457, "handleAppStateUpdate", 230, "93007e7e7600"),
        FunctionPatch(79491, "installWebsocketTelemetryHook", 183, "93007e7e7e7600"),
        FunctionPatch(79533, "setupLibdiscoreTimersMonitor", 39, "93007e7e7e7600"),
        FunctionPatch(79599, "logDangerously", 146, "93007e7e7600"),
        FunctionPatch(79600, "log", 206, "93007e7e7600"),
        FunctionPatch(79601, "verboseDangerously", 146, "93007e7e7600"),
        FunctionPatch(79602, "verbose", 206, "93007e7e7600"),
        FunctionPatch(79603, "info", 206, "93007e7e7600"),
        FunctionPatch(79606, "trace", 146, "93007e7e7600"),
        FunctionPatch(79608, "fileOnly", 148, "93007600"),
        FunctionPatch(83581, "track", 544, "3d00480100001e00450001008c016c0000017600"),
        FunctionPatch(
            83586, "submitEventsImmediately", 251,
            "3d00480100001e00450001008c016c0000017e7e7e7600"
        ),
        FunctionPatch(83707, "", 134, "93007e7e7600"),
        FunctionPatch(89454, "_handleVoiceQualityPeriodicsStats", 508, "93007600"),
        FunctionPatch(112318, "flush", 82, "93007e7e7600"),
        FunctionPatch(112429, "add", 511, "93007e7e7e7600"),
        FunctionPatch(112432, "_flush", 374, "93007e7e7600"),
        FunctionPatch(112433, "_captureMetrics", 97, "93007e7600"),
        FunctionPatch(112454, "add", 443, "93007e7e7e7600"),
        FunctionPatch(112455, "flush", 114, "93007e7e7600"),
        FunctionPatch(
            113052, "", 213,
            "34030142050301000000890701370500073b0803003b0403025e03040a93016e06080103450306005bd16e06030607130306b1a1000000063406005e09040b6e0a08010945090a0159d26e09090a07b04b095e09040b6e0a08010945090a02cdd46e09090a07b019095e04040b6e080801044504080381d36e04040807ae1b3b0a060145090a04bdbd850805532d02006e08090a08130408ae3845070705167f45070706a5b444070707a497001c010000b21c013b07060145060704bdbd850505522d02006e050607051301051004011003047603"
        ),
        FunctionPatch(115029, "sampleStats", 395, "93007e7e7e7600"),
        FunctionPatch(115037, "sampleStats", 68, "93007600"),
        FunctionPatch(115050, "sampleStats", 112, "93007600"),
        FunctionPatch(
            127527, "", 572,
            "3404000202684c00006cc70c003408013b0a08003b0508025e01051c93006e010a0001440601009544030601e05e01051c6e010a0001440101020a4501010371276e01030601520201013b07080e3b0608050101224f6ca98503041e400200520103013b03041f6c030300520103026f010700060152020102080101005a0102003b020411b3a70000000044030104c60202684c0000ae1f0a005e09051c6e090a0009440c090095440b0c01e05e09051c6e090a0009440909020a45090905480a6e090b0c0952020901010b224f6ca98509041f400200520b09013b0c081302091b640000fbc804003b0d0400440d0d064e52090d003b0d041a52090d013b0d040b52090d023b0d040c52090d036f0907000c09520b09023b09041370090700060b09520209026e020301023b020412b39a0000000244030104c60202684c000031ee0c005e09051c6e090a0009440c090095440b0c01e05e09051c6e090a0009440909020a45090907f1a26e090b0c09520209010109224f6ca9850b042040020052090b013b0b081402081c640000f7c804003b0c040052080c003b0c040b52080c013b0c040c52080c026f0807000b08520908023b08041470080700060908520208026e0203010244030104c60202684c000084c70c005e08051c6e080a0008440908009544080901e05e05051c6e050a0005440505020a45050508940e6e05080905520205010105224f6ca985080421400200520508013b0804206c080800520508023b04041570040700060504520204026e020301027601"
        ),
        FunctionPatch(132693, "handlePostConnectionOpen", 373, "93007e7600"),
        FunctionPatch(
            142676, "", 213,
            "34030142050301000000890701370500073b0803003b0403025e03040a93016e06080103450306005bd16e06030607130306b1a1000000063406005e09040b6e0a08010945090a0159d26e09090a07b04b095e09040b6e0a08010945090a02cdd46e09090a07b019095e04040b6e080801044504080381d36e04040807ae1b3b0a060045090a04bdbd8508058e5302006e08090a08130408ae3845070705167f45070706a5b444070707a497001c010000b21c013b07060045060704bdbd8505058d5302006e050607051301051004011003047603"
        ),
        FunctionPatch(148336, "", 31, "93007e7e7e7600"),
    )

    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            out[i] = ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }
}
