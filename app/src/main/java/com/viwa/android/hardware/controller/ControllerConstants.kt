package com.viwa.android.hardware.controller

/**ts` / таймингов `ControllerConnection` / `HardwareManager`. */
object ControllerConstants {
    const val MOCK_PORT_PREFIX = "MOCK_"

 /** Как `MOCK_CONTROLLER` в SerialPortConfigService. */
    const val MOCK_CONTROLLER_PATH = "MOCK_CONTROLLER"

    val DEFAULT_BODY: ByteArray = byteArrayOf(0, 0, 0, 0, 0)

 /**
 * Таймаут на первый байт с контроллера после открытия порта; 0 = не закрывать соединение по этому таймеру.
 * (Раньше 20 с — ожидание «просыпания» железа.)
 */
    const val ALIVE_CHECK_TIMEOUT_MS = 0L
 /** Как эталон G1: имитация ~20 мл/с после задержки BEGIN. */
    const val MOCK_WATER_POUR_SPEED_ML_PER_SEC = 20.0
    const val MOCK_BEGIN_DELAY_MS = 1_000L
    const val MOCK_ACK_DELAY_MS = 50L

 /** Задержка перед первым [ControllerHardwareManager.initializeFromConfig]; 0 = подключаться сразу при старте. */
    const val CONTROLLER_STARTUP_DELAY_MS = 0L
    const val EXPECTED_MODE_AUTO = 1

    /** Short wait for WaterCounterAnswer; on timeout do not reset (avoid silent ml loss). */
    const val WATER_COUNTER_TIMEOUT_MS = 100L

 /** Как `START_DRINK_PREPARING_BODY` в DrinkPreparingService.ts */
    val START_DRINK_PREPARING_BODY: ByteArray = byteArrayOf(0, 0, 1, 1, 0)
}
