package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.security.MachineSecretStore
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.model.MachineRegistration
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/** Resolves machine JWT / legacy credential for REST outbox batch (mirrors WS token path). */
@Singleton
class MachineOutboxBearerTokenProvider
@Inject
constructor(
    private val jwtCache: MachineJwtCache,
    private val apiClient: MvpTelemetryApiClient,
    private val configRepository: ConfigRepository,
    private val machineSecretStore: MachineSecretStore,
) {
    suspend fun resolveBearerToken(): String? {
        val configRaw = configRepository.getJson(com.viwa.android.data.local.db.JsonStoreKeys.TELEMETRY_CONFIG)
        val regRaw = configRepository.getJson(com.viwa.android.data.local.db.JsonStoreKeys.MACHINE_REGISTRATION)
        val json =
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
            }
        val config =
            configRaw?.let {
                runCatching { json.decodeFromString<com.viwa.android.domain.model.TelemetryConfig>(it) }
                    .getOrDefault(com.viwa.android.domain.model.TelemetryConfig())
            } ?: com.viwa.android.domain.model.TelemetryConfig()
        val reg =
            regRaw?.let {
                runCatching { json.decodeFromString<MachineRegistration>(it) }
                    .getOrDefault(MachineRegistration())
            } ?: MachineRegistration()
        val normalizedReg = MachineRegistration.migrateLegacy(reg)
        val serial = normalizedReg.serialNumber
        val stableSecret = machineSecretStore.getSecret(serial)
        if (!stableSecret.isNullOrBlank()) {
            return jwtCache
                .getAccessToken(
                    serialNumber = serial,
                    machineSecret = stableSecret,
                ) {
                    apiClient.fetchToken(
                        baseUrl = config.apiUrl,
                        tokenEndpoint = normalizedReg.tokenEndpoint,
                        requestBody =
                            TokenRequestDto(
                                serialNumber = serial,
                                machineSecret = stableSecret,
                            ),
                    )
                }.getOrElse { error ->
                    Timber.w(error, "MachineOutboxBearerTokenProvider: token fetch failed")
                    null
                }
        }
        val legacyCredential =
            normalizedReg.machineCredential.ifBlank {
                if (normalizedReg.machineKey.startsWith("mch_")) normalizedReg.machineKey else ""
            }
        return legacyCredential.takeIf { it.isNotBlank() }
    }
}
