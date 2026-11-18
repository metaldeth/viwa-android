# task-11 — Contract documentation

**Repo:** `viwa-telemetry` (+ ссылка в `viwa-android`)  
**Зависимости:** task-05

## Связь с этапом

M7 (часть 1) — contract doc.

## Что сделать

- Создать **`viwa-telemetry/docs/contracts/machine-cells-inventory.md`**:
  - WS envelope types (uplink/downlink) with JSON examples.
  - REST endpoints table + error codes (`INVALID_TASTE`, `VOLUME_READ_ONLY`, `CELL_NOT_FOUND`, `INVALID_THRESHOLDS`).
  - C-1 denormalized fields rules (REST/snapshot vs uplink content).
  - C-2 `products[]` in snapshot; lazy catalog refresh policy.
  - C-3 reconnect handshake fields (`clientSchemaHash`, `clientContentRevision`).
  - C-5 `contentSource` apply algorithm.
  - schemaHash algorithm.
  - AuthZ matrix.
  - Protocol v2 hello changes vs v1 baseline (`registration-machine-jwt.md` link).
- Добавить ссылку в:
  - `viwa-android/docs/SIMPLE_TELEMETRY_MVP_ANDROID.md` (section «Related contracts»).
  - `viwa-android/docs/FEATURE_MACHINE_CELLS_INVENTORY.md` §10 if missing entry.
- Shared JSON fixtures reference: `apps/api/test/fixtures/cells/`.

## Точки изменения

- `viwa-telemetry/docs/contracts/machine-cells-inventory.md` (новый)
- `viwa-android/docs/SIMPLE_TELEMETRY_MVP_ANDROID.md` — ссылка
- `viwa-android/docs/FEATURE_MACHINE_CELLS_INVENTORY.md` — ссылка в §10

## Тест-кейсы

1. Doc review: every message type in task-05 has payload schema in contract.
2. Doc review: REST paths match implemented controllers (task-02, task-03).
3. Cross-check: 14 tasteMediaKey values match `TASTE_MEDIA_KEYS` constant and Android catalog.

## Критерии приёмки (TZ)

#32 acceptance; M7 deliverable.

## Verification

Peer review / docs lint (links valid); no code changes required beyond doc links.
