package com.viwa.android.data.payment.aqsi.support

private const val ARCUS_STX: Byte = 0x01

fun buildArcusFrame(command: String, payload: String = ""): ByteArray {
    val body = if (payload.isEmpty()) "$command:" else "$command:$payload"
    val bytes = body.toByteArray(Charsets.UTF_8)
    return byteArrayOf(
        ARCUS_STX,
        (bytes.size / 256).toByte(),
        (bytes.size % 256).toByte(),
    ) + bytes
}

fun arcusPayQueue(
    prepay: List<ByteArray> = listOf(buildArcusFrame("ENDTR", "")),
    payment: List<ByteArray>,
): ArrayDeque<ByteArray> = ArrayDeque(prepay + payment)
