# Start Here

This tree is the new NekoFlash production rewrite.

Read in this order:

1. `01_PRODUCT_AND_PROTOCOL_RULES.md`
2. `02_ARCHITECTURE.md`
3. `03_ROADMAP.md` — the only current roadmap/status source
4. `04_HARDWARE_EVIDENCE.md`
5. `05_CAPABILITY_MATRIX.md`

Every iteration starts and ends with a code/tests/docs/evidence drift check. A change is incomplete if the code and its documentation/evidence disagree.

Repository line policy: production work stays on `production-reset`; `main` remains a reference line and is not merged into or used as the production base during the rewrite. In Termux, `scripts/gpush` is retained as repository tooling and may be installed with `install -m 0755 scripts/gpush "$PREFIX/bin/gpush"`.

Legacy, A2 and the previous clean-tree remain evidence only. Before implementing a concrete protocol behavior, inspect the relevant exact snapshot source and tests instead of relying only on summaries.

## New-chat / new-session recovery contract

A fresh chat or agent session must not depend on previous conversation memory. Recover project state from the repository itself:

1. Work only from the `production-reset` branch unless the owner explicitly changes that policy.
2. Read this file, then `01_PRODUCT_AND_PROTOCOL_RULES.md`, `02_ARCHITECTURE.md`, `03_ROADMAP.md`, `04_HARDWARE_EVIDENCE.md`, and `05_CAPABILITY_MATRIX.md` in that order.
3. Treat `03_ROADMAP.md` as the only current phase/status authority. Historical statements in evidence/docs stay historical and must not override it.
4. Run repository hygiene, localization, and documentation-consistency gates before changing code. Run `scripts/ci/check_all.sh` when an Android SDK/network-capable environment is available.
5. Treat the exact Legacy/A2/current snapshots and their recorded hashes as the fixed implementation/test/hardware evidence base. `main` is a reference line, not the production base.
6. Before ending an iteration, repeat the code/tests/docs/evidence drift audit and update docs/evidence in the same changeset.
7. Do not introduce a new module, capability restriction, ADB/Fastboot/USB implementation, or policy merely because an older tree contained it. Follow the current roadmap and the founding rules.

If a new session follows those steps and inspects the current Git HEAD, it has enough repository-resident context to continue without relying on this chat history.
