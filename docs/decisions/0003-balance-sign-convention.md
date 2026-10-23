# ADR 0003: Balance convention is credits minus debits
- Status: accepted · Date: 2026-10-24
## Context
Spec doesn't fix a sign convention; every account type must follow one rule so sums are checkable.
## Decision
balance = credits - debits for all accounts. Transfer A to B is DEBIT A, CREDIT B. Money enters via a transfer from a SETTLEMENT account, which may go negative.
## Alternatives rejected
Per-type conventions (assets vs liabilities): more accounting-correct but every check needs a switch on type.
