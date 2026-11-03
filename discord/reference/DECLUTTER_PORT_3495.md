# Porting `declutter` — what was safe to take

Source: `git.gay/kmmiio99o/revenge-next-plugins`, `plugins/declutter`. It is a
Revenge (Discord Android client mod) plugin, so its source is real, current
Discord JavaScript — but it patches **at runtime**, which changes what is
portable. This is the port, with the safety reasoning for each item.

## The rule that decided everything

**Stub a component, or stub a data parser. Do not stub a hook whose shape you
have not read.**

A React function component nulled to `return null` renders nothing, always, for
every caller. A hook is different: if callers do `.map` or `.length` on the
result, a wrong stub throws rather than merely failing to hide something.

That rule is why the avatar-decoration port is **one** edit where the plugin
needs four.

## Avatar decorations — `parseAvatarDecorationData` (25406) only

The plugin stubs `useAvatarDecoration`, `getAvatarDecoration`,
`parseAvatarDecorationData`, *and* surgically rewrites the `avatarDecoration`
prop inside `Avatar.tsx`. Only the third is needed, and it is the safest of the
four: it is on the **data** path, not the render path.

Its callers are the transforms that bring a decoration into the client at all:

```
transformUser                    23962, 50210
transformGuildMember             67166
handleGuildMemberUpdate          27261
handleGuildMemberProfileUpdate   27310
getAvatarDecorationFromServerMember  27263
```

and every one only **assigns** the result — `transformUser` does
`PutByIdStrict r3, r6, 7, "avatarDecorationData"` and nothing else with it.

Null is a first-class return there, not a new shape: the function already has two
`return null` paths (non-object input, and `parseSkuIdFromServerData` returning
null). Downstream, `getAvatarDecoration` (45453) explicitly handles a null
`member.avatarDecoration` and falls back to `arg0.avatarDecoration`, which this
edit also makes null — so no default decoration appears in its place.

Two bonuses over the plugin's approach: the render hooks stay intact (so no
caller of theirs can throw), and because this is where the data enters, it also
covers **native chat rows**, which the plugin can only reach through its
`declutter.configure` runtime bridge and which no static JS edit could otherwise
touch.

## The rest — components and boolean gates

| Module | Export | Live / compiled | Shape |
|---|---|---|---|
| `collectibles/nameplates/native/Nameplate.tsx` | `default` | 47350 / 47349 | NULL |
| `collectibles/profile_frames/native/ProfileFrame.tsx` | `default` | 45489 / 45488 | NULL |
| `guild_tag/native/GuildTag.tsx` | `default` | 51635 | NULL |
| " | `GuildTagBadge` | 51632 / 51631 | NULL |
| " | `BaseGuildTagChiplet` | 51634 / 51633 | NULL |
| `guild_tag/native/VoiceGuildTag.tsx` | `default` | 72535 / 72534 | NULL |
| `guild_tag/GuildTagUtils.tsx` | `shouldDisplayGuildTag` | 45222 | FALSE |
| " | `useShouldDisplayGuildTag` | 45218 / 45217 | FALSE |
| `user_profile/hooks/useBadges.tsx` | `default` | 45550 / 45549 | EMPTY_ARRAY |

Every one was checked to be a React function component taking a props object
(`arg0.nameplate`, `arg0.primaryGuild`, `{source, size}`, `{containerStyles,
guildTag, guildBadge, onPress, disabled}`, ...) before being given the null
shape.

The two guild-tag gates are belt and braces with the component nulls: they stop
the callers that *decide* whether to build a tag row, so nothing is constructed.

### The targets were resolved by module path, not by name

The plugin finds things with `kmmiio-lib` filters that match on
`exports.default.name`, and the 349.5 React-Compiler closures are **anonymous in
the function table**. Its discovery does not work on this bundle. Every id above
came from `xref --query <module path>` -> factory -> group the closures by
`isReactCompilerEnabled()`, and for each pair the **first** id is the
`false`-branch, i.e. the live one.

## New stub shape: `EMPTY_ARRAY_TARGETS`

`useBadges` is the first target whose callers *iterate* the result, so none of
the existing shapes fit — `undefined`, `null` and `0` all throw on `.map`.
Rather than a bespoke body edit, it gets a fifth shape alongside the others:

```
EMPTY_ARRAY_HASM = "0000  NewArray r0, 0\n0001  Ret r0\n"    ->  return [];
```

It is used only where the function's **own body already returns `[]` on one of
its paths** — `useBadges` does exactly that twice (`if (null == arg0) return [];`
and `if (badges == null) badges = [];`). That is the evidence the shape is one
its callers handle, not a guess. The shape is plumbed through `core.py`'s stubs
dict, its post-stub check and `already_stubbed()` allowlist, and
`tools/verify-stubs.py`'s `STUB_LINES`.

## Deliberately not ported

| Plugin item | Why not |
|---|---|
| **Nitro You-bar upsell button** | Needs an operand-only edit in the floating-nav builder (same body family as `floating_nav_quests`, fn 75650). Doable, but it is a 1,000-instruction body and the item only appears for accounts *without* a subscription — low yield, real risk. Left for a pass with time to do the liveness analysis properly. |
| **Boost goal bar** | `guild_sidebar/useGuildActionRows.tsx` must keep returning the *other* rows, so it needs array-filtering body surgery — a length change inside a body with jumps, which is exactly `HBC_BODY_EDIT_HAZARD.md`. Boosted servers only. |
| `useNameplate`, `useProfileFrame` hooks | Redundant: the components are nulled, so nothing renders regardless of what the hooks return. Stubbing them would add shape risk for no visible gain. |
| `getAvatarDecoration`, `useAvatarDecoration` | Same — with the parser nulled, both are null already. |
| **Profile effects, settings page, wishlist, quest dock / You-tab quests+shop+orbs** | Already covered by other levers; nothing to add. |
| `declutter.configure` native bridge | Runtime-only. Not expressible as a static stub. Moot for decorations now (the parser covers it), still true in principle for anything else routed that way. |

## State

```
patch:  0 FAIL, 192 Hermes functions stubbed (was 175), 67 OK
build:  discord-alpha-349.5-patched-aligned-signed.apk
verify: PASS 202/202   (185 before; the verifier learned the [] shape)
```

Nothing has been on a device. The visible effect to check: profile pictures
without decoration rings, usernames without gradient plates or guild tag chips,
avatars without frames, and no badge row on a profile.
