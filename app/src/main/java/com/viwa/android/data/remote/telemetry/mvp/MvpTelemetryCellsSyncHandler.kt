package com.viwa.android.data.remote.telemetry.mvp

import kotlinx.serialization.json.JsonObject

/** Callbacks MVP cells sync из [MvpTelemetryWebSocketManager] в [TelemetryCellsSyncCoordinator]. */
interface MvpTelemetryCellsSyncHandler {
    suspend fun onWebSocketHello(hello: MvpHelloPayloadDto)

    suspend fun onCellsSnapshot(payloadJson: String)

    suspend fun onSchemaAck(payload: JsonObject)

    suspend fun onRecipeSyncControl(payloadJson: String)

    suspend fun onRecipeCommand(payloadJson: String)

    suspend fun onRecipeDisconnect()
}
