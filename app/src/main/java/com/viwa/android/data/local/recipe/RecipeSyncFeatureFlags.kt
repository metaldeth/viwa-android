package com.viwa.android.data.local.recipe

/** Local feature gate for managed recipe sync (architecture §11.4). Server hello must also advertise capability. */
object RecipeSyncFeatureFlags {
    /** Report/commands/service UI — requires server FEATURE_RECIPE_SYNC + hello recipeSync. */
    const val FEATURE_RECIPE_SYNC: Boolean = true

    /**
     * Phase C pour gate: pour uses CellEffectiveRecipeStore when managed gate active.
     * Requires [FEATURE_RECIPE_SYNC], negotiated hello capability, and managed uplink gate active.
     */
    const val FEATURE_RECIPE_POUR_FROM_EFFECTIVE: Boolean = true
}
