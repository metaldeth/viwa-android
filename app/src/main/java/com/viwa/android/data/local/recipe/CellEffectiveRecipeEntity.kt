package com.viwa.android.data.local.recipe

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.viwa.android.domain.recipe.CellEffectiveRecipe
import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import com.viwa.android.domain.recipe.CellEffectiveRecipeSource

@Entity(tableName = "cell_effective_recipe")
data class CellEffectiveRecipeEntity(
    @PrimaryKey @ColumnInfo(name = "cell_id") val cellId: String,
    @ColumnInfo(name = "base_drink_volume_ml") val baseDrinkVolumeMl: Int?,
    @ColumnInfo(name = "water_deci_ml") val waterDeciMl: Int?,
    @ColumnInfo(name = "product_deci_ml") val productDeciMl: Int?,
    @ColumnInfo(name = "fingerprint") val fingerprint: String?,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "product_id") val productId: String?,
    @ColumnInfo(name = "base_version_id") val baseVersionId: String?,
    @ColumnInfo(name = "last_applied_command_generation") val lastAppliedCommandGeneration: Long,
    @ColumnInfo(name = "cancel_through_generation") val cancelThroughGeneration: Long,
    @ColumnInfo(name = "device_report_revision") val deviceReportRevision: Long = 0L,
    @ColumnInfo(name = "last_applied_command_id") val lastAppliedCommandId: String? = null,
    @ColumnInfo(name = "last_terminal_ack_status") val lastTerminalAckStatus: String? = null,
    @ColumnInfo(name = "last_terminal_command_generation") val lastTerminalCommandGeneration: Long = 0L,
    @ColumnInfo(name = "last_terminal_ack_failure_code") val lastTerminalAckFailureCode: String? = null,
    @ColumnInfo(name = "terminal_ack_delivered") val terminalAckDelivered: Boolean = false,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
    fun toDomain(): CellEffectiveRecipe =
        CellEffectiveRecipe(
            cellId = cellId,
            baseDrinkVolumeMl = baseDrinkVolumeMl,
            waterDeciMl = waterDeciMl,
            productDeciMl = productDeciMl,
            fingerprint = fingerprint,
            source = CellEffectiveRecipeSource.valueOf(source),
            productId = productId,
            baseVersionId = baseVersionId,
            lastAppliedCommandGeneration = lastAppliedCommandGeneration,
            cancelThroughGeneration = cancelThroughGeneration,
            deviceReportRevision = deviceReportRevision,
            lastAppliedCommandId = lastAppliedCommandId,
            lastTerminalAckStatus = lastTerminalAckStatus,
            lastTerminalCommandGeneration = lastTerminalCommandGeneration,
            lastTerminalAckFailureCode = lastTerminalAckFailureCode,
            terminalAckDelivered = terminalAckDelivered,
            updatedAtMs = updatedAtMs,
        )

    companion object {
        fun fromDomain(recipe: CellEffectiveRecipe): CellEffectiveRecipeEntity =
            CellEffectiveRecipeEntity(
                cellId = recipe.cellId,
                baseDrinkVolumeMl = recipe.baseDrinkVolumeMl,
                waterDeciMl = recipe.waterDeciMl,
                productDeciMl = recipe.productDeciMl,
                fingerprint = recipe.fingerprint,
                source = recipe.source.name,
                productId = recipe.productId,
                baseVersionId = recipe.baseVersionId,
                lastAppliedCommandGeneration = recipe.lastAppliedCommandGeneration,
                cancelThroughGeneration = recipe.cancelThroughGeneration,
                deviceReportRevision = recipe.deviceReportRevision,
                lastAppliedCommandId = recipe.lastAppliedCommandId,
                lastTerminalAckStatus = recipe.lastTerminalAckStatus,
                lastTerminalCommandGeneration = recipe.lastTerminalCommandGeneration,
                lastTerminalAckFailureCode = recipe.lastTerminalAckFailureCode,
                terminalAckDelivered = recipe.terminalAckDelivered,
                updatedAtMs = recipe.updatedAtMs,
            )

        fun controlOnly(
            cellId: String,
            cancelThroughGeneration: Long,
            lastAppliedCommandGeneration: Long = 0L,
            updatedAtMs: Long,
        ): CellEffectiveRecipeEntity =
            fromDomain(
                CellEffectiveRecipeDefaults.controlOnly(
                    cellId = cellId,
                    cancelThroughGeneration = cancelThroughGeneration,
                    lastAppliedCommandGeneration = lastAppliedCommandGeneration,
                    nowMs = updatedAtMs,
                ),
            )
    }
}
