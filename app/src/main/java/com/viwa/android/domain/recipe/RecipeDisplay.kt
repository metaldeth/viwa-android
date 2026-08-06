package com.viwa.android.domain.recipe

import androidx.compose.runtime.Immutable

/** Display-only recipe (REST/web/Android UI) — not fingerprint identity. */
@Immutable
data class RecipeDisplay(
    val baseDrinkVolumeMl: Int,
    val waterMl: Double,
    val productMl: Double,
)

fun RecipeCanonicalTriple.toRecipeDisplay(): RecipeDisplay =
    RecipeDisplay(
        baseDrinkVolumeMl = baseDrinkVolumeMl,
        waterMl = RecipeCanonical.deciMlToMl(waterDeciMl),
        productMl = RecipeCanonical.deciMlToMl(productDeciMl),
    )
