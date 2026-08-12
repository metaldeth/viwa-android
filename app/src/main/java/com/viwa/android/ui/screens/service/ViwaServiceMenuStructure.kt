package com.viwa.android.ui.screens.service

/** Группы и подвкладки сервисного меню (rail + горизонтальные табы). */

sealed interface ViwaServiceGroupId {
    /** Сводка по автомату: ячейки, связь, вода. */
    data object Dashboard : ViwaServiceGroupId

    data object Telemetry : ViwaServiceGroupId

    /** Логи сети и дебаг контроллера. */
    data object Debug : ViwaServiceGroupId

    data object Integrations : ViwaServiceGroupId

    /** Настройка карточного метода — только runtime, не в rail. */
    data object CardPayment : ViwaServiceGroupId

    data object Equipment : ViwaServiceGroupId

    data object Maintenance : ViwaServiceGroupId

    data object Settings : ViwaServiceGroupId

    data object Performance : ViwaServiceGroupId

    data object Metrics : ViwaServiceGroupId
}

sealed interface ViwaServiceSubTabId {
    data object DashboardOverview : ViwaServiceSubTabId

    data object TelemetryConnection : ViwaServiceSubTabId

    data object TelemetryAddresses : ViwaServiceSubTabId

    /** Перенесено в группу «Дебаг». */
    data object TelemetryWsLogs : ViwaServiceSubTabId

    /** Итог merge базы + матрицы. */
    data object TelemetryInventory : ViwaServiceSubTabId

    data object TelemetryTests : ViwaServiceSubTabId

    data object DebugWsLogs : ViwaServiceSubTabId

    data object DebugController : ViwaServiceSubTabId

    data object DebugSubscription : ViwaServiceSubTabId

    /** Тест ViwaNumericKeyboard / ViwaAlphanumericKeyboard без IME. */
    data object DebugKeyboardTest : ViwaServiceSubTabId

    data object Sbp : ViwaServiceSubTabId

    data object Nanokassa : ViwaServiceSubTabId

    data object Max : ViwaServiceSubTabId

    data object Controller : ViwaServiceSubTabId

    /** Главная вкладка «Устройства» (shaker hybrid Devices). */
    data object Devices : ViwaServiceSubTabId

    /** Сканирование UART / назначение контроллера. */
    data object Ports : ViwaServiceSubTabId

    data object Payment : ViwaServiceSubTabId

    data object Scanner : ViwaServiceSubTabId

    data object Rfid : ViwaServiceSubTabId

    data object Inventory : ViwaServiceSubTabId

    data object CalibrationSyrup : ViwaServiceSubTabId

    data object CalibrationWater : ViwaServiceSubTabId

    data object PreparingTime : ViwaServiceSubTabId

    data object DevMode : ViwaServiceSubTabId

    data object Idle : ViwaServiceSubTabId

    data object Window : ViwaServiceSubTabId

    data object Theme : ViwaServiceSubTabId

    /** OTA обновление приложения (telemetry app-releases). */
    data object Updates : ViwaServiceSubTabId

    data object PerformanceGeneral : ViwaServiceSubTabId

    data object Animation : ViwaServiceSubTabId

    data object MetricsMemory : ViwaServiceSubTabId

    /** Вкладка «Метод» — aQsi USB (единственный карточный метод). */
    data object CardPaymentMethod : ViwaServiceSubTabId

    data object AqsiServiceSettings : ViwaServiceSubTabId

    data object AqsiServiceDiagnostics : ViwaServiceSubTabId
}

data class ViwaServiceSubTabSpec(
    val id: ViwaServiceSubTabId,
    val label: String,
)

data class ViwaServiceGroupSpec(
    val id: ViwaServiceGroupId,
    val label: String,
    val subTabs: List<ViwaServiceSubTabSpec>,
)

val ViwaServiceMenuGroups: List<ViwaServiceGroupSpec> =
    listOf(
        ViwaServiceGroupSpec(
            id = ViwaServiceGroupId.Maintenance,
            label = "Обслуживание",
            subTabs =
                listOf(
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.Inventory, "Остатки"),
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.CalibrationSyrup, "Калибровка сиропов"),
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.CalibrationWater, "Калибровка воды"),
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.PreparingTime, "Время готовки"),
                ),
        ),
        ViwaServiceGroupSpec(
            id = ViwaServiceGroupId.Dashboard,
            label = "Дашборд",
            subTabs =
                listOf(
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.DashboardOverview, "Обзор"),
                ),
        ),
        ViwaServiceGroupSpec(
            id = ViwaServiceGroupId.Telemetry,
            label = "Телеметрия",
            subTabs =
                listOf(
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.TelemetryConnection, "Подключение"),
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.TelemetryAddresses, "Адреса"),
                ),
        ),
        ViwaServiceGroupSpec(
            id = ViwaServiceGroupId.Debug,
            label = "Дебаг",
            subTabs =
                listOf(
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.DebugWsLogs, "Логи сети"),
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.DebugController, "Дебаг контроллера"),
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.DebugSubscription, "Подписка"),
                ),
        ),
        ViwaServiceGroupSpec(
            id = ViwaServiceGroupId.Integrations,
            label = "Интеграции",
            subTabs =
                listOf(
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.Sbp, "СБП"),
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.Nanokassa, "Нанокасса"),
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.Max, "MAX"),
                ),
        ),
        ViwaServiceGroupSpec(
            id = ViwaServiceGroupId.Equipment,
            label = "Устройства",
            subTabs =
                listOf(
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.Devices, "Устройства"),
                ),
        ),
        ViwaServiceGroupSpec(
            id = ViwaServiceGroupId.Settings,
            label = "Настройки",
            subTabs =
                listOf(
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.DevMode, "Режим разработки"),
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.Idle, "Ожидание"),
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.Window, "Окно"),
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.Theme, "Тема"),
                    ViwaServiceSubTabSpec(ViwaServiceSubTabId.Updates, "Обновления"),
                ),
        ),
    )

fun findViwaServiceGroup(id: ViwaServiceGroupId): ViwaServiceGroupSpec =
    ViwaServiceMenuGroups.firstOrNull { it.id == id }
        ?: error("Unknown ViwaServiceGroupId: $id")

fun ViwaServiceGroupSpec.subTabIndexOf(subTabId: ViwaServiceSubTabId?): Int {
    if (subTabId == null) return -1
    return subTabs.indexOfFirst { it.id == subTabId }
}
