# Итог этапа: stage-d-telemetry (D1–D4)

## Выполнено

- **D1:** Транспорт как у legacy Android kiosk — `WivaTelemetryWebSocketManager`, Keycloak-токен `WivaTelemetryAuth`, REST-регистрация `TelemetryApiService`, DI `TelemetryModule`. Состояние WS и поля URL/regKey/serial на экране «Сервис».
- **D2:** Таблица соответствия топиков и файлов — `viwa-android/docs/TELEMETRY_EXCHANGES_INVENTORY.md`.
- **D3:** Начальная тройка после connect как в `manager.ts` onConnect (`cellStoreRequestExport`, `machineInfo`, `baseIngredientRequestExportTopic`); входящий `machineInfo` сохраняет id/org/model/serial в `MachineRegistration`. Остальные типы из `messageHandler.ts` зафиксированы в инвентаризации; разбор домена (матрица, подписки) отложен до этапа E при появлении конфигурации/UI.
- **D4:** Модели JSON `saleImportTopic` как в wiva (`ViwaTelemetrySaleDtos`); отправка демо с сервис-экрана; при коде Pax **4** (оплата прошла) из `PaymentTerminalService` — автоматическая попытка отправить тот же демо-payload (связка «событие терминала → telemetry»). Юнит-тест на структуру payload.

## Сборка

`gradlew.bat assembleDebug` (см. `viwa-android/AGENTS.md`).

## Артефакты сессии

- `request.md`, `orchestrator-log.md`, данный `summary.md`.

## Владельцу продукта

Если продакшен-стенд отличается от дефолтов в `TelemetryConfig`, URL задаются на экране сервиса или в `telemetryConfig` в JSON-store.
