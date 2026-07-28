package com.viwa.android.domain.offline

/**
 * Canonical pipe-delimited grant message for Ed25519 verification.
 * Must match `viwa-telemetry/.../canonical-signing.util.ts`.
 */
object OfflineGrantCanonicalSigning {
    private const val PREFIX = "offline-grant-v1"

    data class CanonicalGrantFields(
        val grantId: String,
        val machineId: String,
        val subjectHash: String,
        val subscriptionLevelId: String,
        val issuedAt: String,
        val expiresAt: String,
        val dailyRemainingMlAtIssue: Int,
        val maxOfflinePours: Int,
        val maxOfflineVolumeMl: Int,
        val signingKeyId: String,
        val revocationEpoch: Int,
    )

    fun buildCanonicalMessage(fields: CanonicalGrantFields): ByteArray {
        val parts =
            listOf(
                PREFIX,
                fields.grantId,
                fields.machineId,
                fields.subjectHash,
                fields.subscriptionLevelId,
                fields.issuedAt,
                fields.expiresAt,
                fields.dailyRemainingMlAtIssue.toString(),
                fields.maxOfflinePours.toString(),
                fields.maxOfflineVolumeMl.toString(),
                fields.signingKeyId,
                fields.revocationEpoch.toString(),
            )
        return parts.joinToString("|").toByteArray(Charsets.UTF_8)
    }
}
