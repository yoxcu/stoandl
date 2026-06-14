# Datalog test app

A tiny Pebble watchapp for exercising stoandl's **DataLogging capture** (`TESTING.md` §5.8).
It logs an incrementing 4-byte little-endian `uint32` to **tag 42** every 3 s, shows a live counter,
and **flushes to the phone on SELECT** (data logging otherwise spools and syncs lazily).

Fixed UUID `de7a1057-0000-4000-8000-000000000042`, so the captured data lands at a predictable path:

```
~/.config/stoandl/datalog/de7a1057-0000-4000-8000-000000000042/42.ndjson
```

## Build & install

Needs the Pebble SDK. Because the SDK's build system (`wscript`) isn't committed here, scaffold a
project and drop these two files in:

```sh
pebble new-project datalog-build
cp package.json datalog-build/package.json
cp src/c/datalog-test.c datalog-build/src/c/datalog-build.c   # overwrite the generated main
cd datalog-build
pebble build
pebble install --phone <host-ip>          # via stoandl's developer connection (stoandl developer start)
# or, from the repo:  stoandl sideload build/datalog-build.pbw
```

## Test

1. `datalog.enabled = true` in `stoandl.conf`, restart the daemon.
2. Open **Datalog Test** on the watch — the counter should tick up every 3 s.
3. Press **SELECT** (or back out of the app) to flush.
4. On the host:
   ```sh
   stoandl datalog list                 # shows the UUID with tag 42
   stoandl datalog dump de7a1057 42     # the NDJSON: {"type":"UInt","value":N,...}
   ```
   For live daemon-side confirmation: `STOANDL_LOG=DEBUG` surfaces a `datalog … tag=42 …` line per frame.

## Note

The watch's spooler syncs lazily; with `health.sync = true` you'll also see unrelated DataLogging
traffic (health tags 81–85) in the log — that's consumed internally and never becomes a custom datalog
file. The SELECT flush / app-exit is the reliable way to see tag 42 appear promptly.
