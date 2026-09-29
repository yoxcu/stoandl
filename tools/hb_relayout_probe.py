#!/usr/bin/env python3
"""Locate the battery-block offsets after a native_heartbeat_record layout change.

Why this exists
---------------
`HeartbeatStore` decodes the analytics heartbeat fully only when its `(size, version)` is a
released layout in `HeartbeatLayouts` — deliberately, so we never emit a guessed value. The
layouts come from PebbleOS source (`tools/hb_layouts_from_source.py`). When a firmware changes
the record and that source is not at hand, this tool finds where the battery block went (and
where the bytes were inserted) from the records already captured.

The store keeps the *raw* blob of every record (decoded or not), so the new layout can be
recovered offline — no watch, no serial console, no firmware source (the record's 20-byte
`build_id` is the GNU build-id of the binary, NOT a git SHA, so it cannot be looked up).

How it works
------------
The battery block has three mutually-reinforcing invariants at the known 523-byte offsets:

    soc        @102  u32  battery %, scaled x100      -> plausible range 0 .. 10000
    voltage    @114  u32  millivolts                  -> plausible range 3000 .. 4400
    charge_ms  @130  u32  ms spent charging     \\ these two partition the reporting
    discharge  @134  u32  ms spent discharging  /  interval, so they sum to ~1 hour

A packed struct that gained N bytes *before* the battery block shifts all three by exactly N.
So: scan a shift delta, and accept only the delta where ALL THREE invariants hold across
MANY records at once. Three simultaneous constraints over dozens of records make a
false positive very unlikely — and if nothing scores cleanly, the tool says so rather than
inventing an answer.

Usage
-----
    python3 tools/hb_relayout_probe.py                      # probe every watch in the store
    STOANDL_HB_DIR=/path/to/heartbeat python3 tools/hb_relayout_probe.py
    python3 tools/hb_relayout_probe.py --deltas -16:64      # widen the search window
"""
import base64, glob, json, os, struct, sys

u32 = lambda b, o: struct.unpack_from("<I", b, o)[0]

# Known-good offsets for the 523-byte layout (see HeartbeatStore.kt / hb_offset_check.py).
SOC_OFF, VOLT_OFF, CHG_OFF, DIS_OFF = 102, 114, 130, 134
BASE_SIZE = 523
HOUR_MS = 3_600_000

# Plausibility windows. Deliberately loose — the power comes from requiring all three at once.
SOC_RANGE = (0, 10_000)          # 0.00 % .. 100.00 %
VOLT_RANGE = (3_000, 4_400)      # a Li-ion cell that is alive
INTERVAL_RANGE = (0.5 * HOUR_MS, 1.4 * HOUR_MS)


def store_dir():
    d = os.environ.get("STOANDL_HB_DIR")
    if d:
        return d
    cfg = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return os.path.join(cfg, "stoandl", "battery", "heartbeat")


def fits(blob, delta):
    """Do all three battery invariants hold at (known offset + delta)?"""
    need = max(SOC_OFF, VOLT_OFF, CHG_OFF, DIS_OFF) + delta + 4
    if delta < -min(SOC_OFF, VOLT_OFF, CHG_OFF, DIS_OFF) or need > len(blob):
        return None
    soc = u32(blob, SOC_OFF + delta)
    volt = u32(blob, VOLT_OFF + delta)
    interval = u32(blob, CHG_OFF + delta) + u32(blob, DIS_OFF + delta)
    if not (SOC_RANGE[0] <= soc <= SOC_RANGE[1]):
        return None
    if not (VOLT_RANGE[0] <= volt <= VOLT_RANGE[1]):
        return None
    if not (INTERVAL_RANGE[0] <= interval <= INTERVAL_RANGE[1]):
        return None
    return soc, volt, interval


def probe(rows, size, deltas):
    """Score every candidate shift over all records of this size. Returns [(hits, delta, sample)]."""
    blobs = [base64.b64decode(r["raw"]) for r in rows if r.get("raw") and r.get("size") == size]
    if not blobs:
        return [], 0
    scores = []
    for d in deltas:
        hits = [fits(b, d) for b in blobs]
        good = [h for h in hits if h]
        if good:
            scores.append((len(good), d, good[-1]))
    scores.sort(key=lambda t: (-t[0], abs(t[1])))
    return scores, len(blobs)


u16 = lambda b, o: struct.unpack_from("<H", b, o)[0]


def const_map(blobs, width=2):
    """Offsets whose u16 value is identical across EVERY record -> structural landmarks.

    Two kinds are useful: the `scale` companion of each SCALED metric (a compile-time
    constant such as 100) and metrics that are always zero on this watch. Non-zero
    constants carry nearly all the discriminating power; zeros match zeros under any
    shift, so they are counted separately.
    """
    if not blobs:
        return {}
    n = min(len(b) for b in blobs)
    out = {}
    for o in range(0, n - width + 1):
        first = u16(blobs[0], o)
        if all(u16(b, o) == first for b in blobs):
            out[o] = first
    return out


def find_insertion(old_blobs, new_blobs, grow, header=29):
    """Locate where `grow` bytes were inserted, by aligning the landmark maps.

    For a candidate insertion point P, every old landmark at offset o should reappear in
    the new records at o (if o < P) or o+grow (if o >= P). The P that maximises agreement
    over NON-ZERO landmarks is the answer.
    """
    co, cn = const_map(old_blobs), const_map(new_blobs)
    nz = {o: v for o, v in co.items() if v != 0}
    if not nz:
        return None
    best = []
    old_len = min(len(b) for b in old_blobs)
    for P in range(header, old_len + 1):
        hit = sum(1 for o, v in nz.items() if cn.get(o if o < P else o + grow) == v)
        best.append((hit, P))
    best.sort(key=lambda t: (-t[0], t[1]))
    top_hits = best[0][0]
    # Every P in the winning plateau is equally consistent; report the plateau's bounds.
    plateau = sorted(P for h, P in best if h == top_hits)
    return {
        "hits": top_hits,
        "total_nonzero": len(nz),
        "p_min": plateau[0],
        "p_max": plateau[-1],
        "landmarks_old": len(co),
        "landmarks_new": len(cn),
    }


def main():
    deltas = range(-16, 65)
    for a in sys.argv[1:]:
        if a.startswith("--deltas"):
            lo, hi = (a.split("=", 1)[1] if "=" in a else sys.argv[sys.argv.index(a) + 1]).split(":")
            deltas = range(int(lo), int(hi) + 1)

    d = store_dir()
    files = sorted(glob.glob(os.path.join(d, "*.ndjson")))
    if not files:
        print(f"no heartbeat store found in {d}", file=sys.stderr)
        return 1
    print(f"store: {d}\n")

    for f in files:
        rows = []
        for line in open(f):
            line = line.strip()
            if line:
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
        serial = os.path.basename(f)[:-7]
        sizes = {}
        for r in rows:
            sizes[r.get("size")] = sizes.get(r.get("size"), 0) + 1
        print(f"=== {serial}: {len(rows)} records; sizes {dict(sorted(sizes.items(), key=lambda kv: str(kv[0])))} ===")

        # Baseline: prove the invariants really do hold on the old layout for THIS watch.
        if BASE_SIZE in sizes:
            base_scores, n = probe(rows, BASE_SIZE, [0])
            if base_scores:
                hits, _, (soc, volt, iv) = base_scores[0]
                print(f"  baseline {BASE_SIZE}B: invariants hold on {hits}/{n} records "
                      f"(last: soc={soc/100:.2f}% volt={volt/1000:.3f}V interval={iv/60000:.1f}min) -> offsets trusted")
            else:
                print(f"  baseline {BASE_SIZE}B: invariants did NOT hold — the assumptions in this tool are off")

        for size in sorted(s for s in sizes if isinstance(s, int) and s != BASE_SIZE):
            scores, n = probe(rows, size, deltas)
            print(f"  --- {size}B ({n} records) ---")
            if not scores:
                print("     no shift satisfies all three invariants; the block moved or its fields changed.")
                print("     Do NOT guess — capture a console `analytics native metrics_dump` for this build.")
                continue
            top = scores[0]
            hits, delta, (soc, volt, iv) = top
            frac = hits / n
            runners = [s for s in scores[1:4]]
            verdict = "CONFIDENT" if frac >= 0.9 and (not runners or runners[0][0] < hits) else "WEAK"
            print(f"     best shift: {delta:+d} bytes  ({hits}/{n} records = {frac*100:.0f}%)  [{verdict}]")
            print(f"       => soc@{SOC_OFF+delta} volt@{VOLT_OFF+delta} charge@{CHG_OFF+delta} discharge@{DIS_OFF+delta}")
            print(f"       last record: soc={soc/100:.2f}%  volt={volt/1000:.3f}V  interval={iv/60000:.1f}min")
            for h, dd, (s2, v2, i2) in runners:
                print(f"       runner-up: {dd:+d} ({h}/{n})  soc={s2/100:.2f}% volt={v2/1000:.3f}V")
            # Series sanity: a real soc series drifts smoothly and ends at the CURRENT level.
            blobs = [base64.b64decode(r["raw"]) for r in rows if r.get("size") == size and r.get("raw")]
            series = [fits(b, delta) for b in blobs]
            vals = [s[0] / 100 for s in series if s]
            if vals:
                tail = ", ".join(f"{v:.1f}" for v in vals[-12:])
                print(f"       soc series (last 12): {tail}")
                print(f"       ^ sanity-check the final value against `stoandl watch battery`.")

            # Where did the extra bytes go? The battery block not moving only proves the
            # insertion is after it — the HIGHER offsets stoandl decodes (hrm, cpu
            # residency, used by the power pie) could still have shifted.
            old = [base64.b64decode(r["raw"]) for r in rows if r.get("size") == BASE_SIZE and r.get("raw")]
            new = [base64.b64decode(r["raw"]) for r in rows if r.get("size") == size and r.get("raw")]
            grow = size - BASE_SIZE
            ins = find_insertion(old, new, grow) if old and new and grow > 0 else None
            if not ins:
                print("     insertion point: undetermined (no non-zero landmarks)")
                continue
            conf = ins["hits"] / ins["total_nonzero"]
            print(f"     insertion point: bytes added at offset {ins['p_min']}"
                  + (f"..{ins['p_max']}" if ins['p_max'] != ins['p_min'] else "")
                  + f"  ({ins['hits']}/{ins['total_nonzero']} non-zero landmarks agree, {conf*100:.0f}%)")
            if ins["p_min"] >= BASE_SIZE:
                print("       => APPENDED AT THE END: every existing offset stays valid.")
            for name, off in (("soc", SOC_OFF), ("soc_scale", 106), ("soc_pct_drop", 108),
                              ("voltage", VOLT_OFF), ("charge_ms", CHG_OFF), ("discharge_ms", DIS_OFF),
                              ("hrm", 174), ("cpu_running", 198), ("cpu_sleep", 204)):
                shifts = off >= ins["p_max"]
                amb = ins["p_min"] <= off < ins["p_max"]
                state = "AMBIGUOUS" if amb else (f"shifts +{grow} -> @{off+grow}" if shifts else "unchanged")
                print(f"       {name:<13}@{off:<4} {state}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
