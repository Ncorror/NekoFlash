# ADR 0005 — Professional capability policy: no invented host-side authorization

Status: accepted, recovered and reaffirmed 2026-09-18.

## Context

The previous clean-tree explicitly defined NekoFlash as a professional Android host toolkit and rejected product-level hard guards, Novice/Expert permission profiles, hidden capability tiers and product whitelists as an authorization model. The production reset already retained the shorter rule "Do not invent host-side capability bans", but the full archive audit showed that the stronger formulation needs to live in the rewrite itself so later implementation work cannot silently narrow the product.

## Decision

1. NekoFlash does not create a second authorization system above ADB, Fastboot, Recovery or vendor protocols.
2. Valid protocol capabilities are not removed because they are powerful, destructive, uncommon, vendor-specific or intended for professionals.
3. There is no Novice/Expert permission profile, hidden Expert Mode, capability tier or product allowlist that grants protocol rights.
4. Guided UI may warn and request explicit/typed confirmation for destructive intent. That confirmation is a UX representation of user intent, not a protocol permission and not a check inside the shared protocol engine.
5. Raw protocol surfaces remain available and do not become artificially narrower than typed surfaces. Typed helpers add structure/validation/UX; they do not grant capabilities.
6. Peer/device authority remains authoritative. ADB authorization, lock state, AVB/OEM restrictions, Fastboot `FAIL`, unsupported Recovery services and vendor rejection are surfaced as peer/device outcomes, not pre-empted by a product ban.
7. Host-side refusal is valid only for concrete correctness/platform constraints: malformed framing, stale ownership/generation, impossible wire representation, exact-byte contract failure, ambiguous mutation, unsafe cancel/drain state, missing platform permission, or equivalent conditions where proceeding cannot be represented truthfully/correctly.
8. When mutation may have happened and final state cannot be proved, the result is `Unknown` and no silent automatic retry is allowed.
9. Quick Flash, Mi Unlock, typed UI and raw consoles converge on shared production engines. No feature may create a private capability policy by bypassing or wrapping the engine with a hidden deny list.
10. Any future proposal for a host-side capability restriction must identify the concrete correctness/platform invariant it enforces. "Dangerous", "advanced", "locked", "unsupported by our UI" or "not on an allowlist" is not sufficient.

## Four decision classes

- **Hard invariant** — stop/fail/unknown because technical correctness cannot be preserved.
- **Device authority** — send the valid request and surface the real peer/device response.
- **Advisory** — warn/preflight/override; do not silently deny.
- **User intent** — guided UI may confirm; raw protocol path remains direct.

## Consequences

Capability completeness is evaluated by real protocol/workflow support, not by policy gates. Safety work focuses on exact framing, ownership, generation, mutation boundaries, evidence and honest outcomes rather than disabling professional functions. This ADR is binding on the future ADB service engine, Fastboot engine, Recovery/Sideload, Quick Flash and Mi Unlock work.
