# task-02 — REST products CRUD + tastes allowlist

**Repo:** `viwa-telemetry`  
**Зависимости:** task-01

## Связь с этапом

M2 — REST products + tastes endpoint.

## Что сделать

- Создать `ProductsModule` + `ProductsController` + `ProductsService`.
- Endpoints (session cookie + guards):
  - `GET /api/v1/products` — `{ items: ProductDto[] }`, все ACTIVE роли.
  - `POST /api/v1/products` — `{ name, tasteMediaKey }`, OPERATOR+.
  - `PATCH /api/v1/products/:id` — partial update, OPERATOR+.
  - `DELETE /api/v1/products/:id` — SetNull `productId` на связанных cells, затем delete; 204, OPERATOR+.
  - `GET /api/v1/products/tastes` — `{ items: [{ mediaKey, nameRu }] }`, 14 keys.
- Валидация `tasteMediaKey` через allowlist → `400 INVALID_TASTE`.
- VIEWER / machine JWT на mutation → 403.
- Machine JWT на любой products REST → 403.
- **MVP C-2:** после products CRUD **не** push WS snapshot (lazy refresh on reconnect).
- Подключить `ProductsModule` в `AppModule`.

## Точки изменения

- `viwa-telemetry/apps/api/src/products/` (module, controller, service, dto)
- `viwa-telemetry/apps/api/src/app.module.ts`
- `viwa-telemetry/apps/api/src/auth/` — guards reuse (SessionAuthGuard, RolesGuard)

## Тест-кейсы

1. POST valid product → 201, fields persisted.
2. POST invalid tasteMediaKey → 400 `INVALID_TASTE`.
3. PATCH name + taste → updated fields.
4. DELETE product used in cell → cells.productId=null, product deleted.
5. GET tastes → 14 items with RU names.
6. VIEWER POST/PATCH/DELETE → 403.
7. Machine JWT bearer on POST → 403.

## Критерии приёмки (TZ)

UC-5a/b/c; acceptance #8, #9; authZ #12 (machine JWT REST).

## Verification

`npm test` — REST + authZ unit/integration.
