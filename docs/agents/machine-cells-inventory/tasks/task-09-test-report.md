# task-09 — test report

**Repo:** `viwa-android`  
**Date:** 2026-07-19

## Scope

M6 (часть 2): `TelemetryCellsSyncCoordinator`, WS uplink/downlink, hello wiring, legacy gate.

## Unit tests

| # | Case | Result | File |
|---|------|--------|------|
| 1 | Post-hello schema: structural cells only in `cells[]` (no product/volume) | PASS | `TelemetryCellsSyncCoordinatorTest` |
| 2 | Volume uplink: `cells.volume.report` with uuid+volume only | PASS | `TelemetryCellsSyncCoordinatorTest` |
| 3 | Content uplink: `cells.content.report` without denormalized fields | PASS | `TelemetryCellsSyncCoordinatorTest` |
| 4 | Snapshot downlink: full replace store incl. `products[]` | PASS | `TelemetryCellsSyncCoordinatorTest` |
| 5 | Reconnect: second schema report carries `clientSchemaHash` / `clientContentRevision` | PASS | `TelemetryCellsSyncCoordinatorTest` |
| 6 | Legacy gate: `useMvpProtocol=false` → coordinator not invoked on hello | PASS | `TelemetryCellsSyncCoordinatorTest` |
| 7 | Snapshot during local edit: MVP full replace overwrites pending state | PASS | `TelemetryCellsSyncCoordinatorTest` |
| 8 | Schema ack: persists server `schemaHash` for next reconnect | PASS | `TelemetryCellsSyncCoordinatorTest` |

## Verification commands

| Command | Exit code |
|---------|-----------|
| `gradlew.bat :app:testDebugUnitTest --tests com.viwa.android.data.remote.telemetry.mvp.TelemetryCellsSyncCoordinatorTest` | 0 |
| `gradlew.bat :app:testDebugUnitTest --tests com.viwa.android.data.remote.telemetry.mvp.SimpleTelemetryCoordinator*` | 0 |

## New / modified files (task-09)

**Coordinator + WS**

- `data/remote/telemetry/mvp/TelemetryCellsSyncCoordinator.kt` — post-hello schema, volume/content uplink, snapshot apply, schema ack
- `data/remote/telemetry/mvp/MvpTelemetryCellsSyncHandler.kt` — callback interface
- `data/remote/telemetry/mvp/MvpTelemetryWebSocketManager.kt` — dispatch `hello` → schema, `cells.snapshot`, schema ack
- `data/remote/telemetry/mvp/SimpleTelemetryCoordinator.kt` — handler wiring, `warmUp()` before connect when MVP enabled

**Tests**

- `test/.../TelemetryCellsSyncCoordinatorTest.kt` — 8 TDD cases
- `test/.../SimpleTelemetryCoordinator*Test.kt` — mock `cellsSyncCoordinator` in constructor

## Behavior notes

- **Post-hello:** `cells.schema.report` with `clientSchemaHash` / `clientContentRevision` from persisted snapshot; optional `cells.content.report` when snapshot has reportable content (OQ-9).
- **Volume/content uplink:** best-effort via `sendEnvelope`; local snapshot updated before send (OQ-7).
- **Downlink `cells.snapshot`:** atomic full replace via `TelemetryCellsRepository.replaceSnapshot` (MVP overwrites pending local edits).
- **Legacy isolation:** `useMvpProtocol=true` uses coordinator; `false` skips cells sync handler; Shaker topics remain no-op via existing `skipLegacyTopic` in `ViwaTelemetryService`.
- **Reconnect (C-3):** `schemaHash` from server ack persisted; next hello sends saved revision fields.

## Instrumented / dev API

Не запускалось (нет dev WS в CI-окружении агента).

## Git

Коммит **не** выполнялся (по заданию).
