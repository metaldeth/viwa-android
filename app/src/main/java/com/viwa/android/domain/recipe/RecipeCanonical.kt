package com.viwa.android.domain.recipe

import java.security.MessageDigest
import kotlin.math.roundToInt

/** Integer deci-ml recipe identity — must match `recipe-canonical.util.ts`. */
data class RecipeCanonicalTriple(
    val baseDrinkVolumeMl: Int,
    val waterDeciMl: Int,
    val productDeciMl: Int,
)

enum class RecipeValidationErrorCode {
    BASE_DRINK_VOLUME_ML_MIN,
    BASE_DRINK_VOLUME_ML_MAX,
    WATER_DECI_ML_MIN,
    WATER_DECI_ML_MAX,
    PRODUCT_DECI_ML_MIN,
    PRODUCT_DECI_ML_MAX,
    INVARIANT_SUM,
    INVARIANT_ZERO,
}

enum class RecipeScaleErrorCode {
    BASE_INVALID,
    TARGET_BASE_DRINK_VOLUME_ML_MIN,
    TARGET_BASE_DRINK_VOLUME_ML_MAX,
    WATER_DECI_ML_MAX,
    PRODUCT_DECI_ML_MAX,
    INVARIANT_ZERO,
    OVERFLOW,
}

data class RecipeValidationResult(
    val valid: Boolean,
    val errors: List<RecipeValidationErrorCode>,
)

data class RecipeScaleResult(
    val success: Boolean,
    val scaled: RecipeCanonicalTriple?,
    val errors: List<RecipeScaleErrorCode>,
)

object RecipeCanonical {
    const val CANONICAL_PREFIX = "v1"
    const val BASE_DRINK_VOLUME_ML_MIN = 100
    const val BASE_DRINK_VOLUME_ML_MAX = 1000
    const val WATER_DECI_ML_MAX = 100_000
    const val PRODUCT_DECI_ML_MAX = 10_000

    private val canonicalFieldOrder =
        listOf("baseDrinkVolumeMl", "productDeciMl", "waterDeciMl")

    fun mlToDeciMl(ml: Double): Int = (ml * 10.0).roundToInt()

    fun deciMlToMl(deciMl: Int): Double = deciMl / 10.0

    fun buildCanonicalString(triple: RecipeCanonicalTriple): String {
        val parts =
            canonicalFieldOrder.map { field ->
                when (field) {
                    "baseDrinkVolumeMl" -> "baseDrinkVolumeMl=${triple.baseDrinkVolumeMl}"
                    "productDeciMl" -> "productDeciMl=${triple.productDeciMl}"
                    "waterDeciMl" -> "waterDeciMl=${triple.waterDeciMl}"
                    else -> error("Unknown field $field")
                }
            }
        return "$CANONICAL_PREFIX|${parts.joinToString("|")}"
    }

    fun computeFingerprint(triple: RecipeCanonicalTriple): String {
        val canonical = buildCanonicalString(triple)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(canonical.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun validate(triple: RecipeCanonicalTriple): RecipeValidationResult {
        val errors = mutableListOf<RecipeValidationErrorCode>()

        if (triple.baseDrinkVolumeMl < BASE_DRINK_VOLUME_ML_MIN) {
            errors += RecipeValidationErrorCode.BASE_DRINK_VOLUME_ML_MIN
        } else if (triple.baseDrinkVolumeMl > BASE_DRINK_VOLUME_ML_MAX) {
            errors += RecipeValidationErrorCode.BASE_DRINK_VOLUME_ML_MAX
        }

        if (triple.waterDeciMl < 0) {
            errors += RecipeValidationErrorCode.WATER_DECI_ML_MIN
        } else if (triple.waterDeciMl > WATER_DECI_ML_MAX) {
            errors += RecipeValidationErrorCode.WATER_DECI_ML_MAX
        }

        if (triple.productDeciMl < 0) {
            errors += RecipeValidationErrorCode.PRODUCT_DECI_ML_MIN
        } else if (triple.productDeciMl > PRODUCT_DECI_ML_MAX) {
            errors += RecipeValidationErrorCode.PRODUCT_DECI_ML_MAX
        }

        val componentSum = triple.waterDeciMl + triple.productDeciMl
        val expectedSum = triple.baseDrinkVolumeMl * 10
        if (componentSum != expectedSum) {
            errors += RecipeValidationErrorCode.INVARIANT_SUM
        }
        if (componentSum <= 0) {
            errors += RecipeValidationErrorCode.INVARIANT_ZERO
        }

        return RecipeValidationResult(valid = errors.isEmpty(), errors = errors.toList())
    }

    fun assertValid(triple: RecipeCanonicalTriple): RecipeCanonicalTriple {
        val result = validate(triple)
        check(result.valid) {
            "Invalid recipe triple: ${result.errors.joinToString()}"
        }
        return triple
    }

    fun fingerprint(triple: RecipeCanonicalTriple): String =
        computeFingerprint(assertValid(triple))

    /**
     * Deterministic integer scale (architecture §3.3). Half-up rounding via
     * `(numerator + denominator / 2) / denominator`. Sum invariant may differ
     * by ±1 deci — display/preview only; base identity unchanged.
     */
    fun scaleRecipeDeci(
        base: RecipeCanonicalTriple,
        targetDrinkVolumeMl: Int,
    ): RecipeScaleResult {
        val baseValidation = validate(base)
        if (!baseValidation.valid) {
            return RecipeScaleResult(
                success = false,
                scaled = null,
                errors = listOf(RecipeScaleErrorCode.BASE_INVALID),
            )
        }

        val errors = mutableListOf<RecipeScaleErrorCode>()
        if (targetDrinkVolumeMl < BASE_DRINK_VOLUME_ML_MIN) {
            errors += RecipeScaleErrorCode.TARGET_BASE_DRINK_VOLUME_ML_MIN
        } else if (targetDrinkVolumeMl > BASE_DRINK_VOLUME_ML_MAX) {
            errors += RecipeScaleErrorCode.TARGET_BASE_DRINK_VOLUME_ML_MAX
        }
        if (errors.isNotEmpty()) {
            return RecipeScaleResult(success = false, scaled = null, errors = errors.toList())
        }

        val ratioDen = base.baseDrinkVolumeMl
        val waterNumerator = base.waterDeciMl.toLong() * targetDrinkVolumeMl
        val productNumerator = base.productDeciMl.toLong() * targetDrinkVolumeMl
        if (
            waterNumerator > Int.MAX_VALUE.toLong() * ratioDen ||
                productNumerator > Int.MAX_VALUE.toLong() * ratioDen
        ) {
            return RecipeScaleResult(
                success = false,
                scaled = null,
                errors = listOf(RecipeScaleErrorCode.OVERFLOW),
            )
        }

        val waterDeci = roundHalfUp(waterNumerator, ratioDen)
        val productDeci = roundHalfUp(productNumerator, ratioDen)

        if (waterDeci > WATER_DECI_ML_MAX) {
            errors += RecipeScaleErrorCode.WATER_DECI_ML_MAX
        }
        if (productDeci > PRODUCT_DECI_ML_MAX) {
            errors += RecipeScaleErrorCode.PRODUCT_DECI_ML_MAX
        }
        if (waterDeci + productDeci <= 0) {
            errors += RecipeScaleErrorCode.INVARIANT_ZERO
        }
        if (errors.isNotEmpty()) {
            return RecipeScaleResult(success = false, scaled = null, errors = errors.toList())
        }

        return RecipeScaleResult(
            success = true,
            scaled =
                RecipeCanonicalTriple(
                    baseDrinkVolumeMl = targetDrinkVolumeMl,
                    waterDeciMl = waterDeci,
                    productDeciMl = productDeci,
                ),
            errors = emptyList(),
        )
    }

    /** Half-up integer division: `(numerator + denominator / 2) / denominator`. */
    internal fun roundHalfUp(numerator: Long, denominator: Int): Int {
        require(denominator > 0) { "denominator must be positive" }
        return ((numerator + denominator / 2) / denominator).toInt()
    }
}
