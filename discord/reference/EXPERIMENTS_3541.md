# Experiments in Discord 354.1

Date: 2026-11-04. Scope: the native experiment resolver in the 354.1 dex and
the Hermes experiment gates that remain in the JS bundle.

## What moved

349.5 exposed `com.discord.native_experiments.NativeExperiments` with one
lambda per experiment. 354.1 removes that class and moves the same resolver to
`me/l`, a `Lazy`-per-experiment container. Each lazy is `me/j`, a synthetic
`Function0` with a packed switch on the experiment slot. The old lambda
markers therefore no longer exist, and the 349.5 experiment patch correctly
reported "NativeExperiments.smali missing" instead of silently failing.

`patch_3541_experiments` pins the five arms the 349.5 suite selected by adding
a sparse switch ahead of the packed switch. The fixed arms are:

| Slot | Experiment | Pinned arm | Effect |
| ---: | --- | --- | --- |
| 0 | `2026-01-android-rmle` | treatment 3 | accumulated raw props, props reconciliation, pull model |
| 1 | `2026-03-mobile-hermes-occupancy-target` | treatment 1 | Hermes occupancy target |
| 2 | `2026-02-android-fresco-cache` | treatment 1 | 70% memory-cache reduction |
| 3 | `2026-02-android-chat-mosaic-shared-pool` | treatment 1 | shared pool |
| 4 | `2026-08-android-jank-per-screen` | treatment 0 (CONTROL) | per-screen jank tracking off |

The mapping was checked against the 349.5 bootstrap classes. Treatment 1
produced `(1, 70, false, false)` for Fresco, `(1, true)` for ChatMosaic, the
`TREATMENT` state for Hermes and the shared-pool flag for ChatMosaic.
JankPerScreen treatment 0 produced `CONTROL`, whose `isPerScreenEnabled()` is
false. The 354.1 classes (`me/c`, `me/d`, `me/b`, `me/g`, `me/e`, `me/h`) carry
the same fields, so the arms are equivalent.

## New experiments

354.1 adds three slots that have no 349.5 counterpart:

| Slot | Experiment | Consumer | Decision |
| ---: | --- | --- | --- |
| 5 | `2026-08-android-rn-reparenting-flag` | `n6/f.enableFixForParentTagDuringReparenting` | left at stock |
| 6 | `2026-10-android-chat-preinflation` | `f9/g0` constructor pre-inflation | left at stock |
| 7 | `2026-10-uimanager-experiments` | RN `discordUiManagerOptimization` double | left at stock |
| 8 | `2026-10-android-ui-manager-constants-cache` | `u5/b` UI-manager constants cache | left at stock |

Slot 5 is the reparenting flag already documented in
[`INTL_SPOTIFY_PERF_3495.md`](INTL_SPOTIFY_PERF_3495.md). Its polarity is
inverted and treatment 1 disables a fix that upstream React Native enabled;
forcing it is a behavioural gamble, not a clear win.

Slot 6 only gates a pre-inflation call in the chat list constructor. A
positive treatment skips `f9/b.n`, so forcing it would remove work rather than
add it. The experiment is a rollout of removing pre-inflation, and which arm
ships depends on Discord's server configuration.

Slots 7 and 8 are exposed to React Native as a double and to the UI-manager
constants cache as a boolean. Their treatment values are not documented in the
APK, and neither is covered by a static call-site test. Forcing an unknown
integer into a UI-manager cache is exactly the kind of change that renders
wrongly rather than failing loudly, so both stay at the server default until a
device pass exists.

## React Compiler

`DISCORD_REACT_COMPILER` remains opt-in and off by default. Forcing
`isReactCompilerEnabled()` on selects the compiled half of every component at
once. The 349.5 notes, including the Settings status row that reports
Enabled/Disabled, still apply.
