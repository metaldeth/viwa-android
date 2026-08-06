package com.viwa.android.domain.technician

import com.viwa.android.data.local.technician.FakeTechnicianAllowlistDao
import com.viwa.android.data.local.technician.FakeTechnicianAllowlistStateDao
import com.viwa.android.data.local.technician.FakeTechnicianAuditOutboxDao
import com.viwa.android.data.local.technician.TechnicianAllowlistEntity
import com.viwa.android.data.local.technician.TechnicianAllowlistStore
import com.viwa.android.data.local.technician.TechnicianAuditOutboxStore
import com.viwa.android.data.local.technician.TechnicianAuditOutboxEntity
import com.viwa.android.data.local.technician.TechnicianKeyPolicyStore
import com.viwa.android.data.local.technician.TechnicianAllowlistStateEntity
import com.viwa.android.data.remote.telemetry.mvp.offline.MvpTechnicianKeysCapabilityDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianAllowlistWireRecordDto
import com.viwa.android.data.remote.telemetry.mvp.offline.OfflineSigningPublicKeyDto
import com.viwa.android.domain.offline.BoundedTelemetryClock
import com.viwa.android.domain.offline.OfflineSigningKeysStore
import io.mockk.mockk
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

object TechnicianKeyTestFixtures {
    const val KEY_ID = "550e8400-e29b-41d4-a716-446655440010"
    const val MACHINE_ID = "660e8400-e29b-41d4-a716-446655440001"
    const val OTHER_MACHINE_ID = "770e8400-e29b-41d4-a716-446655440099"
    const val NORMALIZED_KEY = "KEY-0123456789ABCDEFGHJK"
    const val LEGACY_INPUT = "EMP:0123456789ABCDEFGHJK"
    const val EXPIRES_AT = "2026-07-28T00:00:00.000Z"
    const val SIGNING_KEY_ID = "test-v1"

    val canonicalFields =
        TechnicianAllowlistCanonicalSigning.CanonicalAllowlistFields(
            keyId = KEY_ID,
            fingerprint = TechnicianKeyFingerprint.fingerprint(NORMALIZED_KEY),
            machineId = MACHINE_ID,
            scopes = listOf("service.menu", "diagnostics.read"),
            expiresAt = EXPIRES_AT,
            revision = "3",
            revocationEpoch = 0,
        )
}

class TechnicianKeyNormalizerTest {
    @Test
    fun `should normalize EMP prefix to KEY- and uppercase`() {
        assertEquals(
            TechnicianKeyTestFixtures.NORMALIZED_KEY,
            TechnicianKeyNormalizer.normalize(TechnicianKeyTestFixtures.LEGACY_INPUT),
        )
    }

    @Test
    fun `should trim whitespace before validation`() {
        assertEquals(
            TechnicianKeyTestFixtures.NORMALIZED_KEY,
            TechnicianKeyNormalizer.normalize("  ${TechnicianKeyTestFixtures.NORMALIZED_KEY}  "),
        )
    }

    @Test
    fun `should reject invalid body length`() {
        assertFalse(TechnicianKeyNormalizer.isValidFormat("KEY-SHORT"))
    }
}

class TechnicianKeyFingerprintTest {
    @Test
    fun `should compute deterministic sha256 hex parity`() {
        val normalized = TechnicianKeyTestFixtures.NORMALIZED_KEY
        val fp1 = TechnicianKeyFingerprint.fingerprint(normalized)
        val fp2 = TechnicianKeyFingerprint.fingerprint(normalized)
        assertEquals(fp1, fp2)
        assertEquals(64, fp1.length)
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        val expected = digest.joinToString("") { "%02x".format(it) }
        assertEquals(expected, fp1)
    }
}

class TechnicianAllowlistCanonicalSigningTest {
    @Test
    fun `canonical message matches backend pipe format`() {
        val message =
            String(
                TechnicianAllowlistCanonicalSigning.buildCanonicalMessage(TechnicianKeyTestFixtures.canonicalFields),
                Charsets.UTF_8,
            )
        assertEquals(
            "technician-allowlist-v1|550e8400-e29b-41d4-a716-446655440010|" +
                "${TechnicianKeyTestFixtures.canonicalFields.fingerprint}|$MACHINE_ID|" +
                "diagnostics.read,service.menu|2026-07-28T00:00:00.000Z|3|0",
            message,
        )
    }

    private companion object {
        const val MACHINE_ID = TechnicianKeyTestFixtures.MACHINE_ID
    }
}

class TechnicianAllowlistVerifierTest {
    @Test
    fun `should verify valid signature and reject tamper`() {
        val record =
            TechnicianAllowlistWireRecordDto(
                keyId = TechnicianKeyTestFixtures.KEY_ID,
                fingerprint = TechnicianKeyTestFixtures.canonicalFields.fingerprint,
                machineId = TechnicianKeyTestFixtures.MACHINE_ID,
                scopes = listOf("service.menu"),
                expiresAt = TechnicianKeyTestFixtures.EXPIRES_AT,
                revision = "3",
                revocationEpoch = 0,
                signature = "",
            )
        val fields = record.let { TechnicianAllowlistVerifier.Companion.run { it.toCanonicalFields() } }
        val (publicPem, signature) = signFixture(fields)
        val signedRecord = record.copy(signature = signature)
        val keysStore = OfflineSigningKeysStore()
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
        assertTrue(verifier.verifyRecord(signedRecord))
        assertFalse(verifier.verifyRecord(signedRecord.copy(revision = "999")))
    }

    companion object {
        fun signFixture(fields: TechnicianAllowlistCanonicalSigning.CanonicalAllowlistFields): Pair<String, String> {
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val publicPem =
                "-----BEGIN PUBLIC KEY-----\n" +
                    Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded) +
                    "\n-----END PUBLIC KEY-----"
            val pkcs8 = pair.private.encoded
            val seed = pkcs8.copyOfRange(pkcs8.size - 32, pkcs8.size)
            val privateKey = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
            val message = TechnicianAllowlistCanonicalSigning.buildCanonicalMessage(fields)
            val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
            signer.init(true, privateKey)
            signer.update(message, 0, message.size)
            val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.generateSignature())
            return publicPem to signature
        }
    }
}

class TechnicianKeyAuthorizationServiceTest {
    private lateinit var allowlistDao: FakeTechnicianAllowlistDao
    private lateinit var stateDao: FakeTechnicianAllowlistStateDao
    private lateinit var auditDao: FakeTechnicianAuditOutboxDao
    private lateinit var allowlistStore: TechnicianAllowlistStore
    private lateinit var auditStore: TechnicianAuditOutboxStore
    private lateinit var policyStore: TechnicianKeyPolicyStore
    private lateinit var service: TechnicianKeyAuthorizationService
    private lateinit var sessionStore: TechnicianSessionStore
    private lateinit var signature: String

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
        runBlocking { seedPolicy() }
    }

    private val policyJson = Json { ignoreUnknownKeys = true }

    private suspend fun seedPolicy() {
        val capability =
            MvpTechnicianKeysCapabilityDto(
                validateEndpoint = "/validate",
                allowlistDeltaEndpoint = "/delta",
                auditBatchEndpoint = "/audit",
                offlineScopes = listOf("service.menu"),
            )
        stateDao.upsert(
            TechnicianAllowlistStateEntity(
                serverTechnicianKeysEnabled = true,
                offlineScopesJson = policyJson.encodeToString(listOf("service.menu")),
                onlineOnlyScopesJson = "[]",
                capabilityJson = policyJson.encodeToString(capability),
                hasTrustedAllowlistSync = true,
                lastSyncAtMs = 1L,
            ),
        )
    }

    @Test
    fun `should allow offline trusted service menu scan`() = runTest {
        seedAllowlist(machineId = TechnicianKeyTestFixtures.MACHINE_ID)
        val result =
            service.authorizeOffline(
                TechnicianKeyTestFixtures.NORMALIZED_KEY,
                TechnicianKeyTestFixtures.MACHINE_ID,
                "service.menu",
                "880e8400-e29b-41d4-a716-446655440003",
            )
        assertTrue(result.allowed)
        assertNotNull(sessionStore.currentSession())
        assertEquals(1, auditDao.rows.size)
        assertFalse(auditDao.rows.values.any { it.fingerprint.contains("KEY-") })
    }

    @Test
    fun `should deny offline when machine binding mismatches`() = runTest {
        seedAllowlist(machineId = TechnicianKeyTestFixtures.OTHER_MACHINE_ID)
        val result =
            service.authorizeOffline(
                TechnicianKeyTestFixtures.NORMALIZED_KEY,
                TechnicianKeyTestFixtures.MACHINE_ID,
                "service.menu",
                "990e8400-e29b-41d4-a716-446655440004",
            )
        assertFalse(result.allowed)
        assertEquals(TechnicianAuthorizationReason.KEY_MACHINE_DENIED, result.reason)
    }

    @Test
    fun `should deny high-risk scope offline`() = runTest {
        seedAllowlist(machineId = null)
        val result =
            service.authorizeOffline(
                TechnicianKeyTestFixtures.NORMALIZED_KEY,
                TechnicianKeyTestFixtures.MACHINE_ID,
                "firmware.update",
                "aa0e8400-e29b-41d4-a716-446655440005",
            )
        assertFalse(result.allowed)
        assertEquals(TechnicianAuthorizationReason.OFFLINE_SCOPE_DENIED, result.reason)
    }

    @Test
    fun `should deny offline when allowlist stale revoked by epoch`() = runTest {
        seedAllowlist(machineId = TechnicianKeyTestFixtures.MACHINE_ID, revocationEpoch = 0)
        stateDao.upsert(
            (stateDao.getState() ?: TechnicianAllowlistStateEntity()).copy(revocationEpoch = 1),
        )
        val result =
            service.authorizeOffline(
                TechnicianKeyTestFixtures.NORMALIZED_KEY,
                TechnicianKeyTestFixtures.MACHINE_ID,
                "service.menu",
                "bb0e8400-e29b-41d4-a716-446655440006",
            )
        assertFalse(result.allowed)
        assertEquals(TechnicianAuthorizationReason.KEY_REVOKED, result.reason)
    }

    @Test
    fun `audit enqueue is idempotent by requestUuid`() = runTest {
        seedAllowlist(machineId = TechnicianKeyTestFixtures.MACHINE_ID)
        val uuid = "cc0e8400-e29b-41d4-a716-446655440007"
        service.authorizeOffline(
            TechnicianKeyTestFixtures.NORMALIZED_KEY,
            TechnicianKeyTestFixtures.MACHINE_ID,
            "service.menu",
            uuid,
        )
        val second =
            auditStore.enqueueNew(
                requestUuid = uuid,
                fingerprint = TechnicianKeyTestFixtures.canonicalFields.fingerprint,
                technicianKeyId = TechnicianKeyTestFixtures.KEY_ID,
                action = "service.menu",
                channel = "OFFLINE",
                outcome = "SUCCESS",
            )
        assertFalse(second)
        assertEquals(1, auditDao.rows.size)
    }

    @Test
    fun `session clears on expiry`() {
        sessionStore.establish(
            technicianKeyId = TechnicianKeyTestFixtures.KEY_ID,
            scopes = listOf("service.menu"),
            sessionToken = "token",
            expiresAtMs = 1_000L,
        )
        sessionStore.clearIfExpired(2_000L)
        assertNull(sessionStore.currentSession())
    }

    @Test
    fun `session clears on revocation epoch bump`() {
        sessionStore.clearOnRevocationEpochChange(0)
        sessionStore.establish(
            technicianKeyId = TechnicianKeyTestFixtures.KEY_ID,
            scopes = listOf("service.menu"),
            sessionToken = "token",
            expiresAtMs = System.currentTimeMillis() + 60_000L,
        )
        sessionStore.clearOnRevocationEpochChange(1)
        assertNull(sessionStore.currentSession())
    }

    private suspend fun seedAllowlist(
        machineId: String?,
        revocationEpoch: Int = 0,
    ) {
        val record =
            TechnicianAllowlistWireRecordDto(
                keyId = TechnicianKeyTestFixtures.KEY_ID,
                fingerprint = TechnicianKeyTestFixtures.canonicalFields.fingerprint,
                machineId = machineId,
                scopes = listOf("service.menu"),
                expiresAt = TechnicianKeyTestFixtures.EXPIRES_AT,
                revision = "3",
                revocationEpoch = revocationEpoch,
                signature = signature,
            )
        allowlistDao.upsert(
            TechnicianAllowlistEntity(
                fingerprint = record.fingerprint,
                keyId = record.keyId,
                machineId = record.machineId,
                scopesJson = "[\"service.menu\"]",
                expiresAtMs = java.time.Instant.parse(record.expiresAt!!).toEpochMilli(),
                expiresAtIso = record.expiresAt,
                revocationEpoch = record.revocationEpoch,
                revision = record.revision,
                signature = record.signature,
                recordJson = record.toString(),
                revoked = false,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        stateDao.upsert(
            (stateDao.getState() ?: TechnicianAllowlistStateEntity()).copy(revocationEpoch = revocationEpoch),
        )
    }
}

class TechnicianAllowlistStoreDeltaTest {
    @Test
    fun `applyDelta preserves persisted server policy while updating sync cursor`() = runTest {
        val allowlistDao = FakeTechnicianAllowlistDao()
        val stateDao = FakeTechnicianAllowlistStateDao()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val capability =
            MvpTechnicianKeysCapabilityDto(
                validateEndpoint = "/validate",
                allowlistDeltaEndpoint = "/delta",
                auditBatchEndpoint = "/audit",
                offlineScopes = listOf("service.menu"),
            )
        stateDao.upsert(
            TechnicianAllowlistStateEntity(
                deltaCursor = "cursor-1",
                revocationEpoch = 0,
                lastSyncAtMs = 100L,
                serverTechnicianKeysEnabled = true,
                offlineScopesJson = json.encodeToString(listOf("service.menu")),
                onlineOnlyScopesJson = "[]",
                capabilityJson = json.encodeToString(capability),
                hasTrustedAllowlistSync = true,
                policyUpdatedAtMs = 1000L,
            ),
        )
        val store = TechnicianAllowlistStore(allowlistDao, stateDao)
        store.applyDeltaTransactionally(
            records = emptyList(),
            tombstones = emptyList(),
            nextCursor = "cursor-2",
            revocationEpoch = 1,
        )
        val state = stateDao.getState()!!
        assertEquals("cursor-2", state.deltaCursor)
        assertEquals(1, state.revocationEpoch)
        assertTrue(state.lastSyncAtMs > 0L)
        assertEquals(true, state.serverTechnicianKeysEnabled)
        assertTrue(state.hasTrustedAllowlistSync)
        assertTrue(state.offlineScopesJson.contains("service.menu"))
        assertNotNull(state.capabilityJson)
        assertEquals(1000L, state.policyUpdatedAtMs)
    }

    @Test
    fun `hello enabled policy survives delta apply and cold-start offline auth succeeds`() = runTest {
        val allowlistDao = FakeTechnicianAllowlistDao()
        val stateDao = FakeTechnicianAllowlistStateDao()
        val auditDao = FakeTechnicianAuditOutboxDao()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val allowlistStore = TechnicianAllowlistStore(allowlistDao, stateDao)
        val auditStore = TechnicianAuditOutboxStore(auditDao)
        val policyStore = TechnicianKeyPolicyStore(stateDao)
        val clock = BoundedTelemetryClock().apply { updateFromServer("2026-07-27T10:00:00.000Z") }
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
        policyStore.updateFromHello(
            serverTechnicianKeysEnabled = true,
            capability =
                MvpTechnicianKeysCapabilityDto(
                    validateEndpoint = "/validate",
                    allowlistDeltaEndpoint = "/delta",
                    auditBatchEndpoint = "/audit",
                    offlineScopes = listOf("service.menu"),
                ),
        )
        policyStore.markTrustedAllowlistSync(revocationEpoch = 0, lastSyncAtMs = 100L)
        val record =
            TechnicianAllowlistWireRecordDto(
                keyId = TechnicianKeyTestFixtures.KEY_ID,
                fingerprint = TechnicianKeyTestFixtures.canonicalFields.fingerprint,
                machineId = TechnicianKeyTestFixtures.MACHINE_ID,
                scopes = listOf("service.menu"),
                expiresAt = TechnicianKeyTestFixtures.EXPIRES_AT,
                revision = "3",
                revocationEpoch = 0,
                signature = sig,
            )
        allowlistStore.applyDeltaTransactionally(
            records = listOf(record),
            tombstones = emptyList(),
            nextCursor = "cursor-after-delta",
            revocationEpoch = 0,
        )
        val persisted = stateDao.getState()!!
        assertEquals(true, persisted.serverTechnicianKeysEnabled)
        assertTrue(persisted.hasTrustedAllowlistSync)
        assertTrue(persisted.offlineScopesJson.contains("service.menu"))
        assertNotNull(persisted.capabilityJson)
        val service =
            TechnicianKeyAuthorizationService(
                allowlistStore,
                TechnicianAllowlistVerifier(keysStore),
                auditStore,
                TechnicianSessionStore(),
                policyStore,
                clock,
                TechnicianKeyMetrics(allowlistStore, auditStore),
            )
        val result =
            service.authorizeOffline(
                TechnicianKeyTestFixtures.NORMALIZED_KEY,
                TechnicianKeyTestFixtures.MACHINE_ID,
                "service.menu",
                "880e8400-e29b-41d4-a716-446655440020",
            )
        assertTrue(result.allowed)
    }

    @Test
    fun `should mark tombstones on key id and fingerprint`() = runTest {
        val allowlistDao = FakeTechnicianAllowlistDao()
        val fp = TechnicianKeyTestFixtures.canonicalFields.fingerprint
        allowlistDao.upsert(
            TechnicianAllowlistEntity(
                fingerprint = fp,
                keyId = TechnicianKeyTestFixtures.KEY_ID,
                machineId = null,
                scopesJson = "[\"service.menu\"]",
                expiresAtMs = null,
                revocationEpoch = 0,
                revision = "1",
                signature = "sig",
                recordJson = "{}",
                revoked = false,
                updatedAtMs = 1L,
            ),
        )
        allowlistDao.markRevokedByKeyId(TechnicianKeyTestFixtures.KEY_ID, 2L)
        allowlistDao.markRevokedByFingerprint(fp, 2L)
        assertNull(allowlistDao.findActiveByFingerprint(fp))
    }
}

class TechnicianAuditOutboxPersistenceTest {
    @Test
    fun `audit entity never stores raw key field`() {
        val entity =
            TechnicianAuditOutboxEntity(
                requestUuid = "880e8400-e29b-41d4-a716-446655440003",
                fingerprint = TechnicianKeyTestFixtures.canonicalFields.fingerprint,
                technicianKeyId = TechnicianKeyTestFixtures.KEY_ID,
                action = "service.menu",
                channel = "OFFLINE",
                outcome = "SUCCESS",
                failureCode = null,
                createdAtMs = 1L,
            )
        val serialized = entity.toString()
        assertNotEquals(TechnicianKeyTestFixtures.NORMALIZED_KEY, entity.fingerprint)
        assertFalse(serialized.contains(TechnicianKeyTestFixtures.NORMALIZED_KEY))
    }

    @Test
    fun `pending audit cap rejects new enqueue without dropping existing rows`() = runTest {
        val auditDao = FakeTechnicianAuditOutboxDao()
        val auditStore = TechnicianAuditOutboxStore(auditDao)
        repeat(TechnicianKeyConstants.MAX_PENDING_AUDIT_RECORDS) { index ->
            val ok =
                auditStore.enqueueNew(
                    requestUuid = "880e8400-e29b-41d4-a716-44665544${"%04d".format(index)}",
                    fingerprint = "fp-$index",
                    technicianKeyId = null,
                    action = "service.menu",
                    channel = "OFFLINE",
                    outcome = "SUCCESS",
                )
            assertTrue(ok)
        }
        assertEquals(TechnicianKeyConstants.MAX_PENDING_AUDIT_RECORDS, auditStore.pendingCount())
        val rejected =
            auditStore.enqueueNew(
                requestUuid = "990e8400-e29b-41d4-a716-446655440099",
                fingerprint = "fp-overflow",
                technicianKeyId = null,
                action = "service.menu",
                channel = "OFFLINE",
                outcome = "SUCCESS",
            )
        assertFalse(rejected)
        assertEquals(TechnicianKeyConstants.MAX_PENDING_AUDIT_RECORDS, auditStore.pendingCount())
        assertTrue(auditStore.isPendingCapReached())
    }

    @Test
    fun `offline authorization fails closed when audit pending cap is reached`() = runTest {
        val allowlistDao = FakeTechnicianAllowlistDao()
        val stateDao = FakeTechnicianAllowlistStateDao()
        val auditDao = FakeTechnicianAuditOutboxDao()
        val db = mockk<com.viwa.android.data.local.db.ViwaDatabase>(relaxed = true)
        val allowlistStore = TechnicianAllowlistStore(db, allowlistDao, stateDao)
        val auditStore = TechnicianAuditOutboxStore(auditDao)
        val policyStore = TechnicianKeyPolicyStore(stateDao)
        val sessionStore = TechnicianSessionStore()
        val clock = BoundedTelemetryClock().apply { updateFromServer("2026-07-27T10:00:00.000Z") }
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
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        stateDao.upsert(
            TechnicianAllowlistStateEntity(
                serverTechnicianKeysEnabled = true,
                offlineScopesJson = json.encodeToString(listOf("service.menu")),
                onlineOnlyScopesJson = "[]",
                hasTrustedAllowlistSync = true,
                lastSyncAtMs = 1L,
            ),
        )
        allowlistDao.upsert(
            TechnicianAllowlistEntity(
                fingerprint = TechnicianKeyTestFixtures.canonicalFields.fingerprint,
                keyId = TechnicianKeyTestFixtures.KEY_ID,
                machineId = TechnicianKeyTestFixtures.MACHINE_ID,
                scopesJson = json.encodeToString(listOf("service.menu")),
                expiresAtMs = java.time.Instant.parse(TechnicianKeyTestFixtures.EXPIRES_AT).toEpochMilli(),
                expiresAtIso = TechnicianKeyTestFixtures.EXPIRES_AT,
                revocationEpoch = 0,
                revision = "3",
                signature = sig,
                recordJson = "{}",
                revoked = false,
                updatedAtMs = 1L,
            ),
        )
        repeat(TechnicianKeyConstants.MAX_PENDING_AUDIT_RECORDS) { index ->
            auditDao.insert(
                TechnicianAuditOutboxEntity(
                    requestUuid = "aa0e8400-e29b-41d4-a716-44665544${"%04d".format(index)}",
                    fingerprint = "fp-$index",
                    technicianKeyId = null,
                    action = "service.menu",
                    channel = "OFFLINE",
                    outcome = "SUCCESS",
                    failureCode = null,
                    createdAtMs = index.toLong(),
                ),
            )
        }
        val service =
            TechnicianKeyAuthorizationService(
                allowlistStore,
                TechnicianAllowlistVerifier(keysStore),
                auditStore,
                sessionStore,
                policyStore,
                clock,
                TechnicianKeyMetrics(allowlistStore, auditStore),
            )
        val result =
            service.authorizeOffline(
                TechnicianKeyTestFixtures.NORMALIZED_KEY,
                TechnicianKeyTestFixtures.MACHINE_ID,
                "service.menu",
                "bb0e8400-e29b-41d4-a716-446655440099",
            )
        assertFalse(result.allowed)
        assertEquals(TechnicianAuthorizationReason.AUDIT_ENQUEUE_FAILED, result.reason)
        assertNull(sessionStore.currentSession())
        assertEquals(TechnicianKeyConstants.MAX_PENDING_AUDIT_RECORDS, auditStore.pendingCount())
    }
}
