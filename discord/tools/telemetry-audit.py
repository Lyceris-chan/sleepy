#!/usr/bin/env python3
"""Report every app-level call site into a telemetry / crash-reporting sink.

This exists because gating a *feature* (e.g. forcing
`CrashReporting.isDisabled()` true) only covers call sites inside that
feature's own class. Other code can — and does — reach the SDK facade
directly, and then the gate looks like it works while the sink is still
being called.

Uses ASC (`tools/asc`) for cross-DEX reference search, which is fast enough
(~0.2 s per query on a 140 MB APK) to enumerate a whole facade's methods.

Usage:
    tools/telemetry-audit.py <apk> [--json]

Sinks are declared in SINKS below. A sink is a Dalvik class descriptor plus
an optional method allowlist; with no allowlist every declared method of the
class is searched, which is what makes this survive a version bump.
"""

import argparse
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ASC = os.path.join(HERE, "asc")

# Classes we never want called from Discord code. Descriptor form, as ASC
# wants it. `methods=None` means "enumerate them from the class".
#
# `gated` marks a sink whose callers we already neutralise somewhere else —
# `CrashReporting` is forced inert by `CrashReporting.isDisabled()`, so its
# ~70 call sites are informational only and are hidden unless --all is
# passed. The ungated sinks are the ones that matter: they are reached
# without passing through any gate we own.
SINKS = [
    # The Sentry facade (`io.sentry.Sentry`). Everything Discord reports
    # funnels through here. NOT gated: callers reach it directly.
    ("Sentry facade", "io/sentry/c4", None, False),
    # React Native bridge to the JS Sentry SDK.
    ("Sentry RN bridge", "io/sentry/react/RNSentryModuleImpl", None, False),
    # JNI entry point the native WebRTC layer calls on exceptions.
    (
        "WebRTC crash reporting",
        "com/discord/crash_reporting/WebrtcCrashReporting",
        None,
        False,
    ),
    # Discord's own crash reporting — gated by CrashReporting.isDisabled().
    ("CrashReporting (gated)", "com/discord/crash_reporting/CrashReporting",
     None, True),
    # Native crash reporter installer — same gate.
    (
        "NativeCrashReporting (gated)",
        "com/discord/crash_reporting/NativeCrashReporting",
        None,
        True,
    ),
]

# A caller whose own class lives under one of these prefixes is the SDK
# talking to itself; not interesting. Only the SDK qualifies — Discord's own
# `com/discord/crash_reporting/*` classes are app code and their call sites
# matter (e.g. WebrtcCrashReporting reaches the facade with no gate at all).
INTERNAL_PREFIXES = ("io/sentry/",)

# Java-ish method declaration in `asc getclass` output:
#     public static io.sentry.x0 f()
#     private final void start()
METHOD_RE = re.compile(
    r"^\s{4}(?:public|private|protected|static|final|synthetic|\s)*"
    r"\S+\s+([A-Za-z_$][\w$]*)\s*\(",
)

# `classes.dex | Lcom/x/Y;->z | matched=(Lio/sentry/c4;->a)`
# The matched reference is the part that matters: ASC's --class filter is
# fuzzy, and Kotlin synthetic bridges (`a`, `b`, `c`) otherwise collide with
# everything in the APK. Only count a hit whose matched owner is the sink.
REF_RE = re.compile(
    r"^(?P<dex>\S+\.dex) \| (?P<caller>\S+) \| matched=\((?P<owner>[^;]+;)"
)


def run_asc(*args):
    proc = subprocess.run(
        [ASC, *args], capture_output=True, text=True, timeout=300
    )
    return proc.stdout


def class_methods(apk, desc):
    """Method names declared by `desc` (Dalvik descriptor)."""
    out = run_asc("getclass", apk, "L" + desc + ";")
    names = []
    for line in out.splitlines():
        m = METHOD_RE.match(line)
        if m:
            names.append(m.group(1))
    # <init>/<clinit> are noise here.
    return sorted({n for n in names if not n.startswith("<")})


def find_callers(apk, desc, method):
    """Call sites of `desc->method`, as (caller, is_internal).

    Hits whose matched owner is not `desc` are discarded: ASC resolves
    methods by name across the whole APK, so a query for `CrashReporting.a`
    otherwise returns every call to some unrelated `a` as well.
    """
    want = "L" + desc + ";"
    out = run_asc("findrefs", apk, "method", method, "--class", desc)
    hits = []
    for line in out.splitlines():
        m = REF_RE.match(line)
        if not m or m.group("owner") != want:
            continue
        caller = m.group("caller")
        internal = caller.lstrip("L").startswith(INTERNAL_PREFIXES)
        hits.append((caller, internal))
    return hits


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("apk")
    ap.add_argument(
        "--json", action="store_true", help="machine-readable output"
    )
    ap.add_argument(
        "--all",
        action="store_true",
        help="also show sinks we already gate, and intra-package callers",
    )
    args = ap.parse_args()

    if not os.path.exists(args.apk):
        print(f"no such apk: {args.apk}", file=sys.stderr)
        return 2
    if not os.access(ASC, os.X_OK):
        print(f"ASC wrapper not executable: {ASC}", file=sys.stderr)
        return 2

    report = {}
    for label, desc, allow, gated in SINKS:
        if gated and not args.all:
            continue
        methods = allow if allow is not None else class_methods(args.apk, desc)
        found = {}
        for meth in methods:
            for caller, internal in find_callers(args.apk, desc, meth):
                if internal and not args.all:
                    continue
                found.setdefault(caller, set()).add(meth)
        if found:
            report[label] = {
                caller: sorted(ms) for caller, ms in sorted(found.items())
            }

    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0

    if not report:
        print("no app-level call sites into any watched sink")
        return 0

    total = sum(len(v) for v in report.values())
    print(f"{total} app-level call site(s) into watched sinks:\n")
    for label, callers in report.items():
        print(f"## {label}")
        for caller, methods in callers.items():
            pretty = caller.lstrip("L").rstrip(";").replace("/", ".")
            print(f"   {pretty}  ->  {', '.join(methods)}")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
