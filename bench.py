#!/usr/bin/env python3
"""
bench.py -- run a harness scene, keep its result file, and compare it with the stored baseline.

    python bench.py run vfx-blasts --cell gl-g43                 # one run, then the comparison
    python bench.py run vfx-blasts --cell gl-g43 --repeat 3      # the field-wise median of three runs
    python bench.py compare harness-output/vfx-blasts/profile-harness-300f/result.json
    python bench.py accept  <result.json> --reason "why the baseline moves"

A result file is written by the harness next to its report (CgTraceResult, format version 1): every time is
integer nanoseconds, every size integer bytes. Baselines live in baselines/<scene>/<cell>.json. By hand, on the
reference machine: GPU timing on a shared machine is noise.

The noise model. A single run's p99 over a few hundred frames is itself noisy, so a comparison has a band: a time
measure is worse only when it is more than TOLERANCE over the baseline AND more than FLOOR_NS over it, both strictly.
Only wall median and wall p99 are judged; p99.9 and max are one frame's luck in a run this size, so they are
printed and never flagged. A steadier baseline is the median of several runs (--repeat).

Also flagged, each by name: frame-thread allocation (allocatedBytes.median over the baseline's by more than
ALLOC_SLACK), hitches of ours above the baseline's, a compile-wait, sync-load or pipeline-creation counter that rose,
and a measure the baseline had and this run lost (never a silent pass). Improvements are never flagged.

Exit codes: 0 within noise, 1 regression, 2 unusable input, 3 no baseline for that scene and cell.
"""
import argparse
import datetime
import json
import os
import shlex
import subprocess
import sys
from pathlib import Path

VERSION = 1
TOLERANCE = 0.10
FLOOR_NS = 250_000
ALLOC_SLACK = 1024
FLAGGED_COUNTER_SUFFIXES = (".compile-waits", ".sync-loads", ".pipeline-creations")

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
MEASURES = ("wall", "cpu", "gpu", "allocatedBytes", "directBytes")
JUDGED_TIMES = ("median", "p99")
JVM_KEYS = ("compilations", "deoptimisations", "safepoints", "classLoads")


class BenchError(Exception):
    """An input bench.py cannot use; the message names what and why."""


class Finding:
    def __init__(self, measure, message):
        self.measure = measure
        self.message = message

    def __repr__(self):
        return f"Finding({self.measure!r}, {self.message!r})"


def load(path):
    path = Path(path)
    try:
        result = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise BenchError(f"cannot read {path}: {e}")
    if not isinstance(result, dict) or "version" not in result:
        raise BenchError(f"{path}: no version field; not a result file")
    if result["version"] != VERSION:
        raise BenchError(f"{path}: result version {result['version']}, this bench.py reads version {VERSION}")
    return result


def _ms(ns):
    return f"{ns / 1e6:.1f} ms"


def _percent(base, cur):
    return "+inf%" if base == 0 else f"{(cur - base) / base * 100:+.1f}%"


def _same_run(base, cur):
    for key in ("version", "scene", "cell"):
        if base.get(key) != cur.get(key):
            raise BenchError(f"cannot compare: {key} differs ({base.get(key)!r} baseline, {cur.get(key)!r} run)")


def compare(base, cur):
    """The regressions of `cur` against `base`, as Findings; raises BenchError when they are not the same run."""
    _same_run(base, cur)
    found = []
    for name in MEASURES:
        was, now = base["measures"].get(name), cur["measures"].get(name)
        if was is not None and now is None:
            found.append(Finding(name, "unavailable in this run (the baseline had it)"))
    for key in JVM_KEYS:
        if base["jvm"].get(key) is not None and cur["jvm"].get(key) is None:
            found.append(Finding(f"jvm.{key}", "unavailable in this run (the baseline had it)"))

    was, now = base["measures"]["wall"], cur["measures"]["wall"]
    for key in JUDGED_TIMES:
        b, c = was[key], now[key]
        if c * 10 > b * 11 and c - b > FLOOR_NS:
            found.append(Finding(f"wall.{key}", f"{_ms(b)} -> {_ms(c)} ({_percent(b, c)})"))

    was, now = base["measures"].get("allocatedBytes"), cur["measures"].get("allocatedBytes")
    if was is not None and now is not None and now["median"] > was["median"] + ALLOC_SLACK:
        found.append(Finding("allocatedBytes.median",
                             f"{was['median']} B -> {now['median']} B per frame on the frame thread"))

    if cur["hitches"]["ours"] > base["hitches"]["ours"]:
        found.append(Finding("hitches.ours", f"{base['hitches']['ours']} -> {cur['hitches']['ours']}"))

    for name, now in cur["counters"].items():
        if not name.endswith(FLAGGED_COUNTER_SUFFIXES):
            continue
        before = base["counters"].get(name, {}).get("sum", 0)
        if now["sum"] > before:
            found.append(Finding(f"counters.{name}", f"{before} -> {now['sum']} while playing"))
    return found


def _median(values):
    return sorted(values)[len(values) // 2]


def merge(results):
    """The field-wise median of several runs of one scene and cell; a measure any run lacks stays absent."""
    if not results:
        raise BenchError("nothing to merge")
    first = results[0]
    for other in results[1:]:
        _same_run(first, other)
        if other.get("targetNanos") != first.get("targetNanos"):
            raise BenchError("cannot merge: targetNanos differs")

    def fieldwise(items):
        return {k: _median([i[k] for i in items]) for k in items[0]}

    out = {k: first[k] for k in ("version", "scene", "cell")}
    out["commit"] = results[-1]["commit"]
    out["targetNanos"] = first["targetNanos"]
    out["frames"] = _median([r["frames"] for r in results])
    out["measures"] = {}
    for name in MEASURES:
        parts = [r["measures"].get(name) for r in results]
        out["measures"][name] = None if any(p is None for p in parts) else fieldwise(parts)
    out["hitches"] = fieldwise([r["hitches"] for r in results])
    out["gcCollections"] = _median([r["gcCollections"] for r in results])
    out["gcPauseMillis"] = _median([r["gcPauseMillis"] for r in results])
    out["jvm"] = {}
    for key in JVM_KEYS:
        parts = [r["jvm"].get(key) for r in results]
        out["jvm"][key] = None if any(p is None for p in parts) else _median(parts)
    names = sorted({n for r in results for n in r["counters"]})
    zero = {"sum": 0, "max": 0, "frames": 0}
    out["counters"] = {n: fieldwise([r["counters"].get(n, zero) for r in results]) for n in names}
    return out


def _write(path, result):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def baseline_path(baselines, result):
    return Path(baselines) / result["scene"] / f"{result['cell']}.json"


def accept(result_path, baselines, reason):
    """Stores a result as its scene and cell's baseline, with why; the old one stays as <cell>.prev.json."""
    if not reason or not reason.strip():
        raise BenchError("accept needs a reason (--reason): a baseline moves only with the why recorded")
    result = load(result_path)
    result["baseline"] = {
        "reason": reason.strip(),
        "commit": result["commit"],
        "accepted": datetime.date.today().isoformat(),
    }
    target = baseline_path(baselines, result)
    if target.exists():
        os.replace(target, target.with_name(f"{result['cell']}.prev.json"))
    _write(target, result)
    return target


def _report(base, cur):
    findings = compare(base, cur)
    for key in ("p99.9", "max"):
        print(f"  note wall.{key}: {_ms(base['measures']['wall'][key])} -> {_ms(cur['measures']['wall'][key])} (not judged)")
    for f in findings:
        print(f"REGRESSION {f.measure}: {f.message}")
    if findings:
        print(f"FAILED: {len(findings)} regression{'s' if len(findings) != 1 else ''}")
        return 1
    print("OK")
    return 0


def _compare_command(result, baselines):
    cur = load(result)
    path = baseline_path(baselines, cur)
    if not path.is_file():
        print(f"no baseline for scene {cur['scene']!r} cell {cur['cell']!r} ({path}); "
              f"store this run with: bench.py accept {result} --reason \"...\"")
        return 3
    return _report(load(path), cur)


def _commit():
    try:
        return subprocess.run(["git", "-C", str(ROOT / "CrystalGraphics"), "rev-parse", "--short", "HEAD"],
                              capture_output=True, text=True, check=True).stdout.strip() or "unknown"
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def _run_command(args):
    commit = args.commit or _commit()
    gradle = "gradlew.bat" if os.name == "nt" else "./gradlew"
    cmd = [gradle, ":gl-debug-harness:runHarness", f"--args=--mode={args.scene}",
           f"-Dcrystalgraphics.harness.profile={args.frames}",
           f"-Dcrystalgraphics.harness.cell={args.cell}",
           f"-Dcrystalgraphics.harness.commit={commit}"]
    if args.dry_run:
        print(" ".join(shlex.quote(c) for c in cmd))
        return 0
    produced = HERE / "harness-output" / args.scene / f"profile-harness-{args.frames}f" / "result.json"
    runs = []
    for i in range(args.repeat):
        if produced.exists():
            produced.unlink()
        print(f"run {i + 1}/{args.repeat}: {' '.join(shlex.quote(c) for c in cmd)}")
        if subprocess.run(cmd, cwd=ROOT, timeout=1800).returncode != 0 or not produced.is_file():
            raise BenchError(f"the harness run of {args.scene} left no {produced}")
        runs.append(load(produced))
    result = merge(runs) if len(runs) > 1 else runs[0]
    out = HERE / "harness-output" / args.scene / f"bench-{args.cell}.json"
    _write(out, result)
    print(f"result: {out}")
    return _compare_command(out, args.baselines)


def main(argv):
    parser = argparse.ArgumentParser(prog="bench.py", description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    default_baselines = str(HERE / "baselines")

    run = sub.add_parser("run", help="run a scene, write its result, compare with the baseline")
    run.add_argument("scene")
    run.add_argument("--cell", default="default")
    run.add_argument("--commit")
    run.add_argument("--frames", type=int, default=300)
    run.add_argument("--repeat", type=int, default=1)
    run.add_argument("--baselines", default=default_baselines)
    run.add_argument("--dry-run", action="store_true", help="print the command and stop")

    cmp_ = sub.add_parser("compare", help="compare a result file with its stored baseline")
    cmp_.add_argument("result")
    cmp_.add_argument("--baselines", default=default_baselines)

    acc = sub.add_parser("accept", help="store a result as the baseline, recording why")
    acc.add_argument("result")
    acc.add_argument("--baselines", default=default_baselines)
    acc.add_argument("--reason")

    args = parser.parse_args(argv)
    try:
        if args.command == "run":
            return _run_command(args)
        if args.command == "compare":
            return _compare_command(args.result, args.baselines)
        print(f"baseline: {accept(args.result, args.baselines, args.reason)}")
        return 0
    except BenchError as e:
        print(f"error: {e}")
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
