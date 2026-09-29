#!/usr/bin/env python3
"""Derive every released native_heartbeat_record layout from a PebbleOS checkout.

Why this exists
---------------
`HeartbeatLayout.kt` mirrors PebbleOS `analytics.def`: one metric table (declaration order, each
metric tagged with the first release that emits it) plus one row per released `(size, version)`
layout. When a firmware release changes `analytics.def`, this script prints what to add, straight
from the source, instead of an offset hunt:

    python3 tools/hb_layouts_from_source.py ~/src/PebbleOS             # layouts + metric table
    python3 tools/hb_layouts_from_source.py ~/src/PebbleOS --kotlin    # the same, as Kotlin lines
    python3 tools/hb_layouts_from_source.py ~/src/PebbleOS --offsets v4.33.0   # one release's map

It reads only git objects (`git show <tag>:<path>`), so the checkout's working tree does not matter.
Releases are the `vX.Y.Z[.W]` tags that carry an `analytics.def`. The record is
`version:u8 | timestamp:u64 | build_id:u8[20]` (29 B) followed by the metrics in declaration order,
packed: UNSIGNED/SIGNED/TIMER = 4 B, SCALED_* = 4 B + u16 scale, STRING(len) = len + 1.

A `(size, version)` pair that two releases fill with different metric lists is reported as a
COLLISION: such a pair cannot be decoded safely and must not get a row.
"""
import re, subprocess, sys

HEADER = 1 + 8 + 20
KINDS = {"UNSIGNED": "U32", "SIGNED": "I32", "TIMER": "TIMER",
         "SCALED_UNSIGNED": "SCALED_U32", "SCALED_SIGNED": "SCALED_I32", "STRING": "STR"}


def git(repo, *args):
    return subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True, check=True).stdout


def semver(tag):
    return tuple(int(x) for x in tag.lstrip("v").split("."))


def size_of(kind, strlen):
    return 6 if kind.startswith("SCALED") else strlen + 1 if kind == "STR" else 4


def layout_at(repo, tag):
    """(version, [(name, kind, strlen)]) at a tag, or None when it has no analytics.def."""
    paths = git(repo, "ls-tree", "-r", "--name-only", tag).splitlines()
    defs = [p for p in paths if p.endswith("analytics/analytics.def")]
    natives = [p for p in paths if p.endswith("analytics/native.c")]
    if not defs or not natives:
        return None
    metrics = []
    for m in re.finditer(r"^\s*PBL_ANALYTICS_METRIC_DEFINE_(\w+)\(([^)]*)\)", git(repo, "show", f"{tag}:{defs[0]}"), re.M):
        args = [a.strip() for a in m.group(2).split(",")]
        kind = KINDS[m.group(1)]
        metrics.append((args[0], kind, int(args[1]) if kind == "STR" else 0))
    v = re.search(r"#define\s+NATIVE_HEARTBEAT_RECORD_VERSION\s+(\d+)", git(repo, "show", f"{tag}:{natives[0]}"))
    return (int(v.group(1)) if v else None), metrics


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    repo = sys.argv[1]
    tags = sorted((t for t in git(repo, "tag").split() if re.fullmatch(r"v\d+(\.\d+)+", t)), key=semver)
    releases = []  # (tag, version, metrics, size)
    for t in tags:
        got = layout_at(repo, t)
        if got and got[0] is not None:
            version, metrics = got
            releases.append((t, version, metrics, HEADER + sum(size_of(k, n) for _, k, n in metrics)))

    if "--offsets" in sys.argv:
        want = sys.argv[sys.argv.index("--offsets") + 1]
        for t, version, metrics, size in releases:
            if t == want:
                o = HEADER
                for name, kind, n in metrics:
                    print(f"{o:4d}  {name}  {kind}")
                    o += size_of(kind, n)
                print(f"{o:4d}  (end: {size} B / v{version})")
                return 0
        print(f"{want}: no such release with a heartbeat record", file=sys.stderr)
        return 1

    # One row per (size, version), keyed to the first release that emitted it.
    layouts, collisions = {}, []
    for t, version, metrics, size in releases:
        names = [m[0] for m in metrics]
        prev = layouts.get((size, version))
        if prev is None:
            layouts[(size, version)] = (t, names)
        elif prev[1] != names:
            collisions.append(f"{size} B / v{version}: {prev[0]} and {t} differ")

    # The superset in declaration order: each release's new metrics go right after their predecessor.
    order, kinds, since, until = [], {}, {}, {}
    for t, _, metrics, _ in releases:
        names = [m[0] for m in metrics]
        for i, (name, kind, n) in enumerate(metrics):
            if name in kinds and kinds[name] != (kind, n):
                collisions.append(f"{name} changes type at {t}: {kinds[name]} -> {(kind, n)}")
            kinds.setdefault(name, (kind, n))
            if name not in since:
                since[name] = t
                at = order.index(names[i - 1]) + 1 if i > 0 else 0
                order.insert(at, name)
        for name in order:
            if name not in names and name not in until and semver(t) > semver(since[name]):
                until[name] = t
    base = releases[0][0]

    if "--kotlin" in sys.argv:
        for name in order:
            kind, n = kinds[name]
            args = [f'"{name}"', kind] + ([f"strLen = {n}"] if kind == "STR" else [])
            if since[name] != base:
                args.append(f'since = "{since[name].lstrip("v")}"')
            if name in until:
                args.append(f'until = "{until[name].lstrip("v")}"')
            print(f"        M({', '.join(args)}),")
        print()
        for (size, version), (t, names) in layouts.items():
            print(f'        Layout(size = {size}, version = {version}, fw = "{t.lstrip("v")}"),  // {len(names)} metrics')
    else:
        print("size     ver  first release  metrics")
        for (size, version), (t, names) in layouts.items():
            print(f"{size:4d} B  v{version}   {t:<13}  {len(names)}")
        print(f"\n{len(order)} metrics in declaration order (since = first release, until = first without):")
        for i, name in enumerate(order, 1):
            kind, n = kinds[name]
            span = f"{since[name]}" + (f" .. {until[name]}" if name in until else "")
            print(f"{i:3d}  {name:<48} {kind + (f'[{n + 1}]' if kind == 'STR' else ''):<12} {span}")
    for c in collisions:
        print(f"COLLISION: {c}", file=sys.stderr)
    return 1 if collisions else 0


if __name__ == "__main__":
    sys.exit(main())
