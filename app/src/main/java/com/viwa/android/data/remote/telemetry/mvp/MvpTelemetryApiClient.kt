package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.network.redactNetworkPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

class SerialAlreadyBoundException(
    val serialNumber: String,
    message: String = "Serial $serialNumber already bound to another installation",
) : Exception(message)

class RebindNotAllowedException(
    message: String =
        "Перепривязка запрещена: serial уже привязан к другой плате. " +
            "Попросите MASTER/ADMIN разрешить rebind на 15 минут в веб-панели " +
            "или используйте «Подключить WS», если автомат уже зарегистрирован на этой плате.",
) : Exception(message)

class RegistrationKeyException(
    val code: String,
    message: String,
) : Exception(message) {
    companion object {
        fun forCode(code: String): RegistrationKeyException =
            RegistrationKeyException(
                code,
                when (code) {
                    "REG_KEY_ALREADY_USED" ->
                        "Ключ регистрации уже использован. " +
                            "Если автомат уже зарегистрирован на этой плате — нажмите «Подключить WS» " +
                            "(используется сохранённый machineSecret)."
                    "REG_KEY_EXPIRED" ->
                        "Срок действия ключа регистрации истёк. Выпустите новый ключ в веб-панели."
                    "REG_KEY_REVOKED" ->
                        "Ключ регистрации отозван. Выпустите новый ключ в веб-панели."
                    "REG_KEY_SERIAL_MISMATCH" ->
                        "Ключ регистрации не подходит к указанному серийному номеру."
                    "REG_KEY_INVALID" ->
                        "Неверный ключ регистрации. Проверьте формат REG-… и значение из веб-панели."
                    else -> "Ошибка ключа регистрации ($code)."
                },
            )
    }
}

class MissingEnrollmentKeyException(
    message: String =
        "MVP enrollment key не задан. " +
            "Добавьте telemetry.enrollmentKey в local.properties " +
            "или VIWA_TELEMETRY_ENROLLMENT_KEY в окружение и пересоберите APK.",
) : Exception(message)

class TokenAuthException(
    message: String = "Не удалось получить JWT для WebSocket",
) : Exception(message)

class MvpTelemetryApiClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val enrollmentKeyProvider: () -> String,
    private val factoryProvisionKeyProvider: () -> String = { FactoryProvisionKey.reveal() },
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun provision(
        baseUrl: String,
        requestBody: ProvisionRequestDto,
    ): Result<RegisterResponseDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.encodeToString(ProvisionRequestDto.serializer(), requestBody)
                val request =
                    Request.Builder()
                        .url("${baseUrl.trimEnd('/')}/api/v1/machines/provision")
                        .post(body.toRequestBody(jsonMediaType))
                        .header("Content-Type", "application/json")
                        .header("X-Factory-Provision-Key", factoryProvisionKeyProvider())
                        .build()
                executeRegister(request, requestBody.installationId)
            }
        }

    suspend fun register(
        baseUrl: String,
        requestBody: RegisterRequestDto,
    ): Result<RegisterResponseDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.encodeToString(RegisterRequestDto.serializer(), requestBody)
                val request =
                    Request.Builder()
                        .url("${baseUrl.trimEnd('/')}/api/v1/machines/register")
                        .post(body.toRequestBody(jsonMediaType))
                        .header("Content-Type", "application/json")
                        .build()
                executeRegister(request, requestBody.serialNumber)
            }
        }

    suspend fun fetchToken(
        baseUrl: String,
        tokenEndpoint: String,
        requestBody: TokenRequestDto,
    ): Result<TokenResponseDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.encodeToString(TokenRequestDto.serializer(), requestBody)
                val url =
                    if (tokenEndpoint.startsWith("http")) {
                        tokenEndpoint
                    } else {
                        "${baseUrl.trimEnd('/')}/${tokenEndpoint.trimStart('/')}"
                    }
                val request =
                    Request.Builder()
                        .url(url)
                        .post(body.toRequestBody(jsonMediaType))
                        .header("Content-Type", "application/json")
                        .build()
                executeJson(request, TokenResponseDto.serializer())
            }
        }

    suspend fun reserveSerial(
        baseUrl: String,
        installationId: String?,
    ): Result<ReserveSerialResponseDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireEnrollmentKey()
                val body =
                    json.encodeToString(
                        ReserveSerialRequestDto(
                            installationId = installationId?.takeIf { it.isNotBlank() },
                        ),
                    )
                val request =
                    Request.Builder()
                        .url("${baseUrl.trimEnd('/')}/api/v1/machines/serials/reserve")
                        .post(body.toRequestBody(jsonMediaType))
                        .header("Content-Type", "application/json")
                        .header("X-Enrollment-Key", enrollmentKeyProvider())
                        .build()
                executeJson(request, ReserveSerialResponseDto.serializer())
            }
        }

    suspend fun enroll(
        baseUrl: String,
        requestBody: EnrollRequestDto,
    ): Result<EnrollResponseDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireEnrollmentKey()
                val body = json.encodeToString(EnrollRequestDto.serializer(), requestBody)
                val request =
                    Request.Builder()
                        .url("${baseUrl.trimEnd('/')}/api/v1/machines/enroll")
                        .post(body.toRequestBody(jsonMediaType))
                        .header("Content-Type", "application/json")
                        .header("X-Enrollment-Key", enrollmentKeyProvider())
                        .build()
                executeEnroll(request, requestBody.serialNumber)
            }
        }

    private fun requireEnrollmentKey() {
        if (enrollmentKeyProvider().trim().isBlank()) {
            throw MissingEnrollmentKeyException()
        }
    }

    private fun executeRegister(request: Request, serialNumber: String): RegisterResponseDto {
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val errorCode = EnrollConflictCodeParser.parseCode(json, text)
            if (response.code == 403) {
                when (errorCode) {
                    "REBIND_NOT_ALLOWED" -> throw RebindNotAllowedException()
                    else -> error("HTTP 403: ${redactApiLog(text)}")
                }
            }
            if (response.code == 409) {
                when (errorCode) {
                    "SERIAL_ALREADY_BOUND" -> throw SerialAlreadyBoundException(serialNumber)
                    "REG_KEY_ALREADY_USED", "REG_KEY_REVOKED", "REG_KEY_EXPIRED" ->
                        throw RegistrationKeyException.forCode(errorCode!!)
                    else -> error("HTTP 409: $text")
                }
            }
            if (response.code == 422 && errorCode?.startsWith("REG_KEY_") == true) {
                throw RegistrationKeyException.forCode(errorCode)
            }
            if (!response.isSuccessful) {
                Timber.w("MvpTelemetry register HTTP ${response.code}: ${redactApiLog(text)}")
                error("HTTP ${response.code}: ${redactApiLog(text)}")
            }
            return json.decodeFromString(RegisterResponseDto.serializer(), text)
        }
    }

    private fun <T> executeJson(
        request: Request,
        deserializer: kotlinx.serialization.KSerializer<T>,
    ): T {
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Timber.w("MvpTelemetry API ${response.code}: ${redactApiLog(text)}")
                if (response.code == 401 || response.code == 403) {
                    throw TokenAuthException("HTTP ${response.code}")
                }
                error("HTTP ${response.code}: ${redactApiLog(text)}")
            }
            return json.decodeFromString(deserializer, text)
        }
    }

    private fun executeEnroll(request: Request, serialNumber: String): EnrollResponseDto {
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 403) {
                when (EnrollConflictCodeParser.parseCode(json, text)) {
                    "REBIND_NOT_ALLOWED" -> throw RebindNotAllowedException()
                    else -> error("HTTP 403: ${redactApiLog(text)}")
                }
            }
            if (response.code == 409) {
                when (EnrollConflictCodeParser.parseCode(json, text)) {
                    "SERIAL_ALREADY_BOUND" -> throw SerialAlreadyBoundException(serialNumber)
                    else -> error("HTTP 409: $text")
                }
            }
            if (!response.isSuccessful) {
                Timber.w("MvpTelemetry enroll HTTP ${response.code}: ${redactApiLog(text)}")
                error("HTTP ${response.code}: ${redactApiLog(text)}")
            }
            return json.decodeFromString(EnrollResponseDto.serializer(), text)
        }
    }

    private fun redactApiLog(text: String): String = redactNetworkPayload(text)

    suspend fun submitOutboxBatch(
        endpoint: String,
        bearerToken: String,
        request: MachineOutboxBatchRequestDto,
    ): Result<MachineOutboxBatchResponseDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.encodeToString(MachineOutboxBatchRequestDto.serializer(), request)
                val httpRequest =
                    Request.Builder()
                        .url(endpoint)
                        .post(body.toRequestBody(jsonMediaType))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer $bearerToken")
                        .build()
                executeJson(httpRequest, MachineOutboxBatchResponseDto.serializer())
            }
        }

    suspend fun fetchOfflineGrantsDelta(
        endpoint: String,
        bearerToken: String,
        cursor: String,
    ): Result<com.viwa.android.data.remote.telemetry.mvp.offline.OfflineGrantsDeltaResponseDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url =
                    if (cursor.isBlank() || cursor == "0") {
                        endpoint
                    } else {
                        "$endpoint?cursor=${java.net.URLEncoder.encode(cursor, Charsets.UTF_8.name())}"
                    }
                val httpRequest =
                    Request.Builder()
                        .url(url)
                        .get()
                        .header("Authorization", "Bearer $bearerToken")
                        .build()
                executeJson(
                    httpRequest,
                    com.viwa.android.data.remote.telemetry.mvp.offline.OfflineGrantsDeltaResponseDto.serializer(),
                )
            }
        }

    suspend fun submitOfflineReconcileBatch(
        endpoint: String,
        bearerToken: String,
        request: com.viwa.android.data.remote.telemetry.mvp.offline.OfflineReconcileBatchRequestDto,
    ): Result<com.viwa.android.data.remote.telemetry.mvp.offline.OfflineReconcileBatchResponseDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body =
                    json.encodeToString(
                        com.viwa.android.data.remote.telemetry.mvp.offline.OfflineReconcileBatchRequestDto.serializer(),
                        request,
                    )
                val httpRequest =
                    Request.Builder()
                        .url(endpoint)
                        .post(body.toRequestBody(jsonMediaType))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer $bearerToken")
                        .build()
                executeJson(
                    httpRequest,
                    com.viwa.android.data.remote.telemetry.mvp.offline.OfflineReconcileBatchResponseDto.serializer(),
                )
            }
        }

    suspend fun fetchTechnicianAllowlistDelta(
        endpoint: String,
        bearerToken: String,
        cursor: String,
    ): Result<com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianAllowlistDeltaResponseDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url =
                    if (cursor.isBlank() || cursor == "0") {
                        endpoint
                    } else {
                        "$endpoint?cursor=${java.net.URLEncoder.encode(cursor, Charsets.UTF_8.name())}"
                    }
                val httpRequest =
                    Request.Builder()
                        .url(url)
                        .get()
                        .header("Authorization", "Bearer $bearerToken")
                        .build()
                executeJson(
                    httpRequest,
                    com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianAllowlistDeltaResponseDto.serializer(),
                )
            }
        }

    suspend fun validateTechnicianKey(
        endpoint: String,
        bearerToken: String,
        request: com.viwa.android.data.remote.telemetry.mvp.offline.ValidateTechnicianKeyRequestDto,
    ): Result<com.viwa.android.data.remote.telemetry.mvp.offline.ValidateTechnicianKeyResponseDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body =
                    json.encodeToString(
                        com.viwa.android.data.remote.telemetry.mvp.offline.ValidateTechnicianKeyRequestDto.serializer(),
                        request,
                    )
                val httpRequest =
                    Request.Builder()
                        .url(endpoint)
                        .post(body.toRequestBody(jsonMediaType))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer $bearerToken")
                        .build()
                executeJson(
                    httpRequest,
                    com.viwa.android.data.remote.telemetry.mvp.offline.ValidateTechnicianKeyResponseDto.serializer(),
                )
            }.recoverCatching { error ->
                throw mapTechnicianKeyApiError(error)
            }
        }

    suspend fun submitTechnicianAuditBatch(
        endpoint: String,
        bearerToken: String,
        request: com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianAuditBatchRequestDto,
    ): Result<com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianAuditBatchResponseDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body =
                    json.encodeToString(
                        com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianAuditBatchRequestDto.serializer(),
                        request,
                    )
                val httpRequest =
                    Request.Builder()
                        .url(endpoint)
                        .post(body.toRequestBody(jsonMediaType))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer $bearerToken")
                        .build()
                executeJson(
                    httpRequest,
                    com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianAuditBatchResponseDto.serializer(),
                )
            }
        }

    private fun mapTechnicianKeyApiError(error: Throwable): Throwable {
        val message = error.message.orEmpty()
        val codeRegex = """"code"\s*:\s*"([A-Z_]+)"""".toRegex()
        val code = codeRegex.find(message)?.groupValues?.getOrNull(1) ?: "ERROR"
        return com.viwa.android.domain.technician.TechnicianKeyApiException(code, message)
    }

    suspend fun checkAppUpdate(
        baseUrl: String,
        bearerToken: String,
        currentVersionCode: Int,
    ): Result<com.viwa.android.data.remote.ota.OtaCheckResponseDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url =
                    "${baseUrl.trimEnd('/')}/api/v1/machines/app-updates/check?currentVersionCode=$currentVersionCode"
                val httpRequest =
                    Request.Builder()
                        .url(url)
                        .get()
                        .header("Authorization", "Bearer $bearerToken")
                        .build()
                executeJson(httpRequest, com.viwa.android.data.remote.ota.OtaCheckResponseDto.serializer())
            }
        }

    suspend fun reportAppUpdate(
        baseUrl: String,
        bearerToken: String,
        requestUuid: String,
        releaseId: String,
        fromVersionCode: Int?,
        toVersionCode: Int,
        status: com.viwa.android.data.remote.ota.OtaReportStatus,
        failureReason: String? = null,
    ): Result<com.viwa.android.data.remote.ota.OtaReportResponseDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body =
                    json.encodeToString(
                        com.viwa.android.data.remote.ota.OtaReportRequestDto.serializer(),
                        com.viwa.android.data.remote.ota.OtaReportRequestDto(
                            requestUuid = requestUuid,
                            releaseId = releaseId,
                            fromVersionCode = fromVersionCode,
                            toVersionCode = toVersionCode,
                            status = status,
                            failureReason = failureReason,
                        ),
                    )
                val httpRequest =
                    Request.Builder()
                        .url("${baseUrl.trimEnd('/')}/api/v1/machines/app-updates/report")
                        .post(body.toRequestBody(jsonMediaType))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer $bearerToken")
                        .build()
                executeJson(httpRequest, com.viwa.android.data.remote.ota.OtaReportResponseDto.serializer())
            }
        }
}
