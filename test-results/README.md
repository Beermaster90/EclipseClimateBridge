# Test results and captured protocol data

Evidence from the reverse-engineering investigation, kept alongside the code so it's
visible from the repo itself. Most of the original investigation trail (raw brute-force
scripts, intermediate/exploratory decode passes, a wrong-cipher theory later superseded by
the confirmed RC4 mechanism) has been pruned — see
[MitsubishiEclipseCrossReverseEngineerWitifProtocol.md](../docs/MitsubishiEclipseCrossReverseEngineerWitifProtocol.md)
for the actual, confirmed protocol this evidence fed into.

## `climate-write-evidence/`

`register-0x1b-climate-writes.output`: a decoded, checksum-validated real session (250+
seconds), showing register `0x1b` (`cmd=f6`, a write) used for the climate command, with a
`data` payload of `mode, duration, 00, 00` — e.g. `02 03 00 00` / `02 01 00 00` /
`01 00 00 00` across multiple write events. This is the concrete evidence that first
identified the climate-write register and payload shape, later confirmed physically via
`bridge-app` itself (see the protocol doc's results table). The original raw `.pcap` this
was decoded from is no longer available.

## `bridge-app-captures/`

Reusable `tcpdump` decode/summary tooling from `bridge-app` testing on the Nokia 7.2:
`decode_latest_capture.py` and `pcap_summary.py`. Kept for the next capture, not because any
specific past capture here caught useful traffic.

## A note on what's safe to have committed here

None of these files contain the car's Wi-Fi password or any phone/account credentials. They
do contain the car's Wi-Fi SSID indirectly in a couple of places and the protocol-level
6-byte "mac" client-identity value in decoded output — not secret in the way a password is,
but still specific to this car/setup.
