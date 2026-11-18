# B4: порядок вызовов vs wiva_electron (чеклист)

Эталонные файлы: `src/main/services/payment/PaymentTerminalService.ts`, `src/main/modules/payment/preparing/manager.ts`, `src/main/modules/payment/service.ts`, `src/main/modules/payment/manager.ts`.

| Шаг | wiva_electron | viwa-android (эта реализация) |
|-----|---------------|-------------------------------|
| Инициализация сервиса терминала | `PaymentTerminalService.init()` → `events.on(PaymentSystemsPaxStatus, process)` | `PaymentTerminalService` init: `incomingResponses.collect` → при `0x56` вызывается `processPax` |
| Отправка суммы на терминал | `controller.sendCommand(SendSumToPaymentTerminal, [ph,pl,prh,prl,sbp,type])` | `sendSumToTerminal` → `controller.sendCommand(SendSumToPaymentTerminal,` 6 байт с тем же порядком |
| Контроллер недоступен | Лог warn, return | Команда всё равно уходит в шлюз; при отсутствии порта транспорт только логирует (как в TS при закрытом порте — предупреждение в Controller) |
| Отмена транзакции | Лог: VendCancel 0xC5 не отправляется; флаг `isTransactionOnCancel` | `cancelTransaction()` — тот же лог и флаг |
| Статус PAX | `process(body)` — сравнение байта статуса, лог `PaxStatus` | `processPax` — сравнение байта, обновление `vendStatusText`, лог |
| Полный pay → prepare напитка | `payment/service.ts` / `manager.ts` — CARD через `PaymentRunner`, не обязательно 0x48 | **Не портировалось в этом этапе** (ожидает модуль E/оплату); в wiva `sendSumToTerminal` из `src/` сейчас тоже не вызывается в основном флоу — только API сервиса |
| Ошибка приготовления | `preparing/manager` → `paymentTerminal.cancelTransaction()` | Будет связано при переносе preparing; демо: кнопка «Тест 0x48» в сервис-экране |
