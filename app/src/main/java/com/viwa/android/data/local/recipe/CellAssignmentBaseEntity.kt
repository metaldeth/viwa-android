package com.viwa.android.data.local.recipe

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.viwa.android.domain.recipe.AssignmentStatus
import com.viwa.android.domain.recipe.CellAssignmentBase

@Entity(tableName = "cell_assignment_base")
data class CellAssignmentBaseEntity(
    @PrimaryKey @ColumnInfo(name = "cell_uuid") val cellUuid: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "product_id") val productId: String?,
    @ColumnInfo(name = "current_base_version_id") val currentBaseVersionId: String?,
    @ColumnInfo(name = "base_recipe_revision") val baseRecipeRevision: Int?,
    @ColumnInfo(name = "base_drink_volume_ml") val baseDrinkVolumeMl: Int?,
    @ColumnInfo(name = "water_deci_ml") val waterDeciMl: Int?,
    @ColumnInfo(name = "product_deci_ml") val productDeciMl: Int?,
    @ColumnInfo(name = "fingerprint") val fingerprint: String?,
    @ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
    @ColumnInfo(name = "prior_fingerprint") val priorFingerprint: String?,
    @ColumnInfo(name = "prior_received_at_ms") val priorReceivedAtMs: Long?,
) {
    fun toDomain(): CellAssignmentBase =
        CellAssignmentBase(
            cellUuid = cellUuid,
            status = AssignmentStatus.valueOf(status),
            productId = productId,
            currentBaseVersionId = currentBaseVersionId,
            baseRecipeRevision = baseRecipeRevision,
            baseDrinkVolumeMl = baseDrinkVolumeMl,
            waterDeciMl = waterDeciMl,
            productDeciMl = productDeciMl,
            fingerprint = fingerprint,
            receivedAtMs = receivedAtMs,
            priorFingerprint = priorFingerprint,
            priorReceivedAtMs = priorReceivedAtMs,
        )

    companion object {
        fun fromDomain(base: CellAssignmentBase): CellAssignmentBaseEntity =
            CellAssignmentBaseEntity(
                cellUuid = base.cellUuid,
                status = base.status.name,
                productId = base.productId,
                currentBaseVersionId = base.currentBaseVersionId,
                baseRecipeRevision = base.baseRecipeRevision,
                baseDrinkVolumeMl = base.baseDrinkVolumeMl,
                waterDeciMl = base.waterDeciMl,
                productDeciMl = base.productDeciMl,
                fingerprint = base.fingerprint,
                receivedAtMs = base.receivedAtMs,
                priorFingerprint = base.priorFingerprint,
                priorReceivedAtMs = base.priorReceivedAtMs,
            )
    }
}
