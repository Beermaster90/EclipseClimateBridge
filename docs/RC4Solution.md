# RC4 solution — `bridge-app`

The working Android app: joins the car's Wi-Fi and speaks the protocol documented in
[MitsubishiEclipseCrossReverseEngineerWitifProtocol.md](MitsubishiEclipseCrossReverseEngineerWitifProtocol.md),
triggered either by a button in the app or by an allowlisted incoming phone call. Pure
Kotlin/JVM — no native code, no Gradle (a manual SDK command-line toolchain, by design).
It's the only app in this repo -- an earlier diagnostic app (`same-slot-test`, which first
proved the Wi-Fi persistent-MAC behavior this relies on) has been removed as redundant.

**Status**: physically confirmed working — heat, cool, windscreen heat and cancel all
verified against the real car through this app, via both the manual UI button and a real
allowlisted phone call, from a genuinely cold app process (not relying on any residual
foreground state from recent UI use — see `PhevCarCommandService` below, required for the
call-triggered path to actually work unattended).

An earlier attempt used a vendored `phevcore` C library via JNI/NDK. That library targets a
different car (Outlander PHEV) with an incompatible cipher and was a dead end — see the note
in the protocol doc's [encryption section](MitsubishiEclipseCrossReverseEngineerWitifProtocol.md#encryption-per-session-rc4-key-schedule-the-hard-part).
It has been removed from this repo; everything below is the current, sole implementation.

## Architecture

One place owns each concern, shared by both the manual UI and the call-triggered path so a
call behaves exactly like tapping the button would:

- **`Rc4CarClient`** — the protocol itself. Pure functions/one blocking call:
  `climateCommand()`/`cancelCommand()` build the plaintext frames, `sendAuthenticatedCommand()`
  runs the full handshake (find hello → derive seed → RC4 KSA → plaintext start-session →
  XOR'd command) over a `Socket` bound to the given `Network`. Synchronous — always called
  from a background thread.
- **`CarController`** — joins the car's Wi-Fi (`WifiNetworkSpecifier` +
  `ConnectivityManager.bindProcessToNetwork`), acquires a short `PARTIAL_WAKE_LOCK` (so a
  call-triggered run has a chance to finish with the screen off), runs `Rc4CarClient` on a
  background thread, then releases both the wakelock and the network binding. Called directly
  by `Rc4TestActivity`'s buttons, and by `PhevCarCommandService` for the call-triggered path.
- **`CarCredentials`** — SSID/host/port + AES-GCM/AndroidKeystore-encrypted Wi-Fi password,
  in one shared `SharedPreferences` file so credentials saved once in the UI are what the
  call-triggered path uses too.
- **`CallAllowlist`** — phone numbers allowed to trigger the car (normalized to last 9
  digits, so `+358...`/`00358...`/`0...` all match one entry), whether call-triggering is
  enabled, and the default mode/duration a call triggers.
- **`PhevCallScreeningService`** — a `CallScreeningService`. Checks the allowlist, silently
  rejects allowed calls (this is a "call to trigger an action" appliance pattern, not a call
  meant to be answered), and hands off to `PhevCarCommandService`. Must respond within
  Android's 5-second screening window, so the actual Wi-Fi/TCP work happens after responding,
  not inline.
- **`PhevCarCommandService`** — a foreground service that runs `CarController.runCommand()`
  for the call-triggered path specifically. Not optional: `WifiNetworkFactory` rejects a
  `WifiNetworkSpecifier` request with "Request not from foreground app or service" unless the
  requesting process has foreground-level importance, and a plain background thread inside a
  bound `CallScreeningService` does not qualify — confirmed on-device, a call-triggered
  attempt failed instantly this way the moment the app had no residual foreground importance
  left over from recent UI use. The manual UI buttons don't need this (an active `Activity`
  already has foreground importance) and call `CarController` directly.
- **`BridgeLog`** — two-level (SUMMARY/DETAIL) logger with a durable on-device log file
  (`/sdcard/Android/data/fi.mitsukanny.phevbridge/files/bridge-log.txt`, readable via
  `adb pull` even after a crash) and a crash handler.
- **`Rc4TestActivity`** — the launcher UI: SSID/password/host/port entry, mode/duration
  radio groups, Send/Cancel buttons, allowlist management, the one-time call-screening role
  request, and a log view (summary always shown, detail behind a checkbox).

## What "success" means here

`sendAuthenticatedCommand()`'s `success` flag only means the socket round-tripped some bytes
back after the command was sent — write-acks are never decoded/validated (the key index
advances after every `0xf6` send, so an ack is encoded with a different table entry than the
command was; the proven reference implementation this was ported from never validated acks
either). The only real confirmation that a command worked is physically checking the car. A
`checkStatus`/register-`0x1a`-read feature was tried and removed for the same reason it
wouldn't have added a validated signal — see the protocol doc.

Each command opens its own fresh TCP connection and both the socket (`socket.use { }`) and
the Wi-Fi network binding (`unregisterNetworkCallback` + `bindProcessToNetwork(null)`) are
released once that command's result comes back — nothing is kept alive between commands.

## Building

Requires a JDK 17, the Android SDK command-line tools (`android-35` platform + `35.0.1`
build-tools), and `kotlinc`. The
defaults in `build.ps1` point at
`%LOCALAPPDATA%\mitsubishi-android-build\...` (deliberately outside `Temp`, which Windows
cleans up) — override with `-SdkRoot`/`-JavaHome`/
`-KotlinHome` if yours live elsewhere.

```powershell
cd bridge-app
.\build.ps1
```

This compiles the Kotlin sources, links the manifest with `aapt2`, dexes with `d8`, zip-aligns
and signs with a debug keystore (auto-generated on first run at `build\debug.keystore`).
Output: `build\bridge-app.apk`.

Install/launch:

```powershell
adb install -r build\bridge-app.apk
adb shell am start -n fi.mitsukanny.phevbridge/.Rc4TestActivity
```

## First-run setup (on the phone)

0. **Prerequisite, not part of this app**: the phone's Wi-Fi MAC must already be paired
   with the car via the official `com.mitsubishi_motors.remote_ecp` app (install it,
   complete its normal pairing flow against the car once). This app has no pairing logic of
   its own — it relies entirely on the official app having already registered this phone's
   MAC. See [DEVELOPER-NOTES.md](DEVELOPER-NOTES.md#mac-pairing-with-the-car-do-this-before-expecting-the-bridge-app-to-work).
1. Launch the app once, enter the car's Wi-Fi SSID + password (+ host/port if not the
   defaults `192.168.8.46:8080`), tap **Save credentials**.
2. Pick a mode/duration and tap **Send command** to confirm it works manually.
3. To enable call-triggering: tap **Set as call-screening app** (one-time system role grant),
   add allowlisted numbers, check **Enable: allowlisted incoming calls trigger the default
   command**. The default mode/duration is whatever was last selected when credentials were
   saved or a command was sent.
4. If anything above got denied or reset, tap **Fix all permissions** — it steps through
   every requirement in order (Wi-Fi/location + phone/call-log permissions, system Location
   toggle, call-screening role, battery exemption) and skips whatever's already granted, so
   re-tapping after any single dialog just continues from where it left off.

If the phone has a lock screen configured, it must be **unlocked once after every reboot**
before call-triggered control works at all — confirmed on-device, this is an Android Direct
Boot restriction (Telecom can't even bind the call-screening service pre-unlock), not
something this app can work around. See
[DEVELOPER-NOTES.md](DEVELOPER-NOTES.md#reboot-reliability-two-things-must-be-true-before-call-triggered-control-works).

## Permissions

`ACCESS_FINE_LOCATION`/`NEARBY_WIFI_DEVICES` (Wi-Fi scan/join), `READ_PHONE_STATE` +
`READ_CALL_LOG` (call screening), `WAKE_LOCK`, `POST_NOTIFICATIONS` (for
`PhevCarCommandService`'s foreground-service notification), plus the standard Wi-Fi/network
and `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_CONNECTED_DEVICE` permissions. Most are
requested at runtime via **Fix all permissions** above.
