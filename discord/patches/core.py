#!/usr/bin/env python3
"""Core patch set for Discord 354.1 Alpha (versionCode 354201).

354.1 is the first build in this series where Discord ships an R8-obfuscated
dex: class and method *names* that the suite used to rely on are gone for most
internal code (React Native modules keep theirs). Two consequences:

  * a patch that used to name a class directly now locates it by a string or a
    method descriptor, and
  * a patch whose target has genuinely been removed is reported as retired
    rather than failing the build.

The Hermes function IDs in ``patch_js`` were re-derived for this bundle by
``tools/remap_hermes.py`` + ``tools/remap_by_module.py``, then merged with
``tools/merge_remap.py``.

Usage: core.py <decompiled-base-dir>
"""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_BASE = os.path.join(HERE, "..", "decompiled", "base")


def _load_options():
    """Defaults from ``patches/manifest.json``, overridden by a selection file.

    The selection file is ``discord/options.json`` (written by
    ``tools/patches.py``) or the path in ``PATCH_OPTIONS``. It accepts either
    ``{"options": {...}}`` or a flat ``{KEY: value}`` map.
    """
    values = {}
    try:
        with open(os.path.join(HERE, "manifest.json"), encoding="utf-8") as f:
            manifest = json.load(f)
        for patch in manifest.get("patches", []):
            if patch.get("option"):
                values[patch["option"]] = patch.get("default")
    except (OSError, ValueError):
        pass
    selection = os.environ.get("PATCH_OPTIONS") or os.path.join(
        os.path.dirname(HERE), "options.json"
    )
    if selection and os.path.exists(selection):
        try:
            with open(selection, encoding="utf-8") as f:
                chosen = json.load(f)
        except (OSError, ValueError) as exc:
            print(f"  [SKIP] cannot read options file {selection}: {exc}")
            return values
        if not isinstance(chosen, dict):
            print(f"  [SKIP] options file {selection} must contain an object")
            return values
        chosen = chosen.get("options", chosen)
        if not isinstance(chosen, dict):
            print(
                f"  [SKIP] options file {selection}: 'options' must be an object"
            )
            return values
        values.update(chosen)
    return values


OPTIONS = _load_options()


def opt(key, default=None):
    value = OPTIONS.get(key, default)
    return value if value is not None else default


def opt_bool(key, default):
    """Read a boolean option, rejecting a hand-edited non-boolean value."""
    value = OPTIONS.get(key, default)
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in ("1", "true", "yes", "on")
    if isinstance(value, (int, float)):
        return bool(value)
    return default


def opt_int(key, default, lo=None, hi=None):
    """Read an integer option, falling back to the manifest default.

    A hand-edited selection file is not a trusted code path: a negative
    capture width or an enormous framerate would be written straight into the
    media engine. Out-of-range values are refused rather than clamped, so a
    mistake is visible in the log.
    """
    try:
        value = int(OPTIONS.get(key, default))
    except (TypeError, ValueError):
        print(f"  [SKIP] option {key} is not an integer; using {default}")
        return default
    if lo is not None and value < lo or hi is not None and value > hi:
        print(
            f"  [SKIP] option {key}={value} is outside {lo}..{hi}; "
            f"using {default}"
        )
        return default
    return value

ok = 0
fail = 0
skipped = 0

SMALI_DIRS = ["smali", "smali_classes2", "smali_classes3", "smali_classes4"]


def smali_dirs(base):
    """Every ``smali*`` directory in this tree, in dex order.

    A build that adds ``smali_classes5`` must not make ``sfile`` miss a class.
    The static list stays as the fallback for callers that have no tree.
    """
    found = sorted(
        d
        for d in os.listdir(base)
        if d == "smali" or re.fullmatch(r"smali_classes\d+", d)
    )
    return found or SMALI_DIRS


def sfile(base, rel):
    """Resolve a class path against whichever dex dir holds it in this build."""
    for d in smali_dirs(base):
        p = os.path.join(base, d, rel)
        if os.path.exists(p):
            return p
    return os.path.join(base, smali_dirs(base)[0], rel)


def find_smali_with(base, needle, label=None, unique=False):
    """Return a smali file containing ``needle``, or None.

    Used for targets whose class name is R8 output and moves between releases.
    Scans each dex directory once; the tree is ~190 MB, which is cheap next to
    the Hermes work in this same run. With ``unique``, more than one match is
    refused rather than silently editing the first one.
    """
    matches = []
    for d in smali_dirs(base):
        root = os.path.join(base, d)
        if not os.path.isdir(root):
            continue
        for dirpath, _, files in os.walk(root):
            for name in files:
                if not name.endswith(".smali"):
                    continue
                p = os.path.join(dirpath, name)
                try:
                    with open(p, encoding="utf-8") as f:
                        if needle in f.read():
                            matches.append(p)
                            if len(matches) > 1 and not unique:
                                return matches[0]
                except (OSError, UnicodeDecodeError):
                    continue
    if not matches:
        if label:
            print(
                f"  [SKIP] {label}: no file contains {needle!r} "
                "(target removed?)"
            )
        return None
    if unique and len(matches) > 1:
        if label:
            print(
                f"  [SKIP] {label}: {len(matches)} files contain {needle!r}; "
                "refusing to guess"
            )
        return None
    return matches[0]


def method_decl(s, sig):
    """Regex for a method declaration, ignoring its (possibly obfuscated) name."""
    return re.compile(
        r"^\.method [^\n]*?[\w$<>]+" + re.escape(sig) + r"[^\n]*$", re.M
    )


def method_replace_sig(path, sig, new_body, label):
    """Replace a method by descriptor instead of by name.

    354.1 obfuscates internal method names (``CrashReporting.captureException``
    is now ``CrashReporting.c``), but descriptors are part of the call ABI and
    do not change. ``new_body`` may be a callable taking the matched declaration
    line.
    """
    global ok, fail
    if not os.path.exists(path):
        print(f"  [FAIL] {label}: file missing")
        fail += 1
        return
    s = rd(path)
    m = method_decl(s, sig).search(s)
    if not m:
        print(f"  [FAIL] {label}: method {sig} not found")
        fail += 1
        return
    decl = m.group(0)
    body = new_body(decl) if callable(new_body) else new_body
    a = m.start()
    e = s.index(".end method", a) + len(".end method")
    wr(path, s[:a] + body + s[e:])
    ok += 1
    print(f"  [OK] {label}")


def noop_sig(path, sig, label, ret="V", retval=""):
    """``noop`` for an obfuscated method, addressed by descriptor."""

    def build(decl):
        if ret == "V":
            return f"""{decl}
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
        op = (
            "return-object v0"
            if ret.startswith("L") or ret.startswith("[")
            else "return-wide v0"
            if ret in ("J", "D")
            else "return v0"
        )
        return f"""{decl}
    .locals 1
    # Patch: stub.
    {retval}
    {op}
.end method"""

    method_replace_sig(path, sig, build, label)


def rd(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def wr(p, s):
    with open(p, "w", encoding="utf-8") as f:
        f.write(s)


def method_replace(path, marker, new_body, label):
    global ok, fail
    if not os.path.exists(path):
        print(f"  [FAIL] {label}: file missing")
        fail += 1
        return
    s = rd(path)
    try:
        a = s.index(marker)
    except ValueError:
        print(f"  [FAIL] {label}: marker not found")
        fail += 1
        return
    e = s.index(".end method", a) + len(".end method")
    wr(path, s[:a] + new_body + s[e:])
    ok += 1
    print(f"  [OK] {label}")


def noop(path, marker, label, ret="V", retval=""):
    """Replace a method with a stub. ret is the smali return type."""
    if ret == "V":
        body = f"""{marker}
    .locals 0
    # Patch: no-op.
    return-void
.end method"""
    else:
        # smali's opcode for a 32-bit return is `return vAA`, not
        # `return-value`; only object and wide returns take a suffix.
        if ret.startswith("L") or ret.startswith("["):
            op = "return-object v0"
        elif ret in ("J", "D"):
            op = "return-wide v0"
        else:
            op = "return v0"
        body = f"""{marker}
    .locals 1
    # Patch: stub.
    {retval}
    {op}
.end method"""
    method_replace(path, marker, body, label)


def patch_okhttp_cache(base):
    """Share one okhttp3.Cache across RN's clients.

    React Native's OkHttpClientProvider.createClientBuilder(Context,int) builds a
    fresh `Cache(cacheDir/http-cache, n)` every call, and three clients (XHR,
    the
    gateway websocket, the Fresco fetcher) each call it on the same directory,
    so
    they thrash each other's journal and re-download the same images. Memoise the
    Cache in a static field; two OkHttpClients sharing one Cache is the
    supported
    configuration and nothing here closes or evicts it.
    """
    global ok, fail
    path = sfile(
        base, "com/facebook/react/modules/network/OkHttpClientProvider.smali"
    )
    if not os.path.exists(path):
        print("  [FAIL] OkHttpClientProvider missing")
        fail += 1
        return
    s = rd(path)
    if "->httpCache:L" in s:
        print("  [SKIP] OkHttp cache already memoized")
        return

    marker = ".method public static final createClientBuilder(Landroid/content/Context;I)Lokhttp3/OkHttpClient$Builder;"
    if marker not in s:
        print("  [FAIL] createClientBuilder(Context,int) not found")
        fail += 1
        return

    # `okhttp3.Cache` is obfuscated and its name moves between releases
    # (Lcs/g; on 346.2, Lps/g; on 347.5). Read it out of the builder's cache
    # field inside this method instead of hardcoding it, so a version bump
    # cannot silently break the anchor.
    _a = s.index(marker)
    _e = s.index(".end method", _a)
    _m = re.search(r"Lokhttp3/OkHttpClient\$Builder;->k:(L[^;]+;)", s[_a:_e])
    if not _m:
        print("  [FAIL] cannot determine the okhttp3.Cache class from the builder")
        fail += 1
        return
    cache = _m.group(1)

    # Anchor on the first statement of the cache branch rather than on a
    # `.line N` directive, which is a compiler detail that shifts between
    # releases.
    old = """    :cond_0
    new-instance v1, Ljava/io/File;"""
    new = f"""    :cond_0
    # Patch: reuse one disk cache instead of opening three over the same dir.
    sget-object v1, Lcom/facebook/react/modules/network/OkHttpClientProvider;->httpCache:{cache}

    if-eqz v1, :new_cache

    iput-object v1, v0, Lokhttp3/OkHttpClient$Builder;->k:{cache}

    return-object v0

    :new_cache
    new-instance v1, Ljava/io/File;"""
    if old not in s:
        print("  [FAIL] createClientBuilder anchor not found")
        fail += 1
        return
    s = s.replace(old, new, 1)

    old2 = f"""    iput-object p0, v0, Lokhttp3/OkHttpClient$Builder;->k:{cache}

    return-object v0
.end method"""
    new2 = f"""    sput-object p0, Lcom/facebook/react/modules/network/OkHttpClientProvider;->httpCache:{cache}

    iput-object p0, v0, Lokhttp3/OkHttpClient$Builder;->k:{cache}

    return-object v0
.end method"""
    if old2 not in s:
        print("  [FAIL] cache assignment anchor not found")
        fail += 1
        return
    s = s.replace(old2, new2, 1)

    s = s.replace(
        ".field private static client:Lokhttp3/OkHttpClient;",
        ".field private static client:Lokhttp3/OkHttpClient;\n\n"
        f".field private static httpCache:{cache}",
        1,
    )
    wr(path, s)
    ok += 1
    print("  [OK] OkHttp disk cache memoized (shared across RN clients)")


def gate_disabled(path, marker, label):
    """Insert `if (isDisabled()) return;` at the top of a CrashReporting method.

    `isDisabled()` is the canonical "crash reporting is off" switch, but only
    `addBreadcrumb` and `init` actually consult it - `captureException`,
    `captureMessage` and `persistLastCrashInfo` still ran and logged with the
    `SentryBreadcrumb` logcat tag, which is the spam users see.
    """
    global ok, fail
    s = rd(path)
    if marker not in s:
        print(f"  [FAIL] {label}: marker not found")
        fail += 1
        return
    a = s.index(marker)
    e = s.index(".end method", a)
    body = s[a:e]
    if "->isDisabled()Z" in body:
        print(f"  [SKIP] {label} (already gated)")
        return
    lm = re.search(r"\.locals (\d+)", body)
    if not lm or int(lm.group(1)) < 1:
        print(f"  [FAIL] {label}: no free register for the gate")
        fail += 1
        return
    # Insert immediately before the first real instruction. smali requires
    # `.param` / `.annotation` directives to precede code, so inserting after
    # `.locals` would break any `.param` block that follows it.
    lines = body.split("\n")
    ins_line = None
    for idx in range(1, len(lines)):
        line = lines[idx].strip()
        if not line or line.startswith("."):
            continue
        ins_line = idx
        break
    if ins_line is None:
        print(f"  [FAIL] {label}: no instruction found")
        fail += 1
        return
    ins = a + sum(len(l) + 1 for l in lines[:ins_line])
    # `invoke-direct/range {p0 .. p0}` rather than `{p0}`: the 35c form encodes
    # each register in 4 bits, so it cannot name p0 when the method's .locals
    # push the parameter registers past v15 (addBreadcrumbBatchBinary has 21).
    gate = (
        "    # Patch: crash reporting is disabled - do not log or build events.\n"
        "    invoke-direct/range {p0 .. p0}, Lcom/discord/crash_reporting/CrashReporting;->isDisabled()Z\n\n"
        "    move-result v0\n\n"
        "    if-eqz v0, :cond_gate_skip\n\n"
        "    return-void\n\n"
        "    :cond_gate_skip\n\n"
    )
    new = s[:ins] + gate + s[ins:]
    if len(new) <= len(s):  # sanity: an insertion must make the file longer
        print(f"  [FAIL] {label}: refusing to write a truncated file")
        fail += 1
        return
    wr(path, new)
    ok += 1
    print(f"  [OK] {label} gated")


def patch_ota_bundle(base):
    """Make the app always run the APK's (patched) JS bundle.

    Discord's BundleUpdater applies `assets/index.android.bundle.patch` on first
    launch and stores the result under the SharedPreferences key
    `key_android_js_bundle`; any bundle it later downloads is stored the same
    way
    and takes precedence over the APK asset. That silently bypasses every
    JS-side change we make (the nulled Sentry DSN included) - the app keeps
    running a bundle built from the ORIGINAL asset.

    Three changes, all length-preserving where strings are involved:
      1. rename the two pref keys, so a stored bundle is not found and the asset
         is used instead;
      2. point the OTA host at a non-resolving domain so no new bundle can be
         downloaded and re-introduced;
      3. drop the shipped patch asset (it would re-create the old bundle).
    """
    global ok, fail
    # Check the renamed key first: a re-run has no original key left to find,
    # and "already applied" must not look like "target disappeared".
    if find_smali_with(base, "key_android_js_bundlX"):
        print("  [SKIP] OTA bundle already neutralised")
        return
    # BundleUpdater's class name is R8 output in 354.1 (`w6/z`), so locate the
    # class by the pref key it owns instead of by name.
    path = find_smali_with(
        base, "key_android_js_bundle", "OTA bundle pref key", unique=True
    )
    if path is None:
        return
    s = rd(path)

    renamed = 0
    for old, new in (
        (
            "key_android_js_bundle_release_name",
            "key_android_js_bundle_release_namX",
        ),
        ("key_android_js_bundle", "key_android_js_bundlX"),
    ):
        assert len(old) == len(new)
        n = s.count(old)
        if n:
            s = s.replace(old, new)
            renamed += n
    if not renamed:
        print("  [FAIL] bundle pref keys not found")
        fail += 1
        return

    # OTA host: discord.com -> a same-length, non-resolving domain. The
    # register is R8 output, so match it rather than a fixed `v2`.
    s, ota = re.subn(
        r'const-string (v\d+), "discord\.com"',
        r'const-string \1, "invalid.com"',
        s,
    )
    if ota == 0:
        print("  [FAIL] OTA host constant not found - bundle not pinned")
        fail += 1
        return
    wr(path, s)

    asset = os.path.join(base, "assets/index.android.bundle.patch")
    removed = False
    if os.path.exists(asset):
        os.remove(asset)
        removed = True

    ok += 1
    print(
        f"  [OK] OTA bundle neutralised (renamed {renamed} pref key(s), "
        f"{ota} OTA host const(s), patch asset removed={removed})"
    )


def patch_js_sentry_dsn(base):
    """Null the JS Sentry DSN inside the Hermes bundle (length-preserving).

    The native SDK is already disabled, but `@sentry/react-native` still reads
    its
    DSN out of the bundle and keeps running in JS: breadcrumb processing, scope
    management, promise-rejection tracking and the associated console noise. The
    DSN lives in the Hermes string table, which is a packed blob with a separate
    offset table, so it is replaced IN PLACE with a same-length string - every
    string offset stays valid. The replacement is not a valid DSN, so the JS SDK
    disables itself instead of shipping envelopes (which the blocklist already
    204s anyway).
    """
    global ok, fail
    path = os.path.join(base, "assets/index.android.bundle")
    if not os.path.exists(path):
        print("  [FAIL] JS bundle missing")
        fail += 1
        return
    with open(path, "rb") as f:
        data = f.read()
    if b"https://0.0.0.0/000" in data:
        if hashlib.sha1(data[:-20]).digest() != data[-20:]:
            with open(path, "wb") as f:
                f.write(data[:-20] + hashlib.sha1(data[:-20]).digest())
            print("  [OK] JS Sentry DSN already nulled; Hermes footer rehashed")
        else:
            print("  [SKIP] JS Sentry DSN already nulled")
        return
    pat = re.compile(
        rb"https://[0-9a-z.-]+\.sentry\.io/api/\d+/envelope/\?sentry_version=\d+"
        rb"&sentry_key=[0-9a-f]+&sentry_client=[A-Za-z0-9._%]+"
    )
    m = pat.search(data)
    if not m:
        print("  [FAIL] JS Sentry DSN not found in bundle")
        fail += 1
        return
    orig = m.group(0)
    repl = b"https://0.0.0.0/" + b"0" * (len(orig) - len("https://0.0.0.0/"))
    assert len(repl) == len(orig)
    data = data[: m.start()] + repl + data[m.end() :]
    # The Hermes footer is SHA-1(all bytes except the last 20) stored in the
    # last
    # 20 bytes; an in-place edit invalidates it and makes HBC tooling refuse the
    # file, so recompute it.
    data = data[:-20] + hashlib.sha1(data[:-20]).digest()
    with open(path, "wb") as f:
        f.write(data)
    ok += 1
    print(
        f"  [OK] JS Sentry DSN nulled in place ({len(orig)} bytes, offsets preserved, footer rehashed)"
    )


def find_tool(name):
    """Return the absolute path of a toolchain binary, or None.

    Walks up from this file so the patch suite keeps working wherever it
    sits in the tree (it used to live under build/<ver>/patches/).
    """
    here = os.path.dirname(os.path.abspath(__file__))
    for _ in range(4):
        candidate = os.path.join(here, "tools", name)
        if os.path.exists(candidate):
            return candidate
        here = os.path.dirname(here)
    return None


def patch_js(base):
    """No-op telemetry, logging and analytics inside the Hermes bundle.

    Native patches kill the SDKs and the network, but Discord's JavaScript still
    builds the payloads, drives the logger and writes its own debug buffer.
    This step reaches into the bundle's bytecode and replaces the bodies of the
    offending functions with `return undefined`.

    Two groups are targeted:

    * ``discord_common/js/packages/logger/Logger.tsx`` — the debug-level methods
      (`verbose`, `log`, `trace`, …). Silencing these removes the bulk of the
      in-memory JS debug log, including the `[NetStats]`/`[Sentry]` lines that
      survive every native-side kill.
    * ``utils/SentryUtils.native.tsx`` — the whole Sentry JS API surface.

    Only functions that return ``undefined`` are touched: a stub that returned
    the wrong shape would break callers that await a promise.

    The bundle is Hermes bytecode (HBC v98), so this uses the portable
    `hermes-decomp` binary. Function IDs are per-bundle, so each one is verified
    by decompiling it and checking the name before the body is reassembled.
    """
    global ok, fail
    tool = find_tool("hermes-decomp")
    bundle = os.path.join(base, "assets/index.android.bundle")
    if tool is None:
        print("  [FAIL] hermes-decomp not found in the toolchain")
        fail += 1
        return
    if not os.path.exists(bundle):
        print("  [FAIL] JS bundle missing")
        fail += 1
        return

    # function id -> name the decompiler prints for it (346.2 bundle).
    TARGETS = {
"64274": "MobileShopButtonCoachmark",
        "64274": "MobileShopButtonCoachmark",
        "64273": "CollectiblesShopEntryButton",
        "64273": "CollectiblesShopEntryButton",
        "71491": "BalanceWidgetMenuWrapper",
        "71491": "BalanceWidgetMenuWrapper",
        "68751": "UserSettingsEditUserProfile",
        "68751": "UserSettingsEditUserProfile",
        "65479": "GiftPurchaseButton",
        "64904": "OrbsPriceTag",
        "64904": "OrbsPriceTag",
        "64839": "AddToWishlistGrid",
        "64839": "AddToWishlistGrid",
        "62152": "ChatInputActionButtonGiftOrThread",
        "62152": "ChatInputActionButtonGiftOrThread",
        "64251": "QuestOrbsRewardModal",
        "64251": "QuestOrbsRewardModal",
        "53085": "NitroUpsellButton",
        "53085": "NitroUpsellButton",
        # Logger.tsx - every non-error logging level. Only `warn` and `error`
        # are kept, so genuine problems still surface.
        "77289": "logDangerously",
        "77290": "log",
        "77291": "verboseDangerously",
        "77292": "verbose",
        "77293": "info",
        "77296": "trace",
        "77298": "fileOnly",
        # AnalyticsUtils.tsx - the `[Analytics]` debug reporters and the
        # central event tracker. `track` is what every feature calls to emit a
        # science event; it also samples CPU and memory per event via
        # expandEventProperties, so silencing it removes that cost too.
        "25650": "debugLogEvent",
        "25721": "trackNetworkAction",
        "25713": "track",
        # AppAnalyticsUtils.tsx - trackWithMetadata is the emitter behind the
        # forum/clickstream trackers (modules/forums/tracking/Tracking.tsx).
        "36098": "trackWithMetadata",
        # Found by cross-referencing NoTrack (maisymoe/strife), a Strife client
        # mod that no-ops the same surfaces from JS. These two are
        # AnalyticsActionHandlers, reached independently of `track`:
        #   handleTrack       - builds {key,event,properties,fingerprint,
        #                       timestamp} and calls extractId(fingerprint)
        #   handleFingerprint - computes old_/new_fingerprint via
        #                       extractId(fingerprint) - fingerprint tracking
        # Stubbing `track` does NOT neutralise either. NoTrack's third target,
        # submitLiveCrashReport, is already inert on Android: one body is a
        # promise stub already and the other is gated on isWindows().
        "67877": "handleTrack",
        # --- Nitro upsell openers (Equicord's noNitroUpsell approach, adapted).
        # There is no single premium-upsell gate in this bundle: only
        # per-surface experiments (MobileStickerPickerUpsellRestyle,
        # MobileGoLiveUpsell, ...), each component mounted independently.
        # These two openers are the chokepoints - every upsell alert, action
        # sheet and modal launches through one of them.
        #   openPremiumModal          16 call sites, all event handlers whose
        #                             body is a bare call statement; no caller
        #                             reads the returned promise
        #   openPremiumUpsellActionSheet  9 call sites, same shape
        # Both offsets are unique (the shared-body guard would refuse them
        # otherwise).
        "52267": "openPremiumModal",
        "51876": "openPremiumUpsellActionSheet",
        # NitroUpsellButton - the inline "Get Nitro" button, 11 consumers
        # (GoLiveActionSheet, StickerDetail, TieredTenureBadge, MarketingBanner,
        # ShopNitroUpsellBanner, ...). Widest-reaching single component.
        "53085": "NitroUpsellButton",
        # The gift buttons themselves. Stubbing NitroUpsellButton removed the
        # "Get Nitro" prompts but not the gift affordances - these are separate
        # components, and the chat-input one sits next to the message box.
        "62154": "ChatInputActionButtonGift",
        "62152": "ChatInputActionButtonGiftOrThread",
        # 354.1 has one named GiftButton and one GiftPurchaseButton; the
        # React Compiler pair collapsed, so a single row each is enough.
        "64917": "GiftButton",
        # --- Orbs, the quest currency. The real gate is
        # `useVirtualCurrencyMobileEnabled`, handled in OBJECT_FALSE_TARGETS
        # (it returns an object, so no boolean stub can express it, and it
        # shares its body with an alias, so it needs the SHARED_OK entry).
        # These are the rendering components that gate sits in front of, kept
        # as defence in depth so orbs stay gone even if the gate is re-enabled
        # by a future bundle. All shared=1.
        #
        # `canUseMoreQuestOrbs` is deliberately NOT stubbed: it is a
        # *Nitro-perk* check, and forcing it false resolves the multiplier
        # badge to UPSELL and ADDS upsell UI - the opposite of the intent.
        "71490": "BalanceWidgetMenu",          # You-tab orb balance widget
        # 63908 BalanceWidgetMenuWrapper is already covered by the
        # 70917/70918 pair the structural walk added above; the stale
        # 348.2 id is dropped rather than left to skip forever.
        "69901": "QuestHomeOrbShopCarousel",   # in-quest orb purchase carousel
        # DROPPED in 349.5 - `OrbsBalance`, `OrbsPriceTag` and `EditSection`
        # no longer exist anywhere in the bundle: no module path, no name
        # string, no function. Discord removed or renamed them, so there is
        # nothing left to hide. The orb gate itself is still handled, by the
        # `useVirtualCurrencyMobileEnabled` {enabled:false} stub, which is the
        # source rather than a screen.
        "64251": "QuestOrbsRewardModal",
        "64251": "QuestOrbsRewardModal",
        # --- Profile customization. `canUsePremiumProfileCustomization` looks
        # like the gate but covers one section of five, and the `*AbstractUI`
        # illustrations share a 99-way *live image* body that must not be
        # touched. The entry points are the lever.
        # (EditSection dropped - see above.)
        "68751": "UserSettingsEditUserProfile",
        # --- Shop. No Shop tab exists to close (MainTabsConstants has only
        # guilds/icymi/notifications; RootNavigator has you/settings), so these
        # are the entry points and the screen itself.
        "64273": "CollectiblesShopEntryButton",
        "64273": "CollectiblesShopEntryButton",
        "64274": "MobileShopButtonCoachmark",
        "64274": "MobileShopButtonCoachmark",
        "71776": "CollectiblesShopInternal",
        "71776": "CollectiblesShopInternal",
        "71774": "CollectiblesShopV2",
        "71774": "CollectiblesShopV2",
        # --- Shop surfaces OUTSIDE the shop screen.
        #
        # 71380/71379 and 71382/71381 above are the shop *screen*, so everything
        # the screen renders is already gone. The entries below are reached from
        # somewhere else entirely, which is why nulling the screen did not
        # remove them:
        #
        # `modules/user_settings/shop/native/CollectiblesShopScreen.tsx` is the
        # component the `CollectiblesShopSetting` route renders. Hiding the
        # *route* (shop_setting_predicate) closes the Settings path, but the
        # shop also has a deep-link entry (`useCollectiblesShopDeepLinkProps`),
        # so the screen component itself is nulled too.
        #
        # "Shop This Look" - an action sheet opened from a message/attachment
        # context menu, plus its marketing coachmark. Neither lives inside the
        # shop screen.
        #
        # The opener (`openShopThisLookActionSheet`, 62159) is deliberately NOT
        # patched: its body is deduplicated with `showGummyCustomColorSheet`
        # (69939) from `modules/display_name_styles`, so stubbing it would take
        # an unrelated display-name feature with it. Nulling the sheet's own
        # component instead means that if the opener still runs, the sheet opens
        # empty rather than showing shop content.
        #
        # `modules/game_profile/native/components/GameProfileShopCarousel.tsx`
        # - a shop carousel rendered on a game profile page.
        #
        # Every id below is the `isReactCompilerEnabled() == false` branch (the
        # live one) with the compiled sibling listed too, so a flag flip cannot
        # bring any of them back.
        "71771": "CollectiblesShopScreen",
        "71771": "CollectiblesShopScreen",
        "64422": "ShopThisLookActionSheet",
        "64422": "ShopThisLookActionSheet",
        "64437": "ShopThisLookMarketingCoachmark",
        "64437": "ShopThisLookMarketingCoachmark",
        "50529": "GameProfileShopCarousel",
        "50529": "GameProfileShopCarousel",
        # --- Wishlist
        "64839": "AddToWishlistGrid",
        "64839": "AddToWishlistGrid",
        "64840": "AddToWishlistItemCard",
        # --- Quests, in Settings.
        # 62298 stubs the *screen* the row navigates to, which is not what puts
        # the row on screen - the row stayed visible on device for exactly that
        # reason. The row's visibility is decided by the route's own
        # `usePredicate`, and the whole chain is now traced:
        #
        #   SettingHookHarness (61429) reads SETTING_RENDERER_CONFIG[key] and
        #   treats `false === cfg.usePredicate()` as hidden; toSettingListItems
        #   (99355) then filters rows through `isBlocked`.
        #
        # `modules/user_settings/defs/native/QuestHomeSetting.tsx` (factory
        # 14531) builds that route with `usePredicate` as closure 62294, whose
        # body is `getIsEligibleForQuests()`. 62294 is the lever, and it is
        # *not* the shared body that made getIsEligibleForQuests itself
        # unpatchable - offset 1fd3172 has exactly one function on it.
        # It is stubbed in FALSE_TARGETS below, not here: the harness compares
        # with `false ===`, so returning undefined would leave the row visible.
        "69721": "QuestHomeSetting",
        "69721": "QuestHomeSetting",
        "22017": "handleFingerprint",
        # LogAggregator.tsx - feeds the debug panel's log buffer.
        "20404": "report",
        # --- modules/app_analytics/AppAnalyticsManager.tsx
        # Verified dead by reading the bodies: `_trackStartSpeaking` builds a
        # 9-field object plus two `Object.assign` merges (getGameMetadata,
        # getPacketStats) and `_trackStartListening` does the same, purely to
        # hand the result to `trackWithMetadata` - which this same suite already
        # stubs to a bare `debugger;` (fn 33307). So every field is computed,
        # every object allocated, and the result discarded.
        #
        # `_trackStartSpeaking` runs on every SpeakingStore aggregate transition,
        # i.e. every time someone in the call starts or stops talking, and
        # `_trackStartListening` alongside it. Both bodies are unique
        # (offset 2147d68 / 2147e54, one function each), and both return nothing.
        "75337": "_trackStartSpeaking",
        "75338": "_trackStartListening",
        # The 60-second interval callback that keeps them firing for the whole
        # call. Its entire body is `_trackStartSpeaking(); _trackStartListening();`
        # - an Interval started on joining voice and stopped by `_reset()` on
        # leave. Stubbing this is the cleanest lever: it kills the recurring
        # cost while leaving the channel-tracking bookkeeping in 107062 intact.
        #
        # NOT stubbed in the same module: `VoiceStateAnalytics.setSpeaking`
        # (59640) and `updateVoiceStates` (59637). `setSpeaking` WRITES store
        # state (`self.totalSpeakers[arg0] = arg1`), and while the only reader
        # found is the disconnect analytics payload, a write-shaped function is
        # not something to no-op on inference. Left alone.
        # 349.5's anonymous 60-second voice-analytics interval callback has no
        # structural match in 354.1's bundle; its two callees (_trackStart
        # Speaking / _trackStartListening) are stubbed below, so the timer left
        # behind does nothing observable. Retired rather than guessed at.
        # SessionHeartbeatScheduler.tsx - the periodic heartbeat that pings the
        # API on a timer and emits a breadcrumb every time. No-op'ing the
        # initialiser means the scheduler is never started, so the timer and
        # its listeners are never created.
        "43388": "initSessionHeartbeatScheduler",
        # QuestFetchManager.tsx - handlePostConnectionOpen installs the
        # recurring setInterval that refetches quests forever. The endpoints
        # are already blocked, so this is pure wasted timer + network churn.
        "118774": "handlePostConnectionOpen",
        # SentryUtils.native.tsx - Sentry JS API surface.
        "25572": "setUser",
        "25573": "clearUser",
        "25574": "setTags",
        "25575": "setExtra",
        "25576": "captureException",
        "25577": "captureCrash",
        "25578": "captureMessage",
        "25579": "addFeatureFlag",
        "25580": "addBreadcrumb",
        "25582": "crash",
        "25583": "triggerMemoryWarning",
        "25584": "markCrashHandled",
        # actions/SurveyActionCreators.tsx - the void half of the app-rating
        # survey: a dispatcher update plus an APP_NOTICE_* analytics event.
        "71519": "overrideSurvey",
        "71520": "surveyHide",
        # app-start-performance/AppStartPerformance.tsx - the recorders behind
        # `mark()`/`markAndLog()`, called at every startup milestone and on
        # every traced operation. Each one appends a formatted string to an
        # in-memory log; none of them return anything. The `time`/`timeAsync`
        # wrappers and the `isTracing` getter are left alone because callers
        # consume their results.
        "20476": "resumeTracing",
        "20477": "mark",
        "20478": "markAndLog",
        "20479": "addImportLogDetail",
        "20480": "markWithDelta",
        "20481": "markAt",
        "20482": "addDetail",
        "20485": "setServerTrace",
        # modules/tti_analytics/TTITracker.tsx - records per-milestone timings
        # and import counts that are assembled into the startup analytics
        # payload. `measure`/`serialize*`/`time` return values and stay.
        "20433": "recordStart",
        "20435": "recordEnd",
        "20445": "record",
        "20437": "set",
        # modules/resource_usage/MonitoringAgent.tsx - accumulates client
        # metrics and POSTs them to `/metrics/v2` on a 2-minute timer. That
        # endpoint was not blocked, so this was a live upload. `increment` is
        # called from ~25 modules.
        "38240": "increment",
        "38241": "distribution",
        "38242": "_flush",
        # metrics/MetricsAggregator.tsx - buckets client metrics and flushes
        # them on an interval.
        "103986": "add",
        "103989": "_flush",
        "103990": "_captureMetrics",
        # metrics/BrowserMetricsAggregator.tsx - the browser-side twin.
        "104011": "add",
        "104012": "flush",
        # metrics/SessionFlusher.tsx - 60s timer that ships session aggregates.
        "103875": "flush",
        # analytics/useTrackImpression.tsx - builds its own trackMaker and
        # posts to `/science` without going through AnalyticsUtils.track, so
        # the earlier stub did not cover it. Void; all callers discard.
        "37621": "trackImpression",
        # Sentry's core addBreadcrumb (module 773). The Flux dispatcher adds a
        # breadcrumb for every action; with the DSN blank there is no egress,
        # but this stops the collection at the source.
        "22882": "addBreadcrumb",
        # --- always-on sampling / startup work that only fed telemetry ---
        # websocketTelemetryHook: installs a patched global WebSocket whose
        # per-message handler JSON.parses every gateway frame a second time
        # and appends to the (disabled) telemetry ring.
        "77122": "installWebsocketTelemetryHook",
        # ProcessUtils 1 Hz sampler retired: 354.1's anonymous replacement has
        # no structural match and the old id now names an unrelated function
        # (`monthsRegex`). Its only consumer was the telemetry ring, which is
        # stubbed above.
        # Voice call 1 Hz samplers feeding the voice-quality analytics payload.
        # 354.1: 115029 resolved to 88911 by matching the body
        # (`incrementNetworkStats` + `takeSample` + transportBaseline).
        "88911": "sampleStats",  # VoiceQuality.sampleStats
        "88931": "sampleStats",  # SystemResponsiveness.sampleStats
        # A fourth per-call sampler, missed in the 347.5 round. Runs on every
        # call stats callback: incrementNetworkStats -> updateSystemResourceStats
        # -> updateVideoEffectStats -> receivedStats. 347.5's equivalent (98345,
        # 112 bytes) was identified but never added.
        "88987": "sampleStats",  # video-effect / system-resource sampler
        # 5-minute voice-quality payload builder.
        "88602": "_handleVoiceQualityPeriodicsStats",
        # Gateway READY analytics: 58075 JSON.stringify's the whole READY
        # payload ~14 times on 1% of connects; 58073 walks every guild.
        "65738": "logReadyPayloadReceived",
        "65739": "getConnectionPath",
        "65740": "getReadyPayloadByteSizeAnalytics",
        "65741": "logGatewayConnected",
        # Per-request URL regexes + telemetry ring append.
        "75145": "trackHttpRequest",
        # AutoAnalytics navigator lifecycle -> trackWithMetadata/track.
        "74076": "componentDidMount",
        "74077": "componentDidUpdate",
        # MessageCacheStats recorders: write into an unbounded Map that is only
        # read by the stubbed analytics emitter.
        "43544": "recordChannelFetchStart",
        "43545": "recordChannelFetchedLocal",
        "43546": "recordChannelFetchedNetwork",
        "77056": "handleAppStateUpdate",
        # module_2: called by ~10k modules at import to fill a Set read only by
        # the stubbed TTITracker.
        # MemoryExperiment: module-scope 60s timer that never clears (the
        # bytecode passes the callback to clearInterval, not the timer id).
        # Anonymous, so it has no name to match on: identified by the module
        # path it references (modules/memory/MemoryExperiment.tsx). 354.1's
        # replacement could not be located structurally; retired rather than
        # guessed at. The 60 s timer only fed the stubbed tracker.
        # timerUtils: stringifies slow-timer records for the stubbed track.
        "77228": "setupLibdiscoreTimersMonitor",
        # net_stats/NetStats.tsx - the local sampler. Its network side is
        # already dead (submitEventsImmediately is stubbed), but it still wrote
        # to Storage every 5s, sampled the radio every 60s and accumulated
        # per-message counters. `track` and `writeExistingEventStorage` are
        # async, but every caller is a timer that discards the result, so the
        # no-op shape is safe — verified in the Hermes VM
        # (tools/hermes-test/harness-async.js). `getQueuedEvent` is a read whose
        # value is consumed and is left alone.
        "43355": "handleAppStateChange",
        "43356": "writeExistingEventStorage",
        "43357": "track",
        "43359": "f39936",
        "43360": "f39937",
        "43361": "f39938",
        # modules/promotions/PromotionsManager.tsx - POST_CONNECTION_OPEN
        # handler that fetches `/promotions` on every launch. No cache opcode,
        # so the no-op shape; its caller (the lifecycle manager) discards it.
        # 77913 DROPPED in 349.5. Its body is shared with two functions in
        # `usePromotionMarketingComponent` (a different module), so stubbing
        # it would stop those too - and it buys nothing: `/promotion`,
        # `/bogo-promotions` and `/outbound-promotions` are all in
        # blocklist.py, so the fetch it triggers is already dead.
        # "77913": "onPostConnectionOpen",
        # errors/native/SentryInitUtils.tsx - the JS SDK initialiser. Stubbing
        # it means no client is ever bound, so none of the automatic
        # integrations (global error handlers, breadcrumbs, promise-rejection
        # tracking) are installed at all. `routingInstrumentation` is a separate
        # export and is unaffected.
        "25591": "initSentry",
    }
    # Promise-returning pollers and fetchers. Their callers do `fn().then(…)`
    # or `await fn()`, so `return undefined` would throw. This body returns a
    # resolved promise instead. It is compiler output — `hermesc` compiled
    # `function f(){ return Promise.resolve() }` — and was executed in a real
    # Hermes VM to confirm the shape (see tools/hermes-test/).
    #
    # Both lookups share inline-cache slot 0 on purpose: `patch-function` does
    # not grow a function's register file or cache region, so the stub must not
    # use more than the original body did. The two receivers are the global
    # object and the `Promise` constructor, whose shapes can never be equal, so
    # the shared slot always misses and the result stays correct.
    PROMISE_TARGETS = {
        # THE central analytics emitter - `AnalyticsUtils.trackMaker()` returns
        # this. Proven by disassembly rather than by name: module 1250 exports
        # `trackMaker` as `CreateClosure ..., 23107`, and 23107's only closure
        # is `CreateClosureLongIndex r1, r1, 73760`. So 73760 is the `track`
        # every feature calls to emit a science event, and it is the one that
        # builds event properties and super properties per event.
        #
        # It was NOT being stubbed. The TARGETS entry below lists
        # "23080": "track", which is a *different* function that happens to
        # share the name - it is one of the ~76 closures created inside module
        # 1242 (the analytics store), not the one trackMaker returns. Ten
        # functions in this bundle are named `track`; matching by name picked
        # the wrong one. Both are stubbed now.
        #
        # It returns a promise (early paths `return Promise.resolve()`, the
        # normal path returns the dispatcher's result), so it takes the promise
        # shape - an awaiting caller must still get a resolved promise.
        "81313": "track",

        # actions/SurveyActionCreators.tsx - the app-rating survey poll. The
        # two fetch functions hit the network and emit analytics events; the
        # two void ones dispatch and emit an event.
        "45503": "surveyFetch",
        "71521": "surveySeen",
        # storefront price/product/config fetchers. The shop is already 204'd at
        # the network layer; this removes the failed request and its handling.
        "53932": "fetchSocialLayerStorefrontSkuForApplication",
        # actions/QuestActionCreators.tsx - the quest fetchers the UI calls on
        # a timer (useAdRefreshLoop / useQuestHooks poll every second while a
        # quest is active). The endpoints are already 204'd; this stops the
        # calls being made at all. Every call site ignores the promise.
        # analytics/AnalyticsTrackingStore.tsx - `submitEventsImmediately` is
        # the single POST behind both `/science` and `/beaker`. Every caller
        # discards the resolved value, so a resolved promise is shape-safe.
        # This is the chokepoint that also covers NetStats' direct submission
        # and the JankSessionManager events.
        "81318": "submitEventsImmediately",
        # Storefront / quest fetchers, killed via the implementation each
        # exported trampoline forwards to rather than the export itself.
        # The exports are `function f() { return g.apply(this, arguments) }`,
        # and Hermes deduplicates that body — it is shared with up to 57 other
        # headers — so patching the export would silently no-op dozens of
        # unrelated API calls (that is the bug that hung the app on its loading
        # screen). The `_`-prefixed implementations have unique bodies, so they
        # are the safe target. Every caller discards the promise.
        "48471": "_maybeFetchProductsBySkuIds",
        "50618": "_fetchStorefrontPricesForApplicationId",
        "50619": "_fetchStorefrontPricesForSkuIds",
        "53928": "_fetchSocialLayerStorefrontConfig",
        "51322": "_fetchCurrentQuests",
        "51323": "_sendHeartbeat",
        "51332": "_fetchClaimedQuests",
        "51333": "_fetchQuestToDeliver",
        "51334": "_fetchEarnedQuestToDeliver",
        "50897": "_maybeFetchCollectionsWithProducts",
        # CONNECTION_OPEN roots found by logging what the interceptor 204s at
        # runtime. Each of these fired on every launch without the user opening
        # anything, and each hits an already-blocked endpoint.
        "48843": "fetchGuildAffinities",           # /users/@me/affinities/guilds
        "56683": "subscribePlayerStateNotifications",  # api.spotify.com/me/player
    }
    # Stubs that must return a number rather than undefined: a caller reads
    # `.length` of the result, or treats it as a boolean.
    # Functions whose callers test the result as a boolean. Returning a
    # constant false hides the surface without removing the function, so any
    # caller that also does bookkeeping still runs.
    FALSE_TARGETS = {
        # 349.5: plain closure in QuestDockWithVisibilityContext's factory
        # (14889), found via the `PutByIdStrict ..., "useIsMobileQuestDock
        # VisibleToUser"` store rather than by name - the hook is no longer
        # named in the function table.
        "70072": "useIsMobileQuestDockVisibleToUser",
        # The Quest row in Settings. This is the route's own `usePredicate`
        # closure from `defs/native/QuestHomeSetting.tsx` (factory 14531), and
        # it must return exactly `false`: SettingHookHarness tests
        # `false === cfg.usePredicate()`, so an undefined return leaves the row
        # on screen. Stubbing *this* rather than `getIsEligibleForQuests` is the
        # whole point - the predicate is a unique 34-byte body, while the
        # function it calls is the unpatchable shared one.
        "69718": "usePredicate",
        # The other two rows that carry the word "Quests" in Settings:
        #
        #   modules/user_settings/defs/native/UseDataForQuestsSetting.tsx
        #   modules/user_settings/defs/native/UseDataForQuests3PSetting.tsx
        #
        # Both are `createToggle({ parent: MobileUserSettings.DATA_AND_PRIVACY,
        # usePredicate(){...}, ... })` and both already carry their own
        # predicate, so - like the Quest row above - the row is hidden by
        # stubbing that closure and nothing else:
        #
        #     usePredicate() { return !useIsAdTopicOptOutClientEnabled(); }
        #
        # The two bodies are byte-identical (`GetParentEnvironment ... Not r0`
        # / `Ret r0`, 12 instructions each, same offsets), so Hermes folded
        # them onto one offset and ONE entry does both. They are listed
        # separately anyway: the patcher's shared-body guard refuses a
        # deduplicated body unless every sharer is declared in SHARED_OK
        # (which it is, for both).
        #
        # Note these are NOT the `...SponsoredContentSetting` siblings in the
        # same modules. Those pass the imported `useIsAdTopicOptOutClientEnabled`
        # itself as their predicate, so hiding them would mean editing the
        # route object rather than stubbing a closure - and their titles are
        # about ad personalisation, not quests. Left alone on purpose.
        # Named `usePredicate` (the real function-table name), not
        # `UseDataForQuestsSetting.usePredicate`: the patcher's post-stub check
        # looks for `function <name>(` in the decompilation, so a descriptive
        # label makes it skip the target it was just told to patch. The guard
        # against the wrong body is the SHARED_OK alias set, not the label.
        # Only 71516 is listed. 71528 is the *same body* (same offset, same
        # 37 bytes) so stubbing one stubs both; listing it too would add a
        # permanent `decompiler did not name it` SKIP on the second pass,
        # because by then the body it points at is already the stub.
        "71873": "usePredicate",
        # --- Guild Shop (creator monetisation).
        #
        # `modules/creator_monetization/guild_shop/` holds only two modules, and
        # both are visibility gates - the same shape as the quest gates above,
        # so they are stubbed to `false` rather than chasing the screen
        # component, which is not under any shop-named module.
        #
        #     useGuildShopVisibleInGuild  exports the hook (live 39223 /
        #                                 compiled 39222) and a plain
        #                                 `isGuildShopVisibleInGuild` (39224)
        #     useGuildShopPreviewVisible  RC pair, live 39226 / compiled 39225
        #
        # Every one of these is a boolean gate, so they take FALSE rather than
        # the NOOP shape: a strict `=== false` caller would not accept
        # `undefined`, and `return 0` is not `false` to one either.
        "42256": "useGuildShopVisibleInGuild",
        "42256": "useGuildShopVisibleInGuild",
        "42257": "isGuildShopVisibleInGuild",
        "42258": "useGuildShopPreviewVisible",
        "42258": "useGuildShopPreviewVisible",
        # --- Guild/clan tags, from the same plugin. The component nulls above
        # hide the chips; these two gates also stop the callers that *decide*
        # whether to build a tag row, so nothing is constructed at all.
        #
        # `shouldDisplayGuildTag` is a plain boolean predicate; the other is a
        # hook returning a boolean. Both take FALSE - a strict `=== false`
        # caller would not accept the NOOP shape, and `return 0` is not `false`
        # to one either.
        "48278": "shouldDisplayGuildTag",
        "48277": "useShouldDisplayGuildTag",
        # Billing / Nitro / boost / subscriptions. These four defs *already*
        # carry a `usePredicate`, so the row can be hidden by stubbing that
        # closure - no object surgery, exactly like the Quest row above and
        # unlike the CollectiblesShop row (which had no predicate and needed
        # its route object edited).
        #
        # Every one of these predicates guards a backend that is already dead
        # in this build: the storefront, collectibles and subscription
        # endpoints are 204'd, so the rows lead to screens that cannot do
        # anything. Each body is unique (verified by body offset), so stubbing
        # one cannot take an unrelated setting with it.
        # Gift Inventory. 349.5 keeps the exact body
        # (`isPremiumGiftingSupported()`), now anonymous at 68590.
        # libdiscore action telemetry. `actionHandler` runs on EVERY redux
        # action and hands the JSON-serialised payload to
        # `dispatchAction(json, shouldCollectMetrics())`. The sampling rate
        # is server-controlled:
        #
        #     getMetricsSampleRate() -> treatmentId 1: 0.01, 2: 0.05,
        #                               3: 1, else 0
        #
        # so Discord can turn on action-payload collection for this client
        # at any time, up to MAX_EMISSIONS_PER_APP_LAUNCH (5) per launch.
        # Present in 348.2 as well and never covered - found while looking
        # for NEW telemetry, which is the honest way round.
        #
        # `actionHandler` itself must NOT be stubbed: `dispatchAction` is
        # the store's own dispatch (11 refs, incl. `_dispatchWithLogging`),
        # not a telemetry sink. Cutting the gate leaves the bridge intact
        # and stops the collection.
        "33138": "shouldCollectMetrics",
        "69713": "usePredicate",                     # Gift Inventory
        # Manage Subscriptions: 354.1 dropped the wrapper that used to carry
        # this name and passes `useHasPremiumSubscriptionToDisplay` straight
        # to the route. Stubbing that shared predicate would also hide the
        # You-tab banner and settings overview entries, so the row is left to
        # the route body edit in `manage_subs` instead.
        "70458": "useHasGuildRoleSubscriptionsSetting",  # Server Subscriptions
        # Restore Subscription: body matches 348.2 exactly
        # (`useStateFromStores(...).verified && !isAndroid()`), now an RC pair.
        # 69609 is the *action* closure in that module - wrong target.
        "70577": "useHasPremiumRestoreSubscriptionSetting",
        # NOT PATCHABLE: `getIsEligibleForQuests` (348.5 id 52676) shares its
        # 37-byte body with six unrelated capability checks
        # (isPremiumGiftingSupported, isActionPickSupported,
        # isImageCaptureIntentSupported, ...). Hermes stores that body once, so
        # the guard refuses it and always will. `useIsMobileQuestDockVisibleToUser`
        # below is the working lever for the same dock.
        # modules/slayer_storefront/SlayerStorefrontUtils.tsx
        #   Guild-sidebar GUILD_GAME_SHOP row (fn 66028 `JmpFalse L938`) and
        #   the canSeeGameShop / GAME_SHOP redirect.
        "41991": "hasSocialLayerStorefront",
        # modules/quests/.../QuestHooks.native.tsx
        #   The alternate quest-dock lever. `getIsEligibleForQuests` cannot be
        #   patched - its 37-byte body is byte-identical to six unrelated
        #   capability checks (isPremiumGiftingSupported,
        #   isActionPickSupported, isImageCaptureIntentSupported, ...) and
        #   Hermes stores it once, so the shared-body guard refuses it. This
        #   hook has its own body and a single consumer
        #   (QuestDockWithVisibilityContext, fn 63559).
        # (62451 removed: it was a stale 348.2 id for the hook above, which
        #  is now 68858.)
        # modules/spotify/SpotifyActionCreators.tsx
        #   `fetchIsSpotifyProtocolRegistered()` asks the *on-device Spotify app*
        #   whether Discord can talk to it over a local protocol. That is the
        #   path blocking api.spotify.com can never reach: the playback state
        #   comes over a local socket, not the network, and the client then
        #   broadcasts it in its presence - which is why other people still see
        #   the song. Reporting it unregistered means nothing to broadcast.
        "56688": "fetchIsSpotifyProtocolRegistered",
        # The track renderer. `isListeningOnSpotify` gates what *your* app
        # decides is a Spotify activity, but a friend's Spotify presence is
        # rendered by this component directly - which is why stubbing the gate
        # left other people's songs still visible.
        "56663": "SpotifyTrack",
    }

    ZERO_TARGETS = {
        # Boolean consumed in an `if (...)`, gating quest items in the activity
        # panel. `getIsEligibleForQuests` (the broader predicate) shares its
        # body with seven unrelated capability checks and is permanently
        # unpatchable.
        # Resolved from the `PutByIdStrictLong ..., "useShouldShowQuests
        # ActivityPanelItem"` store in factory 10912.
        "51310": "useShouldShowQuestsActivityPanelItem",
        # modules/.../SpotifyUtils.tsx - the one real Spotify gate. Every one
        # of its 8 call sites tests it in an `if (...)`, so a falsy constant is
        # the correct shape. Blocking api.spotify.com is deliberate, so this
        # removes the remaining branding: presence, "Play on Spotify", the
        # Spotify embed in activity cards, and the outbound Spotify activity
        # the client would otherwise broadcast.
        # The other half of the libdiscore telemetry gate - `return 0` makes
        # `shouldCollectMetrics()` false at the source rather than at the
        # consumer, and covers any other caller of the rate.
        "33136": "getMetricsSampleRate",
        "54082": "isListeningOnSpotify",
        # analytics/ZoomedInAnalyticsExperiment.tsx - falsy guarantees the
        # 500 ms TelemetryRing export loop can never start.
        "27796": "isZoomedExperimentEnabled",
        # NOTE: pako's `deflate` (fn 58131) is deliberately NOT stubbed. Its
        # only telemetry use is GatewaySocket's identifyCompressedByteSize, but
        # it is the same function object `decodeImage.tsx` calls to encode
        # PNG/APNG chunks — stubbing it broke image decoding and the app hung on
        # the loading screen. A per-connect deflate is not worth that.
    }
    OBJECT_FALSE_TARGETS = {
        # modules/virtual_currency/hooks/native/useVirtualCurrencyMobileEnabled
        # The orb gate: `{enabled: !isMetaQuest()}`. Every orb surface reads
        # `.enabled` off the result, and `YouScreenUserProfileContent` builds
        # the BalanceWidgetMenu element only `if (enabled)` - so forcing this
        # false removes orbs at the source rather than hiding each screen that
        # shows them.
        #
        # `isVirtualCurrencyEnabled` (fn 57119) is the SAME body under a second
        # name; Hermes deduplicated them. Patching 57120 therefore disables
        # 57119 as well - verified, and that is the desired outcome for both.
        # The shared-body guard refuses this by default, hence the SHARED_OK
        # entry above, which re-verifies the sharer set every run.
        "64334": "useVirtualCurrencyMobileEnabled",
    }
    # React function components that should draw nothing.
    NULL_TARGETS = {
        # modules/collectibles/profile_effects/native/ProfileEffect.tsx
        #   The animated decoration layer that plays over the profile *card* -
        #   a looping video/composite drawn behind the avatar and banner. This
        #   is the "profile card animation" the user asked to block; the
        #   profile *picture* decoration is a different module
        #   (collectibles/avatar_decorations/) and is deliberately untouched.
        #
        # `WrappedProfileEffect` already has an early `return null` when the
        # effect has not resolved, so a null return is a state the component
        # and every caller already handle - this is not a new shape for them.
        # 349.5: renamed to an anonymous memo() pair, but the body is
        # unchanged - `let thumbnailUrlOverride = arg0; ... arg0.skuId` - and
        # the pair is the module's `default` export.
        "50684": "WrappedProfileEffect",
        "50684": "WrappedProfileEffect",

        # --- The Wishlist *grid* on a profile.
        #
        # These are a different surface from the profile TAB that
        # `profile_tabs` / `wishlist_index` remove. Those edit the segmented
        # control's tab list; the user still saw "Wishlist" on their own
        # profile and on other people's, which is this: a grid of wishlisted
        # items rendered as a section of the profile body, for any profile.
        #
        # modules/user_profile/native/UserProfileWishlistGrid.tsx
        #   `CreateClosure r3, r3, 62758` sits immediately before
        #   `PutByIdStrict r2, r3, 0, "default"`, so 62758 is the export. It is
        #   a plain function, not an RC pair - this module has none on its
        #   default.
        #
        # modules/user_profile/native/UserProfileWishlistSuggestionsGrid.tsx
        #   That module has FOUR RC pairs for inner components and one for the
        #   export. The export is the FIRST pair: r1 is set at L564 and is
        #   never written again before `PutByIdStrict r2, r1, 0, "default"`.
        #   The last pair (62773/62772) is stored to environment slot 18 and is
        #   NOT the export - 62773 decompiles to a `useStateFromStores` +
        #   `useMemo` hook, and stubbing a hook would hand its callers a null
        #   where they expect a subscription result. 62767 is the real one, and
        #   it is already a gate:
        #       if (useIsMobileWishlistSuggestionsEnabled(...)) { ...grid... }
        #       return null;
        #   so `null` is a return value its callers already handle.
        #
        # Both are React components, so they take the null shape: `undefined`
        # is only a legal component return from React 18 on and `0` renders as
        # the text "0", whereas null renders nothing in every version - and
        # the grid already has an empty/failed state its callers handle.
        # modules/quests/native/QuestHome.tsx (factory 14807) - the Quests
        # home screen itself. Every mobile entry point into it is already
        # gated (`useShouldShowQuestsActivityPanelItem`,
        # `useIsMobileQuestDockVisibleToUser`, `floating_nav_quests`, and the
        # Settings row via 68593), but the screen is the thing a route name
        # navigates to and routes are cheap to reach. Nulling the export means
        # that even if something still navigates there, nothing renders.
        #
        # Export is `memo(isReactCompilerEnabled() ? A : B)` - the closure pair
        # is created at L827/L834 and fed straight to `memo()`, whose result is
        # stored as `"default"`. 68629 is the `false` branch (the live one) and
        # 68628 the compiled one; both are listed so a flag flip cannot bring
        # it back.
        "69779": "QuestHome",
        "69779": "QuestHome",
        "64825": "UserProfileWishlistGrid",
        "64834": "UserProfileWishlistSuggestionsGrid",
        "64834": "UserProfileWishlistSuggestionsGrid",
        # --- Collectible clutter, ported from the Revenge "declutter" plugin.
        #
        # Everything below was resolved by MODULE PATH + EXPORT SLOT, not by
        # name: the plugin's own `kmmiio-lib` filters match on
        # `exports.default.name`, and the 349.5 React-Compiler closures are
        # anonymous in the function table, so the name-based discovery it uses
        # does not work on this bundle.
        #
        # For every pair, the FIRST id is the `isReactCompilerEnabled() == false`
        # branch (the live one) and the second is the compiled sibling, so a
        # flag flip cannot bring the clutter back.
        #
        # All of these are React function components taking a props object
        # (`arg0.nameplate`, `arg0.primaryGuild`, `{source, size}`, ...), so
        # `return null` is the correct "draw nothing" shape.

        # modules/collectibles/avatar_decorations/AvatarDecorationUtils.tsx
        #   THE choke point for avatar decorations, and deliberately the only
        #   decoration target. Its callers are the data transforms that bring a
        #   decoration into the client at all - `transformUser` (23962, 50210),
        #   `transformGuildMember` (67166), `handleGuildMemberUpdate` (27261),
        #   `handleGuildMemberProfileUpdate` (27310),
        #   `getAvatarDecorationFromServerMember` (27263) - and every one of
        #   them only *assigns* the result (`PutByIdStrict ..., "avatarDecorationData"`),
        #   never dereferences it.
        #
        #   Null is a first-class return here, not a new shape: the function
        #   has two existing `return null` paths (non-object input, and
        #   `parseSkuIdFromServerData` returning null). Downstream,
        #   `getAvatarDecoration` (45453) explicitly handles a null
        #   `member.avatarDecoration` and falls back to `arg0.avatarDecoration`,
        #   which this edit also makes null - so no default decoration appears.
        #
        #   Stubbing the *render* hooks (`useAvatarDecoration`, the Avatar
        #   component's prop) is therefore unnecessary, which matters because
        #   those are the ones whose callers could have thrown. This also covers
        #   native chat rows, which the plugin can only reach through its
        #   runtime native bridge and which no static JS edit could otherwise
        #   touch.
        "27751": "parseAvatarDecorationData",

        # modules/collectibles/nameplates/native/Nameplate.tsx
        "50712": "Nameplate",
        # modules/collectibles/profile_frames/native/ProfileFrame.tsx
        "48497": "ProfileFrame",
        # modules/guild_tag/native/GuildTag.tsx - default, GuildTagBadge and
        # BaseGuildTagChiplet. All three are rendered as chips next to names.
        "56096": "GuildTag",
        "50111": "GuildTagBadge",
        "50112": "BaseGuildTagChiplet",
        # modules/guild_tag/native/VoiceGuildTag.tsx - 354.1 only ships the
        # chiplet, which is the part that draws.
        "72648": "VoiceGuildTagChiplet",
        # --- 354.1 Hexagon campaign (2026-09-hexagon-campaign-client).
        # Its two experiment gates share their tiny bodies with unrelated
        # config readers, so the components are stubbed instead. Each name is
        # campaign-specific; the general theme carousel and font picker are
        # deliberately untouched.
        "71084": "HexagonCampaignAppIconSection",
        "68830": "UserProfileHexagonCampaignDisplayNameStyleEditButton",
        "68826": "HexagonCampaignDisplayNameStylePromo",
        "69911": "HexagonCampaignSheet",
    }
    # Hooks whose callers iterate the result. See EMPTY_ARRAY_HASM.
    EMPTY_ARRAY_TARGETS = {
        # modules/user_profile/hooks/useBadges.tsx - the profile badge row.
        #
        # `[]` is this hook's own existing return on two of its paths
        # (`if (null == arg0) return [];` and `if (badges == null) badges = [];`),
        # so it is a shape every caller already handles rather than a new one.
        "48538": "useBadges",
        "48538": "useBadges",
    }
    NOOP_HASM = "0000  LoadConstUndefined r0\n0001  Ret r0\n"
    # For callers that read a property of the result (`deflate(...).length`) or
    # treat it as a boolean.
    ZERO_HASM = "0000  LoadConstUInt8 r0, 0\n0001  Ret r0\n"
    # React function components. `undefined` is only a legal return in React 18
    # and later, and `0` renders as the literal text "0" - so neither the no-op
    # nor the zero stub is right for one. `null` renders nothing in every React
    # version, which is what "do not draw this" means.
    NULL_HASM = "0000  LoadConstNull r0\n0001  Ret r0\n"
    # Boolean-returning gates. `return 0` is not `false` to a
    # strict caller, so these get their own shape rather than reusing ZERO.
    FALSE_HASM = "0000  LoadConstFalse r0\n0001  Ret r0\n"
    # Hooks whose callers iterate the result. `undefined`, `null` and `0` all
    # throw on `.map` / `.filter` / `.length`, so a collection needs its own
    # shape. Only used where the function's own body already returns `[]` on
    # one of its paths - that is the evidence the shape is one its callers
    # handle, not a guess.
    EMPTY_ARRAY_HASM = "0000  NewArray r0, 0\n0001  Ret r0\n"
    # Some gates return an OBJECT whose property the caller reads, e.g.
    # `useVirtualCurrencyMobileEnabled().enabled`. `return false` hands the
    # caller `undefined` and the property read throws - so a boolean stub is
    # actively wrong there, not merely imprecise. This shape returns
    # `{enabled: false}`.
    #
    # It reuses the ORIGINAL body's `NewObjectWithBufferLong` template rather
    # than constructing an object, so the result has the same shape as the
    # real return value and costs no register or inline-cache growth. The
    # caller of this generator substitutes BUFFER_INDEX from the original
    # disassembly; see patch_js.
    # The register and operands are filled in from the original disassembly
    #, not written down: the buffer index is per-function and the object
    # register varies. Nothing here is stable across a version bump.
    OBJECT_FALSE_TEMPLATE = (
        "GetParentEnvironment r{env}, {depth}\n"
        "NewObjectWithBuffer{width} r{obj}, {buf}, {shape}\n"
        "LoadConstFalse r0\n"
        "PutOwnBySlotIdx r{obj}, r0, 0\n"
        "Ret r{obj}\n"
    )
    # `GetById`, NOT `GetByIdShort`.
    #
    # The short form carries its property name in an 8-bit operand, and on the
    # 349.5 bundle that operand is mis-resolved: the identical HASM that
    # decompiles to `Promise.resolve()` on 348.2 comes back as
    # `Promise.hash()` on 349.5, and asking for "race" yields `Promise.value()`
    # - the name is read from the function's existing (now stale) inline-cache
    # region rather than from the instruction, so it is not even stable per
    # function. The long form takes a full-width string reference and is
    # unaffected: it renders exactly the property asked for. Verified for
    # resolve/race/all at cache index 0 and 1.
    #
    # Cache index 0 is kept because CACHE_OPCODE below already proves the
    # function owns an inline-cache region and `patch-function` cannot grow
    # one - staying at slot 0 is what makes this safe to write. Before this
    # change 17 PROMISE_TARGETS failed verification; after it, 0 do.
    PROMISE_HASM = (
        "0000  GetGlobalObject r0\n"
        '0001  TryGetById r1, r0, 0, "Promise"\n'
        '0002  GetById r0, r1, 0, "resolve"\n'
        "0003  Call1 r0, r0, r1\n"
        "0004  Ret r0\n"
    )
    # Any of these means the function already owns an inline-cache region, so
    # the stub's slot 0 is safe to use.
    CACHE_OPCODE = re.compile(
        r"\b(?:TryGetById|GetByIdShort|GetById|PutById|PutByIdStrict)\b"
    )

    def run(*args):
        return subprocess.run(
            [tool, *args], capture_output=True, text=True, timeout=900
        )

    def already_stubbed(text):
        """True if a decompiled body is exactly one of the stub shapes.

        Matching on substrings is not enough: real Discord code calls
        `Promise.resolve()` (so `AnalyticsUtils.track` looked "already stubbed"
        and was silently skipped). Compare against the exact statements a stub
        decompiles to instead.
        """
        allowed = {
            "debugger;",
            "const resolved = Promise.resolve();",
            "return resolved;",
            "return Promise.resolve();",
            "return 0;",
            "return false;",
            "return null;",
            "return [];",
        }
        body = text.split("{", 1)[-1].rsplit("}", 1)[0]
        return all(
            not line.strip() or line.strip() in allowed
            for line in body.split("\n")
        )

    # Hermes deduplicates identical function bodies, so many function headers can
    # point at one bytecode body. `patch-function` rewrites the *body*, so
    # patching one of those also rewrites every sibling. Stubbing
    # `maybeFetchCollectionsWithProducts` — whose rest-args trampoline body is
    # shared with 57 headers including `fetchConversationMessages` and
    # `deleteChannel` — silently no-op'd dozens of unrelated API calls and hung
    # the app on its loading screen. Refuse to patch any body that is not unique.
    # Parse from the RIGHT, not by field count. The table is
    #   `ID  Name(padded)  Offset  Size`
    # and the name column is hostile to naive parsing in two ways that both
    # bite in the dangerous direction:
    #
    #   * 58,646 of 128,469 rows have an EMPTY name (anonymous functions), so a
    #     `(\S*)`-style pattern drops them outright;
    #   * names can contain SPACES (`get ActivityIndicator`), so a fixed
    #     field count drops those 335 too.
    #
    # Dropping a row undercounts how many headers share a body, and
    # undercounting is the exact error that hung the app on its loading screen:
    # a body read as "unique" was patched out from under 57 siblings,
    # including `fetchConversationMessages`, and dozens of unrelated API calls
    # silently no-op'd. Overcounting is harmless. Offset and size are always
    # the final two fields, so take the name as whatever sits between the id
    # and them.
    offsets, shared, aliases = {}, {}, {}
    for line in run("dump", "--kind", "functions", bundle).stdout.split("\n"):
        parts = line.split()
        if len(parts) < 3:
            continue
        fid, off = parts[0], parts[-2]
        if not fid.isdigit() or not re.fullmatch(r"[0-9a-f]{4,8}", off):
            continue
        name = " ".join(parts[1:-2])
        offsets[fid] = off
        shared[off] = shared.get(off, 0) + 1
        aliases.setdefault(off, set()).add(name)

    # Bodies Hermes deduplicated across headers that are provably the SAME
    # function under different names, so patching all of them at once is the
    # intent rather than a side effect.
    #
    # The guard's refusal is mechanical - it counts headers per offset - and
    # that is the right default: a body shared with 57 unrelated headers
    # silently no-op'd dozens of API calls and hung the app on its loading
    # screen. But it also blocks genuine aliases, where every sharer is the
    # same predicate.
    #
    # Every entry declares the exact set of names expected at that offset. If
    # the bundle ever disagrees - one extra unrelated header lands on the same
    # body - the patch is refused, so the check cannot rot into a blind
    # allowlist.
    # Keyed by function id, not by body offset: `patch-function` rewrites
    # bodies, and a stub whose length differs from the original shifts every
    # later offset. A hardcoded offset would silently stop matching (or worse,
    # start matching a different function). The id is stable; the offset is
    # resolved fresh each run and the alias set is re-verified against it.
    SHARED_OK = {
        # Keyed by the *hook* name; any of the declared ids may be the one that
        # gets patched, and all of them point at the same body.
        #
        # `useVirtualCurrencyMobileEnabled` and `isVirtualCurrencyEnabled` are
        # one predicate - `{enabled: !isMetaQuest()}` - exported under a hook
        # name and a plain name. It is the semantic gate for orbs: every orb
        # surface reads `.enabled` off it, so disabling it removes the whole
        # feature rather than one screen of it.
        "useVirtualCurrencyMobileEnabled": {
            "useVirtualCurrencyMobileEnabled",
            "isVirtualCurrencyEnabled",
        },
        # The "Use data for quests" settings predicates (71516 / 71528). Same
        # 12-instruction body at 2506814, 37 bytes, and both are named
        # `usePredicate` - so the alias set is exactly that one name.
        "usePredicate": {"usePredicate"},
    }

    done, skipped = 0, 0
    with tempfile.TemporaryDirectory() as td:
        stubs = {}
        for kind, text in (
            ("noop", NOOP_HASM),
            ("promise", PROMISE_HASM),
            ("zero", ZERO_HASM),
            ("null", NULL_HASM),
            ("false", FALSE_HASM),
            ("emptyarray", EMPTY_ARRAY_HASM),
        ):
            path = os.path.join(td, f"{kind}.hasm")
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
            stubs[kind] = path

        work = [(fn, name, "noop") for fn, name in TARGETS.items()]
        work += [(fn, name, "false") for fn, name in FALSE_TARGETS.items()]
        work += [(fn, name, "promise") for fn, name in PROMISE_TARGETS.items()]
        work += [(fn, name, "zero") for fn, name in ZERO_TARGETS.items()]
        work += [(fn, name, "null") for fn, name in NULL_TARGETS.items()]
        work += [
            (fn, name, "emptyarray")
            for fn, name in EMPTY_ARRAY_TARGETS.items()
        ]
        work += [
            (fn, name, "objfalse")
            for fn, name in OBJECT_FALSE_TARGETS.items()
        ]

        for fn, name, kind in work:
            off = offsets.get(fn)
            if off and shared.get(off, 1) > 1:
                expected = SHARED_OK.get(name)
                found = aliases.get(off, set())
                if expected is not None and found == expected:
                    print(
                        f"  [OK] {name}: body shared, but every sharer is a "
                        f"declared alias ({', '.join(sorted(found))})"
                    )
                else:
                    why = (
                        "sharers are not the vetted alias set "
                        f"(expected {', '.join(sorted(expected))}; "
                        f"found {', '.join(sorted(found)) or 'none'})"
                        if expected is not None
                        else "cannot patch safely"
                    )
                    print(
                        f"  [SKIP] {name}: body shared with "
                        f"{shared[off] - 1} other function(s) - {why}"
                    )
                    skipped += 1
                    continue
            dec = run("decompile", "--function", fn, bundle)
            # The decompiler names an anonymous function after its id (`f52476`).
            # That is the normal state for every React Compiler component from
            # 349.5 on: the component is an arrow passed to `memo()`, so the
            # bare identifier never reaches the function table and the name in
            # our table is the *old* one. Accepting the id-derived name keeps a
            # real guard (the id must exist, and must be anonymous) while not
            # rejecting precisely the targets the structural remap resolved.
            # Identity for those is established by the module-path chain that
            # produced the id, not by a name that no longer exists.
            anon = f"function f{fn}("
            if f"function {name}(" not in dec.stdout and anon not in dec.stdout:
                print(f"  [SKIP] {fn} {name}: decompiler did not name it")
                skipped += 1
                continue
            if os.environ.get("PATCH_JS_DEBUG"):
                print(f"  [DBG] {fn} {name} kind={kind} lines={dec.stdout.count(chr(10))}")
            if already_stubbed(dec.stdout):
                continue  # already a no-op
            if kind == "promise":
                dis = run("disasm", "--function", fn, bundle)
                if not CACHE_OPCODE.search(dis.stdout):
                    print(
                        f"  [SKIP] {name}: no inline-cache slot for the "
                        "promise stub (patch-function cannot grow one)"
                    )
                    skipped += 1
                    continue
            # `objfalse` is the one shape that cannot come from the shared
            # table: its operands are read out of this function's own body.
            stub = None
            if kind == "objfalse":
                # Reuse the original body's own object template and registers.
                # `return false` would hand the caller `undefined` and the
                # `.enabled` read would throw, so this has to return a real
                # object - and one with the same shape as the real one.
                dis = run("disasm", "--function", fn, bundle)
                menv = re.search(
                    r"GetParentEnvironment r(\d+), (\d+)", dis.stdout
                )
                mobj = re.search(
                    # `NewObjectWithBuffer` (no `Long`) is the same
                    # instruction with narrower operands, emitted when the
                    # buffer index is small. 349.5 uses it for the orb
                    # gate, so requiring the Long form made this SKIP.
                    r"NewObjectWithBuffer(?:Long)? r(\d+), (\d+), (\d+)",
                    dis.stdout,
                )
                if not menv or not mobj:
                    print(
                        f"  [SKIP] {name}: no object template in the original "
                        "body to build an {enabled:false} stub from"
                    )
                    skipped += 1
                    continue
                stub = os.path.join(td, f"objfalse_{fn}.hasm")
                with open(stub, "w", encoding="utf-8") as f:
                    f.write(
                        OBJECT_FALSE_TEMPLATE.format(
                            # Keep whatever width the original used: the stub
                            # must not be wider than the instruction it
                            # replaces, because patch-function cannot grow the
                            # function's layout.
                            width="Long" if "Long" in mobj.group(0) else "",
                            env=menv.group(1),
                            depth=menv.group(2),
                            obj=mobj.group(1),
                            buf=mobj.group(2),
                            shape=mobj.group(3),
                        )
                    )
            else:
                stub = stubs[kind]
            tmp = os.path.join(td, f"p{fn}.hbc")
            r = run(
                "patch-function",
                "--function",
                fn,
                "--hasm",
                stub,
                "--output",
                tmp,
                bundle,
            )
            if not os.path.exists(tmp):
                print(f"  [FAIL] {name}: {r.stderr.strip()[:110]}")
                fail += 1
                continue
            chk = run("decompile", "--function", fn, tmp)
            if f"function {name}(" not in chk.stdout and anon not in chk.stdout:
                print(f"  [FAIL] {name}: verification failed")
                fail += 1
                continue
            if kind == "promise":
                if "Promise.resolve()" not in chk.stdout:
                    print(f"  [FAIL] {name}: promise stub did not take")
                    fail += 1
                    continue
            elif kind == "false":
                if "return false;" not in chk.stdout:
                    print(f"  [FAIL] {name}: false stub did not take")
                    fail += 1
                    continue
            elif kind == "null":
                if "return null;" not in chk.stdout:
                    print(f"  [FAIL] {name}: null stub did not take")
                    fail += 1
                    continue
            elif kind == "emptyarray":
                if "return [];" not in chk.stdout:
                    print(f"  [FAIL] {name}: empty-array stub did not take")
                    fail += 1
                    continue
            elif kind == "zero":
                if "return 0;" not in chk.stdout:
                    print(f"  [FAIL] {name}: zero stub did not take")
                    fail += 1
                    continue
            elif kind == "objfalse":
                if "{ enabled: false }" not in chk.stdout:
                    print(
                        f"  [FAIL] {name}: {{enabled:false}} stub did not take"
                    )
                    fail += 1
                    continue
            elif not already_stubbed(chk.stdout):
                print(f"  [FAIL] {name}: no-op stub did not take")
                fail += 1
                continue
            shutil.copy2(tmp, bundle)
            done += 1
    # hermes-decomp writes a large sidecar bytecode cache next to the bundle;
    # apktool would pack it into the APK (+50 MB). Drop it.
    for stray in (bundle + ".hdcache", bundle + ".hbc"):
        if os.path.exists(stray):
            os.remove(stray)
            print(f"  [OK] removed tooling sidecar {os.path.basename(stray)}")

    if done:
        ok += 1
        print(
            f"  [OK] Hermes no-op'd at bytecode level: {done} function(s)"
            + (
                f", {skipped} id(s) did not match (left alone)"
                if skipped
                else ""
            )
        )
    else:
        print("  [SKIP] JS targets already no-op'd")


def patch_js_feature_removal(base):
    """Remove UI the user asked to be gone, by editing a body rather than stubbing it.

    `patch_js` replaces a whole body with a constant. That cannot express
    "keep this function, but leave one entry out of the array it builds", which
    is what hiding the Wishlist profile tab needs: the tab is not a function
    that can return false, it is one element of the list `useMemo` hands to the
    segmented control.

    Each edit is stored as a **before/after pair of disassembled bodies** under
    ``patches/hasm/``. HASM operands are bundle-specific - buffer indices,
    object shapes and closure function ids are all per-bundle - so a stale
    after-image would not fail, it would write plausible garbage. The guard is
    therefore an exact match: the function's current body must disassemble to
    the stored *before* image before the *after* image is written. Anything
    else (a new bundle, a different decompiler version) is a SKIP with the
    reason printed, never a guess.

    Re-running is safe: a body equal to the after-image is reported as already
    applied.
    """
    global ok, fail
    tool = find_tool("hermes-decomp")
    bundle = os.path.join(base, "assets/index.android.bundle")
    if tool is None or not os.path.exists(bundle):
        print("  [FAIL] hermes-decomp or the JS bundle is missing")
        fail += 1
        return
    hasm_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hasm")

    def run(*args):
        return subprocess.run(
            [tool, *args], capture_output=True, text=True, timeout=900
        )

    # Both tab-list edits below target the REACT-COMPILER-DISABLED branch.
    #
    # 349.5 ships every component twice:
    #     JmpTrue L554, r1                       ; isReactCompilerEnabled()
    #     CreateClosureLongIndex r1, r5, <B>     ; false -> B   <- the live one
    #     Jmp L561
    #     L554: CreateClosureLongIndex r1, r5, <A>  ; true -> A
    # and the experiment defaults to false, so B is what actually runs. Patching
    # A is dead code that round-trips, verifies and does nothing - which is
    # exactly how the Wishlist tab survived the first attempt.
    EDITS = [
        # YOU_SCREEN tab list, `modules/user_profile/native/YouScreenUserProfileContent.tsx`.
        #
        # 115638 is a closure of 19029 (354.1), the `isReactCompilerEnabled() == false`
        # variant. It builds `items` and pushes Main unconditionally, Board
        # under env[17], Activity under env[18], and **Wishlist with no guard
        # at all** - every account has a wishlist tab, empty or not.
        #
        # The previous revision of this edit flipped the Board guard
        # (`JmpFalseLong L300, r2` -> `r0`), which removed the wrong tab.
        #
        # The wishlist push is `0235 Call2 r2, r3, r1, r2` (r1 = items, r2 =
        # the built tab, r3 = items.push) and is immediately followed by
        # `023a Ret r1`. `Call2` with four register operands and `JmpLong` are
        # both 5 bytes, so replacing it with `JmpLong L586` (586 = 0x23a, the
        # `Ret`) deletes the push with **zero** shift: every L<n> operand and
        # every instruction offset in the body is untouched. The element
        # construction above it still runs and is discarded.
        (
            "115638",
            "You screen profile tabs: build Main/Activity only (no Board, no Wishlist)",
            "profile_tabs",
        ),
        # OTHER PEOPLE'S profiles, `modules/user_profile/native/UserProfileContent.tsx`.
        #
        # 107579 is a closure of 15046 (354.1), the `isReactCompilerEnabled() == false`
        # variant of the same component. Its tab list is built the same way
        # (Main / Board / Activity / Wishlist) but here the Wishlist push
        # *is* guarded:
        #
        #     01f7  LoadFromEnvironment r1, r5, 24   ; the "show wishlist" flag
        #     01fb  JmpFalse L622, r1
        #           ... NewObjectWithBufferLong r3, 19560, 837508 (id: "wishlist")
        #           ... r2.push(r3)
        #     L622: 026e Ret r2
        #
        # `r0` is `LoadConstUndefined` at 001c and is never written again
        # anywhere before the guard (verified by scanning every write to every
        # register in the body), so testing `r0` makes the branch always fire
        # and the Wishlist tab is never appended. Same opcode, same label,
        # same register width - byte-neutral.
        (
            "107579",
            "other users' profile tabs: build Main/Activity only (no Board, no Wishlist)",
            "profile_tabs_other",
        ),
        # modules/messages/native/renderer/MarkupParsers.tsx (fn 8685).
        #
        # The message-markup AST cache is built as
        #     { max: Infinity, maxAge: 15 * MINUTE, updateAgeOnGet: true }
        # `max: Infinity` makes lru-cache's `trim()` early-return, so nothing
        # caps the entry count - and `updateAgeOnGet` refreshes the clock on
        # every read, so frequently-read content never expires at all. These
        # are on the message-render path, so it grows for as long as the app
        # runs. GC pressure of exactly the shape the ANR shows.
        #
        # `max` is slot 0 of a serialized object buffer, so it cannot be
        # swapped as an operand. The clamp goes after the construction instead,
        # overwriting the slot with Discord's own scale (2000, the bound the
        # neighbouring message-preview cache uses). The function has NO jumps
        # at all, so the insertion cannot invalidate an `L<n>` target.
        (
            "8685",
            "markup cache: bound the entry count (max: Infinity)",
            "markup_cache_bound",
        ),
        (
            "64806",
            "useProfileTabIndices: wishlistTabIndex is always -1",
            "wishlist_index",
        ),
        # `floating_nav_quests` is retired: 354.1's FloatingNav no longer
        # builds a Quests button at all (no `getIsEligibleForQuests` or
        # `QuestsIcon` reference in its body), so there is nothing to remove.
        (
            "17970",
            "Settings: CollectiblesShop route gets the (false) usePredicate",
            "shop_setting_predicate",
        ),
        # The two `spotify_recent_activity` edits are retired for 354.1: the
        # filter (module `modules/user_profile/hooks/useUserProfileActivity.tsx`,
        # closure 129392) now handles listened sessions through a different
        # shape, and the old byte-neutral condition no longer exists. The
        # network blocklist still stops Spotify calls, and `SpotifyTrack` is
        # stubbed, so only a friend's already-cached activity row can remain.
        # Re-derive the filter against 129392 before re-enabling these edits.
        # --- Billing / Nitro rows. Their backends are already dead in this
        # build (the storefront, collectibles and subscription endpoints are
        # 204'd), so each row navigates to a screen that can no longer do
        # anything.
        #
        # These three have NO `usePredicate`, so unlike the Quest row they
        # cannot be hidden by stubbing a closure - the route object itself gets
        # one, the same edit that removed the CollectiblesShop row.
        #
        # ONLY these three. The same edit applied to all nine premium/billing
        # defs at once corrupted the JS heap and the app died in ~130 s with a
        # garbage fault in libhermesvm, plus "something isn't a function" on the
        # Settings screen. The failure is in one of 14524 / 14527 / 15418, which
        # are therefore NOT edited here. Each of these three was then applied
        # and run on the device *individually* before being added:
        #   14522 alone 200 s clean, 14529 with it clean, 14518 with both clean.
        # The remaining rows those defs own are either already covered by the
        # predicate stubs below or only appear with an active subscription.
        # 354.1 factory ids: PremiumSetting.tsx, PremiumManagePlanSetting.tsx,
        # and PremiumGuildBoostingSetting.tsx.
        ("17026", "Settings: Nitro / Manage Nitro row", "premium_setting"),
        ("17032", "Settings: Manage Plan row", "manage_plan"),
        ("17034", "Settings: Manage Subscriptions row", "manage_subs"),
        ("17039", "Settings: Server Boost row", "guild_boosting"),
        # The guild-tag upsell is returned as `{creatableGuilds,
        # isUpsellVisible}`. `r0` is the constant zero in this body and is not
        # written again, so putting slot 1 from it keeps the guild list while
        # forcing `isUpsellVisible` false. Byte-neutral.
        ("64286", "Profile: guild-tag upsell hidden", "guild_tag_upsell"),
        # --- Billing / Nitro / boost / gifting settings rows. Their backends
        # are already dead in this build (the storefront, collectibles and
        # subscription endpoints are 204'd), so every one of these rows
        # navigates to a screen that can no longer do anything.
        #
        # Same lever as the CollectiblesShop row above: `createRoute` /
        # `createPressable` copy the config straight through, `SettingHookHarness`
        # hides any entry where `false === cfg.usePredicate()`, and four of these
        # defs already carry a `usePredicate` while five carry none. Appending
        # `DefineOwnById ... "usePredicate"` handles both cases with one shape -
        # it overwrites the existing slot where there is one (the ordinary
        # compiled form of a property assignment) and adds the slot where there
        # is not. All nine point at 62294, the Quest predicate now stubbed to
        # `return false`, so there is exactly one "always false" closure in the
        # bundle rather than nine.

    ]

    def _norm(lines):
        """Instruction text only, offsets and labels stripped.

        The offset column is recomputed by the assembler, and labels move with
        the instructions they name, so neither carries information about
        whether a body is the one we meant to write.
        """
        out = []
        for l in lines:
            if not l.strip() or re.match(r"^L\d+:$", l.strip()):
                continue
            out.append(" ".join(l.split()[1:]).strip())
        return out


    def bad_jumps(hasm_text):
        """Jump targets in `hasm_text` that are not instruction boundaries.

        THIS IS THE CHECK THAT MATTERS, and it is not a style rule.

        Label names in HASM are `L<decimal offset>`, and the assembler resolves
        a jump operand *numerically*: `JmpFalse L855, r10` becomes "jump to byte
        855", not "jump to wherever the line `L855:` happens to sit". While a
        body keeps its length that is harmless, because every label's name still
        equals its offset. The moment an edit changes the length, every label
        after the change point is stale - so a jump can land in the middle of a
        4-byte instruction.

        That is not a theoretical failure. `floating_nav_quests` originally
        dropped one byte by turning `JmpFalse` into a shorter `Jmp`, which moved
        `NewArray r13, 4` from 855 to 854; the jump still said 855, landed one
        byte into the instruction, and the app segfaulted in libhermesvm within
        20 seconds, every launch. `bin-diff` did not flag it and re-assembling
        the emitted body reproduced it, so nothing else in the pipeline could
        have caught it.

        The fix for both affected edits was to make them byte-neutral - change
        an operand, not the instruction count - which keeps every label honest.
        This check enforces the consequence rather than the rule, so a future
        edit that shifts bytes is allowed as long as no jump actually breaks.
        """
        starts = set()
        insns = []
        for l in hasm_text.split("\n"):
            m = re.match(r"^\s*([0-9a-f]+)\s+(\S.*)$", l)
            if m:
                starts.add(int(m.group(1), 16))
                insns.append((int(m.group(1), 16), m.group(2).strip()))
        bad = []
        for off, body in insns:
            m = re.match(r"Jmp\w*\s+L(\d+)", body)
            if m and int(m.group(1)) not in starts:
                bad.append(f"0x{off:x} {body}")
        return bad

    def emit(fn, src):
        r = run("emit-hasm", "--function", fn, src)
        return _norm(r.stdout.split("\n")), r.stdout

    def read(name):
        with open(os.path.join(hasm_dir, name), encoding="utf-8") as f:
            return _norm(f.read().split("\n")), f

    done = 0
    for fn, what, stem in EDITS:
        apath = os.path.join(hasm_dir, stem + ".after.hasm")
        with open(apath, encoding="utf-8") as f:
            raw_after = f.read().split("\n")
        before, after = read(stem + ".before.hasm")[0], _norm(raw_after)
        cur, _ = emit(fn, bundle)
        # "Already applied" must allow for the assembler's byte-preserving
        # filler, because the body on disk carries it and the stored
        # after-image does not. Comparing them raw made every re-run of an
        # applied edit fall through to the drift check and report a false SKIP.
        if [i for i in cur if i != "AsyncBreakCheck"] == after:
            print(f"  [OK] {what} (already applied)")
            continue
        if cur != before:
            print(
                f"  [SKIP] {what}: fn {fn} does not match the stored "
                f"before-image ({len(cur)} instructions vs {len(before)}) - "
                "the bundle drifted; re-derive the edit, do not force it"
            )
            continue
        tmp = bundle + ".edit.hbc"
        r = run(
            "patch-function",
            "--function",
            fn,
            "--hasm",
            apath,
            "--output",
            tmp,
            bundle,
        )
        if not os.path.exists(tmp):
            print(f"  [FAIL] {what}: {r.stderr.strip()[:110]}")
            fail += 1
            continue
        # The after-image is itself the assertion: re-emitting from the
        # patched bundle and comparing proves the assembler produced the body
        # that was written and nothing else.
        #
        # `AsyncBreakCheck` is the one permitted difference. When an edit makes
        # a body shorter the assembler must still emit exactly as many bytes,
        # or every later function's offset moves, so it fills the gap with
        # `AsyncBreakCheck` - a load of the "should we interrupt" flag and a
        # branch, emitted routinely by hermesc at statement boundaries. It is
        # filler, not code. Everything else must match line for line, and the
        # filler is required to *be* AsyncBreakCheck rather than absorbed by a
        # loose comparison.
        got, got_raw = emit(fn, tmp)
        got_unpadded = [i for i in got if i != "AsyncBreakCheck"]
        pad = len(got) - len(got_unpadded)
        if got_unpadded != after:
            diff = next(
                (
                    f"line {i}: {a!r} != {b!r}"
                    for i, (a, b) in enumerate(zip(got_unpadded, after))
                    if a != b
                ),
                f"length {len(got_unpadded)} vs {len(after)}",
            )
            print(f"  [FAIL] {what}: round-trip mismatch ({diff})")
            os.remove(tmp)
            fail += 1
            continue
        # The assembled body must not contain a jump into the middle of an
        # instruction. See bad_jumps() - this is the check that would have
        # caught the floating_nav_quests crash, and it is asserted against the
        # body that is about to be written, not against the source.
        broken = bad_jumps(got_raw)
        if broken:
            print(
                f"  [FAIL] {what}: {len(broken)} jump(s) land mid-instruction "
                f"in the assembled body ({'; '.join(broken[:3])}). The edit "
                "shifted byte offsets and the L<n> operands now name stale "
                "positions - make the edit byte-neutral (change an operand, "
                "not the instruction count) or re-target the jumps."
            )
            os.remove(tmp)
            fail += 1
            continue
        shutil.copy2(tmp, bundle)
        os.remove(tmp)
        done += 1
        print(f"  [OK] {what}" + (f" (+{pad} filler)" if pad else ""))

    for stray in (bundle + ".hdcache", bundle + ".edit.hbc"):
        if os.path.exists(stray):
            os.remove(stray)

    if done:
        ok += 1


# --- Extra UI/behaviour patches, ported from the Reseam patch set (347.12)
# and the Valenced plugin collection. Every target is located by
# (name, string literals, parameter count) rather than by a hardcoded id, so a
# new bundle does not silently point a stub at the wrong function.
DISCORD_HIDE_EXTRA_PROMOTIONS = opt_bool("DISCORD_HIDE_EXTRA_PROMOTIONS", True)
DISCORD_QUIET_SUPER_REACTIONS = opt_bool("DISCORD_QUIET_SUPER_REACTIONS", True)
DISCORD_HIDE_LIST_DIVIDERS = opt_bool("DISCORD_HIDE_LIST_DIVIDERS", False)
DISCORD_HIDE_TYPING_INDICATOR = opt_bool("DISCORD_HIDE_TYPING_INDICATOR", False)
DISCORD_DISABLE_CALL_IDLE = opt_bool("DISCORD_DISABLE_CALL_IDLE", False)
DISCORD_STATIC_STICKERS = opt_bool("DISCORD_STATIC_STICKERS", False)
DISCORD_BETTER_STREAM_QUALITY = opt_bool("DISCORD_BETTER_STREAM_QUALITY", False)
DISCORD_BETTER_VIDEO_QUALITY = opt_bool("DISCORD_BETTER_VIDEO_QUALITY", False)
DISCORD_SKIP_MASKED_LINK_WARNINGS = opt_bool(
    "DISCORD_SKIP_MASKED_LINK_WARNINGS", False
)
DISCORD_SHOW_BLOCKED_MESSAGES = opt_bool("DISCORD_SHOW_BLOCKED_MESSAGES", False)


def patch_js_extra(base):
    """Apply the optional UI/behaviour stubs to the Hermes bundle.

    Each entry is a whole-function stub. The matcher is the same three-part
    identity Reseam uses: function-table name, distinctive string literals in
    the body, and parameter count. That is enough to survive a version bump
    without a hardcoded id, and it refuses ambiguous matches instead of
    guessing.

    Nothing here is a security boundary: the network blocklist and the core
    telemetry stubs above are. These are interface options whose value is
    letting the user remove what Discord keeps re-adding.
    """
    global ok, fail
    tool = find_tool("hermes-decomp")
    bundle = os.path.join(base, "assets/index.android.bundle")
    if tool is None or not os.path.exists(bundle):
        print("  [FAIL] hermes-decomp or the JS bundle is missing")
        fail += 1
        return

    def run(*args):
        global fail
        result = subprocess.run(
            [tool, *args], capture_output=True, text=True, timeout=900
        )
        if result.returncode != 0:
            print(
                "  [FAIL] hermes-decomp "
                f"{' '.join(args[:2])} failed: {result.stderr.strip()[:100]}"
            )
            fail += 1
        return result

    TRUE_HASM = "0000  LoadConstTrue r0\n0001  Ret r0\n"
    NOOP_HASM = "0000  LoadConstUndefined r0\n0001  Ret r0\n"
    FALSE_HASM = "0000  LoadConstFalse r0\n0001  Ret r0\n"
    NULL_HASM = "0000  LoadConstNull r0\n0001  Ret r0\n"

    # (option, function name, strings, param count, stub kind, label)
    work = []
    if DISCORD_HIDE_EXTRA_PROMOTIONS:
        work += [
            (
                "PremiumFeatureUpsellPill",
                ["native.PremiumFeatureUpsell", "featureName", "ShinyButton"],
                1,
                "null",
                "Hide small Nitro upsell pills",
            ),
            (
                "ContactSyncUpsellCTA",
                ["FormCTA", "location", "container"],
                1,
                "null",
                "Hide the contact sync prompt",
            ),
            (
                "GroupDMNitroUpsellBanner",
                ["GroupDMNitroAcquisitionStrategy", "onFloatingListInsetChange"],
                1,
                "null",
                "Hide the group-chat Nitro banner",
            ),
            (
                "PremiumExpressionPickerSearchUpsell",
                ["ctaText", "PressableOpacity", "upsell"],
                1,
                "null",
                "Hide emoji/sticker search promotions",
            ),
            (
                "ActivityCardsItem",
                ["FlashList", "cards", "animatedStyles"],
                1,
                "null",
                "Hide activity cards in the Appearance preview",
            ),
            (
                "showNitroLockedToast",
                ["getNitroLockedMessage", "presentError"],
                1,
                "noop",
                "Hide the Nitro-locked toast",
            ),
            (
                "GuildProfileCreateTagUpsell",
                ["useGuildTagCreationUpsell", "GuildProfileCreateTagUpsell"],
                1,
                "null",
                "Hide the guild-profile tag upsell",
            ),
            (
                "UserProfilePremiumTryItOutSection",
                ["UserProfilePremiumTryItOutSection", "onPreviewPremium"],
                1,
                "null",
                "Hide the premium try-it-out section",
            ),
            (
                "UserProfilePremiumTryItOutUpsell",
                ["UserProfilePremiumTryItOutUpsell", "isVisible"],
                1,
                "null",
                "Hide the premium try-it-out upsell",
            ),
            (
                "PremiumTryItOutModal",
                ["PremiumUpsellTypes", "PremiumTryItOutModal"],
                1,
                "null",
                "Hide the premium try-it-out modal",
            ),
            (
                "openPremiumTryItOutModal",
                ["TrackUserProfileEditActions", "openPremiumTryItOutModal"],
                1,
                "noop",
                "Do not open the premium try-it-out modal",
            ),
            (
                "ReferralProgramEntryPointActionSheet",
                ["ReferralProgramEntryPointActionSheet", "ContentDismissActionType"],
                1,
                "null",
                "Hide the referral-program action sheet",
            ),
            (
                "OrbsArcadeQuestHomeSection",
                ["ORBS_ARCADE_GAME_TILE_MAX_WIDTH", "OrbsArcadeQuestHomeSection"],
                1,
                "null",
                "Hide the Orbs Arcade quest-home section",
            ),
            (
                "HexagonCampaignQuestHomeHeader",
                ["HEXAGON_CAMPAIGN_AD_CONTENT_ID", "HexagonCampaignQuestHomeHeader"],
                0,
                "null",
                "Hide the sponsored Hexagon campaign header",
            ),
        ]
    if DISCORD_QUIET_SUPER_REACTIONS:
        work.append(
            (
                "SuperReactionLocalImageAnimation",
                ["useSuperReactionAnimationSourceFromLocalImage", "animationSource"],
                1,
                "null",
                "Stop Super Reaction decorations from looping",
            )
        )
    if DISCORD_STATIC_STICKERS:
        work.append(
            (
                "shouldAnimateSticker",
                ["ANIMATE_ON_INTERACTION", "NEVER_ANIMATE"],
                2,
                "false",
                "Use static sticker previews",
            )
        )
    if DISCORD_BETTER_STREAM_QUALITY:
        work.append(
            (
                "canStreamQuality",
                ["STREAM_HIGH_QUALITY", "STREAM_MID_QUALITY", "canUserUse"],
                2,
                "true",
                "Allow the higher stream quality tiers",
            )
        )
    if DISCORD_BETTER_VIDEO_QUALITY:
        work.append(
            (
                "canUseHighVideoUploadQuality",
                ["INCREASED_VIDEO_UPLOAD_QUALITY", "canUserUse"],
                1,
                "true",
                "Allow high-quality video uploads",
            )
        )
    if DISCORD_DISABLE_CALL_IDLE:
        work.append(
            (
                "disconnect",
                [
                    "countVoiceStatesForChannel",
                    "sendBotMessage",
                    "selectVoiceChannel",
                ],
                0,
                "noop",
                "Stop empty DM calls from timing out",
            )
        )
    if DISCORD_HIDE_TYPING_INDICATOR:
        work += [
            (
                "handleTypingStartLocal",
                ["FAKE_PLACEHOLDER_PRIVATE_CHANNEL_ID", "prevSend"],
                1,
                "false",
                "Do not record your typing locally",
            ),
            (
                "",
                ["TYPING", "HTTP", "timeout"],
                0,
                "noop",
                "Do not send outgoing typing events",
            ),
        ]
    if DISCORD_SHOW_BLOCKED_MESSAGES:
        work.append(
            (
                "isBlockedForMessage",
                ["interactionMetadata", "interaction_metadata", "BLOCKED"],
                1,
                "false",
                "Show messages from blocked users",
            )
        )
    if DISCORD_SKIP_MASKED_LINK_WARNINGS:
        work.append(
            (
                "isLinkTrusted",
                ["getRecipientId", "isFriend", "DM"],
                2,
                "true",
                "Skip the masked-link destination warning",
            )
        )
    if DISCORD_HIDE_LIST_DIVIDERS:
        work.append(
            (
                "TableRowDivider",
                [],
                1,
                "null",
                "Hide the lines between settings/list rows",
            )
        )
    if not work:
        print("  [SKIP] no extra JS patches enabled")
        return

    # Function table, and body sharing map. Identical bodies are deduplicated
    # by Hermes, so a stub is only written when the body is unique.
    by_name, offsets, shared = {}, {}, {}
    for line in run("dump", "--kind", "functions", bundle).stdout.split("\n"):
        parts = line.split()
        if len(parts) < 3:
            continue
        fid, off = parts[0], parts[-2]
        if not fid.isdigit() or not re.fullmatch(r"[0-9a-f]{4,8}", off):
            continue
        name = " ".join(parts[1:-2])
        by_name.setdefault(name, []).append(fid)
        offsets[fid] = off
        shared[off] = shared.get(off, 0) + 1

    sig_re = re.compile(r"function\s+[^\s(]*\(([^)]*)\)")
    arg_re = re.compile(r"arg\d+")

    def param_count(src):
        m = sig_re.search(src)
        if not m or not m.group(1).strip():
            return 0
        return len(arg_re.findall(m.group(1)))

    def is_stub(src):
        if not src.strip() or "{" not in src or "}" not in src:
            return False
        body = src.split("{", 1)[-1].rsplit("}", 1)[0]
        allowed = {
            "debugger;",
            "return null;",
            "return false;",
            "return true;",
            "return 0;",
        }
        return all(
            not line.strip() or line.strip() in allowed
            for line in body.split("\n")
        )

    def candidates(name, strings):
        ids = list(by_name.get(name, []))
        if not ids and strings:
            sets = [
                set(re.findall(r"Function (\d+) \(", run("xref", "--query", s, bundle).stdout))
                for s in strings
            ]
            ids = sorted(set.intersection(*sets)) if sets else []
        return list(dict.fromkeys(ids))

    def resolve(name, strings, params):
        ids = candidates(name, strings)
        by_params = []
        for fid in ids:
            dec = run("decompile", "--function", fid, bundle).stdout
            if dec and param_count(dec) == params:
                by_params.append((fid, dec))
        if len(by_params) == 1:
            # A unique name plus the expected arity is identity enough. The
            # string check is a disambiguator, not a requirement: some
            # components reference their marker strings through closures, so
            # they never appear in the function's own decompilation.
            return by_params[0][0]
        hits = [fid for fid, dec in by_params if all(s in dec for s in strings)]
        if len(hits) == 1:
            return hits[0]
        if len(hits) > 1 or len(by_params) > 1:
            return None
        return ""

    stubs = {}
    with tempfile.TemporaryDirectory() as td:
        for kind, text in (
            ("true", TRUE_HASM),
            ("false", FALSE_HASM),
            ("null", NULL_HASM),
            ("noop", NOOP_HASM),
        ):
            p = os.path.join(td, kind + ".hasm")
            with open(p, "w", encoding="utf-8") as f:
                f.write(text)
            stubs[kind] = p

        applied = 0
        for name, strings, params, kind, label in work:
            fid = resolve(name, strings, params)
            if fid == "":
                print(f"  [FAIL] {label}: matcher not found")
                fail += 1
                continue
            if fid is None:
                print(f"  [FAIL] {label}: matcher is ambiguous")
                fail += 1
                continue
            if is_stub(run("decompile", "--function", fid, bundle).stdout):
                print(f"  [OK] {label} (already applied)")
                continue
            off = offsets.get(fid)
            if off and shared.get(off, 1) > 1:
                print(
                    f"  [FAIL] {label}: body shared with "
                    f"{shared[off] - 1} other function(s)"
                )
                fail += 1
                continue
            tmp = os.path.join(td, "p" + fid + ".hbc")
            r = run(
                "patch-function",
                "--function",
                fid,
                "--hasm",
                stubs[kind],
                "--output",
                tmp,
                bundle,
            )
            if not os.path.exists(tmp):
                print(f"  [FAIL] {label}: {r.stderr.strip()[:110]}")
                fail += 1
                continue
            chk = run("decompile", "--function", fid, tmp).stdout
            if kind == "noop":
                # `LoadConstUndefined; Ret` decompiles to an empty body (or a
                # bare `debugger;`). Either is the intended no-op shape.
                body = chk.split("{", 1)[-1].rsplit("}", 1)[0]
                took = all(
                    line.strip() in ("", "debugger;")
                    for line in body.split("\n")
                )
            else:
                took = {
                    "null": "return null;",
                    "false": "return false;",
                    "true": "return true;",
                }[kind] in chk
            if not took:
                print(f"  [FAIL] {label}: stub did not take")
                fail += 1
                os.remove(tmp)
                continue
            shutil.copy2(tmp, bundle)
            applied += 1
            print(f"  [OK] {label}")
        if applied:
            ok += 1
    for stray in (bundle + ".hdcache", bundle + ".hbc"):
        if os.path.exists(stray):
            os.remove(stray)


def patch_interceptor_registration(base):
    """Move Discord's RN interceptors from the NETWORK list to the APPLICATION list.

    `requestStatsInterceptor` (blocklist) answers a matched request
    with a synthetic HTTP 204 without calling `chain.proceed()`. OkHttp permits
    that for application interceptors only: for a network interceptor that does
    not call proceed() exactly once it throws

        IllegalStateException: network interceptor ... must call proceed()
        exactly once

    on the OkHttp Dispatcher thread, which is a FATAL EXCEPTION and kills the
    process. React Networking adds the interceptor to
    `OkHttpClient$Builder->d` (networkInterceptors); `c` is the application list
    (the Builder constructor allocates `c` first, and okhttp3's source declares
    `interceptors` before `networkInterceptors`). Both React Networking
    registration sites are moved.
    """
    global ok, fail
    # The blocklist interceptor is `ne/a` -> `ng/b.b(...)`. 354.1's RN client
    # registers it on OkHttp's *network* list (`k50/c0->d`), where a synthetic
    # 204 without proceed() is illegal. Move every registration of that
    # interceptor to the application list (`k50/c0->c`). The class names are
    # R8 output; find the file that both constructs `ne/a` and writes `->d`.
    needle = "Lne/a;"
    patched = 0
    for d in smali_dirs(base):
        root = os.path.join(base, d)
        if not os.path.isdir(root):
            continue
        for dirpath, _, files in os.walk(root):
            for name in files:
                if not name.endswith(".smali"):
                    continue
                p = os.path.join(dirpath, name)
                try:
                    s = rd(p)
                except (OSError, UnicodeDecodeError):
                    continue
                if needle not in s or "Lk50/c0;->d:Ljava/util/ArrayList;" not in s:
                    continue
                s2 = s.replace(
                    "Lk50/c0;->d:Ljava/util/ArrayList;",
                    "Lk50/c0;->c:Ljava/util/ArrayList;",
                )
                if s2 != s:
                    wr(p, s2)
                    patched += 1
                    print(f"  [OK] interceptor registration moved to the application list ({name})")
    if patched:
        ok += 1
    else:
        print(
            "  [SKIP] no network-list registration of the stats interceptor "
            "found (already on the application list?)"
        )


def patch_js_body_edits(base):
    """Rewrite the *bodies* of bundle functions, rather than stubbing them.

    `patch_js` can only replace a whole function with one of three stub shapes.
    Some wins need a surgical edit instead — remove a call, keep everything
    around it — which is what this does: emit HASM, edit the text, reassemble.
    Hermes branch targets in HASM are labels (`Jmp L346`), so instruction counts
    may change freely; the assembler recomputes offsets. The one hard rule is
    the usual one — the body must fit the function header's existing frame and
    inline-cache region, since `patch-function` rewrites the body only.

    Currently one edit: the gateway IDENTIFY byte-size metrics.

    They are dead. `xref` on either name finds only two references — the
    constructor's `DefineOwnById` and this function's `PutByIdStrict` — and no
    reader. Their only consumers were the gateway byte-size analytics the suite
    already stubs. Computing them cost a `JSON.stringify` of the entire IDENTIFY
    payload followed by a full `pako.deflate` of the result, and then the code
    sends the *object* — both the string and the deflated buffer were thrown
    away. That runs on every gateway connect and reconnect, i.e. every network
    transition and every background/foreground cycle, at the moment the socket
    is coming up.

    The edit keeps the two property writes (the analytics object may be
    serialised wholesale, so the shape is preserved) and reports 0.

    The target is located by string xref rather than by function id, so it
    survives a version bump.
    """
    global ok, fail
    tool = find_tool("hermes-decomp")
    bundle = os.path.join(base, "assets/index.android.bundle")
    if tool is None or not os.path.exists(bundle):
        print("  [SKIP] bundle body edits (no tool or bundle)")
        return

    # `run` is a closure inside patch_js, so define our own here.
    def run(*args):
        return subprocess.run(
            [tool, *args], capture_output=True, text=True, timeout=900
        )

    xref = run("xref", "--query", "identifyCompressedByteSize", bundle).stdout
    candidates = re.findall(r"Function (\d+) \(", xref)
    target = None
    for cand in candidates:
        dis = run("disasm", "--function", cand, bundle).stdout
        # The *writer*, not the class constructor that merely defines the
        # field. Both mention the name, so require the store to be on the
        # same line — the constructor's is a DefineOwnById.
        if re.search(
            r"PutByIdStrict.*\"identifyCompressedByteSize\"", dis
        ):
            target = cand
            break
    if target is None:
        print("  [SKIP] IDENTIFY byte-size edit (target not found)")
        return

    with tempfile.TemporaryDirectory() as td:
        hasm = os.path.join(td, "identify.hasm")
        run("emit-hasm", "--function", target, "-o", hasm, bundle)
        if not os.path.exists(hasm):
            print("  [FAIL] IDENTIFY byte-size edit: emit-hasm produced nothing")
            fail += 1
            return
        lines = rd(hasm).split("\n")
        try:
            stringify = next(
                i for i, l in enumerate(lines) if '"stringify"' in l
            )
            json_line = max(
                i for i, l in enumerate(lines[:stringify]) if '"JSON"' in l
            )
            end = next(
                i
                for i, l in enumerate(lines)
                if i > stringify and "identifyCompressedByteSize" in l
            )
        except ValueError:
            print("  [FAIL] IDENTIFY byte-size edit: block not found")
            fail += 1
            return

        keep = lines[end].split("PutByIdStrict", 1)[0].rstrip()
        indent = keep[: len(keep) - len(keep.lstrip())] or "    "
        replacement = [
            f"{indent}LoadFromEnvironment r12, r8, 0",
            f"{indent}LoadConstUInt8 r11, 0",
            f"{indent}"
            f'PutByIdStrict r12, r11, 1, "identifyUncompressedByteSize"',
            f"{indent}LoadFromEnvironment r12, r8, 0",
            f"{indent}LoadConstUInt8 r11, 0",
            f"{indent}"
            f'PutByIdStrict r12, r11, 2, "identifyCompressedByteSize"',
        ]
        lines[json_line : end + 1] = replacement
        # The assembler rejects `#` comments.
        out = "\n".join(
            l for l in lines if not l.lstrip().startswith("#")
        )
        wr(hasm, out)

        tmp = os.path.join(td, "edited.bundle")
        r = run(
            "patch-function", bundle, "--function", target,
            "--hasm", hasm, "-o", tmp,
        )
        if not os.path.exists(tmp):
            print(
                f"  [FAIL] IDENTIFY byte-size edit: "
                f"{r.stderr.strip().splitlines()[-1][:110] if r.stderr.strip() else 'assemble failed'}"
            )
            fail += 1
            return

        chk = run("decompile", "--function", target, tmp).stdout
        if (
            "stringify" in chk
            or "deflate" in chk
            or "identifyCompressedByteSize = 0" not in chk
        ):
            print("  [FAIL] IDENTIFY byte-size edit: verification failed")
            fail += 1
            return
        shutil.copy2(tmp, bundle)
        ok += 1
        print(
            f"  [OK] IDENTIFY byte-size edit: JSON.stringify + pako.deflate "
            f"removed from the gateway connect path (fn {target})"
        )


def patch_foreground_service_type(base):
    """Keep a call's foreground service on a microphone type when backgrounded.

    `ForegroundServiceUtilsKt.startForegroundCompat` promotes the service by
    walking a *chain* of foreground-service types, trying each until one is
    accepted:

        for (int type : buildServiceTypeChain(notificationType, context)) {
            try { service.startForeground(id, notification, type); return; }
            catch (SecurityException e) { /* try the next one */ }
        }

    `buildServiceTypeChain` only offers the microphone type when

        isTelecomBarAvailable() || AppLifecycle.isForegrounded()

    holds — and `isTelecomBarAvailable()` is literally

        public static final boolean isTelecomBarAvailable() { return isMetaQuest(); }

    i.e. "is this a Meta Quest". On a phone that is always false, so the
    condition collapses to "the app is currently foregrounded". The moment the
    user backgrounds the app mid-call (or the screen locks), the microphone and
    camera types drop out of the chain, and the list unconditionally ends with

        add(FOREGROUND_SERVICE_TYPE_DATA_SYNC)   // 1

    so the running call service is re-promoted as **dataSync, not microphone**.
    Android only grants background microphone access to a service holding the
    microphone type, so audio capture is cut while the service — and therefore
    the ongoing-call notification — carries on running. That is a direct
    match for "the call drops but the notification still says connected".

    The fix forces the eligibility flag true, so the microphone type is offered
    whenever the permissions are actually held. It is fail-safe because of the
    chain's own fallback: if the system refuses, the loop moves on and lands on
    dataSync exactly as it does today. The catch is widened from
    SecurityException to Exception so that a refusal arriving as
    ForegroundServiceStartNotAllowedException (an IllegalStateException, and so
    not caught today) falls through instead of escaping.

    Confirm on-device with `adb logcat -s ForegroundService`, which prints
    "Attempting to promote service with type: ..." for each attempt.
    """
    global ok, fail
    print("=== FOREGROUND SERVICE TYPE (call survives backgrounding) ===")
    # 354.1 folded `ForegroundServiceUtilsKt` into androidx core's
    # `ServiceCompat`-shaped helper. The chain is still built in
    # `B(...)`: it reads an eligibility flag into v16, then adds microphone
    # (0x80) and camera (0x40) to the candidate list only when that flag is
    # true. Forcing v16 true after its computation keeps the same fix.
    path = sfile(base, "androidx/core/view/i1.smali")
    if not os.path.exists(path):
        path = find_smali_with(
            base,
            "Attempting to promote service with type: ",
            "foreground service promotion",
            unique=True,
        )
        if path is None:
            return
    s = rd(path)
    marker = (
        ".method public static final B("
        "Lcom/discord/foreground_service/ForegroundService;"
        "Lcom/discord/foreground_service/ForegroundService;"
        "Landroid/app/Notification;)V"
    )
    if marker not in s:
        print("  [SKIP] foreground promotion method not found (shape changed?)")
        return
    a = s.index(marker)
    e = s.index(".end method", a) + len(".end method")
    body = s[a:e]
    if "Patch: treat the app as eligible" in body:
        print("  [SKIP] FGS eligibility already widened")
        return
    anchor = "    :goto_2\n"
    if anchor not in body:
        print("  [FAIL] FGS eligibility anchor not found")
        fail += 1
        return
    ins = body.index(anchor) + len(anchor)
    body = (
        body[:ins]
        + "    # Patch: treat the app as eligible so the microphone/camera\n"
        + "    # types stay in the chain while backgrounded. The chain's own\n"
        + "    # fallback still lands on dataSync if the system refuses.\n"
        + "    const/16 v16, 0x1\n\n"
        + body[ins:]
    )
    # Any refusal - including ForegroundServiceStartNotAllowedException, an
    # IllegalStateException - falls through to the next candidate. Only the
    # catch directive is widened; the method's own SecurityException throws
    # are not part of this change.
    ncatch = body.count(".catch Ljava/lang/SecurityException;")
    body = body.replace(
        ".catch Ljava/lang/SecurityException;", ".catch Ljava/lang/Exception;"
    )
    wr(path, s[:a] + body + s[e:])
    ok += 1
    print(
        "  [OK] FGS eligibility forced true; "
        f"{ncatch} refusal catch(es) widened to Exception"
    )
    if ncatch == 0:
        print(
            "  [WARN] no SecurityException catch blocks were found; "
            "the fallback widening did not apply"
        )


DISCORD_EGL_NONBLOCKING = opt_bool("DISCORD_EGL_NONBLOCKING", True)


def patch_incoming_call_nonblocking(base):
    """Take the network wait off the UI thread when a call arrives.

    See DISCORD_INCOMING_CALL_NO_BLOCK for the why. `fetchImage` is a suspend
    function whose three callers all sit inside `IncomingCallActivity`'s
    coroutines and simply pass its value up to the coroutine that does
    `ImageView.setImageBitmap(check-cast Bitmap, result)`.

    Returning null without suspending is a valid completion for a suspend
    function: the caller compares the result against `COROUTINE_SUSPENDED` and
    otherwise treats it as the value. null casts to Bitmap and
    `setImageBitmap(null)` clears the view, so nothing dereferences it.
    """
    global ok, fail
    rel = "com/discord/notifications/renderer/IncomingCallActivity.smali"
    p = sfile(base, rel)
    if not os.path.exists(p):
        print(f"  [FAIL] {rel} missing")
        fail += 1
        return
    s = rd(p)
    # 354.1 renamed the suspend helper to `r` and changed its first parameter
    # from a String to the activity. The callers still take its result as a
    # Bitmap; returning null without suspending is the same completion.
    marker = (
        ".method public static final r("
        "Lcom/discord/notifications/renderer/IncomingCallActivity;"
        "Ljava/lang/String;Lb10/j;)Ljava/lang/Object;"
    )
    if marker not in s:
        marker = (
            ".method private final fetchImage("
            "Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
        )
    if marker not in s:
        print("  [SKIP] incoming-call image fetch not found (shape changed?)")
        return
    a = s.index(marker)
    e = s.index(".end method", a) + len(".end method")
    if "Patch: no network wait" in s[a:e]:
        print("  [OK] incoming-call image fetch already non-blocking")
        return
    wr(p, s[:a] + f"""{marker}
    .locals 1

    # Patch: no network wait. Returning null without suspending is a valid
    # completion; the caller casts it to Bitmap (null casts fine) and clears
    # the ImageView with it. Removes the Fresco init + network round-trip that
    # `runBlocking` on the main thread was waiting for.
    const/4 v0, 0x0

    return-object v0
.end method""" + s[e:])
    ok += 1
    print(
        "  [OK] incoming call no longer blocks the UI thread on an image fetch"
    )


def patch_video_egl_main_thread_block(base):
    """Remove the UI-thread block in `EglRenderer.init()` without leaking contexts.

    The ANR (real, traced through `dumpsys dropbox`):

        TextureViewEglRenderer.initialize <- VideoStreamTextureView.init
          <- attachToStream <- startRenderingStream <- applyStreamId
          <- VideoStreamViewManager.onAfterUpdateTransaction   (UI thread)
        Subject: executing service .foreground_service.ForegroundService, waited 20001ms

    `init()` used `ThreadUtils.invokeAtFrontUninterruptibly`, which posts to the
    render thread and **blocks the caller** until EGL context creation finishes.
    One parking of the UI thread per video tile.

    THE BLOCK IS NOT ARBITRARY - IT MAINTAINS AN ORDERING INVARIANT, and the
    first attempt at this patch broke that invariant and crashed the app:

        release() jumps the queue:
            renderThreadHandler.postAtFrontOfQueue(cleanup)   // frees eglBase
            renderThreadHandler.post(quitLooper)              // BACK of queue

        and cleanup is null-guarded:
            if (eglBase != null) { eglBase.detachCurrent(); eglBase.release(); }
            eglBase = null;

    While `init()` blocks, `eglBase` is always assigned before any `release()`
    can be scheduled. With a plain `post` it is not: detach a tile before the
    render thread reaches the create message and the queue becomes

        [ cleanup(front) ] [ create ] [ quit ]

    so cleanup finds `eglBase == null`, skips the free, and then **create runs
    and builds an EGL context nothing will ever release**. One leaked context
    per tile torn down in that window - 242 of them, until `eglCreateContext`
    failed with EGL_BAD_ALLOC (0x3003) and the exception was fatal on the render
    thread.

    So this patch does both halves:

    1. `invokeAtFrontUninterruptibly` -> `Handler.post`, which is what removes
       the UI-thread block; and
    2. a released-guard at the top of the create lambda, which is what makes
       that safe. `release()` sets `renderThreadHandler = null`, so that field
       is an exact "already released" indicator, and reading it from the lambda
       is a direct field access because the lambda lives inside `EglRenderer`.

    The guard needs no cooperation from the renderer's consumers:
    `EglSurfaceCreation.run()` already returns early on a null `eglBase`, so a
    skipped create simply produces no surface instead of dereferencing null.

    The trade this makes: previously a failure inside EGL creation was captured
    on the render thread and rethrown on the UI thread by
    `invokeAtFrontUninterruptibly`; now it stays on the render thread. That is
    only reachable if EGL creation fails, which is what the leak caused - and
    the leak is what this fixes.

    Not verified on a real multi-participant call: that needs a second person.
    """
    global ok, fail
    path = sfile(base, "com/discord/media/engine/video/egl_renderer/EglRenderer.smali")
    if not os.path.exists(path):
        # 354.1 removed Discord's EglRenderer fork; the stock WebRTC renderer
        # has different init/teardown code, so this edit must be re-derived
        # before it can be re-enabled. Do not guess at it.
        print(
            "  [SKIP] Discord's EglRenderer fork removed in 354.1 - the "
            "non-blocking init edit needs re-derivation"
        )
        return
    src = rd(path)
    if "Patch: released-guard" in src:
        print("  [SKIP] EglRenderer init already non-blocking and guarded")
        return

    marker = (
        "invoke-static {v4, v3}, Lorg/webrtc/ThreadUtils;->"
        "invokeAtFrontUninterruptibly(Landroid/os/Handler;Ljava/lang/Runnable;)V"
    )
    n = src.count(marker)
    if n != 1:
        print(f"  [SKIP] EglRenderer.init: expected 1 blocking call, found {n}")
        return

    sig = (
        ".method private static final init$lambda$10$lambda$9("
        "Lcom/discord/media/engine/video/egl_renderer/EglRenderer;J)V\n"
        "    .locals 0\n"
    )
    if src.count(sig) != 1:
        print("  [SKIP] EglRenderer: create lambda shape changed")
        return

    src = src.replace(
        "    " + marker,
        "    # Patch: post instead of blocking the UI thread. Safe only because\n"
        "    # of the released-guard added to the create lambda below.\n"
        "    invoke-virtual {v4, v3}, Landroid/os/Handler;->post(Ljava/lang/Runnable;)Z",
        1,
    )
    src = src.replace(
        sig,
        ".method private static final init$lambda$10$lambda$9("
        "Lcom/discord/media/engine/video/egl_renderer/EglRenderer;J)V\n"
        "    .locals 1\n\n"
        "    # Patch: released-guard. `release()` nulls renderThreadHandler and\n"
        "    # jumps its cleanup to the FRONT of this queue, so a create still\n"
        "    # pending further back would otherwise build an EGL context after\n"
        "    # cleanup already ran - and nothing would ever free it.\n"
        "    iget-object v0, p0, Lcom/discord/media/engine/video/egl_renderer/"
        "EglRenderer;->renderThreadHandler:Landroid/os/Handler;\n\n"
        "    if-nez v0, :egl_create\n\n"
        "    return-void\n\n"
        "    :egl_create\n",
        1,
    )
    wr(path, src)
    ok += 1
    print(
        "  [OK] EglRenderer.init no longer blocks the UI thread, and the create "
        "lambda skips when the renderer was already released"
    )


def patch_jank_measurement(base):
    """Stop Discord measuring its own jank. It only ever fed analytics.

    `dumpsys gfxinfo` on a live call, 2026-09-28:

        Total frames rendered : 5616
        Janky frames          : 313 (5.57%)
        50th/90th/95th/99th   : 7 / 19 / 29 / 57 ms
        90th GPU percentile   : 2 ms
        Number Slow UI thread : 298

    The GPU is idle (2 ms) while the frame is 19 ms at the 90th percentile, and
    298 slow-UI-thread frames out of 5616 matches the 5.57% jank figure almost
    exactly. So nearly all jank is work on the UI thread, not rendering.

    Three of that work's sources are Discord's own instrumentation, and every
    one of them is dead weight in this build because the analytics they feed are
    already disabled (`track`, `submitEventsImmediately`, Sentry, the whole
    `jank_stats` report chain):

      * `FrameMetricsAggregator.initialize(Window)` installs a
        `Window.OnFrameMetricsAvailableListener` - a callback Android invokes
        with a `FrameMetrics` object **on every single frame**, processed on the
        main thread. This is the one that costs per-frame time.
      * `JankStatsAggregator.initialize(Window)` installs androidx's JankStats
        overlay on the same window.
      * `JankSessionRecorder.init(Context)` starts session recording at
        application startup, which accumulates into `JankRecordStore` and writes
        SharedPreferences.

    All three are `init`/`initialize` entry points whose return value nobody
    reads, so the no-op shape is exact. Nothing user-visible depends on them:
    they produce `JankReport`/`ScreenJankBucket` records that only the disabled
    reporting path consumed.

    Expected effect: removes per-frame main-thread work. NOT yet measured - the
    before numbers above are from the build without this patch, and the after
    numbers need the same call conditions to be comparable.
    """
    global ok, fail
    retired = 0
    for rel, marker, label in [
        (
            "com/discord/jank_stats/FrameMetricsAggregator.smali",
            ".method public final initialize(Landroid/view/Window;)V",
            "FrameMetricsAggregator.initialize (per-frame FrameMetrics listener)",
        ),
        (
            "com/discord/jank_stats/JankStatsAggregator.smali",
            ".method public final initialize(Landroid/view/Window;)V",
            "JankStatsAggregator.initialize (androidx JankStats overlay)",
        ),
        (
            "com/discord/jank_stats/JankSessionRecorder.smali",
            ".method public final init(Landroid/content/Context;)V",
            "JankSessionRecorder.init (session recording + SharedPreferences)",
        ),
    ]:
        if not os.path.exists(sfile(base, rel)):
            retired += 1
            continue
        noop(sfile(base, rel), marker, label)
    if retired:
        print(
            f"  [SKIP] {retired} jank recorder(s) removed in 354.1 "
            "(their JS consumers are already stubbed)"
        )


def patch_blocking_ota_recovery(base):
    """Stop `ActivityDelegate` blocking the UI thread on OTA recovery.

    `ActivityDelegate.onCreate$lambda$2` runs on the UI thread, during activity
    creation, and does:

        if (!latch.await(30, SECONDS)) {          // app load not finished
            Log.e("ActivityDelegate", "App load still waiting ... after 30s");
            captureException(...);
            latch.await();                        // and now wait forever
        }
        runBlockingOtaRecovery();

    and `runBlockingOtaRecovery` does `BundleUpdater.checkForUpdate().get()` -
    an unbounded `Future.get()` - whenever `needsBlockingOtaRecovery()` is true.
    That predicate is just `CrashReporting.isCrashedLastRun()`:

        needsBlockingOtaRecovery() == (Boolean) isCrashedLastRun()

    So the whole path arms itself **after a crash**, which is exactly when the
    user is trying to reopen the app. And with this patch set the OTA host is
    pointed at a non-resolving domain, so the `checkForUpdate()` future has to
    wait out a network timeout before `get()` returns - on the UI thread, in
    onCreate.

    Forcing the predicate false is safe *because* of the OTA patch: the app is
    already pinned to the APK's bundle, so there is no scenario where a
    blocking OTA check could do anything except delay startup. It removes the
    unbounded `Future.get()` without touching the latch logic.

    Left alone deliberately: the second, untimed `latch.await()` above it. That
    one only runs when app load has *already* failed to complete in 30 s, and
    bounding it would let the activity continue against a half-initialised
    runtime - a different failure, not obviously a better one.
    """
    global ok, fail
    rel = "com/discord/react_activities/ActivityDelegate.smali"
    p = sfile(base, rel)
    if not os.path.exists(p):
        print(f"  [SKIP] {rel} not found")
        return
    s = rd(p)
    marker = ".method private final needsBlockingOtaRecovery()Z"
    if marker not in s:
        print("  [SKIP] needsBlockingOtaRecovery not found (shape changed?)")
        return
    a = s.index(marker)
    e = s.index(".end method", a) + len(".end method")
    body = s[a:e]
    if "const/4 v0, 0x0" in body and "isCrashedLastRun" not in body:
        print("  [SKIP] blocking OTA recovery already disarmed")
        return
    wr(p, s[:a] + f"""{marker}
    .locals 1

    const/4 v0, 0x0

    return v0
.end method""" + s[e:])
    print(
        "  [OK] ActivityDelegate.needsBlockingOtaRecovery -> false "
        "(no unbounded Future.get() on the UI thread after a crash)"
    )


def patch_external_browser(base):
    """Open every link in the system browser instead of in-app. ON by default.

    See EXTERNAL_BROWSER above for the why. The mechanics:

    Discord's whole link surface is one React Native module,
    `com/discord/browser_manager/BrowserManagerModule`, plus the singleton it
    delegates to. The JS side (`modules/links/native/BrowserManager.tsx`) reads
    three constants from it once, keeps them in a store, and then
    `browserManagerOpenUrl` branches on `selectedBrowser`:

        WebBrowserType = { SAFARI: 0, IN_APP: 1, CHROME: 2 }

        IN_APP -> NativeBrowserManager.openInAppURL(url)
                     -> BrowserManager.tryOpenUrlWithCustomTabs(...)
                     -> Chrome Custom Tabs, rendered inside Discord's task
        CHROME -> NativeBrowserManager.openInChromeURL(url)
                     -> BrowserManager.tryOpenUrlExternally(...)
                     -> ACTION_VIEW, i.e. the real system browser

    So there are two independent edits here, and they are deliberately
    redundant:

    1. `getTypedExportedConstants()` is the source of that store. Stock it
       reads a cached `SELECTED_BROWSER` and falls back to IN_APP (1) when
       Chrome Custom Tabs are available. Both halves are changed: the cache key
       is renamed to one nothing ever writes, and the fallback becomes CHROME
       (2). The app therefore believes the user picked Chrome, which also keeps
       its own settings UI self-consistent.

    2. `openInAppURL` is re-pointed at `tryOpenUrlExternally`. This is what
       makes the change a guarantee rather than a default: the two methods have
       **identical descriptors**
       (Landroid/content/Context;Ljava/lang/String;Lkotlin/jvm/functions/Function1;)V,
       so the edit is a method-name swap in one `invoke-virtual` with no
       register or signature change at all. Anything that still selects IN_APP
       - a stale store, a future caller, a deep link that passes an explicit
       selectedBrowser - lands in the system browser anyway.

    Deliberately NOT changed: `openTrackedCustomTab`. It is called from exactly
    one place, `modules/vibegrations/native/VibegrationsDesignFeedbackOverlay.tsx`
    (via f138378 <- 96218 <- 46230 <- 8101 <- 16594), and its callback is
    `Function1<Boolean, Unit>` where `tryOpenUrlExternally` wants
    `Function1<Unit, Unit>` - the generics erase, so the swap compiles and then
    throws ClassCastException on the callback's Boolean cast. One survey
    overlay is not worth that.

    `openPlayStoreInline` is left alone: it is a Play Store deep link, not a
    web link, and forcing it external is what `tryOpenUrlExternally` would do
    anyway.
    """
    global ok, fail
    rel = "com/discord/browser_manager/BrowserManagerModule.smali"
    p = sfile(base, rel)
    if not os.path.exists(p):
        print(f"  [FAIL] {rel} missing")
        fail += 1
        return
    s = rd(p)

    def body_of(text, marker):
        a = text.index(marker)
        e = text.index(".end method", a) + len(".end method")
        return a, e, text[a:e]

    # --- 1. getTypedExportedConstants: always report CHROME ---------------- #
    marker = ".method public getTypedExportedConstants()Ljava/util/Map;"
    if marker not in s:
        print("  [FAIL] getTypedExportedConstants not found (shape changed?)")
        fail += 1
        return
    a, e, body = body_of(s, marker)
    if "SELECTED_BROWSER_IGNORED" in body:
        print("  [OK] browser default already forced to CHROME")
    else:
        reads = re.findall(r'const-string (v\d+), "SELECTED_BROWSER"', body)
        if len(reads) != 1:
            print(
                f"  [FAIL] SELECTED_BROWSER cache read found {len(reads)} "
                "times (expected 1)"
            )
            fail += 1
            return
        fallbacks = re.findall(r"const/4 (v\d+), 0x1", body)
        if len(fallbacks) != 1:
            print(
                f"  [FAIL] IN_APP fallback constant found {len(fallbacks)} "
                "times (expected 1)"
            )
            fail += 1
            return
        body = body.replace(
            f'const-string {reads[0]}, "SELECTED_BROWSER"',
            f'const-string {reads[0]}, "SELECTED_BROWSER_IGNORED"',
        ).replace(f"const/4 {fallbacks[0]}, 0x1", f"const/4 {fallbacks[0]}, 0x2")
        s = s[:a] + body + s[e:]
        print(
            "  [OK] BrowserManager selectedBrowser -> CHROME "
            "(cache read bypassed, IN_APP fallback removed)"
        )

    # --- 2. openInAppURL: hard-redirect to the external browser ------------ #
    marker2 = (
        ".method public openInAppURL(Ljava/lang/String;"
        "Lcom/facebook/react/bridge/Promise;)V"
    )
    if marker2 not in s:
        print("  [FAIL] openInAppURL not found (shape changed?)")
        fail += 1
        return
    a, e, body = body_of(s, marker2)
    if "Patch: skip Custom Tabs" in body:
        print("  [OK] openInAppURL already redirected to the external browser")
    elif "Lt6/a;->c(" in body:
        # 354.1's openInAppURL parses the URL and calls the Custom Tabs
        # helper (`c`); openInChromeURL calls the external helper (`d`).
        # Replace the try body with that same external call, keeping the
        # existing callback object (`Lf6/b` implements Function1) so the
        # promise settlement path is unchanged.
        # The .line directives are compiler output and move between builds;
        # match the call sequence itself, not the line-number annotations.
        replacement = (
            "    # Patch: skip Custom Tabs - open in the system browser.\n"
            "    sget-object p2, Lt6/a;->a:Lt6/a;\n\n"
            "    invoke-virtual {p2, v0, p1, v1}, Lt6/a;->d"
            "(Landroid/content/Context;Ljava/lang/String;"
            "Lkotlin/jvm/functions/Function1;)V"
        )
        body, changed = re.subn(
            r"[ \t]*invoke-static \{p1\}, Landroid/net/Uri;->parse\([^\n]*\n"
            r".*?"
            r"[ \t]*invoke-static \{v0, p2, v2\}, Lt6/a;->c"
            r"\(Landroid/content/ContextWrapper;Landroid/net/Uri;Laf/k;\)V",
            replacement,
            body,
            count=1,
            flags=re.S,
        )
        if not changed:
            print("  [FAIL] openInAppURL Custom Tabs call not found")
            fail += 1
            return
        s = s[:a] + body + s[e:]
        print(
            "  [OK] openInAppURL -> external browser "
            "(in-app path unreachable)"
        )
    else:
        print("  [FAIL] openInAppURL has no browser delegate to re-point")
        fail += 1
        return

    wr(p, s)
    ok += 1


def patch_crash_reporting_clinit(base):
    """Stop loading seven exception classes at startup for a disabled feature.

    `CrashReporting.<clinit>` runs the first time anything touches the class -
    which `MainApplication.performInitialization` does early, to call
    `CrashReporting.init(...)`. Discord's own TTI instrumentation times that
    window and prints it as `CrashReporting.init()`; on the reported run it was
    **28 ms**.

    Almost none of that is the call. `CrashReporting.init` already short-circuits
    on `isDisabled()`, which this patch set forces true. The cost is the static
    initializer, which existed to build one list:

        7 x  const-class Ljava/net/UnknownHostException; ... (and 6 more)
             Reflection.getOrCreateKotlinClass(...)      <- 7 class loads
             new-array v7, 7, [Lkotlin/reflect/KClass;
             ... 7 aput-object ...
             CollectionsKt.listOf(array)
             -> ignoreNetworkExceptionList

    Each `const-class` makes ART resolve and load that class, and each
    `getOrCreateKotlinClass` drags in the Kotlin reflection stack. All of it
    feeds `isIgnorableNetworkException`, which decides whether a network
    exception is worth *reporting* to Sentry - and Sentry is removed from this
    build at every layer.

    The rewrite keeps the field non-null (there is one reader, and it calls
    `.contains` on it) by building the same list from a zero-length array.
    `INSTANCE` is still created, so the call site is unaffected.

    This is the same class of change as `patch_blocking_ota_recovery`: work that
    a disabled feature still pays for, on the startup path.
    """
    global ok, fail
    rel = "com/discord/crash_reporting/CrashReporting.smali"
    p = sfile(base, rel)
    if not os.path.exists(p):
        print(f"  [FAIL] {rel} missing")
        fail += 1
        return
    s = rd(p)
    if "Patch: the 7 exception KClasses" in s:
        print("  [OK] CrashReporting <clinit> already trimmed")
        return
    a = s.find(".method static constructor <clinit>()V")
    if a < 0:
        print("  [FAIL] CrashReporting <clinit> not found")
        fail += 1
        return
    e = s.index(".end method", a) + len(".end method")
    body = s[a:e]
    if "getOrCreateKotlinClass" not in body:
        print("  [SKIP] <clinit> no longer loads KClasses (shape changed?)")
        return
    body = re.sub(r"\n\s+const-class v\d+, L[^;]+;", "", body)
    body = re.sub(
        r"\n\s+invoke-static \{v\d+\}, "
        r"Lkotlin/jvm/internal/Reflection;->getOrCreateKotlinClass"
        r"\(Ljava/lang/Class;\)Lkotlin/reflect/KClass;",
        "",
        body,
    )
    body = re.sub(r"\n\s+move-result-object v\d+", "", body)
    body = re.sub(r"\n\s+aput-object v\d+, v7, v\d+", "", body)
    body = re.sub(r"\n\s+const/4 v0, 0x[1-6]", "", body)
    body = re.sub(r"const/4 v7, 0x7", "const/4 v7, 0x0", body)
    marker = (
        "    # Patch: the 7 exception KClasses this used to build were loaded\n"
        "    # eagerly at startup, purely to decide whether a network exception\n"
        "    # is worth *reporting*. Nothing is reported in this build, so the\n"
        "    # list is empty and the loads are gone.\n"
    )
    body = body.replace("    const/4 v7, 0x0", marker + "    const/4 v7, 0x0", 1)
    if "getOrCreateKotlinClass" in body or "const-class" in body:
        print("  [FAIL] reflection survived the rewrite")
        fail += 1
        return
    if "ignoreNetworkExceptionList" not in body or "INSTANCE:Lcom/discord/" not in body:
        print("  [FAIL] the field or INSTANCE was lost")
        fail += 1
        return
    wr(p, s[:a] + body + s[e:])
    ok += 1
    print(
        "  [OK] CrashReporting <clinit> no longer loads 7 exception classes "
        "for a disabled feature"
    )


def patch_jank_recorder_unloaded(base):
    """Stop `MainApplication` loading `JankSessionRecorder` at startup at all.

    `JankSessionRecorder.init(Context)` is already a no-op (see the
    `jank_stats` initialisers), so the class does nothing. It was still being
    *loaded* on the startup path, and Discord's own TTI instrumentation times
    that window - it prints as `JankSessionRecorder.init()`, 21 ms on the
    reported run. None of that was the call; it is class load + verification of
    a large class, spent to invoke a stub.

    Two touch points in `MainApplication.performInitialization()`, and they
    share one register:

        892  sget-object v0, ...JankSessionRecorder;->INSTANCE:...   <- loads the class
        896  invoke-virtual {v0, p0}, ...->init(Landroid/content/Context;)V
        ...
        1427 invoke-virtual {v0, v6}, ...->setPerScreenExperiment(Z)V

    `v0` is not written between 896 and 1427 - checked, not assumed - so the
    register still holds the INSTANCE at 1427. All three lines go together:
    drop 892 and 1427 reads a register that was never set, drop 1427 alone and
    the class still loads. With all three gone the class is not referenced from
    `MainApplication` at all.

    The other references to it (`am/c`, `bb/a`, `bc/a`, `crash_reporting/c`,
    `appsflyer/a`, `JankScreenContext`, `FrameMetricsAggregator`) are all calls
    into it from code that only runs once tracking starts - and tracking cannot
    start, because `init` is a stub. So nothing loads it.

    What this does NOT do: the `setPerScreenExperiment` flag and the
    `perScreenEnabled` static are simply never set. `isPerScreenEnabled()` is
    only read by the recorder's own paths, which are dead.
    """
    global ok, fail
    rel = "com/discord/MainApplication.smali"
    p = sfile(base, rel)
    if not os.path.exists(p):
        print(f"  [FAIL] {rel} missing")
        fail += 1
        return
    s = rd(p)
    if "Patch: JankSessionRecorder unloaded" in s:
        print("  [OK] JankSessionRecorder already unloaded at startup")
        return
    targets = [
        "    sget-object v0, Lcom/discord/jank_stats/JankSessionRecorder;"
        "->INSTANCE:Lcom/discord/jank_stats/JankSessionRecorder;",
        "    invoke-virtual {v0, p0}, Lcom/discord/jank_stats/JankSessionRecorder;"
        "->init(Landroid/content/Context;)V",
        "    invoke-virtual {v0, v6}, Lcom/discord/jank_stats/JankSessionRecorder;"
        "->setPerScreenExperiment(Z)V",
    ]
    for t in targets:
        n = s.count(t + "\n")
        if n != 1:
            print(f"  [FAIL] expected exactly 1 occurrence of {t.strip()!r}, found {n}")
            fail += 1
            return
    for t in targets:
        s = s.replace(t + "\n", "    # Patch: JankSessionRecorder unloaded\n", 1)
    if "jank_stats/JankSessionRecorder" in s:
        print("  [FAIL] a JankSessionRecorder reference survived")
        fail += 1
        return
    wr(p, s)
    ok += 1
    print(
        "  [OK] MainApplication no longer loads JankSessionRecorder at startup "
        "(3 references removed)"
    )


def patch_react_compiler_flag(base):
    """Force `isReactCompilerEnabled()` to true, selecting the compiled path.

    See DISCORD_REACT_COMPILER above for what this is and why it is off by
    default.

    The target is found structurally rather than by id. The closure id changes
    every release, and there is no name to match either: `isReactCompilerEnabled`
    is an *export property*, and the function itself is anonymous in the
    function table. What is stable is the module path, so: xref
    `modules/react_compiler/ReactCompilerGating.tsx` to its factory, walk the
    factory body for the CreateClosure that feeds the
    `PutByIdStrict ..., "isReactCompilerEnabled"` store, and stub that.

    The stub is `LoadConstTrue; Ret` - the same shape `isReactCompilerBuild()`
    already returns in this bundle (`return true`), so no caller sees a shape
    it does not already handle.
    """
    if not DISCORD_REACT_COMPILER:
        print(
            "  [SKIP] React Compiler left on the experiment default "
            "(2026-09-react-compiler-mobile = false)"
        )
        return
    tool = find_tool("hermes-decomp")
    bundle = os.path.join(base, "assets/index.android.bundle")
    if tool is None or not os.path.exists(bundle):
        print("  [FAIL] hermes-decomp or the JS bundle is missing")
        fail += 1
        return

    def run(*args):
        return subprocess.run(
            [tool, *args], capture_output=True, text=True, timeout=900
        )

    path = "modules/react_compiler/ReactCompilerGating.tsx"
    xr = run("xref", "--query", path, bundle).stdout
    facs = re.findall(r"^\s*Function (\d+)", xr, re.M)
    if len(facs) != 1:
        print(f"  [FAIL] {len(facs)} factories reference {path}")
        fail += 1
        return
    factory = facs[0]

    pending, target = None, None
    for line in run("disasm", "--function", factory, bundle).stdout.splitlines():
        m = re.search(
            r"CreateClosure(?:LongIndex)?\s+r\d+,\s*r\d+,\s*(\d+)", line
        )
        if m:
            pending = m.group(1)
            continue
        # The store immediately follows the closure it publishes.
        if pending and "PutByIdStrict" in line and '"isReactCompilerEnabled"' in line:
            target = pending
            break
    if target is None:
        print("  [FAIL] no CreateClosure feeds the isReactCompilerEnabled export")
        fail += 1
        return

    with tempfile.TemporaryDirectory() as td:
        stub = os.path.join(td, "rc.hasm")
        with open(stub, "w", encoding="utf-8") as f:
            f.write("0000  LoadConstTrue r0\n0001  Ret r0\n")
        tmp = os.path.join(td, "rc.hbc")
        r = run(
            "patch-function", "--function", target, "--hasm", stub,
            "--output", tmp, bundle,
        )
        if not os.path.exists(tmp):
            msg = (r.stderr.strip().splitlines() or ["assemble failed"])[-1]
            print(f"  [FAIL] {msg[:110]}")
            fail += 1
            return
        chk = run("decompile", "--function", target, tmp).stdout
        if "return true;" not in chk:
            print("  [FAIL] React Compiler gate did not take")
            fail += 1
            return
        shutil.copy2(tmp, bundle)
        ok += 1
        print(
            f"  [OK] React Compiler forced ON (fn {target}, from {path}) - "
            "Settings > Advanced should now read Enabled"
        )


def patch_bound_surface_release(base):
    """Give `SurfaceEglRenderer.surfaceDestroyed` a bounded UI-thread wait.

    See DISCORD_BOUND_SURFACE_RELEASE for what this is and why it is off.

    The change is one call: the one-arg `awaitUninterruptibly(CountDownLatch)`
    becomes the timed `awaitUninterruptibly(CountDownLatch, long)` that already
    lives in ThreadUtils. `v0`/`v1` are free at that point - `v0` held the
    Runnable, which `releaseEglSurface` has already consumed - so the `long`
    fits in the frame's existing `.locals 2` with no register growth.
    """
    if not DISCORD_BOUND_SURFACE_RELEASE:
        print(
            "  [SKIP] surfaceDestroyed keeps its unbounded UI-thread wait - "
            "set DISCORD_BOUND_SURFACE_RELEASE=True to bound it"
        )
        return
    rel = "smali_classes4/org/webrtc/SurfaceEglRenderer.smali"
    p = sfile(base, rel)
    if not os.path.exists(p):
        print(f"  [SKIP] {rel} not found")
        return
    s = rd(p)
    old = (
        "    invoke-static {p1}, Lorg/webrtc/ThreadUtils;->"
        "awaitUninterruptibly(Ljava/util/concurrent/CountDownLatch;)V"
    )
    new = (
        f"    const-wide/16 v0, 0x{SURFACE_RELEASE_TIMEOUT_MS:x}\n"
        "\n"
        "    invoke-static {p1, v0, v1}, Lorg/webrtc/ThreadUtils;->"
        "awaitUninterruptibly(Ljava/util/concurrent/CountDownLatch;J)Z\n"
        "\n"
        "    move-result v0"
    )
    if new in s:
        print("  [SKIP] surfaceDestroyed wait already bounded")
        return
    if s.count(old) != 1:
        print(
            f"  [SKIP] expected exactly 1 unbounded wait in surfaceDestroyed, "
            f"found {s.count(old)}"
        )
        return
    wr(p, s.replace(old, new, 1))
    print(
        f"  [OK] surfaceDestroyed UI-thread wait bounded to "
        f"{SURFACE_RELEASE_TIMEOUT_MS} ms (was unbounded)"
    )


def patch_media_callback_visibility(base):
    """Make swallowed media-engine callback failures visible in logcat.

    Every media callback wrapper in `MediaEngine` wraps the JS handler in
    try/catch and, on Throwable, builds a RuntimeException and hands it to
    `CrashReporting.captureException` — then returns normally. So a JS handler
    that throws (for a ping timeout, an MLS failure, a video frame, a
    speaking-state change) aborts *that callback* and nothing else happens.
    No reconnect, no teardown, no trace.

    With crash reporting gated inert, that report goes nowhere, so the failure
    is completely invisible. This is a real candidate for "the call died but
    the notification still says connected": the notification is driven by
    gateway voice state, so if the media callback that would have torn the
    connection down throws, nothing updates.

    This rewrites the `captureException` call in each of those catch blocks to
    a `Log.e(tag, msg, throwable)` instead. It is a **diagnostic, not a fix** —
    it changes no behaviour beyond making the failure observable:

        adb logcat -s MediaEngineCB

    The three registers the original used for `captureException$default`'s
    trailing (boolean, int, Object) arguments are constants loaded immediately
    before the call, i.e. scratch, and the fourth is the Throwable — so the
    rewrite needs no new registers and cannot overflow the frame.
    """
    print("=== MEDIA CALLBACK VISIBILITY (diagnostic) ===")
    # 354.1's media callbacks no longer route failures through
    # CrashReporting.captureException, so there is nothing to re-point at
    # logcat. The media engine itself is `NativeEngine`.
    path = sfile(base, "com/discord/media/engine/MediaEngine.smali")
    if not os.path.exists(path):
        print("  [SKIP] MediaEngine callbacks no longer report to Sentry")
        return
    s = rd(path)
    if "MediaEngineCB" in s:
        print("  [SKIP] media callback logging already installed")
        return

    call = re.compile(
        r"(?m)^(?P<indent>\s*)invoke-static \{(?P<ops>[^}]*)\}, "
        r"Lcom/discord/crash_reporting/CrashReporting;->captureException\$default"
        r"\(Lcom/discord/crash_reporting/CrashReporting;Ljava/lang/Throwable;ZILjava/lang/Object;\)V\s*$"
    )
    count = 0

    def sub(m):
        nonlocal count
        ops = [o.strip() for o in m.group("ops").split(",")]
        if len(ops) != 5:
            return m.group(0)
        # ops = [instance, throwable, boolean, int, object]
        inst, th, b, i, o = ops
        count += 1
        ind = m.group("indent")
        return (
            f"{ind}# Patch: surface the swallowed failure instead of dropping it.\n"
            f"{ind}sget-object {i}, Lcom/discord/logging/Log;->INSTANCE:Lcom/discord/logging/Log;\n\n"
            f'{ind}const-string {b}, "MediaEngineCB"\n\n'
            f'{ind}const-string {o}, "media engine native callback threw"\n\n'
            f"{ind}invoke-virtual {{{i}, {b}, {o}, {th}}}, "
            f"Lcom/discord/logging/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V"
        )

    s2 = call.sub(sub, s)
    if not count:
        print("  [FAIL] no media callback catch blocks matched")
        fail += 1
        return
    wr(path, s2)
    ok_global = count
    print(
        f"  [OK] {ok_global} media callback catch block(s) now log to "
        f"'MediaEngineCB' (adb logcat -s MediaEngineCB)"
    )


# Camera capture request. Lowering this saves real power - 640x360 @ 24 is 4x
# fewer pixels than 1280x720 @ 30 - but it CANNOT be changed on its own, and
# doing so crashes the app the instant the camera starts.
#
# `VideoQualityManager.getVideoQuality` builds a PAIR:
#     obj.capture = { width, height, framerate }        <- the camera feed
#     obj.encode  = { ...ladder.getResolution(...) }    <- the encoder
# and the ladder is derived from `options.videoBudget`, which is 1280x720. So
# the encoder is configured for up to 1280x720 while the camera would deliver
# 640x360 - an invalid pair, because the media engine has to upscale from the
# capture to the encode, and it aborts rather than do that. Confirmed on
# device: camera on -> instant crash.
#
# To take this, lower `videoBudget` (buffer 1245 / the ladder consumer) and the
# bitrate alongside the capture so all three stay consistent, and verify on
# hardware before trusting it. Left off until that is done.
DISCORD_LOWER_CAPTURE = opt_bool("DISCORD_LOWER_CAPTURE", False)
CAPTURE_WIDTH = opt_int("DISCORD_CAPTURE_WIDTH", 640, 2, 7680)
CAPTURE_HEIGHT = opt_int("DISCORD_CAPTURE_HEIGHT", 360, 2, 4320)
CAPTURE_FRAMERATE = opt_int("DISCORD_CAPTURE_FRAMERATE", 24, 1, 120)

# React Compiler (new in 349.5), off by default. See patch_react_compiler_flag.
#
# 349.5 is the first build Discord compiles with the React Compiler, and it
# ships BOTH variants of every component behind a runtime experiment:
#
#     memo(isReactCompilerEnabled() ? (() => {compiled}) : (() => {uncompiled}))
#
# The experiment is `2026-09-react-compiler-mobile` with `defaultValue = false`,
# so a stock client always runs the *uncompiled* branch and the ~27k generated
# functions that came with it are dead weight. Forcing the gate on is the same
# class of change as the other toggles here: it is a real, observable switch
# (Settings > Advanced shows a "React Compiler" row that reads Enabled/Disabled)
# but it selects a code path Discord has not shipped to users yet, so it is
# opt-in and needs a device pass before it can be called an improvement.
DISCORD_REACT_COMPILER = opt_bool("DISCORD_REACT_COMPILER", False)

# Open every web link in the system browser instead of in-app. ON by default.
#
# Discord renders links in Chrome Custom Tabs inside its own task
# (`openInAppURL` -> `tryOpenUrlWithCustomTabs`), so a link to a shop page, a
# quest page or anything else keeps you inside the app, under its toolbar,
# with its session. `openInChromeURL` already exists and does the right thing -
# a plain `ACTION_VIEW` that hands off to the default browser - it is just not
# what the default browser setting selects.
#
# This is on by default, unlike the flags above, because it is not a trade-off:
# there is no case where the in-app tab is better than the browser the user
# actually chose, and the alternative path is already shipped and exercised on
# every device that has no Custom Tabs provider. It needs no device pass to be
# safe - it is a routing change, not a new code path.
EXTERNAL_BROWSER = opt_bool("EXTERNAL_BROWSER", True)

# Stop an incoming call blocking the interface thread. ON by default.
#
# `IncomingCallActivity` runs THREE nested `runBlocking` calls on the main
# thread - `initView` (called from `onCreate`), and `configureActions` and
# `configureUser` inside its coroutine. Behind them, `fetchImage` does
#
#     FrescoModuleDiscord.Companion.initializeFresco(context)     <- image pipeline, disk I/O
#     FrescoFetchDecodedImageKt.fetchDecodedImage(...)            <- SUSPENDING NETWORK FETCH
#
# so the main thread is parked on a network round-trip every time a call
# arrives, plus a cold image-pipeline construction on the first one. That is
# the freeze reported when a call comes in.
#
# The fix stubs the suspend function `fetchImage` to return null. The callers
# already handle that: they hand the result to `check-cast ... Bitmap` (null
# casts fine) and then `ImageView.setImageBitmap(null)`, which clears the view.
# The caller's NAME is still shown - only the avatar is dropped.
#
# The alternative - keeping the avatar - means a `withContext(Dispatchers.IO)`
# hop inside a suspend function, which is a real restructure of the coroutine
# state machine, not a stub. Not worth it for an avatar on a screen you look at
# for two seconds.
#
# Fresco is NOT lost: `NotificationRenderer` and `FrescoModuleDiscord` both
# initialize it on the normal startup path, so this only removes the redundant
# blocking init from the incoming-call path.
DISCORD_INCOMING_CALL_NO_BLOCK = opt_bool("DISCORD_INCOMING_CALL_NO_BLOCK", True)

# Bound the UI-thread wait in `SurfaceEglRenderer.surfaceDestroyed`.
#
# `surfaceDestroyed` is a SurfaceHolder.Callback, so it runs on the UI thread.
# It asserts that, posts the EGL-surface release to the render thread, and then
# blocks on a CountDownLatch with NO timeout:
#
#     checkIsOnMainThread();
#     CountDownLatch latch = new CountDownLatch(1);
#     releaseEglSurface(latch::countDown);
#     ThreadUtils.awaitUninterruptibly(latch);      // <- unbounded, on the UI thread
#
# That is stock upstream WebRTC, and it is deliberate: the method must not
# return until the EGL surface is released, or the surface can be torn down
# while the render thread is still drawing into it. But it also means that if
# the render thread is wedged - which is exactly the state during a call that
# has already failed - the UI thread blocks behind it and the app ANRs.
#
# The flag switches the unbounded wait for the timed overload that already
# exists in this same class (`awaitUninterruptibly(CountDownLatch, long)`),
# with a 2 s ceiling. The ordering guarantee still holds in every normal case
# (the release completes in microseconds); only the pathological case changes,
# from "UI thread hangs until the ANR watchdog fires" to "UI thread gives up
# and the release finishes late".
#
# OFF by default because that last sentence is a real behaviour change: an
# early return narrows, but does not remove, the window upstream blocks to
# close. Needs a device pass on join/leave/rotate/background before it can be
# called an improvement rather than a different bug.
DISCORD_BOUND_SURFACE_RELEASE = opt_bool("DISCORD_BOUND_SURFACE_RELEASE", False)
SURFACE_RELEASE_TIMEOUT_MS = opt_int(
    "DISCORD_SURFACE_RELEASE_TIMEOUT_MS", 2000, 0, 60000
)


# Remove the per-module, per-locale intl cache from res/raw.
#
# Measured on the 349.5 build: 1741 files, 23.63 MB uncompressed / 7.26 MB
# compressed in the APK, covering 52 namespaces (one per module) x 31 locales,
# with one catalog per locale per namespace.
#
# The names decode. `cache_intl_<base64(namespace).lower()>_<locale>messages
# <md5>compiledmessages.jsona`, where the namespace base64 is lowercased and
# padding-stripped: `aw50ba` = base64("intl"), and that one namespace alone is
# 21.40 MB of the 23.63. The remaining 51 are 2.22 MB between them.
#
# What they contain is the actual translated UI text, e.g.
#     {"hPBScv":["Dismiss pop-up window"],"ZqK0uI":["Pop-up Window"], ...}
#
# Why removal looks safe:
#   * NOTHING statically references them. Not one smali method, not a native
#     library, not the Hermes bundle. The resource ids in R$raw.smali are used
#     by no code - verified by taking an actual id (0x7f120005) and grepping
#     every smali file for it; the only hit is its own declaration.
#   * Discord ships an explicit res/raw/keep.xml listing all 1741 of them.
#     A statically-referenced resource does not need a keep rule - the
#     shrinker already sees the reference - so the presence of that list is
#     itself evidence the lookup is by *constructed name*.
#   * There is no `en`/`enus` catalog at all; only `engb`. So an en-US client
#     (the default) already runs with no catalog for its own locale, which
#     means the fall-back-to-source path exists and is exercised today.
#
# Why this is a flag at all: the failure mode if something does resolve one at
# runtime is a `Resources.NotFoundException` - a crash, not a degraded string.
# Turned ON at the user's request (2026-10-03) with English kept, so the only
# unverified thing in this build is the non-English catalogs. If the app fails
# to start or shows blank text, set this back to False and rebuild - that is
# the whole revert.
DISCORD_STRIP_INTL = opt_bool("DISCORD_STRIP_INTL", True)
# Locales to KEEP when DISCORD_STRIP_INTL is on. Default is English only:
#   * it is the only locale whose text is also in the bundle as source,
#   * it costs 0.63 MB of the 23.63, so keeping it gives up ~3% of the win,
#   * and it removes the worst case (a locale whose catalog is missing AND
#     whose source strings are not inlined) for the locale most likely in use.
# Set to set() to remove everything, or add locales to keep them.
_keep_locales = opt("DISCORD_KEEP_LOCALES", "engb")
if not isinstance(_keep_locales, str):
    print("  [SKIP] DISCORD_KEEP_LOCALES is not a string; keeping engb")
    _keep_locales = "engb"
INTL_KEEP_LOCALES = {
    x.strip() for x in _keep_locales.split(",") if x.strip()
}


def patch_strip_intl_cache(base):
    """Drop res/raw/cache_intl_* for locales outside INTL_KEEP_LOCALES.

    This used to delete every catalog. That is the same instruction count for
    English users and strictly worse for anyone else, so it now keeps a set.
    See the DISCORD_STRIP_INTL comment for what these files are and why
    removing them looks safe.
    """
    print("=== INTL CACHE ===")
    global ok, fail
    if not DISCORD_STRIP_INTL:
        n = 0
        d = os.path.join(base, "res", "raw")
        if os.path.isdir(d):
            n = sum(1 for f in os.listdir(d) if f.startswith("cache_intl"))
        r = subprocess.run(
            ["du", "-sh", os.path.join(base, "res", "raw")],
            capture_output=True, text=True,
        ).stdout.split()[0] if os.path.isdir(d) else "?"
        print(f"  [SKIP] {n} cache_intl file(s) present ({r} in res/raw) - "
              f"set DISCORD_STRIP_INTL=True to remove")
        return

    raw = os.path.join(base, "res", "raw")
    # cache_intl_<base64(ns)>_<locale>messages<md5>compiledmessages.jsona
    name_re = re.compile(
        r"^cache_intl_([^_]+)_([a-z0-9]+?)messages([0-9a-f]{32})"
        r"compiledmessages\.jsona$"
    )
    removed, kept, freed = [], [], 0
    if os.path.isdir(raw):
        for f in sorted(os.listdir(raw)):
            if not f.startswith("cache_intl"):
                continue
            m = name_re.match(f)
            # Anything that does not parse is left alone: a name we do not
            # understand is not a name we should delete.
            if not m or m.group(2) in INTL_KEEP_LOCALES:
                kept.append(f)
                continue
            freed += os.path.getsize(os.path.join(raw, f))
            os.remove(os.path.join(raw, f))
            removed.append(os.path.splitext(f)[0])

    gone = set(removed)
    for rel, pat in (
        ("com/discord/R$raw.smali",
         r"^\.field public static (cache_intl\w*):I = 0x[0-9a-f]+\n"),
        ("res/values/public.xml",
         r'^\s*<public type="raw" name="(cache_intl\w*)" id="0x[0-9a-f]+" />\n'),
    ):
        fp = os.path.join(base, rel)
        if not os.path.exists(fp):
            continue
        src = rd(fp)
        src2 = re.sub(
            pat,
            lambda m: "" if m.group(1) in gone else m.group(0),
            src, flags=re.M,
        )
        if src2 != src:
            wr(fp, src2)

    # keep.xml is Discord's own resource-shrinker keep-list and lists every
    # catalog by name. Left stale it names 1741 resources that no longer
    # exist; regenerate it against what survived so aapt does not have to
    # reconcile a keep-list with missing entries.
    kp = os.path.join(raw, "keep.xml")
    if os.path.exists(kp) and removed:
        src = rd(kp)
        if "<resources" in src:
            body = ",".join(f"@raw/{os.path.splitext(f)[0]}" for f in kept)
            wr(kp, '<resources xmlns:tools="http://schemas.android.com/tools" '
                    f'tools:keep="{body}" />\n')

    print(
        f"  [OK] removed {len(removed)} intl cache file(s), "
        f"{freed / 1e6:.1f} MB uncompressed; kept {len(kept)} "
        f"({', '.join(sorted(INTL_KEEP_LOCALES)) or 'none'})"
    )


def patch_video_capture_resolution(base):
    """Lower the camera capture request to save power.

    `VideoQualityManager.getVideoQuality` builds the object the native engine
    reads the capture size from:

        obj.capture = { width:  options.videoCapture.width,
                        height: options.videoCapture.height,
                        framerate: options.videoCapture.framerate }

    `options` comes from `defaultVideoQualityOptions`, whose `videoCapture` is
    serialized into an *object buffer* (buffer 1245) - not an immediate, so the
    numbers cannot be swapped as operands. Clamping them here instead edits
    only the three value-producing chains, replacing each

        GetByIdShort r6, r4, 2, "options"
        GetById      r6, r6, 10, "videoCapture"
        GetByIdShort r6, r6, 11, "width"

    with a single constant load into the same register. The object, its shape,
    the slot indices, the register set and the control flow are all unchanged:
    no environment store is touched (which is what crashed the 347.5 IDENTIFY
    edit), the body only shrinks, and fewer `GetById` uses means less inline
    cache pressure, not more.
    """
    print("=== CAMERA CAPTURE RESOLUTION (off by default) ===")
    global ok, fail
    if not DISCORD_LOWER_CAPTURE:
        print(
            "  [SKIP] capture left at 1280x720 - lowering it alone makes "
            "capture < encode and crashes the camera; set "
            "DISCORD_LOWER_CAPTURE=True only after lowering videoBudget too"
        )
        return
    tool = find_tool("hermes-decomp")
    bundle = os.path.join(base, "assets/index.android.bundle")
    if tool is None or not os.path.exists(bundle):
        print("  [SKIP] hermes-decomp or bundle missing")
        return

    def run(*a):
        return subprocess.run([tool, *a], capture_output=True, text=True, timeout=900)

    # Locate by name, not by id: ids shift every release.
    target = None
    for line in run("dump", "--kind", "functions", bundle).stdout.split("\n"):
        parts = line.split()
        if len(parts) >= 3 and " ".join(parts[1:-2]) == "getVideoQuality":
            target = parts[0]
            break
    if target is None:
        print("  [SKIP] getVideoQuality not found")
        return

    with tempfile.TemporaryDirectory() as td:
        hasm = os.path.join(td, "vq.hasm")
        run("emit-hasm", "--function", target, "-o", hasm, bundle)
        if not os.path.exists(hasm):
            print("  [FAIL] emit-hasm produced nothing")
            fail += 1
            return
        lines = rd(hasm).split("\n")
        # find the three chains that read options.videoCapture.<field>
        idx = [i for i, l in enumerate(lines) if '"videoCapture"' in l]
        if len(idx) < 3:
            print(f"  [SKIP] capture block not found ({len(idx)} videoCapture reads)")
            return
        if any("LoadConstInt r6, %d" % CAPTURE_WIDTH in l for l in lines):
            print("  [SKIP] capture resolution already lowered")
            return
        # each chain is 3 lines: options -> videoCapture -> field; keep the
        # PutOwnBySlotIdx that follows and replace the three reads with a load
        try:
            for want_field, value, opcode in (
                ("width", CAPTURE_WIDTH, "LoadConstInt"),
                ("height", CAPTURE_HEIGHT, "LoadConstInt"),
                ("framerate", CAPTURE_FRAMERATE, "LoadConstInt"),
            ):
                j = next(
                    i
                    for i, l in enumerate(lines)
                    if f'"{want_field}"' in l and i > 0 and '"videoCapture"' in lines[i - 1]
                )
                lines[j - 2 : j + 1] = [f"{opcode} r6, {value}"]
        except StopIteration:
            print("  [FAIL] capture chain shape changed")
            fail += 1
            return
        out = "\n".join(l for l in lines if not l.lstrip().startswith("#"))
        wr(hasm, out)

        tmp = os.path.join(td, "vq.bundle")
        r = run("patch-function", bundle, "--function", target, "--hasm", hasm, "-o", tmp)
        if not os.path.exists(tmp):
            msg = r.stderr.strip().splitlines()[-1][:110] if r.stderr.strip() else "assemble failed"
            print(f"  [FAIL] {msg}")
            fail += 1
            return
        chk = run("decompile", "--function", target, tmp).stdout
        if (
            f"width: {CAPTURE_WIDTH}" not in chk
            or f"height: {CAPTURE_HEIGHT}" not in chk
            or f"framerate: {CAPTURE_FRAMERATE}" not in chk
        ):
            print("  [FAIL] verification failed - capture values did not take")
            fail += 1
            return
        shutil.copy2(tmp, bundle)
        ok += 1
        print(
            f"  [OK] camera capture lowered to "
            f"{CAPTURE_WIDTH}x{CAPTURE_HEIGHT} @ {CAPTURE_FRAMERATE} fps (fn {target})"
        )


MEDIA_SCOPE_INJECTION_V3 = (
    "    # Patch: supervised media scope. A plain Job cancelled every media\n"
    "    # call on the first failure and rethrew it to the process handler;\n"
    "    # the supervisor keeps siblings alive and the handler consumes it.\n"
    "    new-instance v4, Li40/t1;\n\n"
    "    invoke-direct {v4}, Li40/e1;-><init>()V\n\n"
    "    invoke-interface {v3, v4}, Lz00/h;->V(Lz00/h;)Lz00/h;\n\n"
    "    move-result-object v3\n\n"
    "    new-instance v4, Lj40/b;\n\n"
    "    invoke-direct {v4}, Lj40/b;-><init>()V\n\n"
    "    invoke-interface {v3, v4}, Lz00/h;->V(Lz00/h;)Lz00/h;\n\n"
    "    move-result-object v3\n\n"
)

MEDIA_SCOPE_INJECTION_P1 = (
    "    # Patch: supervised media scope (see MediaEngineModule).\n"
    "    new-instance v1, Li40/t1;\n\n"
    "    invoke-direct {v1}, Li40/e1;-><init>()V\n\n"
    "    invoke-static {p1, v1}, Lbs/f;->D(Lz00/f;Lz00/h;)Lz00/h;\n\n"
    "    move-result-object p1\n\n"
    "    new-instance v1, Lj40/b;\n\n"
    "    invoke-direct {v1}, Lj40/b;-><init>()V\n\n"
    "    invoke-static {p1, v1}, Lbs/f;->D(Lz00/f;Lz00/h;)Lz00/h;\n\n"
    "    move-result-object p1\n\n"
)


def patch_coroutine_exception_logging(base):
    """Log the exceptions the media scope's handler consumes."""
    global fail
    handler = sfile(base, "j40/b.smali")
    if not os.path.exists(handler):
        print("  [FAIL] j40/b.smali missing - cannot log coroutine exceptions")
        fail += 1
        return
    h = rd(handler)
    hmarker = ".method public final l0(Ljava/lang/Throwable;Lz00/h;)V"
    if "Patch: log unhandled coroutine exceptions" in h:
        return
    start = h.find(hmarker)
    if start < 0:
        print("  [FAIL] coroutine handler l0 not found")
        fail += 1
        return
    # The method already declares its register count. Insert after that
    # directive so the method header stays in canonical smali order.
    directive = re.search(
        r"(?m)^[ \t]*\.(?:locals|registers)[ \t]+\d+[ \t]*\n",
        h[start + len(hmarker):],
    )
    if not directive:
        print("  [FAIL] coroutine handler has no register directive")
        fail += 1
        return
    cut = start + len(hmarker) + directive.end()
    block = (
        "\n    # Patch: log unhandled coroutine exceptions.\n"
        '    const-string v0, "DiscordCoroutines"\n\n'
        '    const-string v1, "unhandled coroutine exception"\n\n'
        "    invoke-static {v0, v1, p1}, Landroid/util/Log;->e("
        "Ljava/lang/String;Ljava/lang/String;"
        "Ljava/lang/Throwable;)I\n"
    )
    h = h[:cut] + block + h[cut:]
    wr(handler, h)
    print("  [OK] unhandled coroutine exceptions log as DiscordCoroutines")


def patch_media_scope_supervisor(base):
    """Give the media engine a supervisor scope and an exception handler.

    354.1 builds `appScope` as `CoroutineScope(dispatcher)` with only the
    dispatcher in the context, so `CoroutineScope(context)` installs a plain
    Job. The first child exception cancels all 64 media launch sites, and a
    plain Job does not handle the exception: it reaches the uncaught-exception
    handler on the media thread and kills the process.

    A SupervisorJob alone keeps siblings alive but still rethrows the failing
    child, so this adds both elements. Both classes ship in this APK (`i40/t1`
    is the supervisor used by Discord's own MainScope, and `j40/b` is the
    ServiceLoader pre-handler looked up by `i40/z.q`).
    """
    print("=== MEDIA SCOPE (supervisor + handler) ===")
    global ok, fail
    patch_coroutine_exception_logging(base)
    path = sfile(base, "com/discord/media/engine/MediaEngineModule.smali")
    if not os.path.exists(path):
        print("  [FAIL] MediaEngineModule.smali missing")
        fail += 1
        return
    for rel in (
        "i40/t1.smali",
        "i40/e1.smali",
        "j40/b.smali",
        "i40/z.smali",
        "z00/h.smali",
    ):
        if not os.path.exists(sfile(base, rel)):
            print(f"  [FAIL] {rel} missing - cannot supervise the media scope")
            fail += 1
            return
    s = rd(path)
    if "Patch: supervised media scope" in s:
        print("  [OK] media scope already supervised")
        return
    marker = (
        ".method public constructor <init>("
        "Lcom/facebook/react/bridge/ReactApplicationContext;)V"
    )
    if marker not in s:
        print("  [FAIL] MediaEngineModule constructor not found")
        fail += 1
        return
    a = s.index(marker)
    e = s.index(".end method", a) + len(".end method")
    body = s[a:e]
    anchor = "    invoke-static {v3}, Li40/z;->b(Lz00/h;)Ln40/d;"
    if anchor not in body:
        print("  [FAIL] media scope factory anchor not found")
        fail += 1
        return
    body = body.replace(anchor, MEDIA_SCOPE_INJECTION_V3 + anchor, 1)
    wr(path, s[:a] + body + s[e:])
    ok += 1
    print("  [OK] appScope is supervised and has an exception handler")

    # MediaEngine's own scope (`cd/o.<init>`) is built the same way and is
    # launched into by transport code. It gets the same two elements.
    path = sfile(base, "cd/o.smali")
    if os.path.exists(path):
        s = rd(path)
        if "Patch: supervised media scope" not in s:
            anchor2 = "    invoke-static {p1}, Li40/z;->b(Lz00/h;)Ln40/d;"
            if anchor2 in s:
                s = s.replace(
                    anchor2, MEDIA_SCOPE_INJECTION_P1 + anchor2, 1
                )
                wr(path, s)
                print("  [OK] MediaEngine internal scope is supervised")
            else:
                print("  [SKIP] MediaEngine internal scope anchor not found")

DISCORD_STRIP_CAMERA_FPS_LOG = opt_bool(
    "DISCORD_STRIP_CAMERA_FPS_LOG", True
)


def patch_webrtc_camera_log_spam(base):
    """Drop the per-2s fps log build in WebRTC's CameraStatistics. Safe version.

    `CameraVideoCapturer$CameraStatistics$1.run()` reposts itself every 2000 ms
    for the whole camera session. Each tick computes the frame rate, builds a
    `StringBuilder`, and hands it to `org.webrtc.Logging.d` - which, with no
    loggable injected, falls through to `java.util.logging` and builds a SECOND
    StringBuilder before writing a logcat line nobody reads. Two allocations
    and a log write, every 2 s per camera, for the duration of every video
    call. The output is discarded, so the cost is pure waste.

    WHY THIS IS THE SAFE VERSION, AND THE EARLIER ONE WAS NOT
    ---------------------------------------------------------
    This patch was written once before, removed the same block, and **crashed
    the app the instant the camera opened**:

        VerifyError: Verifier rejected class CameraVideoCapturer$CameraStatistics$1:
        run() failed to verify: [0x29] register v1 has type Undefined
        but expected String

    The reason is one register, and it is not obvious from the block being
    deleted. `run()` has three `Logging` call sites:

        119  const-string v1, "CameraStatistics"
        123  invoke-static {v1, v0}, Logging;->d(...)      <- the fps line
        ...
        205  const-string v0, "Camera freezed."
        209  invoke-static {v1, v0}, Logging;->e(...)      <- REUSES v1 as the tag

    Line 209 never reloads the tag - it relies on the `const-string` at 119
    still having executed. So deleting the block *including* that const-string
    leaves `v1` undefined on every path that reaches the freeze-detection
    branch, and ART rejects the class **at load**, which `CameraStatistics.
    <init>` triggers the moment the camera opens.

    The earlier reasoning - "it cannot fire before 2 s because of postDelayed" -
    constrained when `run()` EXECUTES, not when the class is VERIFIED. That is
    what made this look safe and cost four builds.

    So this version keeps the `const-string v1, "CameraStatistics"` and removes
    only the string construction and the log call between it. Checked before
    writing it, not after:

    * `v1` is still a String at 209 - every path there runs 119 first. The only
      labels in the method are `:cond_0`, `:cond_1` and `:cond_2`, all *after*
      the removed range, so nothing branches into it.
    * `v2` is reassigned at 153/156 and again at 181 before each use; the
      `const-string v2, "Camera fps: "` was its only read.
    * `v0` holds the fps int from the computation and is reassigned at 128.
    * There is no back-edge - the 2 s repeat is a `postDelayed`, not a loop.

    The frame counter, the frozen-camera detection and the `onCameraFreezed`
    callbacks are untouched. The fps *computation* above the removed block is
    left in place as well: it is a few float ops on scratch registers, and
    leaving it keeps this edit to the smallest possible span.
    """
    print("=== WEBRTC CAMERA STATS LOG ===")
    global ok, fail
    if not DISCORD_STRIP_CAMERA_FPS_LOG:
        print("  [SKIP] DISCORD_STRIP_CAMERA_FPS_LOG=False")
        return
    path = sfile(
        base, "org/webrtc/CameraVideoCapturer$CameraStatistics$1.smali"
    )
    if not os.path.exists(path):
        print("  [SKIP] CameraStatistics$1.smali missing")
        return
    src = rd(path)
    if "Patch: fps log removed" in src:
        print("  [OK] CameraStatistics fps-log build already removed")
        return
    m = re.search(r"^\.method public run\(\)V.*?^\.end method", src, re.M | re.S)
    if not m:
        print("  [FAIL] CameraStatistics$1.run() not found")
        fail += 1
        return
    body = m.group(0)
    start = body.find("new-instance v1, Ljava/lang/StringBuilder;")
    tag = body.find('const-string v1, "CameraStatistics"')
    end = body.find(
        "invoke-static {v1, v0}, "
        "Lorg/webrtc/Logging;->d(Ljava/lang/String;Ljava/lang/String;)V"
    )
    if start < 0 or tag < 0 or end < 0 or not (start < tag < end):
        print("  [FAIL] fps-log block not found (shape changed?)")
        fail += 1
        return
    end_line = body.find("\n", end) + 1
    replacement = (
            "    # Patch: fps log removed - the string was built every 2 s per\n"
        "    # camera and written to a sink nobody reads. The tag constant\n"
        "    # below is KEPT: the freeze-detection branch further down reuses\n"
        "    # v1 as its tag without reloading it, and deleting it fails ART\n"
        "    # verification at class load (see the docstring).\n"
        '    const-string v1, "CameraStatistics"\n'
    )
    # `start` points at `new-instance`, not at the line start, so the original
    # line's indentation is still in the prefix - strip it or the first comment
    # line renders with eight spaces.
    newbody = body[:start].rstrip(" ") + replacement + body[end_line:]
    new_src = src[: m.start()] + newbody + src[m.end():]

    # The assertion that matters: the tag must survive, the fps build and its
    # log call must not, and the freeze-detection branch must be intact.
    if "Lorg/webrtc/Logging;->d" in newbody:
        print("  [FAIL] the fps log call survived the edit")
        fail += 1
        return
    if 'const-string v1, "CameraStatistics"' not in newbody:
        print("  [FAIL] the tag constant was removed - this is the edit that "
              "crashes the camera on open")
        fail += 1
        return
    if "onCameraFreezed" not in newbody or "Camera freezed." not in newbody:
        print("  [FAIL] freeze detection did not survive the edit")
        fail += 1
        return
    wr(path, new_src)
    ok += 1
    print(
        "  [OK] CameraStatistics fps-log build removed "
        "(freeze detection and the shared log tag kept)"
    )


def patch_sentry_native_libs(base):
    """Remove Sentry's native libraries and disarm their loader.

    `libsentry.so` (707 KB) and `libsentry-android.so` (16 KB) are the Sentry
    NDK: a signal handler that catches native crashes, walks the stack and
    writes envelopes. Nothing loads them - `io/sentry/ndk/SentryNdk.<clinit>`
    is the only thing that calls `loadNativeLibraries`, and `SentryNdk` is
    referenced from nowhere outside the SDK, so the class never initialises.
    They are dead weight, and dead weight that is a crash-reporting stack is
    worth not shipping at all.

    Deleting a `.so` is only safe if nothing can call `System.loadLibrary` for
    it, so this also no-ops the loader. Without that, a future bundle that
    *did* touch `SentryNdk` would get an `UnsatisfiedLinkError` rethrown out
    of `loadNativeLibraries` (its catch-all rethrows) instead of quietly doing
    nothing. The loader also loads "log", which is Android's own `liblog.so` -
    a system library, not ours, so only the two sentry names are removed.
    """
    print("=== SENTRY NATIVE LIBS ===")
    global ok, fail

    loader = sfile(base, "io/sentry/ndk/SentryNdk.smali")
    if os.path.exists(loader):
        src = rd(loader)
        marker = ".method public static declared-synchronized loadNativeLibraries()V"
        if marker in src:
            if "Patch: native libs removed" in src:
                print("  [SKIP] loadNativeLibraries already disarmed")
            else:
                i = src.index(marker)
                e = src.index(".end method", i) + len(".end method")
                wr(
                    loader,
                    src[:i]
                    + marker
                    + "\n    .locals 0\n\n"
                    + "    # Patch: native libs removed - no-op so that a\n"
                    + "    # System.loadLibrary for a deleted .so can never throw.\n"
                    + "    return-void\n"
                    + ".end method"
                    + src[e:],
                )
                print("  [OK] SentryNdk.loadNativeLibraries -> no-op")
    else:
        print("  [SKIP] SentryNdk.smali missing")

    removed = 0
    for abi in os.listdir(os.path.join(base, "lib")) if os.path.isdir(
        os.path.join(base, "lib")
    ) else []:
        for name in ("libsentry.so", "libsentry-android.so"):
            f = os.path.join(base, "lib", abi, name)
            if os.path.exists(f):
                os.remove(f)
                removed += 1
    if removed:
        print(f"  [OK] removed {removed} sentry .so file(s)")
    else:
        print("  [SKIP] no sentry .so files present")

    # apktool carries files it does not understand through `unknown/`. These
    # are all Sentry build metadata: none is read at runtime.
    junk = [
        "unknown/io/sentry",
        "unknown/META-INF/io/sentry",
        "unknown/META-INF/native-image/io.sentry",
        "unknown/META-INF/sentry-android-replay_release.kotlin_module",
    ]
    n = 0
    for rel in junk:
        t = os.path.join(base, rel)
        if os.path.isdir(t):
            shutil.rmtree(t)
            n += 1
        elif os.path.exists(t):
            os.remove(t)
            n += 1
    print(f"  [OK] removed {n} sentry metadata entr(ies) from unknown/")


def patch_sentry_breadcrumb_facade(base):
    """Sever the Sentry breadcrumb facade, not just Discord's gate.

    `CrashReporting.isDisabled()` is forced true, and it gates `CrashReporting`
    `init` — so `SentryAndroid.init` never runs and the Hub keeps no client,
    which makes every capture a no-op. That is the *behavioural* kill, and it
    rests on one app-level boolean that Discord could rename or repurpose in
    any release.

    Three call sites bypass `CrashReporting` entirely and reach the SDK facade
    directly, so the boolean never sees them:

        com.discord.view.ReactViewManagerOverride.addView
        com.discord.view.ScreenOverride.startRemovalTransition
        com.discord.view.ScreenOverride.endRemovalTransition

    All three, and `CrashReporting.addBreadcrumb` too, resolve to the same
    method. No-op'ing it there kills the source for every caller at once and
    keeps working regardless of what Discord does to `isDisabled`.

    The facade class is obfuscated and its name moves between releases
    (`Lio/sentry/c4;` here), so it is read out of `CrashReporting`'s own call
    site rather than hardcoded.
    """
    print("=== SENTRY BREADCRUMB FACADE ===")
    crash = sfile(base, "com/discord/crash_reporting/CrashReporting.smali")
    if not os.path.exists(crash):
        print("  [SKIP] CrashReporting.smali missing")
        return
    m = re.search(
        r"L(io/sentry/[A-Za-z0-9$]+);->([A-Za-z0-9$]+)"
        r"\(Lio/sentry/Breadcrumb;\)V",
        rd(crash),
    )
    if not m:
        print("  [SKIP] no Sentry breadcrumb call site found in CrashReporting")
        return
    cls, meth = m.group(1), m.group(2)
    path = sfile(base, cls + ".smali")
    if not os.path.exists(path):
        print(f"  [FAIL] Sentry facade class not found: {cls}")
        fail += 1
        return
    s = rd(path)
    marker = None
    for line in s.split("\n"):
        if (
            line.startswith(".method")
            and f"{meth}(Lio/sentry/Breadcrumb;)V" in line
            and "static" in line
        ):
            marker = line.strip()
            break
    if marker is None:
        print(f"  [SKIP] {cls}->{meth}(Breadcrumb) not found")
        return
    if "Patch: breadcrumbs are dead" in s:
        print("  [SKIP] Sentry breadcrumb facade already stubbed")
        return
    global ok
    noop(path, marker, f"{cls}->{meth}(Breadcrumb) (all breadcrumb sources)")
    print(
        "  [OK] SDK breadcrumb facade no-op'd - covers the three view call "
        "sites that bypass CrashReporting.isDisabled()"
    )


# The native engine's log level. 0 is NOT a Discord constant - only
# LOGLEVEL_DEBUG (1) and LOGLEVEL_DEFAULT (2) are defined - and it is passed
# straight into `nativeCreateInstance` with no validation. The patch's own
# docstring flagged it as unproven; it is now the prime suspect for the
# instant camera crash. Off until it can be tested on hardware.
DISCORD_LOWER_MEDIA_LOGLEVEL = opt_bool("DISCORD_LOWER_MEDIA_LOGLEVEL", False)


def patch_media_log_level(base):
    """Drop the native media engine's log level.

    `MediaEngine.getEngine` constructs the native engine as
    `new NativeEngine(context, 2)`, and `NativeEngine` labels those values
    `LOGLEVEL_DEBUG = 1` / `LOGLEVEL_DEFAULT = 2` before passing the int
    straight into `nativeCreateInstance(ctx, egl, logLevel, offloadAdmControls)`.

    Level 2 is not quiet. On a device capture the media engine emitted ~700
    lines per session at INFO, and the loudest is emitted **once per
    participant per second**:

        [default] Inbound stats for user: 245606353391976449, audio ssrc: 30740,
        packets received: 1855, lost: 0, ... resolution: 0 x 0, freeze ...

    That is ~400 characters per user per second written to the system log
    buffer from a media thread. Two reasons to want it gone:

      * **Privacy** — it puts Discord user IDs (snowflakes) of everyone in the
        call into logcat, where any process with log access can read them.
        For a build whose whole point is not leaking data, that is the wrong
        default.
      * **Cost** — continuous logcat writes from the audio/video threads
        during a call, on a device already shown to be resource-starved in
        calls (`reference/VOICECALL_FREEZE.md`).

    The change sets the argument to 0. **0 is not a named constant** — Discord
    only defines 1 and 2 — so the semantics of "below DEBUG" are inferred, not
    documented. It is the conventional encoding for "no logging", but it is
    not proven, so this is deliberately easy to revert: delete the call from
    `main()`. Validate with a real call: `adb logcat --pid=$(pidof com.discord)`
    should go quiet during a call, and audio/video must still work.
    """
    print("=== MEDIA ENGINE LOG LEVEL (off by default) ===")
    if not DISCORD_LOWER_MEDIA_LOGLEVEL:
        print(
            "  [SKIP] native log level left at LOGLEVEL_DEFAULT (2). Passing 0 "
            "is an UNDOCUMENTED value straight into nativeCreateInstance and is "
            "the prime suspect for the instant camera crash - see "
            "reference/CAMERA_CRASH.md"
        )
        return
    # 354.1 builds the engine in `cd/o`; the class name is R8 output, but the
    # `new NativeEngine(context, 2)` shape and its `const/4 v3, 0x2` are not.
    path = sfile(base, "cd/o.smali")
    if not os.path.exists(path):
        print("  [SKIP] native engine construction site not found")
        return
    s = rd(path)
    if "Patch: silence the native media engine" in s:
        print("  [SKIP] media log level already lowered")
        return
    old = """    iget-object v2, p0, Lcom/discord/media/engine/MediaEngine;->context:Landroid/content/Context;

    .line 57
    .line 58
    const/4 v3, 0x2

    .line 59
    invoke-direct {v1, v2, v3}, Lcom/discord/native/engine/NativeEngine;-><init>(Landroid/content/Context;I)V"""
    new = """    iget-object v2, p0, Lcom/discord/media/engine/MediaEngine;->context:Landroid/content/Context;

    .line 57
    .line 58
    # Patch: silence the native media engine - level 2 logs per-user inbound
    # stats every second, including user IDs.
    const/4 v3, 0x0

    .line 59
    invoke-direct {v1, v2, v3}, Lcom/discord/native/engine/NativeEngine;-><init>(Landroid/content/Context;I)V"""
    if old not in s:
        print("  [SKIP] NativeEngine construction anchor not found")
        return
    wr(path, s.replace(old, new, 1))
    print("  [OK] native media engine log level 2 -> 0 (quiet during calls)")


def patch_call_path_overhead(base):
    """Strip work that runs on the voice/video call path and during stalls.

    Two of these are JNI entry points the native layers call directly, so the
    `CrashReporting.isDisabled()` gate never sees them — they reach the Sentry
    facade on their own:

      * `WebrtcCrashReporting.reportWebrtcException` — native WebRTC calls this
        on an exception, and it goes straight to `Sentry.captureException`.
        It has no DEX callers at all, which is why a reference search misses it.
      * `CrashReporting.libdiscoreEmitRustPanic` — the Rust panic reporter.

    The worst of them is the profiler pair. `findSampleTrace` enables the Hermes
    sampling profiler, dumps a `.cpuprofile` to disk, reads the whole file back
    and JSON-parses it — and it is only reached from
    `JSWatchdogManager.saveStallReport`, i.e. *because the JS thread has just
    stalled*. It amplifies a stall at the exact moment the app can least afford
    it. Returning null leaves the stall report itself intact, just without the
    profile.

    `ReleaseProfiler.startProfiling` is the JS-callable switch for the same
    profiler and is declared `isBlockingSynchronousMethod`, so it runs inline on
    the JS thread. Refusing to start it leaves `stopProfiling` nothing to dump;
    `stopProfiling` is left alone deliberately, because JS awaits its promise
    and a half-stubbed version would hang the caller.
    """
    print("=== CALL-PATH OVERHEAD (voice/video + stall path) ===")
    for rel, marker, label, ret, retval in [
        (
            "com/discord/js_watchdog/HermesSamplingProfilerUtil.smali",
            ".method public final findSampleTrace(Ljava/io/File;)Ljava/lang/String;",
            "HermesSamplingProfilerUtil.findSampleTrace (profiler dump + JSON parse on stall)",
            "Ljava/lang/String;",
            'const/4 v0, 0x0',
        ),
        (
            "com/releaseprofiler/ReleaseProfilerModule.smali",
            ".method public final startProfiling()Z",
            "ReleaseProfilerModule.startProfiling (JS-thread Hermes profiler switch)",
            "Z",
            "const/4 v0, 0x0",
        ),
        (
            "com/discord/crash_reporting/WebrtcCrashReporting.smali",
            ".method public static reportWebrtcException(Ljava/lang/Throwable;)Ljava/lang/String;",
            "WebrtcCrashReporting.reportWebrtcException (JNI -> Sentry.captureException)",
            "Ljava/lang/String;",
            'const-string v0, ""',
        ),
        (
            "com/discord/crash_reporting/CrashReporting.smali",
            ".method public static final libdiscoreEmitRustPanic(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            "CrashReporting.libdiscoreEmitRustPanic (JNI -> Sentry)",
            "Ljava/lang/String;",
            'const-string v0, ""',
        ),
        (
            "com/discord/crash_reporting/PerformanceTracing.smali",
            ".method private final startTransaction(Lcom/discord/crash_reporting/TraceTransaction;)V",
            "PerformanceTracing.startTransaction",
            "V",
            "",
        ),
        # One EglRenderer per rendered video sink, each on its own HandlerThread,
        # each reposting this every 4 s *unconditionally and forever*. Upstream
        # WebRTC stops after the first rendered frame; this fork does not. Every
        # tick builds a DecimalFormat, two average-time strings and two
        # StringBuilders, and the result goes to org.webrtc.Logging.d, which
        # nothing in the app ever enables (no caller of enableLogToDebugOutput).
        # So the whole cost is discarded. In a group video call this scales with
        # the number of visible tiles.
        (
            # 354.1 dropped Discord's EglRenderer fork; the stock WebRTC
            # renderer keeps the same 4-second stats callback.
            "org/webrtc/EglRenderer.smali",
            ".method private logStatistics()V",
            "EglRenderer.logStatistics (4 s self-reposting stats timer per video tile)",
            "V",
            "",
        ),
    ]:
        target = sfile(base, rel)
        if not os.path.exists(target):
            print(f"  [SKIP] {label}: target removed in 354.1")
            continue
        noop(target, marker, label, ret=ret, retval=retval)


def patch_3541_experiments(base):
    """Pin the native experiment arms 354.1 moved out of NativeExperiments.

    354.1 removes `com.discord.native_experiments.NativeExperiments` and
    resolves the same experiments through `me.l`'s lazy fields, whose
    initialisers are the `me.j` switch. Pinning that switch restores the arms
    the 349.5 suite selected: RMLE treatment 3 (the accumulated-raw-props,
    props-reconciliation and pull-model mounting mode), Fresco 1 (the 70%
    cache-reduction arm), Hermes 1, ChatMosaic 1 (shared pool), and
    JankPerScreen 0 (CONTROL, which turns per-screen jank tracking off).
    """
    global ok, fail
    path = sfile(base, "me/j.smali")
    if not os.path.exists(path):
        return False
    s = rd(path)
    if "Patch: 354.1 experiment pins" in s:
        print("  [OK] 354.1 native experiments already pinned")
        return True
    marker = ".method public final invoke()Ljava/lang/Object;"
    if marker not in s:
        print("  [SKIP] 354.1 experiment switch (me/j invoke not found)")
        return True
    start = s.index(marker)
    end = s.index(".end method", start)
    body = s[start:end]
    required = (
        "iget v0, p0, Lme/j;->d:I",
        "iget-object v5, p0, Lme/j;->e:Lme/l;",
        "    packed-switch v0, :pswitch_data_0",
    )
    missing = [line for line in required if line not in body]
    if missing:
        print(
            "  [SKIP] 354.1 experiment switch shape changed: "
            + "; ".join(missing)
        )
        return True

    dispatch = """    # Patch: 354.1 experiment pins. Case 0 is the RMLE mounting mode;
    # 1-4 are Hermes, Fresco, ChatMosaic and JankPerScreen.
    sparse-switch v0, :pinned_switch_data

    packed-switch v0, :pswitch_data_0"""
    body = body.replace(
        "    packed-switch v0, :pswitch_data_0", dispatch, 1
    )

    pinned = """
    :pinned_rmle
    const/4 v0, 0x3
    invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
    move-result-object v0
    return-object v0

    :pinned_hermes
    sget-object v0, Lme/d;->c:Lme/e;
    return-object v0

    :pinned_fresco
    const/4 v0, 0x1
    const/16 v1, 0xc
    invoke-static {v0, v1}, Lme/c;->a(II)Lme/c;
    move-result-object v0
    return-object v0

    :pinned_chatmosaic
    new-instance v0, Lme/b;
    const/4 v1, 0x1
    invoke-direct {v0, v1, v1}, Lme/b;-><init>(IZ)V
    return-object v0

    :pinned_jank
    sget-object v0, Lme/g;->b:Lme/h;
    return-object v0

    :pinned_switch_data
    .sparse-switch
        0x0 -> :pinned_rmle
        0x1 -> :pinned_hermes
        0x2 -> :pinned_fresco
        0x3 -> :pinned_chatmosaic
        0x4 -> :pinned_jank
    .end sparse-switch

    :pswitch_data_0"""
    body = body.replace("    :pswitch_data_0", pinned, 1)
    wr(path, s[:start] + body + s[end:])
    ok += 1
    print(
        "  [OK] 354.1 experiments pinned: RMLE 3, Fresco 1, Hermes 1, "
        "ChatMosaic 1, JankPerScreen CONTROL"
    )
    return True


def main():
    base = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_BASE)

    print("=== OPTIONS ===")
    try:
        with open(os.path.join(HERE, "manifest.json"), encoding="utf-8") as f:
            manifest = json.load(f)
        for patch in manifest.get("patches", []):
            if patch.get("type") != "option":
                continue
            key = patch.get("option")
            state = "on " if opt_bool(key, patch.get("default")) else "off"
            print(f"  [{state}] {patch['name']} ({key})")
    except (OSError, ValueError):
        print("  [SKIP] option manifest unreadable")

    print("=== MANIFEST ===")
    p = f"{base}/AndroidManifest.xml"
    s = rd(p)
    before = s
    s = re.sub(
        r' android:requiredSplitTypes="[^"]*" android:splitTypes="[^"]*"', "", s
    )
    s = re.sub(
        r'    <uses-permission android:name="com.google.android.gms.permission.AD_ID"/>\n',
        "",
        s,
    )
    # Permissions the app no longer has any live use for. Each was verified
    # against the patched tree before being listed - a permission is only
    # removed when nothing reachable references it, because stripping one that
    # is still used turns a working call into a SecurityException.
    #
    #   READ_CONTACTS                       - only reference is the RN
    #       permission-request module; contact sync itself is stubbed out, so
    #       the app never reads the address book. Removing the declaration
    #       makes that structural rather than dependent on our stub holding.
    #   BIND_GET_INSTALL_REFERRER_SERVICE   - referenced only by the Play
    #       Install Referrer SDK and AppsFlyer, both dead: InstallReferrerModule
    #       is stubbed and AppsFlyerLib.start() has no callers anywhere, so
    #       nothing ever binds the service.
    #   ACCESS_ADSERVICES_ATTRIBUTION       - Privacy Sandbox attribution.
    #   GET_COMMON_DATA (Huawei)            - 0 references in the dex.
    #   READ_APP_INFO (Samsung)             - 0 references in the dex.
    # The last three are dead declarations with no code behind them at all.
    for perm in [
        "android.permission.READ_CONTACTS",
        "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
        "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
        "com.huawei.appmarket.service.commondata.permission.GET_COMMON_DATA",
        "com.samsung.android.mapsagent.permission.READ_APP_INFO",
    ]:
        s = re.sub(
            rf'\s*<uses-permission[^>]*android:name="{re.escape(perm)}"[^>]*/>',
            "",
            s,
        )
    for m in [
        "com.android.vending.splits.required",
        "com.android.vending.splits",
        "com.android.vending.derived.apk.id",
    ]:
        s = re.sub(
            rf'    <meta-data android:name="{re.escape(m)}"[^>]*/>\n', "", s
        )
    s = s.replace(
        'android:extractNativeLibs="false"', 'android:extractNativeLibs="true"'
    )
    # Sentry providers: remove entirely.
    for prov in [
        "io.sentry.android.core.SentryInitProvider",
        "io.sentry.android.core.SentryPerformanceProvider",
    ]:
        s = re.sub(
            rf'<provider[^>]*android:name="{re.escape(prov)}"[^>]*/>\n', "", s
        )
    # Google Analytics components: inert.
    for nm in ["AnalyticsReceiver", "AnalyticsService", "AnalyticsJobService"]:
        s = s.replace(
            f'android:enabled="true" android:exported="false" android:name="com.google.android.gms.analytics.{nm}"',
            f'android:enabled="false" android:exported="false" android:name="com.google.android.gms.analytics.{nm}"',
        )
    # DiscordRpcService is exported with no permission and does no caller check,
    # so any installed app can bind it and push arbitrary presence frames.
    s = s.replace(
        '<service android:exported="true" android:name="com.discord.socialrpc.DiscordRpcService">',
        '<service android:exported="false" android:name="com.discord.socialrpc.DiscordRpcService">',
    )
    # AppsFlyer package-visibility query - its only init site is no-op'd.
    s = re.sub(
        r'\s*<intent>\s*<action android:name="com\.appsflyer\.referrer\.INSTALL_PROVIDER"/>\s*</intent>',
        "",
        s,
    )
    # NOTE: firebase datatransport / FCM components are intentionally left
    # untouched so push notification registration keeps working.
    if s != before:
        wr(p, s)
        ok_ = 1
        print(
            "  [OK] manifest cleaned (splits, AD_ID, 5 dead permissions, "
            "sentry providers, GA)"
        )
    else:
        print("  [WARN] manifest unchanged (already clean?)")

    print("=== SENTRY ===")
    # 354.1 obfuscated CrashReporting's method names (`captureException` is now
    # `c`, `addBreadcrumb` is `a`, ...) and dropped `isDisabled()` entirely.
    # Descriptors are stable, so every event-producing entry point is stubbed
    # by descriptor. With all of them inert there is nothing left to gate.
    crash = sfile(base, "com/discord/crash_reporting/CrashReporting.smali")
    for sig, label in [
        ("(Ljava/lang/Throwable;Z)V", "CrashReporting.captureException"),
        (
            "(Ljava/lang/String;Ljava/lang/String;"
            "Lcom/discord/crash_reporting/j;)V",
            "CrashReporting.captureMessage(tag,msg,level)",
        ),
        (
            "(Lcom/discord/crash_reporting/i;Ljava/lang/String;"
            "Ljava/lang/String;)V",
            "CrashReporting.captureMessage(tag,exception)",
        ),
        (
            "(Landroid/content/Context;Lio/sentry/s4;)V",
            "CrashReporting.persistLastCrashInfo",
        ),
        (
            "(Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;"
            "Lcom/discord/crash_reporting/h;Z)V",
            "CrashReporting.addBreadcrumb",
        ),
    ]:
        noop_sig(crash, sig, label)
    method_replace(
        sfile(base, "io/sentry/react/RNSentryModuleImpl.smali"),
        ".method public initNativeSdk(Lcom/facebook/react/bridge/ReadableMap;Lcom/facebook/react/bridge/Promise;)V",
        """.method public initNativeSdk(Lcom/facebook/react/bridge/ReadableMap;Lcom/facebook/react/bridge/Promise;)V
    .locals 1
    sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
    invoke-interface {p2, v0}, Lcom/facebook/react/bridge/Promise;->resolve(Ljava/lang/Object;)V
    return-void
.end method""",
        "RNSentryModuleImpl.initNativeSdk -> false (JS SDK self-disables)",
    )
    # No envelope ever leaves the JS side, even if the SDK is somehow active.
    method_replace(
        sfile(base, "io/sentry/react/RNSentryModuleImpl.smali"),
        ".method public captureEnvelope(Ljava/lang/String;Lcom/facebook/react/bridge/ReadableMap;Lcom/facebook/react/bridge/Promise;)V",
        """.method public captureEnvelope(Ljava/lang/String;Lcom/facebook/react/bridge/ReadableMap;Lcom/facebook/react/bridge/Promise;)V
    .locals 1
    # Patch: telemetry off - drop the envelope.
    const/4 v0, 0x0
    invoke-interface {p3, v0}, Lcom/facebook/react/bridge/Promise;->resolve(Ljava/lang/Object;)V
    return-void
.end method""",
        "RNSentryModuleImpl.captureEnvelope",
    )

    dsns = [
        "9a42ef460144a03b30c8b2d5321cfe11",
        "70545531dfe34835bf4dd0996821e8b6",
        "90509cba01573ee4e14a2f5e15aee5ca",
    ]
    for rel in [
        "com/discord/client_info/BuildConfig.smali",
        "com/discord/client_info/ClientInfo.smali",
        "com/discord/client_info/react/ClientInfoModule.smali",
        "com/discord/crash_reporting/CrashReporting.smali",
    ]:
        p = sfile(base, rel)
        if not os.path.exists(p):
            continue
        s = rd(p)
        c = 0
        for d in dsns:
            o = f'"https://{d}@o64374.ingest.sentry.io/5992375"'
            if o in s:
                s = s.replace(o, '""')
                c += 1
        if c:
            wr(p, s)
            print(f"  [OK] blanked {c} DSN(s) in {rel}")

    print("=== APPSFLYER / ADS / REFERRER ===")
    # `DeepLinks.init` is gone (the whole deep-link helper was refactored).
    # 354.1 initialises AppsFlyer from MainApplication lambdas, so the SDK's
    # own concrete entry points are stubbed instead. `AFa1uSDK` is the only
    # subclass of AppsFlyerLib and `getInstance()` returns it.
    af = sfile(base, "com/appsflyer/internal/AFa1uSDK.smali")
    method_replace_sig(
        af,
        "(Ljava/lang/String;Lcom/appsflyer/AppsFlyerConversionListener;"
        "Landroid/content/Context;)Lcom/appsflyer/AppsFlyerLib;",
        lambda decl: f"""{decl}
    .locals 0
    # Patch: attribution SDK off - return `this` without initialising.
    return-object p0
.end method""",
        "AppsFlyerLib.init -> no-op",
    )
    noop_sig(af, "(Landroid/content/Context;)V", "AppsFlyerLib.start")
    noop_sig(
        af,
        "(Lcom/appsflyer/deeplink/DeepLinkListener;)V",
        "AppsFlyerLib.subscribeForDeepLink",
    )
    method_replace(
        sfile(base, "com/discord/ads/AdsModule.smali"),
        ".method public getGoogleAdvertisingId(Lcom/facebook/react/bridge/Promise;)V",
        """.method public getGoogleAdvertisingId(Lcom/facebook/react/bridge/Promise;)V
    .locals 1
    invoke-direct {p0, p1}, Lcom/discord/ads/AdsModule;->resolveWithNullId(Lcom/facebook/react/bridge/Promise;)V
    return-void
.end method""",
        "AdsModule.getGoogleAdvertisingId",
    )
    method_replace(
        sfile(base, "com/discord/analytics/InstallReferrerModule.smali"),
        ".method public final get(Lcom/facebook/react/bridge/Promise;)V",
        """.method public final get(Lcom/facebook/react/bridge/Promise;)V
    .locals 1
    const/4 v0, 0x0
    invoke-interface {p1, v0}, Lcom/facebook/react/bridge/Promise;->resolve(Ljava/lang/Object;)V
    return-void
.end method""",
        "InstallReferrerModule.get",
    )

    print("=== WATCHDOG / TELEMETRY RING / TOUCH ===")
    # The guard below suppresses a stall report only when the gap since the
    # last JS ping is 500 ms or less. `updateStallTime` is driven by a
    # `scheduleAtFixedRate(task, 1500, 1000)` timer, and the timestamp it is
    # handed is the *previous ping*, not the previous timer tick — so how much
    # this actually filters depends on the JS ping cadence, which we have not
    # measured. Treat it as "skips short gaps", not as "only reports real
    # stalls". The expensive half of this path is `findSampleTrace`, which is
    # stubbed outright in `patch_call_path_overhead`.
    p = sfile(base, "com/discord/js_watchdog/JSWatchdogManager.smali")
    if os.path.exists(p) and ":cond_no_stall" in rd(p):
        print("  [SKIP] JSWatchdogManager guard (already applied)")
    elif os.path.exists(
        p
    ) and ".method private final updateStallTime(JLjava/lang/String;Z)V" in rd(
        p
    ):
        s = rd(p)
        a = s.index(
            ".method private final updateStallTime(JLjava/lang/String;Z)V"
        )
        e = s.index(".end method", a) + len(".end method")
        body = s[a:e]
        ms = [mm.start() for mm in re.finditer(r"sub-long/2addr v0, p1", body)]
        if len(ms) == 2:
            ins = ms[1] + len("sub-long/2addr v0, p1")
            guard = """
    const-wide/16 v2, 0x0
    cmp-long v2, v0, v2
    if-lez v2, :cond_no_stall
"""
            body = body[:ins] + guard + body[ins:]
            r = body.rindex("return-void")
            body = (
                body[:r]
                + ":cond_no_stall\n\n    return-void"
                + body[r + len("return-void") :]
            )
            wr(p, s[:a] + body + s[e:])
            ok_ = 1
            print("  [OK] JSWatchdogManager.updateStallTime guard")
        else:
            print("  [SKIP] JSWatchdogManager guard (pattern changed)")
    else:
        print("  [SKIP] JSWatchdogManager (not present)")
    # 354.1 folded TelemetryRing into obfuscated classes. `u` holds the public
    # API: `a(...)` is append() and `e(...)` is init(). Both take descriptor
    # targets so the obfuscated names do not matter.
    ring = sfile(base, "com/discord/crash_reporting/u.smali")
    if os.path.exists(ring):
        noop_sig(
            ring,
            "(Ljava/lang/String;JLjava/lang/String;Ljava/util/Map;"
            "Ljava/util/List;)V",
            "TelemetryRing.append",
        )
        noop_sig(
            ring,
            "(Landroid/content/ContextWrapper;Lcom/discord/crash_reporting/a0;)V",
            "TelemetryRing.init (dead DB open + full scan)",
        )
    else:
        print("  [SKIP] TelemetryRing: obfuscated class not found")
    noop(
        sfile(
            base, "com/discord/analytics/touch/TouchEventAnalyticsModule.smali"
        ),
        ".method public enableTouchLogging()V",
        "enableTouchLogging",
    )

    print("=== ROOT TELEMETRY MODULES ===")
    for rel, marker, label in [
        (
            "smali/com/discord/crash_reporting/TelemetryRingModule.smali",
            ".method public append(Ljava/lang/String;DLjava/lang/String;Lcom/facebook/react/bridge/ReadableMap;Lcom/facebook/react/bridge/ReadableArray;)V",
            "TelemetryRingModule.append",
        ),
        (
            "smali/com/discord/crash_reporting/TelemetryRingModule.smali",
            ".method public clear()V",
            "TelemetryRingModule.clear",
        ),
        (
            "smali/com/discord/metric_monitor/MetricMonitorModule.smali",
            ".method public addListener(Ljava/lang/String;)V",
            "MetricMonitorModule.addListener",
        ),
        (
            "smali/com/discord/metric_monitor/MetricMonitorModule.smali",
            ".method public removeListeners(D)V",
            "MetricMonitorModule.removeListeners",
        ),
        # MonitoringAgent is gone from 354.1's dex: the metric-monitor package
        # now ships only its React module, whose listener methods are stubbed
        # below. The JS-side `MonitoringAgent.increment` Hermes function is
        # still stubbed in patch_js, so the collection path stays dead.
        (
            "smali/com/discord/jank_stats/JankSessionModule.smali",
            ".method public hydrateLaunchId(Ljava/lang/String;)V",
            "JankSessionModule.hydrateLaunchId",
        ),
        (
            "smali/com/discord/jank_stats/JankSessionModule.smali",
            ".method public ackReports(Lcom/facebook/react/bridge/ReadableArray;)V",
            "JankSessionModule.ackReports",
        ),
        (
            "smali/com/discord/jank_stats/JankStatsModule.smali",
            ".method public startTracking()V",
            "JankStatsModule.startTracking",
        ),
        (
            "smali/com/discord/jank_stats/JankStatsModule.smali",
            ".method public stopTracking()V",
            "JankStatsModule.stopTracking",
        ),
        (
            "com/discord/tti_manager/TTIManagerModule.smali",
            ".method public trackTTILogged()V",
            "TTIManagerModule.trackTTILogged",
        ),
        (
            "com/discord/tti_manager/TTIManagerModule.smali",
            ".method public logToDevice(Ljava/lang/String;)V",
            "TTIManagerModule.logToDevice",
        ),
        (
            "com/discord/tti_manager/TTIManagerModule.smali",
            ".method public reportFullyDrawn()V",
            "TTIManagerModule.reportFullyDrawn",
        ),
        (
            "smali/com/discord/push_notification_monitor/PushNotificationMonitorModule.smali",
            ".method public clearLogs()V",
            "PushNotificationMonitorModule.clearLogs",
        ),
    ]:
        noop(
            sfile(
                base, rel.split("/", 1)[1] if rel.startswith("smali") else rel
            ),
            marker,
            label,
        )

    # Promise-returning telemetry readers -> empty containers
    method_replace(
        sfile(base, "com/discord/crash_reporting/TelemetryRingModule.smali"),
        ".method public snapshot(Lcom/facebook/react/bridge/ReadableArray;DLcom/facebook/react/bridge/ReadableMap;Ljava/lang/Double;Lcom/facebook/react/bridge/Promise;)V",
        """.method public snapshot(Lcom/facebook/react/bridge/ReadableArray;DLcom/facebook/react/bridge/ReadableMap;Ljava/lang/Double;Lcom/facebook/react/bridge/Promise;)V
    .locals 1
    new-instance v0, Lcom/facebook/react/bridge/WritableNativeMap;
    invoke-direct {v0}, Lcom/facebook/react/bridge/WritableNativeMap;-><init>()V
    invoke-interface {p5, v0}, Lcom/facebook/react/bridge/Promise;->resolve(Ljava/lang/Object;)V
    return-void
.end method""",
        "TelemetryRingModule.snapshot",
    )
    method_replace(
        sfile(base, "com/discord/jank_stats/JankSessionModule.smali"),
        ".method public getPendingReports(Lcom/facebook/react/bridge/Promise;)V",
        """.method public getPendingReports(Lcom/facebook/react/bridge/Promise;)V
    .locals 1
    new-instance v0, Lcom/facebook/react/bridge/WritableNativeArray;
    invoke-direct {v0}, Lcom/facebook/react/bridge/WritableNativeArray;-><init>()V
    invoke-interface {p1, v0}, Lcom/facebook/react/bridge/Promise;->resolve(Ljava/lang/Object;)V
    return-void
.end method""",
        "JankSessionModule.getPendingReports",
    )
    method_replace(
        sfile(base, "com/discord/jank_stats/JankStatsModule.smali"),
        ".method public requestReport()Lcom/facebook/react/bridge/WritableMap;",
        """.method public requestReport()Lcom/facebook/react/bridge/WritableMap;
    .locals 1
    new-instance v0, Lcom/facebook/react/bridge/WritableNativeMap;
    invoke-direct {v0}, Lcom/facebook/react/bridge/WritableNativeMap;-><init>()V
    return-object v0
.end method""",
        "JankStatsModule.requestReport",
    )
    method_replace(
        sfile(
            base,
            "com/discord/push_notification_monitor/PushNotificationMonitorModule.smali",
        ),
        ".method public getPushNotificationLogs(Ljava/lang/String;Lcom/facebook/react/bridge/Promise;)V",
        """.method public getPushNotificationLogs(Ljava/lang/String;Lcom/facebook/react/bridge/Promise;)V
    .locals 1
    new-instance v0, Lcom/facebook/react/bridge/WritableNativeArray;
    invoke-direct {v0}, Lcom/facebook/react/bridge/WritableNativeArray;-><init>()V
    invoke-interface {p2, v0}, Lcom/facebook/react/bridge/Promise;->resolve(Ljava/lang/Object;)V
    return-void
.end method""",
        "PushNotificationMonitorModule.getPushNotificationLogs",
    )

    print("=== LOG NOISE (perf) ===")
    # 354.1 removed `com.discord.logging.Log`; its remaining log facade is
    # `yc/a`, whose methods take (tag, message, throwable) and forward to
    # Timber. The level split is no longer visible from the names, and the
    # debug-level Hermes logger stubs already remove the expensive JS side, so
    # this native half is retired rather than guessed at.
    print("  [SKIP] Log.d facade was removed in 354.1 (see yc/a)")

    print("=== AUDIT ROUND 2: logcat child / per-frame jank / dead DB ===")
    # Crash reporting keeps a child /system/bin/logcat process alive for the
    # whole app lifetime. The starter is now the Runnable in `wa/e`; the
    # bug-report path's short-lived logcat lives elsewhere and is left alone.
    logcat = sfile(base, "wa/e.smali")
    if os.path.exists(logcat):
        noop(
            logcat,
            ".method public final invoke()Ljava/lang/Object;",
            "SystemLogUtils permanent logcat child process",
        )
    else:
        print("  [SKIP] logcat child starter not found")
    # The per-frame FrameMetrics/JankStats recorders are gone from this dex;
    # their JS-side consumers are already stubbed in patch_js.
    print("  [SKIP] FrameMetricsAggregator/JankSessionRecorder removed in 354.1")

    print("=== AUDIT ROUND 2: shared OkHttp disk cache ===")
    # React Native 354.1 no longer ships OkHttpClientProvider; its client is
    # built in `oa/a` from an obfuscated builder. There is no per-provider
    # cache to memoise any more, so the patch is retired.
    print("  [SKIP] OkHttpClientProvider removed in 354.1 (cache patch retired)")

    print(
        "=== LAUNCH CRASH FIX: interceptor must not short-circuit as a network interceptor ==="
    )
    patch_interceptor_registration(base)

    print("=== OTA BUNDLE (so JS-side patches actually take effect) ===")
    patch_ota_bundle(base)
    # ActivityDelegate (blocking OTA recovery) and JankSessionRecorder are no
    # longer in 354.1's dex; the OTA host/pref-key pins above are what still
    # matter, and they are applied unconditionally.
    print("  [SKIP] ActivityDelegate blocking-OTA path removed in 354.1")
    patch_crash_reporting_clinit(base)
    print("  [SKIP] JankSessionRecorder removed in 354.1")

    print("=== LINKS: system browser instead of the in-app tab ===")
    if EXTERNAL_BROWSER:
        patch_external_browser(base)
    else:
        print("  [SKIP] EXTERNAL_BROWSER=False - links stay in the in-app tab")

    print("=== JS SENTRY DSN ===")
    patch_js_sentry_dsn(base)

    print("=== JS TELEMETRY / LOGGING (Hermes bytecode) ===")
    patch_js(base)
    print("=== JS OPTIONAL UI / BEHAVIOUR (Hermes bytecode) ===")
    patch_js_extra(base)
    print("=== JS FEATURE REMOVAL (body edits, not stubs) ===")
    patch_js_feature_removal(base)

    print("=== JS DIAGNOSTIC POLLS (NetStats / RN Sentry bridge) ===")
    # `modules/network/NetStats.android.tsx` polls the native module every few
    # seconds and logs "[NetStats]: Updating Network Info {...}" into Discord's
    # JS debug buffer. Never calling the callback stops both the logging and the
    # collection: no network/resource stats are gathered at all. The other
    # DeviceResourceUsageManagerModule getters are left alone (cpu core count is
    # used for pool sizing).
    method_replace(
        sfile(
            base,
            "com/discord/resource_usage/DeviceResourceUsageManagerModule.smali",
        ),
        ".method public final getNetworkUsage(Lcom/facebook/react/bridge/Callback;)V",
        """.method public final getNetworkUsage(Lcom/facebook/react/bridge/Callback;)V
    .locals 0
    # Patch: resource telemetry off - the JS poll never gets an answer, so
    # nothing is collected and "[NetStats]: Updating Network Info" is not logged.
    return-void
.end method""",
        "DeviceResourceUsageManagerModule.getNetworkUsage (hang -> no NetStats poll)",
    )
    # The byte-counter recorder class is gone in 354.1 (only its React module
    # survives). Its JS reader is stubbed by `DeviceResourceUsageManagerModule`
    # above, and the rebuilt interceptor no longer increments the counters, so
    # there is nothing left to zero.

    # The RN Sentry bridge: the JS SDK adds every breadcrumb through this
    # method.
    # No-op'ing it keeps breadcrumbs out of the native Sentry scope entirely.
    method_replace(
        sfile(base, "io/sentry/react/RNSentryModuleImpl.smali"),
        ".method public addBreadcrumb(Lcom/facebook/react/bridge/ReadableMap;)V",
        """.method public addBreadcrumb(Lcom/facebook/react/bridge/ReadableMap;)V
    .locals 0
    # Patch: telemetry off - drop JS breadcrumbs at the bridge.
    return-void
.end method""",
        "RNSentryModuleImpl.addBreadcrumb",
    )

    print("=== SYSTRACE / DEBUG INSTRUMENTATION ===")
    # The RN Systrace module is pure debug instrumentation: it emits trace
    # events for the JS bridge. Nothing in the app depends on it.
    systrace = sfile(base, "com/discord/systrace/SystraceModule.smali")
    for marker, label in [
        (
            ".method public beginEvent(Ljava/lang/String;)V",
            "SystraceModule.beginEvent",
        ),
        (".method public endEvent()V", "SystraceModule.endEvent"),
        (
            ".method public counterEvent(Ljava/lang/String;D)V",
            "SystraceModule.counterEvent",
        ),
        (
            ".method public endAsyncEvent(Ljava/lang/String;D)V",
            "SystraceModule.endAsyncEvent",
        ),
    ]:
        noop(systrace, marker, label)
    method_replace(
        systrace,
        ".method public beginAsyncEvent(Ljava/lang/String;)D",
        """.method public beginAsyncEvent(Ljava/lang/String;)D
    .locals 2
    # Patch: debug instrumentation off.
    const-wide/16 v0, 0x0
    return-wide v0
.end method""",
        "SystraceModule.beginAsyncEvent",
    )

    print("=== SENTRY LOGCAT SPAM (crash-reporting entry points) ===")
    # The event-producing methods were stubbed by descriptor in the SENTRY
    # section above; only the JNI entry point and the breadcrumb batch remain
    # to be cut here. The batch is still reachable from libdiscore.
    crash = sfile(base, "com/discord/crash_reporting/CrashReporting.smali")
    noop_sig(
        crash,
        "(Ljava/nio/ByteBuffer;)V",
        "CrashReporting.addBreadcrumbBatchBinary",
    )
    noop(
        crash,
        ".method public static final libdiscoreAddBreadcrumb(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
        "CrashReporting.libdiscoreAddBreadcrumb",
    )

    print("=== AUDIT ROUND 2: privacy / attack surface ===")
    # 354.1 folded the contact helpers into `ta/h`; the React module
    # (`ContactSyncManagerModule`) still calls them. Return an empty map and
    # no photos, so the callback plumbing keeps working but no address book
    # is read.
    contacts = sfile(base, "ta/h.smali")
    method_replace_sig(
        contacts,
        "(Lcom/facebook/react/bridge/ReactApplicationContext;)"
        "Ljava/util/LinkedHashMap;",
        lambda decl: f"""{decl}
    .locals 1
    # Patch: no address book read.
    new-instance v0, Ljava/util/LinkedHashMap;
    invoke-direct {{v0}}, Ljava/util/LinkedHashMap;-><init>()V
    return-object v0
.end method""",
        "Contacts.getContactsMap -> empty (no address book read)",
    )
    noop_sig(
        contacts,
        "(Landroid/net/Uri;Lcom/facebook/react/bridge/"
        "ReactApplicationContext;)Ljava/lang/String;",
        "Contacts.getImageForContactId -> null (no contact photo read)",
        ret="Ljava/lang/String;",
        retval="const/4 v0, 0x0",
    )
    noop_sig(
        contacts,
        "(Lcom/facebook/react/bridge/ReactApplicationContext;"
        "Ljava/lang/String;)Ljava/lang/String;",
        "Contacts.getImageForContactId(id) -> null (no contact photo read)",
        ret="Ljava/lang/String;",
        retval="const/4 v0, 0x0",
    )
    # Installed-app fingerprinting: canOpenUrlScheme probes 23 hard-coded
    # third-party packages. isDiscordVariantInstalled is a separate caller and
    # is left untouched so Discord variant detection keeps working.
    method_replace(
        sfile(base, "com/discord/intents/IntentsModule.smali"),
        ".method public canOpenUrlScheme(Ljava/lang/String;)Z",
        """.method public canOpenUrlScheme(Ljava/lang/String;)Z
    .locals 1
    const/4 v0, 0x0
    return v0
.end method""",
        "IntentsModule.canOpenUrlScheme -> false (no app enumeration)",
    )
    # React Native Fabric: Discord gates four RN flags on a server experiment.
    # These two are React Native's OWN defaults (verified against
    # ReactNativeFeatureFlagsDefaults in this APK), so forcing them on only
    # removes Discord's override. Confirmed on-device that this does not cause
    # the launch crash (that was the network-interceptor 204 short-circuit).
    # 354.1 moved the provider to `ol/c` (the only implementer of
    # ReactNativeFeatureFlagsProvider). Its methods cache a Boolean override;
    # forcing the two RN defaults on is the same edit as before.
    flags = sfile(base, "ol/c.smali")
    for flag in (
        "enableAccumulatedUpdatesInRawPropsAndroid",
        "usePullModelOnAndroid",
    ):
        method_replace(
            flags,
            f".method public final {flag}()Z",
            f""".method public final {flag}()Z
    .locals 1
    const/4 v0, 0x1
    return v0
.end method""",
            f"RN Fabric flag {flag} -> true",
        )

    print("=== EXO ALIASES ===")
    aliases = {
        "exo_controls_fastforward": "exo_icon_fastforward",
        "exo_controls_fullscreen_enter": "exo_icon_fullscreen_enter",
        "exo_controls_fullscreen_exit": "exo_icon_fullscreen_exit",
        "exo_controls_next": "exo_icon_next",
        "exo_controls_pause": "exo_icon_pause",
        "exo_controls_play": "exo_icon_play",
        "exo_controls_previous": "exo_icon_previous",
        "exo_controls_repeat_all": "exo_icon_repeat_all",
        "exo_controls_repeat_off": "exo_icon_repeat_off",
        "exo_controls_repeat_one": "exo_icon_repeat_one",
        "exo_controls_rewind": "exo_icon_rewind",
        "exo_controls_shuffle_off": "exo_icon_shuffle_off",
        "exo_controls_shuffle_on": "exo_icon_shuffle_on",
        "exo_controls_vr": "exo_icon_vr",
        "exo_notification_fastforward": "exo_icon_fastforward",
        "exo_notification_next": "exo_icon_next",
        "exo_notification_pause": "exo_icon_pause",
        "exo_notification_play": "exo_icon_play",
        "exo_notification_previous": "exo_icon_previous",
        "exo_notification_rewind": "exo_icon_rewind",
        "exo_notification_small_icon": "exo_icon_circular_play",
        "exo_notification_stop": "exo_icon_stop",
        "exo_styled_controls_audiotrack": "exo_ic_audiotrack",
        "exo_styled_controls_check": "exo_ic_check",
        "exo_styled_controls_fastforward": "exo_ic_forward",
        "exo_styled_controls_fullscreen_enter": "exo_ic_fullscreen_enter",
        "exo_styled_controls_fullscreen_exit": "exo_ic_fullscreen_exit",
        "exo_styled_controls_next": "exo_ic_skip_next",
        "exo_styled_controls_overflow_hide": "exo_ic_chevron_left",
        "exo_styled_controls_overflow_show": "exo_ic_chevron_right",
        "exo_styled_controls_pause": "exo_ic_pause_circle_filled",
        "exo_styled_controls_play": "exo_ic_play_circle_filled",
        "exo_styled_controls_previous": "exo_ic_skip_previous",
        "exo_styled_controls_repeat_all": "exo_icon_repeat_all",
        "exo_styled_controls_repeat_off": "exo_icon_repeat_off",
        "exo_styled_controls_repeat_one": "exo_icon_repeat_one",
        "exo_styled_controls_rewind": "exo_ic_rewind",
        "exo_styled_controls_settings": "exo_ic_settings",
        "exo_styled_controls_shuffle_off": "exo_icon_shuffle_off",
        "exo_styled_controls_shuffle_on": "exo_icon_shuffle_on",
        "exo_styled_controls_speed": "exo_ic_speed",
        "exo_styled_controls_subtitle_off": "exo_ic_subtitle_off",
        "exo_styled_controls_subtitle_on": "exo_ic_subtitle_on",
        "exo_styled_controls_vr": "exo_icon_vr",
    }
    p = f"{base}/res/values/drawables.xml"
    if os.path.exists(p):
        s = rd(p)
        c = 0
        for n, t in aliases.items():
            o = f'<drawable name="{n}" />'
            r = f'<drawable name="{n}">@drawable/{t}</drawable>'
            if o in s:
                s = s.replace(o, r)
                c += 1
        wr(p, s)
        print(f"  [OK] exo aliases restored ({c})")
    else:
        print("  [SKIP] drawables.xml missing")

    print("=== RESOURCE MONITORS ===")
    # 354.1 removed the DeviceResourceUsageManager / PerformanceTracing /
    # JankStatsAggregator classes from the dex. Their JS consumers are stubbed
    # in patch_js, so the sampling has nothing left to feed.
    print("  [SKIP] native resource/jank monitors removed in 354.1")
    # EmojiCompat: Discord uses it zero times, but AndroidX Startup still
    # initialises it and, after the first activity resumes, posts a load that
    # can reach the Google Fonts provider. 354.1's observer is `t2/j`; stubbing
    # `onResume` keeps the singleton built (so `EmojiCompat.get()` cannot
    # throw) while skipping the load.
    emoji = sfile(base, "t2/j.smali")
    if os.path.exists(emoji):
        noop(
            emoji,
            ".method public final onResume(Landroidx/lifecycle/v;)V",
            "EmojiCompatInitializer load (Google Fonts provider IPC)",
        )
    else:
        print("  [SKIP] EmojiCompat initializer observer not found")

    patch_call_path_overhead(base)
    patch_jank_measurement(base)
    patch_media_callback_visibility(base)
    patch_media_log_level(base)
    # This one was written, documented and *never called* - it sat in the file
    # as dead code while `verify-asks.py` reported the check passing on a build
    # that did not contain it. Wiring it in is the whole fix; the body below is
    # unchanged. Any patch added to this file must be added to this list, which
    # is what `verify-asks.py` should have been checking all along.
    patch_media_scope_supervisor(base)
    # Patches that are implemented but must stay OFF, called through their flag
    # rather than left uncalled. The log then states their state on every
    # build. This is not tidiness: `patch_media_scope_supervisor` above sat in
    # this file with no caller at all, and `verify-asks.py` reported its check
    # passing on a build that did not contain it. A flag nothing consults looks
    # exactly like that, and so does a call someone deleted. Calling them makes
    # "off" a thing the build says out loud.
    for label, enabled, flag, fn, why in [
        (
            "Remove the camera frame-rate log",
            DISCORD_STRIP_CAMERA_FPS_LOG,
            "DISCORD_STRIP_CAMERA_FPS_LOG",
            patch_webrtc_camera_log_spam,
            "the fps string was built every 2 s per camera and written to a sink "
            "nobody reads. The tag constant the freeze-detection branch reuses "
            "is KEPT - removing it is what failed ART verification at class "
            "load and crashed the camera on open (reference/CAMERA_CRASH.md)",
        ),
        (
            "Lower video call resolution",
            DISCORD_LOWER_CAPTURE,
            "DISCORD_LOWER_CAPTURE",
            patch_video_capture_resolution,
            "capture and encode are built as a pair from the same budget; "
            "lowering capture alone makes capture < encode and the engine "
            "aborts rather than upscale (confirmed on device)",
        ),
        (
            "Create video surfaces off the main thread",
            DISCORD_EGL_NONBLOCKING,
            "DISCORD_EGL_NONBLOCKING",
            patch_video_egl_main_thread_block,
            "now pairs `post` with a released-guard in the create lambda, so "
            "no EGL context can be built after teardown. The earlier "
            "`post`-only version leaked a context per early-detached tile until "
            "eglCreateContext failed with EGL_BAD_ALLOC. See "
            "reference/ANR_AUDIT_3485.md.",
        ),
        (
            "Remove unused translations",
            DISCORD_STRIP_INTL,
            "DISCORD_STRIP_INTL",
            patch_strip_intl_cache,
            "the per-module, per-locale translated-message catalogs: 23.0 of "
            "23.6 MB uncompressed, keeping only "
            f"{', '.join(sorted(INTL_KEEP_LOCALES)) or 'nothing'}. Nothing "
            "statically references them and Discord keeps them from the "
            "shrinker with an explicit keep.xml - which is itself evidence the "
            "lookup is by constructed name. Unverified on device: the tell is "
            "that the app starts and still shows text",
        ),
        (
            "Stop an incoming call blocking the interface thread",
            DISCORD_INCOMING_CALL_NO_BLOCK,
            "DISCORD_INCOMING_CALL_NO_BLOCK",
            patch_incoming_call_nonblocking,
            "three nested runBlocking calls on the main thread, with Fresco init "
            "and a suspending network image fetch behind them - the freeze "
            "reported when a call arrives. Costs the caller's avatar on the "
            "incoming-call screen; the name is still shown",
        ),
        (
            "Cap the video surface teardown wait",
            DISCORD_BOUND_SURFACE_RELEASE,
            "DISCORD_BOUND_SURFACE_RELEASE",
            patch_bound_surface_release,
            "`SurfaceEglRenderer.surfaceDestroyed` runs on the UI thread and "
            "blocks on a latch with no timeout, waiting for the render thread "
            "to release the EGL surface. Stock upstream, and deliberate - but "
            "it means a wedged render thread hangs the UI thread instead of "
            "failing the call. Bounding it trades ANR risk for a narrow "
            "ordering race, so it wants a device pass on join/leave/rotate",
        ),
        (
            "React Compiler",
            DISCORD_REACT_COMPILER,
            "DISCORD_REACT_COMPILER",
            patch_react_compiler_flag,
            "349.5 ships every component twice and picks between them with "
            "`isReactCompilerEnabled()`; the experiment behind it "
            "(2026-09-react-compiler-mobile) defaults to false, so the compiled "
            "half has never run for a real user. Forcing it on switches every "
            "component in the app at once - a genuine perf lever (the compiler "
            "auto-memoises, which is exactly what the jank numbers were about) "
            "but one that wants a device pass, not a default",
        ),
    ]:
        if enabled:
            fn(base)
        else:
            print(f"=== {label} ===")
            print(f"  [SKIP] {flag}=False - {why}")
    patch_sentry_breadcrumb_facade(base)
    patch_sentry_native_libs(base)
    patch_foreground_service_type(base)
    # DISABLED: this hand-edited Hermes body segfaults the app.
    #   SIGSEGV / SEGV_MAPERR, null deref, in libhermesvm.so on the JS thread,
    #   reached from RuntimeScheduler_Modern::performMicrotaskCheckpoint ~1 s
    #   after launch — i.e. in the bundle, not in smali.
    # The edit itself assembles and round-trips (asm-check), decompiles to
    # exactly the intended JavaScript, and leaves the function/string/
    # identifier tables untouched — and it still crashes. The likely cause is
    # a closure slot that a *nested* closure reads: the original stored the
    # serialised payload in environment slot 14, my replacement stopped
    # writing it, and a nested closure's access to that slot does not appear
    # in the outer function's disassembly, which is where I checked.
    # Removing a dead JSON.stringify + pako.deflate per gateway connect is not
    # worth a startup crash, so it is off until it can be validated in a
    # Hermes VM rather than on a phone.
    # patch_js_body_edits(base)

    print("=== PERF EXPERIMENTS ===")
    pinned = patch_3541_experiments(base)
    p = sfile(base, "com/discord/native_experiments/NativeExperiments.smali")
    if not pinned and os.path.exists(p):
        s = rd(p)
        # Every experiment NativeExperiments exposes, not just the first
        # three. Treatment 1 is the fast/cheap arm for Fresco, Hermes and
        # ChatMosaic; for JankPerScreen treatment 0 is CONTROL, which is the
        # one that switches per-screen jank tracking off
        # (`isPerScreenEnabled()` is true only for TREATMENT).
        for name, settings, label, treatment in [
            (
                "frescoCacheExperimentSettings_delegate$lambda$2",
                "FrescoMemoryCacheExperimentSettings",
                "Fresco",
                1,
            ),
            (
                "hermesOccupancyTargetExperimentSettings_delegate$lambda$1",
                "HermesOccupancyTargetExperimentSettings",
                "Hermes",
                1,
            ),
            (
                "chatMosaicSharedPoolExperimentSettings_delegate$lambda$3",
                "ChatMosaicSharedPoolExperimentSettings",
                "ChatMosaic",
                1,
            ),
            (
                "jankPerScreenExperimentSettings_delegate$lambda$4",
                "JankPerScreenExperimentSettings",
                "JankPerScreen (per-screen jank tracking OFF)",
                0,
            ),
        ]:
            marker = f".method private static final {name}(Lcom/discord/libdiscore/LibdiscoreModuleClass$LibdiscoreModule;)Lcom/discord/native_experiments/{settings};"
            if marker in s:
                a = s.index(marker)
                e = s.index(".end method", a) + len(".end method")
                bs = settings.replace("Settings", "Bootstrap")
                new = f"""{marker}
    .locals 2
    sget-object v0, Lcom/discord/native_experiments/{bs};->INSTANCE:Lcom/discord/native_experiments/{bs};
    const/4 p0, 0x{treatment}
    invoke-virtual {{v0, p0}}, Lcom/discord/native_experiments/{bs};->fromTreatmentId(I)Lcom/discord/native_experiments/{settings};
    move-result-object p0
    return-object p0
.end method"""
                s = s[:a] + new + s[e:]
                print(f"  [OK] {label}: treatment {treatment} forced")
            else:
                print(f"  [SKIP] {label} experiment (marker not found)")

        # mountingMode is a bare int, and it drives four React Native Fabric
        # flags at once:
        #     enableAccumulatedUpdatesInRawPropsAndroid() { return mode >= 2; }
        #     enablePropsUpdateReconciliationAndroid()    { return mode >= 2; }
        #     usePullModelOnAndroid()                     { return mode == 3; }
        #     useSilenceErrorSMMViewNotFound()            { return mode == 1; }
        # We already force the first three true individually, which is only
        # self-consistent at mode 3 — so pin the experiment to 3 as well and
        # let the flag methods follow from it. Cheaper and it cannot drift out
        # of sync with the individual patches.
        marker = ".method private static final mountingModeExperiment_delegate$lambda$0(Lcom/discord/libdiscore/LibdiscoreModuleClass$LibdiscoreModule;)I"
        if marker in s:
            a = s.index(marker)
            e = s.index(".end method", a) + len(".end method")
            new = f"""{marker}
    .locals 1
    const/4 p0, 0x3
    return p0
.end method"""
            s = s[:a] + new + s[e:]
            print(
                "  [OK] mountingMode forced to 3 "
                "(pull model + accumulated raw props + props reconciliation)"
            )
        else:
            print("  [SKIP] mountingMode experiment (marker not found)")

        wr(p, s)
    elif not pinned:
        print("  [SKIP] NativeExperiments.smali missing")

    print("=== FCM / NOTIFICATIONS ===")
    print(
        "  [KEEP] FirebaseMessaging.getToken() left stock -> push works again"
    )
    print("  [KEEP] firebase datatransport components left stock")

    print("=== RETIRED TARGETS (reported, not silently missed) ===")
    for label in (
        "Spotify activity filter: the 354.1 filter shape changed; "
        "network blocking and SpotifyTrack stay active",
        "Floating nav Quests button: 354.1's FloatingNav no longer builds it",
        "Native MonitoringAgent: removed from the 354.1 dex",
    ):
        print(f"  [SKIP] {label}")

    print(f"\n=== CORE DONE: {ok} OK, {fail} FAIL ===")
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())
