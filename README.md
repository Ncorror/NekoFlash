# NekoFlash

Clean-room production rewrite of NekoFlash.

The implementation is intentionally minimal. Phase 2 is in progress with a hardware-proven Android USB boundary and a minimal evidence-first ADB `CNXN`/`AUTH` handshake probe. ADB services/shell/sync and all Fastboot protocol bytes remain absent. Legacy, A2 and the previous clean-tree are evidence/reference sources, not production bases.

Start at [`docs/00_START_HERE.md`](docs/00_START_HERE.md). The only current roadmap/status source is [`docs/03_ROADMAP.md`](docs/03_ROADMAP.md). A new chat/session should recover context from those repository docs and the current `production-reset` HEAD rather than relying on prior chat memory.
