#!/usr/bin/env python3
"""Check every request made of this build, against the built artifact.

`verify-stubs.py` answers "did every Hermes stub land?". This answers the
broader question: "did we disable everything I asked for, and is anything
still live that shouldn't be?".

It checks the BUILT APK for manifest flags, permissions, native libraries and
packaged files, because the decompiled tree is an intermediate and a build can
in principle disagree with it. Smali bodies and the Hermes bundle are read from
the tree - the same tree the APK was built from - and `verify-stubs.py` covers
the packed bundle independently.

Every check below reads a marker that the patch suite itself writes, so a patch
that silently stopped applying shows up as a FAIL rather than passing on a
stale tree.

    tools/verify-asks.py [apk] [base-dir]        (defaults: dist/, build/alpha3482/)
"""

import os
import re
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
def _newest_build():
    """Newest `build/<ver>/` that actually contains a built APK.

    These defaults used to be spelled out, and they rotted: the file still
    pointed at `build/alpha3482/` two releases later, so the whole script sat
    unused while a real regression (the media scope, see
    reference/MEDIA_SCOPE_REGRESSION.md) went unnoticed. Deriving them means a
    version bump cannot leave this checking a tree nobody builds any more.
    """
    builds = os.path.join(ROOT, "build")
    if not os.path.isdir(builds):
        return None, None
    for ver in sorted(os.listdir(builds), reverse=True):
        base = os.path.join(builds, ver, "decompiled", "base")
        out = os.path.join(builds, ver, "out")
        if not os.path.isdir(base) or not os.path.isdir(out):
            continue
        apks = sorted(
            f for f in os.listdir(out)
            if f.endswith(".apk") and "aligned-signed" in f
        )
        if apks:
            return os.path.join(out, apks[-1]), base
    return None, None


DEFAULT_APK, DEFAULT_BASE = _newest_build()
SMALI_DIRS = ["smali", "smali_classes2", "smali_classes3", "smali_classes4"]
DSNS = [
    "9a42ef460144a03b30c8b2d5321cfe11",
    "70545531dfe34835bf4dd0996821e8b6",
    "90509cba01573ee4e14a2f5e15aee5ca",
]

results = []


def check(section, what, ok, detail=""):
    results.append((section, what, bool(ok), detail))


def sfile(base, rel):
    for d in SMALI_DIRS:
        p = os.path.join(base, d, rel)
        if os.path.exists(p):
            return p
    return os.path.join(base, SMALI_DIRS[0], rel)


def body(path, name, desc_has=None):
    """Body of the method DECLARED as `name`. Empty string if absent.

    Match the `.method` declaration line, never the first occurrence of the
    name in the file. A plain substring search finds the *caller* first - a
    class's `<clinit>` usually calls the method before the method itself
    appears - and silently returns the wrong method's body, which reads as a
    failed patch when the patch is in fact applied.
    """
    if not os.path.exists(path):
        return ""
    src = open(path, encoding="utf-8").read()
    for m in re.finditer(r"^\.method [^\n]*", src, re.M):
        line = m.group(0)
        if re.search(r"\b" + re.escape(name) + r"\(", line) and (
            desc_has is None or desc_has in line
        ):
            return src[m.start():src.find(".end method", m.start())]
    return ""


def stubbed(path, name, expect=None, desc_has=None):
    """True if the method is a short no-op - optionally returning `expect`."""
    b = body(path, name, desc_has)
    if not b:
        return False
    stmts = [
        l.strip()
        for l in b.split("\n")
        if l.strip()
        and not l.strip().startswith(
            (".method", ".locals", ".param", ".annotation", ".end", "#", ".line", ".prologue", ".register")
        )
    ]
    if not stmts or len(stmts) > 6:
        return False
    if expect and expect not in " ".join(stmts):
        return False
    return all(
        s.startswith(("return", "const/4", "const/16", "const-string", "invoke-static", "sget-object", "move-result", "new-instance", "invoke-direct", "invoke-interface"))
        for s in stmts
    )


def rg(pattern, path):
    return subprocess.run(
        ["rg", "-l", pattern, path], capture_output=True, text=True
    ).stdout.strip()


def _is_js_stub(fid, dec_patched, dec_pristine):
    """True if `fid` was stubbed: trivial now, non-trivial before.

    Shape-matching alone is not enough. The no-op stub decompiles to `debugger;`
    for a function that had statements, but to a completely EMPTY body for one
    whose original body was a single expression - and the false/zero stubs have
    no `debugger;` at all. Requiring a marker produced three false FAILs on
    functions that were correctly stubbed. So compare against the PRISTINE
    bundle: a function that is trivial now and was not trivial then was stubbed,
    whatever shape the stub took.
    """
    def trivial(text):
        if "{" not in text:
            return False
        inner = text.split("{", 1)[-1].rsplit("}", 1)[0]
        allowed = {
            "", "debugger;", "return undefined;", "return 0;", "return false;",
            "return null;",
            "return { enabled: false };", "const resolved = Promise.resolve();",
            "return resolved;", "return Promise.resolve();",
        }
        return all(l.strip() in allowed for l in inner.split("\n"))

    return trivial(dec_patched) and not trivial(dec_pristine)

def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_APK
    base = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else DEFAULT_BASE)
    if not os.path.exists(apk):
        print(f"no apk at {apk}")
        return 2

    z = zipfile.ZipFile(apk)
    names = z.namelist()
    # The APK carries BINARY AXML: attribute strings live in a string pool,
    # so a contiguous `android:exported="false" android:name="X"` never appears
    # there. Use it only for "is this string present at all" checks, and read
    # the decoded tree manifest for anything structural.
    manifest_bin = z.read("AndroidManifest.xml").decode("utf-8", "replace")
    mpath = os.path.join(base, "AndroidManifest.xml")
    manifest = open(mpath, encoding="utf-8").read() if os.path.exists(mpath) else manifest_bin

    # ------------------------------------------------------------- SENTRY
    S = "Sentry (every layer)"
    check(S, "libsentry.so not packaged", not any("libsentry.so" in n for n in names))
    check(S, "libsentry-android.so not packaged",
          not any("libsentry-android.so" in n for n in names))
    check(S, "SentryNdk.loadNativeLibraries disarmed",
          stubbed(sfile(base, "io/sentry/ndk/SentryNdk.smali"), "loadNativeLibraries"))
    check(S, "CrashReporting.isDisabled() returns true",
          "const/4 v0, 0x1" in body(
              sfile(base, "com/discord/crash_reporting/CrashReporting.smali"),
              "isDisabled", "private final isDisabled()Z"))
    ib = body(sfile(base, "com/discord/crash_reporting/CrashReporting.smali"),
              "init", "Lcom/discord/libdiscore/LibdiscoreModuleProvider;")
    gate = ib.find("isDisabled")
    first = min([x for x in (ib.find("Lio/sentry/"), ib.find("NativeCrashReporting")) if x > 0] or [-1])
    check(S, "CrashReporting.init gate precedes SDK + NDK init",
          bool(ib) and 0 <= gate < first, f"gate@{gate} first-init@{first}")
    check(S, "Sentry providers absent from the manifest",
          "SentryInitProvider" not in manifest and "SentryPerformanceProvider" not in manifest)
    check(S, "JS Sentry bridge disabled",
          stubbed(sfile(base, "io/sentry/react/RNSentryModuleImpl.smali"), "initNativeSdk")
          and stubbed(sfile(base, "io/sentry/react/RNSentryModuleImpl.smali"), "captureEnvelope"))
    check(S, "crash DSNs scrubbed from the dex",
          not any(rg(d, os.path.join(base, d_)) for d in DSNS for d_ in SMALI_DIRS
                  if os.path.isdir(os.path.join(base, d_))))

    # ---------------------------------------------------------- TELEMETRY
    T = "Telemetry / attribution"
    for perm, label in [
        ("com.google.android.gms.permission.AD_ID", "AD_ID"),
        ("BIND_GET_INSTALL_REFERRER_SERVICE", "Play install-referrer binding"),
        ("ACCESS_ADSERVICES_ATTRIBUTION", "Privacy-Sandbox attribution"),
        ("GET_COMMON_DATA", "Huawei app-market"),
        ("READ_APP_INFO", "Samsung app-info"),
        ("android.permission.READ_CONTACTS", "READ_CONTACTS"),
    ]:
        check(T, f"permission removed: {label}", perm not in manifest)
    check(T, "Firebase Analytics absent (FCM push kept)",
          "firebase.analytics" not in manifest)
    # `start()` is the call that registers the SDK and begins attribution.
    # It has no caller anywhere in the dex - the only init site is no-op'd.
    check(T, "AppsFlyer never started (AppsFlyerLib.start has no callers)",
          not rg(r"AppsFlyerLib;->start",
                 " ".join(os.path.join(base, d) for d in SMALI_DIRS)))

    # ------------------------------------------------------------ FEATURES
    F = "Features the user asked to hide"
    B = os.path.join(base, "assets/index.android.bundle")
    hd = os.path.join(HERE, "hermes-decomp")

    PR = os.environ.get("PRISTINE_BUNDLE",
                        "/tmp/pristine348/assets/index.android.bundle")

    def dec(fid):
        return subprocess.run([hd, "decompile", "--function", fid, B],
                              capture_output=True, text=True, timeout=600).stdout

    def dec_p(fid):
        return subprocess.run([hd, "decompile", "--function", fid, PR],
                              capture_output=True, text=True, timeout=600).stdout

    if os.path.exists(B):
        check(F, "orb gate returns {enabled:false}", "{ enabled: false }" in dec("57120"))
        check(F, "orb gate alias (isVirtualCurrencyEnabled) also false",
              "{ enabled: false }" in dec("57119"))
        check(F, "the real analytics track() is stubbed",
              "Promise.resolve()" in dec("73760"))
    else:
        check(F, "Hermes bundle present", False, B)
    # One entry per thing the user actually asked to be gone, mapped to the
    # bundle function that renders or gates it. `verify-stubs.py` proves all
    # 127 stubs land in the packed APK; this proves the *set of asks* is
    # covered, so a feature can never be silently dropped from the list.
    ASKS = [
        ("orb balance widget", "63907"),
        ("orb balance wrapper", "63908"),
        ("orb shop carousel", "62435"),
        ("orb balance text", "52968"),
        ("orb price tag", "57674"),
        ("orb reward modal", "52976"),
        ("wishlist grid", "57595"),
        ("wishlist item card", "57596"),
        ("profile edit entry (You tab)", "66981"),
        ("profile edit entry (Settings)", "61440"),
        ("shop entry button", "66966"),
        ("shop coachmark", "66967"),
        ("shop screen", "64168"),
        ("shop v2 screen", "64167"),
        # NOT 62298. That is the screen the Quest settings row *navigates to*;
        # stubbing it left the row on screen, and the on-device report said so.
        # The row's visibility is the route's own usePredicate (62294), which
        # SettingHookHarness tests with `false === cfg.usePredicate()`.
        ("quests row in Settings (route predicate)", "62294"),
        ("quest dock visibility", "62451"),
        ("nitro upsell button", "49956"),
        ("chat gift button", "55199"),
        ("chat gift/thread button", "55192"),
        ("gift button", "57688"),
        ("gift purchase button", "58239"),
        ("spotify gate (isListeningOnSpotify)", "52007"),
        ("spotify: friends' tracks", "54101"),
        ("spotify: on-device app scan", "54126"),
        ("spotify: player-state poll", "54121"),
    ]
    # STALE, and deliberately reported as one line rather than 23.
    #
    # These ids are from 348.2 and were never remapped. Against 349.5 they name
    # unrelated functions, so every entry reported FAIL while the features were
    # in fact gone - which is worse than no check, because a real failure hides
    # among the noise. `verify-stubs.py` already proves every current table
    # entry landed; what is missing is a check that each entry still names the
    # *right* function, and that needs remap_by_module's resolution, not a
    # hand-maintained id list that rots on every bump.
    check(F, "per-feature ask list remapped to this version", False,
          f"the ASKS list is stale ({len(ASKS)} ids from 348.2); "
          "re-run tools/remap_by_module.py and rewrite it, or delete the block")

    vs = subprocess.run([sys.executable, os.path.join(HERE, "verify-stubs.py"), apk],
                        capture_output=True, text=True)
    tail = [l for l in vs.stdout.split("\n") if l.startswith("PASS") or l.startswith("FAIL")]
    check(F, "all Hermes feature stubs land in the packed bundle",
          bool(tail) and tail[-1].startswith("PASS"), tail[-1] if tail else "no output")

    # ------------------------------------------- HIDING, SECOND PASS (348.5)
    # `verify-stubs.py` answers "did the stub apply"; the first 348.5 round
    # proved that is a different question from "is the element gone", and every
    # target in it was wrong. Each check here asserts the *effect* in the
    # bundle: the array the UI reads no longer contains the entry, the branch
    # that builds it is unreachable, the route has no visible predicate. A stub
    # that landed on the wrong function fails all of them.
    H = "Hiding, verified by effect (2nd pass)"

    def dis(fid):
        return subprocess.run([hd, "disasm", "--function", fid, B],
                              capture_output=True, text=True, timeout=600).stdout

    # The profile segmented control is handed an items array built by the memo
    # at 105481. "Wishlist" is gone from it and only Main/Board/Activity are
    # pushed. Counting the pushes matters: the entry object is still *built*
    # and its label still resolved, so a search for the localized string would
    # pass even with the tab present.
    _tabs = dec("105481")
    check(H, "profile tabs: Wishlist entry no longer built",
          '"wishlist"' not in _tabs and _tabs.count("items.push") == 2,
          f"{_tabs.count('items.push')} push(es)")

    # ...and the index it used to be selected by is the "not available"
    # sentinel, which useProfileSectionTabs normalises to MAIN. Without this a
    # deep link to the Wishlist section would select index 3 of a 3-item array.
    check(H, "profile tabs: wishlistTabIndex is -1",
          "wishlistTabIndex = -1" in dec("57553"))

    # The You-screen footer builds the Quests button inside
    # `if (isEligibleForQuests)`. `getIsEligibleForQuests` itself is the
    # unpatchable shared body, so the gate is opened at the call site.
    #
    # The *effect* is that the branch is always taken - not that the jump is
    # unconditional. Both shapes are correct, and the one that ships is the
    # second: `JmpFalse L855, r10`, testing a register loaded with `null` two
    # bytes earlier. That form is byte-neutral, and byte-neutrality is the only
    # way to be certain no label moved (reference/HBC_BODY_EDIT_HAZARD.md) -
    # the unconditional-jump form is what crashed the app. Asserting
    # `startswith("Jmp ")` here made a correct edit look broken.
    _nav = dis("66956")
    _lines = _nav.split("\n")
    _gate_i = None
    for _i, _l in enumerate(_lines):
        if "getIsEligibleForQuests" in _l:
            for _j in range(_i + 1, len(_lines)):
                if re.match(r"^\s*Jmp\w* ", _lines[_j]):
                    _gate_i = _j
                    break
            break
    _ok, _why = False, "no jump after the gate"
    if _gate_i is not None:
        _gate = _lines[_gate_i].strip()
        if _gate.startswith("Jmp "):
            _ok, _why = True, f"{_gate} (unconditional)"
        else:
            _m = re.match(r"Jmp\w*\s+L\d+,\s*(r\d+)", _gate)
            _reg = _m.group(1) if _m else None
            # the most recent write to that register, before the jump. The
            # trailing group has to allow end-of-line: a single-operand
            # instruction like `LoadConstNull r10` has nothing after the
            # register, and requiring a comma or space silently skipped it and
            # reported the previous `CreateClosureLongIndex r10, ...` instead.
            _writes = [
                l.strip()
                for l in _lines[:_gate_i]
                if re.match(r"\w+\s+" + (_reg or "") + r"(?:[, ]|$)", l.strip())
            ]
            _last = _writes[-1] if _writes else ""
            if _reg and _last.startswith("LoadConstNull " + _reg):
                _ok, _why = True, f"{_gate} (tests {_reg}, set null just before)"
            else:
                _why = f"{_gate} (tests {_reg}, last write {_last!r})"
    check(H, "You-screen footer: Quests button is unreachable",
          _ok and "QuestsIcon" in _nav, _why)

    # Both Settings rows are decided by `false === cfg.usePredicate()` inside
    # SettingHookHarness (61429). Quest's route already had one and it is now
    # forced false; the shop's had none at all, so the route object is given
    # one. Two different shapes because the two rows genuinely differ.
    check(H, "Settings: Quest row predicate returns false",
          "return false;" in dec("62294"))
    check(H, "Settings: CollectiblesShop route has a usePredicate",
          "usePredicate" in dec("15420"))

    # Friends' Spotify on profiles arrives in the gateway presence payload, so
    # no host rule can reach it and no Spotify-API stub touches it. The recent
    # activity list is filtered at 117345; the listened-session branch must
    # yield false so the entry is dropped.
    #
    # The effect, again, not the mechanism. The block is still in the body -
    # the edit forces its verdict false with a self-comparison (`Greater r1,
    # r0, r0` computes 0 > 0) rather than deleting it, which keeps the body the
    # same length and therefore keeps every label valid. Asserting the block
    # was *removed* made the correct edit look broken.
    _act = dis("117345")
    _branch = _act.split('"isListenedSessionEntry"', 1)[-1]
    _forced = re.search(r"(\w+) r\d+, (r\d+), \2\b", _branch)  # self-compare
    check(H, "profiles: Spotify (listened-session) entries filtered out",
          "isListenedSessionEntry" in _act and _forced is not None,
          f"branch verdict forced false by {_forced.group(0)}" if _forced
          else "listened-session branch is not forced false")

    # The animated profile *card* decoration, and only that one: the avatar /
    # profile-picture decoration lives in collectibles/avatar_decorations and
    # must stay.
    check(H, "profile card effect stopped rendering (avatar decoration kept)",
          "return null;" in dec("45655")
          and dec("45655").count("null") > 0)

    # ------------------------------------------------------------- PRIVACY
    P_ = "Privacy"
    csp = sfile(base, "com/discord/contact_sync/ContactSyncProvider.smali")
    check(P_, "contacts read returns an empty map",
          "Collections;->emptyMap()Ljava/util/Map;" in body(csp, "getContactsMap"))
    check(P_, "contact photo read returns null",
          "const/4 v0, 0x0" in body(csp, "getImageForContactId"))
    check(P_, "installed-app probe returns false",
          "const/4 v0, 0x0" in body(sfile(base, "com/discord/intents/IntentsModule.smali"),
                                    "canOpenUrlScheme"))
    check(P_, "DiscordRpcService not exported",
          'android:exported="false" android:name="com.discord.socialrpc.DiscordRpcService"' in manifest)
    check(P_, "allowBackup=false", 'android:allowBackup="false"' in manifest)

    # --------------------------------------------------------- CALLS / ANR
    A = "Calls / ANR"
    check(A, "4s per-video-tile stats timer removed",
          stubbed(sfile(base, "com/discord/media/engine/video/egl_renderer/EglRenderer.smali"),
                  "logStatisticsRunnable$lambda$2", "return-void"))
    check(A, "stall-path profiler dump removed",
          stubbed(sfile(base, "com/discord/js_watchdog/HermesSamplingProfilerUtil.smali"),
                  "findSampleTrace", "return-object v0"))
    check(A, "JS-thread profiler switch removed",
          stubbed(sfile(base, "com/releaseprofiler/ReleaseProfilerModule.smali"),
                  "startProfiling", "const/4 v0, 0x0"))
    # Resolve the coroutine package rather than spelling it. R8 renames it
    # between releases (`nr` in 348.5, `mr` in 349.5), and this check was
    # itself hardcoded to `nr` - so it could not have caught the regression it
    # exists to catch even if it had been run against the right tree.
    _mem = sfile(base, "com/discord/media/engine/MediaEngineModule.smali")
    _msrc = open(_mem, encoding="utf-8").read() if os.path.exists(_mem) else ""
    _mi = sfile(base, "com/discord/misc/utilities/coroutines/MainImmediateScopeKt.smali")
    _misrc = open(_mi, encoding="utf-8").read() if os.path.exists(_mi) else ""
    # Resolve the SupervisorJob from Discord's own MainImmediateScope rather
    # than spelling it. R8 renames the whole Kotlin coroutines package between
    # releases (`nr` in 348.5, `mr` in 349.5) and this check was itself
    # hardcoded to the old name, so it could not have caught the regression it
    # exists to catch.
    _sup = re.search(r"new-instance v\d+, L([^;]+);", _misrc)
    check(A, "media appScope is a SupervisorJob (one throw cannot kill the engine)",
          bool(_sup)
          and f"new-instance v1, L{_sup.group(1)};" in _msrc
          and "Lkotlin/coroutines/e;->c(Lkotlin/coroutines/CoroutineContext$Element;Lkotlin/coroutines/CoroutineContext;)Lkotlin/coroutines/CoroutineContext;" in _msrc)
    _ica = sfile(base, "com/discord/notifications/renderer/IncomingCallActivity.smali")
    _icasrc = open(_ica, encoding="utf-8").read() if os.path.exists(_ica) else ""
    _ma = sfile(base, "com/discord/MainApplication.smali")
    _masrc = open(_ma, encoding="utf-8").read() if os.path.exists(_ma) else ""
    check(A, "startup does not load JankSessionRecorder (its init is a stub)",
          "Patch: JankSessionRecorder unloaded" in _masrc
          and "jank_stats/JankSessionRecorder" not in _masrc)
    check(A, "CrashReporting <clinit> no longer loads 7 exception classes",
          "Patch: the 7 exception KClasses" in
          open(sfile(base, "com/discord/crash_reporting/CrashReporting.smali"),
               encoding="utf-8").read())
    check(A, "incoming call does not block the UI thread on an image fetch",
          "Patch: no network wait" in _icasrc)
    check(A, "FGS type chain catch widened (call survives backgrounding)",
          ".catch Ljava/lang/Exception;" in open(
              sfile(base, "com/discord/foreground_service/utils/ForegroundServiceUtilsKt.smali"),
              encoding="utf-8").read())

    # ------------------------------------------------- POWER / WEIGHT
    W = "Power / weight"
    import tempfile as _tf
    _d = _tf.mkdtemp()
    _pb = os.path.join(_d, "b.hbc")
    with open(_pb, "wb") as f:
        f.write(z.read("assets/index.android.bundle"))
    _vq = subprocess.run([hd, "decompile", "--function", "32911", _pb],
                         capture_output=True, text=True).stdout
    # Capture must stay CONSISTENT WITH THE ENCODER. Lowering capture alone
    # makes capture < encode, and the media engine aborts rather than upscale
    # - confirmed on device as an instant crash when the camera starts. The
    # check asserts the pair, not a number.
    check(W, "camera capture left consistent with the encoder (not lowered alone)",
          "self.options.videoCapture.width" in _vq
          and "{ width: 640" not in _vq)
    # Asserts the SAFE shape, which is both halves at once, because either one
    # alone is wrong:
    #
    #   * the fps log build must be GONE - leaving it costs two allocations and
    #     a log write every 2 s per camera, for the whole of every video call;
    #   * the `"CameraStatistics"` tag must be PRESENT - the freeze-detection
    #     branch further down reuses `v1` as its `Logging.e` tag without
    #     reloading it, so removing the constant fails ART verification at
    #     class load and the camera crashes the instant it opens.
    #
    # The check previously asserted only the second half (the patch absent),
    # which was the right answer while the patch had no safe form. See
    # reference/CAMERA_CRASH.md.
    _dex = [z.read(n) for n in names if n.endswith(".dex")]
    check(W, "CameraStatistics: log removed, tag kept (the tag's removal fails ART)",
          any(b"CameraStatistics" in d for d in _dex)
          and not any(b"Camera fps: " in d for d in _dex))
    # The strongest static test available without a device: the Hermes
    # assembler re-encodes the edited function and compares. A body edit that
    # does not round-trip is malformed bytecode, and this is exactly what the
    # 347.5 IDENTIFY edit needed (it round-tripped and still crashed, so this
    # is necessary, not sufficient - but it catches the whole class of
    # encoding mistakes).
    # asm-check reports on STDERR, not stdout - reading only stdout reports a
    # false failure here (it did once).
    _r = subprocess.run([hd, "asm-check", "--function", "32911", _pb],
                        capture_output=True, text=True)
    _rt = (_r.stdout + _r.stderr).strip()
    check(W, "camera-edit bytecode round-trips through the assembler",
          "OK:" in _rt, _rt.split("\n")[-1][:70] if _rt else "no output")
    check(W, "unused intl cache is inventoried (removal is opt-in)",
          sum(1 for n in names if "/cache_intl" in n) == 0
          or True,  # informational: count is reported below
          f"{sum(1 for n in names if '/cache_intl' in n)} cache_intl entries shipped")

    # ----------------------------------------------------------- BLOCKLIST
    K = "Network blocklist"
    inter = sfile(base, "com/discord/resource_usage/DeviceResourceUsageRecorder$Companion.smali")
    isrc = open(inter, encoding="utf-8").read() if os.path.exists(inter) else ""
    for rule, label in [
        ('"sentry.io"', "sentry.io (covers regional DSN hosts)"),
        ('"-collectibles"', "-collectibles compound"),
        ('"/connections/spotify"', "/connections/spotify token fetch"),
        ('"api.spotify.com"', "api.spotify.com"),
        ('"dealer.spotify.com"', "dealer.spotify.com"),
        ('"firebaselogging"', "firebaselogging-pa"),
        ('"/beaker"', "client telemetry /beaker"),
        ('"/typing"', "/typing (NoType)"),
    ]:
        check(K, f"rule present: {label}", rule in isrc)
    check(K, "interceptor matches the URL, not Request.toString()",
          "HttpUrl;->toString()Ljava/lang/String;" in isrc
          and "Lokhttp3/Request;->toString()" not in isrc)
    rn = sfile(base, "com/discord/networking/ReactNetworking.smali")
    rs = open(rn, encoding="utf-8").read() if os.path.exists(rn) else ""
    check(K, "RN interceptors on the APPLICATION list",
          "OkHttpClient$Builder;->c:Ljava/util/ArrayList;" in rs
          and "OkHttpClient$Builder;->d:Ljava/util/ArrayList;" not in rs)

    # ------------------------------------------------------------- REPORT
    print(f"apk:  {apk}")
    print(f"tree: {base}\n")
    sec = None
    npass = nfail = 0
    for s, what, ok, detail in results:
        if s != sec:
            print(f"== {s}")
            sec = s
        if ok:
            npass += 1
            print(f"  PASS  {what}" + (f"   [{detail}]" if detail else ""))
        else:
            nfail += 1
            print(f"  FAIL  {what}" + (f"   [{detail}]" if detail else ""))
    print(f"\n{npass} passed, {nfail} failed")
    return 1 if nfail else 0


if __name__ == "__main__":
    sys.exit(main())
