package com.viwa.android.data.remote.telemetry.mvp

internal object FactoryProvisionKey {
    // XOR mask 0x5A — не светить plaintext константой
    private val MASK = 0x5A
    private val ENCODED =
        intArrayOf(
            12, 28, 10, 119, 17, 109, 55, 20, 104, 42, 11, 34, 99, 8, 45, 110, 18, 41, 3, 57, 108, 14, 62, 22, 98, 24, 44, 27, 105, 60, 29,
        )

    fun reveal(): String =
        String(ENCODED.map { (it xor MASK).toByte() }.toByteArray(), Charsets.UTF_8)
}
