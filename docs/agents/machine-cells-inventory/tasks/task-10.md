# task-10 — Android service menu UI + customer drink adapter

**Repo:** `viwa-android`  
**Зависимости:** task-09

## Связь с этапом

M6 (часть 3) — UI inventory/volumes + drink list adapter.

## Что сделать

- Refactor **`ViwaInventoryVolumesTab`:** edit volume → local snapshot → coordinator volume report (MVP path).
- Refactor **`ViwaTelemetryInventoryTab`:** product picker from **local** `snapshot.products[]` (not REST); edit product/prices → content report.
- **`ServiceViewModel`:** bind `TelemetryCellsRepository` Flow; block/sos indicators §3.1.1; gate legacy vs MVP inventory source via `useMvpProtocol`.
- **`TelemetryCellsSnapshotAdapter`:** map snapshot cells → `DrinkContainer` for customer UI.
- **`DrinkListViewModel`:** when `useMvpProtocol=true` use adapter; when false keep legacy merge path unchanged.
- **`ViwaElectronAssets`:** resolve PNG/video from cell `tasteMediaKey` (existing mapping).
- Service menu structure unchanged (AGENTS.md §Сервисное меню — порядок вкладок).

## Точки изменения

- `app/src/main/java/com/wiva/android/ui/screens/service/tabs/ViwaInventoryVolumesTab.kt`
- `app/src/main/java/com/wiva/android/ui/screens/service/tabs/ViwaTelemetryInventoryTab.kt`
- `app/src/main/java/com/wiva/android/ui/screens/service/ServiceViewModel.kt` (or ServiceScreen VM)
- `app/src/main/java/com/wiva/android/domain/customer/TelemetryCellsSnapshotAdapter.kt` (новый)
- `app/src/main/java/com/wiva/android/ui/screens/customer/DrinkListViewModel.kt`
- `app/src/main/java/com/wiva/android/ui/screens/customer/ViwaElectronAssets.kt` (verify only)

## Тест-кейсы (TDD обязательны)

1. **Adapter:** cell with tasteMediaKey maps to DrinkContainer with correct display fields.
2. **Adapter empty product:** null productUuid → empty/unavailable drink slot handling.
3. **Threshold UI logic:** volume <= block → stop state; <= sos → warning (pure function tests).
4. **DrinkListViewModel MVP:** useMvpProtocol=true uses snapshot adapter not merged inventory (mock).
5. **DrinkListViewModel legacy:** useMvpProtocol=false unchanged behavior (regression mock).
6. **Product picker source:** inventory tab uses snapshot.products not HTTP (unit on ViewModel state).

## Критерии приёмки (TZ)

#23, #24, #26, #27, #28; UC-9.

## Verification

`gradlew.bat :app:testDebugUnitTest`; smoke on emulator `viwa-android` service menu tabs (manual).
