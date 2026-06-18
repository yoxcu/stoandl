# Datalog test app

Source for a tiny Pebble watchapp that exercises stoandl's **DataLogging capture** (`TESTING.md` §5.8).
It logs an incrementing 4-byte little-endian `uint32` to **tag 42** every 3 s, shows a live counter,
and **flushes to the phone on SELECT** (data logging otherwise spools and syncs lazily).

## Why this is source-only

The Pebble SDK project format (manifest + `wscript`) changes between SDK generations — a checked-in
`package.json`/`wscript` goes stale and the current Core SDK rejects it (*"This project is very
outdated, and cannot be handled by this SDK"*). So only the C source is committed; scaffold a fresh
project with **your installed SDK** and drop this file in:

```sh
pebble new-project datalog-build
cp datalog-test.c datalog-build/src/c/datalog-build.c    # overwrite the generated main
cd datalog-build
pebble build
pebble install --phone <host-ip>      # via stoandl's developer connection (stoandl developer start)
# or, from the repo:  stoandl apps install build/datalog-build.pbw
```

(`pebble new-project` assigns a random UUID; find the app's UUID later with `stoandl datalog list`.)

## Test

1. `datalog.enabled = true` in `stoandl.conf`, restart the daemon.
2. Open **datalog-build** on the watch — the counter ticks up every 3 s.
3. Press **SELECT** (or back out of the app) to flush.
4. On the host:
   ```sh
   stoandl datalog list                 # shows the app's UUID with tag 42
   stoandl datalog dump <uuid> 42       # the NDJSON: {"type":"UInt","value":N,...}
   ```
   Live daemon-side confirmation: `STOANDL_LOG=DEBUG` surfaces a `datalog … tag=42 …` line per frame.

## Note

The watch's spooler syncs lazily; with `health.sync = true` you'll also see unrelated DataLogging
traffic (health tags 81–85) in the log — that's consumed internally and never becomes a custom datalog
file. The SELECT flush / app-exit is the reliable way to make tag 42 appear promptly.
