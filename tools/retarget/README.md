# Retargeting the Discord patch suite

These tools move the Discord patch suite from one release to the next. They were
written during the 353.2.0 to 354.1 port and are deliberately split into three
independent checks:

- `retarget_hermes.py` maps the Hermes function table and extracts replacement
  bodies.
- `check_native_anchors.py` checks every native smali path, method signature,
  anchor, and switch-case label against a decompiled build.
- `update_sources.py` refreshes the tracker hashes and sizes in both
  `sources.json` copies.

None of them edits the repository. They read inputs and write reports or a
candidate file to the paths you pass. The one exception is
`update_sources.py --write`, which writes the two `sources.json` copies after
every check passes.

All commands below use `python3 -I`, so the script directory and the current
directory cannot contribute modules. The scripts use only the Python standard
library and make no network requests. They require Python 3.10 or newer. Each
script has a `--self-test` that uses temporary fixtures and writes nothing:

```sh
python3 -I tools/retarget/retarget_hermes.py --self-test
python3 -I tools/retarget/check_native_anchors.py --self-test
python3 -I tools/retarget/update_sources.py --self-test
```

## Prepare a workspace

Set a workspace outside the repository and unpack the stock bundles from the
tracker APKs. Do not run a script from inside a downloaded directory.

```sh
WORK=/tmp/retarget-3541
OLD=/path/to/tracker-353200
NEW=/path/to/tracker-354201
mkdir -p "$WORK"

unzip -p "$OLD/base.apk" assets/index.android.bundle > "$WORK/old.bundle"
unzip -p "$NEW/base.apk" assets/index.android.bundle > "$WORK/new.bundle"

stat -c %s "$WORK/new.bundle"
/path/to/hermes-decomp info "$WORK/new.bundle"
```

`hermes-decomp info` prints the function count and the HBC version. Record the
exact byte size and function count; both are passed to the retarget tool and
both must match the build the table will ship for. A count that is one function
off is a different bundle, not a rounding error.

Function dumps are optional, but supplying them makes the run faster and makes
the tool validate its own parser against `hermes-decomp`:

```sh
/path/to/hermes-decomp dump --kind functions "$WORK/old.bundle" > "$WORK/old.functions.txt"
/path/to/hermes-decomp dump --kind functions "$WORK/new.bundle" > "$WORK/new.functions.txt"
```

You also need a patched bundle for the target build when the table contains
replacement bodies. This is the target bundle after the previous suite's edits
were applied, or a build patched by hand for the port. `retarget_hermes.py`
reads the replacement bodies from it directly. Without it, only short constant
stubs can be carried unchanged; every larger body is reported as a special
entry.

## Step 1: retarget the Hermes table

Run a report-only pass first. The table is not written unless `--emit-kotlin`
is passed, and the tool refuses to overwrite its input table.

```sh
python3 -I tools/retarget/retarget_hermes.py \
  --old-bundle "$WORK/old.bundle" \
  --new-bundle "$WORK/new.bundle" \
  --old-bundle-size 70415324 \
  --old-function-count 163844 \
  --new-bundle-size 50882260 \
  --new-function-count 141898 \
  --kotlin app/src/main/kotlin/dev/sleepy/app/patches/DiscordHermesBundlePatch.kt \
  --decompiler /path/to/hermes-decomp \
  --patched-bundle "$WORK/patched-3541.bundle" \
  --old-functions "$WORK/old.functions.txt" \
  --new-functions "$WORK/new.functions.txt" \
  --report-json "$WORK/retarget.json" \
  --report-txt "$WORK/retarget.txt"
```

The old size and count are also read from the current Kotlin table and checked
against the old bundle, so a table pointed at the wrong old build fails before
mapping. The new size and count are written into the candidate constants.

The report has these sections:

- `resolved`: a mapping was found. The entry also says whether a replacement
  body was carried or extracted; a resolved mapping without a body becomes a
  special entry rather than a written table row.
- `ambiguous`: more than one new function is plausible. The scores, ids, names,
  and candidate bodies are listed. Resolve these by reading the functions, not
  by taking the first row.
- `unresolved`: no new function was mapped confidently. The old target may have
  moved, merged, or been removed; the report lists what was considered.
- `retired`: the old target is absent and no candidate resembles it. Confirm
  that the feature really no longer exists before deleting the entry.
- `special`: the function mapped, but its replacement cannot be carried as a
  short constant stub and the supplied patched bundle still has the stock body.
  These need a body edit by hand.
- `collapsed`: several old entries named the same new function. One entry is
  kept; the aliases are listed. Check that the retained body is the one the
  suite wants, because a constant stub and a body edit can share a target.
- `conflicts`: two different replacements arrived at the same new id.

When every finding has been resolved, emit the candidate outside the
repository:

```sh
python3 -I tools/retarget/retarget_hermes.py ... \
  --emit-kotlin "$WORK/DiscordHermesBundlePatch.kt"
```

`--allow-incomplete` is available for inspecting a candidate with unresolved
entries. Never ship a table produced with it. `--strict` makes any entry that
needs a human return exit status 1, which is the useful setting in a script.
Copy the reviewed table into
`app/src/main/kotlin/dev/sleepy/app/patches/DiscordHermesBundlePatch.kt`
yourself; the tool never writes there.

The tool uses, in order:

1. A unique function name as an anchor.
2. The name plus an id window.
3. The name plus opcode and string-literal similarity, with a byte-similarity
   fallback.
4. An exact body match for a name that is absent or anonymous.
5. Opcode and string similarity for an absent or anonymous function.

Every heuristic match is reported with its method and score. A unique name and
an exact body are the strong signals; similarity alone is a candidate, not a
decision.

## Step 2: check native anchors

Decompile the exact stock target base APK. The tool expects the apktool layout
with `smali/`, `smali_classes2/`, `smali_classes3/`, or a single flat smali
root; it auto-detects them. You can also pass one or more `--smali-root`
arguments.

```sh
python3 -I tools/retarget/check_native_anchors.py \
  --tree "$WORK/decompiled/base" \
  --report-json "$WORK/anchors.json" \
  --report-txt "$WORK/anchors.txt"
```

The default Kotlin inputs are
`app/src/main/kotlin/dev/sleepy/app/patches/DiscordNativePatches.kt` and
`DiscordPatches.kt`. Pass `--kotlin` once per file to use different inputs.

For every entry the report checks:

- the smali path under each root;
- each exact `methodSignature` occurrence;
- each exact `anchor` occurrence;
- each `switchCaseLabel` definition line.

A missing path, method, anchor, or switch label is a failure. So is an
ambiguous one. The current `SmaliPatcher` replaces every occurrence of an
anchor with `String.replace`, so an anchor that occurs twice changes both
places; the report states the count and the line numbers rather than choosing
one. Run the check once against the stock target decode, then rerun it after
the Kotlin edits; do not point it at an already patched tree and read a pass as
proof about the stock build.

When a method or anchor is missing, the tool searches the rest of the tree for
the same exact evidence and reports an inferred move. Two examples from 354.1:

- The two `o64374.ingest.sentry.io` DSNs the table names under
  `com/discord/client_info/react/ClientInfoModule.smali` occur in
  `u5/b.smali` in the stock 354.1 decode. The checker reports that file as a
  move; it does not rewrite the Kotlin path.
- Several 354.1 `MainApplication` anchors name labels such as `:cond_b5` and
  `:goto_cf`, while the stock decode assigns `:cond_6` and `:goto_4` around the
  same `.line` numbers. The anchor is not present and needs re-derivation
  against the decompile the patch will actually run on.

Treat an inferred move as evidence. A method-only match can be a coincidence,
and a copied path can point at a different overload. Re-derive the path,
signature, anchor, and replacement body together, then rerun the check. The
tool never edits Kotlin.

`--report-only` returns 0 even when findings exist; without it the exit status
is 1 when any entry needs attention and 2 for invalid input.

## Step 3: update the source hashes

Put the downloaded tracker files in one directory: `base.apk`, each tracked
`config.*.apk`, and optionally `headers.txt`. The source's existing split list
decides which split files are tracked. Extra APKs are reported and ignored, so
a tracker directory that also carries a split the source does not list does not
fail the update.

Run with `--dry-run`, or without `--write`, first:

```sh
python3 -I tools/retarget/update_sources.py \
  --tracker-dir "$NEW" \
  --source-id discord_354201 \
  --version-code 354201 \
  --version-name "354.1 - Alpha"
```

The tool hashes `base.apk` and every tracked split, prints the old and new
values, and prints a unified diff for both copies of `sources.json`. Nothing is
written until `--write` is passed. The schema stores a size only for splits, so
the base's size is printed but not written.

```sh
python3 -I tools/retarget/update_sources.py ... --write
```

Safety checks:

- Both copies must exist, parse as schema version 2, and hold identical data.
  Use `--allow-divergent-copies` only after deciding which copy is canonical;
  it replaces the second copy with the first.
- Every tracked file must be a regular, non-empty ZIP/APK, not a symlink.
- `patch_ids` are read before the update and asserted unchanged afterwards.
- For a version code that is already recorded, a different hash is refused
  unless `--allow-same-version-hash-change` is passed. A tracker that serves a
  different build under the same code is a new fact, not a stale hash.
- `--checksums` accepts a file of `sha256  filename` pairs. When supplied,
  every tracked file must match it before anything is written. If the tracker
  publishes no checksums, record them out of band and pass that file.

The source URL and split URLs are retargeted by replacing the old version code
with the new one. A tracker path that does not follow that shape needs
`--base-url`; the tool refuses to guess when the old URL does not contain the
old code. `--new-source-id` renames the source id and moves its
`known_limitations` key in both copies. The tool does not add a source that is
not present: copy an entry in both files by hand first, then retarget it.

Other fields stay as they are. That includes `display_name`, `description`,
`patch_ids`, `changelog_url`, and the text of `known_limitations`. Review all
of them for the new release; the tool cannot write a changelog.

## Step 4: run the suite against real fixtures

The unit tests skip work whose fixtures are absent. A green run from a checkout
without fixtures has not checked the table. Set the fixture root and run the
whole suite:

```sh
FIXTURES=/path/to/fixtures       # discord/extracted/base.apk, discord/decompiled/base, ...
OUT=/tmp/verify-3541
mkdir -p "$OUT"

SLEEPY_FIXTURES_ROOT="$FIXTURES" \
SLEEPY_PATCHED_BUNDLE_OUT="$OUT" \
SLEEPY_HARNESS_OUT_DIR="$OUT" \
  ./gradlew :app:testDebugUnitTest --stacktrace
```

The fixture layout is documented in
`app/src/test/kotlin/dev/sleepy/app/testing/ComparisonApks.kt`:

```text
discord/extracted/base.apk
discord/decompiled/base
discord/tools/hermes-decomp
octogram/OctoGram_arm64.apk
octogram/OctoGram_361_arm64.apk
```

The suite writes `patched.bundle` and `patched-functions.txt` into
`SLEEPY_PATCHED_BUNDLE_OUT`. The table test requires every entry to apply with
nothing skipped.

## Step 5: verify with Hermes

The suite's parser is a reimplementation. Hermes itself is the engine that has
to run the bundle. Use `hbcdump` built from `discord/hermes` at the revision
named in `.github/workflows/verify-bundle.yml`; a stock Hermes build of another
bytecode version is not equivalent.

```sh
HBC=/path/to/hbcdump
mkdir -p "$OUT"
unzip -p "$FIXTURES/discord/extracted/base.apk" assets/index.android.bundle \
  > "$OUT/stock.bundle"

"$HBC" "$OUT/stock.bundle" -c quit
"$HBC" "$OUT/patched.bundle" -c quit

COMMANDS="$(sed 's/^/disassemble /; s/$/;/' "$OUT/patched-functions.txt")quit"
"$HBC" "$OUT/patched.bundle" -c "$COMMANDS" > "$OUT/patched-dump.txt"
```

Run the same `disassemble` batch against the stock bundle. If the batch fails,
the workflow falls back to one invocation per function so a crash is attributed
to a function id rather than to the batch. A stock failure is a toolchain fact;
a failure that appears only for a patched function is the patch. Hermes has no
semantic verifier, so this proves that every patched function decodes, not that
it does what the entry claims.

`.github/workflows/verify-bundle.yml` performs these steps in CI. It selects the
source by the literal id `discord_354201`; when a release changes the id,
update that selector in the workflow as part of the release.

## What remains manual

- **Replacement bodies and promise-shaped stubs.** A short constant stub ends
  in `76 00` and can be carried unchanged. A body that allocates, branches, or
  returns a promise cannot. Extract it from a matching patched bundle or write
  it by hand, then check it with the test suite and `hbcdump`.
- **Body edits.** Entries that alter an existing body rather than replace it
  are not recoverable from a patched bundle by size alone. Re-derive the
  register that must hold a constant, the branch target, or the edited bytes
  against the new body.
- **Native anchor re-derivation.** Class moves, obfuscated field types, changed
  labels, and changed `.line` numbers all make a path or anchor stale. The
  checker finds the absence and offers evidence; a person has to rewrite it.
  The 354.1 OkHttp merge is the clearest example: the request URL field is
  declared `Ljava/lang/Object;`, so the generated blocklist body must read that
  type and cast it.
- **New promotion surfaces.** Add stubs or hides for surfaces that did not
  exist in the old build; do not assume the old predicate still covers them.
- **Blocklist rules.** R8 renames the OkHttp classes on every release. The
  resolver finds them by shape; new host and API paths still need a rule.
- **Source and prose.** Adding a source entry, updating `display_name`,
  `description`, `known_limitations`, the README supported-build table, and the
  changelog are outside these tools.

## Known 354.1 findings

These are facts from the 353.2.0 to 354.1 port, recorded so the next port knows
where to look first.

### Bundles and table

- The 353.2.0 old bundle is 70,415,324 bytes with 163,844 functions; the 354.1
  new bundle is 50,882,260 bytes with 141,898 functions. Both are HBC 98,
  Modern12.
- The shipped 354.1 table has 416 entries. Its notes record 380 short constant
  stubs, 25 replacement bodies taken from the patched 354.1 bundle, 8 body
  edits re-derived against 354.1, and 13 entries added for surfaces the release
  changed. Thirteen 353.2.0 entries were retired because their targets no
  longer exist, and duplicate aliases that named the same 354.1 function were
  collapsed.
- The Spotify activity filter and the floating-nav Quests button were not
  re-derived. They are reported as retired rather than silently skipped.

### Class and anchor moves

- 354.1 removes `com.discord.native_experiments.NativeExperiments`. The same
  resolver moves to `me/l`, a lazy-per-experiment container, with each lazy
  implemented by `me/j`, a synthetic `Function0` with a packed switch. The
  patch pins the five 349.5 arms in slots 0 to 4 ahead of that switch. Slots 5
  to 8 have no 349.5 counterpart and stay at Discord's defaults; forcing their
  undocumented treatment values is a device-rendering gamble, not a verified
  improvement.
- The media scopes are `Li40/t1` and `Lj40/b`. The coroutine handler logs as
  `DiscordCoroutines`, and the camera scope needs a supervisor plus an
  exception handler so one failed call does not cancel all media calls.
- OkHttp is fully obfuscated. The blocklist resolver finds the Request,
  Response, Interceptor chain, and Protocol enum by shape. The request URL
  field is declared `Ljava/lang/Object;` because R8 merged unrelated classes
  into Request, so the generated body reads the declared type and casts to the
  URL type. A build whose shape cannot be found is skipped with a reason; it is
  not patched with another release's names.
- The two `o64374.ingest.sentry.io` DSN anchors the table places under
  `com/discord/client_info/react/ClientInfoModule.smali` occur in `u5/b.smali`
  in the stock 354.1 decode. The third, proxy DSN
  (`https://70545531dfe34835bf4dd0996821e8b6@discord.com/error-reporting-proxy/android/5992375`),
  is in `ClientInfoModule`. Re-derive the path and body together.
- Many 354.1 `MainApplication` anchors carry the previous decode's labels and
  `.line` numbers. A stock 354.1 decode around the same code has different
  labels (`:cond_6`, `:goto_4`), so the anchor is genuinely absent. Rebuild the
  anchor from the target decode rather than loosening it.

### New promotion and telemetry surfaces

The following 354.1 surfaces are hidden by the extra-promotion option:

- Nitro-locked toast.
- Guild-profile tag upsell.
- Premium try-it-out modal and its opener.
- Referral-program action sheet.
- Orbs Arcade quest-home section.
- Hexagon campaign sponsored header, app-icon section, display-name promo,
  display-name edit button, and sheet.

The Hexagon experiment gates share tiny bodies with unrelated config readers,
so the components are the safe stubbing point. The general theme carousel and
font picker are deliberately untouched.

Telemetry additions are emitters, not new central sinks. The PayPal SDK beacon
`https://www.paypal.com/xoplatform/logger` and the Orbs Arcade endpoints
(`/orbs-arcade/games/{id}/enrollment`, `/reset`) were the only new gaps. The
354.1 blocklist has 21 host rules and 68 API path rules. The new billing
spellings are `/purchase`, `/store`, `/authorize-payment`,
`/app-store-consent`, `/reactivation-offers`, and `/activate-perk`. Host rules
match a substring of the full URL, not a parsed host, and API rules are gated
on `/api/`; that is a known over-blocking risk, not a rule to copy blindly.

### Resources and splits

The source is the base APK plus ARM64, hdpi, xxhdpi, and English splits. The
tracker publishes no German split for 354.1. Every split carries a partial
resource table naming only its own files, so the base's table has no entries
for the merged images or strings. The same resource name can appear in more
than one split, and the split tables assign different ids. The merge keeps each
source's resource ids, configurations, and compiled paths, and refuses a table
it cannot reconcile. Do not merge by resource name alone, and do not assume the
base's table is complete.

## Tool limitations

### `retarget_hermes.py`

- It implements the HBC 97 and newer Modern12 table and string layout. Legacy
  16-byte function headers are not supported.
- Name and body heuristics can produce a plausible wrong mapping. The tool
  reports ambiguity and scores instead of hiding it, but a human still decides.
- Anonymous functions and generic short stubs carry little identity. An
  anonymous stub is reported as ambiguous unless an exact body match is unique;
  an absent-name stub is unresolved rather than guessed.
- A patched bundle is only useful if it corresponds to the same target and the
  same suite. A body that is stock in the supplied patched bundle remains a
  special entry, even if another suite would have patched it.
- Duplicate old aliases that collapse onto one new id keep the first extracted
  replacement; differing replacements are reported as conflicts. Review the
  retained entry.
- The emitted Kotlin preserves the input table's surrounding comments and
  helpers, but it is a candidate. It has not been compiled until Gradle compiles
  it.

### `check_native_anchors.py`

- The Kotlin reader supports raw strings, ordinary strings and escapes, string
  concatenation, `null`, `.trimIndent()`, and `${'$'}` or other character
  interpolations. A value built from a constant or a function call is reported
  as unsupported and not checked.
- It checks the decompile you give it. A tree from another build, another
  decompiler, or a previous patched output gives a true answer about the wrong
  input. The labels and `.line` directives must come from the same decode the
  patch will run on.
- Ambiguous anchors are findings even when the current patcher would replace
  all occurrences. Confirm that changing every occurrence is intended.
- An inferred class move is a search result. A method-only match can occur in
  several obfuscated classes; only a method plus anchor match in one file is
  strong.
- `switchCaseLabel` is checked as a definition line. The tool does not verify
  that a following `:pswitch_` case exists, which is a separate failure mode of
  `SmaliPatcher`.
- It never rewrites Kotlin.

### `update_sources.py`

- It refreshes an existing source. It does not add one, discover a new split,
  or update prose, display names, descriptions, or patch ids.
- It trusts local files. The SHA-256 is computed from what is on disk; it does
  not authenticate the tracker. Use `--checksums` with a value obtained from a
  trusted source when that distinction matters.
- It checks that each file is a non-empty ZIP/APK, not that the ZIP is a valid
  Android package or the build the version code claims.
- Both copies are written from the first copy's data. If the copies held
  different data, the second is replaced only when
  `--allow-divergent-copies` is passed; the tool warns when it does this.
- The default is a dry run. `--write` is the only way it modifies the
  repository.
