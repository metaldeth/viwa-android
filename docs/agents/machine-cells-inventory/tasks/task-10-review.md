# task-10 — code review (Android service menu UI + customer drink adapter)

**Reviewer:** code-reviewer-complex  
**Date:** 2026-07-19  
**Repo:** `viwa-android`  
**Task:** `docs/agents/machine-cells-inventory/tasks/task-10.md`  
**Test report:** `task-10-test-report.md`  
**Carry-over:** task-09 M-2 (uplink callers via ServiceViewModel)

## Verdict

**APPROVE** — task-10 закрывает M6 (часть 3): MVP volumes/inventory tabs через `snapshotFlow`, wiring `onLocalVolumeChange` / `onLocalContentChange` в `ServiceViewModel`, customer `TelemetryCellsSnapshotAdapter`, legacy gate без регрессии. Критических дефектов нет; unit-тесты 8/8 PASS (по test report). Emulator smoke service menu не выполнялся — defer task-12 / local QA.

---

## Focus check (checklist ревью)

| # | Проверка | Статус | Доказательство |
|---|----------|--------|----------------|
| 1 | MVP volume edit → local snapshot → coordinator volume report | ✅ | `ViwaInventoryVolumesTab` → `saveInventoryVolumes` → `saveInventoryVolumesMvp` → `telemetryCellsSyncCoordinator.onLocalVolumeChange` (`ServiceViewModel.kt` L1106–1147) |
| 2 | MVP content edit → coordinator content report | ✅ | `MvpTelemetryInventoryTab` → `saveMvpInventoryContent` → `onLocalContentChange` (L1151–1182); wire через `codec.encodeContentReportPayload` (coordinator L109–112) |
| 3 | Product picker from `snapshot.products[]`, not REST | ✅ | `snapshotProducts` из `mapMvpInventoryFromSnapshot`; picker в `MvpTelemetryInventoryTab` L217–227; test `MvpInventorySnapshotMapperTest` |
| 4 | Legacy path unchanged при `useMvpProtocol=false` | ✅ | `ViwaInventoryVolumesTab` / `ViwaTelemetryInventoryTab` — отдельные Legacy* composables; `observeInventoryTable` legacy branch (L946–954); `DrinkListViewModelMvpInventoryTest.useMvpProtocol_false_*` |
| 5 | Adapter: tasteMediaKey → DrinkContainer display | ✅ | `TelemetryCellsSnapshotAdapter.toDrinkContainer` L36–52; test `tasteMediaKey_mapsToDrinkContainerDisplayFields` + `ViwaElectronAssets` URI/video |
| 6 | Adapter: null productUuid → empty slot | ✅ | `productUuid ?: return null` L34; test `nullProductUuid_returnsNullDrinkContainer` |
| 7 | block/sos indicators (§3.1.1) | ✅ (service UI) / ⚠️ (customer) | `resolveCellVolumeStatus` + labels в `MvpVolumeRow` / `MvpInventoryEditRow`; pure tests `CellVolumeStatusTest`. Customer `isUnavailable` — см. M-1 |
| 8 | Service menu tab order unchanged | ✅ | `ViwaServiceMenuStructure.kt` не в diff (test report); gate только внутри существующих tabs |
| 9 | task-09 M-2 closed (production uplink callers) | ✅ | Grep `app/src/main`: вызовы только из `ServiceViewModel` L1140, L1174 |
| 10 | TDD cases 1–6 | ✅ | 8/8 PASS (test report) |

---

## Acceptance criteria (TZ #23–#28, UC-9)

| # | Критерий | Статус | Комментарий |
|---|----------|--------|-------------|
| 23 | Volume tab: edit → `cells.volume.report` | ✅ | MVP tab → `saveInventoryVolumesMvp` → coordinator |
| 24 | Inventory tab: product/prices → `cells.content.report` | ✅ | Per-row save → `saveMvpInventoryContent`; локально merge denormalized для snapshot, uplink без них (codec) |
| 26 | `tasteMediaKey` → PNG/video (`ViwaElectronAssets`) | ✅ | Adapter + test asserts `horizontalCardImageUri` / `hasPreparingVideoAsset` |
| 27 | Customer drink list из snapshot, не legacy merge | ✅ | `DrinkListViewModel` init: `snapshotFlow` + `TelemetryCellsSnapshotAdapter` при MVP |
| 28 | Legacy isolation (`useMvpProtocol=true` Shaker no-op; `false` не ломается) | ✅ | UI split + legacy inventory path; Shaker gate из task-09 не затронут |
| UC-9 | Пороги в service menu + customer adapter + legacy gate | ✅ partial | Service tabs показывают стоп/мало/норма; adapter без merge. Customer boundary vs STOP — M-1 |

---

## Architecture compliance

### MVP data path

- `observeInventoryTable`: `combine(inventoryRevision, snapshotFlow, telemetryUseMvpProtocol)` — reactive gate корректен для **ServiceViewModel**.
- `applyMvpInventorySnapshot`: только `mvpInventoryRows` + `snapshotProducts`; dashboard/syrup/preparing stats по-прежнему из legacy merge (test report L47 — out of scope task-10, см. M-2).

### C-1 denormalization

- Content save в VM обогащает локальный `TelemetryCell` (`productName`, `tasteMediaKey`) для snapshot UI — допустимо для downlink-shaped local store.
- Uplink: coordinator → `encodeContentReportPayload` — без denormalized полей (task-08/09).

### Legacy isolation

- Legacy tabs и `DrinkListViewModel` `inventoryRevision` + `TELEMETRY_MERGED_INVENTORY` не изменены по смыслу.
- Default `loadTelemetryUseMvp()` → `true` при отсутствии config — согласовано с `ServiceUiState.telemetryUseMvpProtocol` default.

---

## TDD vs acceptance / UC-9

| Task TDD # | Покрытие | Gap |
|------------|----------|-----|
| 1 Adapter display fields | ✅ `TelemetryCellsSnapshotAdapterTest` | — |
| 2 Empty product | ✅ null container | Нет кейса «productUuid есть, prices пустые» → null (L39 adapter) |
| 3 Threshold UI logic | ✅ `CellVolumeStatusTest` (pure fn) | Нет UI/Compose test меток; нет связки с `DrinkContainer.isUnavailable` |
| 4 DrinkList MVP | ✅ `DrinkListViewModelMvpInventoryTest` | — |
| 5 DrinkList legacy | ✅ regression mock | — |
| 6 Product picker source | ⚠️ | `MvpInventorySnapshotMapperTest` проверяет mapper, не `ServiceViewModel.snapshotProducts` / tab state |
| — ServiceViewModel wiring | ❌ | Нет unit-тестов `saveInventoryVolumesMvp` / `saveMvpInventoryContent` → mock coordinator |
| — Emulator smoke (UC-9 tabs) | ❌ | Not run (test report); nested scroll — см. L-1 |

---

## Compose layout (кратко)

**Риск nested scroll:** `SettingsColumn` уже делает `fillMaxSize().verticalScroll()` (`ServiceTabUtils.kt` L70–74). Внутри MVP/Legacy tabs добавлен второй `verticalScroll` с bounded height (`heightIn(max=640.dp)` / `height(420.dp)`). По канону `android-compose-scroll-layout.mdc` вложенный scroll — антипаттерн; bounded height снижает риск `infinity maximum height`, но **smoke на эмуляторе обязателен** (не выполнен).

---

## Findings

### Critical

_Нет._

---

### Medium (non-blocking)

| ID | Topic | Detail |
|----|-------|--------|
| M-1 | STOP boundary: service vs customer | `resolveCellVolumeStatus`: `volume <= blockVolume` → STOP. `DrinkContainer.isUnavailable`: `volumeMl < minVolumeMl` (strict). При `volume == blockVolume` service tab показывает «стоп», customer карточка может остаться доступной. Adapter явно мапит `blockVolume → minVolumeMl` (comment L14) — операторы должны быть согласованы для UC-9. |
| M-2 | MVP dashboard не из snapshot | `applyMvpInventorySnapshot` не вызывает `updateDashboardDerived`; при `useMvpProtocol=true` dashboard cells остаются пустыми/legacy до отдельной задачи. Задокументировано в test report; не блокер tabs task-10. |
| M-3 | DrinkListViewModel MVP gate только при init | `loadTelemetryUseMvp()` один раз в `init` (L226–236). Смена `telemetryUseMvpProtocol` в service menu без пересоздания VM не переключит источник inventory. Редкий ops-сценарий; worth note для QA. |
| M-4 | Пустой snapshot не очищает customer list | `applyInventoryContainers`: `if (live.isEmpty()) return` (L421) — при очистке snapshot UI может показать stale containers. |
| M-5 | Test #6 не на ViewModel | Product picker source проверен на `mapMvpInventoryFromSnapshot`, не на wiring `ServiceViewModel.snapshotProducts` + tab. Достаточно для mapper layer; слабее для acceptance «inventory tab uses snapshot.products». |
| M-6 | Adapter default dosage | `defaultDosage` hardcoded 300/30/270 для всех ячеек — не из snapshot/recipe. MVP может быть OK для карточек; влияет на preparing stats / recipe display если snapshot несёт другие дозировки. |

---

### Low / Info

| ID | Topic | Detail |
|----|-------|--------|
| L-1 | Emulator smoke deferred | Service menu tabs не открывались на AVD; nested scroll + editable rows — кандидат на task-12 smoke + logcat. |
| L-2 | task-09 M-2 **closed** | Production callers: `ServiceViewModel.saveInventoryVolumesMvp` / `saveMvpInventoryContent`. |
| L-3 | Цены rub ← kopecks | `kopecks / 100` truncates; согласовано с тестом (9900→99). Document if web rounds. |
| L-4 | `ViwaElectronAssets.hasPreparingVideoAsset` | Добавлен для unit-testability — OK, не меняет runtime mapping. |
| L-5 | Content save per-row | Каждая строка — отдельный `saveMvpInventoryContent` (1 cell) — OK для MVP; batch save не требовался. |

---

## File review summary

| File | Verdict | Notes |
|------|---------|-------|
| `TelemetryCellsSnapshotAdapter.kt` | OK | Sort by cellNumber; null product/prices filtered; block→minVolumeMl |
| `TelemetryCellsSnapshotAdapterTest.kt` | OK | Display + empty product + assets |
| `CellVolumeStatus.kt` | OK | Matches TZ §3.1.1 / web getVolumeStatus |
| `CellVolumeStatusTest.kt` | OK | stop/warning/normal boundaries |
| `MvpInventoryTableRow.kt` | OK | Mapper + `volumeStatus` derivation |
| `MvpInventorySnapshotMapperTest.kt` | OK | products from snapshot |
| `ServiceViewModel.kt` | OK | snapshotFlow bind, MVP gate, coordinator wiring; dashboard MVP gap (M-2) |
| `ViwaInventoryVolumesTab.kt` | OK | MVP/Legacy split; status labels on MVP rows |
| `ViwaTelemetryInventoryTab.kt` | OK | Local product picker dialog; MVP edit row |
| `DrinkListViewModel.kt` | OK | MVP snapshot adapter path; init-only gate (M-3) |
| `DrinkListViewModelMvpInventoryTest.kt` | OK | MVP vs legacy regression |
| `ViwaElectronAssets.kt` | OK (verify) | `hasPreparingVideoAsset` for tests |

---

## Verification

| Check | Result |
|-------|--------|
| Unit tests (task-10 scope) | ✅ 8/8 PASS (test report, exit 0) |
| `:app:testDebugUnitTest` full | не запускалось в review session |
| Emulator service menu smoke | ❌ not run |
| Compose layout smoke | ❌ not run |

---

## Recommendations (follow-up)

1. **M-1:** Выровнять customer availability с STOP: `isUnavailable` → `<= minVolumeMl` или отдельный SOS/WARNING для UI (product decision).
2. **L-1 / task-12:** Smoke volumes + inventory tabs на AVD; logcat без `infinity maximum height`.
3. **M-5:** Опционально `ServiceViewModelTest` с mock `TelemetryCellsSyncCoordinator` для #23/#24 wiring.
4. **M-2:** Отдельная задача — dashboard cells из MVP snapshot при `useMvpProtocol=true`.

---

## Итог для оркестратора

```json
{
  "reviewReportFile": "docs/agents/machine-cells-inventory/tasks/task-10-review.md",
  "hasCriticalIssues": false,
  "commentsSummary": "task-10 APPROVE: MVP volumes/inventory tabs через snapshotFlow, ServiceViewModel wiring onLocalVolumeChange/onLocalContentChange (закрывает task-09 M-2), TelemetryCellsSnapshotAdapter + DrinkListViewModel MVP path, legacy gate без регрессии, block/sos в service UI, tasteMediaKey→ViwaElectronAssets. Unit-тесты 8/8 PASS. Некритично: расхождение STOP boundary service vs DrinkContainer.isUnavailable (< vs <=), MVP dashboard не из snapshot, DrinkList MVP gate только при init, nested verticalScroll в SettingsColumn+tabs без emulator smoke, test #6 на mapper а не ViewModel."
}
```
