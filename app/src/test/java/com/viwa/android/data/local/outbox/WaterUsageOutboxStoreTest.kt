package com.viwa.android.data.local.outbox

import com.viwa.android.data.remote.telemetry.v3.TelemetryWaterUsageMessageCodec
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.telemetry.WaterUsageReportSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WaterUsageOutboxStoreTest {
    private lateinit var persistence: FakeMachineOutboxPersistence
    private lateinit var configRepository: FakeConfigRepository
    private lateinit var outboxStore: MachineOutboxStore
    private lateinit var waterUsageOutboxStore: WaterUsageOutboxStore

    @Before
    fun setUp() {
        persistence = FakeMachineOutboxPersistence()
        configRepository = FakeConfigRepository()
        outboxStore =
            MachineOutboxStore(
                persistence = persistence,
                configRepository = configRepository,
                migrator =
                    PendingSalesOutboxMigrator(
                        persistence = persistence,
                        configRepository = configRepository,
                    ),
            )
        waterUsageOutboxStore = WaterUsageOutboxStore(outboxStore)
    }

    @Test
    fun `enqueueWaterUsageReport writes machine water usage kind and payload`() = runTest {
        val report =
            WaterUsageReportSnapshot(
                totalMl = 12500,
                reportedAt = "2026-08-05T12:00:00.000Z",
            )

        val result = waterUsageOutboxStore.enqueueWaterUsageReport(report)

        assertTrue(result is MachineOutboxStore.EnqueueResult.Inserted)
        val row = persistence.allRows().single()
        assertEquals(MachineOutboxKind.MACHINE_WATER_USAGE_REPORT.wireValue, row.kind)
        assertEquals("2026-08-05T12:00:00.000Z", row.idempotencyKey)
        assertEquals(TelemetryWaterUsageMessageCodec.WIRE_TYPE, row.kind)
        val payload = Json.parseToJsonElement(row.payloadJson).jsonObject
        assertEquals(12500, payload["totalMl"]!!.jsonPrimitive.int)
        assertEquals("2026-08-05T12:00:00.000Z", payload["reportedAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `duplicate idempotency key returns Duplicate without second row`() = runTest {
        val report =
            WaterUsageReportSnapshot(
                totalMl = 100,
                reportedAt = "2026-08-05T12:00:00.000Z",
            )

        waterUsageOutboxStore.enqueueWaterUsageReport(report)
        val duplicate = waterUsageOutboxStore.enqueueWaterUsageReport(report)

        assertTrue(duplicate is MachineOutboxStore.EnqueueResult.Duplicate)
        assertEquals(1, persistence.allRows().size)
    }

    private class FakeConfigRepository : ConfigRepository {
        override suspend fun get(key: String): String? = null

        override suspend fun set(
            key: String,
            value: String,
        ) = Unit

        override suspend fun delete(key: String) = Unit

        override suspend fun getJson(key: String): String? = null

        override suspend fun setJson(key: String, json: String) = Unit
    }
}
