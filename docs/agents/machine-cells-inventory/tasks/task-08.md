# task-08 — Android domain models + JsonStore + codec

**Repo:** `viwa-android`  
**Зависимости:** — (контракт: `architecture.md`)

## Связь с этапом

M6 (часть 1) — snapshot store, types, codec unit tests.

## Что сделать

- Domain models:
  - `TelemetryCell` — mirrors CellFull (incl. denormalized productName, tasteMediaKey).
  - `TelemetryProduct` — uuid, name, tasteMediaKey.
  - `TelemetryCellsSnapshot` — schemaHash, contentRevision, products[], cells[], savedAtEpochMs.
- `JsonStoreKeys.TELEMETRY_CELLS_SNAPSHOT = "telemetryCellsSnapshot"`.
- `TelemetryCellsRepository` — atomic read/write/replace Flow.
- `TasteMediaKeyCatalog` — 14 keys aligned with `ViwaElectronAssets.MEDIA_KEY_TO_PNG`.
- `CellUuidAllocator` — stable uuid generation on first schema init (OQ-5).
- `PhysicalCellSchemaProvider` — source of N cells + maxVolume (mock/config).
- **`TelemetryCellsMessageCodec`:**
  - Uplink: schema.report, volume.report, content.report (CellContentReport **without** productName/tasteMediaKey).
  - Downlink: parse `cells.snapshot` full payload with products[].
- **No WS wiring yet** — codec + repository only.

## Точки изменения

- `app/src/main/java/com/wiva/android/domain/model/TelemetryCell.kt` (новый)
- `app/src/main/java/com/wiva/android/domain/model/TelemetryProduct.kt` (новый)
- `app/src/main/java/com/wiva/android/domain/model/TelemetryCellsSnapshot.kt` (новый)
- `app/src/main/java/com/wiva/android/domain/catalog/TasteMediaKeyCatalog.kt` (новый)
- `app/src/main/java/com/wiva/android/domain/telemetry/CellUuidAllocator.kt` (новый)
- `app/src/main/java/com/wiva/android/domain/telemetry/PhysicalCellSchemaProvider.kt` (новый)
- `app/src/main/java/com/wiva/android/domain/repository/TelemetryCellsRepository.kt` (новый)
- `app/src/main/java/com/wiva/android/data/repository/TelemetryCellsRepositoryImpl.kt` (новый)
- `app/src/main/java/com/wiva/android/data/local/db/JsonStoreKeys.kt`
- `app/src/main/java/com/wiva/android/data/remote/telemetry/mvp/cells/TelemetryCellsMessageCodec.kt` (новый)

## Тест-кейсы (TDD обязательны)

1. **Snapshot atomic replace:** save snapshot B replaces A entirely including products[].
2. **Codec uplink content:** serializes productUuid only; no productName in JSON.
3. **Codec downlink snapshot:** deserializes products[] + denormalized cells.
4. **TasteMediaKeyCatalog:** exactly 14 keys; each maps to known asset key set.
5. **CellUuidAllocator:** same cell slot returns same uuid after persist round-trip.
6. **Schema report payload:** includes clientSchemaHash, clientContentRevision when snapshot exists.
7. **Round-trip JsonStore:** TelemetryCellsSnapshot persisted and loaded equals.

## Критерии приёмки (TZ)

#22 partial; architecture domain models §Android.

## Verification

`gradlew.bat :app:testDebugUnitTest` for new test classes.
