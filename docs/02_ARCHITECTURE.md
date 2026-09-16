# Architecture

Phase 1 closed with only three real production boundaries:

- `:app` — Android presentation/bootstrap shell.
- `:core:model` — target/session identity and cross-feature outcome vocabulary.
- `:core:diagnostics` — structured local evidence primitives.

No USB, ADB, Fastboot, Recovery or vendor production modules existed at Phase 1 closeout. Phase 2 may introduce a transport boundary only when executable ownership begins; placeholder modules remain forbidden.

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
