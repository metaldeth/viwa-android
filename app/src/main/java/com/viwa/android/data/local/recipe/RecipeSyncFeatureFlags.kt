package com.viwa.android.data.local.recipe

/** Local feature gate for managed recipe sync (architecture §11.4). Server hello must also advertise capability. */
object RecipeSyncFeatureFlags {
    const val FEATURE_RECIPE_SYNC: Boolean = false

    /**
     * Phase C pour gate (architecture rollout step 7). Keep `false` during report-only (step 5–6).
     * Requires [FEATURE_RECIPE_SYNC], negotiated hello capability, and managed uplink gate active.
     */
    const val FEATURE_RECIPE_POUR_FROM_EFFECTIVE: Boolean = false
}
