# task-06 — отчёт по тестам (A6)

> Сессия `wiva-aqsi-card-payment`. Критерий: unit-тесты вкладок сервисного меню + `assembleDebug`.

## Команды

```bat
cd c:\viwa\viwa-android
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests "com.viwa.android.ui.screens.service.tabs.*"
```

## Результат

| Проверка | Статус |
|----------|--------|
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` (фильтр `com.viwa.android.ui.screens.service.tabs.*`) | BUILD SUCCESSFUL |

## Покрытие (пакет `ui.screens.service.tabs`, тесты)

| Класс теста | Сценарии |
|-------------|----------|
| `CardPaymentServiceMenuPolicyTest` | Кнопка теста 1 коп.: скрыта при `isDebugBuild=false`, видна при `true`. |
| `ViwaCardPaymentMethodViewModelTest` | Загрузка AQSI из репозитория; выбор PAX вызывает сохранение PAX; выбор AQSI (`selectAqsi`) вызывает сохранение AQSI и обновление UI. |
| `ViwaAqsiSettingsViewModelTest` | Пустой/пробельный host: Save и TCP-тест не вызывают `saveConfig` / не сохраняют; Save по валидным полям → `lastSaved`; TCP успех → баннер OK; TCP failure → ошибка без падения; **перед `testTcpConnection`** репозиторий получает `saveConfig`, затем `testTcpConnection` (порядок зафиксирован в fake). |
| `WivaAqsiDiagnosticsViewModelTest` | Метка метода; сводка holder после предзаполнения; чтение полей после «как с заказа» (snapshot decline). |

## Версия APK (build.gradle.kts)

`versionCode` 162, `versionName` 12.05.26.4.

## Примечания

- Логи Timber с тегом `AqsiSettings` при сохранении и TCP-тесте — ручная приёмка в task-07.
- Compose screenshot-тесты не добавлялись (ограничение task-06).
- **Guard «тест 1 коп.»:** отдельного dev/mock-флага именно для этой кнопки в проекте нет; ключ `JsonStoreKeys.USE_MOCK_CONTROLLER` относится к моку контроллера, не к AQSI. Единственный доступный в коде guard видимости/no-op — **`BuildConfig.DEBUG`** (`CardPaymentServiceMenuPolicy` / `pennyTestButtonVisibleInThisBuild`); release блокируется unit-тестом `CardPaymentServiceMenuPolicyTest` (`isPennyTestButtonAvailable(false)`).
