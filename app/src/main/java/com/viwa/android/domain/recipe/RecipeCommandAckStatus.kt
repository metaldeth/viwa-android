package com.viwa.android.domain.recipe

/** Terminal ack status values on wire (contract §8.4.3). */
object RecipeCommandAckStatus {
    const val APPLIED = "applied"
    const val SKIPPED_DIVERGED = "skipped_diverged"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val SUPERSEDED = "superseded"
}
