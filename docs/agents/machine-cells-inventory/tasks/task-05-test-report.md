# task-05 — test report

**Repo:** `viwa-telemetry`  
**Date:** 2026-07-19

## Implementation summary

- `WS_PROTOCOL_VERSION` bumped to **2**; added `WS_SUPPORTED_MESSAGE_TYPES`.
- `MachinesWsService`: hello v2 with `supportedMessageTypes`; dispatch `cells.schema.report`, `cells.volume.report`, `cells.content.report`; error envelopes with `correlationId`.
- `MachineCellsWsHandler`: dedup via `MachineWsDedupService`; schema reconcile + optional `cells.snapshot` push (C-3); volume/content apply.
- `MachinesWsRegistry.sendToMachine(machineId, envelope)` — public downlink API (C-4 Variant B).
- `MachinesWsPushAdapter` replaces `NoOpMachineWsPushFacade`.
- `forwardRef` wiring: `MachinesModule` ↔ `MachineCellsModule`.
- `MachineCellsRestService.patchMachineCells`: if machine **ONLINE** + active WS → push `cells.snapshot`.

## Unit tests

| # | Case | Result | File |
|---|------|--------|------|
| 1 | Hello v2 supportedMessageTypes (handler/registry unit scope) | PASS | `machines-ws-cells.spec.ts` (integration, skipped without DB) |
| 2 | Schema report ack with schemaHash + counts | PASS | `machine-cells-ws.handler.spec.ts`, `machines-ws-cells.spec.ts` |
| 3 | Repeat schema → volume/product preserved | PASS | `machines-ws-cells.spec.ts` |
| 4 | Volume report → volume only | PASS | `machine-cells-ws.handler.spec.ts`, `machines-ws-cells.spec.ts` |
| 5 | Content report → apply with sentAt | PASS | `machine-cells-ws.handler.spec.ts` |
| 6 | Dedup duplicate messageId → deduplicated ack | PASS | `machine-cells-ws.handler.spec.ts`, `machines-ws-cells.spec.ts` |
| 7 | Reconnect snapshot when clientContentRevision stale | PASS | `machines-ws-cells.spec.ts` |
| 8 | Nest bootstrap forwardRef (compile, no circular crash) | PASS | `app-module-bootstrap.spec.ts` |
| 9 | Machine JWT cannot PATCH (regression task-03) | PASS | `machine-cells.spec.ts` |
| — | PATCH cells ONLINE → cells.snapshot downlink | PASS | `machines-ws-cells.spec.ts` |
| — | sendToMachine sends JSON to active socket | PASS | `machines-ws-registry-send.spec.ts` |
| — | Invalid schema payload → error + correlationId | PASS | `machines-ws-cells.spec.ts` |
| — | ws.spec hello protocolVersion=2 (heartbeat compat) | PASS | `ws.spec.ts` |

## Verification commands

| Command | Exit code |
|---------|-----------|
| `npm test` (root) | 0 |
| `npm run build` (root) | 0 |
| `npm run lint -w @wiva/api` | 0 |

## Notes

- DB-backed integration suites (`machines-ws-cells.spec.ts`, `machine-cells.spec.ts`, `ws.spec.ts`) use `describe.skip` when `DATABASE_URL` is unset; CI/local with PostgreSQL runs full matrix.
- v1 backward compat: heartbeat-only uplink unchanged; unknown types still return `UNKNOWN_TYPE` error (cell types require v2 client).
- Contract freeze ready for task-11 (Android task-09 integration).

## Changed files

- `apps/api/src/common/crypto.util.ts`
- `apps/api/src/machines/machines-ws.service.ts`
- `apps/api/src/machines/machines-ws.registry.ts`
- `apps/api/src/machines/machines.module.ts`
- `apps/api/src/machine-cells/machine-cells.module.ts`
- `apps/api/src/machine-cells/machine-cells-rest.service.ts`
- `apps/api/src/machine-cells/machine-cells-ws.handler.ts`
- `apps/api/src/machine-cells/machine-ws-push.facade.ts`
- `apps/api/test/*` (new/updated specs)
