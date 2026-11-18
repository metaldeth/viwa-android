# task-04 — Cell apply services (reconcile, volume, content, dedup, snapshot)

**Repo:** `viwa-telemetry`  
**Зависимости:** task-01

## Связь с этапом

M3 (domain layer) — сервисы apply до WS wiring.

## Что сделать

- **`CellSchemaReconcileService`:** алгоритм §5 FEATURE/TZ:
  - Match by uuid → update structural, preserve volume/product/prices.
  - Same cellNumber, new uuid → deactivate old, insert new (OQ-2).
  - Missing in report → `isActive=false` (OQ-1).
  - Compute `schemaHash` SHA-256 canonical JSON sorted by cellNumber.
  - Update `Machine.cellSchemaHash`, `cellSchemaSyncedAt`.
  - Return counts created/updated/deactivated.
- **`CellVolumeApplyService`:** UPDATE only volume (+ optional block/sos OQ-4); never product/prices.
- **`CellContentApplyService`:** OQ-8 / C-5 per-cell `contentSource`:
  - `DASHBOARD`: skip product/prices from machine; apply volume if present; apply structural if present.
  - `MACHINE`: apply all content fields + optional volumes.
  - LWW by `sentAt` only between two MACHINE content reports.
- **`MachineWsDedupService`:** `tryAcquire(machineId, messageId)`; false → deduplicated ack path.
- **`MachineCellsSnapshotService`:**
  - Build snapshot payload: `schemaHash`, `contentRevision`, `products[]`, denormalized `cells[]`.
  - `shouldPushSnapshotAfterSchemaReport(clientSchemaHash, clientContentRevision)` — C-3 rules.
  - **No** WS send yet — only builder + decision logic.
- Export services from `MachineCellsModule`.

## Точки изменения

- `viwa-telemetry/apps/api/src/machine-cells/cell-schema-reconcile.service.ts`
- `viwa-telemetry/apps/api/src/machine-cells/cell-volume-apply.service.ts`
- `viwa-telemetry/apps/api/src/machine-cells/cell-content-apply.service.ts`
- `viwa-telemetry/apps/api/src/machine-cells/machine-ws-dedup.service.ts`
- `viwa-telemetry/apps/api/src/machine-cells/machine-cells-snapshot.service.ts`
- `viwa-telemetry/apps/api/src/machine-cells/machine-cells.module.ts`
- `viwa-telemetry/apps/api/test/fixtures/cells/` — JSON fixtures для reconcile/snapshot

## Тест-кейсы

1. **Reconcile insert:** first schema report → N cells, defaults volume=0, product=null.
2. **Reconcile preserve:** repeat same uuids → volume/product/prices unchanged.
3. **Reconcile deactivate:** cell in DB not in report → isActive=false.
4. **Reconcile re-key:** same cellNumber new uuid → old deactivated, new inserted.
5. **Volume apply:** updates volume only; productUuid unchanged after volume report.
6. **Content apply MACHINE:** full upsert product + prices + volume.
7. **Content apply DASHBOARD gate:** machine report with new productUuid ignored for product/prices; volume applied.
8. **Dedup:** second same messageId → no double apply.
9. **Snapshot builder:** products[] full catalog; cells denormalized.
10. **shouldPushSnapshot:** server contentRevision > client → true; schemaHash mismatch → true.

## Критерии приёмки (TZ)

#2–#7, #16 logic (without WS transport); architecture C-1, C-3, C-5.

## Verification

`npm test` — unit-heavy для reconcile и contentSource.
