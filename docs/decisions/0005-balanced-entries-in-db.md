# ADR 0005: Balanced entries enforced by a deferred constraint trigger
- Status: accepted · Date: 2026-10-24
## Context
Every journal entry must balance, checked in code AND DB.
## Decision
DEFERRABLE INITIALLY DEFERRED constraint trigger on postings: at commit, per entry, sum(DEBIT)=sum(CREDIT) and a single currency. Domain code checks too.
## Alternatives rejected
Trigger firing per row (would reject the first posting of every entry).
