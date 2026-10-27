# Decision records

One short record per design choice that the spec did not make for me: context, decision, consequences, what was rejected. Format in [0000-template.md](0000-template.md).

| # | Decision |
|---|---|
| [0001](0001-java-21-release-on-newer-jdk.md) | Target Java 21 through `maven.compiler.release` on a newer local JDK |
| [0002](0002-infra-layout.md) | Local infrastructure layout (Compose, one Postgres with a database per service) |
| [0003](0003-balance-sign-convention.md) | Balance is credits minus debits |
| [0004](0004-nonnegative-wallet-in-db.md) | Non-negative wallets enforced by the database, not only the code |
| [0005](0005-balanced-entries-in-db.md) | Balanced journal entries enforced by a deferred constraint trigger |
| [0006](0006-append-only-triggers.md) | Append-only journal and postings via triggers; corrections are reversals |
| [0007](0007-lock-acquisition.md) | Row locks taken one at a time in sorted account-id order |
| [0008](0008-transfer-idempotency.md) | The Idempotency-Key becomes the entry's unique external reference |
| [0009](0009-pessimistic-vs-optimistic.md) | Pessimistic locking by default; optimistic variant measured against it |
| [0010](0010-payment-state-machine.md) | Payment state machine and the "authorization unknown" state |
| [0011](0011-mock-bank.md) | The stand-in bank and its failure modes |
| [0012](0012-payment-idempotency.md) | Idempotent payment creation (insert first, the key decides) |
| [0013](0013-transactional-outbox.md) | Transactional outbox with a lease-based poller |
| [0014](0014-idempotent-ledger-consumer.md) | Idempotent ledger consumer, retries, dead letters and replay |
| [0015](0015-ledger-events-and-notifications.md) | Ledger events, shared outbox module, notification service |
| [0016](0016-tracing-across-the-outbox.md) | One trace across HTTP, outbox and Kafka |
| [0017](0017-bank-reconciliation.md) | Bank reconciliation with Spring Batch |
| [0018](0018-identity-and-tokens.md) | Identity and tokens: auth-service, RS256 JWT, JWKS |
| [0019](0019-authorization-rate-limits-audit-logs.md) | Ownership checks, rate limits, audit log, log hygiene |
| [0020](0020-observability.md) | Metrics and the Grafana dashboard |
| [0021](0021-admin-ui.md) | Admin console: same origin through nginx, token in memory |
| [0022](0022-jenkins.md) | Jenkins pipeline in Docker, configuration as code |
| [0023](0023-deployment.md) | Public deployment on one VM with Compose and Caddy |
