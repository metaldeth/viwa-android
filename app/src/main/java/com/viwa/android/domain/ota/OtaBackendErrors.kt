package com.viwa.android.domain.ota

import com.viwa.android.data.remote.telemetry.mvp.TokenAuthException
import java.io.IOException

internal object OtaBackendErrors {
    fun isTransient(error: Throwable): Boolean {
        when (error) {
            is OtaDownloadIntegrityException,
            is OtaApkVerificationError,
            -> return false
            is OtaHttpException -> return error.statusCode >= 500
            is TokenAuthException -> {
                val code = OtaHttpException.parseStatusCode(error.message.orEmpty())
                return code != null && code >= 500
            }
            is OtaDownloadTransportException,
            is IOException,
            -> return true
        }
        if (error.message.orEmpty().contains("Machine JWT unavailable", ignoreCase = true)) return true
        OtaHttpException.parseStatusCode(error.message.orEmpty())?.let { return it >= 500 }
        return false
    }

    fun userMessage(error: Throwable): String =
        when {
            error is OtaHttpException &&
                error.statusCode == 404 &&
                error.message.contains(OtaHttpException.PUBLIC_OTA_CHECK_UNAVAILABLE, ignoreCase = true) ->
                "Публичный endpoint обновлений недоступен. Сервер обновлений ещё не обновлён."
            error.message.orEmpty().contains("Machine JWT unavailable", ignoreCase = true) ->
                "Сервер временно недоступен. Повторим проверку обновлений автоматически."
            isTransient(error) ->
                "Сервер обновлений временно недоступен. Повторим позже."
            else -> error.message ?: "Ошибка проверки обновления"
        }
}
