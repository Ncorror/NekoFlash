# Capability Matrix

This file records capability/evidence direction, not current implementation status. Current implementation status lives only in `03_ROADMAP.md`.

| Area | Evidence carried forward | Rewrite direction |
|---|---|---|
| Target/session identity | A2/current tests and hardware observations | Separate `TargetId`, `SessionGeneration`, `TargetMode` and transport ownership |
| Diagnostics | A2/current source/tests | Structured evidence first; redact secrets in shareable bundles |
| ADB | Legacy breadth, A2 framing tests, current multi-stream evidence | Future single production engine; one physical reader; generic services; no artificial whitelist |
| Fastboot | Legacy breadth, A2 transaction/DATA tests, current device-authority evidence | Future single transaction lane; peer `FAIL` preserved; exact byte accounting; DATA IN still open |
| Recovery/Sideload | Legacy behavior, A2 mutation/correlation tests, current evidence | Distinguish transfer completion from installation success; retain `Unknown` semantics |
| Quick Flash | Legacy behavior/evidence | Future use case over the same Fastboot engine |
| Mi Unlock | Legacy source/historical evidence | Future vendor use case over generic Fastboot core; fresh final verification required |
| Brand | Legacy exact assets/colors | Welcome immutable; launcher reference retained; semantic Legacy color DNA |
