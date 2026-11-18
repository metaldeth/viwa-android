# task-06 — Web ProductsPage + API client

**Repo:** `viwa-telemetry` (apps/web)  
**Зависимости:** task-02

## Связь с этапом

M4 — Web «База продуктов» CRUD.

## Что сделать

- API client: `productsApi` — list, create, update, delete, getTastes.
- Types: `ProductDto`, taste item, error codes `INVALID_TASTE`.
- **`src/utils/money.ts`** — не обязателен здесь, но подготовить если нужен для будущих цен (может быть в task-07); products page без цен.
- **`ProductsPage.tsx`:** таблица name + RU вкус; add/edit modal (name + select taste from GET tastes); delete with confirm.
- Routing: `/products` in `App.tsx`; nav item «База продуктов» in `AppShell` for ACTIVE roles.
- Role helpers: `canMutateProducts` in `src/auth/roles.ts`; VIEWER — hide Add/Edit/Delete.
- Error display for API failures.

## Точки изменения

- `viwa-telemetry/apps/web/src/api/client.ts` (+ types)
- `viwa-telemetry/apps/web/src/pages/ProductsPage.tsx` (новый)
- `viwa-telemetry/apps/web/src/App.tsx`
- `viwa-telemetry/apps/web/src/auth/roles.ts`
- `viwa-telemetry/apps/web/src/components/` — AppShell nav if separate

## Тест-кейсы

Manual/smoke (TZ WEB-1, WEB-2):
1. OPERATOR: full CRUD flow; refresh persists.
2. VIEWER: list visible, no mutation buttons.
3. Invalid taste via API error surfaced in UI.

## Критерии приёмки (TZ)

#18; UC-5; WEB-1, WEB-2.

## Verification

`npm run lint`, `npm run build`.
