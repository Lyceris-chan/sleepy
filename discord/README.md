# Discord Android — patched builds

Reproducible, portable pipeline for de-Googling, de-telemetrising and
improving a Discord Android APK. Everything runs from this directory with
no system packages: the toolchain bundles its own JRE and every tool is a
self-contained CLI binary or jar.

Current build: **Discord 354.1 Alpha (354201)**. See
[`CHANGELOG.md`](CHANGELOG.md) for what changed and
[`OPTIONS.md`](OPTIONS.md) for the build options.

## Layout

```
discord/
  dist/                  built, signed APKs (archive/ holds older versions)
  build/
    signing.keystore     the key every build is signed with
    alpha3495/           per-version working tree (apk, decompiled, out)
  patches/               the patch suite — the source of truth
    manifest.json        patch metadata and option defaults
    hasm/                before/after images for Hermes body edits
  options.json           your saved option selection (optional)
  scripts/               the pipeline (decode → patch → build)
  tools/                 portable toolchain (see below)
  reference/             logs and legacy material, not needed to build
```

## Toolchain

All tools live in `tools/` and resolve their own paths, so this tree can
be moved anywhere. Nothing is installed system-wide.

| Tool | Purpose |
| --- | --- |
| `jre17/` | bundled Temurin JRE 17 (no system Java needed) |
| `jadx` | dex → readable Java |
| `apktool` | resources + smali decode/rebuild (the patching target) |
| `procyon`, `vineflower`, `cfr` | extra Java decompilers (need a jar from `dex2jar`) |
| `dex2jar` | dex/APK → `.class` jar for the decompilers |
| `uber-apk-signer` | zipalign + APK Signature v2/v3 |
| `apksigner/` | certificate inspection (`apksigner verify --print-certs`) |
| `ecj` | Eclipse Java compiler — validates regex rules on the real JVM |
| `hermes-decomp` | Hermes bytecode (HBC) disassembler/decompiler/patcher |
| `hermes-test/` | executes candidate bytecode stubs in a real Hermes VM |
| `asc` | portable [droidasc](https://github.com/MG1937/ASC) — whole-APK reference search, runs from a venv in `tools/` |
| `telemetry-audit.py` | every app-level call site into a telemetry sink, across all dex |
| `remap_hermes.py` | re-map the Hermes target IDs in `patches/core.py` for a new bundle |
| `verify-stubs.py` | check the stubs landed, in the *built APK* rather than in the patcher's report |
| `patches.py` | list, set, and document build options; `edit` is an interactive selector |

`tools/verify.sh` smoke-tests the whole set. `source tools/env.sh` puts
`tools/bin` and the bundled JRE on `PATH`.

### Working on a new Discord version

```bash
tools/remap_hermes.py <pristine-old>.bundle <new>.bundle --write
tools/telemetry-audit.py <apk>          # what still reaches a telemetry sink
tools/verify-stubs.py <built>.apk       # gate: exit code is the failure count
```

`remap_hermes.py` wants the **pristine** old bundle, not our patched tree —
by the time a version bump happens the old bodies are already stubs in our
copy, so there is nothing left to compare against.

Hermes function IDs are per-bundle, so every release shifts all of them and
the whole `TARGETS` set stops matching at once. The remapper resolves them by
name where the name is unique, then by id position against the bundles'
shared unique-name anchors, then by bytecode similarity inside the implied id
window.

`hermes-decomp` deserves a note: it supports HBC v40–v99 (this build's JS
bundle is v98) and is the only tool found that can both read **and**
rewrite that bytecode. Its `patch-function` path is verified working on
v98; `inject-stub` reports success but does not actually change the body,
and its `decompile` writes a multi-hundred-MB `*.hdcache` sidecar next to
the bundle, which the patch suite deletes before packaging. It rewrites a
function *body* only — it does not grow the register file or inline-cache
region in the function header — so the stubs are kept within the original
footprint, and the promise-shaped one is executed in a real Hermes VM first
(`tools/hermes-test/`).

## Choosing options

The patch list and its options live in `patches/manifest.json`. Use the patch
manager to inspect or change them:

```bash
tools/patches.py list       # grouped patch list with defaults
tools/patches.py edit       # interactive selector (space toggles, s saves)
tools/patches.py set EXTERNAL_BROWSER off
tools/patches.py reset      # back to manifest defaults
```

Your choices are written to `options.json`. `02-patch.sh` picks that file up
automatically. A per-build `build/<version>/options.json`, when present, takes
precedence over `discord/options.json`. `tools/patches.py docs --write`
regenerates [`OPTIONS.md`](OPTIONS.md) from the manifest.

## Building a version

```bash
# 1. decode base.apk + config splits into build/<ver>/decompiled/
scripts/01-decode.sh build/<ver>

# 2. apply the patch suite (reads options.json, if present)
scripts/02-patch.sh build/<ver>

# 3. rebuild + zipalign + sign into build/<ver>/out/
scripts/03-build.sh build/<ver>
```

Every script is idempotent and prints `[OK]` / `[SKIP]` / `[FAIL]` per
target, so a Discord version bump tells you exactly which hook moved.

The APK to install is `build/<ver>/out/*-aligned-signed.apk`; copy it to
`dist/` when you publish it.

## The patch suite

| Script | What it does |
| --- | --- |
| `core.py` | manifest cleanup, Sentry (native + JS), AppsFlyer, Analytics, NetStats, Systrace, resource monitors, log noise, perf experiments, privacy cuts, Hermes bytecode stubs |
| `blocklist.py` | rebuilds the shared OkHttp interceptor: host + API-path request blocklist |

The suite also exposes optional patches — opening links in the system browser,
removing promotion surfaces, quieting media logs, and so on. See
[`OPTIONS.md`](OPTIONS.md) or run `tools/patches.py list`.

## Verifying a build

```bash
tools/jre17/bin/java -jar tools/apksigner/apksigner.jar \
    verify --print-certs dist/discord-alpha-354.1-patched-aligned-signed.apk
```

The upstream APK is authentic Discord if the signer is
`CN=Jason Citron, O=Hammer and Chisel`. A patched build shows the custom
key from `build/signing.keystore` instead — that is expected.

## Known limits

- Hermes function IDs change with every Discord release, so the whole target set
  stops matching on a version bump. Re-map it before patching:
  `tools/remap_hermes.py <pristine-old>.bundle <new>.bundle --write`, then
  `tools/remap_by_module.py` and `tools/merge_remap.py`.
- 354.1 ships an R8-obfuscated dex for internal classes. A patch that named a
  class directly is now located by a method descriptor or a string; one whose
  target was removed is reported as retired instead of failing the build.
- Discord's debug log screen renders an in-memory JavaScript buffer. Native
  telemetry, breadcrumbs and NetStats are killed at the source, but a generic
  app-level debug line can only be removed by patching the JavaScript function
  that emits it.
- A Hermes body edit that changes the body's length invalidates every jump
  target after the change. Read
  [`reference/HBC_BODY_EDIT_HAZARD.md`](reference/HBC_BODY_EDIT_HAZARD.md)
  before writing one.
- Two 349.5 body edits are retired for 354.1: the Spotify activity filter and
  the floating-nav Quests button. Their old targets no longer contain the code
  they edited. Re-derive them against the new bundle before re-enabling.

## Documentation

Write for the reader, not for the author.

- [`CHANGELOG.md`](CHANGELOG.md) follows
  [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). One line per change,
  grouped under `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, and
  `Security`. State what changed, not how it was found.
- [`CHANGES.md`](CHANGES.md) is the reader-facing summary of what this build
  does differently.
- [`OPTIONS.md`](OPTIONS.md) describes each build option as a setting: what it
  does, whether it is on by default, and what to watch for.
- Everything in this file, `CHANGES.md` and `OPTIONS.md` follows the
  [Google developer documentation style guide](https://developers.google.com/style):
  second person, present tense, active voice, sentence case.
- Analysis, measurements and dead ends belong in `reference/`, not in the
  changelog. Link to them.

A changelog entry answers "what is different in this build?" in one line. If it
needs a paragraph, it belongs in `reference/`.
