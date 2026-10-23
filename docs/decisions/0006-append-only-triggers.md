# ADR 0006: Append-only ledger via triggers
- Status: accepted · Date: 2026-10-24
## Context
Entries and postings must never be updated or deleted; corrections are reversals.
## Decision
BEFORE UPDATE OR DELETE triggers on journal_entries, postings and audit_log raise an exception. JPA entities are @Immutable.
## Alternatives rejected
Revoking privileges only (a superuser or migration could still bypass it silently).
