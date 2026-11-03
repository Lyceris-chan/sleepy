# 349.5 ships React-Compiler-compiled components behind a flag that is off

## What changed

349.5 is the first Discord Android build compiled with the React Compiler.
Every component is emitted **twice** — a compiled and an uncompiled variant —
and the choice is made at runtime, at module-init time:

```js
const memoResult = importAllResult.memo(
  arg1(dependencyMap[3]).isReactCompilerEnabled()
    ? (() => { /* compiled   */ })
    : (() => { /* uncompiled */ })
);
```

`modules/react_compiler/ReactCompilerGating.tsx` is the gate:

```js
function isReactCompilerEnabled() { return ReactCompilerExperiment.getCachedEnabled(); }
function isReactCompilerBuild()    { return true; }
```

The experiment is declared in `modules/libdiscore/libdiscoreExperiments.tsx`:

```
new LibdiscoreWrapperSimpleExperiment("2026-09-react-compiler-mobile", ...)
  label        = "React Compiler for mobile"
  defaultValue = false
```

**Default false.** So the compiled half is dead weight on a stock client:
the app always takes the uncompiled branch.

## Why this made the version bump look impossible

This one change explains three measurements that otherwise look alarming:

| Observation | Explanation |
|---|---|
| functions **128,469 → 155,429** (+21%) | ~27k generated memo/wrapper functions |
| strings **337,829 → 337,163** (−666) | no new *code*, so no new literals |
| bytes/function **433.5 → 433.0** (flat) | same code, more records |
| `NitroUpsellButton` etc. gone as *function names* | components are now anonymous arrows passed to `memo()` |

The last one is what broke the old remap: `remap_hermes.py` maps by name, and
the name string for a component is simply **not in the table any more**. The
module path still is, which is why the structure-based remap works.

It also explains `AddToWishlistGrid: body shared with 358 other function(s)`:
the compiler emits large numbers of byte-identical trivial closures.

## The structural pattern (how to find a component's ids)

The split is right there in the bytecode, so it can be parsed rather than
guessed:

```
GetByIdShort r1, r6, 13, "isReactCompilerEnabled"
Call1       r1, r1, r6
JmpTrue     L416, r1
CreateClosureLongIndex r1, r5, 71380     <- enabled() == false
Jmp         L423
L416:
CreateClosureLongIndex r1, r5, 71379     <- enabled() == true
L423:
```

Both ids are **one logical closure**. `tools/remap_by_module.py` groups them
(`groups_of`) so an index recorded against a pre-compiler bundle keeps its
meaning on 349.5. `NitroUpsellButton` is the clean example:

```
348.2 factory 9426 groups: ((49956,),)                 # one closure
349.5 factory 9649 groups: ((52477, 52476),)           # the same one, split
```

## Turning it on

`DISCORD_REACT_COMPILER = False` in `patches/core.py`. Set it `True` and
`patch_react_compiler_flag` stubs `isReactCompilerEnabled` to `return true`.

The target is found **structurally** — xref
`modules/react_compiler/ReactCompilerGating.tsx` → factory → the
`CreateClosure` feeding the `PutByIdStrict ..., "isReactCompilerEnabled"`
store — because there is no id or function-name to match on.

### There is a built-in way to see whether it worked

`modules/user_settings/defs/native/ReactCompilerSetting.tsx` is a **status
row**, not a toggle:

```js
useTitle()     => "React Compiler"
useTrailing()  => isReactCompilerEnabled() ? "Enabled" : "Disabled"
usePredicate() => isReactCompilerBuild()   // hardcoded true -> always visible
```

So Settings shows a **React Compiler** row; it reads *Disabled* on a stock
build and should read *Enabled* once the flag is forced. That is the
verification, with no logcat needed.

### Why it is opt-in rather than on by default

It is a real perf lever — auto-memoisation is aimed squarely at the re-render
jank this project has been measuring — but it selects a code path Discord has
never shipped to users, and it changes **every component at once**. A wrong
component id here does not fail loudly; it silently renders or hides the wrong
thing. Measure before making it a default.

Suggested pass: `dumpsys gfxinfo` jank percentiles before/after, which is the
same measurement that showed the jank-instrumentation removal working
(16.75% → 4.07% janky, p90 40 ms → 15 ms).

## Other experiments in this build (none new)

Read out of `modules/libdiscore/libdiscoreExperiments.tsx`. Only the React
Compiler entry is new in 349.5; the rest were present in 348.2 and are listed
here so a future bump does not re-derive them:

| Key | Label | Default |
|---|---|---|
| `2026-09-react-compiler-mobile` | React Compiler for mobile | **false — NEW** |
| `2026-08-android-jank-per-screen` | Android Per-Screen Jank Aggregation | false |
| `2026-08-android-rn-reparenting-flag` | RN flag, "suspect of causing RMLE regressions" | false |
| `2026-03-mobile-hermes-occupancy-target` | Android Hermes Occupancy Target | false |
| `2026-02-android-chat-mosaic-shared-pool` | Android Chat Mosaic Shared Pool | false |
| `2026-01-android-rmle` | Android Pull Mode Rendering | 4 treatments |
| `2026-02-android-fresco-cache` | Android Fresco Cache | 3 treatments |
| `2025-11-defer-load-late-lazy-cache` | Allow react to render before lazy cache loads | false |

`2026-08-android-jank-per-screen` is worth noting because this project already
disables per-screen jank tracking — `NativeExperiments` in smali is patched
for exactly that, and this is its JS-side counterpart.
