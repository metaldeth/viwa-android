package com.viwa.android.domain.inventory

import com.viwa.android.data.local.recipe.RecipeSyncFeatureFlags
import com.viwa.android.domain.customer.TelemetryCellsDefaultDosage
import com.viwa.android.domain.model.MvpInventoryTableRow
import com.viwa.android.domain.model.customer.DrinkDosage
import com.viwa.android.domain.recipe.CellEffectiveRecipe
import com.viwa.android.domain.recipe.RecipeCanonical
import com.viwa.android.domain.recipe.RecipeCanonicalTriple
import com.viwa.android.domain.recipe.RecipeScaleErrorCode
import com.viwa.android.domain.recipe.RecipeValidationErrorCode
import com.viwa.android.services.drink.DrinkPreparationCalculations

/**
 * Локальная база рецепта для service UI (Android-шаблон, не server-managed recipes).
 * База совпадает с [TelemetryCellsDefaultDosage] / [com.viwa.android.domain.customer.TelemetryCellsSnapshotAdapter].
 *
 * Pour Phase C: [resolvePourSetup] + [isPourFromEffectivePermitted] — architecture §11.3 / rollout step 7.
 * Fail-safe: invalid/incomplete/scale-failure never throws — returns [PourRecipeSource.FALLBACK_LEGACY]
 * with [PourRecipeFallbackReason] for ops grep (task-19 R-1/R-2).
 */
object InventoryCellRecipeSupport {
    const val SOURCE_NOTE = TelemetryCellsDefaultDosage.LOCAL_TEMPLATE_NOTE

    /** Stable wire values for logs/metrics — suitable for task-20 ops completeness gate. */
    enum class PourRecipeFallbackReason(val wireValue: String) {
        MISSING_EFFECTIVE("missing_effective"),
        INCOMPLETE_EFFECTIVE("incomplete_effective"),
        INVALID_EFFECTIVE("invalid_effective"),
        SCALE_FAILED("scale_failed"),
    }

    enum class PourRecipeSource {
        /** Legacy Android template (feature off, report-only, or gate inactive). */
        LEGACY_TEMPLATE,
        /** Durable effective triple applied for controller + telemetry base. */
        EFFECTIVE,
        /** Phase C permitted but effective unusable — legacy controller path (paid pour continues). */
        FALLBACK_LEGACY,
    }

    data class PourRecipeResolution(
        /** Base recipe at [DrinkDosage.drinkVolume] ml (typically 300) for telemetry. */
        val baseDosage: DrinkDosage,
        /** Dosage passed to [com.viwa.android.services.drink.ViwaDrinkSelectionService.chooseDrink]. */
        val controllerDosage: DrinkDosage,
        val source: PourRecipeSource,
        val fallbackReason: PourRecipeFallbackReason? = null,
        val diagnostics: String? = null,
    ) {
        val usedLegacyFallback: Boolean
            get() = source == PourRecipeSource.FALLBACK_LEGACY
    }

    data class RecipeBasis(
        val drinkVolumeMl: Int,
        val waterMl: Double,
        val productMl: Double,
        val conversionFactor: Double,
    )

    data class VolumeRecipeLine(
        val drinkVolumeMl: Int,
        val waterMl: Double,
        val productMl: Double,
        val dispenserSec: Double?,
    )

    private sealed class ScaleDosageOutcome {
        data class Success(val dosage: DrinkDosage) : ScaleDosageOutcome()

        data class Failed(val errors: List<RecipeScaleErrorCode>) : ScaleDosageOutcome()
    }

    fun isRecipeApplicable(row: MvpInventoryTableRow): Boolean = !row.productUuid.isNullOrBlank()

    fun isPourFromEffectivePermitted(
        managedGateActive: Boolean,
        featureEnabled: Boolean = RecipeSyncFeatureFlags.FEATURE_RECIPE_SYNC,
        pourGateEnabled: Boolean = RecipeSyncFeatureFlags.FEATURE_RECIPE_POUR_FROM_EFFECTIVE,
    ): Boolean = featureEnabled && pourGateEnabled && managedGateActive

    /**
     * Full fail-safe pour setup for [com.viwa.android.services.preparing.PreparingManager].
     * Never throws on corrupt effective data or scale overflow.
     */
    fun resolvePourSetup(
        effective: CellEffectiveRecipe?,
        conversionFactor: Double,
        pourVolumeMl: Int,
        pourFromEffectivePermitted: Boolean,
    ): PourRecipeResolution {
        val legacy = legacyBaseDosage(conversionFactor)
        if (!pourFromEffectivePermitted) {
            return legacyTemplateResolution(legacy)
        }
        if (effective == null) {
            return legacyFallbackResolution(
                legacy = legacy,
                reason = PourRecipeFallbackReason.MISSING_EFFECTIVE,
                diagnostics = "effective=null",
            )
        }
        if (!effective.isRecipeComplete) {
            return legacyFallbackResolution(
                legacy = legacy,
                reason = PourRecipeFallbackReason.INCOMPLETE_EFFECTIVE,
                diagnostics = "source=${effective.source}",
            )
        }
        val triple = effective.triple
        if (triple == null) {
            return legacyFallbackResolution(
                legacy = legacy,
                reason = PourRecipeFallbackReason.INCOMPLETE_EFFECTIVE,
                diagnostics = "triple=null",
            )
        }
        val validation = RecipeCanonical.validate(triple)
        if (!validation.valid) {
            return legacyFallbackResolution(
                legacy = legacy,
                reason = PourRecipeFallbackReason.INVALID_EFFECTIVE,
                diagnostics = validationErrorsDiagnostics(validation.errors),
            )
        }
        val baseDosage = baseDosageFromTriple(triple, conversionFactor)
        return when (val scaled = scaleDosageToPourVolume(triple, pourVolumeMl, conversionFactor)) {
            is ScaleDosageOutcome.Success ->
                PourRecipeResolution(
                    baseDosage = baseDosage,
                    controllerDosage = scaled.dosage,
                    source = PourRecipeSource.EFFECTIVE,
                )
            is ScaleDosageOutcome.Failed ->
                legacyFallbackResolution(
                    legacy = legacy,
                    reason = PourRecipeFallbackReason.SCALE_FAILED,
                    diagnostics = scaleErrorsDiagnostics(scaled.errors),
                )
        }
    }

    /** @deprecated Prefer [resolvePourSetup]; kept for narrow unit tests. */
    fun resolvePourDosage(
        effective: CellEffectiveRecipe?,
        conversionFactor: Double,
        pourFromEffectivePermitted: Boolean,
        pourVolumeMl: Int = TelemetryCellsDefaultDosage.RECIPE_DRINK_VOLUME_ML,
    ): PourRecipeResolution =
        resolvePourSetup(
            effective = effective,
            conversionFactor = conversionFactor,
            pourVolumeMl = pourVolumeMl,
            pourFromEffectivePermitted = pourFromEffectivePermitted,
        )

    fun actualWaterMlForResolution(
        resolution: PourRecipeResolution,
        pourVolumeMl: Int,
    ): Double =
        when (resolution.source) {
            PourRecipeSource.EFFECTIVE -> resolution.controllerDosage.water
            PourRecipeSource.LEGACY_TEMPLATE,
            PourRecipeSource.FALLBACK_LEGACY,
            ->
                DrinkPreparationCalculations.waterMlForDrink(
                    dosageWaterMl = resolution.baseDosage.water,
                    drinkVolumeMl = pourVolumeMl,
                    recipeDrinkVolumeMl = resolution.baseDosage.drinkVolume,
                )
        }

    /** Canonical integer scale to target pour volume; null when scale fails (never throws). */
    fun dosageScaledToPourVolumeOrNull(
        baseTriple: RecipeCanonicalTriple,
        targetVolumeMl: Int,
        conversionFactor: Double,
    ): DrinkDosage? =
        when (val outcome = scaleDosageToPourVolume(baseTriple, targetVolumeMl, conversionFactor)) {
            is ScaleDosageOutcome.Success -> outcome.dosage
            is ScaleDosageOutcome.Failed -> null
        }

    fun recipeBasis(conversionFactor: Double): RecipeBasis =
        RecipeBasis(
            drinkVolumeMl = TelemetryCellsDefaultDosage.RECIPE_DRINK_VOLUME_ML,
            waterMl = TelemetryCellsDefaultDosage.RECIPE_WATER_ML,
            productMl = TelemetryCellsDefaultDosage.RECIPE_PRODUCT_ML,
            conversionFactor = conversionFactor.coerceAtLeast(0.0),
        )

    fun volumeRecipeLine(
        basis: RecipeBasis,
        drinkVolumeMl: Int,
    ): VolumeRecipeLine {
        val triple =
            RecipeCanonicalTriple(
                baseDrinkVolumeMl = basis.drinkVolumeMl,
                waterDeciMl = RecipeCanonical.mlToDeciMl(basis.waterMl),
                productDeciMl = RecipeCanonical.mlToDeciMl(basis.productMl),
            )
        return volumeRecipeLineFromTriple(triple, drinkVolumeMl, basis.conversionFactor)
            ?: VolumeRecipeLine(
                drinkVolumeMl = drinkVolumeMl,
                waterMl = 0.0,
                productMl = 0.0,
                dispenserSec = null,
            )
    }

    /** Returns null when canonical scale fails (service preview must not crash). */
    fun volumeRecipeLineFromTriple(
        baseTriple: RecipeCanonicalTriple,
        drinkVolumeMl: Int,
        conversionFactor: Double,
    ): VolumeRecipeLine? {
        val scaled = RecipeCanonical.scaleRecipeDeci(baseTriple, drinkVolumeMl)
        if (!scaled.success || scaled.scaled == null) return null
        val triple = scaled.scaled!!
        val waterMl = RecipeCanonical.deciMlToMl(triple.waterDeciMl)
        val productMl = RecipeCanonical.deciMlToMl(triple.productDeciMl)
        val dispenserSec =
            if (conversionFactor > 0.0) {
                productMl / conversionFactor
            } else {
                null
            }
        return VolumeRecipeLine(
            drinkVolumeMl = drinkVolumeMl,
            waterMl = waterMl,
            productMl = productMl,
            dispenserSec = dispenserSec,
        )
    }

    fun formatVolumeLine(line: VolumeRecipeLine): String =
        buildString {
            append("${line.drinkVolumeMl} мл: ")
            append("вода=${formatMl(line.waterMl)} мл, ")
            append("сироп=${formatMl(line.productMl)} мл")
            line.dispenserSec?.let { sec ->
                if (sec.isFinite()) {
                    append(", дозатор=${"%.2f".format(sec)} с")
                }
            }
        }

    fun formatBasis(basis: RecipeBasis): String =
        "База ${basis.drinkVolumeMl} мл: вода=${formatMl(basis.waterMl)} мл, " +
            "сироп=${formatMl(basis.productMl)} мл, CF=${"%.4f".format(basis.conversionFactor)}"

    fun basisForRow(row: MvpInventoryTableRow): RecipeBasis = recipeBasis(row.conversionFactor)

    private fun legacyTemplateResolution(legacy: DrinkDosage): PourRecipeResolution =
        PourRecipeResolution(
            baseDosage = legacy,
            controllerDosage = legacy,
            source = PourRecipeSource.LEGACY_TEMPLATE,
        )

    private fun legacyFallbackResolution(
        legacy: DrinkDosage,
        reason: PourRecipeFallbackReason,
        diagnostics: String,
    ): PourRecipeResolution =
        PourRecipeResolution(
            baseDosage = legacy,
            controllerDosage = legacy,
            source = PourRecipeSource.FALLBACK_LEGACY,
            fallbackReason = reason,
            diagnostics = diagnostics,
        )

    private fun scaleDosageToPourVolume(
        baseTriple: RecipeCanonicalTriple,
        targetVolumeMl: Int,
        conversionFactor: Double,
    ): ScaleDosageOutcome {
        val scaled = RecipeCanonical.scaleRecipeDeci(baseTriple, targetVolumeMl)
        if (!scaled.success || scaled.scaled == null) {
            return ScaleDosageOutcome.Failed(scaled.errors)
        }
        val triple = scaled.scaled!!
        return ScaleDosageOutcome.Success(
            DrinkDosage(
                conversionFactor = conversionFactor.coerceAtLeast(0.0),
                drinkVolume = targetVolumeMl,
                water = RecipeCanonical.deciMlToMl(triple.waterDeciMl),
                product = RecipeCanonical.deciMlToMl(triple.productDeciMl),
            ),
        )
    }

    private fun validationErrorsDiagnostics(errors: List<RecipeValidationErrorCode>): String =
        "validationErrors=${errors.joinToString(",") { it.name }}"

    private fun scaleErrorsDiagnostics(errors: List<RecipeScaleErrorCode>): String =
        "scaleErrors=${errors.joinToString(",") { it.name }}"

    private fun formatMl(value: Double): String = "%.1f".format(value)

    private fun legacyBaseDosage(conversionFactor: Double): DrinkDosage =
        TelemetryCellsDefaultDosage.templateWithConversionFactor(conversionFactor)

    private fun baseDosageFromTriple(
        triple: RecipeCanonicalTriple,
        conversionFactor: Double,
    ): DrinkDosage =
        DrinkDosage(
            conversionFactor = conversionFactor.coerceAtLeast(0.0),
            drinkVolume = triple.baseDrinkVolumeMl,
            water = RecipeCanonical.deciMlToMl(triple.waterDeciMl),
            product = RecipeCanonical.deciMlToMl(triple.productDeciMl),
        )
}
