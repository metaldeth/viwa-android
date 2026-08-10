package com.viwa.android.domain.technician

import com.viwa.android.data.remote.telemetry.ConnectionState
import com.viwa.android.data.remote.telemetry.mvp.MachineOutboxBearerTokenProvider
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryApiClient
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryWebSocketManager
import com.viwa.android.data.remote.telemetry.mvp.offline.MvpTechnicianKeysCapabilityDto
import com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianKeyFeatureFlags
import com.viwa.android.data.remote.telemetry.mvp.offline.ValidateTechnicianKeyRequestDto
import com.viwa.android.data.telemetry.technician.TechnicianKeyWsCodec
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

@Singleton
class TechnicianKeyOnlineValidator
@Inject
constructor(
    private val apiClient: MvpTelemetryApiClient,
    private val bearerTokenProvider: MachineOutboxBearerTokenProvider,
    private val wsManager: MvpTelemetryWebSocketManager,
    private val authorizationService: TechnicianKeyAuthorizationService,
) {
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Result<JsonObject>>>()

    init {
        wsManager.technicianKeySyncHandler =
            object : MvpTelemetryTechnicianKeySyncHandler {
                override suspend fun onValidateAck(correlationId: String, payload: JsonObject) {
                    pendingAcks.remove(correlationId)?.complete(Result.success(payload))
                }

                override suspend fun onValidateError(correlationId: String?, code: String, message: String) {
                    correlationId?.let { id ->
                        pendingAcks.remove(id)?.complete(
                            Result.failure(TechnicianKeyApiException(code, message)),
                        )
                    }
                }
            }
    }

    suspend fun validateOnline(
        capability: MvpTechnicianKeysCapabilityDto,
        rawCode: String,
        requestedScope: String,
        requestUuid: String,
    ): TechnicianAuthorizationResult {
        if (!TechnicianKeyFeatureFlags.FEATURE_TECHNICIAN_KEYS) {
            return authorizationService.recordOnlineOutcome(
                requestUuid = requestUuid,
                fingerprint = TechnicianKeyFingerprint.fingerprintFromInput(rawCode),
                technicianKeyId = null,
                requestedScope = requestedScope,
                allowed = false,
                reason = TechnicianAuthorizationReason.FEATURE_DISABLED,
            )
        }
        val normalized = TechnicianKeyNormalizer.normalize(rawCode)
        if (!TechnicianKeyNormalizer.isValidFormat(normalized)) {
            return authorizationService.recordOnlineOutcome(
                requestUuid = requestUuid,
                fingerprint = null,
                technicianKeyId = null,
                requestedScope = requestedScope,
                allowed = false,
                reason = TechnicianAuthorizationReason.KEY_INVALID_FORMAT,
            )
        }
        val fingerprint = TechnicianKeyFingerprint.fingerprint(normalized)
        val connected = wsManager.connectionState.value is ConnectionState.Connected
        val response =
            if (connected) {
                validateViaWs(normalized, requestedScope, requestUuid)
            } else {
                null
            } ?: validateViaRest(capability, normalized, requestedScope, requestUuid)

        return response.fold(
            onSuccess = { dto ->
                if (!dto.ok) {
                    val denyCode = dto.code ?: dto.reason
                    val reason = TechnicianKeyPolicyResolver.mapExplicitDenyCode(denyCode)
                    return authorizationService.recordOnlineOutcome(
                        requestUuid = requestUuid,
                        fingerprint = fingerprint,
                        technicianKeyId = dto.technicianKeyId,
                        requestedScope = requestedScope,
                        allowed = false,
                        reason = reason,
                    )
                }
                val expiresAt = dto.expiresAt ?: return transportUnavailable(requestUuid, fingerprint, requestedScope)
                val sessionToken = dto.sessionToken ?: return transportUnavailable(requestUuid, fingerprint, requestedScope)
                val technicianKeyId = dto.technicianKeyId ?: return transportUnavailable(requestUuid, fingerprint, requestedScope)
                val expiresAtMs = Instant.parse(expiresAt).toEpochMilli()
                authorizationService.recordOnlineOutcome(
                    requestUuid = requestUuid,
                    fingerprint = fingerprint,
                    technicianKeyId = technicianKeyId,
                    requestedScope = requestedScope,
                    allowed = true,
                    reason = TechnicianAuthorizationReason.GRANTED,
                    scopes = dto.scopes,
                    sessionToken = sessionToken,
                    expiresAtMs = expiresAtMs,
                )
            },
            onFailure = { error ->
                if (error is TechnicianKeyApiException) {
                    val reason = TechnicianKeyPolicyResolver.mapExplicitDenyCode(error.code)
                    return authorizationService.recordOnlineOutcome(
                        requestUuid = requestUuid,
                        fingerprint = fingerprint,
                        technicianKeyId = null,
                        requestedScope = requestedScope,
                        allowed = false,
                        reason = reason,
                    )
                }
                Timber.tag(TAG).w(error, "online validate transport failure scope=%s", requestedScope)
                TechnicianAuthorizationResult(
                    allowed = false,
                    reason = TechnicianAuthorizationReason.ONLINE_UNAVAILABLE,
                    fingerprint = fingerprint,
                    channel = "ONLINE",
                    transportFailure = true,
                )
            },
        )
    }

    private fun transportUnavailable(
        requestUuid: String,
        fingerprint: String,
        requestedScope: String,
    ): TechnicianAuthorizationResult =
        TechnicianAuthorizationResult(
            allowed = false,
            reason = TechnicianAuthorizationReason.ONLINE_UNAVAILABLE,
            fingerprint = fingerprint,
            channel = "ONLINE",
            transportFailure = true,
        )

    private suspend fun validateViaWs(
        normalizedCode: String,
        requestedScope: String,
        requestUuid: String,
    ): Result<com.viwa.android.data.remote.telemetry.mvp.offline.ValidateTechnicianKeyResponseDto>? {
        val messageId = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Result<JsonObject>>()
        pendingAcks[messageId] = deferred
        val sendResult =
            wsManager.sendEnvelope(
                type = TechnicianKeyWsCodec.TYPE_VALIDATE,
                payload = TechnicianKeyWsCodec.encodeValidate(normalizedCode, requestedScope, requestUuid),
                messageId = messageId,
            )
        if (sendResult.isFailure) {
            pendingAcks.remove(messageId)
            return null
        }
        val ack =
            withTimeoutOrNull(VALIDATE_TIMEOUT_MS) {
                deferred.await()
            }
        pendingAcks.remove(messageId)
        val payloadResult = ack ?: return null
        return payloadResult.fold(
            onSuccess = { payload ->
                runCatching { TechnicianKeyWsCodec.decodeValidateAck(payload) }
            },
            onFailure = { Result.failure(it) },
        )
    }

    private suspend fun validateViaRest(
        capability: MvpTechnicianKeysCapabilityDto,
        normalizedCode: String,
        requestedScope: String,
        requestUuid: String,
    ): Result<com.viwa.android.data.remote.telemetry.mvp.offline.ValidateTechnicianKeyResponseDto> {
        val token =
            bearerTokenProvider.resolveBearerToken()
                ?: return Result.failure(TechnicianKeyApiException("NO_TOKEN", "Missing bearer token"))
        return apiClient.validateTechnicianKey(
            endpoint = capability.validateEndpoint,
            bearerToken = token,
            request =
                ValidateTechnicianKeyRequestDto(
                    code = normalizedCode,
                    requestedScope = requestedScope,
                    requestUuid = requestUuid,
                ),
        )
    }

    companion object {
        private const val TAG = "TechKeyOnline"
        private const val VALIDATE_TIMEOUT_MS = 10_000L
    }
}

class TechnicianKeyApiException(
    val code: String,
    message: String,
) : Exception(message)

interface MvpTelemetryTechnicianKeySyncHandler {
    suspend fun onValidateAck(correlationId: String, payload: JsonObject)

    suspend fun onValidateError(correlationId: String?, code: String, message: String)
}
