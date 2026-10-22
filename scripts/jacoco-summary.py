#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
#
# Aggregate the JaCoCo CSVs a build already wrote: one row per module, then the total.
# JaCoCo writes one `jacoco.csv` per module under `target/site/jacoco/`. On its own a file answers
# only "how much of this module", which is not the question a reviewer asks, and a module that
# produced no execution data simply has no file — it looks like it passed. This script walks the
# reactor for those CSVs, sums the LINE / BRANCH / METHOD counters, prints one table, and names the
# modules that produced no data at all. It reads what is on disk and never builds anything.
#
# 汇总本次构建已经写出的 JaCoCo CSV：每个模块一行，末尾给合计。
# JaCoCo 在每个模块的 `target/site/jacoco/` 下写一份 `jacoco.csv`。单看一份只回答「这个模块覆盖多少」，
# 那不是评审者问的问题；而一个模块若没产生执行数据，就没有这份文件 —— 它看起来像「通过了」。本脚本遍历
# reactor 收集这些 CSV、累加 LINE / BRANCH / METHOD 三组计数、打一张表，并把**一条数据都没有**的模块
# 单独列出来。它只读磁盘上已有的文件，不触发任何构建。
#
# Usage / 用法:
#   python scripts/jacoco-summary.py            # per-module table / 每个模块一行
#   python scripts/jacoco-summary.py --packages # also drill down per package / 另按包下钻
#   python scripts/jacoco-summary.py --no-header
#
# Run it from the repository root after `mvn verify` / 在仓根、`mvn verify` 之后运行。

from __future__ import annotations

import argparse
import csv
import pathlib
import sys

COUNTERS = ("LINE", "BRANCH", "METHOD")
FIELDS = {
    "LINE": ("LINE_MISSED", "LINE_COVERED"),
    "BRANCH": ("BRANCH_MISSED", "BRANCH_COVERED"),
    "METHOD": ("METHOD_MISSED", "METHOD_COVERED"),
}


def new_bucket() -> dict:
    return {"classes": 0, **{c: [0, 0] for c in COUNTERS}}  # [missed, covered]


def add(bucket: dict, row: dict) -> None:
    bucket["classes"] += 1
    for counter, (missed, covered) in FIELDS.items():
        bucket[counter][0] += int(row[missed])
        bucket[counter][1] += int(row[covered])


def pct(pair: list) -> str:
    missed, covered = pair
    total = missed + covered
    return "n/a" if total == 0 else f"{covered * 100.0 / total:.2f}%"


def merge(into: dict, other: dict) -> None:
    into["classes"] += other["classes"]
    for counter in COUNTERS:
        into[counter][0] += other[counter][0]
        into[counter][1] += other[counter][1]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--packages", action="store_true", help="also print a per-package table")
    ap.add_argument("--root", default=".", help="reactor root (default: current directory)")
    args = ap.parse_args()

    root = pathlib.Path(args.root).resolve()
    files = sorted(root.rglob("target/site/jacoco/jacoco.csv"))
    if not files:
        print("no jacoco.csv found — run `mvn verify` first (it writes them at the verify phase)")
        return 1

    modules: dict[str, dict] = {}
    packages: dict[tuple[str, str], dict] = {}
    for path in files:
        rel = path.relative_to(root)
        module = rel.parts[0] if len(rel.parts) > 1 else "."
        bucket = modules.setdefault(module, new_bucket())
        with path.open(newline="", encoding="utf-8") as fh:
            for row in csv.DictReader(fh):
                add(bucket, row)
                if args.packages:
                    add(packages.setdefault((module, row["PACKAGE"]), new_bucket()), row)

    # A module that produced no execution data has no CSV at all, so it would silently vanish from
    # the table. Name those modules explicitly instead.
    # 没有执行数据的模块根本没有 CSV，会从表里悄悄消失；这里把它们显式点名。
    declared = sorted(p.parent.name for p in root.glob("*/pom.xml"))
    silent = [m for m in declared if m not in modules]

    total = new_bucket()
    for bucket in modules.values():
        merge(total, bucket)

    width = max([len(m) for m in modules] + [6])
    print(f"{'module':<{width}}  classes  {'line':>8}  {'branch':>8}  {'method':>8}")
    print("-" * (width + 42))
    for module in sorted(modules, key=lambda m: -sum(modules[m]["LINE"])):
        b = modules[module]
        print(
            f"{module:<{width}}  {b['classes']:>7}  {pct(b['LINE']):>8}  "
            f"{pct(b['BRANCH']):>8}  {pct(b['METHOD']):>8}"
        )
    print("-" * (width + 42))
    print(
        f"{'total':<{width}}  {total['classes']:>7}  {pct(total['LINE']):>8}  "
        f"{pct(total['BRANCH']):>8}  {pct(total['METHOD']):>8}"
    )
    for counter in COUNTERS:
        missed, covered = total[counter]
        print(f"  {counter.lower():<7} {covered}/{covered + missed}")

    if silent:
        print()
        print("modules with no execution data (not in the table above):")
        for module in silent:
            print(f"  - {module}")

    if args.packages:
        print()
        print(f"{'module / package':<60}  {'line':>8}  {'branch':>8}")
        for (module, package) in sorted(packages):
            b = packages[(module, package)]
            print(f"{module + ' / ' + package:<60}  {pct(b['LINE']):>8}  {pct(b['BRANCH']):>8}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
