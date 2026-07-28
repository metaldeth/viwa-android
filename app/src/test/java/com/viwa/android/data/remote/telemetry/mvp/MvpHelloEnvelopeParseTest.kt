package com.viwa.android.data.remote.telemetry.mvp

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MvpHelloEnvelopeParseTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    private val helloJson =
        """
        {
          "type": "hello",
          "messageId": "msg-hello-1",
          "sentAt": "2026-07-27T12:00:00Z",
          "payload": {
            "serialNumber": "VIWA-000001",
            "heartbeatIntervalSeconds": 10
          }
        }
        """.trimIndent()

    @Test
    fun `hello envelope parses for manager tests`() {
        val envelope = json.decodeFromString(MvpWsEnvelopeDto.serializer(), helloJson)
        assertEquals("hello", envelope.type)
        assertNotNull(envelope.payload)
        val hello = json.decodeFromJsonElement(MvpHelloPayloadDto.serializer(), envelope.payload!!)
        assertEquals("VIWA-000001", hello.serialNumber)
    }
}
