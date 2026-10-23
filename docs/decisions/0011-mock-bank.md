# ADR 0011: Mock bank design
- Status: accepted · Date: 2026-10-24
## Context
SPEC 6.6: a bank stub with 50-2000 ms latency, 5% declines, 3% timeouts and 1% "approved but the response was lost", configured by environment variables.
## Decision
- A controller inside payment-service under `/mock-bank`, called over real HTTP by `BankClient` (`RestClient`, read timeout default 3 s) so timeouts are genuine network timeouts.
- Faults: decline (clean 200 DECLINED); timeout (bank never processes the request and sleeps past the client timeout); lost response (bank records the approval, then responds too late). All rates, latency bounds and the sleep come from `MOCK_BANK_*` env vars; the RNG is seedable.
- The bank is idempotent by `reference` (= payment id) and keeps an in-memory store. `GET /mock-bank/authorizations/{reference}` is the status check (404 = unknown to the bank). Store is lost on restart; this is a stub.
- Unauthenticated and enabled by property; restricted in M7.
## Consequences
Faulty requests hold a Tomcat thread for the sleep; fine at test concurrency. A real bank would also expose a void call; the status job treats "unknown to the bank after the grace period" as failed.
## Alternatives rejected
Separate module/service (more moving parts, same behaviour); in-process fake (no real timeouts).
