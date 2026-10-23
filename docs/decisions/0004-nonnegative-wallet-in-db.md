# ADR 0004: Non-negative wallets enforced by a flag column
- Status: accepted · Date: 2026-10-24
## Context
Spec: CHECK balance>=0 for USER_WALLET only. A CHECK cannot read accounts.type.
## Decision
account_balances.floor_zero BOOLEAN (true for USER_WALLET at creation) with CHECK (NOT floor_zero OR balance_minor >= 0).
## Alternatives rejected
Trigger joining accounts (slower, more code); app-only check (no DB backstop).
