package com.viwa.android.data.local.outbox

/** Local feature gate for REST outbox batch (ADR-010). Server hello must also advertise capability. */
object OutboxFeatureFlags {
    const val FEATURE_OUTBOX_REST_SYNC: Boolean = false
}
