#!/usr/bin/env python3
"""Rebuild `DeviceResourceUsageRecorder$Companion.requestStatsInterceptor`.

This single interceptor is shared by every Discord OkHttp client that matters
here: the RN XHR client (JS traffic, which is where the Discord client actually
lives), the media download client, the bundle updater and Fresco. Requests whose
URL matches a tracking / monetisation entry are answered with a synthetic HTTP
204 instead of being sent.

The 204 (rather than a dropped response) is deliberate: JS treats it as a
successful empty response and clears its retry buffer. Dropping the response
makes the client retry forever.

Usage: blocklist.py <decompiled-base-dir>
"""

import os
import re
import sys

SMALI_DIRS = ["smali", "smali_classes2", "smali_classes3", "smali_classes4"]

# Host patterns: matched anywhere in the request string.
# NOTE: api.spotify.com / dealer.spotify.com are blocked on purpose — breaking
# the Spotify integration (REST + listen-along WebSocket) is intended, not an
# oversight. An audit flagged it as a feature regression; the user confirmed the
# block is deliberate.
HOST_BLOCKLIST = [
    "api.spotify.com",
    "dealer.spotify.com",
    # video QoE analytics beacon (Mux/Litix) hit by the media player
    "img.litix.io",
    # Firebase Analytics. The SDK logs to firebaselogging-pa.googleapis.com,
    # which a literal "firebaselogging.googleapis.com" entry never matched —
    # match the prefix instead. Firebase Installations and the FCM/mtalk hosts
    # are deliberately absent: blocking them breaks push.
    "firebaselogging",
    "app-measurement.com",
    # Google Analytics (covers ssl./www./region1. subdomains)
    "google-analytics.com",
    # Bare `sentry.io`, not `ingest.sentry.io`. Sentry moved to regional DSN
    # hosts (`o<org>.ingest.us.sentry.io`, `.de.`, `.eu.`), and the old rule
    # did not match any of them - it only matched the legacy single-region
    # form. The DSNs in this build are scrubbed so nothing can send today, but
    # a future version shipping a regional DSN would have sailed straight past
    # the old pattern. Bare `sentry.io` covers every DSN form and the docs.
    "sentry.io",
    # Sentry's replay bundle CDN
    "sentry-cdn.com",
    # AppsFlyer attribution. The SDK init is already no-op'd; this covers the
    # network side as well, including the OneLink short domain.
    "appsflyer.com",
    "appsflyersdk.com",
    "onelink.me",
    # Qualtrics surveys
    "qualtrics.com",
    # Datadog APM
    "datadog.discord.tools",
    # PayPal conversion beacon and the FraudNet script the checkout webview
    # loads. The SDK's own analytics logger is a full path under
    # www.paypal.com, not a separate host, so match that path narrowly rather
    # than blocking the payment site.
    "b.stats.paypal.com",
    "c.paypal.com",
    "www.paypal.com/xoplatform/logger",
    # Google Mobile Ads attribution beacon. Reachable only if the ads path
    # were live (it is stubbed), but the host string is still in the dex.
    "pagead2.googlesyndication.com",
    "api.amplitude.com",
    "api.segment.io",
    "client-analytics.braintreegateway.com",
    "app.adjust.com",
]

# API path patterns: matched only on Discord API URLs (those containing
# "/api/"). Without that gate a bare substring such as "/track" would also
# block a CDN attachment named "track1.mp3" or "shop.png".
API_BLOCKLIST = [
    # analytics / telemetry
    "/science",
    "/track",
    "scienc0",
    "/affinit",
    "/analytics_sessions",
    # client metrics upload — MonitoringAgent POSTs /metrics/v2 every 2 minutes
    "/metrics",
    # client_telemetry heartbeat (AnalyticsTrackingStore -> TelemetryEndpoints
    # .CLIENT_TELEMETRY). This one was live: it is a plain HTTP.post outside
    # the /science queue and was not blocked at all.
    "/beaker",
    # Discord-side Spotify content inventory (MY_SPOTIFY_CONTENT_INVENTORY) —
    # the one Spotify endpoint that is not on a Spotify host.
    "/content-inventory/users/@me/spotify",
    # The Spotify connection access token. `SpotifyActionCreators.getAccessToken`
    # GETs Discord's own /connections/spotify/<id>/access-token and uses it to
    # talk to the Spotify Web API. No host rule covers this - it is discord.com.
    # Without it the client has no credential even if it reaches api.spotify.com.
    "/connections/spotify",
    # usage statistics
    "/users/@me/activities/statistics",
    # app-rating and embedded surveys
    "/users/@me/survey",
    "/users/@me/embedded-survey",
    # ad attribution
    "/ads/",
    # debug endpoints
    "/debug/temporal",
    # storefront / promotions / quests
    "/storefront",
    "/quest",
    "/quest-home",
    "/promotion",
    "/bogo-promotions",
    "/shop",
    "/game-shop",
    "/collectibles",
    # `/collectibles` only matches a slash-prefixed segment. Two real endpoints
    # spell it as a hyphen compound, so the rule above misses both:
    #   /users/@me/claim-premium-collectibles-product
    #   /users/@me/valid-collectibles-gift-recipients-batch
    # Found by diffing every API-shaped path in the bundle against the rules.
    "-collectibles",
    "/wishlist",
    "/virtual_currency",
    "/perks",
    "/reward",
    # billing / monetisation
    "/users/@me/billing",
    "/billing",
    # 354.1 spellings that the older segment rules miss. `/purchase` covers
    # `/purchases`; `/store` covers `/store-layout` and `/store-terms`; the
    # remaining four are new checkout/perk surfaces in this bundle.
    "/purchase",
    "/store",
    "/authorize-payment",
    "/app-store-consent",
    "/reactivation-offers",
    "/activate-perk",
    # Play Store in-app purchases
    "/google-play/",
    # store listings and price tiers
    "/store/",
    "/entitlement",
    "/gift",
    "/subscription",
    "/guild-role-subscription",
    "/guild_role_subscriptions",
    "/guild_boosting",
    "/skus",
    "/purchases",
    "/checkout",
    "/payment",
    "/user-offer",
    "/referral",
    "/nitro",
    "/guilds/premium",
    "/boost",
    "/premium",
    # typing indicator — NoType
    "/typing",
    # Hyphenated compounds the entries above miss. Matching is a plain
    # `contains`, so "/reward" does not match "-reward-" and
    # "/guild-role-subscription" does not match "/role-subscriptions". These
    # are the live 346.2 spellings (Endpoints table in Constants.tsx).
    "/creator-monetization",
    "/role-subscriptions",
    "/virtual-currency",
    # Orbs Arcade enrollment/reset/claim endpoints. The feature is already
    # gated off by `useVirtualCurrencyMobileEnabled = {enabled:false}`, but the
    # three paths are new in 354.1 and only the claim path happens to match
    # `/reward`. Block the family rather than rely on the gate.
    "/orbs-arcade",
    "/outbound-promotions",
    "/partner-perks",
    "/program-rewards",
    "/tenure-reward",
    "/claim-reward",
    "/store-listing",
    "/application-storefront",
    "/applied-boosts",
    "/powerups",
    "/activities/statistics",
]

DEFAULT_BASE = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "decompiled", "base"
)


# 354.1 ships an R8-obfuscated OkHttp. These are the descriptors this version
# actually uses; every one is verified below before anything is written.
OKHTTP = {
    "response": "k50/j0",
    "response_ctor": (
        "(Lb5/a;Lk50/e0;Ljava/lang/String;ILk50/t;Lk50/u;Lk50/l0;"
        "Lk50/j0;Lk50/j0;Lk50/j0;JJLo50/e;)V"
    ),
    "protocol": "k50/e0",
    "protocol_field": "i",          # HTTP_1_1
    "request": "b5/a",
    "request_url_field": "e",
    # The field is declared as Object because R8 merged unrelated classes into
    # b5/a; the constructor still stores an HttpUrl in it. Reading it as
    # Lk50/w; would be a NoSuchFieldError at runtime, so read Object and cast.
    "request_url_field_type": "Ljava/lang/Object;",
    "url": "k50/w",
    "headers": "k50/u",
    "chain": "p50/e",
    "chain_field": "e",             # Lb5/a; (Request)
    "chain_proceed": "b",           # b(Request)Response
    "interceptor": "ng/b",
    "interceptor_method": "b",
    "interceptor_desc": "(Lp50/e;Lng/c;)Lk50/j0;",
}


def smali_dirs(base):
    import re as _re
    found = sorted(
        d for d in os.listdir(base)
        if d == "smali" or _re.fullmatch(r"smali_classes\d+", d)
    )
    return found or SMALI_DIRS


def find_class(base, rel):
    for d in smali_dirs(base):
        p = os.path.join(base, d, rel)
        if os.path.exists(p):
            return p
    return None


def discover_okhttp(base):
    """Verify every obfuscated OkHttp type this version's interceptor needs.

    Returns the profile dict, or None if the build does not match it. The
    alternative to checking is writing a descriptor that assembles to a
    NoClassDefFoundError on every request, which is how 347.5 shipped once.
    """
    need = [
        OKHTTP["response"],
        OKHTTP["protocol"],
        OKHTTP["request"],
        OKHTTP["url"],
        OKHTTP["headers"],
        OKHTTP["chain"],
        OKHTTP["interceptor"],
    ]
    for rel in need:
        if find_class(base, rel + ".smali") is None:
            print(f"  [SKIP] OkHttp profile does not match: {rel}.smali missing")
            return None
    def read(rel):
        return open(find_class(base, rel + ".smali"), encoding="utf-8").read()

    def field_desc(src, name):
        m = re.search(
            rf"^\.field[^\n]*\b{re.escape(name)}:([^\s]+)", src, re.M
        )
        return m.group(1) if m else None

    proto = read(OKHTTP["protocol"])
    if f'L{OKHTTP["protocol"]};->{OKHTTP["protocol_field"]}:' not in proto:
        print("  [SKIP] Protocol HTTP_1_1 field not found")
        return None
    resp = read(OKHTTP["response"])
    if OKHTTP["response_ctor"] not in resp:
        print("  [SKIP] Response constructor changed")
        return None
    req = read(OKHTTP["request"])
    got = field_desc(req, OKHTTP["request_url_field"])
    if got != OKHTTP["request_url_field_type"]:
        print(
            "  [SKIP] Request URL field type changed: "
            f"{OKHTTP['request_url_field']} is {got}, "
            f"expected {OKHTTP['request_url_field_type']}"
        )
        return None
    chain = read(OKHTTP["chain"])
    got = field_desc(chain, OKHTTP["chain_field"])
    if got != f"L{OKHTTP['request']};":
        print(
            "  [SKIP] Chain request field type changed: "
            f"{OKHTTP['chain_field']} is {got}"
        )
        return None
    proceed = (
        f"{OKHTTP['chain_proceed']}(L{OKHTTP['request']};)"
        f"L{OKHTTP['response']};"
    )
    if proceed not in chain:
        print("  [SKIP] Chain proceed method changed")
        return None
    headers = read(OKHTTP["headers"])
    if "<init>([Ljava/lang/String;)V" not in headers:
        print("  [SKIP] Headers constructor changed")
        return None
    interceptor = read(OKHTTP["interceptor"])
    if OKHTTP["interceptor_desc"] not in interceptor:
        print("  [SKIP] Interceptor signature changed")
        return None
    return OKHTTP


def build_interceptor(profile):
    """The 354.1 blocklist body.

    Structure: read the request URL (never Request.toString(), which renders
    every header including X-Super-Properties), apply the host/API rules, then
    either call chain.proceed() or return a synthetic 204. The response is
    constructed directly on the obfuscated descriptor; its body and
    cache-control/prior-response slots are null, which OkHttp accepts.
    """
    p = profile
    lines = [
        f".method public static {p['interceptor_method']}"
        f"{p['interceptor_desc']}",
        # Keep the original frame. `patch-function` rewrites the body only and
        # cannot grow the register file, so the 204 is built in a small helper
        # class rather than with 16 locals here.
        "    .locals 5",
        "",
        "    # Patch: blocklist - match the URL only.",
        f"    iget-object v1, p0, L{p['chain']};->{p['chain_field']}:L{p['request']};",
        f"    iget-object v2, v1, L{p['request']};->{p['request_url_field']}:"
        f"{p['request_url_field_type']}",
        f"    check-cast v2, L{p['url']};",
        f"    invoke-virtual {{v2}}, L{p['url']};->toString()Ljava/lang/String;",
        "    move-result-object v2",
        "",
        "    # v3 = is this a Discord API URL?",
        '    const-string v4, "/api/"',
        "    invoke-virtual {v2, v4}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z",
        "    move-result v3",
        '# A media-proxy URL embeds the origin after /external/<hmac>/, so it is',
        '# never treated as an API call.',
        '    const-string v4, "/external/"',
        "    invoke-virtual {v2, v4}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z",
        "    move-result v4",
        "    if-eqz v4, :not_proxied",
        "    const/4 v3, 0x0",
        "    :not_proxied",
        "",
    ]
    for entry in HOST_BLOCKLIST:
        lines += [
            f'    const-string v4, "{entry}"',
            "    invoke-virtual {v2, v4}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z",
            "    move-result v4",
            "    if-nez v4, :block",
            "",
        ]
    for i, entry in enumerate(API_BLOCKLIST):
        lines += [
            f'    const-string v4, "{entry}"',
            "    invoke-virtual {v2, v4}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z",
            "    move-result v4",
            f"    if-eqz v4, :skip_{i}",
            "    if-nez v3, :block",
            f"    :skip_{i}",
            "",
        ]
    lines += [
        "    # Not blocked - proceed.",
        f"    invoke-virtual {{p0, v1}}, L{p['chain']};->{p['chain_proceed']}"
        f"(L{p['request']};)L{p['response']};",
        "    move-result-object v0",
        "    return-object v0",
        "",
        "    :block",
        "    # Synthetic HTTP 204 No Content, built in a helper that owns the",
        "    # 16-register frame the Response constructor needs.",
        "    invoke-static {v1}, Lcom/discord/resource_usage/BlockedResponse;"
        f"->create(L{p['request']};)L{p['response']};",
        "    move-result-object v0",
        "    return-object v0",
        ".end method",
    ]
    return "\n".join(lines)


def build_helper(profile):
    """A tiny smali class that builds the synthetic 204.

    The interceptor's original frame has only five locals, and
    `patch-function` cannot grow it. Building `okhttp3.Response` needs a
    contiguous 16-register range, so that construction lives here instead.
    """
    p = profile
    return f""".class public final Lcom/discord/resource_usage/BlockedResponse;
.super Ljava/lang/Object;
.source "BlockedResponse.smali"

.method public static create(L{p['request']};)L{p['response']};
    .locals 17

    # The Response constructor's 15 argument words start at v2 so both `long`
    # parameters land on an even register pair (v12 and v14). v0 is scratch.
    new-instance v1, L{p['response']};
    move-object/from16 v2, p0
    sget-object v3, L{p['protocol']};->{p['protocol_field']}:L{p['protocol']};
    const-string v4, "No Content"
    const/16 v5, 0xcc
    const/4 v6, 0x0
    const/4 v0, 0x0
    new-array v0, v0, [Ljava/lang/String;
    new-instance v7, L{p['headers']};
    invoke-direct {{v7, v0}}, L{p['headers']};-><init>([Ljava/lang/String;)V
    const/4 v8, 0x0
    const/4 v9, 0x0
    const/4 v10, 0x0
    const/4 v11, 0x0
    const-wide/16 v12, 0x0
    const-wide/16 v14, 0x0
    const/16 v16, 0x0
    invoke-direct/range {{v1 .. v16}}, L{p['response']};-><init>{p['response_ctor']}
    return-object v1
.end method
"""


def _write_atomic(path, text):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)


def _missing(what):
    """The blocklist is a core privacy patch: absence is a build failure.

    Set BLOCKLIST_ALLOW_SKIP=1 only for a deliberate partial run.
    """
    if os.environ.get("BLOCKLIST_ALLOW_SKIP") == "1":
        print(f"  [SKIP] {what} (BLOCKLIST_ALLOW_SKIP=1)")
        return 0
    print(f"  [FAIL] {what} - the network blocklist would not be applied")
    return 1


def main():
    base = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_BASE)
    print("=== BLOCKLIST INTERCEPTOR ===")

    profile = discover_okhttp(base)
    if profile is None:
        return _missing("OkHttp profile does not match this build")
    path = find_class(base, profile["interceptor"] + ".smali")
    if path is None:
        return _missing("interceptor class missing")

    # The helper owns the 16-register Response construction. Write it before
    # deciding whether the interceptor needs rebuilding, so an older tree with
    # the 16-local body still gets a working class.
    helper_dir = os.path.join(base, "smali", "com", "discord", "resource_usage")
    os.makedirs(helper_dir, exist_ok=True)
    _write_atomic(os.path.join(helper_dir, "BlockedResponse.smali"),
                  build_helper(profile))

    marker = (
        f".method public static {profile['interceptor_method']}"
        f"{profile['interceptor_desc']}"
    )
    with open(path, encoding="utf-8") as f:
        s = f.read()
    expected = build_interceptor(profile)
    try:
        a = s.index(marker)
    except ValueError:
        print(
            "  [FAIL] stats interceptor method not found "
            f"({profile['interceptor']}.{profile['interceptor_method']})"
        )
        return 1
    e = s.index(".end method", a) + len(".end method")
    if s[a:e] == expected:
        print("  [SKIP] blocklist interceptor already rebuilt")
        return 0
    _write_atomic(path, s[:a] + expected + s[e:])
    print(
        f"  [OK] interceptor rebuilt ({len(HOST_BLOCKLIST)} host + "
        f"{len(API_BLOCKLIST)} API entries)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
