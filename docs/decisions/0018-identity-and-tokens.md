# ADR 0018: Identity and tokens (auth-service, JWT)
- Status: accepted · Date: 2026-10-26
## Context
SPEC 3 and 6.8: Spring Security OAuth2 resource server (JWT) plus a small auth module that issues tokens, with roles USER, MERCHANT and ADMIN. Until now every API was open and payments identified the client with an unauthenticated `X-Client-Id` header (ADR 0012).
## Decision
- A separate `auth-service` (port 8084, own database `auth`) issues tokens; ledger-service and payment-service only verify them, so a compromised business service holds no signing key.
- `POST /auth/token` takes a username and password and returns an RS256 JWT valid 15 minutes with claims `iss=payledger-auth`, `sub=<username>`, `roles=[<ROLE>]`, `jti`. The public key is published at `GET /.well-known/jwks.json`; resource servers fetch it lazily and validate signature, expiry and issuer.
- Users live in `auth.users` (username, BCrypt hash, role). There is no self-registration: `POST /auth/users` is ADMIN-only, and the first ADMIN is created at startup from the env var `AUTH_ADMIN_PASSWORD` when the table is empty. Passwords need at least 10 characters.
- Login uses the same error for an unknown user and a wrong password and does a dummy BCrypt comparison for unknown users, so neither the response nor its timing reveals which usernames exist. Login is rate-limited per client IP (ADR 0019).
- The signing key is read from `AUTH_JWT_PRIVATE_KEY` (PKCS8 PEM). If it is not set a key is generated at startup and every token dies when the process restarts; a warning is logged.
- Identity is the username. `sub` is compared with `accounts.owner_id` in the ledger and `payments.merchant_id` in the payment service, so no mapping tables are needed. A MERCHANT user `m1` owns the ledger MERCHANT account with owner id `m1` and the payments with merchant id `m1`. The authenticated `sub` also replaces `X-Client-Id` as the idempotency scope.
## Consequences
If auth-service is down, tokens that are valid cannot be verified until the key set is fetched again (401). Not built: refresh tokens, password reset, MFA, OIDC discovery, key rotation with overlap (the JWKS format allows it later). TLS is terminated in front of the services (M10), so tokens must not be sent over plain HTTP outside local development.
## Alternatives rejected
Spring Authorization Server (a full OAuth2 server for a three-role demo); signing tokens inside ledger-service (couples identity to a business service); HS256 with a shared secret (every verifier could forge tokens).
