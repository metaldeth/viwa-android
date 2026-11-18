# task-02 — code review (Products REST CRUD + tastes)

**Reviewer:** code-reviewer-complex  
**Date:** 2026-07-19  
**Repo:** `viwa-telemetry`  
**Task:** `docs/agents/machine-cells-inventory/tasks/task-02.md`  
**Test report:** `task-02-test-report.md`

## Verdict

**APPROVE** — реализация соответствует acceptance task-02. Критических дефектов нет.

Unit-тесты по scope task-02 проходят (11/11). Integration `products.spec.ts` и полный `npm test` / `build` не блокируют ревью: DB-тесты пропущены без `DATABASE_URL`; падения из WIP task-04 вне scope task-02.

---

## Acceptance criteria (TZ)

| # | Критерий | Статус | Доказательство |
|---|----------|--------|----------------|
| 1 | `ProductsModule` в `AppModule` | ✅ | `app.module.ts` импортирует `ProductsModule` |
| 2 | `GET /api/v1/products` → `{ items: ProductDto[] }`, ACTIVE роли | ✅ | `ProductsController.list()`, роли MASTER/ADMIN/OPERATOR/VIEWER |
| 3 | `POST /api/v1/products` → 201, `{ name, tasteMediaKey }`, OPERATOR+ | ✅ | `@Post()`, `MUTATION_ROLES`, `reply.status(201)` |
| 4 | `PATCH /api/v1/products/:id` partial, OPERATOR+ | ✅ | `UpdateProductDto`, optional fields, `NotFoundException` |
| 5 | `DELETE /api/v1/products/:id` → 204, SetNull `cells.productId`, затем delete | ✅ | `$transaction`: `machineCell.updateMany` + `product.delete` |
| 6 | `GET /api/v1/products/tastes` → 14 `{ mediaKey, nameRu }` | ✅ | `TASTE_MEDIA_KEYS` (14), labels совпадают с FEATURE §3.1.4 |
| 7 | Невалидный `tasteMediaKey` → `400 INVALID_TASTE` | ✅ | `badRequestApiError('INVALID_TASTE', …)`; unit + integration spec |
| 8 | VIEWER POST/PATCH/DELETE → 403 | ✅ | `MUTATION_ROLES` без VIEWER + `RolesGuard`; integration spec |
| 9 | Machine JWT на products REST → 403 | ✅ | `RejectMachineJwtGuard` на классе контроллера; unit + integration |
| 10 | MVP C-2: после CRUD **нет** WS snapshot push | ✅ | В `products/**` нет импортов WS/Gateway/notifier |

---

## Архитектура и код

### Сильные стороны

- **Слои:** controller → service → Prisma; DTO с class-validator и Swagger.
- **Allowlist:** единый источник `taste-media-keys.ts`, тип `TasteMediaKey`, RU-лейблы синхронны с `FEATURE_MACHINE_CELLS_INVENTORY.md` §3.1.4.
- **DELETE:** транзакция явно обнуляет `productId` до удаления — соответствует ТЗ и дублирует Prisma `onDelete: SetNull` на FK.
- **Machine JWT:** guard на уровне контроллера блокирует все маршруты `/products/*`; невалидный bearer пропускается к session auth — корректная семантика.
- **Guards order:** `RejectMachineJwtGuard` → `SessionAuthGuard` → `ActiveUserGuard` → `RolesGuard`.
- **Routing:** `@Get('tastes')` объявлен до `@Patch(':id')` / `@Delete(':id')` — конфликта с `:id` нет.
- **Ошибки:** `INVALID_TASTE` через общий `badRequestApiError` → `{ message: { code, message } }`, как в остальном API.

### Замечания (non-blocking)

| Severity | Topic | Detail |
|----------|-------|--------|
| Low | PATCH `INVALID_TASTE` | `assertValidTasteMediaKey` вызывается при update, но unit-тест только на create; integration покрывает POST, не PATCH с bad key. Риск минимален — та же функция. |
| Low | Machine JWT на `/tastes` | Integration явно тестирует GET `/products`, не `/products/tastes`; guard на классе покрывает оба — достаточно для merge, тест можно добавить позже. |
| Low | PATCH unit coverage | Нет unit-теста успешного `updateProduct` / reject invalid taste on patch — компенсируется integration при наличии DB. |
| Low | `name.trim()` | DTO `@IsNotEmpty()` не отсекает строку из пробелов до trim в service; в БД может попасть `""`. Вне acceptance task-02. |
| Low | Пустой PATCH body | `BadRequestException('At least one field must be provided')` без structured `{ code }` — допустимо, в ТЗ не требовалось. |
| Info | `RejectMachineJwtGuard` provider | Объявлен в `ProductsModule`, не в `AuthModule` — ок для task-02; при расширении на другие REST стоит вынести в общий модуль. |

Критических (security, data loss, неверный authZ, нарушение контракта API) замечаний **нет**.

---

## Файлы

| File | Оценка |
|------|--------|
| `src/products/products.module.ts` | OK — imports Auth + Machines (для `MachineJwtService`), exports service |
| `src/products/products.controller.ts` | OK — endpoints, roles, guards, 201/204 |
| `src/products/products.service.ts` | OK — CRUD, allowlist, transaction delete |
| `src/products/dto/products.dto.ts` | OK — validation + Swagger |
| `src/products/taste-media-keys.ts` | OK — 14 keys, RU labels |
| `src/auth/reject-machine-jwt.guard.ts` | OK — 403 on valid machine JWT, pass-through invalid |
| `src/common/api-errors.util.ts` | OK — без task-specific изменений; используется `badRequestApiError` |
| `src/app.module.ts` | OK — `ProductsModule` подключён |
| `test/products.service.spec.ts` | OK — list, INVALID_TASTE create, delete+SetNull, tastes×14 |
| `test/products.spec.ts` | OK (design) — полное покрытие acceptance; SKIPPED без DB |
| `test/reject-machine-jwt.guard.spec.ts` | OK — 3 cases |
| `test/taste-media-keys.spec.ts` | OK — allowlist + labels |

---

## Тесты (сверка с test report)

| Suite | Result | task-02 relevance |
|-------|--------|-------------------|
| `taste-media-keys.spec.ts` | PASS (3) | ✅ |
| `products.service.spec.ts` | PASS (5) | ✅ |
| `reject-machine-jwt.guard.spec.ts` | PASS (3) | ✅ |
| `products.spec.ts` | SKIPPED | ⚠️ re-run with `DATABASE_URL` before production sign-off |
| Full `npm test` / `build` | FAIL (WIP task-04) | ➖ не относится к task-02 |

**Рекомендация перед merge в prod path:** прогнать `products.spec.ts` локально с `DATABASE_URL`.

---

## WS / C-2

Поиск по `products/**`: нет вызовов `MachinesWsService`, notifier, snapshot push. CRUD не триггерит WS — соответствует MVP C-2 (lazy refresh on reconnect).

---

## Итог для оркестратора

```json
{
  "reviewReportFile": "docs/agents/machine-cells-inventory/tasks/task-02-review.md",
  "hasCriticalIssues": false,
  "commentsSummary": "Products CRUD + tastes allowlist реализованы по ТЗ: endpoints, INVALID_TASTE, DELETE SetNull, authZ VIEWER/machine JWT, без WS push. Unit 11/11 PASS; integration SKIPPED без DATABASE_URL. Некритичные пробелы: PATCH invalid taste и machine JWT на /tastes не покрыты integration явно."
}
```
