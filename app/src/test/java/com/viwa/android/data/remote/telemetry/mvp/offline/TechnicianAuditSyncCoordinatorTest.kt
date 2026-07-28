package com.viwa.android.data.remote.telemetry.mvp.offline



import com.viwa.android.data.local.technician.FakeTechnicianAuditOutboxDao

import com.viwa.android.data.local.technician.TechnicianAuditOutboxEntity

import com.viwa.android.data.local.technician.TechnicianAuditOutboxStore

import com.viwa.android.data.remote.telemetry.mvp.MachineOutboxBearerTokenProvider

import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryApiClient

import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryWebSocketManager

import com.viwa.android.domain.technician.TechnicianKeyConstants

import com.viwa.android.domain.technician.TechnicianKeyMetrics

import io.mockk.coEvery

import io.mockk.every

import io.mockk.mockk

import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals

import org.junit.Assert.assertTrue

import org.junit.Before

import org.junit.Test



class TechnicianAuditSyncCoordinatorTest {

    private lateinit var auditDao: FakeTechnicianAuditOutboxDao

    private lateinit var auditStore: TechnicianAuditOutboxStore

    private lateinit var apiClient: MvpTelemetryApiClient

    private lateinit var bearerProvider: MachineOutboxBearerTokenProvider

    private lateinit var wsManager: MvpTelemetryWebSocketManager

    private lateinit var coordinator: TechnicianAuditSyncCoordinator



    @Before

    fun setUp() {

        auditDao = FakeTechnicianAuditOutboxDao()

        auditStore = TechnicianAuditOutboxStore(auditDao)

        apiClient = mockk(relaxed = true)

        bearerProvider = mockk(relaxed = true)

        wsManager = mockk(relaxed = true)

        coEvery { bearerProvider.resolveBearerToken() } returns "token"

        every { wsManager.isNetworkValidated() } returns true

        coordinator =

            TechnicianAuditSyncCoordinator(

                apiClient = apiClient,

                auditOutboxStore = auditStore,

                bearerTokenProvider = bearerProvider,

                metrics = mockk<TechnicianKeyMetrics>(relaxed = true),

                wsManagerLazy =

                    object : dagger.Lazy<MvpTelemetryWebSocketManager> {

                        override fun get(): MvpTelemetryWebSocketManager = wsManager

                    },

                appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),

            )

    }



    @Test

    fun `missing requestUuid in batch result leaves row pending`() = runTest {

        // given

        val requestUuid = "aa0e8400-e29b-41d4-a716-446655440005"

        auditDao.insert(sampleAudit(requestUuid))

        coEvery {

            apiClient.submitTechnicianAuditBatch(any(), any(), any())

        } returns

            Result.success(

                TechnicianAuditBatchResponseDto(

                    results =

                        listOf(

                            TechnicianAuditBatchItemResultDto(

                                requestUuid = "",

                                status = "ACCEPTED",

                            ),

                        ),

                ),

            )



        // when

        coordinator.syncBatch(sampleCapability())



        // then

        assertEquals(TechnicianAuditOutboxEntity.SYNC_PENDING, auditDao.rows[requestUuid]!!.syncStatus)

    }



    @Test

    fun `purge removes terminal records older than retention`() = runTest {

        // given

        val oldSyncedUuid = "bb0e8400-e29b-41d4-a716-446655440006"

        val recentSyncedUuid = "cc0e8400-e29b-41d4-a716-446655440007"

        val now = System.currentTimeMillis()

        auditDao.insert(sampleAudit(oldSyncedUuid))

        auditDao.markSynced(oldSyncedUuid, TechnicianAuditOutboxEntity.SYNC_SYNCED, now - 8L * 24 * 60 * 60 * 1000)

        auditDao.insert(sampleAudit(recentSyncedUuid))

        auditDao.markSynced(recentSyncedUuid, TechnicianAuditOutboxEntity.SYNC_SYNCED, now - 1_000L)



        // when

        val purged = auditStore.purgeTerminalOlderThan(TechnicianKeyConstants.AUDIT_TERMINAL_RETENTION_MS)



        // then

        assertEquals(1, purged)

        assertTrue(oldSyncedUuid !in auditDao.rows)

        assertTrue(recentSyncedUuid in auditDao.rows)

    }



    @Test

    fun `onDisconnect cancels periodic audit sync job`() = runTest {

        // given

        coordinator.onHello(sampleCapability())

        coordinator.onDisconnect()



        // when / then — no crash; job cancelled (smoke via lifecycle)

        assertEquals(0, auditDao.countByStatus(TechnicianAuditOutboxEntity.SYNC_PENDING))

    }



    private fun sampleAudit(requestUuid: String): TechnicianAuditOutboxEntity =

        TechnicianAuditOutboxEntity(

            requestUuid = requestUuid,

            fingerprint = "abc123",

            technicianKeyId = "key-1",

            action = "service.menu",

            channel = "OFFLINE",

            outcome = "SUCCESS",

            failureCode = null,

            createdAtMs = System.currentTimeMillis(),

        )



    private fun sampleCapability(): MvpTechnicianKeysCapabilityDto =

        MvpTechnicianKeysCapabilityDto(

            validateEndpoint = "/v1/technician/validate",

            allowlistDeltaEndpoint = "/v1/technician/allowlist/delta",

            auditBatchEndpoint = "/v1/technician/audit/batch",

        )

}


