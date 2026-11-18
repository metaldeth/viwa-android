# task-09 — code review (Android TelemetryCellsSyncCoordinator + WS integration)

**Reviewer:** code-reviewer-complex  
**Date:** 2026-07-19  
**Repo:** `viwa-android`  
**Task:** `docs/agents/machine-cells-inventory/tasks/task-09.md`  
**Test report:** `task-09-test-report.md`  
**Architecture:** C-3 reconnect handshake, OQ-7 volume best-effort, OQ-9 optional post-schema content, legacy `skipLegacyTopic`

## Verdict

**APPROVE** — task-09 закрывает M6 (часть 2): post-hello schema report, volume/content uplink, snapshot full replace, reconnect fields, MVP legacy gate через `cachedUseMvpProtocol`, OQ-9 optional content после успешного schema. Критических дефектов нет; unit-тесты 8/8 PASS (по test report). Интеграция `onLocalVolumeChange` / `onLocalContentChange` в production callers и E2E WS — вне scope / follow-up.

---

## Focus check (checklist ревью)

| # | Проверка | Статус | Доказательство |
|---|----------|--------|----------------|
| 1 | Post-hello → `cells.schema.report` (structural cells only) | ✅ | `TelemetryCellsSyncCoordinator.sendSchemaReport` L81–94; `buildSchemaReportCells` без product/volume; test `post-hello schema emits structural cells only` |
| 2 | Volume uplink: snapshot update → `cells.volume.report` (uuid+volume) | ✅ | `onLocalVolumeChange` L68–72; `applyVolumeUpdatesToSnapshot` + `sendVolumeReport`; test volume report |
| 3 | Content uplink: без denormalized полей + volumes | ✅ | `encodeContentReportPayload` (codec) + `onLocalContentChange`; test `inventory edit produces content report without denormalized fields` |
| 4 | Downlink `cells.snapshot` → full replace incl. `products[]` | ✅ | `onCellsSnapshot` → `repository.replaceSnapshot`; tests snapshot downlink + snapshot during local edit |
| 5 | Reconnect: `clientSchemaHash` / `clientContentRevision` из persisted snapshot | ✅ | `encodeSchemaReportPayload` via codec; test second schema report |
| 6 | Legacy gate: MVP path vs skip coordinator | ✅ (partial) | `SimpleTelemetryCoordinator` init L43–62: `cachedUseMvpProtocol`; test legacy gate false path; `ViwaTelemetryService.skipLegacyTopic` L428–434 — pre-existing, не новый код task-09 |
| 7 | OQ-9: optional `cells.content.report` после успешного schema | ✅ impl / ⚠️ test | `maybeSendInitialContentReport` L96–101, вызов `.onSuccess { maybeSendInitialContentReport(snapshot) }` L93; **нет** `coVerify` content report в post-hello тесте |
| 8 | C-3: persist `schemaHash` из ack для следующего hello | ✅ | `onSchemaAck` → `mergeRevisionFields`; test `schema ack persists server schemaHash` |
| 9 | WS wiring: hello / snapshot / ack dispatch | ✅ | `MvpTelemetryWebSocketManager.onHello` L239–242, `onCellsSnapshot` L258–265, `onAck` L245–256 |
| 10 | `warmUp()` до connect при MVP | ✅ | `SimpleTelemetryCoordinator.connectInternal` L481–483 (carry-over task-08 M-2) |

---

## Acceptance criteria (task-09 / TZ)

| # | Критерий | Статус | Комментарий |
|---|----------|--------|-------------|
| 1 | Post-hello schema: structural cells only | ✅ | 6 cells из `DefaultPhysicalCellSchemaProvider`; без product/volume в payload |
| 2 | Volume uplink best-effort (OQ-7) | ✅ | Локальный snapshot обновляется до send; `sendEnvelope` failure → Timber.w, без throw |
| 3 | Content uplink без denormalized | ✅ | Wire DTO из task-08; coordinator делегирует codec |
| 4 | Snapshot full replace | ✅ | `replaceSnapshot` atomic; MVP overwrite pending edits задокументирован в KDoc L23–24 |
| 5 | Reconnect handshake fields | ✅ | Из persisted snapshot; ack обновляет `schemaHash` |
| 6 | Legacy gate `useMvpProtocol=true` | ✅ (coordinator) / ℹ️ (Shaker) | Coordinator gated; Shaker no-op через существующий `skipLegacyTopic` — не регрессия task-09 |
| 7 | Snapshot during local edit (MVP overwrite) | ✅ | Test PASS |
| — | TZ #21, #25, #28 partial; UC-1, UC-3, UC-4, UC-7 | ✅ partial | WS orchestration готов; UI/service callers volume/content — не подключены в `app/src/main` (см. M-2) |
| — | Unit tests TDD | ✅ | 8 cases PASS; SimpleTelemetryCoordinator* с mock coordinator |

---

## Architecture compliance

### C-3 reconnect

- Android отправляет `clientSchemaHash` / `clientContentRevision` из `TelemetryCellsSnapshot` — соответствует architecture §Reconnect.
- `schemaHash` после ack сервера персистится через `mergeRevisionFields` — следующий hello несёт актуальный hash.
- `contentRevision` обновляется при downlink snapshot (full replace) — согласовано с server-authoritative revision.

### OQ-7 / OQ-9

- Volume/content send не блокируют caller: `Result` + log on failure.
- OQ-9: content report только после **успешного** schema send; отсутствие не ломает flow — соответствует «recommended, not blocking».

### Legacy isolation

- `useMvpProtocol=false` → handler не вызывает coordinator (unit test).
- `useMvpProtocol=true` → legacy Shaker topics no-op через `ViwaTelemetryService.skipLegacyTopic` (не изменялся в task-09; поведение из FEATURE doc).

---

## Findings

### Critical

_Нет._

---

### Medium (non-blocking)

| ID | Topic | Detail |
|----|-------|--------|
| M-1 | UUID не персистятся до snapshot downlink | `sendSchemaReport` вызывает `uuidAllocator.allocateForPhysicalCells`, но **не** сохраняет выделенные uuid в `TelemetryCellsRepository`. На пустом store каждый `onWebSocketHello()` может сгенерировать **новые** uuid (нарушение OQ-5 до первого snapshot). MVP mitigates: server ack + snapshot downlink обычно следуют сразу; при обрыве между hello и snapshot — риск duplicate reconcile на сервере. Рекомендация: после allocate merge minimal structural cells в snapshot (uuid, cellNumber, maxVolume) перед send или сразу после успешного schema ack. |
| M-2 | Uplink API без production callers | `onLocalVolumeChange` / `onLocalContentChange` существуют только в coordinator + tests; grep `app/src/main` — нет вызовов из `ViwaTelemetryService` / ViewModel. Ожидаемо для M6 part 2 (orchestration layer); wiring в volume/import path — task-10 или follow-up. Зафиксировать в plan, чтобы не считать UC-3/UC-4 закрытыми end-to-end. |
| M-3 | `schemaHash` на устройстве не вычисляется | Carry-over task-08 M-3: coordinator не передаёт локальный `schemaHash` в uplink payload (только `clientSchemaHash` из store). Сервер вычисляет canonical hash и возвращает в ack — для MVP достаточно; golden vector cross-platform — task-11 / hardening. |
| M-4 | OQ-9 без dedicated unit test | `maybeSendInitialContentReport` реализован, но post-hello тест мокает `cells.content.report` и **не** проверяет `coVerify` при snapshot с `productUuid`/volume. Добавить тест: schema success + reportable snapshot → content report; schema failure → content report не шлётся. |
| M-5 | Legacy gate test — только negative path | Test case 6 в task-09 описывает «useMvpProtocol=true → legacy handlers not invoked»; реализован только `false → coordinator skip`. Positive path coordinator + `skipLegacyTopic` — без unit test (приемлемо: Shaker gate pre-existing). |

---

### Low / Info

| ID | Topic | Detail |
|----|-------|--------|
| L-1 | Concurrent handler jobs | `onHello`, `onCellsSnapshot`, `onSchemaAck` запускаются в `appScope.launch` без serial mutex. На reconnect возможен overlap schema uplink + snapshot downlink — architecture допускает оба порядка; MVP OK. |
| L-2 | `onAck` routing по `schemaHash` key | Любой ack с полем `schemaHash` триггерит `onSchemaAck`. Сейчас только schema ack несёт это поле — OK; при расширении протокола — явный type/correlation filter. |
| L-3 | `cachedUseMvpProtocol` @Volatile | Runtime смена config без reconnect может desync handler gate и `ViwaTelemetryService.isMvpProtocolActive()` — edge case настроек; не блокер MVP. |
| L-4 | Instrumented / dev WS | Не запускалось (test report) — риск wire incompatibility только E2E (task-12). |
| L-5 | Circular DI | `TelemetryCellsSyncCoordinator` → `MvpTelemetryWebSocketManager`; handler wiring runtime через `SimpleTelemetryCoordinator.init` — compile-time цикла нет; Hilt `@Singleton` порядок OK. |
| L-6 | `applyVolumeUpdatesToSnapshot` no-op на null snapshot | `getSnapshot() ?: return` — volume change до первого snapshot silently ignored; согласовано с пустым store, документировать для callers. |

---

## Файлы

| File | Оценка |
|------|--------|
| `TelemetryCellsSyncCoordinator.kt` | OK — orchestration, OQ-7/9, MVP full replace KDoc; ⚠️ M-1 uuid persist |
| `MvpTelemetryCellsSyncHandler.kt` | OK — минимальный callback interface |
| `MvpTelemetryWebSocketManager.kt` | OK — hello/snapshot/ack dispatch, `cellsSyncHandler` delegate; fire-and-forget launch |
| `SimpleTelemetryCoordinator.kt` | OK — MVP gate, warmUp, handler wiring в init |
| `TelemetryCellsSyncCoordinatorTest.kt` | OK — 8 TDD cases PASS; ⚠️ M-4 OQ-9 gap, M-5 legacy positive path |
| `SimpleTelemetryCoordinator*Test.kt` | OK — mock `cellsSyncCoordinator` в constructor (регрессия DI) |

---

## Тесты

| Suite | Cases | Result | Замечание |
|-------|-------|--------|-----------|
| `TelemetryCellsSyncCoordinatorTest` | 8 | PASS | По test report + targeted gradlew exit 0 |
| `SimpleTelemetryCoordinator*` | mock coordinator | PASS | Wiring не ломает existing suites |
| Instrumented / dev WS | — | не запускалось | CI-окружение агента |

---

## Scope notes

- Production wiring `onLocalVolumeChange` / `onLocalContentChange` — **не** в task-09 (M-2).
- `schemaHash` SHA-256 on device — deferred (M-3).
- Git commit — не выполнялся (по test report).

---

## Рекомендации (post-merge, optional)

1. **M-1:** persist allocated uuids (minimal structural cells) перед/после первого schema report.
2. **M-4:** unit test OQ-9 path (`maybeSendInitialContentReport`).
3. **M-2:** подключить coordinator в volume/import handlers (task-10).
4. **M-3:** локальный `schemaHash` + golden vector с backend (contract task-11).
5. Task-12 E2E: reconnect stale revision → snapshot after schema.

---

## Итог для оркестратора

```json
{
  "reviewReportFile": "docs/agents/machine-cells-inventory/tasks/task-09-review.md",
  "hasCriticalIssues": false,
  "commentsSummary": "TelemetryCellsSyncCoordinator + WS integration APPROVE: post-hello cells.schema.report с clientSchemaHash/clientContentRevision, volume/content best-effort uplink, cells.snapshot full replace, schema ack persist для C-3, OQ-9 optional content после успешного schema, legacy gate через cachedUseMvpProtocol + существующий skipLegacyTopic. Unit-тесты 8/8 PASS. Некритично: uuid из allocator не персистятся до snapshot downlink (OQ-5 edge), onLocalVolumeChange/onLocalContentChange без production callers, schemaHash на устройстве не вычисляется (carry-over task-08), нет unit-теста OQ-9 content-after-schema, instrumented WS не проверялся."
}
```
