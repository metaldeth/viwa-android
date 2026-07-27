package com.viwa.android.services.telemetry

import com.viwa.android.data.telemetry.loyalty.LoyaltyWsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T11-3: scan prefix `CLIENT_{uuid}` validation used by [LoyaltyCardScanCoordinator]. */
class LoyaltyCardScanCoordinatorTest {
    @Test
    fun T11_3_validClientPrefix_extractsUuid() {
        // given
        val uuid = "660e8400-e29b-41d4-a716-446655440010"

        // when
        val parsed = LoyaltyWsCodec.parseClientIdFromScan("CLIENT_$uuid")

        // then
        assertTrue(parsed.isSuccess)
        assertEquals(uuid, parsed.getOrNull())
    }

    @Test
    fun T11_3_invalidClientPrefix_returnsError() {
        // when
        val parsed = LoyaltyWsCodec.parseClientIdFromScan("CLIENT_not-a-uuid")

        // then
        assertTrue(parsed.isFailure)
    }

    @Test
    fun T11_3_nonClientPrefix_returnsError() {
        // when
        val parsed = LoyaltyWsCodec.parseClientIdFromScan("660e8400-e29b-41d4-a716-446655440010")

        // then
        assertTrue(parsed.isFailure)
    }
}
