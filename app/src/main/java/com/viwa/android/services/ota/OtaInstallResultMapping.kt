package com.viwa.android.services.ota

import android.content.Intent
import android.content.pm.PackageInstaller

data class OtaInstallResultAction(
    val deliverToHandler: Boolean,
    val handlerStatus: Int,
    val handlerMessage: String?,
    val confirmationIntent: Intent?,
)

object OtaInstallResultMapping {
    const val MISSING_CONFIRMATION_MESSAGE = "Не удалось открыть подтверждение установки"

    fun mapStatus(
        status: Int,
        message: String?,
        confirmationIntent: Intent?,
    ): OtaInstallResultAction {
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (confirmationIntent != null) {
                return OtaInstallResultAction(
                    deliverToHandler = false,
                    handlerStatus = status,
                    handlerMessage = message,
                    confirmationIntent = confirmationIntent,
                )
            }
            return OtaInstallResultAction(
                deliverToHandler = true,
                handlerStatus = PackageInstaller.STATUS_FAILURE,
                handlerMessage = message ?: MISSING_CONFIRMATION_MESSAGE,
                confirmationIntent = null,
            )
        }
        return OtaInstallResultAction(
            deliverToHandler = true,
            handlerStatus = status,
            handlerMessage = message,
            confirmationIntent = null,
        )
    }
}
