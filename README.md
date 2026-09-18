# NekoFlash

Clean-room production rewrite of NekoFlash. NekoFlash is a **professional Android host toolkit**: protocol correctness is strict, but the product does not invent capability tiers, command allowlists, vendor whitelists, or a second host-side authorization system above ADB/Fastboot/Recovery/vendor protocols.

The implementation is intentionally minimal. Phase 2 is in progress with a hardware-proven Android USB boundary, a hardware-proven minimal ADB `CNXN`/`AUTH` handshake, and an auto-first USB/ADB entry path that restores the useful Legacy/A2 startup/attach behavior while keeping manual controls as diagnostic fallback. ADB services/shell/sync and all Fastboot protocol bytes remain absent. Legacy, A2 and the previous clean-tree are evidence/reference sources, not production bases.

Start at [`docs/00_START_HERE.md`](docs/00_START_HERE.md). The only current roadmap/status source is [`docs/03_ROADMAP.md`](docs/03_ROADMAP.md). The cross-archive/current-protocol capability audit lives in [`docs/06_FULL_PROTOCOL_AUDIT.md`](docs/06_FULL_PROTOCOL_AUDIT.md), and the binding professional capability policy is ADR [`0005_PROFESSIONAL_CAPABILITY_POLICY.md`](docs/adr/0005_PROFESSIONAL_CAPABILITY_POLICY.md). A new chat/session should recover context from those repository docs and the current `production-reset` HEAD rather than relying on prior chat memory.
