# What's different in this Discord build

This version is based on **Discord 354.1 Alpha (354201)**.

The changes are the same kind as before: no crash reporting, no telemetry, no
ads or storefront prompts, and fixes for calls that froze or dropped. This
release also adds an option picker: run `tools/patches.py edit`, or read
[`OPTIONS.md`](OPTIONS.md), to turn individual behaviour patches on or off
before building.

This is a modified copy of the Discord Android app. Nothing about how you chat,
call or use Discord has been redesigned — the changes are all about what the app
does **behind the scenes**.

The short version: **this version stops the app's analytics and crash
reporting, removes the store and promotion surfaces, and fixes the call-freeze
paths described below.** Messaging, voice, video, notifications, and file
uploads are unchanged.

---

## What changed

- **No ads, no Nitro popups, no "get more" buttons.** Tapping where they used to
  be does nothing instead of opening a sales page.
- **No Spotify.** The integration is gone on purpose (see below).
- **The Shop and Quest areas are gone** from the app.
- **Calls are more stable.** The specific bug where a call would drop but the
  notification still said you were connected is fixed.
- **Background work is lower.** Analytics, jank recording, the permanent
  logcat child, and several startup loads no longer run, which can reduce
  battery use and stutter.

---

## Tracking — the app no longer reports what you do

Discord normally sends a steady stream of information about how you use the app:
which screens you open, which buttons you press, how long sessions last, and how
well your device is performing. It also runs a crash reporter called **Sentry**
that collects diagnostics.

In this build:

- **All of that reporting is switched off.** Roughly a hundred separate places in
  the app that used to send these reports now do nothing.
- **The crash reporter is dead at every level** — the part built into the app,
  the part that runs inside the JavaScript engine, and the part that would
  normally start automatically when the app launches.
- **The app no longer reads your phone's system log.** It used to keep a
  background `logcat` process running for the whole time the app was open,
  reading every line the system wrote and matching it against a list of
  patterns. That process no longer starts.
- **Debug-level logging is off.** The app's own debug logging is silenced; real
  errors are still recorded so problems remain diagnosable.
- **The app no longer describes itself as modified** to Discord's servers.

The network blocklist covers the known analytics hosts and API paths listed in
the source. A new endpoint on a new host or path is not blocked until it is
added to that list.

---

## Advertising and attribution

These are the services apps use to work out who installed them and how to target
advertising. All are disabled:

- AppsFlyer (and its short-link domain)
- Google Analytics and Firebase Analytics
- Google's advertising ID — the permission is removed from the app entirely
- The Google Play install referrer
- Adjust, Amplitude, Segment, and Braintree's analytics endpoint
- Google's mobile ads beacon and PayPal's conversion tracking

---

## Money — stores, quests, Nitro

Everything that exists to take payment or drive engagement is blocked or hidden:

- **The Shop** — all storefront, price, catalogue and purchase requests are
  refused before they leave the phone, and the Shop button no longer appears.
- **Quests** — the quest dock that appears over the app is hidden, quest data is
  never requested, and the Quests entry is gone from Settings.
- **Orbs** — the in-app currency is switched off at the source. The balance
  widget, orb price tags and the orb shop carousel no longer appear anywhere.
- **Wishlists** — the "add to wishlist" buttons are gone.
- **Profile decoration** — the decoration, effect, frame and nameplate screens
  are no longer reachable, and the "Edit Profile" entry does nothing.
- **Nitro and subscriptions** — the popups, banners and "Get Nitro" buttons no
  longer open anything. This includes the gift button's sales paths.
- **Guild boosts, gifts, wishlists, virtual currency, role subscriptions,
  referral programmes and promotions** — all blocked.
- **The guild shop row** in the channel sidebar is hidden.

If you already have Nitro, it still works. The app stops advertising it and
stops opening the store surfaces.

---

## Spotify — removed on purpose

The Spotify integration is switched off: connecting an account, "Listen Along",
the Spotify card in your profile, and the app broadcasting what you're listening
to.

**This is intentional, not a bug.** It was a deliberate choice when the build was
made, so do not report it as something that needs fixing.

---

## Privacy

- **Your contacts are no longer read.** The app used to read your address book to
  suggest friends; it now returns an empty list instead.
- **It no longer checks which other apps you have installed.** Discord used to
  probe for around twenty different social and gaming apps.
- **Discord's game-integration service is no longer exposed to other apps.** It
  used to be reachable by anything on your phone with no permission check.

---

## Stability and speed — the freeze fixes

The original problem report was a call that froze the phone and then dropped,
while the notification still claimed you were connected. Several separate causes
were found and fixed:

- **Calls dropping while still showing "connected".** When you put the app in the
  background during a call, Android was demoting the call from a "microphone" task
  to a "background data" task — which silently cuts your microphone. The app now
  keeps claiming the microphone. *This is the main fix and the one worth testing.*
- **A camera failure can no longer cancel every media call.** The media engine
  ran its work in a scope that cancelled itself and every other media call on
  the first exception, then passed that exception to the process crash handler.
  It now uses a supervisor and a coroutine exception handler, so a camera
  failure leaves the call and the rest of the media engine running.
- **The app was making freezes worse.** When the app noticed its JavaScript was
  struggling, it responded by taking a full performance profile of the struggling
  code, writing it to disk and reading it back. It now doesn't.
- **A timer that ran forever.** Every video tile had a timer firing every four
  seconds, doing work whose result was thrown away. Removed.
- **A 60-second timer that never stopped.** Removed.
- **Every network request was doing unnecessary work.** The app was assembling a
  copy of all its own request headers — including a very large one — and then
  searching that text 81 times, on every single request. It now looks only at the
  web address.
- **Log noise removed.** Debug logging was slowed down or removed; genuine errors
  are still logged.

---

## What has not changed

- **Messaging, voice, video, notifications and file uploads work normally.**
- **You can't buy anything.** If you need to make a purchase, use the official
  Discord app.
- **Removed screens do nothing when you tap where they were.** They don't open a
  blank page.
- **Discord can still identify you** from your account, your IP address and
  normal usage. This build stops the extra reporting. It doesn't make you
  anonymous.

---

## If something goes wrong

The two things worth checking after a call:

1. **That calls stay connected when you background the app.** Start a call, press
   home, wait a minute, come back. If the call survived, the main fix is working.
2. **That audio and video still work normally** in a call.

If you find a problem, note what you were doing and report it.
