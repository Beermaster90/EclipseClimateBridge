# Official-app captures — 2026-09-23

Captures of the **official Mitsubishi app** (`com.mitsubishi_motors.remote_ecp`), not this
project's `bridge-app` — confirmed by the user; `bridge-app`'s own log shows no activity during
either window. Captured with tcpdump on `wlan0` (interface-level, so it sees any app's traffic
regardless of which one generated it). Two capture windows are described below: a main run and
a separate 30-min retest. The raw `.pcap` files are not kept in the repo (gitignored); this
report is the distilled result.

## 1. Official app confirms our protocol encoding is correct

In `dash-only`, the official app itself wrote register `0x1b` with:
- `02 03 01 00` — windscreen, 20 min
- `02 03 02 00` — windscreen, 30 min

Byte-for-byte identical to what `Rc4CarClient.climateCommand()` produces for the same
selections. This is the strongest confirmation available that our reverse-engineered payload
format is correct — it's not just consistent with our own assumptions, the authoritative
client sends the exact same bytes.

## 2. The "communication error" happened to the official app too

Right after the official app sent windscreen 30min (`t=146s` in `dash-only`), its own TCP
connection degraded into a run of oversized, undecodable packets and ended in a RST (`flags=4`
at the end of the capture). Since this happened to the **official app**, not `bridge-app`,
**it's very unlikely to be a bug in this project's code** — more likely a car-side or Wi-Fi
condition that affects any client (signal, a rapid mode-switch the car's firmware doesn't
handle cleanly, or similar). Not fully root-caused; noted for awareness, not treated as
something to "fix" in this app.

## 3. No single register gives a clean, reliable "climate is running mode X" readout

Surveyed every register with non-trivial (non-zero) data across the full `dash-only` capture.
Identified, none of them climate-status:
- `reg=0x28`: pushes the car's own SSID as ASCII (`REMOTE75trpe`) — a device-identity string,
  not climate-related.
- `reg=0x05` / `reg=0x12`: carry what looks like a date/time encoding (`1a 09 17 10...` =
  `0x1a`=26→2026, `0x09`=September, `0x17`=23, `0x10`=16 — matches the actual capture date/time,
  2026-09-23 ~16:xx). Clock sync, not climate.
- `reg=0x16`: an 8-byte value that changes on nearly every push — likely a continuously-updating
  telemetry field (battery/signal/odometer-like), not obviously climate-specific.
- `reg=0x10`: the only register that reacted near climate commands in some runs (`02 70 XX`,
  `XX` varying) — but **inconsistent between test runs**: it fired for windscreen 20min and
  30min in `dash-only`, but fired for neither in the earlier same-day test (see
  `2026-09-23-mode-duration-verify/REPORT.md`). The varying third byte looks more like a
  sequence/event counter than a mode identifier, so even when it does fire, it doesn't clearly
  say *which* mode or duration is active — only that *something* climate-related happened.

**Conclusion, matching everything found earlier in this project**: there's no reliable
wire-level "is the car actually heating/cooling/defrosting right now" readout. Physically
checking the car remains the only trustworthy confirmation.

## 4. The 30min-retest capture didn't actually reach a climate command

`verify_30min.pcap` (36 seconds) only shows the official app's startup/sync traffic — clock
sync to `reg=0x05`, and small single-byte config writes to `reg=0x03`/`0x07`/`0x17`. No
register-`0x1b` write appears in it at all, so this particular capture window ended before
(or without) the windscreen 30min command actually being sent.
