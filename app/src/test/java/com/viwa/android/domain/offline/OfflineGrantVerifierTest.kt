package com.viwa.android.domain.offline

import com.viwa.android.data.local.entitlement.EntitlementCacheStore
import com.viwa.android.data.local.entitlement.FakeEntitlementCacheDao
import com.viwa.android.data.local.entitlement.FakeOfflineUsageLedgerDao
import com.viwa.android.data.local.entitlement.OfflineUsageLedgerState
import com.viwa.android.data.local.entitlement.OfflineUsageLedgerStore
import com.viwa.android.data.remote.telemetry.mvp.offline.OfflineGrantWirePayloadDto
import com.viwa.android.data.remote.telemetry.mvp.offline.OfflineSigningPublicKeyDto
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

object OfflineGrantTestFixtures {
    const val GRANT_ID = "550e8400-e29b-41d4-a716-446655440000"
    const val MACHINE_ID = "660e8400-e29b-41d4-a716-446655440001"
    const val SUBJECT_HASH = "deadbeef"
    const val SUBSCRIPTION_LEVEL_ID = "770e8400-e29b-41d4-a716-446655440002"
    const val ISSUED_AT = "2026-07-27T08:00:00.000Z"
    const val EXPIRES_AT = "2026-07-27T12:00:00.000Z"
    const val KEY_ID = "test-v1"

    val canonicalFields =
        OfflineGrantCanonicalSigning.CanonicalGrantFields(
            grantId = GRANT_ID,
            machineId = MACHINE_ID,
            subjectHash = SUBJECT_HASH,
            subscriptionLevelId = SUBSCRIPTION_LEVEL_ID,
            issuedAt = ISSUED_AT,
            expiresAt = EXPIRES_AT,
            dailyRemainingMlAtIssue = 500,
            maxOfflinePours = 1,
            maxOfflineVolumeMl = 500,
            signingKeyId = KEY_ID,
            revocationEpoch = 0,
        )
}

class OfflineGrantCanonicalSigningTest {
    @Test
    fun `canonical message matches backend pipe format`() {
        val message = OfflineGrantCanonicalSigning.buildCanonicalMessage(OfflineGrantTestFixtures.canonicalFields)
        val expected =
            "offline-grant-v1|550e8400-e29b-41d4-a716-446655440000|" +
                "660e8400-e29b-41d4-a716-446655440001|deadbeef|" +
                "770e8400-e29b-41d4-a716-446655440002|2026-07-27T08:00:00.000Z|" +
                "2026-07-27T12:00:00.000Z|500|1|500|test-v1|0"
        assertEquals(expected, String(message, Charsets.UTF_8))
    }

    @Test
    fun `subject hash normalizes lowercase trim`() {
        val clientId = "  2CAAF0B2-2B7F-4C09-9BEF-DAFD984C9A66  "
        val hash = SubjectHashUtil.computeSubjectHash(clientId)
        assertEquals(64, hash.length)
        assertEquals(hash, SubjectHashUtil.computeSubjectHash(clientId.trim().lowercase()))
    }
}

class OfflineGrantVerifierTest {
    @Test
    fun `should verify valid signature and reject tamper`() {
        val (publicPem, signature) = signFixtureGrant(OfflineGrantTestFixtures.canonicalFields)
        val keysStore = OfflineSigningKeysStore()
        keysStore.updateFromHello(
            listOf(
                OfflineSigningPublicKeyDto(
                    keyId = OfflineGrantTestFixtures.KEY_ID,
                    publicKeyPem = publicPem,
                    revocationEpoch = 0,
                ),
            ),
            revocationEpoch = 0,
        )
        val verifier = OfflineGrantVerifier(keysStore)
        val payload =
            OfflineGrantWirePayloadDto(
                grantId = OfflineGrantTestFixtures.GRANT_ID,
                subjectHash = OfflineGrantTestFixtures.SUBJECT_HASH,
                machineId = OfflineGrantTestFixtures.MACHINE_ID,
                subscriptionLevelId = OfflineGrantTestFixtures.SUBSCRIPTION_LEVEL_ID,
                issuedAt = OfflineGrantTestFixtures.ISSUED_AT,
                expiresAt = OfflineGrantTestFixtures.EXPIRES_AT,
                dailyRemainingMlAtIssue = 500,
                maxOfflinePours = 1,
                maxOfflineVolumeMl = 500,
                signingKeyId = OfflineGrantTestFixtures.KEY_ID,
                revocationEpoch = 0,
                revision = "42",
                signature = signature,
            )
        assertTrue(verifier.verifyGrantPayload(payload))
        assertFalse(verifier.verifyGrantPayload(payload.copy(dailyRemainingMlAtIssue = 999)))
    }

    private fun signFixtureGrant(fields: OfflineGrantCanonicalSigning.CanonicalGrantFields): Pair<String, String> {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicPem =
            "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded) +
                "\n-----END PUBLIC KEY-----"
        val signature = signWithPrivateKey(pair.private.encoded, fields)
        return publicPem to signature
    }

    private fun signWithPrivateKey(
        pkcs8: ByteArray,
        fields: OfflineGrantCanonicalSigning.CanonicalGrantFields,
    ): String {
        val seed = pkcs8.copyOfRange(pkcs8.size - 32, pkcs8.size)
        val privateKey = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
        val message = OfflineGrantCanonicalSigning.buildCanonicalMessage(fields)
        val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.generateSignature())
    }
}

class OfflinePourAuthorizationServiceTest {
    private lateinit var cacheStore: EntitlementCacheStore
    private lateinit var ledgerStore: OfflineUsageLedgerStore
    private lateinit var service: OfflinePourAuthorizationService
    private lateinit var pourCoordinator: OfflinePourTransactionCoordinator

    private val clientId = "2caaf0b2-2b7f-4c09-9bef-dafd984c9a66"
    private val machineId = OfflineGrantTestFixtures.MACHINE_ID

    @Before
    fun setUp() {
        cacheStore = EntitlementCacheStore(FakeEntitlementCacheDao())
        ledgerStore = OfflineUsageLedgerStore(FakeOfflineUsageLedgerDao())
        val clock = BoundedTelemetryClock()
        clock.updateFromServer("2026-07-27T09:00:00.000Z")
        val keysStore = OfflineSigningKeysStore()
        val verifierTest = OfflineGrantVerifierTest()
        val (publicPem, signature) = verifierTest.signFixtureGrantForTest(clientId, machineId)
        keysStore.updateFromHello(
            listOf(
                OfflineSigningPublicKeyDto(
                    keyId = OfflineGrantTestFixtures.KEY_ID,
                    publicKeyPem = publicPem,
                    revocationEpoch = 0,
                ),
            ),
            0,
        )
        val verifier = OfflineGrantVerifier(keysStore)
        val metrics = OfflineEntitlementMetrics(cacheStore, ledgerStore)
        service = OfflinePourAuthorizationService(cacheStore, ledgerStore, verifier, clock, metrics)
        val waterOutbox = mockk<com.viwa.android.data.local.outbox.LoyaltyWaterOutboxStore>(relaxed = true)
        pourCoordinator =
            OfflinePourTransactionCoordinator(ledgerStore, cacheStore, service, waterOutbox, clock, metrics)
        runBlocking { seedGrant(clientId, machineId, signature) }
    }

    private suspend fun seedGrant(clientId: String, machineId: String, signature: String) {
        val subjectHash = SubjectHashUtil.computeSubjectHash(clientId)
        cacheStore.upsertGrant(
            OfflineGrantWirePayloadDto(
                grantId = OfflineGrantTestFixtures.GRANT_ID,
                subjectHash = subjectHash,
                machineId = machineId,
                subscriptionLevelId = OfflineGrantTestFixtures.SUBSCRIPTION_LEVEL_ID,
                issuedAt = OfflineGrantTestFixtures.ISSUED_AT,
                expiresAt = OfflineGrantTestFixtures.EXPIRES_AT,
                dailyRemainingMlAtIssue = 500,
                maxOfflinePours = 1,
                maxOfflineVolumeMl = 500,
                signingKeyId = OfflineGrantTestFixtures.KEY_ID,
                revocationEpoch = 0,
                revision = "42",
                signature = signature,
            ),
        )
    }

    @Test
    fun `should allow first pour within tariff limits`() = runTest {
        val auth = service.authorizePour(clientId, machineId, volumeMl = 200)
        assertTrue(auth.allowed)
        assertEquals(OfflineAuthorizationReason.GRANTED, auth.reason)
    }

    @Test
    fun `should deny second pour same grant`() = runTest {
        val requestUuid = "880e8400-e29b-41d4-a716-446655440003"
        pourCoordinator.reservePour(clientId, machineId, 200, 20, "sale-1", requestUuid)
        pourCoordinator.markPouring(requestUuid)
        pourCoordinator.finalizePour(requestUuid, 200)
        val second = service.authorizePour(clientId, machineId, volumeMl = 100)
        assertFalse(second.allowed)
        assertEquals(OfflineAuthorizationReason.OFFLINE_POUR_LIMIT, second.reason)
    }

    @Test
    fun `should deny volume above grant max`() = runTest {
        val auth = service.authorizePour(clientId, machineId, volumeMl = 600)
        assertFalse(auth.allowed)
        assertEquals(OfflineAuthorizationReason.OFFLINE_VOLUME_LIMIT, auth.reason)
    }

    @Test
    fun `should recover reserved state on startup`() = runTest {
        val requestUuid = "990e8400-e29b-41d4-a716-446655440004"
        pourCoordinator.reservePour(clientId, machineId, 200, 20, "sale-2", requestUuid)
        pourCoordinator.recoverUncertainStatesOnStartup()
        val row = ledgerStore.findByRequestUuid(requestUuid)
        assertEquals(OfflineUsageLedgerState.REJECTED.name, row?.state)
    }
}

private fun OfflineGrantVerifierTest.signFixtureGrantForTest(clientId: String, machineId: String): Pair<String, String> {
    val subjectHash = SubjectHashUtil.computeSubjectHash(clientId)
    val fields =
        OfflineGrantTestFixtures.canonicalFields.copy(
            subjectHash = subjectHash,
            machineId = machineId,
        )
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val publicPem =
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded) +
            "\n-----END PUBLIC KEY-----"
    val pkcs8 = pair.private.encoded
    val seed = pkcs8.copyOfRange(pkcs8.size - 32, pkcs8.size)
    val privateKey = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
    val message = OfflineGrantCanonicalSigning.buildCanonicalMessage(fields)
    val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
    signer.init(true, privateKey)
    signer.update(message, 0, message.size)
    val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.generateSignature())
    return publicPem to signature
}
