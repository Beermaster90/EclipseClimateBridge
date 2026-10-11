# Mode/duration verification — 2026-09-23

tcpdump capture of a manual test run: windscreen 10min → cancel → windscreen 20min → cancel →
windscreen 30min → cancel → normal heat 20min → cancel. Decoded with
`bridge-app-captures/decode_latest_capture.py` against `verify_capture.pcap` in this folder.

## 1. The app sent byte-for-byte correct payloads every time — not an app bug

| Time | Command | Payload sent (reg 0x1b) | Matches `Rc4CarClient` expectation |
|---|---|---|---|
| t=19s | Windscreen, 10 min | `02 03 00 00` | ✓ |
| t=39s | Cancel | `01 00 00 00` | ✓ |
| t=56s | Windscreen, 20 min | `02 03 01 00` | ✓ |
| t=74s | Cancel | `01 00 00 00` | ✓ |
| t=93s | Windscreen, 30 min | `02 03 02 00` | ✓ |
| t=107s | Cancel | `01 00 00 00` | ✓ |
| t=127s | Heat, 20 min | `02 02 01 00` | ✓ |
| t=143s | Cancel | `01 00 00 00` | ✓ |

Every command matches `mode.byte`/`duration.code` exactly as `Rc4CarClient.climateCommand()`
should produce them. **Whatever's wrong with windscreen+20min (the "starts cooling instead"
finding from the previous session) is not an encoding bug in this app** — the wire bytes are
exactly what was intended.

## 2. The car's own status register (0x10) tells a more specific story

Register `0x10` pushes an unsolicited 3-byte status update (`cmd=6f`) when its state changes.
Across the whole run, only two pushes carried a non-zero payload:

| Time | Push | Follows |
|---|---|---|
| t=2s | `00 00 00` | (baseline, before test) |
| t=19s (windscreen 10min sent) | **no push** | — |
| t=39s (cancel) | `00 00 00` | — |
| t=56s (windscreen 20min sent) | **no push** | — |
| t=74s (cancel) | **no push** | — |
| t=93s (windscreen 30min sent) | **`02 70 10`** | real activation |
| t=108s (cancel) | `00 00 00` | — |
| t=127s (heat 20min sent) | **`02 70 10`** | real activation |
| t=143s (cancel) | `00 00 00` | — |

Only **windscreen 30min** and **heat 20min** produced the `02 70 10` activation code from the
car. Windscreen 10min and windscreen 20min produced no register-0x10 reaction at all in this
run.

**Important caveat**: each mode was only left running ~17–20 seconds before being cancelled in
this test (not the full programmed duration), so absence of a push isn't proof the car did
nothing — it could be a delayed reaction, or register 0x10 might simply not be the status
register windscreen mode reports through (earlier sessions physically confirmed windscreen
10min actually heats the glass, by feel, which this capture doesn't contradict, just doesn't
independently corroborate at the wire level).

## What this does and doesn't settle

- **Settled**: the app is not the source of the windscreen+20min misbehavior. Every payload
  sent this run was byte-correct for all four mode/duration combinations tested.
- **Not settled**: exactly what's different about windscreen+20min at the car's firmware
  level, or whether windscreen+10min's lack of a register-0x10 push means anything (probably
  not, given it's separately physically confirmed working). Would need a repeat run with full
  wait times per mode (actually waiting out 10/20/30 minutes while capturing) to get a
  definitive wire-level activation signal for every combination, matching physical
  observation.
