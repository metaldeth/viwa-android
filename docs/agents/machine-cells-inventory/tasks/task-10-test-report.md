# task-10 — test report

**Session:** `machine-cells-inventory`  
**Repo:** `viwa-android`  
**Date:** 2026-07-19

## Scope delivered

- `ViwaInventoryVolumesTab`: MVP path → local snapshot + `TelemetryCellsSyncCoordinator.onLocalVolumeChange`; legacy unchanged.
- `ViwaTelemetryInventoryTab`: MVP editable product/prices; product picker from `snapshot.products[]`; `onLocalContentChange`.
- `ServiceViewModel`: bind `TelemetryCellsRepository.snapshotFlow`; `mvpInventoryRows` / `snapshotProducts`; gate via `telemetryUseMvpProtocol`.
- `TelemetryCellsSnapshotAdapter`: snapshot → `DrinkContainer` (customer UI).
- `DrinkListViewModel`: MVP uses adapter + `snapshotFlow`; legacy uses `TELEMETRY_MERGED_INVENTORY`.
- `ViwaElectronAssets`: verified PNG + video asset mapping (`hasPreparingVideoAsset` for unit tests).

## Unit tests (TDD)

| # | Test class | Test | Result |
|---|------------|------|--------|
| 1 | `TelemetryCellsSnapshotAdapterTest` | `tasteMediaKey_mapsToDrinkContainerDisplayFields` | PASS |
| 2 | `TelemetryCellsSnapshotAdapterTest` | `nullProductUuid_returnsNullDrinkContainer` | PASS |
| 3 | `CellVolumeStatusTest` | stop / warning / normal thresholds | PASS (3 assertions) |
| 4 | `DrinkListViewModelMvpInventoryTest` | `useMvpProtocol_true_usesSnapshotAdapter` | PASS |
| 5 | `DrinkListViewModelMvpInventoryTest` | `useMvpProtocol_false_keepsLegacyMergedInventory` | PASS |
| 6 | `MvpInventorySnapshotMapperTest` | `productPickerSource_isSnapshotProducts_notExternalHttp` | PASS |

**Total:** 8 test methods, 8 passed.

## Verification commands

```bat
cd c:\viwa\viwa-android
gradlew.bat :app:testDebugUnitTest --tests "com.viwa.android.domain.customer.TelemetryCellsSnapshotAdapterTest" --tests "com.viwa.android.domain.model.CellVolumeStatusTest" --tests "com.viwa.android.domain.model.MvpInventorySnapshotMapperTest" --tests "com.viwa.android.ui.screens.customer.DrinkListViewModelMvpInventoryTest"
```

**Exit code:** 0

## Emulator smoke (service menu tabs)

**Status:** not run  
**Reason:** subagent session without confirmed AVD `wiva-android` online; manual smoke deferred to integration task-12 / local QA.

## Notes

- M-1 (uuid persist on schema) intentionally not in scope.
- Service menu tab order unchanged (`ViwaServiceMenuStructure.kt` not modified).
- Dashboard cells still derived from legacy merge when `useMvpProtocol=false`; MVP dashboard mapping not in task-10 scope.

## Changed files (production)

- `app/src/main/java/com/wiva/android/domain/model/CellVolumeStatus.kt` (new)
- `app/src/main/java/com/wiva/android/domain/model/MvpInventoryTableRow.kt` (new)
- `app/src/main/java/com/wiva/android/domain/customer/TelemetryCellsSnapshotAdapter.kt` (new)
- `app/src/main/java/com/wiva/android/ui/screens/service/ServiceViewModel.kt`
- `app/src/main/java/com/wiva/android/ui/screens/service/tabs/ViwaInventoryVolumesTab.kt`
- `app/src/main/java/com/wiva/android/ui/screens/service/tabs/ViwaTelemetryInventoryTab.kt`
- `app/src/main/java/com/wiva/android/ui/screens/customer/DrinkListViewModel.kt`
- `app/src/main/java/com/wiva/android/ui/screens/customer/ViwaElectronAssets.kt`

## Changed files (tests)

- `app/src/test/java/com/wiva/android/domain/customer/TelemetryCellsSnapshotAdapterTest.kt` (new)
- `app/src/test/java/com/wiva/android/domain/model/CellVolumeStatusTest.kt` (new)
- `app/src/test/java/com/wiva/android/domain/model/MvpInventorySnapshotMapperTest.kt` (new)
- `app/src/test/java/com/wiva/android/ui/screens/customer/DrinkListViewModelMvpInventoryTest.kt` (new)
- `app/src/test/java/com/wiva/android/ui/screens/customer/DrinkListViewModelTask05IntegrationTest.kt` (constructor fix)
