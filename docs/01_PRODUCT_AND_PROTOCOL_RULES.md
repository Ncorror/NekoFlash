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

## Professional capability policy boundary

NekoFlash is a professional Android host toolkit. **Correctness protection is not capability restriction.** The application does not create a second authorization system above ADB, Fastboot, Recovery or vendor protocols. This rule is binding on typed UI, raw consoles, Quick Flash, Mi Unlock and future automation; see ADR `0005_PROFESSIONAL_CAPABILITY_POLICY.md`.

There is no Novice/Expert permission profile, unlockable Expert Mode, hidden capability tier, product command allowlist/denylist, or vendor/product whitelist that grants the right to use a valid protocol capability. Progressive disclosure is a UI density choice only. Raw protocol surfaces may expose valid peer capabilities directly and must not be arbitrarily narrower than typed surfaces.

All decisions that can stop or shape an operation belong to exactly one of four classes:

- **Hard invariant** — stop/fail/`Unknown` because protocol/platform correctness cannot be preserved: malformed framing, stale ownership/generation, impossible wire representation, broken exact-byte contract, ambiguous mutation, unsafe cancel/drain state, missing platform permission, or an equivalent technical condition.
- **Device authority** — send the valid request and surface the actual peer/device result, including `FAIL`, lock/AVB/OEM restrictions, missing ADB authorization, unsupported service, or vendor rejection. Do not pre-empt the peer with a host authorization rule.
- **Advisory** — warn/preflight and allow the professional operator to continue; never turn uncertainty, a quirk, lock state, low battery or missing optional metadata into a hidden deny.
- **User intent** — guided UI may request a precise confirmation (including typed confirmation for destructive actions); raw protocol surfaces remain direct. Confirmation expresses intent, not protocol permission.

If mutation may have happened but the final state cannot be proved, the result is `Unknown` and the host must not hide that uncertainty with a silent automatic retry. Any future proposal for a host-side restriction must name the concrete Hard invariant or platform requirement it enforces; "dangerous", "advanced", "locked", "unsupported by our UI", or "not on an allowlist" is not sufficient.

## ADB handshake evidence rule

The first ADB slice is diagnostic, not a general ADB client. It may send exactly one `CNXN` and the peer-required `AUTH` signature/public-key responses. It must not send `OPEN`, `WRTE`, shell, sync/push, reboot, package-management or other ADB service traffic. There is no vendor VID/PID whitelist: ADB candidacy is the protocol interface shape (`FF/42/01`) with bulk IN+OUT.

Handshake diagnostics may record command/type names, byte counts, endpoint addresses, call timing/result codes, peer protocol/max-payload metadata and a coarse peer kind (`DEVICE`/`RECOVERY`/`SIDELOAD`/`UNKNOWN`). They must not record AUTH tokens, RSA signatures, public-key material, private-key paths/content, or raw connection banners. A failed USB transfer is terminal for the probe: no automatic second `CNXN`, reconnect, `clearEndpointHalt`, or service command is allowed to hide the first failure.

## USB/ADB auto-first entry rule

The normal product path is not a sequence of diagnostic button presses. After the Welcome/entry gate, NekoFlash automatically performs the safe chain `startup/USB attach -> scan -> unique canonical ADB candidate -> USB permission when required -> open/claim -> bounded CNXN/AUTH handshake`. Android USB permission and target-side RSA authorization remain explicit platform/user approvals; once they are granted, the host flow resumes automatically.

Automatic selection is intentionally conservative: exactly one physical device with exactly one canonical ADB `FF/42/01` interface and bulk IN+OUT may advance. Generic vendor bulk interfaces, multiple devices, or multiple canonical ADB interfaces remain manual evidence cases. The manual Scan/Permission/Open+claim/ADB controls remain available for diagnostics and retry, but they are not the expected happy path. A terminal handshake/transport failure is not silently retried in the same attachment generation; detach or an explicit New evidence session re-arms automation. This behavior is an acceptance contract, not temporary UI polish, and future Fastboot entry must follow the same auto-first principle when its new engine exists.

## Fastboot DATA backend retention rule

The clean rewrite must not erase hardware-proven transport capability merely because the implementation is being replaced.
When the Fastboot DATA phase is introduced, its architecture must preserve explicit backend selection with at least:

- Java `ASYNC_USB_REQUEST` as the hardware-proven A2 fallback baseline;
- `NATIVE_USBFS` as a required high-throughput capability to be reimplemented and freshly validated;
- a bounded synchronous bulk path only as an explicitly selected fallback/diagnostic mode, never as a silent retry after DATA negotiation.

Native-vs-Java selection is resolved before `download:`. Once DATA negotiation starts, an ambiguous native transfer must not be retried inline on another backend. Native USBFS must preserve confirmed-byte accounting, two-URB ownership, adaptive ENOMEM reduction, `DISCARDURB -> REAP` before memory release, and fail-closed poisoned-backend semantics when drain cannot be proven. These are correctness requirements, not optional performance polish.
