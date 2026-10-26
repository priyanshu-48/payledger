#!/usr/bin/env python3
"""Reconciliation demo tool (SPEC section 6.7, ADR 0017).

generate: reads the PAYMENT_CAPTURE entries of the running ledger and writes a bank settlement file with
          deliberate errors, plus the answer key of what the reconciliation must report.
verify:   compares a finished run's mismatches (GET /recon/runs/{id}/items) with that answer key.

Standard library only; talks to Postgres through `docker compose ... psql`. `verify` needs an ADMIN token in RECON_TOKEN.
"""
import argparse
import csv
import json
import os
import random
import subprocess
import sys
import urllib.request

HEADER = ["external_ref", "amount_minor", "currency", "settled_at"]
SETTLED_AT = "2026-10-26T10:00:00Z"


def ledger_captures(project):
    sql = ("select e.external_ref, p.amount_minor, p.currency from journal_entries e "
           "join postings p on p.entry_id = e.id and p.direction = 'CREDIT' "
           "where e.type = 'PAYMENT_CAPTURE' order by e.external_ref")
    out = subprocess.run(["docker", "compose", "-p", project, "exec", "-T", "postgres", "psql", "-U", "ledger",
                          "-d", "ledger", "-tAF,", "-c", sql], capture_output=True, text=True, check=True).stdout
    return [(r[0], int(r[1]), r[2]) for r in csv.reader(out.strip().splitlines())]


def generate(args):
    rows = ledger_captures(args.project)
    needed = args.drop + args.alter + args.duplicate
    if len(rows) < needed + 1:
        sys.exit(f"only {len(rows)} captures in the ledger, need more than {needed}")
    rng = random.Random(args.seed)
    rng.shuffle(rows)
    dropped, altered, duplicated, rest = (rows[:args.drop], rows[args.drop:args.drop + args.alter],
                                          rows[args.drop + args.alter:needed], rows[needed:])
    expected = {}
    bank = []
    for ref, amount, currency in dropped:
        expected[ref] = "MISSING_IN_BANK"
    for ref, amount, currency in altered:
        bank.append((ref, amount + 1, currency))
        expected[ref] = "AMOUNT_MISMATCH"
    for ref, amount, currency in duplicated:
        bank.extend([(ref, amount, currency)] * 2)
        expected[ref] = "DUPLICATE_IN_BANK"
    for ref, amount, currency in rest:
        bank.append((ref, amount, currency))
    for i in range(1, args.extra + 1):
        ghost = f"payment-capture:ghost-{args.seed}-{i}"
        bank.append((ghost, 12345, "INR"))
        expected[ghost] = "MISSING_IN_LEDGER"
    rng.shuffle(bank)

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "settlement.csv"), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(HEADER)
        for ref, amount, currency in bank:
            w.writerow([ref, amount, currency, SETTLED_AT])
    with open(os.path.join(args.out, "planted-errors.csv"), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["external_ref", "expected_outcome"])
        for ref in sorted(expected):
            w.writerow([ref, expected[ref]])
    print(f"ledger captures: {len(rows)}; planted: {args.drop} missing in bank, {args.alter} amount mismatches, "
          f"{args.duplicate} duplicates, {args.extra} missing in ledger; matched expected: {len(rest)}")


def verify(args):
    expected = {}
    with open(args.expected) as f:
        for row in csv.DictReader(f):
            expected[row["external_ref"]] = row["expected_outcome"]
    url = f"{args.base}/recon/runs/{args.run}/items?size=1000"
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + os.environ["RECON_TOKEN"]})
    with urllib.request.urlopen(request) as response:  # default listing excludes MATCHED
        found = {i["externalRef"]: i["outcome"] for i in json.load(response)}
    missed = {r: o for r, o in expected.items() if found.get(r) != o}
    false_positives = {r: o for r, o in found.items() if r not in expected}
    print(f"planted {len(expected)}, found correctly {len(expected) - len(missed)}, "
          f"missed or wrong {len(missed)}, false positives {len(false_positives)}")
    for r, o in list(missed.items())[:5]:
        print(f"  MISSED {r}: expected {o}, got {found.get(r)}")
    for r, o in list(false_positives.items())[:5]:
        print(f"  FALSE POSITIVE {r}: {o}")
    sys.exit(0 if not missed and not false_positives else 1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    g = sub.add_parser("generate")
    g.add_argument("--project", default="payledger")
    g.add_argument("--out", required=True)
    g.add_argument("--seed", type=int, default=7)
    g.add_argument("--drop", type=int, default=3)
    g.add_argument("--alter", type=int, default=3)
    g.add_argument("--duplicate", type=int, default=2)
    g.add_argument("--extra", type=int, default=3)
    g.set_defaults(func=generate)
    v = sub.add_parser("verify")
    v.add_argument("--base", default="http://localhost:8082")
    v.add_argument("--run", required=True)
    v.add_argument("--expected", required=True)
    v.set_defaults(func=verify)
    parsed = parser.parse_args()
    parsed.func(parsed)
