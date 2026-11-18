# task-08 — code review (Android domain models + JsonStore + codec)

**Reviewer:** code-reviewer-complex  
**Date:** 2026-07-19  
**Repo:** `viwa-android`  
**Task:** `docs/agents/machine-cells-inventory/tasks/task-08.md`  
**Test report:** `task-08-test-report.md`  
**Architecture:** §Android domain models, JsonStoreKeys, uplink/downlink codec

## Verdict

**APPROVE** — реализация соответствует acceptance task-08. Критических дефектов нет. WS wiring отсутствует (корректно для scope). Unit-тесты 7/7 + 3 дополнительных — PASS (локально подтверждено).

---

## Acceptance criteria (task-08)

| # | Критерий | Статус | Доказательство |
|---|----------|--------|----------------|
| 1 | Domain models: `TelemetryCell`, `TelemetryProduct`, `TelemetryCellsSnapshot` | ✅ | Поля совпадают с `architecture.md` §Domain (Android); `@Serializable` для JsonStore |
| 2 | `JsonStoreKeys.TELEMETRY_CELLS_SNAPSHOT = "telemetryCellsSnapshot"` | ✅ | `JsonStoreKeys.kt:59`; legacy keys не тронуты |
| 3 | `TelemetryCellsRepository` — atomic read/write/replace + Flow | ✅ | `Mutex` + `replaceSnapshot` full replace; `snapshotFlow: StateFlow` |
| 4 | `TasteMediaKeyCatalog` — ровно 14 keys, aligned с assets | ✅ | `ALL_KEYS` (14 уникальных); тест `hasAssetMapping` для каждого ключа |
| 5 | `CellUuidAllocator` — stable uuid после persist (OQ-5) | ✅ | `uuidForCellNumber` читает existing snapshot; round-trip тест |
| 6 | `PhysicalCellSchemaProvider` — N cells + maxVolume mock | ✅ | `DefaultPhysicalCellSchemaProvider`: 6 × 5000 мл |
| 7 | Codec uplink: schema/volume/content без denormalized в content | ✅ | `CellContentReportWire` без `productName`/`tasteMediaKey`; тест substring + compile-time wire type |
| 8 | Codec downlink: `cells.snapshot` → products[] + denormalized cells | ✅ | `CellsSnapshotPayloadWire` → `TelemetryCellsSnapshot`; тест decode |
| 9 | Schema report: `clientSchemaHash`, `clientContentRevision` при snapshot | ✅ | `encodeSchemaReportPayload` берёт из `snapshot?.schemaHash/contentRevision` |
| 10 | Snapshot atomic replace incl. `products[]` | ✅ | `replaceSnapshot` перезаписывает весь JSON; тест A→B |
| 11 | Round-trip JsonStore | ✅ | `TelemetryCellsRepositoryImplTest.roundTrip_jsonStore_*` |
| 12 | **No WS wiring** | ✅ | Нет `TelemetryCellsSyncCoordinator`; grep по `app/src/main` — только codec/repository/module |
| 13 | Hilt DI | ✅ | `TelemetryCellsModule`: repository + schema provider; `@Inject @Singleton` для codec/allocator |
| 14 | Unit tests (TDD) | ✅ | 4 test classes; `gradlew :app:testDebugUnitTest` (targeted) exit 0 |

---

## Architecture compliance (§Android domain models)

| Invariant | Статус | Комментарий |
|-----------|--------|-------------|
| `TelemetryCell` mirrors CellFull (denormalized локально) | ✅ | `productName`, `tasteMediaKey` nullable на domain |
| Uplink `CellContentReport` — subset без denormalized | ✅ | Отдельный wire DTO + явный map в codec |
| Downlink snapshot — full payload | ✅ | `decodeSnapshotPayload` мапит products + cells |
| JsonStore key канон | ✅ | `"telemetryCellsSnapshot"` |
| 14 tasteMediaKey = `ViwaElectronAssets` канон | ✅ | Списки ключей идентичны (см. `ViwaElectronAssets` `MEDIA_KEY_TO_PNG`) |
| Atomic replace on snapshot | ✅ | Full document replace через `setJson` |
| Coordinator / WS — не в task-08 | ✅ | Отложено в task-09 по plan |

---

## Findings

### Critical

_Нет._

---

### Medium (non-blocking)

#### M-1. Domain → UI dependency в `TasteMediaKeyCatalog`

`domain/catalog/TasteMediaKeyCatalog.kt` импортирует `ui.screens.customer.ViwaElectronAssets` для `hasAssetMapping()`. Это инверсия слоёв (domain зависит от UI). Функционально корректно и гарантирует sync с assets, но для долгосрочной архитектуры лучше вынести `MEDIA_KEY_TO_PNG` в shared constants (например `domain/catalog/` или `core/assets`) и использовать в UI и catalog. Не блокер task-08 / task-09.

#### M-2. `snapshotFlow` не прогревается при старте репозитория

`_snapshotFlow` инициализируется `null`; загрузка из JsonStore — только при первом `getSnapshot()`. Подписчики Flow до вызова `getSnapshot()`/`replaceSnapshot()` увидят `null`. Для task-09 coordinator/ViewModel должны явно вызвать `getSnapshot()` при init — зафиксировать в wiring task-09.

#### M-3. `schemaHash` на устройстве не вычисляется

Codec принимает optional `schemaHash` параметр, но алгоритм SHA-256 из architecture §schemaHash не реализован. Ожидаемо для task-08 (codec + store); coordinator task-09 должен добавить вычисление или делегировать — cross-ref с golden vector (carry-over из backend reviews).

---

### Low / Info

| ID | Topic | Detail |
|----|-------|--------|
| L-1 | Дублирование `FakeConfigRepository` | Одинаковый fake в `TelemetryCellsRepositoryImplTest` и `CellUuidAllocatorTest` — вынести в test fixture при следующем touch |
| L-2 | Content uplink test через `toString()` | `root["cells"]!!.toString()` вместо парсинга `JsonArray` → `JsonObject` — хрупче, но PASS; type-safe wire DTO уже защищает |
| L-3 | Нет теста emission `snapshotFlow` | `replaceSnapshot` обновляет `_snapshotFlow`, но Flow не проверен Turbine — некритично для task-08 |
| L-4 | `@Immutable` на domain models | Compose-правило рекомендует `@Immutable` для data classes — опционально перед UI task |
| L-5 | `encodeSchemaReportPayloadFromPhysicalCells` | Не мержит `blockVolume`/`sosVolume` из existing snapshot cells — только structural fields; уточнить в task-09 при сборке полного schema report |
| L-6 | Corrupt JsonStore | `loadFromStore` логирует и возвращает `null` — поведение OK; recovery policy (clear vs keep) — task-09/coordinator |

---

## Файлы

| File | Оценка |
|------|--------|
| `domain/model/TelemetryCell.kt` | OK — mirrors CellFull, defaults для optional |
| `domain/model/TelemetryProduct.kt` | OK |
| `domain/model/TelemetryCellsSnapshot.kt` | OK — единый store document |
| `domain/catalog/TasteMediaKeyCatalog.kt` | OK — 14 keys + RU; ⚠️ M-1 UI dependency |
| `domain/telemetry/CellUuidAllocator.kt` | OK — stable uuid по cellNumber из snapshot |
| `domain/telemetry/PhysicalCellSchemaProvider.kt` | OK — MVP mock 6×5000 |
| `domain/repository/TelemetryCellsRepository.kt` | OK — контракт replace + Flow |
| `data/repository/TelemetryCellsRepositoryImpl.kt` | OK — Mutex, atomic replace, kotlinx.serialization |
| `data/remote/.../TelemetryCellsMessageCodec.kt` | OK — wire DTOs разделены uplink/downlink; `explicitNulls = false` |
| `data/local/db/JsonStoreKeys.kt` | OK — новый ключ с KDoc |
| `di/TelemetryCellsModule.kt` | OK — binds repository + schema provider |
| `test/.../TelemetryCellsRepositoryImplTest.kt` | OK — replace, round-trip, clear |
| `test/.../TelemetryCellsMessageCodecTest.kt` | OK — uplink/downlink/schema/volume |
| `test/.../TasteMediaKeyCatalogTest.kt` | OK — 14 keys, asset, reject unknown |
| `test/.../CellUuidAllocatorTest.kt` | OK — persist round-trip через repository |

---

## Тесты

| Suite | Cases | Result | Замечание |
|-------|-------|--------|-----------|
| `TelemetryCellsRepositoryImplTest` | 3 | PASS | replace incl. products, round-trip, clear |
| `TelemetryCellsMessageCodecTest` | 4 | PASS | no denormalized uplink, downlink snapshot, schema client fields, volume |
| `TasteMediaKeyCatalogTest` | 3 | PASS | 14 keys, assets, reject unknown |
| `CellUuidAllocatorTest` | 1 | PASS | stable uuid after JsonStore persist |
| **Локально** `gradlew :app:testDebugUnitTest` (targeted, 2026-07-19) | — | **PASS** exit 0 | совпадает с test report |

---

## Scope notes (OK for task-08)

- `TelemetryCellsSyncCoordinator`, WS dispatch в `MvpTelemetryWebSocketManager` / `SimpleTelemetryCoordinator` — **не** реализованы (task-09).
- Service menu / customer UI adapter — вне scope.
- `schemaHash` computation on device — deferred (M-3).
- Git commit — не выполнялся (по test report).

---

## Рекомендации для task-09

1. При wiring coordinator: `getSnapshot()` на старте + `replaceSnapshot` на downlink `cells.snapshot` (M-2).
2. Реализовать/подключить `schemaHash` (SHA-256 canonical JSON) — golden vector с backend (M-3).
3. Рассмотреть вынос taste keys map из UI layer (M-1).
4. Опционально: Turbine-тест на `snapshotFlow` после `replaceSnapshot` (L-3).
5. Уточнить merge `blockVolume`/`sosVolume` в schema report из локального snapshot (L-5).

---

## Итог для оркестратора

```json
{
  "reviewReportFile": "docs/agents/machine-cells-inventory/tasks/task-08-review.md",
  "hasCriticalIssues": false,
  "commentsSummary": "Domain models, JsonStore snapshot repository, 14-key TasteMediaKeyCatalog, CellUuidAllocator и TelemetryCellsMessageCodec реализованы по architecture §Android: uplink content без productName/tasteMediaKey, downlink snapshot с products[] и denormalized cells, atomic replace, WS wiring отсутствует. Unit-тесты 7/7+3 PASS. Некритично: domain→UI dependency в catalog (ViwaElectronAssets), snapshotFlow без eager load до getSnapshot(), schemaHash на устройстве — в task-09."
}
```
