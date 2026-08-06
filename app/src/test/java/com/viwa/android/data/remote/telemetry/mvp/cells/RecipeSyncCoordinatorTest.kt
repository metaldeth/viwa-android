package com.viwa.android.data.remote.telemetry.mvp.cells

import app.cash.turbine.test
import com.viwa.android.data.remote.telemetry.mvp.MvpHelloPayloadDto
import com.viwa.android.data.remote.telemetry.mvp.MvpHelloCapabilitiesDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecipeSyncCoordinatorTest {

    private lateinit var codec: RecipeMessageCodec
    private lateinit var coordinator: RecipeSyncCoordinator

    @Before
    fun setUp() {
        codec = RecipeMessageCodec()
        coordinator = RecipeSyncCoordinator.forTests(codec)
    }

    @Test
    fun managedModeInactiveForOldHello() {
        coordinator.configureFromHello(
            MvpHelloPayloadDto(serialNumber = "VIWA-1", protocolVersion = 3),
        )
        assertFalse(coordinator.isHelloEligible())
        assertFalse(coordinator.isManagedModeActive())
    }

    @Test
    fun buffersDownlinkUntilUplinkPhaseComplete() = runTest {
        coordinator.configureFromHello(recipeHello())
        coordinator.beginInitialSync()

        coordinator.handleSyncControl(
            """
            {"cells":[{"cellUuid":"cell-1","cancelThroughGeneration":"1","serverLastAppliedGeneration":"0"}]}
            """.trimIndent(),
        )
        assertEquals(1, coordinator.bufferedDownlinkCount())
        assertFalse(coordinator.shouldProcessRecipeDownlink())

        coordinator.downlinkEvents.test {
            coordinator.completeUplinkPhase(success = true)
            coordinator.markManagedModeReady()
            val event = awaitItem()
            assertTrue(event is RecipeDownlinkEvent.SyncControl)
            expectNoEvents()
        }
        assertTrue(coordinator.isManagedModeActive())
    }

    @Test
    fun uplinkFailureKeepsFenceClosed() = runTest {
        coordinator.configureFromHello(recipeHello())
        coordinator.beginInitialSync()
        coordinator.completeUplinkPhase(success = false)
        assertFalse(coordinator.isUplinkPhaseComplete())
        assertFalse(coordinator.isManagedModeActive())
    }

    @Test
    fun resetOnDisconnectClearsBufferAndGate() = runTest {
        coordinator.configureFromHello(recipeHello())
        coordinator.beginInitialSync()
        coordinator.handleCommand(
            """
            {
              "commandId":"cmd-1",
              "commandGeneration":"2",
              "kind":"UNASSIGN_CLEAR",
              "cellUuid":"cell-1"
            }
            """.trimIndent(),
        )
        assertEquals(1, coordinator.bufferedDownlinkCount())

        coordinator.resetOnDisconnect()

        assertFalse(coordinator.isHelloEligible())
        assertFalse(coordinator.isManagedModeActive())
        assertEquals(0, coordinator.bufferedDownlinkCount())
    }

    private fun recipeHello(): MvpHelloPayloadDto =
        MvpHelloPayloadDto(
            serialNumber = "VIWA-1",
            protocolVersion = 4,
            capabilities =
                MvpHelloCapabilitiesDto(
                    recipeSync = MvpRecipeSyncCapabilityDto(),
                ),
        )
}
