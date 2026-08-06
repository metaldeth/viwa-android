package com.viwa.android.data.remote.telemetry.mvp.cells

import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import com.viwa.android.domain.recipe.RecipeCanonical
import com.viwa.android.domain.recipe.RecipeCanonicalTriple
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeMessageCodecTest {

    private val codec = RecipeMessageCodec()

    @Test
    fun encodeReportPayload_roundTripsIntegerFieldsAndGenerationStrings() {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        val fingerprint = CellEffectiveRecipeDefaults.legacyFingerprint
        val payloadJson =
            codec.encodeReportPayload(
                listOf(
                    RecipeReportCellUplink(
                        cellUuid = "cell-1",
                        effectiveRecipe = triple,
                        effectiveFingerprint = fingerprint,
                        lastAppliedCommandGeneration = 42L,
                        cancelThroughGeneration = 40L,
                        deviceReportRevision = 3L,
                    ),
                ),
            )

        val root = codec.reportPayloadObject(payloadJson)
        val cell = root["cells"]!!.toString()
        assertTrue(cell.contains("\"baseDrinkVolumeMl\":300"))
        assertTrue(cell.contains("\"waterDeciMl\":2700"))
        assertTrue(cell.contains("\"productDeciMl\":300"))
        assertTrue(cell.contains("\"lastAppliedCommandGeneration\":\"42\""))
        assertTrue(cell.contains("\"cancelThroughGeneration\":\"40\""))
        assertTrue(cell.contains("\"deviceReportRevision\":\"3\""))
        assertTrue(cell.contains(fingerprint))
    }

    @Test
    fun goldenDefaultFingerprint_matchesFixtureVector() {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        assertEquals(
            "cd1adcdbe51bd39d03619bc39d834fc2fdd38293801f7053b164f920923d8afd",
            RecipeCanonical.fingerprint(triple),
        )
    }

    @Test
    fun parseGenerationString_rejectsNegativeOverflowAndNonDigits() {
        assertNull(codec.parseGenerationString("-1"))
        assertNull(codec.parseGenerationString("abc"))
        assertNull(codec.parseGenerationString("12.5"))
        assertNull(codec.parseGenerationString("9223372036854775808"))
        assertEquals(0L, codec.parseGenerationString("0"))
        assertEquals(9223372036854775807L, codec.parseGenerationString("9223372036854775807"))
    }

    @Test
    fun decodeSyncControl_rejectsMalformedGeneration() {
        val payload =
            """
            {
              "cells": [{
                "cellUuid": "cell-1",
                "cancelThroughGeneration": "not-a-number",
                "serverLastAppliedGeneration": "1"
              }]
            }
            """.trimIndent()

        val result = codec.decodeSyncControlPayload(payload)
        assertTrue(result is RecipeDecodeResult.Invalid)
    }

    @Test
    fun decodeSyncControl_parsesWatermarkPerCell() {
        val payload =
            """
            {
              "cells": [{
                "cellUuid": "cell-1",
                "cancelThroughGeneration": "12",
                "serverLastAppliedGeneration": "11"
              }]
            }
            """.trimIndent()

        val result = codec.decodeSyncControlPayload(payload)
        assertTrue(result is RecipeDecodeResult.Success)
        val cells = (result as RecipeDecodeResult.Success).value
        assertEquals(12L, cells.single().cancelThroughGeneration)
        assertEquals(11L, cells.single().serverLastAppliedGeneration)
    }

    @Test
    fun validateCommandTargetAuthority_unassignClear_rejectsTargetTuple() {
        val result =
            codec.validateCommandTargetAuthority(
                kind = RECIPE_UNASSIGN_CLEAR_KIND,
                targetRecipe =
                    RecipeEffectiveRecipeWire(
                        baseDrinkVolumeMl = 300,
                        waterDeciMl = 2700,
                        productDeciMl = 300,
                    ),
                targetBaseVersionId = "base-version",
            )
        assertTrue(result is RecipeDecodeResult.Invalid)
    }

    @Test
    fun validateCommandTargetAuthority_recipeBearing_requiresCompleteTuple() {
        val missingVersion =
            codec.validateCommandTargetAuthority(
                kind = "RESET",
                targetRecipe =
                    RecipeEffectiveRecipeWire(
                        baseDrinkVolumeMl = 300,
                        waterDeciMl = 2700,
                        productDeciMl = 300,
                    ),
                targetBaseVersionId = null,
            )
        assertTrue(missingVersion is RecipeDecodeResult.Invalid)

        val valid =
            codec.validateCommandTargetAuthority(
                kind = "RESET",
                targetRecipe =
                    RecipeEffectiveRecipeWire(
                        baseDrinkVolumeMl = 300,
                        waterDeciMl = 2700,
                        productDeciMl = 300,
                    ),
                targetBaseVersionId = "base-version",
            )
        assertTrue(valid is RecipeDecodeResult.Success)
    }

    @Test
    fun decodeCommand_rejectsInvalidTargetTriple() {
        val payload =
            """
            {
              "commandId": "cmd-1",
              "commandGeneration": "5",
              "kind": "RESET",
              "cellUuid": "cell-1",
              "targetRecipe": {
                "baseDrinkVolumeMl": 300,
                "waterDeciMl": 2700,
                "productDeciMl": 301
              },
              "targetBaseVersionId": "base-version"
            }
            """.trimIndent()

        val result = codec.decodeCommandPayload(payload)
        assertTrue(result is RecipeDecodeResult.Invalid)
    }

    @Test
    fun verifyOptionalFingerprint_logsMismatchButDoesNotThrow() {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        val outcome =
            codec.verifyOptionalFingerprint(
                triple = triple,
                optionalFingerprintHex = "0000000000000000000000000000000000000000000000000000000000000000",
            )
        assertEquals(RecipeFingerprintVerifyOutcome.MISMATCH_LOGGED, outcome)
    }

    @Test
    fun isRecipeCommandAckPayload_distinguishesFromContentAck() {
        val recipeAckJson =
            """
            {
              "acks": [{
                "commandId": "cmd-1",
                "commandGeneration": "1",
                "cellUuid": "cell-1",
                "status": "applied"
              }]
            }
            """.trimIndent()
        val recipeAck = codec.reportPayloadObject(recipeAckJson)
        val contentAck = buildJsonObject { put("ok", true); put("applied", 1) }

        assertTrue(codec.isRecipeCommandAckPayload(recipeAck))
        assertTrue(!codec.isRecipeCommandAckPayload(contentAck))
    }

    @Test
    fun encodeSyncRequestPayload_emitsEmptyCellsArray() {
        val payload = codec.encodeSyncRequestPayload()
        val cells = codec.reportPayloadObject(payload)["cells"]?.jsonArray
        assertTrue(cells == null || cells.isEmpty())
    }
}
