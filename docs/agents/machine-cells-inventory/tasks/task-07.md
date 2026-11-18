# task-07 — Web MachineDetail cells section

**Repo:** `viwa-telemetry` (apps/web)  
**Зависимости:** task-03, task-06

## Связь с этапом

M5 — Web MachineDetail «Ячейки».

## Что сделать

- **`MachineCellsSection`** component (embedded in `MachineDetailPage` or separate file).
- Load `GET /machines/:id/cells` + `GET /products` for product select.
- Table columns: №, product (select name+taste), volume **read-only**, maxVolume, block, sos, dosage1/2, status indicators.
- **`money.ts`:** `kopecksToRublesDisplay`, `rublesInputToKopecks` for dosage prices.
- Save → `PATCH /machines/:id/cells` bulk dirty rows only.
- Polling 15–30 s refetch for volume eventual consistency.
- Status colors: volume ≤ blockVolume → stop/red; ≤ sosVolume → warning/yellow; else normal.
- VIEWER: read-only, no Save button.
- Handle `VOLUME_READ_ONLY` if API returns (no volume inputs in UI).
- `canMutateCells` role helper.

## Точки изменения

- `viwa-telemetry/apps/web/src/components/MachineCellsSection.tsx` (новый)
- `viwa-telemetry/apps/web/src/pages/MachineDetailPage.tsx`
- `viwa-telemetry/apps/web/src/api/client.ts` — `machineCellsApi`
- `viwa-telemetry/apps/web/src/utils/money.ts`
- `viwa-telemetry/apps/web/src/auth/roles.ts`

## Тест-кейсы

Manual/smoke (TZ WEB-3, WEB-5, WEB-6, WEB-7):
1. After schema report (integration/mock): N rows, empty product, volume=0.
2. Volume updates via polling after machine volume report.
3. PATCH product/prices → save success; no volume edit in UI.
4. Threshold indicators correct for block/sos.
5. Product select lists all products from catalog.
6. VIEWER read-only.

## Критерии приёмки (TZ)

#19, #20; UC-6; WEB-3..WEB-7.

## Verification

`npm run lint`, `npm run build`.

## Note

Snapshot delivery to device verified in task-12 E2E; this task works against REST primarily.
