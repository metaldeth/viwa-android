package com.viwa.android.data.local.outbox

import com.viwa.android.data.local.recipe.CellEffectiveRecipeStore
import com.viwa.android.data.local.recipe.FakeCellEffectiveRecipeDao
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeCommandAckEntry
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeMessageCodec
import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import com.viwa.android.domain.recipe.CellEffectiveRecipeSource
import com.viwa.android.domain.recipe.RecipeCommandAckStatus
import com.viwa.android.data.repository.ConfigRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecipeOutboxStoreTest {
    private lateinit var persistence: FakeMachineOutboxPersistence
    private lateinit var outboxStore: MachineOutboxStore
    private lateinit var recipeDao: FakeCellEffectiveRecipeDao
    private lateinit var recipeOutboxStore: RecipeOutboxStore
    private val codec = RecipeMessageCodec()

    @Before
    fun setUp() {
        persistence = FakeMachineOutboxPersistence()
        val config = FakeConfigRepository()
        outboxStore =
            MachineOutboxStore(
                persistence = persistence,
                configRepository = config,
                migrator = PendingSalesOutboxMigrator(persistence, config),
            )
        recipeDao = FakeCellEffectiveRecipeDao()
        recipeOutboxStore =
            RecipeOutboxStore(
                outboxStore = outboxStore,
                recipeCodec = codec,
                effectiveRecipeDao = recipeDao,
            )
    }

    @Test
    fun `enqueueRecipeReport uses cell revision idempotency key and wire kind`() = runTest {
        val store =
            CellEffectiveRecipeStore(
                dao = recipeDao,
                featureEnabled = { true },
            )
        val recipe =
            store.applyLocalEffectiveRecipe(
                cellId = "cell-1",
                triple = CellEffectiveRecipeDefaults.legacyTriple,
                source = CellEffectiveRecipeSource.LOCAL_EDIT,
            )

        val result = recipeOutboxStore.enqueueRecipeReport(recipe)

        assertTrue(result is MachineOutboxStore.EnqueueResult.Inserted)
        val row = persistence.allRows().single()
        assertEquals(MachineOutboxKind.CELLS_RECIPE_REPORT.wireValue, row.kind)
        assertEquals("cell-1|${recipe.deviceReportRevision}", row.idempotencyKey)
        val cells = Json.parseToJsonElement(row.payloadJson).jsonObject["cells"]!!.jsonArray
        assertEquals(1, cells.size)
        assertEquals("cell-1", cells.single().jsonObject["cellUuid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `duplicate report idempotency coalesces not duplicates`() = runTest {
        val store =
            CellEffectiveRecipeStore(
                dao = recipeDao,
                featureEnabled = { true },
            )
        val recipe =
            store.applyLocalEffectiveRecipe(
                cellId = "cell-1",
                triple = CellEffectiveRecipeDefaults.legacyTriple,
                source = CellEffectiveRecipeSource.LOCAL_EDIT,
            )

        recipeOutboxStore.enqueueRecipeReport(recipe)
        val duplicate = recipeOutboxStore.enqueueRecipeReport(recipe)

        assertTrue(duplicate is MachineOutboxStore.EnqueueResult.Duplicate)
        assertEquals(1, persistence.allRows().size)
    }

    @Test
    fun `flush ordering lists recipe report before command ack`() = runTest {
        val ack =
            RecipeCommandAckEntry(
                commandId = "cmd-1",
                commandGeneration = 2L,
                cellUuid = "cell-1",
                status = RecipeCommandAckStatus.APPLIED,
                failureCode = null,
                appliedRecipe = CellEffectiveRecipeDefaults.legacyTriple,
            )
        recipeOutboxStore.enqueueCommandAck(ack)
        val store =
            CellEffectiveRecipeStore(
                dao = recipeDao,
                featureEnabled = { true },
            )
        val recipe =
            store.applyLocalEffectiveRecipe(
                cellId = "cell-1",
                triple = CellEffectiveRecipeDefaults.legacyTriple,
                source = CellEffectiveRecipeSource.LOCAL_EDIT,
            )
        recipeOutboxStore.enqueueRecipeReport(recipe)

        val ordered = outboxStore.listDrainable(limit = 10)
        assertEquals(MachineOutboxKind.CELLS_RECIPE_REPORT.wireValue, ordered.first().kind)
        assertEquals(MachineOutboxKind.CELLS_RECIPE_COMMAND_ACK.wireValue, ordered.last().kind)
    }

    @Test
    fun `enqueueCommandAck uses command id status generation idempotency key`() = runTest {
        val ack =
            RecipeCommandAckEntry(
                commandId = "cmd-abc",
                commandGeneration = 9L,
                cellUuid = "cell-1",
                status = RecipeCommandAckStatus.CANCELLED,
                failureCode = null,
                appliedRecipe = null,
            )

        recipeOutboxStore.enqueueCommandAck(ack)

        val row = persistence.allRows().single()
        assertEquals("cmd-abc|cancelled|9", row.idempotencyKey)
        assertEquals(MachineOutboxKind.CELLS_RECIPE_COMMAND_ACK.wireValue, row.kind)
    }

    @Test
    fun `recoverPendingTerminalAcks enqueues applied ack from Room when outbox empty`() = runTest {
        val store =
            CellEffectiveRecipeStore(
                dao = recipeDao,
                featureEnabled = { true },
            )
        store.setRuntimeManagedModeActive(true)
        store.applyManagedCommand(
            com.viwa.android.data.remote.telemetry.mvp.cells.RecipeCommandDownlink(
                commandId = "cmd-recover",
                commandGeneration = 3L,
                kind = "RESET",
                cellUuid = "cell-1",
                targetRecipe = CellEffectiveRecipeDefaults.legacyTriple,
                targetBaseVersionId = "base-v1",
                fromFingerprint = null,
                fromBaseVersionId = null,
                campaignId = null,
                resetBatchId = null,
            ),
        )

        val recovered = recipeOutboxStore.recoverPendingTerminalAcks()

        assertEquals(1, recovered)
        assertEquals(1, persistence.allRows().size)
        assertEquals("cmd-recover|applied|3", persistence.allRows().single().idempotencyKey)
    }

    @Test
    fun `enqueueReportAfterLocalEdit queues offline local edit`() = runTest {
        val store =
            CellEffectiveRecipeStore(
                dao = recipeDao,
                featureEnabled = { true },
            )
        val recipe =
            store.applyLocalEffectiveRecipe(
                cellId = "cell-1",
                triple = CellEffectiveRecipeDefaults.legacyTriple,
                source = CellEffectiveRecipeSource.LOCAL_EDIT,
            )

        recipeOutboxStore.enqueueReportAfterLocalEdit(recipe)

        assertEquals(1, persistence.allRows().size)
        assertEquals(MachineOutboxKind.CELLS_RECIPE_REPORT.wireValue, persistence.allRows().single().kind)
    }

    private class FakeConfigRepository : ConfigRepository {
        override suspend fun get(key: String): String? = null

        override suspend fun set(key: String, value: String) = Unit

        override suspend fun delete(key: String) = Unit

        override suspend fun getJson(key: String): String? = null

        override suspend fun setJson(key: String, json: String) = Unit
    }
}
