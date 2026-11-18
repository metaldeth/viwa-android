# Инвентаризация обменов телеметрии (D2)

Источник семантики: **wiva_electron** `src/main/modules/telemetry/`. Транспорт: **legacy Android kiosk** `data/remote/telemetry/*` (адаптировано в `viwa-android` под `com.viwa.android`).

| Топик / `type` | Направление | Файл эталона (TS) | Планируемый / фактический Kotlin |
| --- | --- | --- | --- |
| `cellStoreRequestExport` | → сервер | `manager.ts` onConnect, `requestCellStoreExport` | `ViwaTelemetryService.sendInitialExchange` |
| `machineInfo` | → запрос | `manager.ts` onConnect | `ViwaTelemetryService.sendInitialExchange` |
| `machineInfo` | ← ответ | `exchanges/machineInfo/types/machineInfoResponse.ts`, `processMachineInfoResponse` | `ViwaTelemetryService.handleMachineInfo` → `MachineRegistration` |
| `baseIngredientRequestExportTopic` | → запрос | `manager.ts` onConnect, `requestBaseIngredientExport` | `ViwaTelemetryService.sendInitialExchange` |
| `baseIngredientRequestExportTopic` | ← данные | `exchanges/baseIngredient/types/baseIngredientExport.ts`, `processBaseIngredientExport` | Лог (полная матрица — этап E/config) |
| `cellStoreExport` | ← push | `exchanges/cellStore/types/cellStoreExport.ts`, `processCellStoreExport` | Лог (мердж конфига — этап E) |
| `cellStoreRequestExport` | ← | `exchanges/cellStore/types/cellStoreRequestExport.ts` | Лог |
| `cellVolumeExport` | ← | `exchanges/cellVolume/processCellVolumeExport.ts` | Лог |
| `statusSubscribeTopic` | → | `manager.ts` `sendSubscriptionRequest` (`body` = UUID строкой) | `ViwaTelemetryService.sendStatusSubscribeTopic` (`clientId`, `type`, `body` строка) |
| `subscriptionLevelTopic` | → | `manager.ts` `sendSubscriptionLevelRequest` | По мере UI |
| `subscribeInformationTopic` | ← | `exchanges/subscription/types/subscribeInformationTopic.ts` | Лог / подписка (этап E) |
| `subscriptionLevelTopic` | ← | `exchanges/subscription/processSubscriptionLevelResponse.ts` | Лог |
| `saleSubscribeTopic` | → | `manager.ts` `sendSaleSubscribeTopic` | По мере UI оплаты подписки |
| `saleImportTopic` | → | `manager.ts` `sendSaleImportTopic` | `ViwaTelemetryService.sendSaleImportTopic`, демо D4, триггер после Pax 4 |
| `useSubscriptionSaleTopic` | → | `manager.ts` `sendUseSubscriptionSaleTopic` | По мере сценария подписки |
| `cellVolumeImportTopic` | → | `manager.ts` `sendCellVolumeImportTopic` | После конфига ячеек |
| `cellStoreImportTopic` | → | `manager.ts` `sendCellStoreImportFromConfig` | После калибровки (как wiva) |
| `machineVersionChangeExport` | → | `manager.ts` `sendMachineVersionChangeExport` | OTA/версии приложения |
| `machineVersionChangeExport` | ← ack | `messageHandler.ts` | Лог |
| `authCodeRequestExport` | → / ← | `manager.ts` `sendAuthCodeRequest`, `exchanges/authCodeRequestExport/types/...` | `ViwaTelemetryService.sendAuthCodeRequest` |

Регистрация REST: `registration.ts` → `TelemetryApiService.registerMachine` + `ViwaTelemetryService.registerMachine` (modelName/machineName **WIVA**).

Авторизация WS: `authorization.ts` → `WivaTelemetryAuth.fetchAccessToken` (client_credentials).
