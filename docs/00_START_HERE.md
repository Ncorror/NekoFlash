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
