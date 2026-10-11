# Developer / debugger notes

Everything a fresh session needs to keep working on this project: repo layout, the test
phone's setup, and the debug tooling used to capture/inspect car traffic. See
[RC4Solution.md](RC4Solution.md) for the app itself and
[MitsubishiEclipseCrossReverseEngineerWitifProtocol.md](MitsubishiEclipseCrossReverseEngineerWitifProtocol.md)
for the protocol.

## Repo layout

- `bridge-app/` — the production app (see RC4Solution.md), and the only app in this repo.
  `build.ps1` builds it; no Gradle, no native code.
- `test-results/` — captured `.pcap`s, decode scripts, and other raw evidence from testing
  against the real car.

## Test hardware

- **Car**: Mitsubishi Eclipse Cross PHEV, 2023. Wi-Fi AP SSID prefix `REMOTE75...`, control
  service at `192.168.8.46:8080`.
- **Phone**: Nokia 7.2 (`TA-1196`, codename `DDV_sprout`), rooted, running LineageOS 21.
  - ADB serial: `D0AA002186J90211322`
  - `adb devices -l` product string: `Daredevil_00EEA`
  - Started life locked at Android 11 / security patch 2022-10-01 / build `00WW_3_690_SP07`.

## MAC pairing with the car (do this before expecting the bridge app to work)

The car appears to only accept commands from a Wi-Fi client MAC that's already been
registered with it via the **official Mitsubishi app**, not from any client that merely
knows the Wi-Fi password. See
[MitsubishiEclipseCrossReverseEngineerWitifProtocol.md](MitsubishiEclipseCrossReverseEngineerWitifProtocol.md#mac-pairing-required-done-once-via-the-official-app--not-part-of-this-protocol)
for the evidence. This repo's `bridge-app` has no pairing logic of its own and doesn't need
any — it just has to run on a phone whose MAC has already been paired.

**Before setting up a new phone (or after wiping/reflashing this one)**:
1. Install `com.mitsubishi_motors.remote_ecp` (the official app) on the phone.
2. Complete its normal setup/pairing flow against the real car at least once, over the
   car's own Wi-Fi.
3. Only then install/use `bridge-app` — it will present the same MAC the official app just
   registered.

**Why this MAC stays stable on the current test phone**: Wi-Fi MAC randomization is
globally **off** here (`settings get global wifi_connected_mac_randomization_enabled` is
unset; every network's `macRandomizationSetting` is `0`/device-MAC, confirmed via
`dumpsys wifi`), so every app on this phone — official or homebrew — always presents the
same real hardware MAC for every network, including the car's. Confirmed empirically: the
bridge app connects using the phone's real MAC (`f8:ad:cb:04:c5:3d`), not a randomized one.

**Risks to watch for**, not yet hit but worth knowing:
- If MAC randomization ever gets turned back on for this phone (a settings reset, a future
  LineageOS update changing the default, etc.), the bridge app would start presenting a
  different, unpaired MAC and climate commands would likely stop working with no useful
  error — indistinguishable from a Wi-Fi or protocol bug unless you know to check this.
- Moving to a different phone requires redoing the official app's pairing on it first; the
  bridge app cannot do this itself.
- A raw kernel-level MAC spoof (`ip link set wlan0 address ...`) was tried once to test
  this and just broke the Wi-Fi interface outright (instant `could not join` failure, ~25ms
  — the driver/HAL rejected it before any real association attempt) — not a viable way to
  present an arbitrary MAC to the car on this hardware.

## Reboot reliability: two things must be true before call-triggered control works

Both confirmed on-device via real reboot tests, not assumptions:

1. **The phone must be unlocked with its screen-lock PIN once after every reboot.**
   Current PIN: see `.secrets/session-state.md` (gitignored, not in this file). Tested by
   rebooting and placing a real call to the phone *before* unlocking it: Telecom logged
   `fi.mitsukanny.phevbridge has no call screening service defined` /
   `Call screening service binding failed`, and the call rang through normally (unscreened,
   ended as a missed call) instead of triggering anything. This is Android's Direct Boot
   restriction — before first unlock, an app's credential-encrypted state isn't available,
   and that appears to block Telecom from resolving/binding a `CallScreeningService` at all,
   not just delay it. Unlocking once (PIN via the lock screen, or `adb shell input keyevent
   KEYCODE_WAKEUP; wm dismiss-keyguard` + PIN digit taps) fixes it for the rest of that boot
   session — confirmed a call worked normally afterward.
2. **`PhevCallScreeningService` must hand the actual work to a foreground service, not a
   plain background thread.** `WifiNetworkFactory` rejects a `WifiNetworkSpecifier` request
   with `Request not from foreground app or service` unless the requesting process has
   foreground-level importance. A `CallScreeningService`'s own background thread doesn't
   qualify. This is already fixed in the app itself (`PhevCarCommandService`, see
   `RC4Solution.md`) — noted here because an early "successful" call-triggered test was
   misleading: it only worked because the app still had residual foreground importance from
   recently-used UI, not because the real unattended path worked. **Always test the
   call-triggered path from a cold app process** (fresh install, or `adb shell am force-stop
   fi.mitsukanny.phevbridge` first) to get a trustworthy result.

## Getting a fresh phone to this state

1. **Enable Developer options + USB debugging**: Settings → About phone → tap Build number
   7x → Settings → System → Developer options → USB debugging on. Connect via USB, accept the
   RSA fingerprint prompt. Verify with `adb devices -l` (should show `device`, not
   `unauthorized`).
2. **Unlock the bootloader** — HMD disabled this via OTA in Dec 2019, so a phone already
   patched past that point refuses `fastboot flashing unlock` outright. The only way back in:
   - Downgrade to signed Android 9 firmware first (factory reset → recovery →
     `adb sideload` the downgrade package → the device will show "Can't load Android
     system", which is expected → Factory data reset from that screen).
   - **The step that's easy to miss**: sideload a separate *unlock package* (also from the
     same source) before attempting the unlock — it replaces the bootloader itself. Without
     it, `fastboot oem unlock` fails with `Failed to unlock, decrypt failed!` even on the
     right firmware.
   - Then `fastboot flashing unlock` (not the older `oem unlock` syntax) succeeds.
   - Windows won't see the phone in fastboot mode without extra driver work; **WSL2 +
     usbipd-win** (`usbipd bind`/`usbipd attach --wsl`) avoids that entirely since Linux
     fastboot needs no custom driver.
   - Full step-by-step (exact firmware URLs/hashes, the Windows driver GUID fix if not using
     WSL, verification commands) is preserved in git history
     (`NokiaDowngradeBootloaderunlock.md`, removed from the working tree in the docs
     cleanup — `git log --all --diff-filter=D -- NokiaDowngradeBootloaderunlock.md` to find
     it).
   - Unlock is persistent — future OTAs don't re-lock it.
3. **Root**: flash LineageOS 21, then enable via **Settings → System → Developer options →
   Root access → ADB** (must be toggled through the actual UI; setting the prop directly via
   `adb shell setprop` doesn't reliably take). From the PC, `adb root` gives the shell uid 0
   directly — no separate `su` binary on this setup.

## Debug tooling on the phone

- **tcpdump** (packet capture without a VPN, straight off `wlan0` via a raw socket — doesn't
  interfere with or route through anything):
  ```
  adb root
  adb shell "/data/local/tmp/tcpdump -i wlan0 -w /sdcard/capture.pcap"
  # reproduce, then Ctrl+C
  adb pull /sdcard/capture.pcap
  ```
  `/system/bin/tcpdump` ships with this LineageOS build; `/data/local/tmp/tcpdump` is a
  separately pushed static v4.99.6 build (androidtcpdump.com) kept as a known-good fallback.
  Needs a root shell specifically (`adb root`), not the Termux user below.
- **Termux** (on-device shell, `openssh`/`python`/`git`/`nmap`/`curl`): install via
  `adb shell pm install <path-on-device>` after pushing the APK to `/data/local/tmp` (the
  normal streamed `adb install` fails silently for this device/build). No `tcpdump` package
  in Termux's own repo — use the copies above instead. Termux's `apt`/`dpkg` refuse to run as
  root by design; use `adb shell run-as com.termux <cmd>` (needs the debuggable termux-app
  build from GitHub releases, not the unmaintained Play Store one) to run as the correct uid
  instead of as root.
- **SSH over Wi-Fi** (no USB needed): Termux `sshd` on port 8022 (unprivileged — port 22
  needs root a normal app doesn't have), key-based auth (`passwd` can't be scripted since
  Termux reads it from the TTY, not stdin). Client key: `~/.ssh/nokia7_2_debug` on the PC.
  ```
  ssh -i ~/.ssh/nokia7_2_debug -p 8022 u0_a172@<phone-ip>
  ```
  On the home network the phone has a static DHCP reservation (`192.168.2.232`); on the car's
  own AP it'll be different — check via `adb shell ip -4 addr show wlan0`.
- **Auto-start on boot**: Termux:Boot + `~/.termux/boot/sshd.sh` (runs `termux-wake-lock` +
  `sshd`), with `com.termux`/`com.termux.boot`/`com.termux.api` whitelisted in
  `dumpsys deviceidle whitelist` so Doze doesn't kill it.

### Typical debug session

1. Connect the phone to the car's Wi-Fi.
2. `ssh -i ~/.ssh/nokia7_2_debug -p 8022 u0_a172@<ip>` for general poking around, or
   `adb shell` + `adb root` specifically for tcpdump.
3. Capture, reproduce the issue, pull the `.pcap`, inspect in Wireshark.
4. For decoding RC4-encrypted traffic from a capture: derive the seed from the plaintext
   `cmd=0x6e,reg=0x01` hello early in the capture (see the protocol doc), rebuild the KSA
   table, and XOR each subsequent message with `table[index]` — remembering `index` only
   advances on the client's own `0xf6` sends, not on every message.

## Building the app

See [RC4Solution.md](RC4Solution.md#building) — `bridge-app\build.ps1`, no Gradle, needs a
local JDK 17 + Android SDK command-line tools + kotlinc.
