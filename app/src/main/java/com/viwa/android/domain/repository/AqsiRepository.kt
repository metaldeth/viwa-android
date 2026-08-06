package com.viwa.android.domain.repository

import com.viwa.android.domain.model.AqsiConfig
import com.viwa.android.domain.model.AqsiPaymentResult

/**
 * Доступ к настройке и операциям ридера aQsi. Конфиг читается/пишется в JsonStore —
 * ключ [com.viwa.android.data.local.db.JsonStoreKeys.AQSI_SETTINGS].
 *
 * Production card flow: USB Arcus2 on the assigned Pill via [initiatePayment] /
 * [cancelPayment] (see [com.viwa.android.data.payment.aqsi.AqsiUsbPaymentManager]).
 */
interface AqsiRepository {

    suspend fun loadConfig(): AqsiConfig

    suspend fun saveConfig(config: AqsiConfig)

    /**
     * Service-menu connectivity probe: runs a real 1 ₽ Arcus2 [testPayment] on the assigned
     * USB Pill (not a standalone TCP/JPAY customer path).
     */
    suspend fun testTcpConnection(): Result<Unit>

    suspend fun initiatePayment(amountKopecks: Int): Result<AqsiPaymentResult>

    suspend fun cancelPayment(): Result<Unit>
}
