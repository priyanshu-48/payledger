#!/usr/bin/env python3
"""Summarises the console output of Gatling runs, one run per request rate, and reports the maximum sustainable rate.

A rate is sustained when it had under 1% failed requests and a 95th percentile under 500 ms. The numbers come from the
"Global Information" block each run prints (request count, percentiles of the total column). For payments, the create
and the capture request are counted together. Standard library only.

    summarize.py LABEL RATE=LOGFILE [RATE=LOGFILE ...]
"""
import re
import sys

MAX_FAILURE_PERCENT = 1.0
MAX_P95_MS = 500


def number(text):
    text = text.strip().replace(",", "")
    return 0 if text in ("-", "") else int(float(text))


def read(path):
    text = open(path, encoding="utf-8", errors="replace").read()
    count = re.search(r"> request count\s*\|\s*(\S+)\s*\|\s*(\S+)\s*\|\s*(\S+)", text)
    if not count:
        raise SystemExit(f"no Global Information block in {path}")

    def pct(n):
        return number(re.search(rf"> response time {n}th percentile \(ms\)\s*\|\s*(\S+)", text).group(1))

    return number(count.group(1)), number(count.group(3)), pct(50), pct(95), pct(99)


label = sys.argv[1]
runs = sorted((int(a.split("=")[0]), a.split("=", 1)[1]) for a in sys.argv[2:])
print(f"== {label}")
print(f"{'rate/s':>7} {'requests':>9} {'failed %':>9} {'p50 ms':>7} {'p95 ms':>7} {'p99 ms':>7}  verdict")
best = None
for rate, path in runs:
    total, failed, p50, p95, p99 = read(path)
    failed_percent = 100.0 * failed / total if total else 100.0
    ok = failed_percent < MAX_FAILURE_PERCENT and p95 < MAX_P95_MS
    best = rate if ok else best
    print(f"{rate:>7} {total:>9} {failed_percent:>9.2f} {p50:>7} {p95:>7} {p99:>7}  {'sustained' if ok else 'over the limit'}")
print(f"max sustainable rate: {best if best is not None else 'none'}/s "
      f"(under {MAX_FAILURE_PERCENT:.0f}% failures and p95 under {MAX_P95_MS} ms)")
