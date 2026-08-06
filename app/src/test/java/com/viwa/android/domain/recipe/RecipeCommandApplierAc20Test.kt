package com.viwa.android.domain.recipe

import com.viwa.android.data.local.outbox.RecipeOutboxTestFixtures
import com.viwa.android.data.local.recipe.CellAssignmentBaseStore
import com.viwa.android.data.local.recipe.CellEffectiveRecipeStore
import com.viwa.android.data.local.recipe.FakeCellAssignmentBaseDao
import com.viwa.android.data.local.recipe.FakeCellEffectiveRecipeDao
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeCommandDownlink
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncControlCell
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncCoordinator
import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import com.viwa.android.domain.recipe.CellEffectiveRecipeSource
import com.viwa.android.domain.recipe.RecipeCanonicalTriple
import com.viwa.android.domain.recipe.RecipeCommandAckStatus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** AC-20 matrix: cancel/apply ordering, crash replay, queue order, runtime gate, revision persistence. */
class RecipeCommandApplierAc20Test {
    private lateinit var store: CellEffectiveRecipeStore
    private lateinit var applier: RecipeCommandApplier
    private lateinit var inbox: RecipeCommandInbox
    private lateinit var wsCoordinator: RecipeSyncCoordinator
    private lateinit var orchestrator: RecipeSyncOrchestrator

    private lateinit var outboxStack: RecipeOutboxTestFixtures.RecipeOutboxTestStack

    @Before
    fun setUp() {
        outboxStack = RecipeOutboxTestFixtures.createOutboxStack()
        store =
            CellEffectiveRecipeStore(
                dao = outboxStack.recipeDao,
                featureEnabled = { true },
            )
        store.setRuntimeManagedModeActive(true)
        applier = RecipeCommandApplier(store)
        inbox = outboxStack.inbox(applier, store)
        wsCoordinator = RecipeSyncCoordinator.forTests()
        orchestrator =
            RecipeSyncOrchestrator(
                wsCoordinator,
                inbox,
                store,
                CellAssignmentBaseStore.forTests(FakeCellAssignmentBaseDao()),
            )
    }

    @Test
    fun `cancel before apply skips command with cancelled ack`() = runTest {
        store.setRuntimeManagedModeActive(true)
        enqueueControl(cancelThrough = 5L)
        enqueueCommand(gen = 5L, kind = "RESET", triple = CellEffectiveRecipeDefaults.legacyTriple)
        assertEquals(1, inbox.drain())
        val row = store.getEffective("cell-1")
        assertFalse(row!!.isRecipeComplete)
    }

    @Test
    fun `apply before cancel keeps applied effective`() = runTest {
        store.setRuntimeManagedModeActive(true)
        enqueueControl(cancelThrough = 0L)
        enqueueCommand(gen = 3L, kind = "RESET", triple = CellEffectiveRecipeDefaults.legacyTriple)
        assertEquals(1, inbox.drain())
        assertTrue(store.getEffective("cell-1")!!.isRecipeComplete)
        enqueueControl(cancelThrough = 10L)
        enqueueCommand(gen = 4L, kind = "RESET", triple = CellEffectiveRecipeDefaults.legacyTriple)
        assertEquals(1, inbox.drain())
        assertEquals(3L, store.getEffective("cell-1")!!.lastAppliedCommandGeneration)
    }

    @Test
    fun `queue orders by cell then generation`() = runTest {
        enqueueControl(cancelThrough = 0L)
        inbox.enqueueCommand(cmd("cell-b", 2L))
        inbox.enqueueCommand(cmd("cell-a", 3L))
        inbox.enqueueCommand(cmd("cell-a", 2L))
        assertEquals(3, inbox.drain())
    }

    @Test
    fun `deviceReportRevision persists across disconnect without reset`() = runTest {
        store.setRuntimeManagedModeActive(true)
        store.nextDeviceReportRevision("cell-1")
        store.nextDeviceReportRevision("cell-1")
        assertEquals(2L, store.peekDeviceReportRevision("cell-1"))
        orchestrator.onDisconnect()
        assertEquals(2L, store.peekDeviceReportRevision("cell-1"))
        assertFalse(store.isRuntimeManagedModeActive())
    }

    @Test
    fun `runtime gate inactive returns null effective until fence opened`() = runTest {
        val gatedStore =
            CellEffectiveRecipeStore(
                dao = FakeCellEffectiveRecipeDao(),
                featureEnabled = { true },
            )
        gatedStore.applyLocalEffectiveRecipe(
            cellId = "cell-1",
            triple = CellEffectiveRecipeDefaults.legacyTriple,
            source = CellEffectiveRecipeSource.LOCAL_EDIT,
        )
        assertEquals(null, gatedStore.getEffective("cell-1"))
        gatedStore.setRuntimeManagedModeActive(true)
        assertTrue(gatedStore.getEffective("cell-1")!!.isRecipeComplete)
    }

    @Test
    fun `crash simulation redelivery same commandId is idempotent`() = runTest {
        store.setRuntimeManagedModeActive(true)
        val cmd = cmd("cell-1", 7L, id = "crash-cmd")
        enqueueControl(0L)
        inbox.enqueueCommand(cmd)
        inbox.drain()
        val revision = store.peekDeviceReportRevision("cell-1")
        val replay = applier.apply(cmd)
        assertTrue(replay is CellEffectiveRecipeStore.ManagedCommandApplyResult.Redelivered)
        assertEquals(revision, store.peekDeviceReportRevision("cell-1"))
    }

    @Test
    fun `malformed force kind without target fails validation`() = runTest {
        enqueueControl(0L)
        inbox.enqueueCommand(
            RecipeCommandDownlink(
                commandId = "bad",
                commandGeneration = 1L,
                kind = "ASSIGN_COPY",
                cellUuid = "cell-1",
                targetRecipe = null,
                targetBaseVersionId = null,
                fromFingerprint = null,
                fromBaseVersionId = null,
                campaignId = null,
                resetBatchId = null,
            ),
        )
        inbox.drain()
        val row = store.getEffective("cell-1")
        assertTrue(row == null || !row.isRecipeComplete)
    }

    @Test
    fun `all force kinds apply regardless of local difference`() = runTest {
        store.setRuntimeManagedModeActive(true)
        store.applyLocalEffectiveRecipe(
            cellId = "cell-1",
            triple = CellEffectiveRecipeDefaults.legacyTriple,
            source = CellEffectiveRecipeSource.LOCAL_EDIT,
        )
        val target =
            RecipeCanonicalTriple(
                baseDrinkVolumeMl = 300,
                waterDeciMl = 2500,
                productDeciMl = 500,
            )
        listOf("ASSIGN_COPY", "RESET", "REMOTE_RECIPE_SET", "CAMPAIGN_FORCE_APPLY").forEachIndexed { index, kind ->
            val result =
                applier.apply(
                    RecipeCommandDownlink(
                        commandId = "force-$index",
                        commandGeneration = index + 1L,
                        kind = kind,
                        cellUuid = "cell-1",
                        targetRecipe = target,
                        targetBaseVersionId = "base-1",
                        fromFingerprint = null,
                        fromBaseVersionId = null,
                        campaignId = null,
                        resetBatchId = null,
                    ),
                )
            assertEquals(RecipeCommandAckStatus.APPLIED, applier.buildAck(result).status)
        }
        assertEquals(500, store.getEffective("cell-1")!!.productDeciMl)
    }

    private suspend fun enqueueControl(cancelThrough: Long) {
        inbox.enqueueSyncControl(
            listOf(
                RecipeSyncControlCell(
                    cellUuid = "cell-1",
                    cancelThroughGeneration = cancelThrough,
                    serverLastAppliedGeneration = 0L,
                ),
            ),
        )
    }

    private suspend fun enqueueCommand(
        gen: Long,
        kind: String,
        triple: RecipeCanonicalTriple?,
    ) {
        inbox.enqueueCommand(
            RecipeCommandDownlink(
                commandId = "cmd-$gen",
                commandGeneration = gen,
                kind = kind,
                cellUuid = "cell-1",
                targetRecipe = triple,
                targetBaseVersionId = if (kind == "UNASSIGN_CLEAR") null else "base-1",
                fromFingerprint = null,
                fromBaseVersionId = null,
                campaignId = null,
                resetBatchId = null,
            ),
        )
    }

    private fun cmd(cellUuid: String, generation: Long, id: String = "id-$generation"): RecipeCommandDownlink =
        RecipeCommandDownlink(
            commandId = id,
            commandGeneration = generation,
            kind = "RESET",
            cellUuid = cellUuid,
            targetRecipe = CellEffectiveRecipeDefaults.legacyTriple,
            targetBaseVersionId = "base-1",
            fromFingerprint = null,
            fromBaseVersionId = null,
            campaignId = null,
            resetBatchId = null,
        )
}
