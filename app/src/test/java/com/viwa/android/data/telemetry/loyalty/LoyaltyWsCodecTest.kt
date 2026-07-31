package com.viwa.android.data.telemetry.loyalty

import com.viwa.android.services.telemetry.SubscribeInformationState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoyaltyWsCodecTest {
    @Test
    fun T11_1_encodeStatusGetEnvelope_containsTypeAndClientId() {
        // given
        val clientId = "660e8400-e29b-41d4-a716-446655440010"
        val messageId = "550e8400-e29b-41d4-a716-446655440001"
        val sentAt = "2026-07-27T09:00:00.000Z"

        // when
        val raw = LoyaltyWsCodec.encodeStatusGetEnvelopeJson(clientId, messageId, sentAt)

        // then
        assertTrue(raw.contains("\"type\":\"loyalty.status.get\""))
        assertTrue(raw.contains("\"messageId\":\"$messageId\""))
        assertTrue(raw.contains("\"sentAt\":\"$sentAt\""))
        assertTrue(raw.contains("\"clientId\":\"$clientId\""))
    }

    @Test
    fun T11_2_decodeStatusAck_mapsVolumeAndLimitFields() {
        // given
        val payload =
            buildJsonObject {
                put("clientId", "660e8400-e29b-41d4-a716-446655440010")
                put("active", true)
                put("volumeMl", 450)
                put("dailyLimitMl", 2000)
                put("dailyUsedMl", 1550)
                put("dailyRemainingMl", 450)
                put("limitExhausted", false)
            }

        // when
        val state = LoyaltyWsCodec.decodeStatusAck(payload)
        val fields = LoyaltyWsCodec.decodeStatusAckFields(payload)

        // then
        assertEquals(450, state.volumeMl)
        assertEquals(2000, state.maxVolumeMl)
        assertFalse(fields.limitExhausted)
        assertEquals(450, fields.dailyRemainingMl)
    }

    @Test
    fun decodeStatusAck_trialUsesClientVolumeMlWhenPoolEmpty() {
        // given — trial: volumeMl=1000, monthly pool dailyRemainingMl=0, active=false
        val payload =
            buildJsonObject {
                put("clientId", "660e8400-e29b-41d4-a716-446655440010")
                put("active", false)
                put("volumeMl", 1000)
                put("dailyLimitMl", 0)
                put("dailyUsedMl", 0)
                put("dailyRemainingMl", 0)
                put("limitExhausted", false)
            }

        // when
        val state = LoyaltyWsCodec.decodeStatusAck(payload)

        // then
        assertEquals(1000, state.volumeMl)
        assertEquals(1000, state.maxVolumeMl)
        assertFalse(state.isActiveSubscribe)
    }

    @Test
    fun mergePourBalanceAck_partialDailyRemaining_preservesClientAndMax() {
        // given
        val current =
            SubscribeInformationState(
                isStatusRequest = true,
                isActiveSubscribe = true,
                clientId = "660e8400-e29b-41d4-a716-446655440010",
                subscribeDateEnd = "2026-08-31T00:00:00.000Z",
                volumeMl = 450,
                maxVolumeMl = 2000,
            )
        val payload = buildJsonObject { put("dailyRemainingMl", 250) }

        // when
        val merged = LoyaltyWsCodec.mergePourBalanceAck(current, payload)

        // then
        assertEquals(250, merged!!.volumeMl)
        assertEquals(2000, merged.maxVolumeMl)
        assertEquals("660e8400-e29b-41d4-a716-446655440010", merged.clientId)
        assertEquals("2026-08-31T00:00:00.000Z", merged.subscribeDateEnd)
        assertTrue(merged.isActiveSubscribe)
    }

    @Test
    fun mergePourBalanceAck_trialIgnoresPoolRemainingWithoutWalletBalance() {
        val current =
            SubscribeInformationState(
                isStatusRequest = true,
                isActiveSubscribe = false,
                clientId = "660e8400-e29b-41d4-a716-446655440010",
                subscribeDateEnd = null,
                volumeMl = 1_000,
                maxVolumeMl = 1_000,
            )
        val payload = buildJsonObject { put("dailyRemainingMl", 0) }

        val merged = LoyaltyWsCodec.mergePourBalanceAck(current, payload)

        assertEquals(1_000, merged!!.volumeMl)
        assertEquals(1_000, merged.maxVolumeMl)
        assertFalse(merged.isActiveSubscribe)
    }

    @Test
    fun parseClientIdFromScan_acceptsValidUuid() {
        // given
        val uuid = "660e8400-e29b-41d4-a716-446655440010"

        // when
        val parsed = LoyaltyWsCodec.parseClientIdFromScan("CLIENT_$uuid")

        // then
        assertTrue(parsed.isSuccess)
        assertEquals(uuid, parsed.getOrNull())
    }

    @Test
    fun parseClientIdFromScan_rejectsInvalidUuid() {
        // when
        val parsed = LoyaltyWsCodec.parseClientIdFromScan("CLIENT_not-a-uuid")

        // then
        assertTrue(parsed.isFailure)
    }
}
