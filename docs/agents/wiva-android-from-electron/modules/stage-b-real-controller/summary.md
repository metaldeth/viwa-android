# Итог: stage-b-real-controller (B1, B2, B4)

**sessionId:** `viwa-android-from-electron/modules/stage-b-real-controller`

**Входной промпт:** в пользовательском сообщении указан только сегмент «3»; по `CHECKLIST_WIVA_ANDROID_STAGES.md` **этап 3** интерпретирован как `moduleId=stage-b-real-controller`, задачи **B1, B2, B4**.

## Выполнено

### B2 — протокол 1:1 с wiva_electron

- `ControllerProtocol.kt`: `formatRequest`, `processBuffer`, `parseResponse` — логика как в `ControllerProtocol.ts`.
- Юнит-тесты `ControllerProtocolTest.kt` — сценарии из `ControllerProtocol.test.ts` (TX FE…, RX D5…, неполный кадр).

### B1 — serial/USB обёртка

- `ControllerPortSettings` — baud **9600**, 8N1, путь по умолчанию как в `SERIAL_PORTS_IMPLEMENTATION.md` (`/dev/ttyS0`).
- `ControllerSerialTransport` + `LoggingStubControllerSerialTransport` — открытие с логом конфигурации, `write` с hex в logcat; входящих байт с железа **нет** до отдельной реализации USB/serial.
- `RealControllerGateway` — TX через протокол → транспорт; RX через буфер и `processBuffer` → `SharedFlow` (готово к подключению реального транспорта).
- `StubRealControllerGateway` удалён; `DelegatingControllerGateway` делегирует реал в `RealControllerGateway`.

### B4 — терминал → контроллер

- `PaymentTerminalService.kt` — зеркало `PaymentTerminalService.ts`: тело 0x48 (6 байт), подписка на `PaymentSystemsPaxStatus` (0x56), `cancelTransaction` без VendCancel, строки статусов RU.
- Сервис-экран: строка «PAX (0x56)», кнопка «Тест 0x48 — сумма на терминал (B4)».
- Чеклист сравнения с wiva: `b4_call_order_checklist_vs_wiva_electron.md`.

## Сборка

- Команда из `viwa-android/AGENTS.md`: `gradlew.bat assembleDebug` (+ `testDebugUnitTest`) — успешно.

## Версия приложения

- `versionCode` 4, `versionName` 26.04.01.04`.

## Артефакты модуля

- `request.md`, `orchestrator-log.md`, `summary.md`, `b4_call_order_checklist_vs_wiva_electron.md`.

## Не в scope этого этапа

- Физический USB/serial драйвер на Android.
- Вызов `sendSumToTerminal` из полного payment flow (в эталонном wiva тоже не вызывается из основного `src/`).
- Связка `preparing/manager` → `cancelTransaction` — после переноса preparing (модуль E и др.).

## Blocking questions

- Нет: неоднозначностей по §4.1 не выявлено; расхождение «sendSum не в main flow wiva» отражено в чеклисте B4.
