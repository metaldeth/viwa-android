package com.viwa.android.ui.screens.service

/**
 * Stable Compose [androidx.compose.ui.platform.testTag] values for service menu automation.
 *
 * With [androidx.compose.ui.semantics.testTagsAsResourceId] on [ServiceScreen], UiAutomator can
 * target nodes via `By.res("com.viwa.android:id/<tag>")` (package + tag as synthetic resource id).
 */
object ServiceMenuTestTags {
    const val SERVICE_MENU_ROOT = "service_menu_root"
    const val SERVICE_MENU_CLOSE = "service_menu_close"

    const val TELEMETRY_CONNECTION_ROOT = "telemetry_connection_root"
    const val TELEMETRY_CONNECTION_STATUS_CARD = "telemetry_connection_status_card"
    const val TELEMETRY_CONNECTION_STATUS_TEXT = "telemetry_connection_status_text"
    const val TELEMETRY_CONNECTION_RECONNECT = "telemetry_connection_reconnect"
    const val TELEMETRY_SERIAL_INPUT = "telemetry_serial_input"
    const val TELEMETRY_RESERVE_SERIAL = "telemetry_reserve_serial"
    const val TELEMETRY_REGISTER = "telemetry_register"
    const val TELEMETRY_AUTO_PROVISION = "telemetry_auto_provision"
    const val TELEMETRY_CONNECT_WS = "telemetry_connect_ws"
    const val TELEMETRY_DISCONNECT_WS = "telemetry_disconnect_ws"
    const val TELEMETRY_REBIND_CARD = "telemetry_rebind_card"
    const val TELEMETRY_REBIND_CONFIRM = "telemetry_rebind_confirm"
    const val TELEMETRY_REBIND_CANCEL = "telemetry_rebind_cancel"
    const val TELEMETRY_REG_KEY_INPUT = "telemetry_reg_key_input"
    const val TELEMETRY_QR_SCANNED_BANNER = "telemetry_qr_scanned_banner"
    const val TELEMETRY_BANNER = "telemetry_banner"
    const val TELEMETRY_BUSY = "telemetry_busy"

    const val TELEMETRY_ADDRESSES_ROOT = "telemetry_addresses_root"
    const val TELEMETRY_API_URL_INPUT = "telemetry_api_url_input"
    const val TELEMETRY_WS_URL_INPUT = "telemetry_ws_url_input"
    const val TELEMETRY_SAVE_ADDRESSES = "telemetry_save_addresses"

    const val CONTROLLER_PORT_ROOT = "controller_port_root"
    const val CONTROLLER_PORT_DROPDOWN = "controller_port_dropdown"
    const val CONTROLLER_PORT_CONNECT = "controller_port_connect"

    const val INVENTORY_ROOT = "service_inventory_root"
    const val INVENTORY_RECIPE_DIALOG = "service_inventory_recipe_dialog"
    const val INVENTORY_RECIPE_DRIFT_BADGE = "service_inventory_recipe_drift_badge"
    const val INVENTORY_RECIPE_BASE_VERSION = "service_inventory_recipe_base_version"
    const val INVENTORY_RECIPE_SYNC_STATUS = "service_inventory_recipe_sync_status"
    const val INVENTORY_RECIPE_EDIT_BUTTON = "service_inventory_recipe_edit_button"
    const val INVENTORY_RECIPE_EDIT_SAVE = "service_inventory_recipe_edit_save"
    const val INVENTORY_RECIPE_EDIT_CONFIRM = "service_inventory_recipe_edit_confirm"
    const val INVENTORY_RECIPE_EDIT_CONFIRM_BUTTON = "service_inventory_recipe_edit_confirm_button"
    const val INVENTORY_RECIPE_RESET_BUTTON = "service_inventory_recipe_reset_button"
    const val INVENTORY_RECIPE_RESET_CONFIRM = "service_inventory_recipe_reset_confirm"
    const val INVENTORY_RECIPE_RESET_CONFIRM_BUTTON = "service_inventory_recipe_reset_confirm_button"
    const val INVENTORY_TASTE_PICKER = "service_inventory_taste_picker"
    const val INVENTORY_TASTE_CONFIRM = "service_inventory_taste_confirm"
    const val INVENTORY_TASTE_CONFIRM_BUTTON = "service_inventory_taste_confirm_button"

    fun inventoryRecipeButtonTag(cellNumber: Int): String = "service_inventory_recipe_btn_$cellNumber"

    fun inventoryChangeTasteButtonTag(cellNumber: Int): String = "service_inventory_change_taste_btn_$cellNumber"

    fun inventoryTasteOptionTag(productUuid: String): String =
        "service_inventory_taste_option_${productUuid.replace("-", "_")}"

    fun serviceGroupTag(groupId: ViwaServiceGroupId): String =
        when (groupId) {
            ViwaServiceGroupId.Dashboard -> "service_group_dashboard"
            ViwaServiceGroupId.Telemetry -> "service_group_telemetry"
            ViwaServiceGroupId.Debug -> "service_group_debug"
            ViwaServiceGroupId.Integrations -> "service_group_integrations"
            ViwaServiceGroupId.CardPayment -> "service_group_card_payment"
            ViwaServiceGroupId.Equipment -> "service_group_equipment"
            ViwaServiceGroupId.Maintenance -> "service_group_maintenance"
            ViwaServiceGroupId.Settings -> "service_group_settings"
            ViwaServiceGroupId.Performance -> "service_group_performance"
            ViwaServiceGroupId.Metrics -> "service_group_metrics"
        }

    fun serviceSubTabTag(subTabId: ViwaServiceSubTabId): String =
        when (subTabId) {
            ViwaServiceSubTabId.DashboardOverview -> "service_subtab_dashboard_overview"
            ViwaServiceSubTabId.TelemetryConnection -> "service_subtab_telemetry_connection"
            ViwaServiceSubTabId.TelemetryAddresses -> "service_subtab_telemetry_addresses"
            ViwaServiceSubTabId.TelemetryWsLogs -> "service_subtab_telemetry_ws_logs"
            ViwaServiceSubTabId.TelemetryInventory -> "service_subtab_telemetry_inventory"
            ViwaServiceSubTabId.TelemetryTests -> "service_subtab_telemetry_tests"
            ViwaServiceSubTabId.DebugWsLogs -> "service_subtab_debug_ws_logs"
            ViwaServiceSubTabId.DebugController -> "service_subtab_debug_controller"
            ViwaServiceSubTabId.DebugSubscription -> "service_subtab_debug_subscription"
            ViwaServiceSubTabId.DebugKeyboardTest -> "service_subtab_debug_keyboard_test"
            ViwaServiceSubTabId.Sbp -> "service_subtab_sbp"
            ViwaServiceSubTabId.Nanokassa -> "service_subtab_nanokassa"
            ViwaServiceSubTabId.Max -> "service_subtab_max"
            ViwaServiceSubTabId.Devices -> "service_subtab_devices"
            ViwaServiceSubTabId.Ports -> "service_subtab_ports"
            ViwaServiceSubTabId.Controller -> "service_subtab_controller"
            ViwaServiceSubTabId.Payment -> "service_subtab_payment"
            ViwaServiceSubTabId.Scanner -> "service_subtab_scanner"
            ViwaServiceSubTabId.Rfid -> "service_subtab_rfid"
            ViwaServiceSubTabId.Inventory -> "service_subtab_inventory"
            ViwaServiceSubTabId.CalibrationSyrup -> "service_subtab_calibration_syrup"
            ViwaServiceSubTabId.CalibrationWater -> "service_subtab_calibration_water"
            ViwaServiceSubTabId.PreparingTime -> "service_subtab_preparing_time"
            ViwaServiceSubTabId.DevMode -> "service_subtab_dev_mode"
            ViwaServiceSubTabId.Idle -> "service_subtab_idle"
            ViwaServiceSubTabId.Window -> "service_subtab_window"
            ViwaServiceSubTabId.Theme -> "service_subtab_theme"
            ViwaServiceSubTabId.Updates -> "service_subtab_updates"
            ViwaServiceSubTabId.PerformanceGeneral -> "service_subtab_performance_general"
            ViwaServiceSubTabId.Animation -> "service_subtab_animation"
            ViwaServiceSubTabId.MetricsMemory -> "service_subtab_metrics_memory"
            ViwaServiceSubTabId.CardPaymentMethod -> "service_subtab_card_payment_method"
            ViwaServiceSubTabId.AqsiServiceSettings -> "service_subtab_aqsi_settings"
            ViwaServiceSubTabId.AqsiServiceDiagnostics -> "service_subtab_aqsi_diagnostics"
        }

    /** Every declared tag must be unique (UiAutomator resource-id collision). */
    val allDeclaredTags: Set<String> =
        setOf(
            SERVICE_MENU_ROOT,
            SERVICE_MENU_CLOSE,
            TELEMETRY_CONNECTION_ROOT,
            TELEMETRY_CONNECTION_STATUS_CARD,
            TELEMETRY_CONNECTION_STATUS_TEXT,
            TELEMETRY_CONNECTION_RECONNECT,
            TELEMETRY_SERIAL_INPUT,
            TELEMETRY_RESERVE_SERIAL,
            TELEMETRY_REGISTER,
            TELEMETRY_AUTO_PROVISION,
            TELEMETRY_CONNECT_WS,
            TELEMETRY_DISCONNECT_WS,
            TELEMETRY_REBIND_CARD,
            TELEMETRY_REBIND_CONFIRM,
            TELEMETRY_REBIND_CANCEL,
            TELEMETRY_REG_KEY_INPUT,
            TELEMETRY_QR_SCANNED_BANNER,
            TELEMETRY_BANNER,
            TELEMETRY_BUSY,
            TELEMETRY_ADDRESSES_ROOT,
            TELEMETRY_API_URL_INPUT,
            TELEMETRY_WS_URL_INPUT,
            TELEMETRY_SAVE_ADDRESSES,
            CONTROLLER_PORT_ROOT,
            CONTROLLER_PORT_DROPDOWN,
            CONTROLLER_PORT_CONNECT,
            INVENTORY_ROOT,
            INVENTORY_RECIPE_DIALOG,
            INVENTORY_RECIPE_DRIFT_BADGE,
            INVENTORY_RECIPE_BASE_VERSION,
            INVENTORY_RECIPE_SYNC_STATUS,
            INVENTORY_RECIPE_EDIT_BUTTON,
            INVENTORY_RECIPE_EDIT_SAVE,
            INVENTORY_RECIPE_EDIT_CONFIRM,
            INVENTORY_RECIPE_EDIT_CONFIRM_BUTTON,
            INVENTORY_RECIPE_RESET_BUTTON,
            INVENTORY_RECIPE_RESET_CONFIRM,
            INVENTORY_RECIPE_RESET_CONFIRM_BUTTON,
            INVENTORY_TASTE_PICKER,
            INVENTORY_TASTE_CONFIRM,
            INVENTORY_TASTE_CONFIRM_BUTTON,
        ) +
            ViwaServiceMenuGroups.map { serviceGroupTag(it.id) }.toSet() +
            ViwaServiceMenuGroups.flatMap { group -> group.subTabs.map { serviceSubTabTag(it.id) } }.toSet()
}
