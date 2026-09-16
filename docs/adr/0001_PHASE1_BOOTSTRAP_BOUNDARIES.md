# ADR 0001 — Phase 1 Bootstrap Boundaries

Status: Accepted

The rewrite begins with only `:app`, `:core:model` and `:core:diagnostics`.

Reason: these are current ownership boundaries with executable responsibility now. USB and protocol modules would be placeholders in Phase 1 and therefore are intentionally absent.

The Gradle/toolchain versions are copied as build evidence from the exact current snapshot, but no previous production implementation or architecture is copied wholesale.
