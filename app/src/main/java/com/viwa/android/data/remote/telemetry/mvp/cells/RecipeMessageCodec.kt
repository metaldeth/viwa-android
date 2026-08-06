package com.viwa.android.data.remote.telemetry.mvp.cells

import com.viwa.android.domain.recipe.AssignmentStatus
import com.viwa.android.domain.recipe.RecipeCanonical
import com.viwa.android.domain.recipe.RecipeCanonicalTriple
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber

const val RECIPE_WS_MAX_REPORT_CELLS = 64
const val RECIPE_WS_MAX_ACK_BATCH = 64

const val RECIPE_WS_TYPE_REPORT = "cells.recipe.report"
const val RECIPE_WS_TYPE_SYNC_REQUEST = "cells.recipe.sync.request"
const val RECIPE_WS_TYPE_SYNC_CONTROL = "cells.recipe.sync.control"
const val RECIPE_WS_TYPE_COMMAND = "cells.recipe.command"
const val RECIPE_WS_TYPE_COMMAND_ACK = "cells.recipe.command.ack"

val RECIPE_WS_DOWNLINK_TYPES =
    setOf(
        RECIPE_WS_TYPE_SYNC_CONTROL,
        RECIPE_WS_TYPE_COMMAND,
    )

val RECIPE_WS_UPLINK_TYPES =
    setOf(
        RECIPE_WS_TYPE_REPORT,
        RECIPE_WS_TYPE_SYNC_REQUEST,
        RECIPE_WS_TYPE_COMMAND_ACK,
    )

val RECIPE_WS_ALL_TYPES = RECIPE_WS_UPLINK_TYPES + RECIPE_WS_DOWNLINK_TYPES

/** Recipe-bearing command kinds — require complete target tuple + base version id. */
val RECIPE_BEARING_COMMAND_KINDS =
    setOf(
        "ASSIGN_COPY",
        "RESET",
        "REMOTE_RECIPE_SET",
        "CAMPAIGN_FORCE_APPLY",
        "CAMPAIGN_CONDITIONAL_APPLY",
    )

const val RECIPE_UNASSIGN_CLEAR_KIND = "UNASSIGN_CLEAR"

@Serializable
data class MvpRecipeSyncCapabilityDto(
    val maxReportCells: Int = RECIPE_WS_MAX_REPORT_CELLS,
    val maxAckBatch: Int = RECIPE_WS_MAX_ACK_BATCH,
)

@Serializable
data class RecipeEffectiveRecipeWire(
    val baseDrinkVolumeMl: Int,
    val waterDeciMl: Int,
    val productDeciMl: Int,
)

@Serializable
data class RecipeCellReportWire(
    val cellUuid: String,
    val effectiveRecipe: RecipeEffectiveRecipeWire? = null,
    val effectiveFingerprint: String? = null,
    val lastAppliedCommandGeneration: String? = null,
    val cancelThroughGeneration: String? = null,
    val deviceReportRevision: String? = null,
)

@Serializable
internal data class RecipeReportPayloadWire(
    val cells: List<RecipeCellReportWire> = emptyList(),
)

@Serializable
internal data class RecipeSyncRequestPayloadWire(
    val cells: List<RecipeCellReportWire> = emptyList(),
)

@Serializable
data class RecipeAssignmentWire(
    val status: String,
    val productId: String? = null,
    val currentBaseVersionId: String? = null,
    val baseRecipeRevision: Int? = null,
    val currentBaseRecipe: RecipeEffectiveRecipeWire? = null,
    val currentBaseFingerprint: String? = null,
)

@Serializable
data class RecipeSyncControlCellWire(
    val cellUuid: String,
    val cancelThroughGeneration: String,
    val serverLastAppliedGeneration: String,
    val assignment: RecipeAssignmentWire? = null,
)

@Serializable
internal data class RecipeSyncControlPayloadWire(
    val cells: List<RecipeSyncControlCellWire>,
)

@Serializable
data class RecipeCommandDownlinkWire(
    val commandId: String,
    val commandGeneration: String,
    val kind: String,
    val cellUuid: String,
    val targetRecipe: RecipeEffectiveRecipeWire? = null,
    val targetBaseVersionId: String? = null,
    val fromFingerprint: String? = null,
    val fromBaseVersionId: String? = null,
    val campaignId: String? = null,
    val resetBatchId: String? = null,
)

@Serializable
data class RecipeCommandAckEntryWire(
    val commandId: String,
    val commandGeneration: String,
    val cellUuid: String,
    val status: String,
    val failureCode: String? = null,
    val appliedRecipe: RecipeEffectiveRecipeWire? = null,
)

@Serializable
internal data class RecipeCommandAckPayloadWire(
    val acks: List<RecipeCommandAckEntryWire> = emptyList(),
)

data class RecipeReportCellUplink(
    val cellUuid: String,
    val effectiveRecipe: RecipeCanonicalTriple,
    val effectiveFingerprint: String,
    val lastAppliedCommandGeneration: Long,
    val cancelThroughGeneration: Long,
    val deviceReportRevision: Long,
)

data class RecipeAssignmentControl(
    val status: AssignmentStatus,
    val productId: String? = null,
    val currentBaseVersionId: String? = null,
    val baseRecipeRevision: Int? = null,
    val triple: RecipeCanonicalTriple? = null,
    val wireFingerprint: String? = null,
)

data class RecipeSyncControlCell(
    val cellUuid: String,
    val cancelThroughGeneration: Long,
    val serverLastAppliedGeneration: Long,
    val assignment: RecipeAssignmentControl? = null,
)

data class RecipeCommandDownlink(
    val commandId: String,
    val commandGeneration: Long,
    val kind: String,
    val cellUuid: String,
    val targetRecipe: RecipeCanonicalTriple?,
    val targetBaseVersionId: String?,
    val fromFingerprint: String?,
    val fromBaseVersionId: String?,
    val campaignId: String?,
    val resetBatchId: String?,
)

data class RecipeCommandAckEntry(
    val commandId: String,
    val commandGeneration: Long,
    val cellUuid: String,
    val status: String,
    val failureCode: String?,
    val appliedRecipe: RecipeCanonicalTriple?,
)

sealed class RecipeDecodeResult<out T> {
    data class Success<T>(val value: T) : RecipeDecodeResult<T>()

    data class Invalid(val reason: String) : RecipeDecodeResult<Nothing>()
}

enum class RecipeFingerprintVerifyOutcome {
    MATCH,
    MISMATCH_LOGGED,
    SKIPPED,
}

@Singleton
class RecipeMessageCodec
@Inject
constructor() {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

    fun encodeReportPayload(cells: List<RecipeReportCellUplink>): String {
        require(cells.size <= RECIPE_WS_MAX_REPORT_CELLS) {
            "Report batch exceeds max $RECIPE_WS_MAX_REPORT_CELLS cells"
        }
        val wireCells =
            cells.map { cell ->
                RecipeCellReportWire(
                    cellUuid = cell.cellUuid,
                    effectiveRecipe =
                        RecipeEffectiveRecipeWire(
                            baseDrinkVolumeMl = cell.effectiveRecipe.baseDrinkVolumeMl,
                            waterDeciMl = cell.effectiveRecipe.waterDeciMl,
                            productDeciMl = cell.effectiveRecipe.productDeciMl,
                        ),
                    effectiveFingerprint = cell.effectiveFingerprint,
                    lastAppliedCommandGeneration = cell.lastAppliedCommandGeneration.toString(),
                    cancelThroughGeneration = cell.cancelThroughGeneration.toString(),
                    deviceReportRevision = cell.deviceReportRevision.toString(),
                )
            }
        return json.encodeToString(RecipeReportPayloadWire.serializer(), RecipeReportPayloadWire(cells = wireCells))
    }

    fun encodeSyncRequestPayload(): String =
        json.encodeToString(
            RecipeSyncRequestPayloadWire.serializer(),
            RecipeSyncRequestPayloadWire(cells = emptyList()),
        )

    fun encodeCommandAckPayload(acks: List<RecipeCommandAckEntry>): String {
        require(acks.size <= RECIPE_WS_MAX_ACK_BATCH) {
            "Ack batch exceeds max $RECIPE_WS_MAX_ACK_BATCH entries"
        }
        val wireAcks =
            acks.map { ack ->
                RecipeCommandAckEntryWire(
                    commandId = ack.commandId,
                    commandGeneration = ack.commandGeneration.toString(),
                    cellUuid = ack.cellUuid,
                    status = ack.status,
                    failureCode = ack.failureCode,
                    appliedRecipe =
                        ack.appliedRecipe?.let {
                            RecipeEffectiveRecipeWire(
                                baseDrinkVolumeMl = it.baseDrinkVolumeMl,
                                waterDeciMl = it.waterDeciMl,
                                productDeciMl = it.productDeciMl,
                            )
                        },
                )
            }
        return json.encodeToString(
            RecipeCommandAckPayloadWire.serializer(),
            RecipeCommandAckPayloadWire(acks = wireAcks),
        )
    }

    fun decodeSyncControlPayload(payloadJson: String): RecipeDecodeResult<List<RecipeSyncControlCell>> =
        runCatching {
            val wire = json.decodeFromString(RecipeSyncControlPayloadWire.serializer(), payloadJson)
            if (wire.cells.isEmpty()) {
                return RecipeDecodeResult.Invalid("sync.control cells empty")
            }
            val parsed = mutableListOf<RecipeSyncControlCell>()
            for (cell in wire.cells) {
                if (cell.cellUuid.isBlank()) {
                    return RecipeDecodeResult.Invalid("sync.control cellUuid blank")
                }
                val cancelGen =
                    parseGenerationString(cell.cancelThroughGeneration)
                        ?: return RecipeDecodeResult.Invalid(
                            "sync.control cancelThroughGeneration invalid for ${cell.cellUuid}",
                        )
                val serverLast =
                    parseGenerationString(cell.serverLastAppliedGeneration)
                        ?: return RecipeDecodeResult.Invalid(
                            "sync.control serverLastAppliedGeneration invalid for ${cell.cellUuid}",
                        )
                val assignment = cell.assignment?.let { decodeAssignment(it) }
                parsed +=
                    RecipeSyncControlCell(
                        cellUuid = cell.cellUuid,
                        cancelThroughGeneration = cancelGen,
                        serverLastAppliedGeneration = serverLast,
                        assignment = assignment,
                    )
            }
            RecipeDecodeResult.Success(parsed.toList())
        }.getOrElse { RecipeDecodeResult.Invalid(it.message ?: "sync.control parse failed") }

    internal fun decodeAssignment(wire: RecipeAssignmentWire): RecipeAssignmentControl? {
        val status =
            when (wire.status.lowercase()) {
                "assigned" -> AssignmentStatus.ASSIGNED
                "unassigned" -> AssignmentStatus.UNASSIGNED
                "unknown" -> AssignmentStatus.UNKNOWN
                else -> {
                    Timber.w("RecipeMessageCodec: unknown assignment.status=${wire.status}")
                    AssignmentStatus.UNKNOWN
                }
            }
        val triple =
            wire.currentBaseRecipe?.let {
                decodeEffectiveRecipeTriple(it)
            }
        return RecipeAssignmentControl(
            status = status,
            productId = wire.productId,
            currentBaseVersionId = wire.currentBaseVersionId,
            baseRecipeRevision = wire.baseRecipeRevision,
            triple = triple,
            wireFingerprint = wire.currentBaseFingerprint,
        )
    }

    fun decodeCommandPayload(payloadJson: String): RecipeDecodeResult<RecipeCommandDownlink> =
        runCatching {
            val wire = json.decodeFromString(RecipeCommandDownlinkWire.serializer(), payloadJson)
            if (wire.commandId.isBlank() || wire.cellUuid.isBlank() || wire.kind.isBlank()) {
                return RecipeDecodeResult.Invalid("command missing required ids")
            }
            val generation =
                parseGenerationString(wire.commandGeneration)
                    ?: return RecipeDecodeResult.Invalid("command commandGeneration invalid")
            val authority =
                validateCommandTargetAuthority(
                    kind = wire.kind,
                    targetRecipe = wire.targetRecipe,
                    targetBaseVersionId = wire.targetBaseVersionId,
                )
            if (authority is RecipeDecodeResult.Invalid) return authority

            val targetTriple =
                wire.targetRecipe?.let {
                    decodeEffectiveRecipeTriple(it)
                        ?: return RecipeDecodeResult.Invalid("command targetRecipe invalid")
                }

            RecipeDecodeResult.Success(
                RecipeCommandDownlink(
                    commandId = wire.commandId,
                    commandGeneration = generation,
                    kind = wire.kind,
                    cellUuid = wire.cellUuid,
                    targetRecipe = targetTriple,
                    targetBaseVersionId = wire.targetBaseVersionId,
                    fromFingerprint = wire.fromFingerprint,
                    fromBaseVersionId = wire.fromBaseVersionId,
                    campaignId = wire.campaignId,
                    resetBatchId = wire.resetBatchId,
                ),
            )
        }.getOrElse { RecipeDecodeResult.Invalid(it.message ?: "command parse failed") }

    fun decodeCommandAckPayload(payloadJson: String): RecipeDecodeResult<List<RecipeCommandAckEntry>> =
        runCatching {
            val wire = json.decodeFromString(RecipeCommandAckPayloadWire.serializer(), payloadJson)
            if (wire.acks.isEmpty()) {
                return RecipeDecodeResult.Invalid("command ack batch empty")
            }
            if (wire.acks.size > RECIPE_WS_MAX_ACK_BATCH) {
                return RecipeDecodeResult.Invalid("command ack batch exceeds max")
            }
            val parsed = mutableListOf<RecipeCommandAckEntry>()
            for (entry in wire.acks) {
                if (entry.commandId.isBlank() || entry.cellUuid.isBlank() || entry.status.isBlank()) {
                    return RecipeDecodeResult.Invalid("command ack entry missing required fields")
                }
                val generation =
                    parseGenerationString(entry.commandGeneration)
                        ?: return RecipeDecodeResult.Invalid(
                            "command ack generation invalid for ${entry.commandId}",
                        )
                val appliedTriple =
                    entry.appliedRecipe?.let {
                        decodeEffectiveRecipeTriple(it)
                            ?: return RecipeDecodeResult.Invalid(
                                "command ack appliedRecipe invalid for ${entry.commandId}",
                            )
                    }
                parsed +=
                    RecipeCommandAckEntry(
                        commandId = entry.commandId,
                        commandGeneration = generation,
                        cellUuid = entry.cellUuid,
                        status = entry.status,
                        failureCode = entry.failureCode,
                        appliedRecipe = appliedTriple,
                    )
            }
            RecipeDecodeResult.Success(parsed.toList())
        }.getOrElse { RecipeDecodeResult.Invalid(it.message ?: "command ack parse failed") }

    fun validateCommandTargetAuthority(
        kind: String,
        targetRecipe: RecipeEffectiveRecipeWire?,
        targetBaseVersionId: String?,
    ): RecipeDecodeResult<Unit> =
        when (kind) {
            RECIPE_UNASSIGN_CLEAR_KIND -> {
                if (targetRecipe != null || !targetBaseVersionId.isNullOrBlank()) {
                    RecipeDecodeResult.Invalid("UNASSIGN_CLEAR must not carry target recipe tuple")
                } else {
                    RecipeDecodeResult.Success(Unit)
                }
            }
            in RECIPE_BEARING_COMMAND_KINDS -> {
                val triple =
                    targetRecipe?.let { decodeEffectiveRecipeTriple(it) }
                        ?: return RecipeDecodeResult.Invalid("$kind requires complete targetRecipe triple")
                if (targetBaseVersionId.isNullOrBlank()) {
                    RecipeDecodeResult.Invalid("$kind requires targetBaseVersionId")
                } else {
                    RecipeDecodeResult.Success(Unit)
                }
            }
            else -> RecipeDecodeResult.Invalid("unknown recipe command kind: $kind")
        }

    fun verifyOptionalFingerprint(
        triple: RecipeCanonicalTriple,
        optionalFingerprintHex: String?,
    ): RecipeFingerprintVerifyOutcome {
        if (optionalFingerprintHex.isNullOrBlank()) return RecipeFingerprintVerifyOutcome.SKIPPED
        val normalized = optionalFingerprintHex.lowercase()
        if (normalized.length != 64) {
            Timber.w("RecipeMessageCodec: fingerprint length ${normalized.length}, expected 64")
            return RecipeFingerprintVerifyOutcome.MISMATCH_LOGGED
        }
        val recomputed = RecipeCanonical.fingerprint(triple)
        return if (recomputed == normalized) {
            RecipeFingerprintVerifyOutcome.MATCH
        } else {
            Timber.w(
                "RecipeMessageCodec: fingerprint mismatch expected=$recomputed got=$normalized",
            )
            RecipeFingerprintVerifyOutcome.MISMATCH_LOGGED
        }
    }

    fun isRecipeCommandAckPayload(payload: JsonObject): Boolean {
        val acks = payload["acks"]?.jsonArray ?: return false
        if (acks.isEmpty()) return false
        val first = acks.firstOrNull()?.jsonObject ?: return false
        return first.containsKey("commandId") &&
            first.containsKey("commandGeneration") &&
            first.containsKey("cellUuid") &&
            first.containsKey("status")
    }

    fun isRecipeReportAckPayload(payload: JsonObject): Boolean =
        payload.containsKey("ingested") ||
            payload.containsKey("stale") ||
            payload.containsKey("delivered")

    fun parseGenerationString(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        if (!raw.all { it.isDigit() }) return null
        return try {
            val value = raw.toLong()
            if (value < 0L) null else value
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun decodeEffectiveRecipeTriple(wire: RecipeEffectiveRecipeWire): RecipeCanonicalTriple? {
        val triple =
            RecipeCanonicalTriple(
                baseDrinkVolumeMl = wire.baseDrinkVolumeMl,
                waterDeciMl = wire.waterDeciMl,
                productDeciMl = wire.productDeciMl,
            )
        return if (RecipeCanonical.validate(triple).valid) triple else null
    }

    /** Golden round-trip helper for tests. */
    fun reportPayloadObject(payloadJson: String): JsonObject =
        json.parseToJsonElement(payloadJson).jsonObject
}
