package com.viwa.android.data.remote.telemetry.mvp

import kotlinx.serialization.json.JsonObject

/** Inbound loyalty WS events routed from [MvpTelemetryWebSocketManager]. */
interface MvpTelemetryLoyaltySyncHandler {
    suspend fun onLoyaltyAck(correlationId: String, payload: JsonObject)

    suspend fun onStatusChanged(payload: JsonObject)

    suspend fun onLoyaltyError(correlationId: String?, code: String, message: String)
}
