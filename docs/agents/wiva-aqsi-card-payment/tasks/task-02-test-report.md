# Отчёт по тестам — task-02 (домен aQsi)

**Дата:** 2026-05-12  
**Проект:** `viwa-android`  
**Этап ТЗ:** A2  
**Примечание:** повторный прогон после ревью — `AqsiConfig.timeoutMs` приведён к `Long`, `AQSI_DEFAULT_TIMEOUT_MS = 15000L` (как в `request.md` / `architecture.md`).

## Команды

| Команда | Результат |
|---------|-----------|
| `gradlew.bat assembleDebug` | `BUILD SUCCESSFUL` |
| `gradlew.bat :app:testDebugUnitTest --tests "com.viwa.android.domain.model.AqsiConfigTest"` | зелёные |
| `gradlew.bat :app:testDebugUnitTest --tests "com.viwa.android.domain.model.AqsiPaymentResultTest"` | зелёные |

При последнем прогоне обе группы указаны одной Gradle-командой с двумя `--tests`.

## Покрытые кейсы из task-02

| Класс | Тесты |
|-------|-------|
| `AqsiConfigTest` | Дефолты конструктора (`timeoutMs` как `Long` / 15000); `{}`/частичный JSON → значения порта и таймаута 16107 / 15000; copy/equals/не-равенство; encode/decode round-trip; литерал `JsonStoreKeys.AQSI_SETTINGS` |
| `AqsiPaymentResultTest` | Равенство `Approved`; `Declined` / `Error`; exhaustiveness при `when` по sealed-веткам |

Интерфейс `AqsiRepository` без сетевой реализации — без отдельных unit-тестов (см. task-03).

## Итог

Все добавленные тесты каталога домена aQsi для task-02 завершены успешно (`exit code 0`).
