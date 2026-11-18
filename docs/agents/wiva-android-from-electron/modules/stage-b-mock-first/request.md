# Запрос сессии: stage-b-mock-first

**ПАРАМЕТРЫ ЭТАПА:** `2 | stage-b-mock-first | B3`

**ТЗ:** `viwa-android/docs/TZ_WIVA_ANDROID_FROM_ELECTRON.md` — выполнить только **B3** (MockController).

**Правила:** §2 источники (протокол/моки из `wiva_electron`), §3 мок контроллера, §4.1 — вопросы владельцу при неоднозначности.

**sessionId:** `viwa-android-from-electron/modules/stage-b-mock-first`  
**Каталог артефактов:** `viwa-android/docs/agents/viwa-android-from-electron/modules/stage-b-mock-first/`

**Итог этапа:** `gradlew.bat assembleDebug` из `viwa-android/AGENTS.md`; в каталоге модуля — `summary.md`.

**Эталон мока:** `viwa_electron/src/main/hardware/controller/__mocks__/MockControllerConnection.ts` и тесты рядом.

**Контекст проекта:** `viwa-android` уже имеет переключатель «Мок контроллера» в сервис-экране (`ServiceScreen` / `ServiceViewModel`, ключ `JsonStoreKeys.USE_MOCK_CONTROLLER`). Нужно связать флаг с реализацией контракта `ControllerGateway` / транспортом и моком по смыслу wiva.
