package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.outbox.RecipeOutboxTestFixtures
import com.viwa.android.data.local.recipe.CellAssignmentBaseStore
import com.viwa.android.data.local.recipe.CellEffectiveRecipeStore
import com.viwa.android.data.local.recipe.FakeCellAssignmentBaseDao
import com.viwa.android.data.local.recipe.FakeCellEffectiveRecipeDao
import com.viwa.android.data.remote.telemetry.mvp.cells.MvpRecipeSyncCapabilityDto
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeDownlinkOverflow
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeMessageCodec
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncCoordinator
import com.viwa.android.data.remote.telemetry.mvp.cells.TelemetryCellsMessageCodec
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.data.repository.TelemetryCellsRepositoryImpl
import com.viwa.android.domain.recipe.RecipeCommandApplier
import com.viwa.android.domain.recipe.RecipeSyncOrchestrator
import com.viwa.android.domain.telemetry.DefaultPhysicalCellSchemaProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelemetryCellsSyncCoordinatorOverflowTest {
    private val recipeMessageCodec = RecipeMessageCodec()
    private lateinit var recipeSyncCoordinator: RecipeSyncCoordinator
    private lateinit var coordinator: TelemetryCellsSyncCoordinator
    private var reconnectCount = 0

    @Before
    fun setUp() {
        reconnectCount = 0
        val configRepository = OverflowTestConfigRepository()
        val repository = TelemetryCellsRepositoryImpl(configRepository)
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        coEvery { wsManager.currentSessionGeneration() } returns 1L
        coEvery { wsManager.sendEnvelope(any(), any(), any()) } returns Result.success("msg-1")
        val recipeDao = FakeCellEffectiveRecipeDao()
        val outboxStack = RecipeOutboxTestFixtures.createOutboxStack(recipeDao = recipeDao)
        val effectiveStore =
            CellEffectiveRecipeStore(
                dao = recipeDao,
                featureEnabled = { true },
            )
        recipeSyncCoordinator = RecipeSyncCoordinator.forTests(recipeMessageCodec)
        val orchestrator =
            RecipeSyncOrchestrator(
                wsCoordinator = recipeSyncCoordinator,
                inbox = outboxStack.inbox(RecipeCommandApplier(effectiveStore), effectiveStore),
                effectiveRecipeStore = effectiveStore,
                assignmentBaseStore = CellAssignmentBaseStore.forTests(FakeCellAssignmentBaseDao()),
            )
        coordinator =
            TelemetryCellsSyncCoordinator(
                repository = repository,
                codec = TelemetryCellsMessageCodec(),
                schemaProvider = DefaultPhysicalCellSchemaProvider(),
                uuidAllocator = mockk(relaxed = true),
                wsManager = wsManager,
                contentReportAckAwaiter = mockk(relaxed = true),
                waterCalibrationService = mockk(relaxed = true),
                conversionFactorMigration = mockk(relaxed = true),
                syrupCalibrationInventory = mockk(relaxed = true),
                effectiveRecipeStore = effectiveStore,
                recipeMessageCodec = recipeMessageCodec,
                recipeSyncCoordinator = recipeSyncCoordinator,
                recipeSyncOrchestrator = orchestrator,
                recipeOutboxStore = outboxStack.recipeOutboxStore,
                outboxDrainCoordinator = mockk(relaxed = true),
                appScope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            )
    }

    @Test
    fun `overflow triggers single reconnect and resets recipe sync state`() = runTest {
        // given — test debounce/reset via direct handler (production wiring uses startRecipeDownlinkOverflowHandling)
        var reconnectReason: String? = null
        recipeSyncCoordinator.configureFromHello(recipeHello())
        recipeSyncCoordinator.beginInitialSync()
        recipeSyncCoordinator.completeUplinkPhase(success = true)
        recipeSyncCoordinator.markManagedModeReady()
        assertTrue(recipeSyncCoordinator.isManagedModeActive())

        // when — second call within debounce window must not reconnect again
        val overflow =
            RecipeDownlinkOverflow(
                bufferedPreFence = 256,
                channelCapacity = 128,
                droppedEventType = "SyncControl",
            )
        coordinator.handleRecipeDownlinkOverflow(overflow) { reason ->
            reconnectCount++
            reconnectReason = reason
        }
        coordinator.handleRecipeDownlinkOverflow(overflow) { reason ->
            reconnectCount++
        }

        // then
        assertEquals(1, reconnectCount)
        assertEquals("recipe-downlink-overflow", reconnectReason)
        assertFalse(recipeSyncCoordinator.isManagedModeActive())
    }

    private fun recipeHello() =
        MvpHelloPayloadDto(
            serialNumber = "VIWA-1",
            protocolVersion = 4,
            capabilities =
                MvpHelloCapabilitiesDto(
                    recipeSync = MvpRecipeSyncCapabilityDto(),
                ),
        )
}

private class OverflowTestConfigRepository : ConfigRepository {
    override suspend fun get(key: String): String? = null

    override suspend fun set(key: String, value: String) = Unit

    override suspend fun delete(key: String) = Unit

    override suspend fun getJson(key: String): String? = null

    override suspend fun setJson(key: String, json: String) = Unit
}
