# task-06 — отчёт по тестам (Web ProductsPage)

> Сессия `machine-cells-inventory`. Критерий: ProductsPage CRUD + lint/build.

## Команды

```powershell
cd c:\viwa\viwa-telemetry\apps\web
npm run lint
npm run build
npm test
```

## Результат

| Проверка | Статус |
|----------|--------|
| `npm run lint` | exit 0 |
| `npm run build` (`tsc -b && vite build`) | exit 0 |
| `npm test` (vitest) | 9 files, 49 tests — all passed |

## Unit-тесты (ProductsPage)

| Тест | Сценарий |
|------|----------|
| `shows products table for VIEWER without mutation controls` | Таблица name + RU вкус; кнопки Add/Edit/Delete скрыты |
| `allows OPERATOR to create a product` | Диалог добавления, POST через `productsApi.create` |
| `surfaces INVALID_TASTE API error in the form dialog` | Ошибка `INVALID_TASTE` отображается в модалке |

## Изменённые файлы (web)

- `src/api/client.ts` — `productsApi`, парсинг `ApiError.code`
- `src/types/api.ts` — `ProductDto`, taste types, `ProductErrorCode`
- `src/auth/roles.ts` — `canMutateProducts`
- `src/pages/ProductsPage.tsx` — новая страница
- `src/App.tsx` — маршрут `/products`
- `src/components/Guards.tsx` — nav «База продуктов»
- `src/test/products-page.test.tsx` — unit-тесты

## Manual/smoke (не автоматизировано)

1. OPERATOR: полный CRUD, refresh сохраняет данные — требует запущенный API + БД.
2. VIEWER: список виден, мутации скрыты — покрыто unit-тестом.
3. Invalid taste via API — покрыто unit-тестом (`INVALID_TASTE`).

## Примечания

- `src/utils/money.ts` не добавлялся (опционально для task-07).
- Коммит не выполнялся по инструкции задачи.
