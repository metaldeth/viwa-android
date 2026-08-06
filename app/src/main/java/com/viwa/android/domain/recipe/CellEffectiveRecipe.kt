package com.viwa.android.domain.recipe

import com.viwa.android.domain.customer.TelemetryCellsDefaultDosage

/**
 * Per-cell managed effective recipe (integer deci-ml identity).
 *
 * When [source] is [CellEffectiveRecipeSource.UNINITIALIZED], recipe identity fields are null —
 * the row holds generation watermarks only until sync/command initializes the effective recipe.
 */
data class CellEffectiveRecipe(
    val cellId: String,
    val baseDrinkVolumeMl: Int?,
    val waterDeciMl: Int?,
    val productDeciMl: Int?,
    val fingerprint: String?,
    val source: CellEffectiveRecipeSource,
    val productId: String?,
    val baseVersionId: String?,
    val lastAppliedCommandGeneration: Long,
    val cancelThroughGeneration: Long,
    val deviceReportRevision: Long = 0L,
    val lastAppliedCommandId: String? = null,
    val lastTerminalAckStatus: String? = null,
    val lastTerminalCommandGeneration: Long = 0L,
    val lastTerminalAckFailureCode: String? = null,
    val terminalAckDelivered: Boolean = false,
    val updatedAtMs: Long,
) {
    /** True when integer triple + fingerprint are present and safe for identity/drift/pour. */
    val isRecipeComplete: Boolean
        get() =
            when (source) {
                CellEffectiveRecipeSource.UNINITIALIZED -> false
                CellEffectiveRecipeSource.LEGACY_TEMPLATE,
                CellEffectiveRecipeSource.LOCAL_EDIT,
                CellEffectiveRecipeSource.COMMAND,
                ->
                    baseDrinkVolumeMl != null &&
                        waterDeciMl != null &&
                        productDeciMl != null &&
                        fingerprint != null
            }

    val triple: RecipeCanonicalTriple?
        get() {
            if (!isRecipeComplete) return null
            return RecipeCanonicalTriple(
                baseDrinkVolumeMl = baseDrinkVolumeMl!!,
                waterDeciMl = waterDeciMl!!,
                productDeciMl = productDeciMl!!,
            )
        }

    /** Explicit label when [source] is legacy template (feature gate off). */
    val sourceLabel: String?
        get() =
            if (source == CellEffectiveRecipeSource.LEGACY_TEMPLATE) {
                TelemetryCellsDefaultDosage.LOCAL_TEMPLATE_NOTE
            } else {
                null
            }
}

enum class CellEffectiveRecipeSource {
    /** Shared 300/270/30 template — feature off only; not server-managed recipe sync. */
    LEGACY_TEMPLATE,
    /** Managed mode: control/generations persisted; effective recipe not yet initialized. */
    UNINITIALIZED,
    LOCAL_EDIT,
    COMMAND,
}

object CellEffectiveRecipeDefaults {
    val LEGACY_BASE_DRINK_VOLUME_ML: Int = TelemetryCellsDefaultDosage.RECIPE_DRINK_VOLUME_ML
    const val LEGACY_WATER_DECI_ML: Int = 2700
    const val LEGACY_PRODUCT_DECI_ML: Int = 300

    val legacyTriple: RecipeCanonicalTriple =
        RecipeCanonicalTriple(
            baseDrinkVolumeMl = LEGACY_BASE_DRINK_VOLUME_ML,
            waterDeciMl = LEGACY_WATER_DECI_ML,
            productDeciMl = LEGACY_PRODUCT_DECI_ML,
        )

    val legacyFingerprint: String = RecipeCanonical.fingerprint(legacyTriple)

    fun legacyForCell(cellId: String, nowMs: Long = 0L): CellEffectiveRecipe =
        CellEffectiveRecipe(
            cellId = cellId,
            baseDrinkVolumeMl = LEGACY_BASE_DRINK_VOLUME_ML,
            waterDeciMl = LEGACY_WATER_DECI_ML,
            productDeciMl = LEGACY_PRODUCT_DECI_ML,
            fingerprint = legacyFingerprint,
            source = CellEffectiveRecipeSource.LEGACY_TEMPLATE,
            productId = null,
            baseVersionId = null,
            lastAppliedCommandGeneration = 0L,
            cancelThroughGeneration = 0L,
            deviceReportRevision = 0L,
            updatedAtMs = nowMs,
        )

    fun controlOnly(
        cellId: String,
        cancelThroughGeneration: Long = 0L,
        lastAppliedCommandGeneration: Long = 0L,
        nowMs: Long = 0L,
    ): CellEffectiveRecipe =
        CellEffectiveRecipe(
            cellId = cellId,
            baseDrinkVolumeMl = null,
            waterDeciMl = null,
            productDeciMl = null,
            fingerprint = null,
            source = CellEffectiveRecipeSource.UNINITIALIZED,
            productId = null,
            baseVersionId = null,
            lastAppliedCommandGeneration = lastAppliedCommandGeneration,
            cancelThroughGeneration = cancelThroughGeneration,
            deviceReportRevision = 0L,
            updatedAtMs = nowMs,
        )
}
