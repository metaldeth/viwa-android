package com.viwa.android.domain.recipe

import com.viwa.android.data.local.outbox.RecipeOutboxStore
import com.viwa.android.data.local.outbox.RecipeOutboxTestFixtures
import com.viwa.android.data.local.recipe.CellEffectiveRecipeStore
import com.viwa.android.data.remote.telemetry.mvp.cells.RECIPE_UNASSIGN_CLEAR_KIND
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeCommandDownlink
import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import com.viwa.android.domain.recipe.CellEffectiveRecipeSource
import com.viwa.android.domain.recipe.RecipeCanonical
import com.viwa.android.domain.recipe.RecipeCommandAckStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Process-death recovery for all terminal command ack categories (task-17 resilience). */
class RecipeTerminalAckRecoveryTest {
    private lateinit var stack: RecipeOutboxTestFixtures.RecipeOutboxTestStack
    private lateinit var store: CellEffectiveRecipeStore
    private lateinit var recipeOutboxStore: RecipeOutboxStore

    @Before
    fun setUp() {
        stack = RecipeOutboxTestFixtures.createOutboxStack()
        store =
            CellEffectiveRecipeStore(
                dao = stack.recipeDao,
                featureEnabled = { true },
            )
        store.setRuntimeManagedModeActive(true)
        recipeOutboxStore = stack.recipeOutboxStore
    }

    @Test
    fun `recover enqueues cancelled terminal ack after crash before outbox`() = runTest {
        store.advanceCancelWatermark("cell-1", 5L)
        applyWithoutOutbox(
            cmd(
                id = "cmd-cancel",
                gen = 3L,
                kind = "RESET",
                triple = CellEffectiveRecipeDefaults.legacyTriple,
            ),
        )

        val recovered = recipeOutboxStore.recoverPendingTerminalAcks()

        assertEquals(1, recovered)
        assertEquals("cmd-cancel|cancelled|3", stack.persistence.allRows().single().idempotencyKey)
    }

    @Test
    fun `recover enqueues superseded terminal ack after crash before outbox`() = runTest {
        applyWithoutOutbox(
            cmd(id = "cmd-first", gen = 5L, kind = "RESET", triple = CellEffectiveRecipeDefaults.legacyTriple),
        )
        applyWithoutOutbox(
            cmd(id = "cmd-stale", gen = 3L, kind = "RESET", triple = CellEffectiveRecipeDefaults.legacyTriple),
        )

        val recovered = recipeOutboxStore.recoverPendingTerminalAcks()

        assertEquals(1, recovered)
        assertEquals("cmd-stale|superseded|3", stack.persistence.allRows().single().idempotencyKey)
    }

    @Test
    fun `recover enqueues skipped diverged terminal ack after crash before outbox`() = runTest {
        store.applyLocalEffectiveRecipe(
            cellId = "cell-1",
            triple = CellEffectiveRecipeDefaults.legacyTriple,
            source = CellEffectiveRecipeSource.LOCAL_EDIT,
        )
        val fingerprint = store.getEffective("cell-1")!!.fingerprint!!
        val wrongFingerprint = fingerprint.dropLast(1) + if (fingerprint.last() == '0') "1" else "0"
        applyWithoutOutbox(
            RecipeCommandDownlink(
                commandId = "cmd-skip",
                commandGeneration = 4L,
                kind = "CAMPAIGN_CONDITIONAL_APPLY",
                cellUuid = "cell-1",
                targetRecipe = CellEffectiveRecipeDefaults.legacyTriple,
                targetBaseVersionId = "base-v1",
                fromFingerprint = wrongFingerprint,
                fromBaseVersionId = null,
                campaignId = "camp",
                resetBatchId = null,
            ),
        )
        assertEquals(fingerprint, store.getEffective("cell-1")!!.fingerprint)

        val recovered = recipeOutboxStore.recoverPendingTerminalAcks()

        assertEquals(1, recovered)
        assertEquals("cmd-skip|skipped_diverged|4", stack.persistence.allRows().single().idempotencyKey)
    }

    @Test
    fun `recover enqueues failed terminal ack with failure code after crash before outbox`() = runTest {
        applyWithoutOutbox(
            RecipeCommandDownlink(
                commandId = "cmd-fail",
                commandGeneration = 2L,
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

        val recovered = recipeOutboxStore.recoverPendingTerminalAcks()

        assertEquals(1, recovered)
        val row = stack.persistence.allRows().single()
        assertEquals("cmd-fail|failed|2", row.idempotencyKey)
        val failureCode =
            Json.parseToJsonElement(row.payloadJson)
                .jsonObject["acks"]!!
                .jsonArray
                .single()
                .jsonObject["failureCode"]!!
                .jsonPrimitive
                .content
        assertEquals("INVALID_TARGET", failureCode)
    }

    @Test
    fun `recover enqueues applied terminal ack with recipe after crash before outbox`() = runTest {
        applyWithoutOutbox(
            cmd(id = "cmd-applied", gen = 7L, kind = "RESET", triple = CellEffectiveRecipeDefaults.legacyTriple),
        )

        val recovered = recipeOutboxStore.recoverPendingTerminalAcks()

        assertEquals(1, recovered)
        assertEquals("cmd-applied|applied|7", stack.persistence.allRows().single().idempotencyKey)
        assertTrue(store.getEffective("cell-1")!!.isRecipeComplete)
    }

    @Test
    fun `recover enqueues unassign applied ack without applied recipe payload`() = runTest {
        store.applyLocalEffectiveRecipe(
            cellId = "cell-1",
            triple = CellEffectiveRecipeDefaults.legacyTriple,
            source = CellEffectiveRecipeSource.LOCAL_EDIT,
        )
        applyWithoutOutbox(
            RecipeCommandDownlink(
                commandId = "cmd-unassign",
                commandGeneration = 8L,
                kind = RECIPE_UNASSIGN_CLEAR_KIND,
                cellUuid = "cell-1",
                targetRecipe = null,
                targetBaseVersionId = null,
                fromFingerprint = null,
                fromBaseVersionId = null,
                campaignId = null,
                resetBatchId = null,
            ),
        )
        assertFalse(store.getEffective("cell-1")!!.isRecipeComplete)

        val recovered = recipeOutboxStore.recoverPendingTerminalAcks()

        assertEquals(1, recovered)
        val payload =
            Json.parseToJsonElement(stack.persistence.allRows().single().payloadJson)
                .jsonObject["acks"]!!
                .jsonArray
                .single()
                .jsonObject
        assertEquals("cmd-unassign|applied|8", stack.persistence.allRows().single().idempotencyKey)
        assertNull(payload["appliedRecipe"])
    }

    @Test
    fun `redelivery after crash uses persisted terminal without server frame`() = runTest {
        val command =
            cmd(id = "cmd-redeliver", gen = 6L, kind = "RESET", triple = CellEffectiveRecipeDefaults.legacyTriple)
        applyWithoutOutbox(command)
        recipeOutboxStore.recoverPendingTerminalAcks()
        val revision = store.peekDeviceReportRevision("cell-1")

        val replay = store.applyManagedCommand(command)

        assertTrue(replay is CellEffectiveRecipeStore.ManagedCommandApplyResult.Redelivered)
        assertEquals(revision, store.peekDeviceReportRevision("cell-1"))
        val ack = (replay as CellEffectiveRecipeStore.ManagedCommandApplyResult.Redelivered).ack
        assertEquals(RecipeCommandAckStatus.APPLIED, ack.status)
        assertEquals(6L, ack.commandGeneration)
    }

    @Test
    fun `duplicate recovery coalesces to single outbox row`() = runTest {
        applyWithoutOutbox(
            cmd(id = "cmd-dup", gen = 1L, kind = "RESET", triple = CellEffectiveRecipeDefaults.legacyTriple),
        )

        assertEquals(1, recipeOutboxStore.recoverPendingTerminalAcks())
        assertEquals(0, recipeOutboxStore.recoverPendingTerminalAcks())
        assertEquals(1, stack.persistence.allRows().size)
    }

    @Test
    fun `mark delivered prevents further recovery while preserving dedup fields`() = runTest {
        applyWithoutOutbox(
            cmd(id = "cmd-delivered", gen = 2L, kind = "RESET", triple = CellEffectiveRecipeDefaults.legacyTriple),
        )
        recipeOutboxStore.recoverPendingTerminalAcks()
        val row = stack.persistence.allRows().single()

        assertTrue(recipeOutboxStore.markCommandAckDelivered(row.idempotencyKey))
        assertEquals(0, recipeOutboxStore.recoverPendingTerminalAcks())

        val entity = stack.recipeDao.findByCellId("cell-1")!!
        assertTrue(entity.terminalAckDelivered)
        assertEquals("cmd-delivered", entity.lastAppliedCommandId)
        assertEquals(RecipeCommandAckStatus.APPLIED, entity.lastTerminalAckStatus)
    }

    private suspend fun applyWithoutOutbox(command: RecipeCommandDownlink) {
        val result = store.applyManagedCommand(command)
        assertTrue(result is CellEffectiveRecipeStore.ManagedCommandApplyResult.Processed)
    }

    private fun cmd(
        id: String,
        gen: Long,
        kind: String = "RESET",
        triple: com.viwa.android.domain.recipe.RecipeCanonicalTriple? = null,
    ): RecipeCommandDownlink =
        RecipeCommandDownlink(
            commandId = id,
            commandGeneration = gen,
            kind = kind,
            cellUuid = "cell-1",
            targetRecipe = triple,
            targetBaseVersionId = if (triple != null) "base-v1" else null,
            fromFingerprint = null,
            fromBaseVersionId = null,
            campaignId = null,
            resetBatchId = null,
        )
}
