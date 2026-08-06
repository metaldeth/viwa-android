package com.viwa.android.data.remote.telemetry.mvp.cells

import app.cash.turbine.test
import com.viwa.android.domain.recipe.AssignmentStatus
import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecipeSyncCoordinatorLosslessTest {
    private lateinit var coordinator: RecipeSyncCoordinator

    @Before
    fun setUp() {
        coordinator = RecipeSyncCoordinator.forTests()
    }

    @Test
    fun `pre-fence buffer preserves sync control before command flush order`() = runTest {
        coordinator.configureFromHello(recipeHello())
        coordinator.beginInitialSync()

        val controlPayload =
            """
            {"cells":[{"cellUuid":"cell-1","cancelThroughGeneration":"1","serverLastAppliedGeneration":"0"}]}
            """.trimIndent()
        val commandPayload =
            """
            {
              "commandId":"cmd-1",
              "commandGeneration":"2",
              "kind":"UNASSIGN_CLEAR",
              "cellUuid":"cell-1"
            }
            """.trimIndent()

        coordinator.handleSyncControl(controlPayload)
        coordinator.handleCommand(commandPayload)
        assertEquals(2, coordinator.bufferedDownlinkCount())

        coordinator.downlinkEvents.test {
            coordinator.completeUplinkPhase(success = true)
            val first = awaitItem()
            val second = awaitItem()
            assertTrue(first is RecipeDownlinkEvent.SyncControl)
            assertTrue(second is RecipeDownlinkEvent.Command)
            expectNoEvents()
        }
    }

    @Test
    fun `overflow emits signal instead of silent drop when pre-fence buffer exceeded`() = runTest {
        coordinator.configureFromHello(recipeHello())
        coordinator.beginInitialSync()

        coordinator.overflowEvents.test {
            repeat(RecipeSyncCoordinator.MAX_PRE_FENCE_BUFFER + 1) { index ->
                coordinator.handleSyncControl(
                    """
                    {"cells":[{"cellUuid":"cell-$index","cancelThroughGeneration":"0","serverLastAppliedGeneration":"0"}]}
                    """.trimIndent(),
                )
            }

            assertEquals(RecipeSyncCoordinator.MAX_PRE_FENCE_BUFFER, coordinator.bufferedDownlinkCount())
            val overflow = awaitItem()
            assertEquals(RecipeSyncCoordinator.MAX_PRE_FENCE_BUFFER, overflow.bufferedPreFence)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `decodeSyncControl parses assignment block`() {
        val codec = RecipeMessageCodec()
        val payload =
            """
            {
              "cells": [{
                "cellUuid": "cell-1",
                "cancelThroughGeneration": "0",
                "serverLastAppliedGeneration": "0",
                "assignment": {
                  "status": "assigned",
                  "productId": "prod-1",
                  "currentBaseVersionId": "base-1",
                  "baseRecipeRevision": 2,
                  "currentBaseRecipe": {
                    "baseDrinkVolumeMl": 300,
                    "waterDeciMl": 2700,
                    "productDeciMl": 300
                  },
                  "currentBaseFingerprint": "${CellEffectiveRecipeDefaults.legacyFingerprint}"
                }
              }]
            }
            """.trimIndent()

        val result = codec.decodeSyncControlPayload(payload)
        assertTrue(result is RecipeDecodeResult.Success)
        val cell = (result as RecipeDecodeResult.Success).value.single()
        assertEquals(AssignmentStatus.ASSIGNED, cell.assignment!!.status)
        assertEquals("prod-1", cell.assignment!!.productId)
    }

    private fun recipeHello() =
        com.viwa.android.data.remote.telemetry.mvp.MvpHelloPayloadDto(
            serialNumber = "VIWA-1",
            protocolVersion = 4,
            capabilities =
                com.viwa.android.data.remote.telemetry.mvp.MvpHelloCapabilitiesDto(
                    recipeSync = MvpRecipeSyncCapabilityDto(),
                ),
        )
}
