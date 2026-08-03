package com.viwa.android.data.local.db

object JsonStoreKeys {
    const val UPDATE_SERVER_HOST = "updateServerHost"
 /** Строка `"true"` / `"false"` для переключателя мока контроллера. */
    const val USE_MOCK_CONTROLLER = "useMockController"

 /** [android.hardware.usb.UsbDevice.getDeviceName] выбранного UART (например `/dev/bus/usb/001/005`). */
    const val CONTROLLER_USB_DEVICE_PATH = "controllerUsbDevicePath"

 /** Serial-порт тестового RFID-ридера в сервисном меню. */
    const val RFID_READER_DEVICE_PATH = "rfidReaderDevicePath"

 /** JSON `Map<deviceName, PortRole.name>` — роли USB-serial,. */
    const val PORT_ASSIGNMENTS = "portAssignments"

 /**
 * Как `freeMode`.
 * По умолчанию (ключ отсутствует) — `true`, чтобы happy-path на эмуляторе без терминала.
 */
    const val DEV_FREE_MODE = "devFreeMode"

    const val SBP_SETTINGS = "sbpSettings"

 /** Строка [com.viwa.android.domain.model.CardPaymentMethod] в хранилище: `"PAX"` | `"AQSI"`. */
    const val CARD_PAYMENT_METHOD = "cardPaymentMethod"

 /** Строка [com.viwa.android.domain.model.CardPaymentMockMode]: `"OFF"` | `"TWOCAN"` | `"AQSI"`. */
    const val CARD_PAYMENT_MOCK_MODE = "cardPaymentMockMode"

 /** Строка [com.viwa.android.domain.model.CardPaymentMockOutcome] для тестового исхода mock-оплаты. */
    const val CARD_PAYMENT_MOCK_OUTCOME = "cardPaymentMockOutcome"

 /** JSON [com.viwa.android.domain.model.AqsiConfig] — TCP host/port/timeout к ридеру aQsi. */
    const val AQSI_SETTINGS = "aqsiSettings"

    const val MAX_SETTINGS = "maxSettings"
    const val NANOKASSA_SETTINGS = "nanokassaSettings"
 /** JSON [com.viwa.android.domain.model.MachineRegistration] — серийник для NanoKassa и т.д. */
    const val MACHINE_REGISTRATION = "machineRegistration"
 /** JSON [com.viwa.android.domain.model.TelemetryConfig] — URL API/WS/Keycloak (. */
    const val TELEMETRY_CONFIG = "telemetryConfig"
 /** `"true"` — отправлять capabilities pingPong при подключении WS; по умолчанию выключено. */
    /** JSON Map<Int, Double> — conversionFactor по номеру ячейки (калибровка сиропов). */
    const val SYRUP_CONVERSION_FACTORS = "syrupConversionFactors"

 /** `"true"` — пользователь нажал «Отключить WS» в текущей сессии; при холодном старте сбрасывается. */
    const val TELEMETRY_PAUSED_BY_USER = "telemetryPausedByUser"

 /** Плоская база ингредиентов после baseIngredientRequestExportTopic. */
    const val TELEMETRY_BASE_INGREDIENTS = "telemetryBaseIngredients"

 /** Матрица наполнения cellStore* (как `cellStoreMatrix`). */
    const val TELEMETRY_CELL_STORE_MATRIX = "telemetryCellStoreMatrix"

 /** Итог merge базы + матрицы (как `config` в WIVA_config.db). */
    const val TELEMETRY_MERGED_INVENTORY = "telemetryMergedInventory"

 /** JSON [com.viwa.android.domain.model.TelemetryCellsSnapshot] — MVP flat cells + products catalog. */
    const val TELEMETRY_CELLS_SNAPSHOT = "telemetryCellsSnapshot"

 /** "true"/отсутствует = тёмная (по умолчанию), "false" = светлая. */
    const val THEME_IS_DARK = "themeIsDark"

 /**
 * Устаревший одиночный ключ: при миграции копируется в светлую и тёмную, если те пусты.
 * @see CUSTOMER_PRIMARY_BUTTON_ARGB_LIGHT
 * @see CUSTOMER_PRIMARY_BUTTON_ARGB_DARK
 */
    const val CUSTOMER_PRIMARY_BUTTON_ARGB = "customerPrimaryButtonArgb"

 /** ARGB брендового акцента (кнопка «Налить воду», шкала подписки, `primary` в клиентской теме) — светлая тема. */
    const val CUSTOMER_PRIMARY_BUTTON_ARGB_LIGHT = "customerPrimaryButtonArgbLight"

 /** То же для тёмной темы. */
    const val CUSTOMER_PRIMARY_BUTTON_ARGB_DARK = "customerPrimaryButtonArgbDark"

 /** JSON [com.viwa.android.domain.model.WaterCalibrationData] — калибровка воды (G1). */
    const val WATER_CALIBRATION = "water_calibration"

 /** Накопленный расход воды (мл), строка с double — как `WATER_USAGE_ML_KEY`. */
    const val WATER_USAGE_ML = "water_usage_ml"

 /** JSON-массив фактических замеров готовки (история для сервисного экрана времени). */
    const val PREPARING_TIME_HISTORY = "preparing_time_history"

 /** JSON-массив [com.viwa.android.data.local.sales.PendingSale] — durable uplink sale.report. */
    const val PENDING_SALES = "pending_sales"

    /** `"true"` после одноразового импорта [JsonStoreKeys.PENDING_SALES] в Room `machine_outbox`. */
    const val OUTBOX_PENDING_SALES_IMPORTED = "outbox_pending_sales_imported_v1"

    /** Last seen monotonic revision for offline grants delta sync. */
    const val OFFLINE_GRANTS_DELTA_CURSOR = "offline_grants_delta_cursor"

 /**
 * Авто-выход с экрана готовки через N минут (строка с int). `0` — только ручной/секретный выход.
 * Ключ отсутствует — **5** минут.
 */
    const val PREPARING_AUTO_EXIT_MINUTES = "preparing_auto_exit_minutes"

 /** Размер окна последних готовок для расчёта адаптивной скорости налива (1.20). */
    const val PREPARING_FLOW_WINDOW_SIZE = "preparing_flow_window_size"

 /**
 * JSON-список id включённых видео скринсейвера.
 * Пустой список = ожидание отключено полностью.
 * Отсутствует = все 14 видео включены (дефолт).
 */
    const val IDLE_ENABLED_VIDEOS = "idleEnabledVideoIds"

 /**
 * Вариант анимации основной кнопки на экране напитков ([PrimaryButtonPulseStyle.storageKey]).
 * Выбор — вкладка «Производительность → Анимации».
 */
    const val PRIMARY_BUTTON_PULSE_STYLE = "primaryButtonPulseStyle"

 /**
 * `"true"` — режим отладки подписки включён: на экране напитков показывается FAB
 * с доступом к WS и controller-логам; в Дебаг → Подписка доступна кнопка ручной отправки
 * statusSubscribeTopic.
 */
    const val SUBSCRIPTION_DEBUG_MODE = "subscriptionDebugMode"

 /**
 * ARGB ленты Flow-станции (команда SetFlowRgb 0xD2), строка из 8 hex-цифр как у брендового цвета
 * ([CUSTOMER_PRIMARY_BUTTON_ARGB_LIGHT]).
 */
    const val FLOW_STRIP_RGB_ARGB = "flowStripRgbArgb"

    /** JSON [PersistedAppUpdateState] — OTA coordinator persistence across process restart. */
    /** Highest accepted OTA manifest revocation epoch (anti-rollback). */
    const val OTA_TRUSTED_REVOCATION_EPOCH = "otaTrustedRevocationEpoch"

    const val OTA_UPDATE_STATE = "otaUpdateState"

    /** `"true"` — explicit client flag to enforce server mandatory updates. */
    const val OTA_MANDATORY_ENFORCEMENT = "otaMandatoryEnforcement"

    /** `"true"` — allow legacy HTTP version.json flow (debug/fallback only). */
    const val OTA_LEGACY_HTTP_FALLBACK = "otaLegacyHttpFallback"
}
