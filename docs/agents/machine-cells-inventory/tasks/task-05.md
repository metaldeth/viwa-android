# task-05 — WS v2 handlers + snapshot push + Nest wiring

**Repo:** `viwa-telemetry`  
**Зависимости:** task-03, task-04

## Связь с этапом

M3 — WS v2 handlers, reconcile, dedup, snapshot push, reconnect.

## Что сделать

- Bump `WS_PROTOCOL_VERSION` to **2** in `crypto.util.ts` (or canonical location).
- Extend `MachinesWsService`:
  - `hello` payload: `protocolVersion=2`, `supportedMessageTypes` incl. cell types.
  - Dispatch uplink: `cells.schema.report`, `cells.volume.report`, `cells.content.report`.
  - Dedup before apply via `MachineWsDedupService`.
  - Ack payloads per architecture §Uplink table.
  - v1 backward compat: v1 clients connect without breaking.
- **`MachinesWsRegistry`:** export public `sendToMachine(machineId, envelope)` (C-4 Variant B).
- Wire `forwardRef` between `MachinesModule` ↔ `MachineCellsModule`.
- After successful `cells.schema.report` reconcile → optional `cells.snapshot` if `shouldPushSnapshotAfterSchemaReport` (C-3).
- Extend `MachineCellsController` PATCH: if machine ONLINE → push `cells.snapshot` via registry.
- Error envelopes with `correlationId` on validation failures.

## Точки изменения

- `viwa-telemetry/apps/api/src/machines/machines-ws.service.ts`
- `viwa-telemetry/apps/api/src/machines/machines-ws.registry.ts`
- `viwa-telemetry/apps/api/src/machines/machines.module.ts`
- `viwa-telemetry/apps/api/src/machine-cells/machine-cells.module.ts`
- `viwa-telemetry/apps/api/src/machine-cells/machine-cells.controller.ts` — snapshot side-effect
- `viwa-telemetry/apps/api/src/crypto.util.ts` (or WS constants file)

## Тест-кейсы

1. **Hello v2:** protocolVersion=2, supportedMessageTypes includes cell types.
2. **Schema report:** creates cells, ack with schemaHash + counts; preserve on repeat.
3. **Volume report:** volume updated; product unchanged.
4. **Content report:** upsert with contentSource rules.
5. **Dedup WS:** duplicate messageId → ack deduplicated, no double DB write.
6. **PATCH cells ONLINE:** mock registry receives `cells.snapshot` with products[] + cells.
7. **Reconnect snapshot:** schema report with stale clientContentRevision → snapshot pushed after ack.
8. **Nest bootstrap:** AppModule starts with forwardRef (no circular dependency crash).
9. **Machine JWT:** cannot hit REST PATCH (regression from task-03).

## Критерии приёмки (TZ)

#14–#17; UC-1, UC-7; architecture C-2 lazy products refresh on reconnect only.

## Verification

`npm test`; manual/mock WS client for snapshot timing.

## Contract freeze

После этой задачи — freeze envelope для task-11 и Android task-09 integration.
