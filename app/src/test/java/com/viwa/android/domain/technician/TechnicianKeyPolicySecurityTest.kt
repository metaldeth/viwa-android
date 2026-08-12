package com.viwa.android.domain.technician

import com.viwa.android.data.local.technician.FakeTechnicianAllowlistDao
import com.viwa.android.data.local.technician.FakeTechnicianAllowlistStateDao
import com.viwa.android.data.local.technician.FakeTechnicianAuditOutboxDao
import com.viwa.android.data.local.technician.TechnicianAllowlistEntity
import com.viwa.android.data.local.technician.TechnicianAllowlistStateEntity
import com.viwa.android.data.local.technician.TechnicianAllowlistStore
import com.viwa.android.data.local.technician.TechnicianAuditOutboxStore
import com.viwa.android.data.local.technician.TechnicianKeyPolicyStore
import com.viwa.android.data.remote.telemetry.mvp.offline.MvpTechnicianKeysCapabilityDto
import com.viwa.android.data.remote.telemetry.mvp.offline.OfflineSigningPublicKeyDto
import com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianAllowlistWireRecordDto
import com.viwa.android.domain.offline.BoundedTelemetryClock
import com.viwa.android.domain.offline.OfflineSigningKeysStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TechnicianKeyPolicyResolverTest {
    @Test
    fun `offline scopes use conservative intersection with local defaults`() {
        val effective =
            TechnicianKeyPolicyResolver.effectiveOfflineScopes(
                listOf("service.menu", "diagnostics.read", "registration.rebind"),
            )
        assertTrue(effective.contains("service.menu"))
        assertTrue(effective.contains("diagnostics.read"))
        assertFalse(effective.contains("registration.rebind"))
    }

    @Test
    fun `online-only scopes union server wins over local`() {
        val effective =
            TechnicianKeyPolicyResolver.effectiveOnlineOnlyScopes(
                listOf("custom.admin"),
            )
        assertTrue(effective.contains("firmware.update"))
        assertTrue(effective.contains("custom.admin"))
    }

    @Test
    fun `feature enabled requires explicit server true`() {
        assertFalse(TechnicianKeyPolicyResolver.isFeatureEnabled(null))
        assertFalse(TechnicianKeyPolicyResolver.isFeatureEnabled(false))
        assertTrue(TechnicianKeyPolicyResolver.isFeatureEnabled(true))
    }

    @Test
    fun `unknown explicit deny maps to stable SERVER_DENIED`() {
        assertEquals(
            TechnicianAuthorizationReason.SERVER_DENIED,
            TechnicianKeyPolicyResolver.mapExplicitDenyCode("SOME_NEW_CODE"),
        )
    }
}

class TechnicianKeyPolicySecurityTest {
    private lateinit var allowlistDao: FakeTechnicianAllowlistDao
    private lateinit var stateDao: FakeTechnicianAllowlistStateDao
    private lateinit var auditDao: FakeTechnicianAuditOutboxDao
    private lateinit var allowlistStore: TechnicianAllowlistStore
    private lateinit var auditStore: TechnicianAuditOutboxStore
    private lateinit var policyStore: TechnicianKeyPolicyStore
    private lateinit var service: TechnicianKeyAuthorizationService
    private lateinit var sessionStore: TechnicianSessionStore
    private lateinit var signature: String

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        allowlistDao = FakeTechnicianAllowlistDao()
        stateDao = FakeTechnicianAllowlistStateDao()
        auditDao = FakeTechnicianAuditOutboxDao()
        val db = mockk<com.viwa.android.data.local.db.ViwaDatabase>(relaxed = true)
        allowlistStore = TechnicianAllowlistStore(db, allowlistDao, stateDao)
        auditStore = TechnicianAuditOutboxStore(auditDao)
        policyStore = TechnicianKeyPolicyStore(stateDao)
        sessionStore = TechnicianSessionStore()
        val clock = BoundedTelemetryClock()
        clock.updateFromServer("2026-07-27T10:00:00.000Z")
        val keysStore = OfflineSigningKeysStore()
        val (publicPem, sig) =
            TechnicianAllowlistVerifierTest.signFixture(
                TechnicianAllowlistCanonicalSigning.CanonicalAllowlistFields(
                    keyId = TechnicianKeyTestFixtures.KEY_ID,
                    fingerprint = TechnicianKeyTestFixtures.canonicalFields.fingerprint,
                    machineId = TechnicianKeyTestFixtures.MACHINE_ID,
                    scopes = listOf("service.menu"),
                    expiresAt = TechnicianKeyTestFixtures.EXPIRES_AT,
                    revision = "3",
                    revocationEpoch = 0,
                ),
            )
        signature = sig
        keysStore.updateFromHello(
            listOf(
                OfflineSigningPublicKeyDto(
                    keyId = TechnicianKeyTestFixtures.SIGNING_KEY_ID,
                    publicKeyPem = publicPem,
                    revocationEpoch = 0,
                ),
            ),
            0,
        )
        val verifier = TechnicianAllowlistVerifier(keysStore)
        val metrics = TechnicianKeyMetrics(allowlistStore, auditStore)
        service =
            TechnicianKeyAuthorizationService(
                allowlistStore,
                verifier,
                auditStore,
                sessionStore,
                policyStore,
                clock,
                metrics,
            )
    }

    @Test
    fun `cold-start offline succeeds with persisted enabled policy and trusted sync`() = runTest {
        seedPolicy(serverEnabled = true, trustedSync = true)
        seedAllowlist(expiresAtIso = TechnicianKeyTestFixtures.EXPIRES_AT)
        val result =
            service.authorizeOffline(
                TechnicianKeyTestFixtures.NORMALIZED_KEY,
                TechnicianKeyTestFixtures.MACHINE_ID,
                "service.menu",
                "880e8400-e29b-41d4-a716-446655440010",
            )
        assertTrue(result.allowed)
        assertNotNull(sessionStore.currentSession())
    }

    @Test
    fun `persisted server disabled policy denies offline`() = runTest {
        seedPolicy(serverEnabled = false, trustedSync = true)
        seedAllowlist(expiresAtIso = TechnicianKeyTestFixtures.EXPIRES_AT)
        val result =
            service.authorizeOffline(
                TechnicianKeyTestFixtures.NORMALIZED_KEY,
                TechnicianKeyTestFixtures.MACHINE_ID,
                "service.menu",
                "880e8400-e29b-41d4-a716-446655440011",
            )
        assertFalse(result.allowed)
        assertEquals(TechnicianAuthorizationReason.OFFLINE_POLICY_DISABLED, result.reason)
    }

    @Test
    fun `no prior trusted sync denies offline`() = runTest {
        seedPolicy(serverEnabled = true, trustedSync = false)
        seedAllowlist(expiresAtIso = TechnicianKeyTestFixtures.EXPIRES_AT)
        val result =
            service.authorizeOffline(
                TechnicianKeyTestFixtures.NORMALIZED_KEY,
                TechnicianKeyTestFixtures.MACHINE_ID,
                "service.menu",
                "880e8400-e29b-41d4-a716-446655440012",
            )
        assertFalse(result.allowed)
        assertEquals(TechnicianAuthorizationReason.OFFLINE_NO_TRUSTED_SYNC, result.reason)
    }

    @Test
    fun `scope intersection denies offline when server omits scope`() = runTest {
        seedPolicy(
            serverEnabled = true,
            trustedSync = true,
            offlineScopes = listOf("diagnostics.read"),
        )
        seedAllowlist(expiresAtIso = TechnicianKeyTestFixtures.EXPIRES_AT)
        val result =
            service.authorizeOffline(
                TechnicianKeyTestFixtures.NORMALIZED_KEY,
                TechnicianKeyTestFixtures.MACHINE_ID,
                "service.menu",
                "880e8400-e29b-41d4-a716-446655440013",
            )
        assertFalse(result.allowed)
        assertEquals(TechnicianAuthorizationReason.OFFLINE_SCOPE_DENIED, result.reason)
    }

    @Test
    fun `online-only scope denied offline even if allowlist contains it`() = runTest {
        seedPolicy(
            serverEnabled = true,
            trustedSync = true,
            onlineOnlyScopes = listOf("firmware.update"),
        )
        seedAllowlist(
            scopes = listOf("firmware.update"),
            expiresAtIso = TechnicianKeyTestFixtures.EXPIRES_AT,
        )
        val result =
            service.authorizeOffline(
                TechnicianKeyTestFixtures.NORMALIZED_KEY,
                TechnicianKeyTestFixtures.MACHINE_ID,
                "firmware.update",
                "880e8400-e29b-41d4-a716-446655440014",
            )
        assertFalse(result.allowed)
        assertEquals(TechnicianAuthorizationReason.OFFLINE_SCOPE_DENIED, result.reason)
    }

    @Test
    fun `exact ISO canonical signature verification rejects reformatted expiresAt`() = runTest {
        seedPolicy(serverEnabled = true, trustedSync = true)
        seedAllowlist(expiresAtIso = TechnicianKeyTestFixtures.EXPIRES_AT)
        val entity = allowlistDao.findActiveByFingerprint(TechnicianKeyTestFixtures.canonicalFields.fingerprint)!!
        val tamperedIso = "2026-07-28T00:00:00Z"
        allowlistDao.upsert(entity.copy(expiresAtIso = tamperedIso))
        val result =
            service.authorizeOffline(
                TechnicianKeyTestFixtures.NORMALIZED_KEY,
                TechnicianKeyTestFixtures.MACHINE_ID,
                "service.menu",
                "880e8400-e29b-41d4-a716-446655440015",
            )
        assertFalse(result.allowed)
        assertEquals(TechnicianAuthorizationReason.OFFLINE_STALE_ALLOWLIST, result.reason)
    }

    @Test
    fun `audit enqueue failure denies offline and clears session`() = runTest {
        seedPolicy(serverEnabled = true, trustedSync = true)
        val clock = BoundedTelemetryClock().apply { updateFromServer("2026-07-27T10:00:00.000Z") }
        val keysStore = OfflineSigningKeysStore()
        val (publicPem, matchingSignature) =
            TechnicianAllowlistVerifierTest.signFixture(
                TechnicianAllowlistCanonicalSigning.CanonicalAllowlistFields(
                    keyId = TechnicianKeyTestFixtures.KEY_ID,
                    fingerprint = TechnicianKeyTestFixtures.canonicalFields.fingerprint,
                    machineId = TechnicianKeyTestFixtures.MACHINE_ID,
                    scopes = listOf("service.menu"),
                    expiresAt = TechnicianKeyTestFixtures.EXPIRES_AT,
                    revision = "3",
                    revocationEpoch = 0,
                ),
            )
        keysStore.updateFromHello(
            listOf(
                OfflineSigningPublicKeyDto(
                    keyId = TechnicianKeyTestFixtures.SIGNING_KEY_ID,
                    publicKeyPem = publicPem,
                    revocationEpoch = 0,
                ),
            ),
            0,
        )
        seedAllowlist(expiresAtIso = TechnicianKeyTestFixtures.EXPIRES_AT, signatureOverride = matchingSignature)
        val failingAuditStore = mockk<TechnicianAuditOutboxStore>()
        coEvery {
            failingAuditStore.enqueueNew(any(), any(), any(), any(), any(), any(), any())
        } returns false
        coEvery { failingAuditStore.pendingCount() } returns 0
        val guardedService =
            TechnicianKeyAuthorizationService(
                allowlistStore,
                TechnicianAllowlistVerifier(keysStore),
                failingAuditStore,
                sessionStore,
                policyStore,
                clock,
                TechnicianKeyMetrics(allowlistStore, failingAuditStore),
            )
        val result =
            guardedService.authorizeOffline(
                TechnicianKeyTestFixtures.NORMALIZED_KEY,
                TechnicianKeyTestFixtures.MACHINE_ID,
                "service.menu",
                "880e8400-e29b-41d4-a716-446655440016",
            )
        assertFalse(result.allowed)
        assertEquals(TechnicianAuthorizationReason.AUDIT_ENQUEUE_FAILED, result.reason)
        assertNull(sessionStore.currentSession())
    }

    @Test
    fun `service menu gate requires active technician session scope`() {
        val clock = BoundedTelemetryClock().apply { updateFromServer("2026-07-27T10:00:00.000Z") }
        val access = TechnicianServiceMenuAccess(sessionStore, clock)
        assertFalse(access.isAuthorized())
        sessionStore.establish(
            technicianKeyId = TechnicianKeyTestFixtures.KEY_ID,
            scopes = listOf("service.menu"),
            sessionToken = "token",
            expiresAtMs = clock.trustedNowMs() + 60_000L,
        )
        assertTrue(access.isAuthorized())
    }

    @Test
    fun `studio password path establishes local session and navigates`() {
        val clock = BoundedTelemetryClock().apply { updateFromServer("2026-07-27T10:00:00.000Z") }
        val access = TechnicianServiceMenuAccess(sessionStore, clock)
        val gate = ServiceMenuNavigationGate(access, sessionStore, clock)
        var navigated = false
        assertFalse(gate.isAuthorized())
        gate.navigateAfterLocalStudioPassword { navigated = true }
        assertTrue(navigated)
        assertTrue(gate.isAuthorized())
        assertEquals(
            ServiceMenuNavigationGate.LOCAL_STUDIO_TECHNICIAN_KEY_ID,
            sessionStore.currentSession()?.technicianKeyId,
        )
        assertTrue(
            sessionStore.currentSession()?.scopes?.contains(TechnicianKeyConstants.SCOPE_SERVICE_MENU) == true,
        )
        assertTrue(
            sessionStore.currentSession()?.scopes?.contains(TechnicianKeyConstants.SCOPE_FIRMWARE_UPDATE) == true,
        )
    }

    private suspend fun seedPolicy(
        serverEnabled: Boolean?,
        trustedSync: Boolean,
        offlineScopes: List<String> = listOf("service.menu"),
        onlineOnlyScopes: List<String> = emptyList(),
    ) {
        val capability =
            MvpTechnicianKeysCapabilityDto(
                validateEndpoint = "/validate",
                allowlistDeltaEndpoint = "/delta",
                auditBatchEndpoint = "/audit",
                offlineScopes = offlineScopes,
                onlineOnlyScopes = onlineOnlyScopes,
            )
        stateDao.upsert(
            TechnicianAllowlistStateEntity(
                serverTechnicianKeysEnabled = serverEnabled,
                offlineScopesJson = json.encodeToString(offlineScopes),
                onlineOnlyScopesJson = json.encodeToString(onlineOnlyScopes),
                capabilityJson = json.encodeToString(capability),
                hasTrustedAllowlistSync = trustedSync,
                lastSyncAtMs = if (trustedSync) 1L else 0L,
            ),
        )
    }

    private suspend fun seedAllowlist(
        scopes: List<String> = listOf("service.menu"),
        expiresAtIso: String?,
        signatureOverride: String = signature,
    ) {
        val record =
            TechnicianAllowlistWireRecordDto(
                keyId = TechnicianKeyTestFixtures.KEY_ID,
                fingerprint = TechnicianKeyTestFixtures.canonicalFields.fingerprint,
                machineId = TechnicianKeyTestFixtures.MACHINE_ID,
                scopes = scopes,
                expiresAt = expiresAtIso,
                revision = "3",
                revocationEpoch = 0,
                signature = signatureOverride,
            )
        allowlistDao.upsert(
            TechnicianAllowlistEntity(
                fingerprint = record.fingerprint,
                keyId = record.keyId,
                machineId = record.machineId,
                scopesJson = json.encodeToString(scopes),
                expiresAtMs = expiresAtIso?.let { java.time.Instant.parse(it).toEpochMilli() },
                expiresAtIso = expiresAtIso,
                revocationEpoch = record.revocationEpoch,
                revision = record.revision,
                signature = signatureOverride,
                recordJson = record.toString(),
                revoked = false,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        stateDao.upsert(
            stateDao.getState()?.copy(revocationEpoch = 0)
                ?: TechnicianAllowlistStateEntity(revocationEpoch = 0),
        )
    }
}

class TechnicianKeyOnlineDenyTest {
    @Test
    fun `explicit online deny never maps to GRANTED`() = runTest {
        val authService = mockk<TechnicianKeyAuthorizationService>(relaxed = true)
        coEvery {
            authService.recordOnlineOutcome(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            TechnicianAuthorizationResult(
                allowed = arg(4),
                reason = arg(5),
                channel = "ONLINE",
            )
        }
        val result =
            authService.recordOnlineOutcome(
                requestUuid = "990e8400-e29b-41d4-a716-446655440099",
                fingerprint = "abc",
                technicianKeyId = null,
                requestedScope = "service.menu",
                allowed = false,
                reason = TechnicianKeyPolicyResolver.mapExplicitDenyCode("KEY_REVOKED"),
            )
        assertFalse(result.allowed)
        assertNotNull(result.reason)
        assertEquals(TechnicianAuthorizationReason.KEY_REVOKED, result.reason)
        assertFalse(result.reason == TechnicianAuthorizationReason.GRANTED)
    }
}

class ViwaDatabaseMigration34SqlTest {
    @Test
    fun `migration 3 to 4 spans technician policy schema version`() {
        assertEquals(3, com.viwa.android.data.local.db.ViwaDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, com.viwa.android.data.local.db.ViwaDatabase.MIGRATION_3_4.endVersion)
    }
}
