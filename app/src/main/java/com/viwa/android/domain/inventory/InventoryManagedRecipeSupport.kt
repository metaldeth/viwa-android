package com.viwa.android.domain.inventory

import com.viwa.android.data.local.recipe.RecipeSyncFeatureFlags
import com.viwa.android.domain.model.MvpInventoryTableRow
import com.viwa.android.domain.recipe.AssignmentStatus
import com.viwa.android.domain.recipe.CellAssignmentBase
import com.viwa.android.domain.recipe.CellEffectiveRecipe
import com.viwa.android.domain.recipe.RecipeCanonical
import com.viwa.android.domain.recipe.RecipeDriftBadge
import com.viwa.android.services.drink.DrinkPreparationCalculations

/** Managed-recipe service UI helpers — drift, formatting, edit validation. */
object InventoryManagedRecipeSupport {
    /** Offline base considered stale after 24h without refresh. */
    const val BASE_STALE_THRESHOLD_MS: Long = 24L * 60 * 60 * 1000

    data class InventoryRecipePanel(
        val featureManaged: Boolean,
        val legacyNote: String?,
        val effective: CellEffectiveRecipe?,
        val assignmentBase: CellAssignmentBase?,
        val driftBadge: RecipeDriftBadge?,
        val baseVersionLabel: String?,
        val syncStatusLabel: String?,
        val conversionFactor: Double,
        val canEdit: Boolean,
        val canReset: Boolean,
        val editDisabledReason: String?,
    )

    data class EditDraft(
        val baseDrinkVolumeMl: String,
        val waterDeciMl: String,
        val productDeciMl: String,
    )

    data class EditValidationResult(
        val valid: Boolean,
        val triple: com.viwa.android.domain.recipe.RecipeCanonicalTriple?,
        val errorMessage: String?,
    )

    fun buildPanel(
        row: MvpInventoryTableRow,
        effective: CellEffectiveRecipe?,
        assignmentBase: CellAssignmentBase?,
        managedGateActive: Boolean,
        technicianAuthorized: Boolean,
        recipeBusy: Boolean,
        pendingOutbox: Boolean,
        serverConfirmed: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): InventoryRecipePanel {
        if (!RecipeSyncFeatureFlags.FEATURE_RECIPE_SYNC) {
            val basis = InventoryCellRecipeSupport.basisForRow(row)
            return InventoryRecipePanel(
                featureManaged = false,
                legacyNote = InventoryCellRecipeSupport.SOURCE_NOTE,
                effective = null,
                assignmentBase = null,
                driftBadge = null,
                baseVersionLabel = null,
                syncStatusLabel = null,
                conversionFactor = basis.conversionFactor,
                canEdit = false,
                canReset = false,
                editDisabledReason = "Managed recipe sync выключен",
            )
        }

        val drift = computeDrift(effective, assignmentBase, row.productUuid, nowMs)
        val baseLabel = formatBaseVersionLabel(assignmentBase, nowMs)
        val syncLabel =
            when {
                pendingOutbox -> "Сохранено локально, ожидает синхронизации"
                serverConfirmed -> "Подтверждено сервером"
                effective != null -> "Локальное состояние"
                else -> null
            }

        val hasProduct = !row.productUuid.isNullOrBlank()
        val baseKnown = assignmentBase?.hasCompleteAssignedBase == true
        val disabledReason =
            when {
                !technicianAuthorized -> "Требуется авторизация техника"
                !hasProduct -> "Нет продукта в ячейке"
                !managedGateActive -> "Managed recipe gate не инициализирован"
                recipeBusy -> "Операция выполняется"
                else -> null
            }
        val resetDisabledReason =
            when {
                disabledReason != null -> disabledReason
                !baseKnown -> "База продукта неизвестна"
                else -> null
            }

        return InventoryRecipePanel(
            featureManaged = true,
            legacyNote = null,
            effective = effective,
            assignmentBase = assignmentBase,
            driftBadge = drift,
            baseVersionLabel = baseLabel,
            syncStatusLabel = syncLabel,
            conversionFactor = row.conversionFactor,
            canEdit = disabledReason == null,
            canReset = resetDisabledReason == null,
            editDisabledReason = disabledReason ?: resetDisabledReason,
        )
    }

    fun computeDrift(
        effective: CellEffectiveRecipe?,
        assignmentBase: CellAssignmentBase?,
        rowProductUuid: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): RecipeDriftBadge? {
        if (effective == null || !effective.isRecipeComplete) return RecipeDriftBadge.BASE_UNKNOWN
        if (assignmentBase == null) return RecipeDriftBadge.BASE_UNKNOWN

        when (assignmentBase.status) {
            AssignmentStatus.UNASSIGNED -> return RecipeDriftBadge.BASE_UNKNOWN
            AssignmentStatus.UNKNOWN -> return RecipeDriftBadge.BASE_UNKNOWN
            AssignmentStatus.ASSIGNED -> Unit
        }

        if (!assignmentBase.hasCompleteAssignedBase) return RecipeDriftBadge.BASE_UNKNOWN
        if (
            !rowProductUuid.isNullOrBlank() &&
            !assignmentBase.productId.isNullOrBlank() &&
            rowProductUuid != assignmentBase.productId
        ) {
            return RecipeDriftBadge.BASE_UNKNOWN
        }

        if (nowMs - assignmentBase.receivedAtMs > BASE_STALE_THRESHOLD_MS) {
            return RecipeDriftBadge.OFFLINE_STALE
        }

        val effectiveFp = effective.fingerprint ?: return RecipeDriftBadge.BASE_UNKNOWN
        val baseFp = assignmentBase.fingerprint ?: return RecipeDriftBadge.BASE_UNKNOWN
        return if (effectiveFp == baseFp) RecipeDriftBadge.ALIGNED else RecipeDriftBadge.MODIFIED
    }

    fun driftBadgeLabel(badge: RecipeDriftBadge?): String? =
        when (badge) {
            RecipeDriftBadge.ALIGNED -> "Совпадает с базой"
            RecipeDriftBadge.MODIFIED -> "Изменён локально/командой"
            RecipeDriftBadge.BASE_UNKNOWN -> "База неизвестна"
            RecipeDriftBadge.OFFLINE_STALE -> "База устарела (offline)"
            null -> null
        }

    fun formatBaseVersionLabel(base: CellAssignmentBase?, nowMs: Long = System.currentTimeMillis()): String? {
        if (base == null) return null
        return when (base.status) {
            AssignmentStatus.UNASSIGNED -> "Ячейка без продукта"
            AssignmentStatus.UNKNOWN -> "База продукта неизвестна"
            AssignmentStatus.ASSIGNED -> {
                val rev = base.baseRecipeRevision?.let { "rev $it" } ?: "rev ?"
                val version = base.currentBaseVersionId?.take(8) ?: "?"
                val stale =
                    if (nowMs - base.receivedAtMs > BASE_STALE_THRESHOLD_MS) {
                        " · устарела ${formatRelativeAge(nowMs - base.receivedAtMs)}"
                    } else {
                        ""
                    }
                "База $version ($rev)$stale"
            }
        }
    }

    fun validateEditDraft(draft: EditDraft): EditValidationResult {
        val base = draft.baseDrinkVolumeMl.trim().toIntOrNull()
        val water = draft.waterDeciMl.trim().toIntOrNull()
        val product = draft.productDeciMl.trim().toIntOrNull()
        if (base == null || water == null || product == null) {
            return EditValidationResult(false, null, "Введите целые значения для объёма и deci-ml")
        }
        val triple =
            com.viwa.android.domain.recipe.RecipeCanonicalTriple(
                baseDrinkVolumeMl = base,
                waterDeciMl = water,
                productDeciMl = product,
            )
        val validation = RecipeCanonical.validate(triple)
        if (!validation.valid) {
            return EditValidationResult(false, null, "Некорректный рецепт: ${validation.errors.joinToString()}")
        }
        return EditValidationResult(true, triple, null)
    }

    fun formatEffectiveLine(
        effective: CellEffectiveRecipe,
        drinkVolumeMl: Int,
        conversionFactor: Double,
    ): String {
        val triple = effective.triple ?: return "—"
        val scaled = RecipeCanonical.scaleRecipeDeci(triple, drinkVolumeMl)
        val scaledTriple = scaled.scaled ?: return "—"
        val waterMl = RecipeCanonical.deciMlToMl(scaledTriple.waterDeciMl)
        val productMl = RecipeCanonical.deciMlToMl(scaledTriple.productDeciMl)
        val dispenserSec =
            if (conversionFactor > 0.0) productMl / conversionFactor else null
        return buildString {
            append("$drinkVolumeMl мл: вода=${formatMl(waterMl)} мл, сироп=${formatMl(productMl)} мл")
            dispenserSec?.let { sec ->
                if (sec.isFinite()) append(", дозатор=${"%.2f".format(sec)} с")
            }
        }
    }

    fun formatBaseTripleLine(base: CellAssignmentBase): String? {
        val triple = base.triple ?: return null
        return "База ${triple.baseDrinkVolumeMl} мл: " +
            "вода=${formatMl(RecipeCanonical.deciMlToMl(triple.waterDeciMl))} мл, " +
            "сироп=${formatMl(RecipeCanonical.deciMlToMl(triple.productDeciMl))} мл"
    }

    fun resetConfirmationMessage(base: CellAssignmentBase): String {
        val rev = base.baseRecipeRevision?.toString() ?: "?"
        val version = base.currentBaseVersionId ?: "?"
        return "Сбросить эффективный рецепт к базе продукта (версия $version, rev $rev)?"
    }

    private fun formatRelativeAge(ageMs: Long): String {
        val hours = ageMs / (60 * 60 * 1000)
        return if (hours >= 24) "${hours / 24} дн." else "$hours ч."
    }

    private fun formatMl(value: Double): String = "%.1f".format(value)
}
