# Scheduled/delayed climate start — 2026-09-23

Two captures of the official app testing the "schedule a start time" feature (separate from the
climate command's own duration): a 5-min-delay run and a schedule-isolation run. The raw `.pcap`
files are not kept in the repo (gitignored); this report records the distilled evidence. Full
writeup now lives in
[MitsubishiEclipseCrossReverseEngineerWitifProtocol.md](../../../docs/MitsubishiEclipseCrossReverseEngineerWitifProtocol.md#climate-control-command-register-0x1b).

## Payloads captured (register 0x1b, `cmd=f6`)

| Test | Delay | Duration | Payload |
|---|---|---|---|
| 1 | 120 min | (later shown to be 10 min) | `02 03 00 09` |
| 2 | 5 min | (later shown to be 30 min, not 10 as first assumed) | `02 03 02 01` |
| 3 | 5 min | 20 min | `02 03 01 01` |
| 4 | 5 min | 30 min | `02 03 02 01` |

Test 4 exactly reproduces test 2's bytes, revealing the original "5 min delay, 10 min duration"
description was a mis-recollection — it was actually 30 min duration. Once corrected, the model
is fully consistent: duration byte means exactly what it always has (`00`/`01`/`02` =
10/20/30 min), and the delay byte is the official app's preset-list index
(`[5,10,20,30,40,50,60,90,120,150,180]` minutes, 1-indexed): index 1 = 5 min, index 9 = 120 min.

## Clearing a schedule

Both tests ended with `01 00 00 00` — the ordinary cancel payload, same as stopping an active
climate run. No separate "clear schedule" command was observed or appears necessary.
