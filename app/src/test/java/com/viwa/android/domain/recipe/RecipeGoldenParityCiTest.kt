package com.viwa.android.domain.recipe

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CI gate: cross-repo golden fixture parity (task-20 / architecture §15.1).
 * Canonical source: `wiva-telemetry/apps/api/test/fixtures/recipe/golden-v1.json`.
 */
class RecipeGoldenParityCiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `golden-v1 byte-identical with telemetry canonical copy`() {
        assertFixtureByteIdentical("golden-v1.json", TELEMETRY_GOLDEN_FILE_SHA256)
    }

    @Test
    fun `scale-v1 byte-identical with telemetry canonical copy`() {
        assertFixtureByteIdentical("scale-v1.json", TELEMETRY_SCALE_FILE_SHA256)
    }

    @Test
    fun `golden-v1 paritySha256 matches TS sync gate`() {
        val rawText = readRawText("golden-v1.json")
        val fixture = json.decodeFromString<GoldenParityFixture>(rawText)
        val root = json.parseToJsonElement(rawText).jsonObject
        val filtered = JsonObject(root.filter { (key, _) -> key != "paritySha256" })
        val payload = json.encodeToString(JsonObject.serializer(), filtered)
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        assertEquals(requireNotNull(fixture.paritySha256), digest)
    }

    private fun assertFixtureByteIdentical(resourceName: String, expectedSha256: String) {
        val rawBytes =
            requireNotNull(javaClass.classLoader?.getResourceAsStream("recipe/$resourceName")) {
                "Missing recipe/$resourceName"
            }.readBytes()
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(rawBytes)
                .joinToString("") { byte -> "%02x".format(byte) }
        assertEquals(expectedSha256, digest)
    }

    private fun readRawText(resourceName: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("recipe/$resourceName")) {
            "Missing recipe/$resourceName"
        }.bufferedReader().use { it.readText() }

    @kotlinx.serialization.Serializable
    private data class GoldenParityFixture(
        @SerialName("paritySha256") val paritySha256: String? = null,
    )

    private companion object {
        /** SHA-256 of wiva-telemetry/apps/api/test/fixtures/recipe/golden-v1.json */
        const val TELEMETRY_GOLDEN_FILE_SHA256 =
            "a04f5e7a4010cb3d618cd167adfd82341c9c1d14b6a962fb54708f74380ef76c"

        /** SHA-256 of wiva-telemetry/apps/api/test/fixtures/recipe/scale-v1.json */
        const val TELEMETRY_SCALE_FILE_SHA256 =
            "2fa3ece8afb91d9e0a173b6acc76bdb409bd0b169f11c8a9bf33c1dd2367b878"
    }
}
