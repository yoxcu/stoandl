#!/usr/bin/env python3
"""Offline HRM / power-draw offset check for the analytics native_heartbeat_record.

No watch, daemon, or serial cable needed: it re-reads the *raw* 523-byte blobs stoandl already
captured under <config>/battery/heartbeat/<serial>.ndjson and prints the power-relevant fields —
crucially `hrm@174` (the heart-rate on-time offset under test) in ms and as a % of the hour.

Why: the reworked "what drew power" pie weights on-time by an estimated current, which would *mask* a
wrong `hrm` offset. This lets you confirm @174 really is the HRM on-time without touching the firmware
console (see TESTING.md §5.29L):
  * On a watch with NO heart-rate sensor (e.g. Pebble Time Steel / basalt) hrm@174 MUST be ~0.
    A large value there means offset @174 is wrong (it's reading a different metric).
  * On an HRM watch (Pebble Time 2 / emery), toggle continuous HR: hrm@174 should be ~0 with HR off
    and large with HR on. If it doesn't track the setting, the offset is wrong.

Usage:
  python3 tools/hb_offset_check.py            # last few records per watch
  python3 tools/hb_offset_check.py --scan     # + a u32 neighbour dump to hunt the real offset
  STOANDL_HB_DIR=/path/to/heartbeat python3 tools/hb_offset_check.py   # override the store dir
"""
import base64, glob, json, os, struct, sys

u32 = lambda b, o: struct.unpack_from("<I", b, o)[0]
u16 = lambda b, o: struct.unpack_from("<H", b, o)[0]

# Offsets verified against coredevices/PebbleOS@main (native.c + analytics.def); see
# docs/battery-insights.md and the stoandl-heartbeat-record-layout note.
HRM_OFF = 174
SIZE = 523


def store_dir():
    if os.environ.get("STOANDL_HB_DIR"):
        return os.path.expanduser(os.environ["STOANDL_HB_DIR"])
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return os.path.join(base, "stoandl", "battery", "heartbeat")


def main():
    scan = "--scan" in sys.argv
    d = store_dir()
    files = sorted(glob.glob(os.path.join(d, "*.ndjson")))
    if not files:
        print(f"No heartbeat store found in {d!r}.")
        print("Run the daemon connected to the watch for >= ~1 h (battery.heartbeat = true) first,")
        print("or set STOANDL_HB_DIR to the directory holding <serial>.ndjson.")
        return 1

    for f in files:
        rows = [json.loads(l) for l in open(f) if l.strip()]
        dec = [r for r in rows if r.get("decoded") and r.get("raw")]
        print(f"\n=== {os.path.basename(f)[:-7]}: {len(dec)} decoded of {len(rows)} records ===")
        if not dec:
            print("  (no decoded records — UNDECODED layout? check `stoandl watch battery heartbeat`)")
            continue
        for r in dec[-8:]:
            b = base64.b64decode(r["raw"])
            if len(b) != SIZE:
                print(f"  ts={r.get('watch_ts')} size={len(b)} (not {SIZE} — skip)")
                continue
            interval = u32(b, 130) + u32(b, 134)          # charge_ms + discharge_ms
            drop = u32(b, 108) / (u16(b, 112) or 1)       # firmware's own soc_pct_drop
            hrm = u32(b, HRM_OFF)
            pct = 100.0 * hrm / interval if interval else 0.0
            print(f"  ts={r.get('watch_ts')} soc={r.get('soc')}% drop={drop:.2f}% "
                  f"interval={interval}ms | hrm@{HRM_OFF}={hrm}ms ({pct:.0f}% of hour) "
                  f"backlight@138={u32(b,138)}ms cpu_run@198={u32(b,198)/(u16(b,202) or 1):.1f}%")
        if scan:
            b = base64.b64decode(dec[-1]["raw"])
            if len(b) == SIZE:
                print("  u32-LE neighbour scan of the last record (offset=value), hunting a plausible ms on-time:")
                for o in range(130, 331, 4):
                    v = u32(b, o)
                    mark = "  <-- @174 (hrm under test)" if o == HRM_OFF else ""
                    print(f"    @{o:<3} = {v}{mark}")

    print("\nInterpretation:")
    print("  * No-HRM watch (Time Steel/basalt): hrm@174 ~0 = offset OK; large = offset WRONG.")
    print("  * HRM watch (Time 2/emery): hrm@174 should track continuous-HR on/off. If it does,")
    print("    a large Heart-rate pie slice is real draw; if not, the offset is wrong.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
