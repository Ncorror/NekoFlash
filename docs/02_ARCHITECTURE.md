# Architecture

Phase 1 owns only three real boundaries:

- `:app` — Android presentation/bootstrap shell.
- `:core:model` — target/session identity and cross-feature outcome vocabulary.
- `:core:diagnostics` — structured local evidence primitives.

No USB, ADB, Fastboot, Recovery or vendor production modules exist yet. They will be added only when their implementation phase begins and a real ownership boundary exists.

Conceptual direction for later phases:

```text
Presentation / Device Workspace / Terminal
        ↓
Application use cases + operation-specific state
        ↓
ADB / Fastboot / Recovery / Vendor
        ↓
Target / SessionGeneration
        ↓
USB transport
```

`TargetId`, `SessionGeneration`, `TargetMode` and future transport handles are separate concepts. No global mutable `currentDevice/currentConnection` contract is introduced.
