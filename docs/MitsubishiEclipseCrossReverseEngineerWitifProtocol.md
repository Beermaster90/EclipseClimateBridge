# Mitsubishi Eclipse Cross PHEV — remote climate-control Wi-Fi protocol

Reverse-engineered from the official `com.mitsubishi_motors.remote_ecp` Android app (real
traffic captures + decompiled source), confirmed against a real 2023 Eclipse Cross PHEV.
This is the distilled, working result — see [RC4Solution.md](RC4Solution.md) for how it's
implemented in this repo's app.

## Network

- The car exposes a Wi-Fi AP (SSID prefix `REMOTE75...`, WPA2). Connect to it (e.g. Android
  `WifiNetworkSpecifier`).
- The car's control service listens on **TCP `192.168.8.46:8080`**.
- The wire protocol itself (framing, RC4 handshake, register writes below) has no
  device-ID/MAC field anywhere in it, and needs no separate handshake beyond what's
  documented here. **But the car does appear to require the client's Wi-Fi MAC to already
  be paired via the official Mitsubishi app** — see "MAC pairing" below. This was not
  originally caught (an earlier version of this doc claimed no pairing was needed at all),
  because all testing happened on a phone whose MAC had already been paired.

## MAC pairing (required, done once via the official app — not part of this protocol)

The car's control service does not accept commands from just any client on its Wi-Fi — the
client device's MAC address needs to have been registered with the car first, and that
registration happens through the **official `com.mitsubishi_motors.remote_ecp` app**, not
through anything documented in this file. Evidence:

- The dedicated test phone (Nokia 7.2) had its bootloader unlocked and was completely
  reflashed (Android 11 → 9 → LineageOS 21) partway through this project. After that
  reflash, the official app needed to go through its pairing flow again before it would
  control the car — it did not just work again on the new OS install.
- The bridge app in this repo has **no MAC-handling code at all** — it never reads, sets,
  or spoofs a MAC, it just joins the car's Wi-Fi via `WifiNetworkSpecifier` and lets
  Android decide what MAC to present. It has only ever been tested on the same phone,
  after the official app had already re-paired that phone's MAC with the car. Every
  successful RC4/climate-control test in this repo was implicitly riding on that prior
  pairing, not proof that pairing is unnecessary.
- A live test forcing a different (spoofed) MAC at the kernel level failed instantly
  (~25ms — too fast to even be a real Wi-Fi association attempt), because the phone's own
  Wi-Fi driver/HAL rejected the raw spoof before a connection to the car was ever
  attempted. That test is inconclusive about the car's behavior — it only shows kernel-level
  MAC spoofing isn't a working test method on this hardware, not that the car rejects
  unpaired MACs. Nobody has yet run this from a device whose MAC was *never* paired via the
  official app, so "the car requires MAC pairing" is the best-supported explanation given
  the evidence above, not an independently confirmed fact.

**Practical upshot**: before this project's bridge app can control a given car, the
official Mitsubishi app must be installed on the same phone and its pairing flow completed
at least once. After that, this repo's app works with no further pairing step of its own,
because it presents the same MAC the official app just registered (see
[DEVELOPER-NOTES.md](DEVELOPER-NOTES.md) for why that MAC stays stable on the current test
phone, and the operational risk if it ever doesn't).

## Message framing (before encryption)

Every message: `[cmd][len][type][reg][data...][checksum]`

- `cmd` (1 byte): message/command type. Relevant values:
  - `0xf3` = read-register request
  - `0x3f` = read-register ack/response
  - `0x6f` = register value push (car → app, both unsolicited and read responses)
  - `0x6e` = "hello" / device-info push (see below)
  - `0xf6` = **write register / SEND_CMD** (used both for real control commands and as a
    generic ack for reads elsewhere in the protocol — the byte value is overloaded, only
    `reg=0x1b` writes matter for climate control)
  - `0xe6` = start/end remote session (see plaintext bypass below)
- `len` (1 byte): total message length minus 2 (i.e. `len = total_bytes - 2`).
- `type` (1 byte): `0x00` for requests, `0x01` for the corresponding acks/responses (not
  universally consistent, don't rely on it beyond framing).
- `reg` (1 byte): register number. **`0x1b` is the climate-control register.**
- `data` (variable): payload, length = `len - 3`.
- `checksum` (1 byte): sum of all preceding bytes (cmd through last data byte) mod 256.

Example, plaintext, unencrypted: `f3 04 00 00 00 f7` = read register 0, checksum
`f3+04+00+00=f7`. ✓

## Encryption: per-session RC4 key schedule (the hard part)

Every message on the wire (after the initial plaintext phase) is XORed byte-for-byte with a
single rotating key byte. This is **not** a formula over time or a counter — it's an
RC4-style key-schedule table, recovered by decompiling the official app (classes `x0.z`,
`x0.y`, `com.mitsubishi_motors.remote_ecp.iMobile_AppGlobalVar`).

A separate, unrelated open-source library (`phevcore`/`phevctl`, github.com/phev-remote) was
investigated early on as a possible shortcut — it targets the Outlander PHEV and has its own,
different, incompatible key mechanism (`phev_core_getXOR()`). That whole avenue was a dead
end for this car and is not used anywhere in this repo's solution.

### 1. Connect, still in plaintext

Right after the TCP connect, a client can send plaintext requests — e.g. a register-0 poll
`f3 04 00 00 00 f7`. The car will spontaneously push a **"hello"** message:

```
cmd=0x6e, type=0x00, reg=0x01, 9 data bytes, plaintext (XOR key 0x00)
```

### 2. Derive the session seed from the hello

The seed is hidden as **bit 3 (0x08) of each of the hello's first 8 data bytes**, packed
LSB-first:

```
seed = sum over k=0..7 of ( (hello_data[k] >> 3) & 1 ) << k
```

### 3. Build the 256-entry key table (RC4 KSA, repeating single-byte "key" = seed)

```
S = [0, 1, 2, ..., 255]
j = 0
for i in 0..255:
    j = (j + S[i] + seed) mod 256
    swap(S[i], S[j])
# S is now the key table
```

### 4. Use the table

- `index` starts at **0** as soon as the table is built.
- The XOR key for **any** message (either direction) at the current moment is `S[index]`.
- `index` only **advances by 1** when the app itself *sends* a message whose plaintext
  `cmd == 0xf6`. Since nothing has been sent yet when the table is built, **the very first
  `0xf6` on a fresh connection always uses `S[0]`** — no need to replay a register-sweep
  first.

### 5. Special always-plaintext message

`cmd == 0xe6 && type == 1` is **never encrypted**, regardless of key state. This is the
**"start remote session"** message and must be sent (once, plaintext) before the real
climate command, or the car will ack writes but silently not actuate them:

```
e6 04 01 01 00 ec   (checksum 0xe6+0x04+0x01+0x01+0x00 = 0xec)
```

## Working connection sequence (proven, from a cold TCP connect)

1. Open TCP socket to `192.168.8.46:8080`.
2. Send `f3 04 00 00 00 f7` (plaintext register-0 poll) — not strictly required to elicit
   the hello, but reliably speeds it up.
3. Read from the socket until a valid `cmd=0x6e,type=0x00,reg=0x01` message is found (scan
   the raw buffer, byte-by-byte, validating length + checksum at each candidate offset — it
   arrives interleaved with other short plaintext acks). Extract its 9 data bytes.
4. Compute `seed` (step 2 above), build the table `S` (step 3), `index = 0`.
5. Send `e6 04 01 01 00 ec` (plaintext) on the same socket. Read/discard its ack.
6. Build the real command: `f6 [len] 00 1b [data] [checksum]` (plaintext), XOR every byte
   with `S[0]`, send it. Read the ack (its content isn't otherwise validated — getting any
   sane-length response back is a good sign, but the only real confirmation is physically
   checking the car).
7. Each command opens its own fresh connection — simplest, and the one proven to work; no
   need to keep a session alive or track `index` across multiple sends.

## Climate control command (register `0x1b`)

Plaintext (pre-XOR) payload for `cmd=0xf6, reg=0x1b`:

```
02 [mode] [duration_code] [delay_index]
```

- **mode**: `0x01` = cool (A/C), `0x02` = normal cabin heat, `0x03` = windscreen heat/defrost
- **duration_code**: `0x00` = 10 min (default), `0x01` = 20 min, `0x02` = 30 min
- **delay_index** (2026-09-23 finding): `0x00` for an immediate ("start now") command — every
  sample captured before this date used `0x00` here, which is why it was originally assumed to
  be an unused/reserved byte. For a **scheduled/delayed start**, this is a **1-indexed position**
  into the official app's fixed preset delay list, in minutes:
  `[5, 10, 20, 30, 40, 50, 60, 90, 120, 150, 180]` — so `0x01`=5 min delay,
  `0x02`=10 min delay, ... `0x09`=120 min delay. Confirmed directly from the official app's own
  traffic: `02 03 00 09` (windscreen, 10 min duration, 120 min delay) and `02 03 02 01`
  (windscreen, 30 min duration, 5 min delay) — the duration byte keeps the exact same meaning
  in both immediate and scheduled commands, confirmed by holding the delay fixed at 5 min and
  varying duration across 20/30 min in a follow-up test (`02 03 01 01` / `02 03 02 01`).
  **Clearing a schedule uses the ordinary cancel payload below, not a distinct command.**

Full example (normal heat, 20 min, immediate): `f6 07 00 1b 02 02 01 00 [checksum]`.
Full example (windscreen, 30 min, scheduled 5 min from now):
`f6 07 00 1b 02 03 02 01 [checksum]`.

**Cancel** (stop whatever is running), payload is different/shorter:

```
01 00 00 00
```

Full example: `f6 06 00 1b 01 00 00 00 [checksum]`.

## Confirmed by physical testing (2026-09-21/22, this car)

| Mode | Duration | Payload | Result |
|---|---|---|---|
| Windscreen heat | 10 min (default) | `02 03 00 00` | Physically confirmed heating |
| Windscreen heat | 30 min (official-app capture) | `02 03 02 00` | Seen on wire, not independently re-tested |
| Windscreen heat | 20 min *(requested)* | `02 03 01 00` | **Retested manually, 2026-09-23 — starts cooling (A/C) instead of windscreen heat.** Not a silent default to 10-min heat as earlier guessed; the car does something actively wrong with this combination, not just "ignores the unsupported duration." **Do not use windscreen mode with 20 min.** |
| Cool (A/C) | 10 min | `02 01 00 00` | Physically confirmed cooling |
| Normal cabin heat | 20 min | `02 02 01 00` | Physically confirmed — ran the full 20 minutes (started 18:08:20, still running at 18:26:07, stopped ~18:28:20) |
| Cancel | — | `01 00 00 00` | Physically confirmed stops both heat and cool |

**Not yet tested**: normal heat 30 min, cool 20/30 min, windscreen 30 min re-verified against
this same wrong-mode-switch risk (only ever seen from an official-app capture, not
independently re-tested since the 20-min finding above). Given windscreen+20min actively
misbehaves rather than just falling back safely, **windscreen+30min should be treated as
unverified/risky until specifically retested**, not assumed safe by extrapolation.

Two back-to-back heat commands (tested to check for a distinguishable "already running" vs.
"newly started" response) returned the same acked-but-unvalidated response shape both times —
the protocol gives no wire-level signal to distinguish those cases; only physically checking
the car does.

## What was *not* pursued further

- **Status/read-back of register `0x1a`**: a `checkStatus` read path was built and later
  removed. It never produced a validated decode — responses came back with an unexplained
  `6e 0c 00 01`-prefixed byte stream that didn't match the expected ack shape. Given the
  proven reference implementation never validates acks either (success = "the car returned
  *something*"), this was deprioritized rather than solved.
- **Session keep-alive / ping loop**: the one-fresh-connection-per-command model sidesteps
  needing this entirely. The car has been observed to reset long-lived sessions after a
  couple of exchanges in earlier (pre-RC4) investigation; per-command connections never hit
  that failure mode.

## Source material

The full investigation narrative (including the abandoned `phevcore`/JNI path and the
brute-force key-search dead end) is not kept in this repo; this file and
[RC4Solution.md](RC4Solution.md) are the distilled, current result.
