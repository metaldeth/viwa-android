# Итог complex-сессии: wiva-aqsi-card-payment

**Проект:** `viwa-android`  
**SessionId:** `wiva-aqsi-card-payment`  
**Статус:** реализация A1-A7 завершена, ревью-круги пройдены, сборка и тесты зелёные.

## Что сделано

- Добавлен выбор метода карточной оплаты `PAX` / `AQSI` с персистентностью через новый ключ `CARD_PAYMENT_METHOD`.
- Добавлен доменный и data-слой aQsi: `AqsiConfig`, `AqsiPaymentResult`, `AqsiRepository`, `Arcus2Client`, `AqsiRepositoryImpl`, in-memory диагностика последней операции.
- Добавлен отдельный Hilt-модуль `AqsiModule` без изменений `IntegrationsModule`.
- Добавлен `CardPaymentOrchestrator`; оба карточных входа `DrinkListViewModel` идут через него:
  - покупка напитка при `sbp == false`;
  - оплата подписки при `isSbp == false`.
- SBP-ветки, `PaymentTerminalService`, `hardware/controller/**`, `PaymentMethod` и существующие вкладки сервиса не изменялись по контракту.
- Добавлена группа сервисного меню «Оплата картой» с вкладками:
  - «Метод»;
  - «aQsi — Настройки»;
  - «aQsi — Диагностика».
- Доведены логи A7: `CardPayment`, `AqsiRepo`, `Arcus2`, `AqsiSettings`; Arcus2 wire-log только debug и без полного payload; TCP/config logs не раскрывают raw endpoint.

## Ревью-круги

| Этап | Круги | Итог |
|------|-------|------|
| ТЗ | 2 | Принято |
| Архитектура | 2 | Принято; добавлено покрытие подписки картой |
| План | 2 | Принято; расширены альтернативные ветки и диагностика |
| task-01 | 2 | Принято |
| task-02 | 2 | Принято; `timeoutMs` приведён к `Long` |
| task-03 | 3 | Принято; закрыты negative amount, cancel, ER/decline, wire parsing |
| task-04 | 2 | Принято; Hilt instrumented smoke и full connected suite зелёные |
| task-05 | 4 | Принято; добавлены ViewModel-тесты обоих карточных входов и AQSI outcomes |
| task-06 | 2 | Принято; закрыта validation/settings/diagnostics часть |
| task-07 | 3 | Принято; закрыты логи, телеметрия и финальные проверки |

## Проверки

- `gradlew.bat assembleDebug` — OK.
- `gradlew.bat :app:testDebugUnitTest` — OK.
- `gradlew.bat :app:connectedDebugAndroidTest` — OK, 3 tests on `snack-101-800x1280`.
- IDE lints по затронутым main/test областям — без ошибок.
- `TEMP_*` файлов в проекте не найдено.

## Артефакты

- `tz.md`
- `tz_review.md`
- `architecture.md`
- `architecture_review.md`
- `plan.md`
- `plan_review.md`
- `tasks/task-01.md` ... `tasks/task-07.md`
- `tasks/task-01-review.md` ... `tasks/task-07-review.md`
- `tasks/task-01-test-report.md` ... `tasks/task-07-test-report.md`

## Остаточные проверки вне автотестов

- Офисная проверка с живым PAX/контроллером.
- Проверка aQsi Pill / T7100 на реальном устройстве и сети.
- Release-проверка на целевом wiva AVD/железе перед выкладкой.
