# Задача: интеграция aQsi Pill / T7100 в viwa-android (оплата картой)

> **Уровень:** Complex  
> **SessionId:** `wiva-aqsi-card-payment`  
> **Целевой проект:** `c:\viwa\viwa-android`

---

## Источники

| Тип | Путь / URL |
|-----|-----------|
| База знаний aQsi (сайт) | https://knowledge-base.aqsi.ru/pages/viewpage.action?pageId=54558724 |
| PDF-даташит Pill | `c:\viwa\docs\payment-aqsi-pill-t7100\исходники\pill.pdf` |
| Полный контент KB в MD | `c:\viwa\docs\payment-aqsi-pill-t7100\knowledge-base-aqsi-t7100-full.md` |
| Извлечённые PDF-протоколы | `c:\viwa\docs\payment-aqsi-pill-t7100\knowledge-base-aqsi-t7100-pdf-extracts.md` |
| Инвентарь KB (файлы) | `c:\viwa\docs\payment-aqsi-pill-t7100\knowledge-base-aqsi-t7100-inventory.md` |
| Исходники KB (raw) | `c:\viwa\docs\payment-aqsi-pill-t7100\исходники\knowledge-base-t7100\` |

---

## Контекст: текущий механизм оплаты картой в viwa-android

Сейчас оплата картой работает через цепочку:

```
DrinkListViewModel.startCardPayment()
  → PaymentTerminalService.sendSumToTerminal()     # команда 0x48 контроллеру
    → Controller (контроллер автомата)             # PAX-терминал через контроллер
      → ResponseCommand.PaymentSystemsPaxStatus (0x56)  # статус 4 = «оплата прошла»
        → ViwaTelemetryService.sendDemoSaleImportForE2e()
```

**Ключевые файлы существующего слоя:**

| Файл | Роль |
|------|------|
| `services/payment/PaymentTerminalService.kt` | Отправляет 0x48, слушает 0x56, статус `4` = успех |
| `hardware/controller/RequestCommand.kt` | Содержит `SendSumToPaymentTerminal(0x48)` |
| `hardware/controller/ResponseCommand.kt` | Содержит `PaymentSystemsPaxStatus(0x56)` |
| `ui/screens/customer/DrinkListViewModel.kt` | `startCardPayment()` / `paidTerminalThenPour()` |
| `domain/model/PaymentMethod.kt` | Enum: `NONE`, `SBP`, `CARD` |
| `data/local/db/JsonStoreKeys.kt` | Ключи локального хранилища настроек |
| `ui/screens/service/ViwaServiceMenuStructure.kt` | Группы/вкладки сервисного меню |
| `ui/screens/service/ViwaServiceMenuTabContent.kt` | UI вкладки `Equipment > Платёжник` |
| `di/IntegrationsModule.kt` | Hilt-модуль для интеграций (MAX, СБП, НК) |

---

## Что делаем aQsi Pill / T7100

Устройство aQsi Pill — физический NFC/EMV-ридер, подключается к Android по USB (USB-Ethernet, VCOM). Взаимодействие идёт по протоколу **Arcus2** (TCP/IP поверх USB-Ethernet или эмулированного COM). Прикладной уровень — **JPAY**: инициирует транзакцию напрямую с устройством, без контроллера автомата.

Ключевое отличие от текущего PAX-пути:

| Параметр | PAX (текущее) | aQsi Pill |
|----------|--------------|-----------|
| Транспорт | Контроллер → 0x48/0x56 | USB Ethernet / VCOM (TCP/IP) |
| Протокол | Бинарный проприетарный (контроллер) | Arcus2 (TCP) |
| Инициатор | Контроллер отдаёт сигнал | Приложение само открывает сессию |
| Статус | Код 4 в байте 0x56 | Ответ JPAY: одобрено / отклонено |
| Конфигурация | Не требует настройки в приложении | IP/порт, COM-порт, параметры Arcus2 |

Протокол aQsi Arcus2 описан в `knowledge-base-aqsi-t7100-pdf-extracts.md` (секция «aQsi protocol.pdf»). Основные команды для платёжной транзакции — `INIT_PAYMENT`, `CONFIRM_PAYMENT`, `CANCEL_PAYMENT` через Arcus2 TCP-сессию (порт по умолчанию 16107).

---

## Жёсткие правила реализации

> **Нарушение любого из этих правил — блокирующий дефект.**

1. **Не трогать контроллер.** Файлы `RequestCommand.kt`, `ResponseCommand.kt`, весь `hardware/controller/` — читаем только. Не добавлять новые команды.

2. **Не трогать PAX-слой.** `PaymentTerminalService.kt` — не изменять. Команда 0x48 и подписка на 0x56 должны работать ровно как сейчас.

3. **Не трогать СБП.** `DrinkListViewModel.startSbpPayment()`, репозитории SBP, вкладка «СБП» в сервисном меню — без изменений.

4. **Не изменять существующие сервисные вкладки.** `Equipment > Платёжник`, `Equipment > Контроллер`, `Integrations > СБП` — не менять их UI и логику.

5. **Вариативность только для оплаты картой.** SBP и бесплатный режим (`freeMode`) не затрагиваются механизмом переключения.

6. **Логирование — Timber.** Теги по образцу существующих: `TAG = "AqsiPayment"`, `TAG = "AqsiRepo"`, и т.д. Стиль: `Timber.tag(TAG).i(...)`, `Timber.tag(TAG).e(e, ...)`.

7. **DataStore / JsonStore — только новые ключи.** Добавлять только в `JsonStoreKeys` — без изменения существующих значений.

8. **DI — отдельный Hilt-модуль** `AqsiModule.kt`, не изменять `IntegrationsModule.kt`.

---

## Декомпозиция на подзадачи

### A1 — Модель и перечисление методов оплаты картой

**Что добавить:**

- Новый класс/sealed interface `CardPaymentMethod`:
  ```kotlin
  sealed interface CardPaymentMethod {
      data object Pax : CardPaymentMethod      // текущий PAX через контроллер 0x48
      data object Aqsi : CardPaymentMethod     // новый aQsi Pill по Arcus2
  }
  ```
- Ключ в `JsonStoreKeys`: `CARD_PAYMENT_METHOD = "cardPaymentMethod"` (строка `"PAX"` / `"AQSI"`, дефолт `"PAX"`).
- Репозиторий/UseCase для чтения и сохранения выбранного метода.

**DoD A1:** Модель компилируется, ключ добавлен, тест сохранения/чтения.

---

### A2 — Доменный слой aQsi: конфиг и репозиторий

**Что добавить:**

- `domain/model/AqsiConfig.kt` — data class с полями: `host: String`, `port: Int` (default 16107), `timeoutMs: Long` (default 15000).
- `domain/repository/AqsiRepository.kt` — интерфейс:
  - `suspend fun saveConfig(config: AqsiConfig)`
  - `suspend fun loadConfig(): AqsiConfig`
  - `suspend fun initiatePayment(amountKopecks: Int): Result<AqsiPaymentResult>`
  - `suspend fun cancelPayment(): Result<Unit>`
- `domain/model/AqsiPaymentResult.kt` — sealed: `Approved`, `Declined(reason: String)`, `Error(cause: Throwable)`.
- Ключ в `JsonStoreKeys`: `AQSI_SETTINGS = "aqsiSettings"` (JSON `AqsiConfig`).

**DoD A2:** Интерфейс и модели объявлены, проект собирается.

---

### A3 — Data-слой: Arcus2 TCP-клиент

**Что добавить:**

- `data/payment/aqsi/Arcus2Client.kt` — отвечает за TCP-соединение с ридером:
  - `connect(host, port, timeoutMs)` — устанавливает сокет.
  - `sendInitPayment(amountKopecks: Int)` — отправляет команду инициации транзакции по протоколу Arcus2.
  - `receivePaymentResult()` — читает ответ, парсит результат.
  - `sendCancel()` — команда отмены.
  - `disconnect()` — закрывает сокет.
- `data/payment/aqsi/AqsiRepositoryImpl.kt` — реализует `AqsiRepository`, использует `Arcus2Client`.
- Логирование: каждый этап (connect, send, receive, error, disconnect) — `Timber.tag("AqsiRepo")`.

> Протокол Arcus2 — см. `knowledge-base-aqsi-t7100-pdf-extracts.md`, раздел «aQsi protocol.pdf». Основные пакеты: заголовок (STX / LEN / CMD / DATA / ETX / CRC). Использовать `ByteArray` + `InputStream`/`OutputStream` поверх `java.net.Socket`.

**DoD A3:** `AqsiRepositoryImpl` реализует интерфейс. Unit-тест на парсинг ответного пакета Arcus2 (approve/decline) с mock-данными.

---

### A4 — DI: `AqsiModule`

**Что добавить:**

- `di/AqsiModule.kt` — `@Module @InstallIn(SingletonComponent::class)`:
  - `provideAqsiRepository(configRepository, scope) : AqsiRepository`
  - Любые дополнительные `@Named` зависимости, если нужны.
- **Не трогать** `IntegrationsModule.kt`.

**DoD A4:** Hilt-граф строится без ошибок, `assembleDebug` проходит.

---

### A5 — Оркестратор оплаты картой: `CardPaymentOrchestrator`

**Что добавить:**

- `services/payment/CardPaymentOrchestrator.kt` — `@Singleton`:
  - Зависимости: `PaymentTerminalService`, `AqsiRepository`, `CardPaymentMethodRepository`.
  - Метод `suspend fun pay(type, price, productNumber): CardPaymentResult`:
    - Читает текущий `CardPaymentMethod`.
    - Если `Pax` — делегирует в `PaymentTerminalService.sendSumToTerminal(...)` (существующий путь, без изменений).
    - Если `Aqsi` — вызывает `AqsiRepository.initiatePayment(amountKopecks)`, ждёт `AqsiPaymentResult`.
  - `fun cancel()` — делегирует в зависимости от активного метода.
- `domain/model/CardPaymentResult.kt` — sealed: `Success`, `Failed(reason)`, `Cancelled`.

**Изменения в `DrinkListViewModel`:**

- Метод `paidTerminalThenPour` — заменить прямой вызов `paymentTerminalService.sendSumToTerminal` на `cardPaymentOrchestrator.pay(...)`.
- SBP-ветка, free mode, mock controller — **не трогать**.
- Логика после успешной оплаты (налив, телеметрия) — **не изменять**.

> `PaymentTerminalService` остаётся без изменений. `CardPaymentOrchestrator` вызывает его как есть.

**DoD A5:** `startCardPayment` в ViewModel работает через оркестратор. PAX-путь верифицируется на моке контроллера. SBP-путь не затронут.

---

### A6 — Настройки aQsi: отдельный экран сервисного меню

**Что добавить:**

В `ViwaServiceMenuStructure.kt` — новая группа и вкладки (только добавление):

```kotlin
// В ViwaServiceGroupId:
data object CardPayment : ViwaServiceGroupId

// В ViwaServiceSubTabId:
data object CardPaymentMethod : ViwaServiceSubTabId   // выбор PAX / aQsi
data object AqsiSettings : ViwaServiceSubTabId        // host, port, timeout, тест соединения
data object AqsiDiagnostics : ViwaServiceSubTabId     // статус, лог последней транзакции

// В WivaServiceMenuGroups: добавить блок после Integrations:
WivaServiceGroupSpec(
    id = ViwaServiceGroupId.CardPayment,
    label = "Оплата картой",
    subTabs = listOf(
        WivaServiceSubTabSpec(ViwaServiceSubTabId.CardPaymentMethod, "Метод"),
        WivaServiceSubTabSpec(ViwaServiceSubTabId.AqsiSettings, "aQsi — Настройки"),
        WivaServiceSubTabSpec(ViwaServiceSubTabId.AqsiDiagnostics, "aQsi — Диагностика"),
    ),
)
```

**Вкладка «Метод»:**
- RadioGroup / SegmentedButton: PAX (текущий контроллер) / aQsi Pill.
- Сохраняется в `JsonStoreKeys.CARD_PAYMENT_METHOD`.
- Предупреждение: «aQsi: контроллер не задействован. PAX: через 0x48/0x56».

**Вкладка «aQsi — Настройки»:**
- Поля ввода: Host (IP или hostname), Port (default 16107), Timeout (мс).
- Кнопка «Сохранить» — сохраняет в `JsonStoreKeys.AQSI_SETTINGS`.
- Кнопка «Тест соединения» — `AqsiRepository.connect()` → показывает «Соединение OK» / ошибку с деталями.

**Вкладка «aQsi — Диагностика»:**
- Текущий выбранный метод.
- Статус последнего подключения (timestamp, результат).
- Лог последней транзакции (approve/decline/error с кодом Arcus2).
- Кнопка «Тест платежа» — тестовая транзакция с суммой 1 коп (только в dev-режиме / mock).

**DoD A6:** Все три вкладки отображаются в Rail, не ломают существующие группы. Настройки сохраняются и переживают перезапуск.

---

### A7 — Логирование и телеметрия

**Логирование (Timber):**

| Место | TAG | Что логировать |
|-------|-----|----------------|
| `CardPaymentOrchestrator` | `"CardPayment"` | Выбранный метод, старт/финиш, результат, ошибки |
| `AqsiRepositoryImpl` | `"AqsiRepo"` | Connect/disconnect, команды, ответы, ошибки сокета |
| `Arcus2Client` | `"Arcus2"` | Байты в/из сокета (только в debug-builds), парсинг пакетов |
| `AqsiSettingsViewModel` | `"AqsiSettings"` | Сохранение конфига, результат теста соединения |

Не изменять теги существующих классов (`"PaymentTerminal"` и т.д.).

**Телеметрия:**

- При успешной оплате через aQsi вызывать тот же `ViwaTelemetryService.sendDemoSaleImportForE2e()` (или финальный метод), что и PAX-путь.
- Метод в `SaleImportPaymentJson.method` = `"CARD"` (не менять существующую строку).
- Если в проекте появится различение по типу ридера — отдельный ADR, не в этой задаче.

**DoD A7:** `Timber.plant(Timber.DebugTree())` существующий. В logcat видны теги aQsi при прохождении платежа.

---

## Структура новых файлов

```
app/src/main/java/com/wiva/android/
├── domain/
│   ├── model/
│   │   ├── AqsiConfig.kt               # A2
│   │   ├── AqsiPaymentResult.kt        # A2
│   │   ├── CardPaymentMethod.kt        # A1
│   │   └── CardPaymentResult.kt        # A5
│   └── repository/
│       ├── AqsiRepository.kt           # A2
│       └── CardPaymentMethodRepository.kt  # A1
├── data/
│   ├── payment/
│   │   └── aqsi/
│   │       ├── Arcus2Client.kt         # A3
│   │       └── AqsiRepositoryImpl.kt   # A3
│   └── repository/
│       └── CardPaymentMethodRepositoryImpl.kt  # A1
├── services/
│   └── payment/
│       └── CardPaymentOrchestrator.kt  # A5
├── di/
│   └── AqsiModule.kt                   # A4
└── ui/
    └── screens/
        └── service/
            └── AqsiServiceTabContent.kt  # A6 — UI трёх вкладок
```

**Изменяемые существующие файлы:**

| Файл | Изменение |
|------|-----------|
| `data/local/db/JsonStoreKeys.kt` | `+CARD_PAYMENT_METHOD`, `+AQSI_SETTINGS` |
| `ui/screens/service/ViwaServiceMenuStructure.kt` | `+CardPayment` группа и 3 вкладки |
| `ui/screens/service/ViwaServiceMenuTabContent.kt` | `+when` ветки для трёх новых вкладок |
| `ui/screens/customer/DrinkListViewModel.kt` | `paidTerminalThenPour` → через `CardPaymentOrchestrator` |

---

## Что НЕ трогать (явный запрет)

```
hardware/controller/RequestCommand.kt       ← НЕ ИЗМЕНЯТЬ
hardware/controller/ResponseCommand.kt      ← НЕ ИЗМЕНЯТЬ
hardware/controller/ControllerGateway.kt    ← НЕ ИЗМЕНЯТЬ
services/payment/PaymentTerminalService.kt  ← НЕ ИЗМЕНЯТЬ
domain/model/PaymentMethod.kt              ← НЕ ИЗМЕНЯТЬ (enum NONE/SBP/CARD)
ui/screens/service/* (существующие вкладки) ← только добавление новых блоков
di/IntegrationsModule.kt                   ← НЕ ИЗМЕНЯТЬ
```

---

## Критерии приёмки

| ID | Критерий |
|----|---------|
| A1 | `CardPaymentMethod` объявлен, ключ в `JsonStoreKeys`, сохранение/чтение работает |
| A2 | `AqsiConfig`, `AqsiRepository` объявлены, проект собирается |
| A3 | `Arcus2Client` реализован; unit-тест парсинга пакетов проходит |
| A4 | `AqsiModule` добавлен в Hilt-граф, `assembleDebug` без ошибок |
| A5 | `CardPaymentOrchestrator` работает; PAX-путь верифицирован на моке; SBP не затронут |
| A6 | Группа «Оплата картой» с 3 вкладками видна в сервисном меню; настройки сохраняются |
| A7 | В logcat при карточной транзакции видны теги `CardPayment`, `AqsiRepo`, `Arcus2` |
| ✅ | Существующие вкладки Equipment, Integrations, SBP — без изменений |
| ✅ | Никаких изменений в `hardware/controller/` и `PaymentTerminalService.kt` |
| ✅ | `assembleDebug` после каждого подэтапа |

---

## Порядок выполнения подзадач

```
A1 (модель) → A2 (domain) → A3 (data/Arcus2) → A4 (DI) → A5 (оркестратор) → A6 (UI) → A7 (logging check)
```

Сборка после: A2, A4, A5, A6.  
MR: один MR на весь `wiva-aqsi-card-payment` в ветку `dev`.

---

## Нефункциональные требования

- Минимальный Android API: как в `build.gradle.kts` проекта (`minSdk`).
- `Arcus2Client` работает в `IO`-диспатчере, не блокирует Main.
- Тайм-аут соединения и транзакции — конфигурируемый (`AqsiConfig.timeoutMs`), не хардкодить.
- Обработка ошибок: сокет недоступен, таймаут, некорректный пакет — `Result.failure(...)`, логируется, не крашит приложение.
- Сохранение настроек — через `JsonStore` / `ConfigRepository` по образцу `SBP_SETTINGS`.

---

*Задача подготовлена для complex-пайплайна. Источники: [база знаний aQsi T7100](https://knowledge-base.aqsi.ru/pages/viewpage.action?pageId=54558724), PDF-даташит `pill.pdf`, `knowledge-base-aqsi-t7100-pdf-extracts.md`.*
