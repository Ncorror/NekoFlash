# Product and Protocol Rules

## Founding rules

- Rewrite implementation and architecture; preserve verified knowledge, tests, hardware observations and brand evidence.
- Legacy, A2 and the previous clean-tree are reference/evidence, not production bases.
- Do not invent host-side capability bans. Device/peer responses remain authoritative unless a concrete protocol/transport invariant makes sending technically invalid.
- Hard correctness remains strict for framing, ownership, generation, mutation boundaries, exact byte accounting and cancellation/drain semantics.
- Outcomes include `Success`, `Failed`, `Cancelled` and `Unknown`. If mutation may have happened but final state cannot be proved, use `Unknown` and do not silently retry.
- Typed UI, raw console, Quick Flash and Mi Unlock must converge on the same production protocol engines when those engines are introduced.
- Documentation drift is a defect. Update code/tests/docs/evidence together.
- The Legacy Welcome JPEG is immutable and byte-identical. The launcher artwork remains reference-only until owner review.
- Preserve Legacy color DNA through semantic roles rather than by cloning the old UI architecture.

## Product policy boundary

Warnings and explicit user-intent confirmation belong in guided UI. They are not protocol permissions. Raw protocol surfaces must not be arbitrarily narrower than typed surfaces.

## Fastboot DATA backend retention rule

The clean rewrite must not erase hardware-proven transport capability merely because the implementation is being replaced.
When the Fastboot DATA phase is introduced, its architecture must preserve explicit backend selection with at least:

- Java `ASYNC_USB_REQUEST` as the hardware-proven A2 fallback baseline;
- `NATIVE_USBFS` as a required high-throughput capability to be reimplemented and freshly validated;
- a bounded synchronous bulk path only as an explicitly selected fallback/diagnostic mode, never as a silent retry after DATA negotiation.

Native-vs-Java selection is resolved before `download:`. Once DATA negotiation starts, an ambiguous native transfer must not be retried inline on another backend. Native USBFS must preserve confirmed-byte accounting, two-URB ownership, adaptive ENOMEM reduction, `DISCARDURB -> REAP` before memory release, and fail-closed poisoned-backend semantics when drain cannot be proven. These are correctness requirements, not optional performance polish.
