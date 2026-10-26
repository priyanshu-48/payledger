#!/usr/bin/env python3
"""Runs every PromQL query of the committed dashboard against Prometheus (M8).

Fails when a query is rejected or returns no series, which is what a wrong metric name looks like in Grafana
(an empty panel). Standard library only.

    check_dashboard.py [DASHBOARD_JSON] [PROMETHEUS_URL]
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

dashboard = sys.argv[1] if len(sys.argv) > 1 else "ops/grafana/dashboards/payledger.json"
prometheus = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:9090"

failures = 0
for panel in json.load(open(dashboard, encoding="utf-8"))["panels"]:
    for target in panel["targets"]:
        url = prometheus + "/api/v1/query?" + urllib.parse.urlencode({"query": target["expr"]})
        try:
            body = json.load(urllib.request.urlopen(url, timeout=10))
            series = len(body["data"]["result"]) if body["status"] == "success" else -1
        except (urllib.error.URLError, KeyError, ValueError) as e:
            print(f"  ERROR  {panel['title']}: {e}")
            failures += 1
            continue
        ok = series > 0
        failures += 0 if ok else 1
        print(f"  {'ok   ' if ok else 'EMPTY'}  {series:2d} series  {panel['title']}  [{target['refId']}]")
print("PASS: every panel query answers" if failures == 0 else f"FAIL: {failures} queries failed or are empty")
sys.exit(1 if failures else 0)
