# Security Policy

NekoFlash controls USB transports, ADB host identity, Fastboot operations and diagnostic evidence. Security reports should therefore avoid publishing target identifiers, private keys, authentication material, unlock tokens or raw diagnostic bundles in a public issue.

## Reporting a vulnerability

Prefer GitHub private vulnerability reporting / a private security advisory when the repository UI offers it. If private reporting is not available, open a public issue containing **only** a request for a private contact channel and no exploit details, secrets, device serials or diagnostic attachments.

A useful private report includes the affected revision, Android host version/device, target mode (ADB/Recovery/Fastboot/fastbootd), exact preconditions, expected vs actual behavior and the smallest reproduction that does not expose unrelated personal data.

## Sensitive project data

- Never attach an ADB host private key. If one is exposed, treat that host identity as compromised and replace it.
- Raw diagnostics are evidence bundles, not sanitized support bundles. They may contain host/device identifiers, USB serials, paths, command text and protocol responses; review them before sharing.
- A transport failure after a possibly mutating write is an **unknown outcome**, not evidence that the target was untouched. Do not recommend blind retry when wire effect is ambiguous.

## Supported versions

The repository is currently a development line (`0.1.0-dev`). Until tagged releases define a support matrix, security fixes target the current `main` line.
