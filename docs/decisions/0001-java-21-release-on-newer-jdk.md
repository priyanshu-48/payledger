# ADR 0001: Target Java 21 via `maven.compiler.release` on a newer local JDK
- Status: accepted · Date: 2026-10-24
## Context
Spec requires Java 21. Dev machine has JDK 24 only (and JAVA_HOME points at JDK 8). Docker images and CI use JDK 21.
## Decision
`maven.compiler.release=21` in the parent POM; CI and Dockerfile use JDK 21. Build locally with JAVA_HOME set to JDK 24.
## Alternatives rejected
Installing a second JDK (extra setup, no gain: `release` restricts the API to 21).
## Consequences
Bytecode and API are Java 21. Runtime behaviour differences only appear in JDK 24 local runs; CI catches them on 21.
