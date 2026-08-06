package com.viwa.android.data.remote.telemetry.mvp.cells

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CellsContentReportAckSemanticsTest {

    @Test
    fun `accepts ok true with applied greater than zero`() {
        val result =
            CellsContentReportAckSemantics.parseAck(
                buildJsonObject {
                    put("ok", true)
                    put("applied", 1)
                },
            )

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().applied)
    }

    @Test
    fun `rejects applied zero`() {
        val result =
            CellsContentReportAckSemantics.parseAck(
                buildJsonObject {
                    put("ok", true)
                    put("applied", 0)
                },
            )

        assertTrue(result.isFailure)
    }
}
