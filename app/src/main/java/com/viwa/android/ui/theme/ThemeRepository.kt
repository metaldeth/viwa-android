package com.viwa.android.ui.theme

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.ui.screens.customer.ViwaCustomerUiTokens
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class ThemeRepository
@Inject
constructor(
    private val configRepository: ConfigRepository,
) {
    private val _isDark = MutableStateFlow(true)
    val isDark: StateFlow<Boolean> = _isDark.asStateFlow()

    private val _customerPrimaryLightArgb =
        MutableStateFlow(ViwaCustomerUiTokens.DefaultBrandPrimaryArgb)
    private val _customerPrimaryDarkArgb =
        MutableStateFlow(ViwaCustomerUiTokens.DefaultBrandPrimaryArgbDark)

    val customerPrimaryLightArgb: StateFlow<Int> = _customerPrimaryLightArgb.asStateFlow()
    val customerPrimaryDarkArgb: StateFlow<Int> = _customerPrimaryDarkArgb.asStateFlow()

    suspend fun init() {
        // Нет ключа → тёмная (дефолт автомата). Явный "false" → светлая (выбор в сервисе).
        _isDark.value = configRepository.get(JsonStoreKeys.THEME_IS_DARK) != "false"
        loadCustomerPrimaryArgbFromStore()
    }

    suspend fun setIsDark(dark: Boolean) {
        _isDark.value = dark
        configRepository.set(JsonStoreKeys.THEME_IS_DARK, if (dark) "true" else "false")
    }

 /** Обновление UI при движении ползунка (без записи в БД) — для активной темы. */
    fun setCustomerPrimaryButtonArgbPreview(argb: Int) {
        if (_isDark.value) {
            _customerPrimaryDarkArgb.value = argb
        } else {
            _customerPrimaryLightArgb.value = argb
        }
    }

    suspend fun persistCustomerPrimaryButtonArgb() {
        if (_isDark.value) {
            configRepository.set(
                JsonStoreKeys.CUSTOMER_PRIMARY_BUTTON_ARGB_DARK,
                argbToHex8(_customerPrimaryDarkArgb.value),
            )
        } else {
            configRepository.set(
                JsonStoreKeys.CUSTOMER_PRIMARY_BUTTON_ARGB_LIGHT,
                argbToHex8(_customerPrimaryLightArgb.value),
            )
        }
    }

    suspend fun resetCustomerPrimaryButtonArgbToDefault() {
        _customerPrimaryLightArgb.value = ViwaCustomerUiTokens.DefaultBrandPrimaryArgb
        _customerPrimaryDarkArgb.value = ViwaCustomerUiTokens.DefaultBrandPrimaryArgbDark
        configRepository.delete(JsonStoreKeys.CUSTOMER_PRIMARY_BUTTON_ARGB_LIGHT)
        configRepository.delete(JsonStoreKeys.CUSTOMER_PRIMARY_BUTTON_ARGB_DARK)
        configRepository.delete(JsonStoreKeys.CUSTOMER_PRIMARY_BUTTON_ARGB)
    }

    private suspend fun loadCustomerPrimaryFromKey(key: String): Int? {
        val raw = configRepository.get(key)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return parseArgbHex8(raw)
    }

    private suspend fun loadCustomerPrimaryArgbFromStore() {
        val legacy = loadCustomerPrimaryFromKey(JsonStoreKeys.CUSTOMER_PRIMARY_BUTTON_ARGB)
        val light = loadCustomerPrimaryFromKey(JsonStoreKeys.CUSTOMER_PRIMARY_BUTTON_ARGB_LIGHT)
        val dark = loadCustomerPrimaryFromKey(JsonStoreKeys.CUSTOMER_PRIMARY_BUTTON_ARGB_DARK)
        val defaultLight = ViwaCustomerUiTokens.DefaultBrandPrimaryArgb
        val defaultDark = ViwaCustomerUiTokens.DefaultBrandPrimaryArgbDark
        _customerPrimaryLightArgb.value = light ?: legacy ?: defaultLight
        _customerPrimaryDarkArgb.value = dark ?: legacy ?: defaultDark
    }

    private companion object {
        fun argbToHex8(argb: Int): String =
            argb.toUInt().toString(16).padStart(8, '0').uppercase()

        fun parseArgbHex8(stored: String): Int? {
            val t = stored.trim().removePrefix("#").uppercase()
            if (t.length != 8) return null
            return try {
                t.toULong(16).toInt()
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
