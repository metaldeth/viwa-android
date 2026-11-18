# task-06 — code review (Web ProductsPage + API client)

**Reviewer:** code-reviewer-complex  
**Date:** 2026-07-19  
**Repo:** `viwa-telemetry` (`apps/web`)  
**Task:** `docs/agents/machine-cells-inventory/tasks/task-06.md`  
**Test report:** `task-06-test-report.md`

## Verdict

**APPROVE** — реализация соответствует acceptance task-06 и контракту backend task-02. Критических дефектов нет.

`npm run lint`, `npm run build`, `npm test` — exit 0 (49 tests). ProductsPage: 3 unit-теста покрывают VIEWER read-only, create (OPERATOR), `INVALID_TASTE`. Полный CRUD smoke (WEB-1) — manual, не автоматизирован (зафиксировано в test report).

---

## Acceptance criteria (TZ)

| # | Критерий | Статус | Доказательство |
|---|----------|--------|----------------|
| 1 | `productsApi`: list, create, update, delete, getTastes | ✅ | `client.ts` → `/api/v1/products`, `/tastes`; типизированные DTO |
| 2 | Types: `ProductDto`, taste item, `ProductErrorCode` / `INVALID_TASTE` | ✅ | `types/api.ts` |
| 3 | `ProductsPage`: таблица name + RU вкус | ✅ | `tasteLabelByKey` из `getTastes`, fallback на `tasteMediaKey` |
| 4 | Add/edit modal: name + select вкуса из GET tastes | ✅ | `ProductFormDialog`, `productsApi.getTastes()` |
| 5 | Delete с confirm | ✅ | Dialog «Удалить продукт?», предупреждение про ячейки |
| 6 | Routing `/products` в `App.tsx` | ✅ | `Route path="/products" element={<ProductsPage />}` под `AuthGuard` + `AppShell` |
| 7 | Nav «База продуктов» для ACTIVE | ✅ | `Guards.tsx` — desktop `ShellNavLinks` + mobile Drawer; PENDING уходит в `/pending` |
| 8 | `canMutateProducts`; VIEWER — hide Add/Edit/Delete | ✅ | `roles.ts` (MASTER/ADMIN/OPERATOR); UI `canMutate` + unit-тест VIEWER |
| 9 | Error display для API failures | ✅ | Alerts для load errors; `formatApiError` + `ApiError.code` в form/delete feedback |
| 10 | `INVALID_TASTE` в UI | ✅ | RU-сообщение в модалке; unit-тест |
| 11 | Acceptance #18, UC-5, WEB-1, WEB-2 | ✅ | Отдельная страница, nav, CRUD UI для OPERATOR+; VIEWER read-only |
| 12 | Verification lint/build | ✅ | test report exit 0 |

---

## Сверка с backend (task-02)

| Аспект | Backend | Web | Статус |
|--------|---------|-----|--------|
| List/tastes roles | ACTIVE: MASTER, ADMIN, OPERATOR, VIEWER | Все ACTIVE видят страницу и список | ✅ |
| Mutation roles | MASTER, ADMIN, OPERATOR | `canMutateProducts` — те же роли | ✅ |
| Error shape | `{ message: { code, message } }` | `apiFetch` парсит `structured?.code` → `ApiError` | ✅ |
| `INVALID_TASTE` | 400 + code | `formatApiError` → фиксированный RU-текст | ✅ |
| DELETE side effect | SetNull cells | Текст в confirm-dialog | ✅ (UX hint) |

---

## Архитектура и код

### Сильные стороны

- **React Query:** раздельные queries `products` / `products/tastes`; invalidate после mutations.
- **RBAC UI-only:** скрытие мутаций для VIEWER; backend 403 остаётся второй линией — согласовано с WEB-2.
- **Паттерн страницы:** MUI Table + modals, `LoadingState`, feedback Alert — консистентно с остальным web.
- **A11y:** `aria-label` на таблице, кнопках Edit/Delete, dialog titles.
- **Remount формы при edit:** `key={formDialog?.product?.id ?? 'create'}` сбрасывает state при смене продукта / edit→create.

### Findings

#### Critical

_Нет._

#### Medium (non-blocking)

| ID | Topic | Detail |
|----|-------|--------|
| M-1 | Stale state в create-dialog | `ProductFormDialog` монтируется с `key='create'` постоянно. После «Отмена» → повторное «Добавить» поля name/taste не сбрасываются (`useState(initial)` только на mount). Edit и edit→create работают (key меняется). Fix: remount по счётчику открытий или `useEffect` при `open`. |
| M-2 | Неполное покрытие CRUD в тестах | Unit: create + VIEWER + INVALID_TASTE. Нет тестов update, delete confirm, success feedback, list/tastes load errors. WEB-1 steps 4–6 — manual only. |
| M-3 | `canMutateProducts` без unit-теста | Логика тривиальна и совпадает с backend `MUTATION_ROLES`, но helper не проверен отдельно (в отличие от guard-тестов). |

#### Low / Info

| ID | Topic | Detail |
|----|-------|--------|
| L-1 | `ProductErrorCode` | Тип объявлен, но `ApiError.code` — `string`; нет type-narrowing по union. Допустимо для task-06. |
| L-2 | Пустой справочник вкусов | «Добавить продукт» disabled при `tastes.length === 0` без поясняющего текста. |
| L-3 | Validation UX select | `FormControl error` без `helperText` для пустого вкуса (в отличие от TextField name). |
| L-4 | Delete error UX | `onError` delete закрывает dialog (`setDeleteTarget(null)`); повтор без re-open. |
| L-5 | Nav/routing tests | `/products` и пункт «База продуктов» не покрыты `auth-guards.test.tsx` (только machines/registration-keys). |
| L-6 | `money.ts` | Не добавлен — ok по task (опционально для task-07). |

---

## Файлы

| File | Оценка |
|------|--------|
| `src/api/client.ts` | OK — `productsApi` CRUD + tastes; `ApiError.code` из structured body |
| `src/types/api.ts` | OK — `ProductDto`, taste types, `ProductErrorCode` |
| `src/auth/roles.ts` | OK — `canMutateProducts` = OPERATOR+ |
| `src/pages/ProductsPage.tsx` | OK — table, modals, RBAC, errors; ⚠️ M-1 create reopen |
| `src/App.tsx` | OK — route `/products` |
| `src/components/Guards.tsx` | OK — nav desktop + drawer |
| `src/test/products-page.test.tsx` | OK — 3 сценария; ⚠️ M-2 gaps |

---

## Тесты (сверка с test report)

| Suite | Result | task-06 relevance |
|-------|--------|-------------------|
| `npm run lint` | PASS | ✅ |
| `npm run build` | PASS | ✅ |
| `npm test` (49 total) | PASS | ✅ |
| `products-page.test.tsx` (3) | PASS | ✅ VIEWER, create, INVALID_TASTE |
| Manual WEB-1 full CRUD | Not run | ⚠️ before prod sign-off with live API |

---

## Рекомендации (post-merge, optional)

1. Исправить M-1 (reset create form) — один PR, без изменения API.
2. Добавить unit-тесты: update, delete, reopen create после cancel.
3. Manual smoke WEB-1 на стенде с `DATABASE_URL` + OPERATOR session.

---

## Итог для оркестратора

```json
{
  "reviewReportFile": "docs/agents/machine-cells-inventory/tasks/task-06-review.md",
  "hasCriticalIssues": false,
  "commentsSummary": "ProductsPage + productsApi соответствуют task-06/TZ #18/WEB-1/WEB-2: CRUD UI для OPERATOR+, VIEWER read-only, nav /products, INVALID_TASTE в модалке, контракт с backend task-02. lint/build/test PASS. Некритично: stale state при повторном «Добавить», нет unit-тестов update/delete, WEB-1 full CRUD — manual."
}
```
