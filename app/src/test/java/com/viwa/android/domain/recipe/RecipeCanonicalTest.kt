package com.viwa.android.domain.recipe

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-repo golden vector — must match
 * `viwa-telemetry/apps/api/test/fixtures/recipe/golden-v1.json` (byte-identical).
 */
class RecipeCanonicalTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** SHA-256 of `wiva-telemetry/apps/api/test/fixtures/recipe/golden-v1.json` (byte-identical copy). */
    private companion object {
        const val TELEMETRY_GOLDEN_FILE_SHA256 =
            "a04f5e7a4010cb3d618cd167adfd82341c9c1d14b6a962fb54708f74380ef76c"
    }

    @Test
    fun `golden fixture paritySha256 matches TS sync gate`() {
        val rawText = readRawText()
        val fixture = json.decodeFromString<GoldenFixture>(rawText)
        val root = json.parseToJsonElement(rawText).jsonObject
        val filtered = JsonObject(root.filter { (key, _) -> key != "paritySha256" })
        val payload = json.encodeToString(JsonObject.serializer(), filtered)
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        assertEquals(requireNotNull(fixture.paritySha256), digest)
    }

    @Test
    fun `shared golden fixture is byte-identical with telemetry canonical copy`() {
        val rawBytes =
            requireNotNull(javaClass.classLoader?.getResourceAsStream("recipe/golden-v1.json")) {
                "Missing recipe/golden-v1.json"
            }.readBytes()
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(rawBytes)
                .joinToString("") { byte -> "%02x".format(byte) }
        // Must match SHA-256 of wiva-telemetry/apps/api/test/fixtures/recipe/golden-v1.json
        assertEquals(TELEMETRY_GOLDEN_FILE_SHA256, digest)
    }

    @Test
    fun `golden vectors produce byte-identical SHA-256 fingerprints`() {
        val fixture = loadFixture()
        fixture.vectors.filter { it.expectValid }.forEach { row ->
            val triple =
                RecipeCanonicalTriple(
                    baseDrinkVolumeMl = row.baseDrinkVolumeMl,
                    waterDeciMl = row.waterDeciMl,
                    productDeciMl = row.productDeciMl,
                )
            assertTrue(RecipeCanonical.validate(triple).valid)
            assertEquals(row.canonicalString, RecipeCanonical.buildCanonicalString(triple))
            assertEquals(row.fingerprint, RecipeCanonical.computeFingerprint(triple))
            assertEquals(row.fingerprint, RecipeCanonical.fingerprint(triple))
        }
    }

    @Test
    fun `golden reject vectors fail strict validation`() {
        val fixture = loadFixture()
        fixture.vectors.filter { !it.expectValid }.forEach { row ->
            val triple =
                RecipeCanonicalTriple(
                    baseDrinkVolumeMl = row.baseDrinkVolumeMl,
                    waterDeciMl = row.waterDeciMl,
                    productDeciMl = row.productDeciMl,
                )
            val result = RecipeCanonical.validate(triple)
            assertFalse(result.valid)
            row.rejectReasons?.forEach { reason ->
                assertTrue(
                    result.errors.any { it.name == reason },
                )
            }
            try {
                RecipeCanonical.fingerprint(triple)
                error("Expected fingerprint to throw for ${row.id}")
            } catch (_: IllegalStateException) {
                // expected
            }
        }
    }

    @Test
    fun `ml to deci round-trip matches TS Math round`() {
        assertEquals(2700, RecipeCanonical.mlToDeciMl(270.0))
        assertEquals(2701, RecipeCanonical.mlToDeciMl(270.05))
        assertEquals(270.0, RecipeCanonical.deciMlToMl(2700), 0.0001)
        assertEquals(270.5, RecipeCanonical.deciMlToMl(2705), 0.0001)
    }

    private fun readRawText(): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("recipe/golden-v1.json")) {
            "Missing recipe/golden-v1.json"
        }.bufferedReader().readText()

    private fun loadFixture(): GoldenFixture = json.decodeFromString(readRawText())

    @kotlinx.serialization.Serializable
    private data class GoldenFixture(
        @SerialName("schemaVersion") val schemaVersion: Int,
        @SerialName("canonicalPrefix") val canonicalPrefix: String,
        @SerialName("fieldOrder") val fieldOrder: List<String>,
        @SerialName("syncNote") val syncNote: String? = null,
        @SerialName("paritySha256") val paritySha256: String? = null,
        @SerialName("vectors") val vectors: List<GoldenVector>,
    )

    @kotlinx.serialization.Serializable
    private data class GoldenVector(
        @SerialName("id") val id: String,
        @SerialName("baseDrinkVolumeMl") val baseDrinkVolumeMl: Int,
        @SerialName("waterDeciMl") val waterDeciMl: Int,
        @SerialName("productDeciMl") val productDeciMl: Int,
        @SerialName("canonicalString") val canonicalString: String? = null,
        @SerialName("fingerprint") val fingerprint: String? = null,
        @SerialName("expectValid") val expectValid: Boolean,
        @SerialName("rejectReasons") val rejectReasons: List<String>? = null,
    )
}
