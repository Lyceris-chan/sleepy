# Audit: Discord 354.1 build

Date: 2026-11-03. Scope: the patch suite, the option manager, and the signed
APK built from `build/3541-final`.

## Build verification

- **Signed APK:** `dist/discord-alpha-354.1-patched-aligned-signed.apk`,
  129,419,206 bytes,
  SHA-256 `40676617cdd830d6075ad1f04df8b9cca879029a5ec974582a48e4ffb8db85d1`.
- **Signature:** APK Signature Scheme v2 and v3 verify. Signer is the local
  `CN=Discord Patched` certificate.
- **Version:** `354.1 - Alpha`, versionCode 354201, package `com.discord`.
- **Hermes stubs:** `tools/verify-stubs.py` reports 185/185 core targets and
  6/6 optional targets stubbed in the built APK.
- **Body edits:** profile tab lists jump over the Wishlist push, the wishlist
  index is -1, and the markup cache is capped at 2000.
- **Native edits:** the blocklist interceptor reads the request URL through
  `Ljava/lang/Object;` and casts it, the media scopes contain `Li40/t1` and
  `Lj40/b`, and the foreground-service chain forces its eligibility flag.
- **Manifest:** no advertising ID, contact permission, or Sentry provider.
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
