package com.viwa.android.domain.recipe

import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-repo scale vectors — must match
 * `wiva-telemetry/apps/api/test/fixtures/recipe/scale-v1.json` (byte-identical).
 */
class RecipeScaleDeciTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** SHA-256 of `wiva-telemetry/apps/api/test/fixtures/recipe/scale-v1.json` (byte-identical copy). */
    private companion object {
        const val TELEMETRY_SCALE_FILE_SHA256 =
            "2fa3ece8afb91d9e0a173b6acc76bdb409bd0b169f11c8a9bf33c1dd2367b878"
    }

    @Test
    fun `scale fixture paritySha256 matches TS sync gate`() {
        val rawText = readRawText()
        val fixture = json.decodeFromString<ScaleFixture>(rawText)
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
    fun `shared scale fixture is byte-identical with telemetry canonical copy`() {
        val rawBytes =
            requireNotNull(javaClass.classLoader?.getResourceAsStream("recipe/scale-v1.json")) {
                "Missing recipe/scale-v1.json"
            }.readBytes()
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(rawBytes)
                .joinToString("") { byte -> "%02x".format(byte) }
        assertEquals(TELEMETRY_SCALE_FILE_SHA256, digest)
    }

    @Test
    fun `roundHalfUp vectors from scale-v1 fixture`() {
        loadFixture().roundHalfUpVectors.forEach { row ->
            assertEquals(
                "roundHalfUp failed for ${row.id}",
                row.expect,
                RecipeCanonical.roundHalfUp(row.numerator, row.denominator),
            )
        }
    }

    @Test
    fun `scale-v1 success vectors match expected scaled triple`() {
        loadFixture().vectors.filter { it.expectSuccess }.forEach { row ->
            val base =
                RecipeCanonicalTriple(
                    baseDrinkVolumeMl = row.baseDrinkVolumeMl,
                    waterDeciMl = row.waterDeciMl,
                    productDeciMl = row.productDeciMl,
                )
            val result = RecipeCanonical.scaleRecipeDeci(base, row.targetDrinkVolumeMl)
            assertTrue("Expected success for ${row.id}", result.success)
            assertEquals(
                RecipeCanonicalTriple(
                    baseDrinkVolumeMl = requireNotNull(row.scaledBaseDrinkVolumeMl),
                    waterDeciMl = requireNotNull(row.scaledWaterDeciMl),
                    productDeciMl = requireNotNull(row.scaledProductDeciMl),
                ),
                result.scaled,
            )
            val expectedSum = row.targetDrinkVolumeMl * 10
            val actualSum = result.scaled!!.waterDeciMl + result.scaled!!.productDeciMl
            assertEquals(
                "sumDeltaFromTarget for ${row.id}",
                requireNotNull(row.sumDeltaFromTarget),
                abs(actualSum - expectedSum),
            )
        }
    }

    @Test
    fun `scale-v1 reject vectors fail with fixture reasons`() {
        loadFixture().vectors.filter { !it.expectSuccess }.forEach { row ->
            val base =
                RecipeCanonicalTriple(
                    baseDrinkVolumeMl = row.baseDrinkVolumeMl,
                    waterDeciMl = row.waterDeciMl,
                    productDeciMl = row.productDeciMl,
                )
            val result = RecipeCanonical.scaleRecipeDeci(base, row.targetDrinkVolumeMl)
            assertFalse("Expected reject for ${row.id}", result.success)
            assertNull(result.scaled)
            row.rejectReasons?.forEach { reason ->
                assertTrue(
                    "Missing $reason for ${row.id}",
                    result.errors.any { it.name == reason },
                )
            }
        }
    }

    @Test
    fun `toRecipeDisplay converts deci to ml for UI only`() {
        val triple =
            RecipeCanonicalTriple(
                baseDrinkVolumeMl = 300,
                waterDeciMl = 2705,
                productDeciMl = 295,
            )
        val display = triple.toRecipeDisplay()
        assertEquals(300, display.baseDrinkVolumeMl)
        assertEquals(270.5, display.waterMl, 0.0001)
        assertEquals(29.5, display.productMl, 0.0001)
    }

    private fun readRawText(): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("recipe/scale-v1.json")) {
            "Missing recipe/scale-v1.json"
        }.bufferedReader().readText()

    private fun loadFixture(): ScaleFixture = json.decodeFromString(readRawText())

    @kotlinx.serialization.Serializable
    private data class ScaleFixture(
        @SerialName("schemaVersion") val schemaVersion: Int,
        @SerialName("function") val function: String,
        @SerialName("architectureSection") val architectureSection: String,
        @SerialName("syncNote") val syncNote: String? = null,
        @SerialName("paritySha256") val paritySha256: String? = null,
        @SerialName("roundHalfUpVectors") val roundHalfUpVectors: List<RoundHalfUpVector>,
        @SerialName("vectors") val vectors: List<ScaleVector>,
    )

    @kotlinx.serialization.Serializable
    private data class RoundHalfUpVector(
        @SerialName("id") val id: String,
        @SerialName("numerator") val numerator: Long,
        @SerialName("denominator") val denominator: Int,
        @SerialName("expect") val expect: Int,
    )

    @kotlinx.serialization.Serializable
    private data class ScaleVector(
        @SerialName("id") val id: String,
        @SerialName("baseDrinkVolumeMl") val baseDrinkVolumeMl: Int,
        @SerialName("waterDeciMl") val waterDeciMl: Int,
        @SerialName("productDeciMl") val productDeciMl: Int,
        @SerialName("targetDrinkVolumeMl") val targetDrinkVolumeMl: Int,
        @SerialName("expectSuccess") val expectSuccess: Boolean,
        @SerialName("scaledBaseDrinkVolumeMl") val scaledBaseDrinkVolumeMl: Int? = null,
        @SerialName("scaledWaterDeciMl") val scaledWaterDeciMl: Int? = null,
        @SerialName("scaledProductDeciMl") val scaledProductDeciMl: Int? = null,
        @SerialName("sumDeltaFromTarget") val sumDeltaFromTarget: Int? = null,
        @SerialName("rejectReasons") val rejectReasons: List<String>? = null,
    )
}
