# task-03 — code review (REST GET/PATCH machine cells)

**Reviewer:** code-reviewer-complex  
**Date:** 2026-07-19  
**Repo:** `viwa-telemetry`  
**Task:** `docs/agents/machine-cells-inventory/tasks/task-03.md`  
**Test report:** `task-03-test-report.md`  
**Architecture:** `architecture.md` § REST cells, OQ-3, OQ-8, error codes

## Verdict

**APPROVE** — REST GET/PATCH cells реализован по acceptance task-03. Критических дефектов нет (`hasCriticalIssues: false`).

`npm test` / `npm run build` (root) — exit 0 по test report. Integration `machine-cells.spec.ts` (7 кейсов) написан, но **SKIP** без `DATABASE_URL`; перед prod sign-off прогнать с Postgres.

---

## Acceptance criteria (task-03 / TZ #10–#12)

| # | Критерий | Статус | Доказательство |
|---|----------|--------|----------------|
| 1 | `MachineCellsModule` + controller + REST read/update | ✅ | `machine-cells.module.ts`, `MachineCellsController`, `MachineCellsRestService` |
| 2 | `GET /api/v1/machines/:id/cells` → `{ schemaHash, contentRevision, updatedAt, items }` | ✅ | `getMachineCells()`; denormalized `productName` / `tasteMediaKey` через `include: { product: true }` + `mapCellToDto` |
| 3 | GET: только active cells | ✅ | `where: { machineId, isActive: true }`, `orderBy: cellNumber asc` |
| 4 | GET empty machine → `items: []`, `schemaHash: null` | ✅ | integration spec #1 (SKIP без DB) |
| 5 | `PATCH` body `{ cells: [{ uuid, productUuid?, dosage1Price?, dosage2Price? }] }` | ✅ | `PatchMachineCellsDto` / `PatchMachineCellItemDto` |
| 6 | PATCH `volume` → 400 `VOLUME_READ_ONLY` | ✅ | `RejectVolumePatchInterceptor` + `badRequestApiError`; spec #4 |
| 7 | PATCH unknown uuid → 404 `CELL_NOT_FOUND` | ✅ | `notFoundApiError('CELL_NOT_FOUND', …)` в transaction; spec #5 |
| 8 | PATCH: `contentSource=DASHBOARD`, `cellsContentRevision++` | ✅ | `ContentSource.DASHBOARD` на каждую ячейку в batch; `increment: 1` один раз на PATCH; spec #3 + DB assert |
| 9 | AuthZ GET: ACTIVE (MASTER/ADMIN/OPERATOR/VIEWER) | ✅ | `@Roles(...ACTIVE_ROLES)` на GET |
| 10 | AuthZ PATCH: OPERATOR+; VIEWER → 403 | ✅ | `MUTATION_ROLES` без VIEWER; spec #6 |
| 11 | Machine JWT на REST → 403 | ✅ | `RejectMachineJwtGuard` на контроллере; spec #7 (PATCH) |
| 12 | Structural fields на dashboard PATCH read-only (OQ-3) | ✅ | DTO whitelist + global `forbidNonWhitelisted: true` → 400 на лишние поля; `volume` — отдельный код через interceptor |
| 13 | **Без** WS push в этом task | ✅ | `MachineCellsRestService` не inject/push; `NoOpMachineWsPushFacade` в module (task-05) |
| 14 | `MachineCellsModule` в `AppModule`, без цикла с `MachinesModule` | ✅ | `app.module.ts` import; `MachinesModule` не импортирует `MachineCellsModule` |

---

## Фокусная проверка (по запросу ревью)

### AuthZ

- Guard chain на контроллере: `RejectMachineJwtGuard` → `SessionAuthGuard` → `ActiveUserGuard` → `RolesGuard` — совпадает с `ProductsController`.
- Machine JWT: валидный bearer → 403 до session auth; невалидный bearer пропускается (корректная семантика guard).
- VIEWER: GET разрешён, PATCH запрещён ролями — соответствует architecture § auth matrix.

### `VOLUME_READ_ONLY`

- Interceptor на `@Patch()` проверяет сырое `body.cells[].volume` **до** `ValidationPipe` (Nest lifecycle: interceptors → pipes) — гарантирует structured `{ message: { code: 'VOLUME_READ_ONLY' } }`, а не generic `forbidNonWhitelisted`.
- `blockVolume` / `sosVolume` / `maxVolume` в DTO не объявлены → отклоняются ValidationPipe (не код `VOLUME_READ_ONLY`) — допустимо для OQ-3; в ТЗ явный код требовался только для `volume`.

### `CELL_NOT_FOUND`

- Поиск ячейки scoped: `{ id: update.uuid, machineId, isActive: true }` — чужой uuid / inactive → 404 с кодом `CELL_NOT_FOUND`.
- Ошибка бросается внутри `$transaction` → откат транзакции, revision не инкрементируется.

### `contentSource=DASHBOARD` + revision bump

- На каждую ячейку в PATCH batch всегда пишется `contentSource: DASHBOARD`, даже если переданы только `uuid` без полей — согласовано с OQ-8 («dashboard wins»).
- `cellsContentRevision` инкрементируется **один раз** на успешный PATCH (не per-cell).
- Ответ PATCH — полный snapshot через `getMachineCells()` (удобно для web polling).

### No WS push

- В REST-слое нет вызовов `MachineCellsSnapshotService.push*`, `MACHINE_WS_PUSH_FACADE`, `MachinesWsService`.
- Module по-прежнему регистрирует `NoOpMachineWsPushFacade` для WS-сервисов task-04 — не нарушает scope task-03.

### Module + AppModule

- `MachineCellsModule` расширен (controller + `MachineCellsRestService`), apply/snapshot services без изменений контракта task-03.
- Import `MachinesModule` — для `MachineJwtService` / `RejectMachineJwtGuard` (тот же паттерн, что `ProductsModule`); forwardRef не нужен.

---

## Архитектура и код

### Сильные стороны

- **Слои:** controller → `MachineCellsRestService` → Prisma; mapper вынесен; DTO с validation + Swagger.
- **Транзакция PATCH:** validate products → per-cell update → single revision bump → consistent read.
- **Denormalization (C-1):** LEFT JOIN через Prisma `include: { product: true }`; uplink-поля не принимаются.
- **Ошибки:** `VOLUME_READ_ONLY`, `CELL_NOT_FOUND` через `api-errors.util` → `{ message: { code, message } }`.
- **Product validation:** batch `findMany` по unique ids до transaction — без N+1.
- **Disconnect product:** `productUuid: null` + `@IsOptional()` корректно сбрасывает FK.

### Замечания (non-blocking)

| Severity | Topic | Detail |
|----------|-------|--------|
| Low | Integration без DB | Все 7 кейсов `describeIfDb` — SKIP локально; re-run с `DATABASE_URL` перед merge в prod path |
| Low | Нет unit-тестов REST-слоя | Нет spec на `MachineCellsRestService`, `mapCellToDto`, `RejectVolumePatchInterceptor`; компенсируется integration при наличии DB |
| Low | Machine JWT только на PATCH | Guard на классе покрывает GET, но integration тестирует только PATCH bearer — достаточно для merge, GET можно добавить позже |
| Low | Product not found | `NotFoundException('Product not found')` без structured `{ code }` — как в `products.service.ts`; в task-03 код не требовался |
| Low | Machine not found | GET/PATCH → plain `NotFoundException('Machine not found')` без code — вне test-кейсов task-03 |
| Info | Test report «whitelist strip» | При `forbidNonWhitelisted: true` structural fields **отклоняются** (400), а не молча strip — формулировка в test report неточная, поведение корректное для OQ-3 |
| Info | Duplicate uuid в одном PATCH | Два одинаковых uuid обновят ячейку дважды в одной транзакции; revision +1 — edge case, MVP OK |
| Info | `RejectVolumePatchInterceptor` не в providers | Класс без DI — `@UseInterceptors` достаточно |

Критических (security, data loss, неверный authZ, нарушение REST-контракта, лишний WS push) замечаний **нет**.

---

## Файлы

| File | Оценка |
|------|--------|
| `machine-cells.controller.ts` | OK — routes, roles, guards, interceptor на PATCH |
| `machine-cells-rest.service.ts` | OK — GET/PATCH, transaction, DASHBOARD, revision, validation |
| `dto/machine-cells.dto.ts` | OK — CellDto, patch DTO, `@ArrayMinSize(1)` |
| `machine-cells.mapper.ts` | OK — denormalized product fields, ISO timestamps |
| `reject-volume-patch.interceptor.ts` | OK — `VOLUME_READ_ONLY` на raw body |
| `machine-cells.module.ts` | OK — controller + REST service; NoOp WS facade |
| `app.module.ts` | OK — `MachineCellsModule` imported |
| `common/api-errors.util.ts` | OK — `notFoundApiError` используется для `CELL_NOT_FOUND` |
| `test/machine-cells.spec.ts` | OK (design) — 7 acceptance cases; SKIP без DB |

---

## Тесты (сверка с test report)

| Suite | Result | task-03 relevance |
|-------|--------|-------------------|
| `machine-cells.spec.ts` (7 cases) | SKIP без `DATABASE_URL` | ⚠️ re-run перед prod sign-off |
| Root `npm test` | PASS (exit 0) | ✅ |
| Root `npm run build` | PASS (exit 0) | ✅ |

**Рекомендация:** прогнать `machine-cells.spec.ts` в CI/локально с Postgres (`DATABASE_URL`).

---

## WS / task-05 boundary

PATCH REST не триггерит snapshot push — соответствует task-03 («push в task-05»). Architecture § «Push snapshot если ONLINE» относится к полному M2/M3 pipeline, не к scope этого task.

---

## Итог для оркестратора

```json
{
  "reviewReportFile": "docs/agents/machine-cells-inventory/tasks/task-03-review.md",
  "hasCriticalIssues": false,
  "commentsSummary": "REST GET/PATCH cells соответствует task-03: denormalized GET active cells, PATCH product/prices + contentSource=DASHBOARD + cellsContentRevision++, VOLUME_READ_ONLY и CELL_NOT_FOUND с structured codes, authZ ACTIVE/OPERATOR+/VIEWER 403/machine JWT 403, без WS push. Module в AppModule без цикла. Integration 7/7 написаны но SKIP без DATABASE_URL; unit-слой REST не покрыт — некритично. Test report неточно описывает structural fields как strip (фактически forbidNonWhitelisted reject)."
}
```
