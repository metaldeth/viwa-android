package com.viwa.android.domain.technician

/** Must match `viwa-telemetry/.../technician-key-canonical.util.ts`. */
object TechnicianAllowlistCanonicalSigning {
    private const val PREFIX = "technician-allowlist-v1"

    data class CanonicalAllowlistFields(
        val keyId: String,
        val fingerprint: String,
        val machineId: String?,
        val scopes: List<String>,
        val expiresAt: String?,
        val revision: String,
        val revocationEpoch: Int,
    )

    fun buildCanonicalMessage(fields: CanonicalAllowlistFields): ByteArray {
        val machineToken = fields.machineId ?: "*"
        val scopesToken = fields.scopes.sorted().joinToString(",")
        val expiresToken = fields.expiresAt ?: "*"
        val parts =
            listOf(
                PREFIX,
                fields.keyId,
                fields.fingerprint,
                machineToken,
                scopesToken,
                expiresToken,
                fields.revision,
                fields.revocationEpoch.toString(),
            )
        return parts.joinToString("|").toByteArray(Charsets.UTF_8)
    }
}
