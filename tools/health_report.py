#!/usr/bin/env python3
"""Quick look at stoandl's exported health data.

Reads the NDJSON that `health.export` writes under $XDG_CONFIG_HOME/stoandl/health
(falling back to ~/.config/stoandl/health) and prints a daily table, totals/averages,
and recent workouts. Stdlib only.

Usage:
    tools/health_report.py            # last 14 days + recent activities
    tools/health_report.py 30         # last 30 days
    tools/health_report.py --dir /path/to/health
"""
import json
import os
import sys
from datetime import datetime, timezone


def health_dir():
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return os.path.join(base, "stoandl", "health")


def read_ndjson(path):
    if not os.path.isfile(path):
        return []
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
    return rows


def fmt_dur(minutes):
    if minutes is None:
        return "-"
    return f"{minutes // 60}h{minutes % 60:02d}m" if minutes >= 60 else f"{minutes}m"


def fmt_dist(m):
    if m is None:
        return "-"
    return f"{m / 1000:.1f}km" if m >= 1000 else f"{m}m"


SPARK = "▁▂▃▄▅▆▇█"


def sparkline(values):
    nums = [v for v in values if v is not None]
    if not nums:
        return ""
    lo, hi = min(nums), max(nums)
    span = (hi - lo) or 1
    return "".join(
        " " if v is None else SPARK[min(len(SPARK) - 1, int((v - lo) / span * (len(SPARK) - 1)))]
        for v in values
    )


def daily_report(rows, days):
    rows = rows[-days:]
    if not rows:
        print("No daily health data exported yet.")
        return
    print(f"Daily — last {len(rows)} day(s)")
    print(f"{'DATE':<12}{'STEPS':>8}{'DIST':>9}{'SLEEP':>8}{'ACTIVE':>8}{'RHR':>5}{'AVGHR':>6}")
    for r in rows:
        steps = format(r.get("steps", 0), ",")
        print(
            f"{r.get('date','-'):<12}"
            f"{steps:>8}"
            f"{fmt_dist(r.get('distance_m')):>9}"
            f"{fmt_dur(r.get('sleep_total_min')):>8}"
            f"{fmt_dur(r.get('active_minutes')):>8}"
            f"{str(r.get('resting_hr','-')):>5}"
            f"{str(r.get('avg_hr','-')):>6}"
        )

    steps = [r.get("steps") for r in rows]
    sleep = [r.get("sleep_total_min") for r in rows]
    n_steps = [s for s in steps if s]
    n_sleep = [s for s in sleep if s]
    print()
    print(f"steps  {sparkline(steps)}   avg {sum(n_steps)//len(n_steps) if n_steps else 0:,}/day")
    print(f"sleep  {sparkline(sleep)}   avg {fmt_dur(sum(n_sleep)//len(n_sleep)) if n_sleep else '-'}/night")


def activities_report(rows, days):
    cutoff = datetime.now(timezone.utc).timestamp() - days * 86400
    rows = [r for r in rows if (r.get("start") or 0) >= cutoff]
    if not rows:
        return
    print(f"\nActivities — last {days} day(s)")
    print(f"{'WHEN':<17}{'TYPE':<11}{'DUR':>7}{'STEPS':>8}{'DIST':>9}{'KCAL':>6}")
    for r in rows:
        when = datetime.fromtimestamp(r.get("start", 0)).strftime("%Y-%m-%d %H:%M")
        steps = format(r.get("steps", 0), ",")
        print(
            f"{when:<17}{r.get('type','-'):<11}"
            f"{fmt_dur(r.get('duration_min')):>7}"
            f"{steps:>8}"
            f"{fmt_dist(r.get('distance_m')):>9}"
            f"{str(r.get('active_kcal','-')):>6}"
        )


def main():
    days = 14
    d = health_dir()
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        if args[i] == "--dir":
            d = args[i + 1]
            i += 2
        else:
            days = int(args[i])
            i += 1

    daily_report(read_ndjson(os.path.join(d, "daily.ndjson")), days)
    activities_report(read_ndjson(os.path.join(d, "activities.ndjson")), days)


if __name__ == "__main__":
    main()
