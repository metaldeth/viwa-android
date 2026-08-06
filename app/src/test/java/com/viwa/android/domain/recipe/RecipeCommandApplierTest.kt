package com.viwa.android.domain.recipe

import com.viwa.android.data.local.outbox.RecipeOutboxTestFixtures
import com.viwa.android.data.local.recipe.CellEffectiveRecipeStore
import com.viwa.android.data.local.recipe.FakeCellEffectiveRecipeDao
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeCommandDownlink
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeMessageCodec
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncCoordinator
import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import com.viwa.android.domain.recipe.CellEffectiveRecipeSource
import com.viwa.android.domain.recipe.RecipeCanonical
import com.viwa.android.domain.recipe.RecipeCommandAckStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecipeCommandApplierTest {
    private lateinit var store: CellEffectiveRecipeStore
    private lateinit var applier: RecipeCommandApplier
    private lateinit var inbox: RecipeCommandInbox
    private lateinit var ackEmitter: RecipeCommandAckEmitter

    private lateinit var outboxStack: RecipeOutboxTestFixtures.RecipeOutboxTestStack

    @Before
    fun setUp() {
        outboxStack = RecipeOutboxTestFixtures.createOutboxStack()
        store =
            CellEffectiveRecipeStore(
                dao = outboxStack.recipeDao,
                featureEnabled = { true },
                clock = { 1_000L },
            )
        store.setRuntimeManagedModeActive(true)
        applier = RecipeCommandApplier(store)
        ackEmitter = RecipeCommandAckEmitter(outboxStack.recipeOutboxStore, drainCoordinator = null)
        inbox = outboxStack.inbox(applier, store)
    }

    @Test
    fun `watermark skip returns cancelled without changing effective`() = runTest {
        store.advanceCancelWatermark("cell-1", 10L)
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        val result =
            applier.apply(
                command(
                    generation = 10L,
                    kind = "RESET",
                    triple = triple,
                ),
            )
        val ack = applier.buildAck(result)
        assertEquals(RecipeCommandAckStatus.CANCELLED, ack.status)
        assertFalse(store.getEffective("cell-1")!!.isRecipeComplete)
    }

    @Test
    fun `conditional apply skips when fingerprint diverged`() = runTest {
        store.applyLocalEffectiveRecipe(
            cellId = "cell-1",
            triple = CellEffectiveRecipeDefaults.legacyTriple,
            source = CellEffectiveRecipeSource.LOCAL_EDIT,
        )
        val target =
            RecipeCanonicalTriple(
                baseDrinkVolumeMl = 300,
                waterDeciMl = 2750,
                productDeciMl = 250,
            )
        val result =
            applier.apply(
                command(
                    generation = 2L,
                    kind = "CAMPAIGN_CONDITIONAL_APPLY",
                    triple = target,
                    fromFingerprint = "0000000000000000000000000000000000000000000000000000000000000000",
                ),
            )
        assertEquals(RecipeCommandAckStatus.SKIPPED_DIVERGED, applier.buildAck(result).status)
        assertEquals(CellEffectiveRecipeDefaults.legacyFingerprint, store.getEffective("cell-1")!!.fingerprint)
    }

    @Test
    fun `force apply applies even when local differed`() = runTest {
        store.applyLocalEffectiveRecipe(
            cellId = "cell-1",
            triple = CellEffectiveRecipeDefaults.legacyTriple,
            source = CellEffectiveRecipeSource.LOCAL_EDIT,
        )
        val target =
            RecipeCanonicalTriple(
                baseDrinkVolumeMl = 300,
                waterDeciMl = 2600,
                productDeciMl = 400,
            )
        val result =
            applier.apply(
                command(
                    generation = 3L,
                    kind = "REMOTE_RECIPE_SET",
                    triple = target,
                ),
            )
        assertEquals(RecipeCommandAckStatus.APPLIED, applier.buildAck(result).status)
        assertEquals(400, store.getEffective("cell-1")!!.productDeciMl)
    }

    @Test
    fun `duplicate commandId redelivery emits identical ack without reapply`() = runTest {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        val cmd = command(id = "cmd-dup", generation = 5L, kind = "RESET", triple = triple)
        val first = applier.apply(cmd)
        val revisionAfterFirst = store.peekDeviceReportRevision("cell-1")
        val second = applier.apply(cmd)
        assertTrue(first is CellEffectiveRecipeStore.ManagedCommandApplyResult.Processed)
        assertTrue(second is CellEffectiveRecipeStore.ManagedCommandApplyResult.Redelivered)
        assertEquals(applier.buildAck(first).status, applier.buildAck(second).status)
        assertEquals(revisionAfterFirst, store.peekDeviceReportRevision("cell-1"))
    }

    @Test
    fun `inbox persists sync control before command processing`() = runTest {
        inbox.enqueueCommand(
            command(
                id = "cmd-early",
                generation = 2L,
                kind = "UNASSIGN_CLEAR",
            ),
        )
        assertEquals(0, inbox.drain())
        inbox.enqueueSyncControl(
            listOf(
                com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncControlCell(
                    cellUuid = "cell-1",
                    cancelThroughGeneration = 0L,
                    serverLastAppliedGeneration = 0L,
                ),
            ),
        )
        assertEquals(1, inbox.drain())
    }

    @Test
    fun `unassign clear clears effective but preserves watermark state`() = runTest {
        store.applyLocalEffectiveRecipe(
            cellId = "cell-1",
            triple = CellEffectiveRecipeDefaults.legacyTriple,
            source = CellEffectiveRecipeSource.COMMAND,
        )
        store.advanceCancelWatermark("cell-1", 4L)
        val result =
            applier.apply(
                command(
                    generation = 5L,
                    kind = "UNASSIGN_CLEAR",
                ),
            )
        assertEquals(RecipeCommandAckStatus.APPLIED, applier.buildAck(result).status)
        val row = store.getEffective("cell-1")
        assertFalse(row!!.isRecipeComplete)
        assertEquals(4L, row.cancelThroughGeneration)
        assertEquals(5L, row.lastAppliedCommandGeneration)
    }

    @Test
    fun `unsupported kind returns failed validation`() = runTest {
        val result =
            applier.apply(
                command(
                    generation = 1L,
                    kind = "MYSTERY_KIND",
                    triple = CellEffectiveRecipeDefaults.legacyTriple,
                ),
            )
        assertEquals(RecipeCommandAckStatus.FAILED, applier.buildAck(result).status)
    }

    private fun command(
        id: String = "cmd-1",
        generation: Long,
        kind: String,
        triple: RecipeCanonicalTriple? = null,
        fromFingerprint: String? = null,
    ): RecipeCommandDownlink =
        RecipeCommandDownlink(
            commandId = id,
            commandGeneration = generation,
            kind = kind,
            cellUuid = "cell-1",
            targetRecipe = triple,
            targetBaseVersionId = if (kind == "UNASSIGN_CLEAR") null else "base-1",
            fromFingerprint = fromFingerprint,
            fromBaseVersionId = null,
            campaignId = null,
            resetBatchId = null,
        )
}
