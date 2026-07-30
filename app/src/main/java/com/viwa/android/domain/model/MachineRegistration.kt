package com.viwa.android.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MachineRegistration(
    val machineId: String = "",
    val serialNumber: String = "",
    val kioskId: String = "",
 /** Legacy machine credential (`mch_…`) — только fallback до REG; не stable secret. */
    val machineKey: String = "",
    val machineCredential: String = "",
    val installationId: String = "",
    val reservationToken: String = "",
    val reservationExpiresAt: String = "",
    val wsProtocolUrl: String = "",
    val tokenEndpoint: String = TelemetryConfig.DEFAULT_TOKEN_ENDPOINT,
    val regKey: String = "",
 /** `stable_secret` после REG; `legacy_credential` до REG; пусто — не зарегистрирован. */
    val authScheme: String = "",
    val isRegistered: Boolean = false,
    val enrolled: Boolean = false,
 /** Из legacy `machineInfo`. */
    val organizationId: String = "",
    val modelId: String = "",
) {
    companion object {
        const val AUTH_SCHEME_STABLE_SECRET = "stable_secret"
        const val AUTH_SCHEME_LEGACY_CREDENTIAL = "legacy_credential"

        fun migrateLegacy(reg: MachineRegistration): MachineRegistration {
            val authScheme =
                when {
                    reg.authScheme.isNotBlank() -> reg.authScheme
                    reg.enrolled && reg.machineCredential.isBlank() && reg.machineKey.isBlank() ->
                        AUTH_SCHEME_STABLE_SECRET
                    else -> {
                        val credential =
                            when {
                                reg.machineCredential.isNotBlank() -> reg.machineCredential
                                reg.machineKey.startsWith("mch_") -> reg.machineKey
                                else -> reg.machineCredential
                            }
                        if (credential.isNotBlank()) AUTH_SCHEME_LEGACY_CREDENTIAL else ""
                    }
                }
            val credential =
                if (authScheme == AUTH_SCHEME_LEGACY_CREDENTIAL) {
                    when {
                        reg.machineCredential.isNotBlank() -> reg.machineCredential
                        reg.machineKey.startsWith("mch_") -> reg.machineKey
                        else -> reg.machineCredential
                    }
                } else {
                    ""
                }
            // Частичный JsonStore (mch_ + legacy_credential без флагов) — иначе connect
            // падает на «registration required» и WS-reconnect не стартует.
            val hasUsableLegacyCredential =
                authScheme == AUTH_SCHEME_LEGACY_CREDENTIAL && credential.isNotBlank()
            val registered = reg.enrolled || reg.isRegistered || hasUsableLegacyCredential
            val serial = reg.serialNumber.ifBlank { "E-01" }.let {
                if (it == "E-01" && registered) it else it
            }
            return reg.copy(
                machineCredential = credential,
                machineKey = if (credential.isNotBlank()) credential else "",
                authScheme = authScheme,
                isRegistered = registered,
                enrolled = registered,
                serialNumber = serial,
                tokenEndpoint = TelemetryConfig.migrateTokenEndpoint(reg.tokenEndpoint),
                wsProtocolUrl = TelemetryConfig.migrateWsProtocolUrl(reg.wsProtocolUrl),
            )
        }

        fun isEnrolled(reg: MachineRegistration): Boolean {
            if (reg.enrolled) return true
            if (reg.authScheme == AUTH_SCHEME_LEGACY_CREDENTIAL &&
                (reg.machineCredential.isNotBlank() || reg.machineKey.startsWith("mch_"))
            ) {
                return true
            }
            return reg.isRegistered &&
                (reg.authScheme == AUTH_SCHEME_STABLE_SECRET || reg.machineCredential.isNotBlank())
        }

        /** Сброс enroll после смены serial в UI: сохраняем regKey, новый installationId. */
        fun resetAfterSerialChange(
            reg: MachineRegistration,
            serialInStore: String,
            newInstallationId: String,
        ): MachineRegistration =
            reg.copy(
                serialNumber = serialInStore,
                machineId = "",
                machineKey = "",
                machineCredential = "",
                authScheme = "",
                isRegistered = false,
                enrolled = false,
                reservationToken = "",
                reservationExpiresAt = "",
                installationId = newInstallationId,
            )
    }
}
