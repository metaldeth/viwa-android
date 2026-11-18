# task-04 — test report

**Repo:** `viwa-telemetry`  
**Date:** 2026-07-19

## Unit tests — reconcile

| # | Case | Result |
|---|------|--------|
| 1 | First schema report → N cells, defaults volume=0, product=null | PASS |
| 2 | Repeat same uuids → volume/product/prices unchanged | PASS |
| 3 | Cell in DB not in report → isActive=false | PASS |
| 4 | Same cellNumber new uuid → old hard-deleted, new inserted; volume/product/prices/contentSource preserved (OQ-2) | PASS |

**File:** `apps/api/test/cell-schema-reconcile.service.spec.ts`

## Unit tests — volume apply

| # | Case | Result |
|---|------|--------|
| 5 | Volume apply updates volume only; productUuid unchanged | PASS |

**File:** `apps/api/test/cell-volume-apply.service.spec.ts`

## Unit tests — content apply

| # | Case | Result |
|---|------|--------|
| 6 | Content apply MACHINE: full upsert product + prices + volume | PASS |
| 7 | Content apply DASHBOARD gate: product/prices ignored; volume applied | PASS |
| — | Stale MACHINE report (sentAt < updatedAt) skips product/prices, applies volume | PASS |

**File:** `apps/api/test/cell-content-apply.service.spec.ts`

## Unit tests — dedup

| # | Case | Result |
|---|------|--------|
| 8 | Second same messageId → tryAcquire returns false | PASS |

**File:** `apps/api/test/machine-ws-dedup.service.spec.ts`

## Unit tests — snapshot builder

| # | Case | Result |
|---|------|--------|
| 9 | Snapshot builder: products[] full catalog; cells denormalized | PASS |
| 10 | shouldPushSnapshot: contentRevision newer → true | PASS |
| 10 | shouldPushSnapshot: schemaHash mismatch → true | PASS |
| 10 | shouldPushSnapshot: first connect with active cells → true | PASS |
| 10 | shouldPushSnapshot: client up to date → false | PASS |

**File:** `apps/api/test/machine-cells-snapshot.service.spec.ts`

## Fixtures

- `apps/api/test/fixtures/cells/schema-first-report.json`
- `apps/api/test/fixtures/cells/schema-deactivate.json`
- `apps/api/test/fixtures/cells/schema-rekey.json`
- `apps/api/test/fixtures/cells/snapshot-fixture.json`

## Verification commands

| Command | Exit code |
|---------|-----------|
| `npm test` (root) | 0 |
| `npm run build` (root) | 0 |
| `npm run lint -w @wiva/api` | 0 |

## Scope notes

- `MachineCellsModule` создан с сервисами и `NoOpMachineWsPushFacade` stub; **не** импортирован в `AppModule` (task-05).
- WS dispatch и REST cells controller — вне scope task-04.

## Open questions

- **OQ-4:** block/sos в volume report опциональны — реализовано как partial UPDATE.
- **C-3 first connect:** push при `clientSchemaHash=null && clientContentRevision=null && hasActiveCells` — может совпасть с schemaHash mismatch; оба пути ведут к push (ожидаемо для MVP).

## Review fix (2026-07-19, round 2)

- **OQ-2 re-key:** вместо deactivate+insert — `delete` старой строки + `create` новой в одной транзакции (обходит `@@unique([machineId, cellNumber])`).
- **Mock:** `MachineCellsStore.create` эмулирует P2002 при конфликте `(machineId, cellNumber)`.
- **schemaHash JSDoc:** зафиксирован порядок ключей `cellNumber, maxVolume, uuid`.

## Hotfix (2026-07-19, OQ-2 preserve)

- **Re-key:** после `delete` новая строка сохраняет `volume`, `productId`, `dosage1Price`, `dosage2Price`, `contentSource` со старой ячейки; structural — из incoming; `schemaRevision+1`.
