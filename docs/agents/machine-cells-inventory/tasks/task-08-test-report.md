# task-08 — test report

**Repo:** `viwa-android`  
**Date:** 2026-07-19

## Scope

M6 (часть 1): domain models, JsonStore snapshot, WS codec (без WS wiring).

## Unit tests

| # | Case | Result | File |
|---|------|--------|------|
| 1 | Snapshot atomic replace: B replaces A incl. `products[]` | PASS | `TelemetryCellsRepositoryImplTest` |
| 2 | Codec uplink content: `productUuid` only; no `productName` in JSON | PASS | `TelemetryCellsMessageCodecTest` |
| 3 | Codec downlink snapshot: `products[]` + denormalized cells | PASS | `TelemetryCellsMessageCodecTest` |
| 4 | TasteMediaKeyCatalog: exactly 14 keys; asset mapping | PASS | `TasteMediaKeyCatalogTest` |
| 5 | CellUuidAllocator: same cell slot → same uuid after persist | PASS | `CellUuidAllocatorTest` |
| 6 | Schema report payload: `clientSchemaHash`, `clientContentRevision` when snapshot exists | PASS | `TelemetryCellsMessageCodecTest` |
| 7 | Round-trip JsonStore: persisted snapshot equals loaded | PASS | `TelemetryCellsRepositoryImplTest` |

Дополнительно:

| Case | Result |
|------|--------|
| `clearSnapshot` removes JsonStore key | PASS |
| Volume report payload serializes `updates[]` | PASS |
| Unknown taste key rejected by catalog | PASS |

## Verification commands

| Command | Exit code |
|---------|-----------|
| `gradlew.bat :app:testDebugUnitTest --tests com.viwa.android.data.repository.TelemetryCellsRepositoryImplTest --tests com.viwa.android.data.remote.telemetry.mvp.cells.TelemetryCellsMessageCodecTest --tests com.viwa.android.domain.catalog.TasteMediaKeyCatalogTest --tests com.viwa.android.domain.telemetry.CellUuidAllocatorTest` | 0 |

## New / modified files

**Domain**

- `domain/model/TelemetryCell.kt`
- `domain/model/TelemetryProduct.kt`
- `domain/model/TelemetryCellsSnapshot.kt`
- `domain/catalog/TasteMediaKeyCatalog.kt`
- `domain/telemetry/CellUuidAllocator.kt`
- `domain/telemetry/PhysicalCellSchemaProvider.kt`
- `domain/repository/TelemetryCellsRepository.kt`

**Data**

- `data/repository/TelemetryCellsRepositoryImpl.kt`
- `data/remote/telemetry/mvp/cells/TelemetryCellsMessageCodec.kt`
- `data/local/db/JsonStoreKeys.kt` — `TELEMETRY_CELLS_SNAPSHOT`

**DI**

- `di/TelemetryCellsModule.kt`

**Tests**

- `test/.../TelemetryCellsRepositoryImplTest.kt`
- `test/.../TelemetryCellsMessageCodecTest.kt`
- `test/.../TasteMediaKeyCatalogTest.kt`
- `test/.../CellUuidAllocatorTest.kt`

## Notes

- WS wiring / `TelemetryCellsSyncCoordinator` — **не** в scope (task-09).
- `DefaultPhysicalCellSchemaProvider`: 6 ячеек, `maxVolume=5000` мл (mock MVP).
- Uplink `cells.content.report` не сериализует denormalized поля (`productName`, `tasteMediaKey`).
- Downlink `cells.snapshot` → atomic replace через `TelemetryCellsRepository.replaceSnapshot`.

## Git

Коммит **не** выполнялся (по заданию).
