# task-03 — REST GET/PATCH machine cells + contentSource

**Repo:** `viwa-telemetry`  
**Зависимости:** task-01, task-02

## Связь с этапом

M2 — REST cells GET/PATCH (без WS push в этом task).

## Что сделать

- Создать `MachineCellsModule` + `MachineCellsController` + сервисы read/update.
- `GET /api/v1/machines/:id/cells`:
  - Response: `{ schemaHash, contentRevision, updatedAt, items: CellDto[] }`.
  - `CellDto` — denormalized `productName`, `tasteMediaKey` (LEFT JOIN Product).
  - Только active cells по умолчанию (или все с `isActive` flag — как в architecture).
- `PATCH /api/v1/machines/:id/cells`:
  - Body: `{ cells: [{ uuid, productUuid?, dosage1Price?, dosage2Price? }] }`.
  - **Запрет** `volume` и structural fields в MVP → 400 `VOLUME_READ_ONLY` / ignore structural.
  - Per-cell: update product/prices; set `contentSource=DASHBOARD`; bump `Machine.cellsContentRevision++`.
  - Валидация uuid exists, product exists, thresholds если переданы (не MVP для web).
- AuthZ: ACTIVE GET; OPERATOR+ PATCH; VIEWER PATCH → 403; machine JWT → 403.
- **Без** WS push в этом task (push в task-05 через snapshot service).
- Import `MachineCellsModule` в `AppModule` (без цикла с MachinesModule пока минимальный import).

## Точки изменения

- `viwa-telemetry/apps/api/src/machine-cells/` (module, controller, dto, mapper)
- `viwa-telemetry/apps/api/src/app.module.ts`

## Тест-кейсы

1. GET empty machine (post-register, pre-schema) → empty items, schemaHash null.
2. GET after manual DB seed / future reconcile fixture → denormalized product fields.
3. PATCH productUuid + prices → updated, contentSource=DASHBOARD, contentRevision incremented.
4. PATCH with volume field → 400 `VOLUME_READ_ONLY`.
5. PATCH unknown uuid → 404 `CELL_NOT_FOUND`.
6. VIEWER PATCH → 403.
7. Machine JWT PATCH → 403.

## Критерии приёмки (TZ)

#10, #11, #12; OQ-3 web structural read-only; OQ-8 dashboard wins (contentSource set here).

## Verification

`npm test`.
