# task-11 — code review (contract documentation)

**Reviewer:** code-reviewer-complex (docs)  
**Date:** 2026-07-19  
**Repos:** `viwa-telemetry` (canonical artifact), `viwa-android` (links)  
**Task:** `docs/agents/machine-cells-inventory/tasks/task-11.md`  
**Test report:** `task-11-test-report.md`  
**Artifact:** `viwa-telemetry/docs/contracts/machine-cells-inventory.md`  
**Cross-check:** task-02/03/05 controllers & WS, `architecture.md`, `FEATURE_MACHINE_CELLS_INVENTORY.md`, TZ #32

## Verdict

**APPROVE** — contract doc закрывает M7 (часть 1) и TZ #32: канон WS v2 + REST согласован с реализацией task-02/03/05, C-1…C-3/C-5, schemaHash, AuthZ и 14 `tasteMediaKey` зафиксированы. Критических расхождений нет. Известный info-gap `INVALID_THRESHOLDS` корректно помечен как contract-reserved.

---

## Focus check (checklist ревью)

| # | Проверка | Статус | Доказательство |
|---|----------|--------|----------------|
| 1 | Все task-05 uplink types: schema + JSON example | ✅ | `cells.schema.report`, `cells.volume.report`, `cells.content.report` — payload tables + examples L122–255 |
| 2 | Все task-05 downlink types | ✅ | `cells.snapshot` L261–303; legacy `cells.volume.patch` L305–307 |
| 3 | Baseline envelope + hello/ack/error | ✅ | Envelope L21–41; hello v2 L46–80; ack/error L82–114 |
| 4 | Hello v2 vs v1 delta + link baseline | ✅ | Table L47–55; `./registration-machine-jwt.md` exists |
| 5 | REST paths = controllers (task-02/03) | ✅ | `ProductsController` `@Controller('api/v1/products')`; `MachineCellsController` `@Controller('api/v1/machines/:machineId/cells')` |
| 6 | REST error codes | ✅ | `INVALID_TASTE`, `VOLUME_READ_ONLY`, `CELL_NOT_FOUND` emitted; `INVALID_THRESHOLDS` reserved L435 |
| 7 | WS error codes | ✅ | `INVALID_JSON`, `INVALID_ENVELOPE`, `INVALID_PAYLOAD`, `UNKNOWN_TYPE` — matches `MachinesWsService` |
| 8 | C-1 denormalization | ✅ | Table L311–318; uplink forbids `productName`/`tasteMediaKey`; REST/snapshot JOIN |
| 9 | C-2 `products[]` + lazy refresh | ✅ | L323–328; matches `buildSnapshot` `orderBy: name asc` + no push on products CRUD |
| 10 | C-3 reconnect handshake + push rules | ✅ | Fields L334–338; `shouldPushSnapshotAfterSchemaReport` L340–346 ≡ `machine-cells-snapshot.service.ts` L17–35 |
| 11 | C-5 `contentSource` algorithm | ✅ | Dashboard PATCH L354–363; content report L365–376 ≡ `machine-cells-rest.service.ts` + `cell-content-apply.service.ts` |
| 12 | schemaHash algorithm | ✅ | Key order `cellNumber`, `maxVolume`, `uuid` L388–391 ≡ `schema-hash.util.ts` L15–18 |
| 13 | AuthZ matrix | ✅ | Session roles + `RejectMachineJwtGuard`; machine JWT WS-only uplink L446–453 |
| 14 | 14 `tasteMediaKey` | ✅ | Table L461–476 ≡ `TASTE_MEDIA_KEYS` + `TASTE_MEDIA_KEY_LABELS_RU` + Android `TasteMediaKeyCatalog` |
| 15 | Fixtures reference | ✅ | 4 files in `apps/api/test/fixtures/cells/` |
| 16 | Android doc links | ✅ | `SIMPLE_TELEMETRY_MVP_ANDROID.md` §Related contracts; `FEATURE_MACHINE_CELLS_INVENTORY.md` §10 |
| 17 | Relative links valid | ✅ | `../../viwa-telemetry/docs/contracts/…` from Android docs resolves; contract → fixtures & `schema-hash.util.ts` |

---

## Cross-check: WS types vs `WS_SUPPORTED_MESSAGE_TYPES`

| Type | Contract | `crypto.util.ts` | Handler |
|------|----------|------------------|---------|
| `hello` | ✅ | ✅ | baseline |
| `heartbeat` | ✅ (baseline) | ✅ | baseline |
| `ack` | ✅ | ✅ | baseline |
| `error` | ✅ | ✅ | baseline |
| `cells.schema.report` | ✅ | ✅ | `handleSchemaReport` |
| `cells.volume.report` | ✅ | ✅ | `handleVolumeReport` |
| `cells.content.report` | ✅ | ✅ | `handleContentReport` |
| `cells.snapshot` | ✅ | ✅ | `sendCellsSnapshot` |

Ack payloads: schema `{ ok, schemaHash, created, updated, deactivated }`; volume/content `{ ok, applied }`; dedup `{ ok, deduplicated: true }` — совпадает с `MachineCellsWsHandler`.

---

## Cross-check: REST vs implementation

| Contract | Implementation | Match |
|----------|----------------|-------|
| GET/PATCH cells response shape | `MachineCellsRestService.getMachineCells` | ✅ |
| PATCH → `contentSource=DASHBOARD`, revision++ | `patchMachineCells` transaction L82–109 | ✅ |
| PATCH ONLINE → snapshot push | `pushSnapshotIfOnline` L117–129 | ✅ |
| Products CRUD + tastes | `ProductsController` + `listTasteMediaKeysWithLabelsRu()` | ✅ |
| Machine JWT REST → 403 | `RejectMachineJwtGuard` on both controllers | ✅ |

---

## Acceptance (task-11 / TZ #32)

| Критерий | Статус |
|----------|--------|
| Contract doc published | ✅ `viwa-telemetry/docs/contracts/machine-cells-inventory.md` |
| Согласован с brief §4 / architecture C-1…C-5 | ✅ |
| Ссылки из Android docs | ✅ |
| Peer review / links valid | ✅ (this review) |
| No application code changes | ✅ docs only |

---

## Findings

### Critical

_Нет._

---

### Medium (non-blocking)

| ID | Topic | Detail |
|----|-------|--------|
| M-1 | Порядок snapshot vs ack на wire | Contract L189, L346: «push **after** schema ack» читается как «клиент сначала получает ack». Реализация (`MachineCellsWsHandler` L102–113): snapshot **отправляется до** return ack — на wire snapshot может прийти **раньше** ack. Task-05 review рекомендовал явно зафиксировать оба порядка в contract (carry-over не закрыт). Architecture §Reconnect (L247) та же двусмысленность. **Не блокер freeze**, но Android/client должны быть готовы к snapshot-before-ack. |
| M-2 | Retry `messageId` после WS `error` | Task-05 рекомендация (dedup + validation error) не отражена в contract. Для MVP dedup row может «сгореть» при `INVALID_PAYLOAD` на том же `messageId` — out of scope task-11, но стоит одной строкой в §Dedup или Known gaps. |

---

### Low / Info

| ID | Topic | Detail |
|----|-------|--------|
| G-1 | `INVALID_THRESHOLDS` | Зарезервирован в contract + web `MachineCellErrorCode`; API не эмитит — **info**, не doc blocker (согласовано с пользователем). |
| G-2 | Register `protocolVersion: 1` vs WS hello v2 | Явно документировано L51–52 — **info**. |
| L-1 | Related documents table | L498–499: пути к Android docs без markdown-ссылок (в отличие от baseline link) — косметика. |
| L-2 | REST path notation | Narrative иногда без префикса `/api/v1` (L267 `PATCH /machines/:id/cells`); таблица REST L405+ — с префиксом. Не противоречие, но можно унифицировать в follow-up. |
| L-3 | Uplink optional `schemaHash` | Contract L131: client field informational; server recomputes — согласовано с handler (не читает client `schemaHash` для reconcile). Android carry-over M-3 (не вычисляет локально) — OK для MVP. |

---

## Test report validation

`task-11-test-report.md`: checklist 1–15 **PASS** — подтверждено независимой сверкой с кодом. Gaps G-1/G-2 корректно классифицированы как info.

---

## Рекомендации (post-approve, optional)

1. Добавить в §C-3 или §`cells.schema.report` явную фразу: «Server may send `cells.snapshot` **before** uplink ack on the wire; client must handle either order.»
2. Одна строка в §Dedup про повтор uplink с тем же `messageId` после validation `error` (ссылка на task-05 M-1).
3. При первом REST/WS validation thresholds — включить `INVALID_THRESHOLDS` в таблицу endpoint (сейчас `*` contract).

---

## Итог для оркестратора

```json
{
  "reviewReportFile": "docs/agents/machine-cells-inventory/tasks/task-11-review.md",
  "hasCriticalIssues": false,
  "commentsSummary": "Contract doc APPROVE (TZ #32 / M7): WS v2 uplink/downlink с JSON examples, REST paths и error codes совпадают с task-02/03/05; C-1/C-2/C-3/C-5, schemaHash, AuthZ и 14 tasteMediaKey верны; ссылки из Android docs и fixtures валидны. INVALID_THRESHOLDS корректно зарезервирован (API не эмитит — info). Некритично: не зафиксирован wire-order snapshot-before-ack и retry messageId после WS error (carry-over из task-05 review)."
}
```
