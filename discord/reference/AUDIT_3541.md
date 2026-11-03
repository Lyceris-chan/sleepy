# Audit: Discord 354.1 build

Date: 2026-11-04. Scope: the patch suite, the option manager, and the signed
APK built from `build/3541-final`.

## Build verification

- **Signed APK:** `dist/discord-alpha-354.1-patched-aligned-signed.apk`,
  129,406,918 bytes,
  SHA-256 `d1eb8977cb635a4190ae580f61037b28e0546453f47a80122ae25c825d71dcd2`.
- **Signature:** APK Signature Scheme v2 and v3 verify. Signer is the local
  `CN=Discord Patched` certificate.
- **Version:** `354.1 - Alpha`, versionCode 354201, package `com.discord`.
- **Hermes stubs:** `tools/verify-stubs.py` reports 189/189 core targets and
  6/6 optional targets stubbed in the built APK.
- **Body edits:** profile tab lists jump over the Wishlist push, the wishlist
  index is -1, the markup cache is capped at 2000, and the Manage
  Subscriptions route carries the false predicate. The guild-tag upsell flag
  is false while the guild list is preserved.
- **Experiments:** 354.1's `me/j` resolver pins RMLE mounting mode 3, Fresco 1,
  Hermes 1, ChatMosaic 1, and JankPerScreen CONTROL.
- **Native edits:** the blocklist interceptor reads the request URL through
  `Ljava/lang/Object;` and casts it, the media scopes contain `Li40/t1` and
  `Lj40/b`, the handler logs as `DiscordCoroutines`, and the foreground-service
  chain forces its eligibility flag.
- **Blocklist:** 21 host rules and 68 API path rules, including the new 354.1
  billing spellings, `/orbs-arcade`, and the PayPal SDK logger path.
- **Manifest:** no advertising ID, contact permission, or Sentry provider
  (only `io.sentry.auto-init=false` remains).
- **Translations:** 70 catalog files remain (English only).
- **Removed libraries:** no Sentry `.so` files are packaged.

## Audit findings fixed before this build

- **Blocklist field type.** The interceptor read `b5/a->e` as `Lk50/w;`, but
  the field is declared `Ljava/lang/Object;`. The build now reads the declared
  type and casts. A profile check verifies the field, chain, proceed method,
  headers constructor, and interceptor signature before writing.
- **Camera scope.** A plain Job cancelled all 64 media calls on the first
  failure and sent the exception to the process handler. The scope now has a
  supervisor and a coroutine exception handler; the internal media scope gets
  the same treatment. See [`MEDIA_SCOPE_3541.md`](MEDIA_SCOPE_3541.md).
- **Option validation.** Hand-edited options are normalized, malformed files
  warn instead of crashing, numeric values are range-checked, and the CLI and
  patcher use the same boolean coercion.
- **Blocklist failure policy.** A missing OkHttp profile now fails the build
  instead of reporting success without the blocklist.
- **Patch summary.** `02-patch.sh` counts only the current logs and exits
  nonzero when a `[FAIL]` line is present.
- **Patch manager accessibility.** The selector has an Enter detail toggle, a
  small-terminal guard, a non-TTY fallback, and a discard confirmation. State
  uses `[x]`/`[ ]` and text, not color alone.
- **Documentation.** The README table has a header, the option list describes
  per-build precedence, and the changelog and reader summary avoid absolute
  claims and future tense.
- **Experiment pins.** 354.1 removed `NativeExperiments`; the five arms the
  349.5 suite selected are pinned in the replacement `me/j` resolver. See
  [`EXPERIMENTS_3541.md`](EXPERIMENTS_3541.md).
- **Dropped billing row.** `useShowManageSubscriptionsSetting` no longer exists
  as a closure. The Manage Subscriptions route now carries a false
  `usePredicate`, so the row is hidden without stubbing the shared premium
  predicate.
- **New monetisation surfaces.** The Nitro-locked toast, guild-tag upsell,
  premium try-it-out section/upsell/modal/opener, referral action sheet, Orbs
  Arcade quest section, and Hexagon campaign surfaces are hidden. Hexagon's
  experiment gates share their bodies with unrelated config readers, so its
  campaign-specific components are stubbed instead.
- **Telemetry audit.** No new central sink bypasses the existing chokepoints.
  The PayPal SDK logger path and the Orbs Arcade endpoints were the only gaps;
  both are now covered. See [`TELEMETRY_3541.md`](TELEMETRY_3541.md).
- **No-op stub verification.** The optional-patch check expected a literal
  `debugger;` from a void stub; it now accepts the empty body that
  `LoadConstUndefined; Ret` actually decompiles to.

## Residual risks

- **No device run.** The camera-scope change is verified by smali assembly and
  artifact inspection, not by a call on hardware. Toggle the camera repeatedly
  and watch `adb logcat -b crash`.
- **Host matching.** Host blocklist entries match a substring of the full URL,
  not a parsed host. A query string that contains a blocked host name is
  over-blocked. The API rules are gated on `/api/`; the host rules are not.
- **In-place writes.** Smali and bundle edits are written in place. A full disk
  or a crash during patching can leave a truncated tree; rerun from a clean
  decode.
- **Retired targets.** The Spotify activity filter and the floating-nav Quests
  button are not re-derived for 354.1. They are reported as retired, not
  silently skipped.
- **New experiments.** The three 2026-10 experiments (chat preinflation,
  UI-manager experiments, UI-manager constants cache) are left at Discord's
  defaults. Their treatment values are undocumented in the APK and they need a
  device pass; see [`EXPERIMENTS_3541.md`](EXPERIMENTS_3541.md).
