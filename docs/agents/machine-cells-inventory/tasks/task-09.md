# task-09 — Android TelemetryCellsSyncCoordinator + WS integration

**Repo:** `viwa-android`  
**Зависимости:** task-05, task-08

## Связь с этапом

M6 (часть 2) — schema/volume/content uplink, snapshot apply, hello wiring.

## Что сделать

- **`TelemetryCellsSyncCoordinator`:**
  - After WS `hello` → build schema from `PhysicalCellSchemaProvider` + persisted uuids → send `cells.schema.report` with `clientSchemaHash`, `clientContentRevision`.
  - On volume local change → update snapshot → fire `cells.volume.report` (best-effort, no UI block on ack — OQ-7).
  - On content local change → `cells.content.report` with CellContentReport shape + volumes.
  - On `cells.snapshot` downlink → **full replace** snapshot store (cells + products[]).
  - Persist schemaHash/contentRevision for next reconnect handshake.
- Extend **`MvpTelemetryWebSocketManager`:** dispatch `cells.snapshot` to coordinator.
- Extend **`SimpleTelemetryCoordinator`:** trigger schema report post-hello.
- Ensure `useMvpProtocol=true` path uses coordinator; legacy Shaker topics remain no-op (`skipLegacyTopic`).
- DI wiring (Hilt module if needed).

## Точки изменения

- `app/src/main/java/com/wiva/android/data/remote/telemetry/mvp/TelemetryCellsSyncCoordinator.kt` (новый)
- `app/src/main/java/com/wiva/android/data/remote/telemetry/mvp/MvpTelemetryWebSocketManager.kt`
- `app/src/main/java/com/wiva/android/data/remote/telemetry/mvp/SimpleTelemetryCoordinator.kt`
- Hilt modules under `di/` if new bindings required

## Тест-кейсы (TDD обязательны)

1. **Post-hello schema:** coordinator emits schema report with structural cells only in payload cells[].
2. **Volume uplink:** local volume change produces volume.report with uuid+volume only.
3. **Content uplink:** inventory edit produces content.report without denormalized fields.
4. **Snapshot apply:** downlink snapshot replaces entire store including products[].
5. **Reconnect fields:** second schema report includes clientSchemaHash/contentRevision from saved snapshot.
6. **Legacy gate:** with useMvpProtocol=true, legacy cell topic handlers not invoked (mock verify).
7. **Snapshot during local edit (MVP):** full replace overwrites local pending state (documented behavior).

## Критерии приёмки (TZ)

#21, #25, #28 partial; UC-1, UC-3, UC-4, UC-7; architecture C-3 reconnect.

## Verification

`gradlew.bat :app:testDebugUnitTest`; optional instrumented test against dev API + WS if available.
