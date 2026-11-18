# task-07 — code review (Web MachineCellsSection)

**Reviewer:** code-reviewer-complex  
**Date:** 2026-07-19  
**Repo:** `viwa-telemetry` (`apps/web`)  
**Task:** `docs/agents/machine-cells-inventory/tasks/task-07.md`  
**Test report:** `task-07-test-report.md`

## Verdict

**APPROVE** — секция «Ячейки» на MachineDetail соответствует acceptance task-07, UC-6 и WEB-3..WEB-7. Критических дефектов нет.

`npm run lint`, `npm run build`, `npm test` — exit 0 (57 tests). Unit-тесты покрывают таблицу, volume read-only, block/sos индикаторы, VIEWER, bulk PATCH dirty rows, `VOLUME_READ_ONLY`, money helpers. WEB-3 polling и WEB-4 snapshot на device — manual / task-12 E2E (зафиксировано в test report).

---

## Acceptance criteria (TZ)

| # | Критерий | Статус | Доказательство |
|---|----------|--------|----------------|
| 1 | `MachineCellsSection` embedded в MachineDetail | ✅ | `MachineDetailPage.tsx` → `{id && <MachineCellsSection machineId={id} />}` |
| 2 | Load `GET /machines/:id/cells` + `GET /products` (+ tastes для RU label) | ✅ | `machineCellsApi.list`, `productsApi.list`, `productsApi.getTastes` |
| 3 | Колонки: №, product (name+taste), volume RO, maxVolume, block, sos, dosage1/2, status | ✅ | TableHead + Chip «Стоп»/«Мало»/«Норма» |
| 4 | `money.ts`: `kopecksToRublesDisplay`, `rublesInputToKopecks` | ✅ | `utils/money.ts` + `money.test.ts` |
| 5 | Save → `PATCH` bulk только dirty rows | ✅ | `dirtyCells` + `buildPatchPayload` + unit-тест PATCH payload |
| 6 | Polling 15–30 s refetch volume | ✅ | `CELLS_POLL_INTERVAL_MS = 20_000`, `refetchInterval` в useQuery |
| 7 | Status: volume ≤ block → stop/red; ≤ sos → warning; else normal | ✅ | `getVolumeStatus` + unit + Chip colors |
| 8 | VIEWER read-only, no Save | ✅ | `canMutateCells`; Select/TextField скрыты; unit-тест |
| 9 | `VOLUME_READ_ONLY` при ошибке API | ✅ | `formatApiError` + unit-тест (UI без volume inputs) |
| 10 | `canMutateCells` role helper | ✅ | `roles.ts` = MASTER/ADMIN/OPERATOR (как backend MUTATION_ROLES) |
| 11 | TZ #19 MachineDetail ячейки | ✅ | read-only volume, select, save, block/sos |
| 12 | TZ #20 цены рубли ↔ копейки | ✅ | display comma decimal; PATCH kopecks |
| 13 | UC-6 read/write + A1/A3 | ✅ | read path + PATCH; A3 VIEWER; structural fields RO (OQ-3) |
| 14 | WEB-3..WEB-7 | ✅ unit; ⚠️ WEB-3 polling / WEB-4 device — manual |
| 15 | Verification lint/build | ✅ | test report exit 0 |

---

## Сверка с backend (task-03)

| Аспект | Backend | Web | Статус |
|--------|---------|-----|--------|
| GET cells path/ shape | `/api/v1/machines/:id/cells` → `{ schemaHash, contentRevision, updatedAt, items }` | `MachineCellsResponse` + `machineCellsApi.list` | ✅ |
| PATCH body | `{ cells: [{ uuid, productUuid?, dosage1Price?, dosage2Price? }] }` | `machineCellsApi.patch(machineId, payload)` | ✅ |
| volume в PATCH | 400 `VOLUME_READ_ONLY` | Нет volume в UI и в `PatchMachineCellItem` | ✅ |
| PATCH roles | OPERATOR+ | `canMutateCells` | ✅ |
| VIEWER PATCH | 403 | UI без Save и без edit controls | ✅ |
| Error codes | `VOLUME_READ_ONLY`, `CELL_NOT_FOUND` | `formatApiError` RU-тексты | ✅ (CELL_NOT_FOUND без unit-теста) |

---

## Фокусная проверка (по запросу ревью)

### MachineCellsSection

- Draft-state: `useEffect` синхронизирует drafts с сервером только если нет локальных изменений (`draftsEqual`) — polling не затирает несохранённые product/price edits; volume в таблице берётся из `cell.volume` (сервер), не из draft — корректно для eventual consistency.
- После save: `setQueryData` + полный reset drafts из ответа PATCH — согласовано с backend «full snapshot in response».
- Empty state: сообщение «Ячейки появятся после schema report» — WEB-3 UX.
- Structural fields (maxVolume, block, sos) — только отображение, без inputs — OQ-3.

### money.ts

- Формат `9900 → "99,00"`, парсинг `,` и `.`, пустая строка → `null`, отрицательные/NaN → invalid — покрыто тестами.
- `Math.round(value * 100)` — допустимо для MVP; float edge cases (0.1+0.2) маловероятны при ручном вводе цен.

### machineCellsApi

- Минимальный контракт `list` / `patch`; типы из `types/api.ts`.
- `ApiError.code` из structured body — используется в `formatApiError`.

### canMutateCells

- Совпадает с `canMutateProducts` и backend `MUTATION_ROLES`.
- UI-only gate; backend 403 — вторая линия (UC-8).

### MachineDetailPage embed

- Секция после grid полей машины, `machineId` из route `id`.
- Regression: `machine-detail-page.test.tsx` мокает cells/products API (пустые items) — существующие тесты rebind/nav не ломаются; явной проверки заголовка «Ячейки» нет.

### Polling

- 20 s ∈ [15, 30] из ТЗ.
- Refetch после save через `onSuccess` + `setQueryData` (не только interval).
- Save disabled при `cellsQuery.isFetching` — на каждом poll кратковременно блокирует кнопку (см. M-1).

### block / sos (WEB-6)

- Логика `getVolumeStatus`: stop при `volume <= blockVolume`, warning при `volume <= sosVolume` (и > block), иначе normal — совпадает с TZ §семантика порогов (`<=`, не strict `<`).
- Unit-тесты: 50/100/300, 250/100/300, 500/100/300.
- Chip + Tooltip с raw values — удобно для отладки.

### volume read-only (WEB-5)

- Volume — `Typography`, не `TextField`.
- `buildPatchPayload` / `PatchMachineCellItem` не содержат volume.
- Defensive handling `VOLUME_READ_ONLY` на PATCH error — ok.

### VIEWER (WEB-2 / UC-6 A3)

- Нет кнопки «Сохранить».
- Product — текст из `productName` + taste; prices — `kopecksToRublesDisplay`.
- Unit-тест проверяет отсутствие Save; не assert-ит отсутствие Select/TextField (код корректен).

---

## Архитектура и код

### Сильные стороны

- **React Query:** cells polling + products/tastes cache; invalidate через setQueryData на save.
- **Чистые helpers:** `getVolumeStatus`, `buildPatchPayload`, `hasPatchChanges` — экспорт для unit-тестов.
- **RBAC:** единый паттерн с ProductsPage (`canMutate*` + условный рендер).
- **A11y:** `aria-label` на таблице, volume, price inputs.
- **UX:** dirty row highlight (`selected={isDirty}`), счётчик несохранённых, hint про auto volume refresh.

### Findings

#### Critical

_Нет._

#### Medium (non-blocking)

| ID | Topic | Detail |
|----|-------|--------|
| M-1 | Save disabled on poll fetch | `disabled={… \|\| cellsQuery.isFetching}` — каждые 20 с refetch кратко блокирует «Сохранить». Риск для оператора с долгим редактированием. Fix: не блокировать save на background refetch (только `isPending` mutation / initial load). |
| M-2 | Конфликт dirty draft vs server | Polling не перезаписывает draft при расхождении с server product/prices — правильно для local edits, но другой оператор мог уже PATCHнуть ячейку; save перезапишет без merge/conflict UI. MVP acceptable; v1 — revision/conflict hint. |
| M-3 | Неполное покрытие VIEWER | Тест только на отсутствие Save; нет assert read-only product/price rendering. |
| M-4 | WEB-3 / WEB-4 не автоматизированы | Schema report + volume polling + device snapshot ≤5 s — manual / task-12; ожидаемо по task note. |
| M-5 | `canMutateCells` без unit-теста | Тривиальная логика, зеркало backend; как M-3 в task-06-review. |

#### Low / Info

| ID | Topic | Detail |
|----|-------|--------|
| L-1 | i18n заголовков | «Block» / «SOS» на английском при русском UI («Объём, мл», «Стоп»). |
| L-2 | `tastesQuery.isError` | Нет Alert; fallback на `tasteMediaKey` — degrades ok. |
| L-3 | `CELL_NOT_FOUND` | Handler есть, unit-теста нет. |
| L-4 | `INVALID_THRESHOLDS` | В `MachineCellErrorCode`, не в `formatApiError` — не актуально для web PATCH (structural RO). |
| L-5 | MachineDetail embed test | Нет smoke «Ячейки» / heading в page test — только API mocks. |
| L-6 | Default block=sos=0 | `volume=0` → stop при `blockVolume=0` — буквально по `<=`; при «нет порогов» может показать красный на пустой ячейке. Согласовано с TZ формулой; продуктово спорно. |
| L-7 | Polling в фоне | `refetchInterval` активен при уходе со страницы до unmount — стандартное поведение RQ. |

---

## Файлы

| File | Оценка |
|------|--------|
| `src/components/MachineCellsSection.tsx` | OK — core UI, polling, drafts, PATCH; ⚠️ M-1 save+fetching |
| `src/utils/money.ts` | OK |
| `src/api/client.ts` | OK — `machineCellsApi.list` / `patch` |
| `src/types/api.ts` | OK — `MachineCellDto`, patch types |
| `src/auth/roles.ts` | OK — `canMutateCells` |
| `src/pages/MachineDetailPage.tsx` | OK — embed секции |
| `src/test/money.test.ts` | OK |
| `src/test/machine-cells-section.test.tsx` | OK — 4 UI + 2 helper; ⚠️ M-3 gaps |
| `src/test/machine-detail-page.test.tsx` | OK — regression mocks; ⚠️ L-5 |

---

## Тесты (сверка с test report)

| Suite | Result | task-07 relevance |
|-------|--------|-------------------|
| `npm run lint` | PASS | ✅ |
| `npm run build` | PASS | ✅ |
| `npm test` (57 total) | PASS | ✅ |
| `money.test.ts` (2) | PASS | ✅ WEB-4 / TZ #20 |
| `machine-cells-section.test.tsx` (6) | PASS | ✅ WEB-3/5/6/7, VIEWER, VOLUME_READ_ONLY |
| `machine-detail-page.test.tsx` | PASS | ✅ regression |
| Manual WEB-3 polling | Not run | ⚠️ staging |
| Manual WEB-4 device snapshot | Not run | ⚠️ task-12 E2E |

---

## Рекомендации (post-merge, optional)

1. M-1: убрать `cellsQuery.isFetching` из disabled Save (или различать initial vs background refetch).
2. Unit: VIEWER — нет Select/TextField; invalid price blocks save; `CELL_NOT_FOUND` message.
3. Manual smoke WEB-3 на стенде: schema report → N rows → volume report → refetch ≤30 s.
4. L-1: переименовать колонки Block/SOS → «Блок, мл» / «SOS, мл» (или RU эквивалент).

---

## Итог для оркестратора

```json
{
  "reviewReportFile": "docs/agents/machine-cells-inventory/tasks/task-07-review.md",
  "hasCriticalIssues": false,
  "commentsSummary": "MachineCellsSection + machineCellsApi + money.ts + canMutateCells соответствуют task-07/TZ #19–#20/UC-6/WEB-3..WEB-7: таблица ячеек на MachineDetail, volume read-only, product select из каталога, bulk PATCH dirty rows, polling 20 с, block/sos Chip-индикаторы, VIEWER read-only, VOLUME_READ_ONLY в UI. lint/build/test PASS (57). Некритично: Save блокируется на background poll fetch, нет E2E polling/snapshot (manual/task-12), мелкие gaps unit-тестов VIEWER/CELL_NOT_FOUND, заголовки Block/SOS на EN."
}
```
