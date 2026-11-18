# task-05 — code review (WS v2 cells + snapshot push + Nest wiring)

**Reviewer:** code-reviewer-complex  
**Date:** 2026-07-19  
**Repo:** `viwa-telemetry`  
**Task:** `docs/agents/machine-cells-inventory/tasks/task-05.md`  
**Test report:** `task-05-test-report.md`  
**Architecture:** C-3 reconnect snapshot, C-4 Variant B (`forwardRef` + `sendToMachine`), WS uplink/downlink handlers

## Verdict

**APPROVE** — task-05 закрывает M3 WS wiring: protocol v2 hello, cell uplink dispatch, dedup, C-3 snapshot push, REST PATCH side-effect, Nest `forwardRef` без circular crash. Критических дефектов нет; contract freeze для task-11 / Android task-09 допустим с учётом medium-замечаний ниже.

---

## Focus check (checklist ревью)

| # | Проверка | Статус | Доказательство |
|---|----------|--------|----------------|
| 1 | `WS_PROTOCOL_VERSION=2`, hello `supportedMessageTypes` с cell types | ✅ | `crypto.util.ts` L100–111; `MachinesWsService.handleConnection` L125–134; integration `machines-ws-cells.spec.ts`, `ws.spec.ts` |
| 2 | `MachineCellsWsHandler` + dispatch в `MachinesWsService` | ✅ | `CELL_UPLINK_TYPES` + `cellsWsHandler.handleUplink` L219–230; handler: schema/volume/content + dedup L51–70 |
| 3 | `MachinesWsRegistry.sendToMachine` + `MachinesWsPushAdapter` | ✅ | `machines-ws.registry.ts` L73–81; `MachinesWsPushAdapter` → `cells.snapshot` envelope; unit `machines-ws-registry-send.spec.ts` |
| 4 | `forwardRef` MachinesModule ↔ MachineCellsModule | ✅ | `machines.module.ts` L17; `machine-cells.module.ts` L19; `@Inject(forwardRef(() => MachineCellsWsHandler))` в `MachinesWsService` L42–43 |
| 5 | Schema/volume/content reports + dedup + snapshot push (C-3 + PATCH ONLINE) | ✅ | Dedup `tryAcquire` L55–58; `shouldPushSnapshotAfterSchemaReport` L94–105; `MachineCellsRestService.pushSnapshotIfOnline` L117–130 |
| 6 | Bootstrap без circular crash | ✅ | `app-module-bootstrap.spec.ts`; `AppModule` импортирует оба модуля + `MachinesModule` → `forwardRef(MachineCellsModule)` |

---

## Acceptance criteria (task-05 / TZ #14–#17)

| # | Критерий | Статус | Комментарий |
|---|----------|--------|-------------|
| 1 | Hello v2: protocolVersion=2, supportedMessageTypes incl. cell types | ✅ | Массив включает `cells.schema.report`, `cells.volume.report`, `cells.content.report`, `cells.snapshot` |
| 2 | Schema report: ack schemaHash + counts; repeat preserve content | ✅ | Handler ack L107–113; integration repeat schema preserves volume |
| 3 | Volume report: volume only, product unchanged | ✅ | `CellVolumeApplyService`; integration asserts productId null |
| 4 | Content report: apply with sentAt / contentSource rules | ✅ | `contentApply.apply(..., sentAt)` L133; rules в task-04 service |
| 5 | Dedup: duplicate messageId → deduplicated ack, no double write | ✅ | `MachineWsDedupService` + handler L55–58; integration count=2 |
| 6 | PATCH cells ONLINE → `cells.snapshot` with products[] + cells | ✅ | `pushSnapshotIfOnline`: status ONLINE + `hasActiveConnection`; integration PATCH test |
| 7 | Reconnect: stale clientContentRevision → snapshot after schema | ✅ | `shouldPushSnapshotAfterSchemaReport`; integration stale revision test |
| 8 | Nest bootstrap forwardRef | ✅ | `app-module-bootstrap.spec.ts` compile + resolve both services |
| 9 | Machine JWT cannot REST PATCH (task-03 regression) | ✅ | `machine-cells.spec.ts` forbids machine JWT bearer |
| — | Error envelope + correlationId on validation failure | ✅ | `MachinesWsService.sendError(..., envelope.messageId)` L228; integration bad payload |
| — | v1 backward compat (heartbeat) | ✅ | Heartbeat path unchanged L199–216; unknown types → `UNKNOWN_TYPE` L233–238 |

---

## Architecture compliance

### C-3 reconnect snapshot

- Handshake fields `clientSchemaHash`, `clientContentRevision` читаются в `handleSchemaReport` L94–100.
- Алгоритм push делегирован в `MachineCellsSnapshotService.shouldPushSnapshotAfterSchemaReport` (4 условия из architecture §Reconnect).
- Snapshot push после успешного reconcile, до return ack (downlink может прийти раньше ack — клиент должен обрабатывать оба порядка).

### C-4 Variant B

- Публичный `MachinesWsRegistry.sendToMachine(machineId, envelope)` экспортирован из `MachinesModule`.
- Push из `MachineCellsModule` через `MachinesWsPushAdapter` + token `MACHINE_WS_PUSH_FACADE` — без прямой зависимости handler → `MachinesWsService`.
- Цикл разорван `forwardRef` на уровне модулей и инжекта `MachineCellsWsHandler` в `MachinesWsService`.

### WS contract (uplink ack table)

| type | ack (implementation) | architecture | Match |
|------|------------------------|--------------|-------|
| `cells.schema.report` | `{ ok, schemaHash, created, updated, deactivated }` | § Uplink table | ✅ |
| `cells.volume.report` | `{ ok, applied }` | best-effort | ✅ |
| `cells.content.report` | `{ ok, applied }` | | ✅ |
| dedup | `{ ok, deduplicated: true }` | § Dedup | ✅ |

---

## Findings

### Critical

_Нет._

---

### Medium (non-blocking)

| ID | Topic | Detail |
|----|-------|--------|
| M-1 | Dedup до валидации / apply | `tryAcquire` вызывается до `parseSchemaCells` и reconcile. При `BadRequestException` (invalid payload) или DB error slot `(machineId, messageId)` остаётся в `machine_ws_message_dedup`. Повтор **того же** messageId после error → `{ deduplicated: true }` без повторной обработки. Architecture §Dedup явно ставит dedup «перед apply» — для MVP OK, если Android всегда генерирует новый messageId после error; иначе — release dedup в catch или dedup после успешного apply. |
| M-2 | DB integration suites skip без `DATABASE_URL` | `machines-ws-cells.spec.ts`, `machine-cells.spec.ts`, `ws.spec.ts` — `describe.skip`. Unit/mock покрытие хорошее; полный WS matrix только в CI/local с PostgreSQL. Зафиксировать в CI gate. |
| M-3 | Snapshot push: return value игнорируется | `MachinesWsPushAdapter.sendCellsSnapshot` не проверяет `sendToMachine === false` (race disconnect). Fire-and-forget допустим для MVP; offline PATCH уже персистит — snapshot при следующем reconnect (C-3). |
| M-4 | Duplicate cellNumber/uuid во входящем report | WS handler не валидирует дубликаты в массиве `cells` / `updates` (carry-over L-2 из task-04). Reconcile может вести себя непредсказуемо на malformed batch — желательно в task-11 contract validation или отдельный hardening. |

---

### Low / Info

| ID | Topic | Detail |
|----|-------|--------|
| L-1 | `correlationId` только при известном messageId | `INVALID_JSON` / `INVALID_ENVELOPE` (нет type/messageId) — error без correlationId L190–196. Для cell uplink validation — OK per task. |
| L-2 | `supportedMessageTypes` включает downlink/meta types | `hello`, `ack`, `error` в массиве L102–111 — документирует полный протокол, не только uplink; Android должен фильтровать при отправке. |
| L-3 | Register REST vs WS hello | `MachinesService.buildRegisterResponse` отдаёт `protocolVersion: 2` без `supportedMessageTypes` — канонический список только в WS hello. Достаточно для MVP. |
| L-4 | Handler vs architecture sketch | Architecture §Module dependency упоминает forwardRef к reconcile/volume/content по отдельности; реализация свела в один `MachineCellsWsHandler` — чище, compliant. |
| L-5 | PATCH snapshot guard | `pushSnapshotIfOnline` требует ONLINE **и** `hasActiveConnection` — строже формулировки task («if ONLINE»); защита от stale status в БД. |
| L-6 | Dedup TTL cleanup | `ExpiredRecordsCleanupService` для `machine_ws_message_dedup` — out of scope task-05; table растёт (noted in task-04 L-6). |

---

## Тесты (сверка с test report)

| Suite | Cases | Result | Замечание |
|-------|-------|--------|-----------|
| `machine-cells-ws.handler.spec.ts` | 6 | PASS | schema/volume/content/dedup/snapshot push/invalid payload |
| `machines-ws-registry-send.spec.ts` | 2 | PASS | sendToMachine active/offline |
| `app-module-bootstrap.spec.ts` | 1 | PASS | forwardRef compile |
| `machines-ws-cells.spec.ts` | 8+ | PASS* | *skip без DATABASE_URL |
| `machine-cells.spec.ts` | incl. machine JWT PATCH | PASS* | *skip без DATABASE_URL |
| `ws.spec.ts` | hello v2 + heartbeat | PASS* | *skip без DATABASE_URL |
| `npm test` / `npm run build` / lint | — | PASS | per test report |

---

## Changed files (оценка)

| File | Оценка |
|------|--------|
| `common/crypto.util.ts` | OK — `WS_PROTOCOL_VERSION=2`, `WS_SUPPORTED_MESSAGE_TYPES`, `WsEnvelope` |
| `machines/machines-ws.service.ts` | OK — hello v2, cell dispatch, error+correlationId, v1 heartbeat |
| `machines/machines-ws.registry.ts` | OK — public `sendToMachine`, `hasActiveConnection` |
| `machines/machines.module.ts` | OK — forwardRef import, export registry |
| `machine-cells/machine-cells.module.ts` | OK — forwardRef, PushAdapter, export handler+facade |
| `machine-cells/machine-cells-ws.handler.ts` | OK — dedup chain, C-3 push; ⚠️ M-1 dedup-before-validation |
| `machine-cells/machine-ws-push.facade.ts` | OK — NoOp удалён, adapter на registry |
| `machine-cells/machine-cells-rest.service.ts` | OK — PATCH snapshot side-effect |

---

## Contract freeze readiness (task-11 / Android task-09)

| Item | Status |
|------|--------|
| Envelope types (uplink/downlink) | ✅ Frozen in code + architecture table |
| Hello v2 fields | ✅ |
| Ack payloads | ✅ |
| C-3 handshake fields | ✅ |
| Error `INVALID_PAYLOAD` + correlationId | ✅ |
| Golden schemaHash cross-platform | ⚠️ Carry-over M-1 from task-04 — не блокер WS transport |

---

## Рекомендации (post-merge, optional)

1. **M-1:** при error после `tryAcquire` — delete dedup row или перенести acquire после успешной валидации payload (с осторожностью к double-apply).
2. **M-4:** WS-level validation duplicate uuid/cellNumber в одном batch.
3. Убедиться, что CI всегда задаёт `DATABASE_URL` для WS integration suites.
4. В `docs/contracts/machine-cells-inventory.md` (task-11) зафиксировать порядок downlink snapshot vs ack и политику messageId retry после error.

---

## Итог для оркестратора

```json
{
  "reviewReportFile": "docs/agents/machine-cells-inventory/tasks/task-05-review.md",
  "hasCriticalIssues": false,
  "commentsSummary": "WS v2 cells wiring APPROVE: protocolVersion=2 + supportedMessageTypes в hello, dispatch schema/volume/content через MachineCellsWsHandler с dedup, C-3 snapshot push после schema reconcile и PATCH ONLINE через MachinesWsRegistry.sendToMachine + MachinesWsPushAdapter, forwardRef Machines↔MachineCells без bootstrap crash. Тесты PASS (integration skip без DATABASE_URL). Некритично: dedup slot сгорает при validation error на том же messageId; игнор sendToMachine=false; нет batch duplicate validation."
}
```
