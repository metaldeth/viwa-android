# task-12 — Integration E2E tests

**Repo:** `viwa-telemetry` (+ optional Android contract tests)  
**Зависимости:** task-05, task-07, task-09, task-11

## Связь с этапом

M7 (часть 2) — E2E integration.

## Что сделать

- **`viwa-telemetry/apps/api` E2E test** (Nest testing module or supertest + mock WS client):
  1. Register machine → assert 0 cells in DB.
  2. WS connect → receive hello v2.
  3. Send `cells.schema.report` → cells created; ack with schemaHash.
  4. REST POST product → catalog entry.
  5. REST PATCH cell product/prices → contentSource DASHBOARD.
  6. Mock WS client receives `cells.snapshot` with products[] + denormalized cell.
  7. Send `cells.volume.report` → volume updated.
  8. GET cells reflects new volume.
  9. Duplicate messageId → deduplicated ack, no double apply.
- Optional: reconnect scenario — schema report with stale clientContentRevision → snapshot pushed.
- Optional Android: contract fixture test loading JSON from shared fixtures (codec parity with API fixtures).
- Document test run in session log if executed.

## Точки изменения

- `viwa-telemetry/apps/api/test/e2e/machine-cells-inventory.e2e-spec.ts` (или `.spec.ts` по convention проекта)
- `viwa-telemetry/apps/api/test/fixtures/cells/` — дополнить если нужно
- Optional: `viwa-android/app/src/test/.../TelemetryCellsContractFixturesTest.kt`

## Тест-кейсы (E2E — основной deliverable)

1. **Full happy path:** register → hello → schema → product create → PATCH cell → snapshot → volume report → GET cells (TZ #31).
2. **Register no cells:** machine_cells count 0 after register only.
3. **Reconcile preserve:** second schema same uuids → product/prices/volume preserved.
4. **AuthZ regression:** machine JWT REST PATCH → 403.
5. **Reconnect snapshot:** server newer contentRevision → downlink snapshot after schema report.

## Критерии приёмки (TZ)

#31, #15 (snapshot within integration stand timing); integration section M7.

## Verification

```powershell
cd c:\viwa\viwa-telemetry
npm test
```

```powershell
cd c:\viwa\viwa-android
gradlew.bat :app:testDebugUnitTest
```

Manual staging (if E2E mock insufficient): WEB-4 ONLINE PATCH → device snapshot ≤5s.
