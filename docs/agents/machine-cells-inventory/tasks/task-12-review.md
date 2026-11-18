# task-12 — code review (Integration E2E)

**Reviewer:** code-reviewer-complex  
**Date:** 2026-07-19  
**Repo (primary):** `viwa-telemetry`  
**Task:** `docs/agents/machine-cells-inventory/tasks/task-12.md`  
**Test report:** `task-12-test-report.md`  
**Artifact:** `viwa-telemetry/apps/api/test/machine-cells-inventory.e2e-spec.ts`  
**Contract:** `viwa-telemetry/docs/contracts/machine-cells-inventory.md`  
**Cross-check:** `machines-ws-cells.spec.ts`, `machine-cells.spec.ts`, TZ #31 / M7

## Verdict

**APPROVE** (с риском исполнения) — все 6 обязательных сценариев task-12 покрыты одним E2E-файлом; assertions согласованы с контрактом (hello v2, schema ack + `computeSchemaHash`, REST product/PATCH, `contentSource=DASHBOARD`, downlink `cells.snapshot` с `products[]` и denormalized полями, volume → GET, dedup, reconnect C-3, AuthZ 403). Skip-guard `describeIfDb` соответствует остальным integration-спекам. **Риск:** 6/6 кейсов не выполнялись на живой PostgreSQL в agent-окружении — runtime не подтверждён.

---

## Coverage vs 6 required scenarios

| # | Сценарий task-12 | Статус | Тест / строки | Контракт / architecture |
|---|------------------|--------|---------------|-------------------------|
| 1 | Full happy path TZ #31 | ✅ | `full happy path…` L168–301 | register→0 cells L172–173; hello v2 L177–186; schema ack + `schemaHash` L188–206; POST product L220–224; PATCH→`contentSource` DASHBOARD L227–254; snapshot `products[]`+denorm L256–276; volume→GET L278–298 |
| 2 | Register → 0 cells | ✅ | `register creates zero…` L303–320 | Contract L16–17; GET `{ schemaHash: null, contentRevision: 0, items: [] }` L315–318 |
| 3 | Reconcile preserve | ✅ | `reconcile preserve…` L322–385 | Second schema `created:0, updated:2` L371; volume/product/prices/`contentSource` preserved L376–382 — сильнее, чем WS-only spec (включает dashboard PATCH) |
| 4 | AuthZ machine JWT PATCH → 403 | ✅ | `forbids machine JWT…` L387–407 | Contract AuthZ L441, L452; `RejectMachineJwtGuard` path через live register+WS setup |
| 5 | Reconnect stale revision → snapshot | ✅ | `reconnect: stale clientContentRevision…` L409–444 | C-3 rule 1 L342; `cellsContentRevision: 10` vs client `5` L420–431; snapshot after ack L436–441 |
| 6 | Dedup messageId | ✅ | `duplicate messageId…` L446–472 | Contract dedup L120; ack `{ deduplicated: true }` L461; cell count L463–464; **`machineWsMessageDedup` row count = 1** L466–469 — усиление vs `machines-ws-cells.spec.ts` |

---

## Deliverables check

| Deliverable | task-12 | Факт | Статус |
|-------------|---------|------|--------|
| E2E integration file | `test/e2e/…e2e-spec.ts` или `.spec.ts` | `apps/api/test/machine-cells-inventory.e2e-spec.ts` | ✅ (путь без `e2e/` — см. L-1) |
| Jest picks up `.e2e-spec.ts` | да | `jest.config.js` `testRegex: '.*\\.(spec\|e2e-spec)\\.ts$'` | ✅ |
| Reuse fixtures | `schema-first-report.json` | import L9 | ✅ |
| Optional Android fixture test | optional | не реализован (test report) | ℹ️ non-blocker |
| Verification `npm test` | exit 0 | test report: 23 passed, 9 skipped suite-level | ✅ |

---

## Skip-guard & execution risk

| Аспект | Оценка |
|--------|--------|
| Guard pattern | ✅ `const describeIfDb = process.env.DATABASE_URL ? describe : describe.skip` — идентично `machines-ws-cells.spec.ts`, `machine-cells.spec.ts`, `ws.spec.ts` и др. |
| Agent env | 6 skipped — ожидаемо без `DATABASE_URL`; **не auto-reject** |
| CI/staging gap | ⚠️ Если CI тоже без DB — E2E never run; рекомендуется job с PostgreSQL + migrations (см. test report L44–48) |
| Timeout | `testTimeout: 30000` в jest + 5s WS helpers — достаточно для integration stand |

---

## Contract / architecture alignment (happy path detail)

| Step | E2E assertion | Contract |
|------|---------------|----------|
| Hello v2 | `protocolVersion: 2`, cell types in `supportedMessageTypes` | L46–80 |
| Schema ack | `schemaHash` = `computeSchemaHash(cells)`, counts | L177–187, schemaHash L380–396 |
| PATCH | `contentRevision: 1`, denorm in REST response | C-5 L354–363 |
| Snapshot | `contentRevision`, `products[]`, cell denorm fields | C-1 L311–318, C-2 L323–328 |
| Volume | `{ ok, applied: 1 }`; GET reflects `volume: 1200` | volume report L191–218; volume не в PATCH |
| Dedup | `{ deduplicated: true }`, no double cells | L120 |

---

## Overlap with existing specs

| Сценарий | E2E file | Уже в других spec |
|----------|----------|-------------------|
| Happy path REST+WS end-to-end | **уникален** (register→product POST→PATCH→snapshot→volume→GET) | Части разнесены по `machines-ws-cells` + `machine-cells` |
| Dedup | ✅ + dedup table count | `machines-ws-cells.spec.ts` L275–295 (без table assert) |
| Reconnect snapshot | ✅ | `machines-ws-cells.spec.ts` L298–333 (почти идентично) |
| AuthZ machine JWT PATCH | ✅ full flow | `machine-cells.spec.ts` L582–604 (REST-only seed) |
| Register 0 cells | ✅ + GET empty | Implicit в register flow elsewhere |
| Reconcile preserve | ✅ + dashboard content | `machines-ws-cells` L211–241 (только volume) |

**Вывод:** E2E оправдан как **сквозной TZ #31**; отдельные кейсы 3–6 частично дублируют существующие integration-тесты, но не противоречат контракту.

---

## Findings

### Critical

_Нет._

---

### Medium (non-blocking)

| ID | Topic | Detail |
|----|-------|--------|
| M-1 | Runtime не подтверждён | Все 6 кейсов SKIP без `DATABASE_URL`. Код и assertions выглядят корректно, но flakiness, migration drift, WS race (snapshot-before-ack, task-11 M-1) не проверены на живой DB. **Acceptance с риском.** |
| M-2 | Дублирование helpers/сценариев | `registerMachine`, `connectWs`, `sendAndWaitForAck`, `waitForDownlink` — копия `machines-ws-cells.spec.ts`; dedup/reconnect почти 1:1. Риск расхождения при рефакторинге. Рекомендация: shared test helper module (follow-up). |
| M-3 | `updateMany` без фильтра machineId | Reconnect test L420–422: `prisma.machine.updateMany({ data: { cellsContentRevision: 10 } })`. Безопасно при `resetDatabase` + одной машине; хрупко при расширении suite. Лучше `where: { id: machineId }`. |

---

### Low / Info

| ID | Topic | Detail |
|----|-------|--------|
| L-1 | Путь файла | Task L28 предлагает `test/e2e/…`; фактически `test/machine-cells-inventory.e2e-spec.ts` — convention проекта (`test/` root), не блокер. |
| L-2 | Reconnect snapshot assertions | L437–441: `products`/`cells` — `expect.any(Array)` без проверки denorm/content; для reconnect достаточно, happy path покрывает детали. |
| L-3 | Dedup `messageId` | `'dup-e2e-message-id'` не UUID; контракт рекомендует UUID (L38), handler не валидирует формат — согласовано с `machines-ws-cells` `'dup-msg'`. |
| L-4 | TZ #15 timing | Task упоминает snapshot ≤5s на staging; E2E не меряет latency, только 5s timeout — OK для automated integration, manual staging out of scope. |
| L-5 | Optional Android | `TelemetryCellsContractFixturesTest.kt` отложен; codec parity через `TelemetryCellsMessageCodecTest` — acceptable per task optional clause. |
| L-6 | `cells.content.report` uplink | Не в 6 сценариях task-12; покрыт в `machines-ws-cells.spec.ts` — OK. |

---

## Test report validation

`task-12-test-report.md`: таблица coverage vs acceptance **корректна**; skip reason и команды verification согласованы с кодом и jest config. Заявление «6 integration E2E cases» — верно.

---

## Рекомендации (post-approve)

1. Прогнать на PostgreSQL локально/CI: `$env:DATABASE_URL=…; npm run test -w @wiva/api -- machine-cells-inventory.e2e-spec.ts`.
2. В reconnect test заменить `updateMany` на `update({ where: { id: machineId } })`.
3. (Optional) Вынести WS+register helpers в `test/helpers/machine-e2e.ts` для DRY с `machines-ws-cells.spec.ts`.
4. (Optional) CI job с Postgres service для `.e2e-spec.ts` / `describeIfDb` suites.

---

## Итог для оркестратора

```json
{
  "reviewReportFile": "docs/agents/machine-cells-inventory/tasks/task-12-review.md",
  "hasCriticalIssues": false,
  "commentsSummary": "APPROVE с риском исполнения: 6/6 сценариев task-12 (TZ #31 happy path, register→0 cells, reconcile preserve, AuthZ 403, reconnect snapshot, dedup messageId) покрыты в machine-cells-inventory.e2e-spec.ts и согласованы с контрактом; skip-guard describeIfDb корректен. Некритично: тесты не гонялись на живой DB (6 SKIP); частичное дублирование machines-ws-cells.spec.ts; reconnect updateMany без machineId filter. Optional Android fixture deferred — non-blocker."
}
```
