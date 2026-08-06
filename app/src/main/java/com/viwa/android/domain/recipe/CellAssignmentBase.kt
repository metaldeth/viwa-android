package com.viwa.android.domain.recipe

/** Cached server assignment/base snapshot — never effective recipe authority. */
data class CellAssignmentBase(
    val cellUuid: String,
    val status: AssignmentStatus,
    val productId: String?,
    val currentBaseVersionId: String?,
    val baseRecipeRevision: Int?,
    val baseDrinkVolumeMl: Int?,
    val waterDeciMl: Int?,
    val productDeciMl: Int?,
    val fingerprint: String?,
    val receivedAtMs: Long,
    /** Optional prior assigned snapshot for stale/offline display only. */
    val priorFingerprint: String? = null,
    val priorReceivedAtMs: Long? = null,
) {
    val triple: RecipeCanonicalTriple?
        get() {
            if (
                status != AssignmentStatus.ASSIGNED ||
                baseDrinkVolumeMl == null ||
                waterDeciMl == null ||
                productDeciMl == null
            ) {
                return null
            }
            return RecipeCanonicalTriple(
                baseDrinkVolumeMl = baseDrinkVolumeMl,
                waterDeciMl = waterDeciMl,
                productDeciMl = productDeciMl,
            )
        }

    val hasCompleteAssignedBase: Boolean =
        status == AssignmentStatus.ASSIGNED &&
            triple != null &&
            !fingerprint.isNullOrBlank() &&
            !currentBaseVersionId.isNullOrBlank()
}

enum class AssignmentStatus {
    ASSIGNED,
    UNASSIGNED,
    UNKNOWN,
}

enum class RecipeDriftBadge {
    /** Effective fingerprint matches cached current base for same product. */
    ALIGNED,
    /** Effective differs from cached current base. */
    MODIFIED,
    /** No reliable base snapshot (unknown/unassigned/missing fingerprint). */
    BASE_UNKNOWN,
    /** Assigned base exists but offline/stale by age. */
    OFFLINE_STALE,
}
