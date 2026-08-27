# NekoFlash Reference Archives

This directory contains immutable historical and founding reference snapshots.

These archives are NOT production source code of the new NekoFlash application.

## Archives

### NekoFlash-main-legacy.zip

Legacy NekoFlash implementation.

Purpose:
- functional reference;
- UX and branding reference;
- Mi Unlock workflow reference;
- historical hardware behavior and evidence;
- capability inventory.

The new NekoFlash must not inherit the Legacy architecture wholesale.

### NekoFlash-A2-frozen.zip

Frozen A2 implementation.

Purpose:
- USB transport correctness reference;
- ADB/Fastboot correctness reference;
- cancellation/drain/poison semantics;
- mutation-boundary reference;
- Sideload correctness model;
- tests and engineering evidence.

A2 development is CLOSED.

The final ADB inbound USB framing fix passed the hardware gate.
A2 remains reference/evidence only and must not become the production
codebase of the new application.

### NekoFlash-canonical-20260827.zip

Founding canonical specification snapshot for the clean NekoFlash project.

This archive preserves the original canonical planning state as of
2026-08-27.

The actively maintained project documentation lives under `docs/`.

## Integrity

Archive integrity is recorded in:

`reference/SHA256SUMS`

Reference archives are immutable.

Do not replace or modify an existing archive after it has been committed.
If a new historical snapshot is needed, add a new explicitly dated archive
and record its SHA-256 checksum.

## Project principle

The new NekoFlash is a professional Android host toolkit.

Legacy and A2 are sources of verified behavior, protocol contracts,
engineering lessons, tests and evidence.

They are not templates to copy wholesale into the new architecture.
