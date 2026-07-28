package com.viwa.android.data.telemetry.technician

import com.viwa.android.data.remote.telemetry.mvp.offline.ValidateTechnicianKeyResponseDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object TechnicianKeyWsCodec {
    const val TYPE_VALIDATE = "technician.key.validate"

    fun encodeValidate(code: String, requestedScope: String, requestUuid: String): JsonObject =
        buildJsonObject {
            put("code", code)
            put("requestedScope", requestedScope)
            put("requestUuid", requestUuid)
        }

    fun decodeValidateAck(payload: JsonObject): ValidateTechnicianKeyResponseDto {
        val json =
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
            }
        return json.decodeFromJsonElement(ValidateTechnicianKeyResponseDto.serializer(), payload)
    }

    fun decodeErrorCode(payload: JsonObject): String? {
        val nested = payload["message"]?.jsonObject
        return nested?.get("code")?.jsonPrimitive?.content ?: payload["code"]?.jsonPrimitive?.content
    }
}
