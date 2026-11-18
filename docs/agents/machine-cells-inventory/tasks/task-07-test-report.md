# task-07 — отчёт по тестам (Web MachineCellsSection)

> Сессия `machine-cells-inventory`. Критерий: MachineDetail «Ячейки» + lint/build.

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
| `npm run lint` | exit 0 (0 errors, 0 warnings после fix useMemo) |
| `npm run build` (`tsc -b && vite build`) | exit 0 |
| `npm test` (vitest) | 11 files, 57 tests — all passed |

## Unit-тесты

| Файл | Тест | Сценарий |
|------|------|----------|
| `money.test.ts` | kopecks/rubles conversion | WEB-4: формат и парсинг цен |
| `machine-cells-section.test.tsx` | table + status | WEB-3/WEB-6: таблица, volume read-only, block/sos индикаторы |
| `machine-cells-section.test.tsx` | VIEWER read-only | WEB-2/WEB-5: нет кнопки «Сохранить» |
| `machine-cells-section.test.tsx` | PATCH dirty rows | WEB-4/WEB-7: product select + цены → bulk PATCH |
| `machine-cells-section.test.tsx` | VOLUME_READ_ONLY | WEB-5: сообщение об ошибке API |
| `machine-cells-section.test.tsx` | helpers | пороги volume и diff payload |
| `machine-detail-page.test.tsx` | (regression) | секция ячеек не ломает существующие тесты |

## Manual/smoke (TZ WEB-3..WEB-7, не автоматизировано)

1. **WEB-3:** N строк после schema report, volume=0 — требует API + WS/mock.
2. **WEB-3 polling:** volume обновляется через refetch 20 с — требует running stack + volume report.
3. **WEB-4:** PATCH → snapshot на device ≤5 с — task-12 E2E / staging.
4. **WEB-5:** UI не содержит полей volume — покрыто отсутствием input + unit PATCH payload без volume.
5. **WEB-6:** stop/warning/normal — покрыто unit `getVolumeStatus` + Chip в таблице.
6. **WEB-7:** select из GET `/products` — покрыто unit save + manual с API.

## Изменённые файлы (web)

- `src/utils/money.ts` — `kopecksToRublesDisplay`, `rublesInputToKopecks`
- `src/types/api.ts` — `MachineCellDto`, `MachineCellsResponse`, patch types
- `src/api/client.ts` — `machineCellsApi.list` / `patch`
- `src/auth/roles.ts` — `canMutateCells`
- `src/components/MachineCellsSection.tsx` — новая секция «Ячейки»
- `src/pages/MachineDetailPage.tsx` — embed `MachineCellsSection`
- `src/test/money.test.ts`
- `src/test/machine-cells-section.test.tsx`
- `src/test/machine-detail-page.test.tsx` — mocks для cells/products API

## Примечания

- Polling: `refetchInterval: 20_000` ms (диапазон 15–30 с из ТЗ).
- Save отправляет только dirty rows (изменённые productUuid / dosage prices).
- Коммит не выполнялся по инструкции задачи.
