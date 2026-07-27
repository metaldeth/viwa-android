package com.viwa.android.ui.screens.customer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viwa.android.services.telemetry.ViwaTelemetryService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Базовый URL client web для QR регистрации подписки (без orgId). */
private const val DEFAULT_SUBSCRIPTION_QR_BASE_URL = "http://dev.ishaker.ru:3005"

@HiltViewModel
class FreeDrinkOfferViewModel
    @Inject
    constructor(
        private val telemetryService: ViwaTelemetryService,
    ) : ViewModel() {
        private val _qrUrl = MutableStateFlow<String?>(null)
        val qrUrl: StateFlow<String?> = _qrUrl.asStateFlow()

        init {
            viewModelScope.launch {
                val reg = telemetryService.loadMachineRegistration()
                val serial = reg.serialNumber.trim()
                if (serial.isNotEmpty()) {
                    _qrUrl.value = "$DEFAULT_SUBSCRIPTION_QR_BASE_URL/m/$serial/auth"
                }
            }
        }
    }
